// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils

import androidx.preference.PreferenceManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.citra.citra_emu.BuildConfig
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.features.settings.model.AbstractBooleanSetting
import org.citra.citra_emu.features.settings.model.AbstractIntSetting
import org.citra.citra_emu.features.settings.model.AbstractSetting
import org.citra.citra_emu.features.settings.model.Settings
import org.citra.citra_emu.features.settings.utils.SettingsFile
import org.citra.citra_emu.utils.repair.ForkProfileChange
import org.citra.citra_emu.utils.repair.ForkProfileRecord
import org.citra.citra_emu.utils.repair.ForkProfileRepair
import org.citra.citra_emu.utils.repair.ForkProfileStore
import org.citra.citra_emu.utils.repair.RepairChange
import org.citra.citra_emu.utils.repair.RepairTier
import org.citra.citra_emu.utils.repair.SettingsRepairPlanner
import org.citra.citra_emu.utils.repair.SettingsRepairProfile

/**
 * Puts settings back that should never have been wrong, on every launch.
 *
 * ## Why this exists
 *
 * `use_hw_shader = false` sat in Marty's config.ini for months next to `resolution_factor = 4` —
 * software vertex shading at the resolution where it costs the most — and survived every upgrade.
 * It survived because the first-run profile ran exactly once per install, against a profile that
 * predated the opinion, set its one-shot flag whether or not it wrote anything, and skipped any
 * key config.ini already had a value for. A wrong value was therefore permanent by construction,
 * and every opinion the fork formed afterwards could never reach the device it was formed for.
 *
 * This pass is the fix for that class of problem rather than for that one setting. It runs on
 * every launch, it is a pure function of the configuration and a provenance record so it can be
 * reasoned about and tested, and it distinguishes three kinds of claim — see [RepairTier].
 *
 * ## What makes it safe to run against somebody's real configuration
 *
 *  - **It is idempotent.** Once a pass has run, the next one has nothing to do; the second run of
 *    the planner over its own output is empty by construction.
 *  - **It cedes rather than clobbers.** A tier-2 value that is neither ours nor the untouched
 *    default is a decision somebody made, and is given up permanently the moment it is seen.
 *  - **Only tier 1 overrides a decision**, that is exactly two settings, and there is a single
 *    switch that turns even that off.
 *  - **Settings are written before the record.** If the process dies between the two, the next
 *    pass sees a value it has no record of and cedes it — the pessimistic direction. Nothing we
 *    can be killed in the middle of can produce a wrong write.
 *  - **A missing or corrupt record is not a failure**, it just re-derives provenance from
 *    config.ini, which is what the very first pass does anyway.
 */
object SettingsRepair {
    /**
     * What the last pass changed, for the notice on the game list. Read and cleared by whoever
     * shows it; a pass that changed nothing leaves it empty and is completely silent.
     */
    @Volatile
    var pendingNotice: List<RepairChange> = emptyList()
        private set

    /** Preference key mirroring [isOptedOut], so the opt-out cannot be lost silently. */
    private const val PREF_OPT_OUT = "SettingsRepair_OptOut"

    private val prefs
        get() = PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)

    /**
     * Whether the user has asked to be left alone entirely.
     *
     * Recorded in *both* the sidecar beside config.ini and a preference, and read as the OR of
     * the two. Everything else this object stores is provenance, which has to outlive an
     * uninstall and therefore belongs next to the configuration it describes - but this is a
     * decision about the app, and it is the only escape from a pass that deliberately overrides
     * explicit choices. Every way the sidecar can fail - an unwritable directory, a rename that
     * does not take, a record left unparseable or written by a newer build - reads back as
     * `false`, which fails *open*: tier 1 would resume rewriting settings on every launch while
     * the switch showed "off". A preference cannot fix an unwritable sidecar, but it fails in
     * different circumstances, and agreeing to be left alone should not depend on the one store
     * that can silently go away.
     */
    var isOptedOut: Boolean
        get() = prefs.getBoolean(PREF_OPT_OUT, false) || (ForkProfileStore.load()?.optOut ?: false)
        set(value) {
            // The preference first: it is the store that cannot fail quietly, so if the sidecar
            // write below is lost the user's decision still holds.
            prefs.edit().putBoolean(PREF_OPT_OUT, value).apply()
            val record = ForkProfileStore.load() ?: ForkProfileRecord()
            if (!ForkProfileStore.save(record.copy(optOut = value))) {
                Log.warning(
                    "[SettingsRepair] Could not record the opt-out beside config.ini; it is held " +
                        "in preferences only and will not survive a reinstall."
                )
            }
            Log.info("[SettingsRepair] Setting repair is now ${if (value) "off" else "on"}")
        }

    /**
     * Runs a pass. Safe and cheap to call on every launch; it needs the user directory to be
     * ready and does nothing otherwise.
     */
    fun run() {
        if (!DirectoryInitialization.areCitraDirectoriesReady()) {
            return
        }
        // This runs from Application.onCreate and from the setup flow, where an unreadable
        // config.ini must not be fatal: a wrong setting is a nuisance, a crash before the first
        // activity is not recoverable from the device.
        try {
            repair()
        } catch (e: Exception) {
            Log.error("[SettingsRepair] The settings repair pass failed: ${e.message}")
        }
    }

    private fun repair() {
        val configFile = try {
            SettingsFile.getSettingsFile(SettingsFile.FILE_NAME_CONFIG)
        } catch (e: Exception) {
            null
        }
        if (configFile == null || !configFile.exists()) {
            // An absent config.ini is indistinguishable from one where every key is blank, and
            // acting on that would write a profile against a file that does not exist yet.
            Log.warning("[SettingsRepair] No config.ini yet; nothing to repair.")
            return
        }

        val record = ForkProfileStore.load()
        // Deliberately not the isOptedOut getter: the record is already in hand, and this runs
        // before any activity exists, where a second trip through the storage provider is not free.
        val optedOut = prefs.getBoolean(PREF_OPT_OUT, false) || record?.optOut == true
        if (optedOut) {
            Log.info("[SettingsRepair] Skipped: settings are being kept exactly as they are set.")
            return
        }

        // Also syncs the settings singletons with the file, so a value written below starts from
        // what is actually on disk rather than from a stale in-memory copy.
        val sections = SettingsFile.readFile(SettingsFile.FILE_NAME_CONFIG)
        val entries = SettingsRepairProfile.entries()
        val outcome = SettingsRepairPlanner.plan(
            entries = entries,
            live = { key ->
                val setting = SettingsRepairProfile.settingFor(key)
                if (setting == null) {
                    Log.error("[SettingsRepair] $key is not a setting this build knows about")
                    null
                } else {
                    // A key with no value is a key nobody has ever decided, which is how an
                    // absent one reads too; SettingsFile drops blank values for us.
                    sections[setting.section]?.getSetting(key)?.valueAsString
                }
            },
            record = record
        )

        val settings = Settings()
        val written = outcome.changes.filter { apply(settings, it) }
        for (change in written) {
            Log.info(
                "[SettingsRepair] ${change.key}: ${change.from} -> ${change.to} " +
                    "(tier ${change.tier.number}, ${change.rule}) ${change.why}"
            )
        }
        for (key in outcome.ceded - (record?.ceded?.toSet() ?: emptySet())) {
            Log.info(
                "[SettingsRepair] $key was changed deliberately, so it is the user's from now " +
                    "on and will not be repaired again."
            )
        }

        val updated = ForkProfileRecord(
            lastAppliedVersionCode = BuildConfig.VERSION_CODE,
            lastAppliedVersionName = BuildConfig.VERSION_NAME,
            // Not `record?.optOut`: a record that went missing or unparseable would otherwise
            // erase a decision the preference still remembers, permanently.
            optOut = optedOut,
            owned = outcome.owned,
            ceded = outcome.ceded.toList(),
            lastRepair = if (written.isEmpty()) {
                record?.lastRepair
            } else {
                ForkProfileRepair(
                    at = timestamp(),
                    fromVersionCode = record?.lastAppliedVersionCode ?: 0,
                    changed = written.map {
                        ForkProfileChange(
                            key = it.key,
                            from = it.from,
                            to = it.to,
                            tier = it.tier.number,
                            rule = it.rule.name,
                            why = it.why
                        )
                    }
                )
            }
        )
        // The steady state is a pass that found nothing and has nothing new to say, which is
        // almost every launch. Writing an identical record there would cost two storage round
        // trips on the boot path for no information.
        if (updated != record) {
            // The record goes down after the settings, never before: a kill between the two costs
            // a key its provenance, which cedes it, and never overwrites a value.
            ForkProfileStore.save(updated)
        }

        pendingNotice = written
        if (written.isEmpty()) {
            Log.debug("[SettingsRepair] Nothing to repair.")
        }
    }

    /**
     * Puts the values from [changes] back, and gives up every tier-2 key among them for good.
     *
     * Tier 1 is deliberately not waived here: those two settings are repaired over any change,
     * including this one, so an undone tier-1 repair comes back on the next launch. The switch in
     * Settings is the way to stop that, and the details dialog says so.
     */
    fun undo(changes: List<RepairChange>) {
        if (changes.isEmpty()) {
            return
        }
        val settings = Settings()
        for (change in changes) {
            val restored = RepairChange(
                key = change.key,
                from = change.to,
                to = change.from,
                tier = change.tier,
                rule = change.rule,
                why = "Undone by the user."
            )
            if (apply(settings, restored)) {
                Log.info(
                    "[SettingsRepair] Undone: ${change.key}: ${change.to} -> ${change.from}"
                )
            }
        }
        val record = ForkProfileStore.load() ?: ForkProfileRecord()
        val cededNow = changes.filter { it.tier == RepairTier.OPINION }.map { it.key }
        ForkProfileStore.save(
            record.copy(
                owned = record.owned - cededNow.toSet(),
                ceded = (record.ceded + cededNow).distinct(),
                lastRepair = null
            )
        )
        pendingNotice = emptyList()
    }

    /** Discards the notice without undoing anything. */
    fun dismissNotice() {
        pendingNotice = emptyList()
    }

    /**
     * Called after the explicit "Apply Thor defaults" reset, which writes the full profile
     * including taste. Everything in the file is ours again at that point, so the record says so:
     * previously ceded keys come back under our care, because the user has just asked for exactly
     * that.
     */
    fun onFullProfileApplied() {
        val entries = SettingsRepairProfile.entries()
            .filter { it.tier == RepairTier.OPINION }
        val record = ForkProfileStore.load() ?: ForkProfileRecord()
        ForkProfileStore.save(
            record.copy(
                lastAppliedVersionCode = BuildConfig.VERSION_CODE,
                lastAppliedVersionName = BuildConfig.VERSION_NAME,
                owned = entries.associate { it.key to it.opinion },
                ceded = emptyList(),
                lastRepair = null
            )
        )
        pendingNotice = emptyList()
        Log.info("[SettingsRepair] The full profile was applied; provenance reset to match it.")
    }

    private fun apply(settings: Settings, change: RepairChange): Boolean {
        val setting: AbstractSetting = SettingsRepairProfile.settingFor(change.key) ?: return false
        when {
            setting is AbstractBooleanSetting -> setting.boolean = change.to.toBoolean()

            setting is AbstractIntSetting -> {
                setting.int = change.to.toIntOrNull() ?: return false
            }

            else -> return false
        }
        settings.saveSetting(setting, SettingsFile.FILE_NAME_CONFIG)
        return true
    }

    private fun timestamp(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }
}
