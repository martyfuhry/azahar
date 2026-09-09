// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils.repair

import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.nio.charset.StandardCharsets
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.utils.DirectoryInitialization
import org.citra.citra_emu.utils.Log

/**
 * Reads and writes [ForkProfileRecord] as `fork-profile.json` in the config directory, beside
 * config.ini, so the record lives exactly as long as the file it describes.
 *
 * The write is done through a pending file that is renamed into place, so a process killed
 * part-way through never leaves a half-written record where a whole one used to be. If the kill
 * lands between the two steps the pending file is still complete and is read on the way back in.
 */
object ForkProfileStore {
    private const val CONFIG_DIRECTORY = "config"
    private const val FILE_NAME = "fork-profile.json"
    private const val PENDING_FILE_NAME = "fork-profile.pending.json"
    private const val MIME_TYPE = "application/json"

    private val resolver get() = CitraApplication.appContext.contentResolver

    /** The record on disk, or null when there is none, it is unreadable, or it is not ours. */
    fun load(): ForkProfileRecord? {
        val directory = configDirectory() ?: return null
        // The finished record first; the pending one only if a write was interrupted before the
        // rename, in which case it is the newer and equally complete of the two.
        for (name in listOf(FILE_NAME, PENDING_FILE_NAME)) {
            val file = directory.findFile(name) ?: continue
            val text = readText(file) ?: continue
            val record = ForkProfileRecord.parse(text)
            if (record != null) {
                if (name == PENDING_FILE_NAME) {
                    Log.warning(
                        "[SettingsRepair] Recovered the provenance record from an interrupted " +
                            "write; the previous run was killed before it could be renamed."
                    )
                }
                return record
            }
            Log.error("[SettingsRepair] $name is not a readable provenance record; ignoring it.")
        }
        return null
    }

    /**
     * Writes [record], replacing whatever is there. Returns false if it could not be stored, in
     * which case the next pass simply re-derives provenance from config.ini.
     */
    fun save(record: ForkProfileRecord): Boolean {
        val directory = configDirectory() ?: return false
        return try {
            val pending = directory.findFile(PENDING_FILE_NAME)
                ?: directory.createFile(MIME_TYPE, PENDING_FILE_NAME)
                ?: return false
            resolver.openOutputStream(pending.uri, "wt")?.use {
                it.write(record.encode().toByteArray(StandardCharsets.UTF_8))
                it.flush()
            } ?: return false

            // Only now is the old record expendable.
            directory.findFile(FILE_NAME)?.delete()
            val renamed: Uri? =
                DocumentsContract.renameDocument(resolver, pending.uri, FILE_NAME)
            if (renamed == null && directory.findFile(FILE_NAME) == null) {
                // The rename failed and there is no finished record: the pending file is still
                // complete and load() will pick it up, so this is recoverable rather than lost.
                Log.error("[SettingsRepair] Could not rename the provenance record into place.")
                return false
            }
            true
        } catch (e: Exception) {
            Log.error("[SettingsRepair] Could not write the provenance record: ${e.message}")
            false
        }
    }

    /** Removes the record entirely. Used by the full "Apply Thor defaults" reset. */
    fun clear() {
        val directory = configDirectory() ?: return
        try {
            directory.findFile(FILE_NAME)?.delete()
            directory.findFile(PENDING_FILE_NAME)?.delete()
        } catch (e: Exception) {
            Log.error("[SettingsRepair] Could not clear the provenance record: ${e.message}")
        }
    }

    private fun configDirectory(): DocumentFile? = try {
        val userDirectory = DirectoryInitialization.userDirectory
        if (userDirectory.isNullOrEmpty()) {
            null
        } else {
            DocumentFile.fromTreeUri(CitraApplication.appContext, Uri.parse(userDirectory))
                ?.findFile(CONFIG_DIRECTORY)
        }
    } catch (e: Exception) {
        Log.error("[SettingsRepair] Could not reach the config directory: ${e.message}")
        null
    }

    private fun readText(file: DocumentFile): String? = try {
        resolver.openInputStream(file.uri)?.use {
            String(it.readBytes(), StandardCharsets.UTF_8)
        }
    } catch (e: Exception) {
        Log.error("[SettingsRepair] Could not read ${file.name}: ${e.message}")
        null
    }
}
