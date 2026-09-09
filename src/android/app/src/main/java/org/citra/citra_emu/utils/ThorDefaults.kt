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
import org.citra.citra_emu.features.settings.model.AbstractSetting
import org.citra.citra_emu.features.settings.model.BooleanSetting
import org.citra.citra_emu.features.settings.model.IntSetting
import org.citra.citra_emu.features.settings.model.Settings
import org.citra.citra_emu.features.settings.utils.SettingsFile

/**
 * The out-of-the-box configuration for the AYN Thor, whose two built-in panels (6" 1080x1920
 * on top, 3.92" 1080x1240 on the bottom) map one-to-one onto the 3DS' two screens. Without it
 * a fresh install boots both 3DS screens onto the top panel at 1x, which is exactly the
 * hand-configuration this fork exists to remove.
 *
 * The profile is written through the normal settings path ([Settings.saveSetting], i.e. the
 * config.ini writer), so the values are indistinguishable from ones the user picked in the
 * settings menu and are picked up by [org.citra.citra_emu.display.SecondaryDisplay] the next
 * time an EmulationActivity loads settings. It is therefore applied at application/first-run
 * time, before any emulation starts, rather than from inside a running game.
 *
 * It is applied at most once per install ([PREF_APPLIED]), and on that pass it only writes
 * keys that config.ini does not already carry a value for, so an existing configuration is
 * never rewritten. [apply] with `force` set is the "Thor defaults" settings action, which is
 * an explicit request to overwrite.
 */
object ThorDefaults {
    private const val PREF_APPLIED = "thor_defaults_applied"

    /**
     * Hardware this profile is meant for, matched case-insensitively against
     * [Build.MANUFACTURER] plus either [Build.MODEL] or [Build.DEVICE]. Extend the list rather
     * than loosening the match: the profile turns the second screen on and quadruples the
     * render resolution, which is wrong for a phone.
     */
    private val knownDevices = listOf(
        KnownDevice(manufacturer = "ayn", model = "thor")
    )

    private data class KnownDevice(val manufacturer: String, val model: String)

    /** A single setting the profile owns, paired with the write that sets its Thor value. */
    private class ProfileEntry(val setting: AbstractSetting, val write: () -> Unit)

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
        // Graphics: Vulkan on the Adreno 740, 4x internal resolution, both shader caches on.
        ProfileEntry(IntSetting.GRAPHICS_API) {
            IntSetting.GRAPHICS_API.int = GRAPHICS_API_VULKAN
        },
        ProfileEntry(IntSetting.RESOLUTION_FACTOR) {
            IntSetting.RESOLUTION_FACTOR.int = RESOLUTION_FACTOR
        },
        ProfileEntry(BooleanSetting.DISK_SHADER_CACHE) {
            BooleanSetting.DISK_SHADER_CACHE.boolean = true
        },
        ProfileEntry(BooleanSetting.ASYNC_SHADERS) {
            BooleanSetting.ASYNC_SHADERS.boolean = true
        },
        // No texture filter: every filter multiplies sampled-texture memory by the square of
        // the resolution factor, and 4x is already the memory ceiling on the 8 GB SKU.
        ProfileEntry(IntSetting.TEXTURE_FILTER) {
            IntSetting.TEXTURE_FILTER.int = TEXTURE_FILTER_NONE
        },
        ProfileEntry(IntSetting.TEXTURE_SAMPLING) {
            IntSetting.TEXTURE_SAMPLING.int = TEXTURE_SAMPLING_GAME_CONTROLLED
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
        ProfileEntry(IntSetting.PERF_LOG_INTERVAL) {
            IntSetting.PERF_LOG_INTERVAL.int = 0
        }
    )

    // Values that have no Kotlin enum to name them; see src/common/settings.h.
    private const val GRAPHICS_API_VULKAN = 2
    private const val TEXTURE_FILTER_NONE = 0
    private const val TEXTURE_SAMPLING_GAME_CONTROLLED = 0
    private const val AUTOSAVE_MODE_ALWAYS = 2
    private const val RESOLUTION_FACTOR = 4
    private const val FRAME_LIMIT = 100
    private const val CPU_CLOCK_PERCENTAGE = 100

    /** True when this build or this hardware is a Thor, i.e. the profile is meaningful here. */
    val isThor: Boolean
        get() = BuildUtil.isThorBuild || isThorHardware

    private val isThorHardware: Boolean
        get() {
            val manufacturer = Build.MANUFACTURER.lowercase()
            val model = Build.MODEL.lowercase()
            val device = Build.DEVICE.lowercase()
            return knownDevices.any {
                manufacturer.contains(it.manufacturer) &&
                    (model.contains(it.model) || device.contains(it.model))
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
        } catch (e: Exception) {
            Log.error("[ThorDefaults] Could not apply the first-run profile: ${e.message}")
        }
    }

    /**
     * Writes the profile to config.ini and returns the number of settings changed.
     *
     * With [force] off, settings config.ini already carries a value for are left alone and the
     * "applied" marker is set whatever happens, so the pass runs at most once. With [force] on
     * (the settings action) every value in the profile is written.
     */
    fun apply(settings: Settings, force: Boolean): Int {
        val existing = if (force) {
            null
        } else {
            // Also syncs the in-memory settings with the file, which nothing has read yet on
            // the first-run path.
            SettingsFile.readFile(SettingsFile.FILE_NAME_CONFIG)
        }

        val applied = mutableListOf<String>()
        for (entry in profile()) {
            val key = entry.setting.key ?: continue
            val section = entry.setting.section ?: continue
            if (existing != null && existing[section]?.getSetting(key) != null) {
                continue
            }
            entry.write()
            settings.saveSetting(entry.setting, SettingsFile.FILE_NAME_CONFIG)
            applied.add("$key=${entry.setting.valueAsString}")
        }

        PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
            .edit()
            .putBoolean(PREF_APPLIED, true)
            .apply()

        val how = if (force) "on request" else "on first run"
        val changed = if (applied.isEmpty()) "nothing to change" else applied.joinToString(", ")
        Log.info(
            "[ThorDefaults] Applied the AYN Thor profile $how (flavor=${BuildConfig.FLAVOR}, " +
                "${Build.MANUFACTURER} ${Build.MODEL} / ${Build.DEVICE}): $changed"
        )
        return applied.size
    }
}
