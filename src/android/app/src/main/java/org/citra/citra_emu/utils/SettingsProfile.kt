// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils

import java.io.BufferedReader
import java.io.InputStreamReader
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.features.settings.model.AbstractSetting
import org.citra.citra_emu.features.settings.model.CitraSettings
import org.citra.citra_emu.features.settings.utils.SettingsFile

/**
 * A single setting a canned profile owns, paired with the value the profile gives it. The
 * setting is the same [AbstractSetting] descriptor the settings UI edits, so a value written
 * here and one picked from the menu are indistinguishable afterwards.
 */
class ProfileEntry<T>(val setting: AbstractSetting<T>, val value: T) {
    /** True when [settings] already holds this entry's value. */
    fun holds(settings: CitraSettings): Boolean = settings.get(setting) == value

    /** The `key=value` line this entry writes, for the log. */
    fun describe(): String = "${setting.key}=${setting.valueToString(value)}"
}

/**
 * The one path by which a canned profile — [ThorDefaults] at first run, [GraphicsPresets] when
 * the player picks a preset, [SettingsRepair] when it puts a value back — puts values into
 * config.ini.
 *
 * It writes each entry twice over, on purpose: into the [CitraSettings] the caller is editing
 * (so the menu shows the new value immediately, and so the save on activity exit writes the new
 * value rather than the one it loaded), and straight into the ini so it survives even if the
 * process never reaches a clean exit. The live [CitraSettings.activeSettings] are kept in step
 * as well, when they have been loaded, because that is what a running game reads and what the
 * next settings screen copies its values from.
 *
 * Every write is to the **global** scope. The fork has no per-game profiles: a Thor profile or a
 * graphics preset is a statement about the device, not about one title.
 */
object SettingsProfile {
    /**
     * Writes [entries] and returns a `key=value` line for each one written.
     *
     * With [force] off, keys config.ini already carries a value for are left alone — that is
     * the "fill in what the user has not decided" pass. With [force] on every entry is written.
     */
    fun write(
        settings: CitraSettings,
        entries: List<ProfileEntry<*>>,
        force: Boolean
    ): List<String> {
        val existing = if (force) null else readConfig()

        val applied = mutableListOf<String>()
        for (entry in entries) {
            val key = entry.setting.key
            val section = entry.setting.section
            if (existing != null && existing[section]?.containsKey(key) == true) {
                continue
            }
            write(settings, entry)
            applied.add(entry.describe())
        }
        return applied
    }

    /** Writes one entry into [settings], the live settings and config.ini. */
    fun <T> write(settings: CitraSettings, entry: ProfileEntry<T>) {
        settings.setGlobal(entry.setting, entry.value)
        persistGlobal(entry.setting, entry.value)
    }

    /**
     * Puts [value] into config.ini as the global value of [setting], and into the live
     * [CitraSettings.activeSettings] if they have been loaded.
     *
     * The live settings are left alone while they are still empty: an empty active set is how
     * `EmulationActivity` tells that nothing has loaded the file yet, and a single key written
     * into it from `Application.onCreate` would make it skip that load. Whoever loads them next
     * reads the value from the file anyway.
     */
    fun <T> persistGlobal(setting: AbstractSetting<T>, value: T) {
        // A scratch instance with no per-game scope, so SettingsFile.saveSetting always
        // targets config.ini whatever game the caller happens to be editing.
        val scratch = CitraSettings()
        scratch.setGlobal(setting, value)
        SettingsFile.saveSetting(setting, scratch)

        val active = CitraSettings.activeSettings
        if (active.getAllGlobal().isNotEmpty()) {
            active.setGlobal(setting, value)
        }
    }

    /**
     * The raw contents of config.ini as `section -> key -> value`, with blank values dropped the
     * same way [SettingsFile] drops them. An unreadable file reads as empty.
     *
     * This is read raw rather than through [SettingsFile.loadSettings] because the callers need
     * to know whether a key is *present*, not what it would parse to: a key that is in the file
     * is a key somebody decided, and a typed load would fold an absent key and a default one
     * into the same value.
     */
    fun readConfig(): Map<String, Map<String, String>> {
        val sections = HashMap<String, HashMap<String, String>>()
        val ini = try {
            SettingsFile.getSettingsFile(SettingsFile.FILE_NAME_CONFIG)
        } catch (e: Exception) {
            Log.error("[SettingsProfile] No config.ini to read: ${e.message}")
            return sections
        }
        try {
            val inputStream = CitraApplication.appContext.contentResolver.openInputStream(ini.uri)
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var current: HashMap<String, String>? = null
                var line = reader.readLine()
                while (line != null) {
                    if (line.startsWith("[") && line.endsWith("]")) {
                        current = sections.getOrPut(line.substring(1, line.length - 1)) {
                            HashMap()
                        }
                    } else if (current != null) {
                        val split = line.split("=".toRegex(), limit = 2)
                        if (split.size == 2) {
                            val key = split[0].trim()
                            val value = split[1].trim()
                            if (value.isNotEmpty()) {
                                current[key] = value
                            }
                        }
                    }
                    line = reader.readLine()
                }
            }
        } catch (e: Exception) {
            Log.error("[SettingsProfile] Could not read config.ini: ${e.message}")
        }
        return sections
    }
}
