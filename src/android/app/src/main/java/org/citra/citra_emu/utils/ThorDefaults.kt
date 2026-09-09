// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils

import android.os.Build
import androidx.preference.PreferenceManager
import org.citra.citra_emu.BuildConfig
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.display.ScreenLayout
import org.citra.citra_emu.display.SecondaryDisplayLayout
import org.citra.citra_emu.features.settings.model.BooleanSetting
import org.citra.citra_emu.features.settings.model.IntSetting
import org.citra.citra_emu.features.settings.model.Settings

/**
 * The out-of-the-box configuration for the AYN Thor, whose two built-in panels (6" 1080x1920
 * on top, 3.92" 1080x1240 on the bottom) map one-to-one onto the 3DS' two screens. Without it
 * a fresh install boots both 3DS screens onto the top panel at 1x, which is exactly the
 * hand-configuration this fork exists to remove.
 *
 * The profile is written through [SettingsProfile], the same path the graphics presets use,
 * so the values are indistinguishable from ones the user picked in the
 * settings menu and are picked up by [org.citra.citra_emu.display.SecondaryDisplay] the next
 * time an EmulationActivity loads settings. It is therefore applied at application/first-run
 * time, before any emulation starts, rather than from inside a running game.
 *
 * It is applied at most once per install ([PREF_APPLIED]), and on that pass it only writes
 * keys that config.ini does not already carry a value for, so an existing configuration is
 * never rewritten. [apply] with `force` set is the "Thor defaults" settings action, which is
 * an explicit request to overwrite.
 *
 * Running once is right for what is left here, which is **taste**: the screen layout, the
 * resolution, autosave. Running once was badly wrong for the settings that have a right answer,
 * because it meant an opinion formed after a device's first launch could never reach it — which
 * is how `use_hw_shader = false` survived for months. Those settings belong to
 * [SettingsRepair], which runs on every launch and knows the difference between a value it wrote
 * and a value somebody chose.
 */
object ThorDefaults {
    private const val PREF_APPLIED = "thor_defaults_applied"

    /**
     * Hardware this profile is meant for, matched case-insensitively against
     * [Build.MANUFACTURER] **and** [Build.MODEL]. Both have to match; extend the list rather
     * than loosening the match, because the profile turns the second screen on and quadruples
     * the render resolution, which is wrong for a phone.
     *
     * The strings come from a read-only `getprop` snapshot of Marty's own Thor, recorded in
     * `docs/fork/baselines/2026-09-09-thor-device-snapshot.md`:
     * `ro.product.manufacturer=AYN`, `ro.product.model=AYN Thor`, Android 13 / SDK 33,
     * `ro.soc.model=QCS8550`. Two properties look like they would work and do not:
     *
     *  - **`Build.BRAND` is `qti`, not `AYN`.** The device ships Qualcomm's reference brand,
     *    so any match on `ro.product.brand` fails on the real hardware.
     *  - **`Build.DEVICE` (and `ro.product.name`) is `kalama`**, which is Qualcomm's platform
     *    name for the SM8550 and is shared with every other reference-derived SM8550 product.
     *    Matching it alone would enable a two-panel, 4x profile on unrelated phones, so it is
     *    deliberately not consulted here.
     *
     * This is only the fallback path: a `thor` flavour build is a Thor by construction (see
     * [BuildUtil.isThorBuild]). The allowlist exists so a vanilla build running on the real
     * hardware still gets sane defaults.
     */
    private val knownDevices = listOf(
        KnownDevice(manufacturer = "ayn", model = "ayn thor")
    )

    private data class KnownDevice(val manufacturer: String, val model: String)

    /**
     * The profile. Values that already match the stock default are still listed: the point is
     * that a Thor is configured the same way whatever the upstream defaults happen to be, and
     * the "Thor defaults" action has to be able to put them back.
     */
    private fun profile(): List<ProfileEntry> = listOf(
        // Two panels, one 3DS screen each, top on top.
        ProfileEntry(BooleanSetting.ENABLE_SECONDARY_DISPLAY) {
            BooleanSetting.ENABLE_SECONDARY_DISPLAY.boolean = true
        },
        ProfileEntry(IntSetting.SCREEN_LAYOUT) {
            IntSetting.SCREEN_LAYOUT.int = ScreenLayout.SINGLE_SCREEN.int
        },
        ProfileEntry(IntSetting.SECONDARY_DISPLAY_LAYOUT) {
            IntSetting.SECONDARY_DISPLAY_LAYOUT.int = SecondaryDisplayLayout.REVERSE_PRIMARY.int
        },
        ProfileEntry(BooleanSetting.SWAP_SCREEN) {
            BooleanSetting.SWAP_SCREEN.boolean = false
        },
        // Vulkan on the Adreno 740.
        ProfileEntry(IntSetting.GRAPHICS_API) {
            IntSetting.GRAPHICS_API.int = GRAPHICS_API_VULKAN
        },
        ProfileEntry(BooleanSetting.VSYNC) {
            BooleanSetting.VSYNC.boolean = false
        },
        ProfileEntry(BooleanSetting.USE_FRAME_LIMIT) {
            BooleanSetting.USE_FRAME_LIMIT.boolean = true
        },
        ProfileEntry(IntSetting.FRAME_LIMIT) {
            IntSetting.FRAME_LIMIT.int = FRAME_LIMIT
        },
        ProfileEntry(IntSetting.CPU_CLOCK_SPEED) {
            IntSetting.CPU_CLOCK_SPEED.int = CPU_CLOCK_PERCENTAGE
        },
        // Audio.
        ProfileEntry(BooleanSetting.ENABLE_AUDIO_STRETCHING) {
            BooleanSetting.ENABLE_AUDIO_STRETCHING.boolean = true
        },
        // Lifecycle: the lid and the AYN launcher background the app constantly, so the
        // autosave has to come back without asking. Perf logging stays off.
        ProfileEntry(IntSetting.AUTOSAVE_MODE) {
            IntSetting.AUTOSAVE_MODE.int = AUTOSAVE_MODE_ALWAYS
        },
        // A kill during play is the case the pause-time autosave cannot cover, so the profile
        // takes one every few minutes as well.
        ProfileEntry(IntSetting.AUTOSAVE_INTERVAL) {
            IntSetting.AUTOSAVE_INTERVAL.int = AUTOSAVE_INTERVAL_MINUTES
        },
        ProfileEntry(IntSetting.PERF_LOG_INTERVAL) {
            IntSetting.PERF_LOG_INTERVAL.int = 0
        }
        // Everything that decides how a game looks comes from the graphics preset instead, so
        // there is one definition of "4x, no texture filter, shader caches on" and it works out
        // the resolution from the panel rather than assuming a Thor-sized one.
    ) + GraphicsPresets.profileFor(GraphicsPreset.BEST_LOOKING)

    // Values that have no Kotlin enum to name them; see src/common/settings.h.
    private const val GRAPHICS_API_VULKAN = 2
    private const val AUTOSAVE_MODE_ALWAYS = 2
    private const val AUTOSAVE_INTERVAL_MINUTES = 5
    private const val FRAME_LIMIT = 100
    private const val CPU_CLOCK_PERCENTAGE = 100

    /** True when this build or this hardware is a Thor, i.e. the profile is meaningful here. */
    val isThor: Boolean
        get() = BuildUtil.isThorBuild || isThorHardware

    private val isThorHardware: Boolean
        get() {
            val manufacturer = Build.MANUFACTURER.lowercase()
            val model = Build.MODEL.lowercase()
            // Manufacturer AND model, both. `contains` on the model rather than an exact
            // compare so a later "AYN Thor <something>" still matches; `Build.DEVICE` is
            // intentionally absent, see [knownDevices].
            return knownDevices.any {
                manufacturer.contains(it.manufacturer) && model.contains(it.model)
            }
        }

    /** Whether the first-run pass has already run on this install. */
    val hasBeenApplied: Boolean
        get() = PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
            .getBoolean(PREF_APPLIED, false)

    /**
     * Applies the profile once per install, on a Thor, filling in only the settings the user
     * has not set. Safe and cheap to call repeatedly; it needs the user directory to be ready
     * (config.ini has to exist) and does nothing otherwise.
     */
    fun applyOnFirstRun() {
        if (!isThor || hasBeenApplied || !DirectoryInitialization.areCitraDirectoriesReady()) {
            return
        }
        // This runs from Application.onCreate and from the setup flow, where a missing or
        // unreadable config.ini must not be fatal: a wrong default is a nuisance, a crash
        // before the first activity is not recoverable from the device.
        try {
            apply(Settings(), force = false)
            // Only now, and only here. The marker means "the first-run pass has happened", so it
            // is set when the pass completes and not when it merely starts: it used to be written
            // unconditionally from inside apply(), which meant a pass that threw part-way through
            // could never be retried and a config the pass never actually saw was recorded as
            // done. What the marker still guards is taste, which is set once by design; the
            // settings that have a right answer are [SettingsRepair]'s job on every launch.
            markApplied()
        } catch (e: Exception) {
            Log.error("[ThorDefaults] Could not apply the first-run profile: ${e.message}")
        }
    }

    /** Records that the first-run pass has happened, so taste is never written over again. */
    fun markApplied() {
        PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
            .edit()
            .putBoolean(PREF_APPLIED, true)
            .apply()
    }

    /**
     * Writes the profile to config.ini and returns the number of settings changed.
     *
     * With [force] off, settings config.ini already carries a value for are left alone. With
     * [force] on (the settings action) every value in the profile is written. Marking the pass as
     * done is the caller's job — see [applyOnFirstRun] and [markApplied] — so that a pass which
     * never ran is never recorded as having run.
     */
    fun apply(settings: Settings, force: Boolean): Int {
        val applied = SettingsProfile.write(settings, profile(), force)

        // Claim the preset only if the configuration really is it: on the non-forcing first-run
        // pass some graphics keys may have been left alone because config.ini already had them.
        if (GraphicsPresets.matches(GraphicsPreset.BEST_LOOKING)) {
            GraphicsPresets.active = GraphicsPreset.BEST_LOOKING
        }

        val how = if (force) "on request" else "on first run"
        val changed = if (applied.isEmpty()) "nothing to change" else applied.joinToString(", ")
        Log.info(
            "[ThorDefaults] Applied the AYN Thor profile $how (flavor=${BuildConfig.FLAVOR}, " +
                "${Build.MANUFACTURER} ${Build.MODEL} / ${Build.DEVICE}): $changed"
        )
        return applied.size
    }
}
