// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils

import org.citra.citra_emu.features.settings.model.AbstractSetting
import org.citra.citra_emu.features.settings.model.Settings
import org.citra.citra_emu.features.settings.utils.SettingsFile

/**
 * A single setting a canned profile owns, paired with the write that gives it the profile's
 * value. The setting is the same [AbstractSetting] singleton the settings UI edits, so writing
 * it here and writing it from the menu are indistinguishable afterwards.
 */
class ProfileEntry(val setting: AbstractSetting, val write: () -> Unit)

/**
 * The one path by which a canned profile — [ThorDefaults] at first run, [GraphicsPresets] when
 * the player picks a preset — puts values into config.ini.
 *
 * It writes each entry twice over, on purpose: [ProfileEntry.write] mutates the setting object
 * the settings screen reads (so the menu shows the new value immediately, and so a later
 * [Settings.saveSettings] on activity exit writes the new value rather than the one it loaded),
 * and [Settings.saveSetting] puts the same value straight into the ini so it survives even if
 * the process never reaches a clean exit.
 */
object SettingsProfile {
    /**
     * Writes [entries] and returns a `key=value` line for each one written.
     *
     * With [force] off, keys config.ini already carries a value for are left alone — that is
     * the "fill in what the user has not decided" pass. With [force] on every entry is written.
     */
    fun write(settings: Settings, entries: List<ProfileEntry>, force: Boolean): List<String> {
        val existing = if (force) {
            null
        } else {
            // Also syncs the in-memory settings with the file, which nothing has read yet on
            // the first-run path.
            SettingsFile.readFile(SettingsFile.FILE_NAME_CONFIG)
        }

        val applied = mutableListOf<String>()
        for (entry in entries) {
            val key = entry.setting.key ?: continue
            val section = entry.setting.section ?: continue
            if (existing != null && existing[section]?.getSetting(key) != null) {
                continue
            }
            entry.write()
            settings.saveSetting(entry.setting, SettingsFile.FILE_NAME_CONFIG)
            applied.add("$key=${entry.setting.valueAsString}")
        }
        return applied
    }
}
