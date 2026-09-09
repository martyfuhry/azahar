// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils.repair

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One line of the durable record of a repair, in the same terms the log and the notice use. */
@Serializable
data class ForkProfileChange(
    val key: String,
    val from: String,
    val to: String,
    val tier: Int,
    val rule: String,
    val why: String
)

/**
 * What the last pass that changed anything did. A pass that changed nothing does not replace it.
 */
@Serializable
data class ForkProfileRepair(
    val at: String,
    @SerialName("fromVersionCode") val fromVersionCode: Int,
    val changed: List<ForkProfileChange>
)

/**
 * The provenance record: what this fork last wrote, so that a later pass can tell its own value
 * from one the user chose.
 *
 * **It lives next to config.ini, not in SharedPreferences**, and that is the load-bearing design
 * decision rather than an implementation detail. SharedPreferences is app-private and is wiped on
 * uninstall, while config.ini sits on shared storage and survives uninstall, reinstall and every
 * upgrade. A record with a shorter life than the thing it describes is worse than no record: after
 * a reinstall every tier-2 key would look "not ours", and tier 2 would be frozen for good on
 * exactly the installs that have been around longest.
 *
 * Everything here is optional and everything degrades safely. A missing, truncated or unparseable
 * record is treated as no record at all, which falls back to reading config.ini as its own
 * evidence — the same rule the very first pass uses. That is why a corrupt sidecar can cost at
 * most a re-derivation and can never cause a wrong write.
 */
@Serializable
data class ForkProfileRecord(
    val schema: Int = SCHEMA,
    /** The build that last ran a pass. Only ever informational; the pass is not gated on it. */
    val lastAppliedVersionCode: Int = 0,
    val lastAppliedVersionName: String = "",
    /** "Keep my settings exactly as I set them": disables the whole pass, tier 1 included. */
    val optOut: Boolean = false,
    /** Tier-2 keys and the value we last wrote for each. Absent means we have no history. */
    val owned: Map<String, String> = emptyMap(),
    /** Tier-2 keys the user has moved. Once here, never touched again. */
    val ceded: List<String> = emptyList(),
    val lastRepair: ForkProfileRepair? = null
) {
    companion object {
        const val SCHEMA = 1

        /**
         * Lenient on the way in on purpose: an older or newer build's extra fields must not make
         * the record unreadable, because an unreadable record silently loses provenance.
         */
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        /** Parses [text], or returns null if it is not a record this build understands. */
        fun parse(text: String): ForkProfileRecord? {
            val record = try {
                json.decodeFromString<ForkProfileRecord>(text)
            } catch (e: Exception) {
                return null
            }
            // A record from a future schema describes rules we do not have. Treating it as absent
            // re-derives from config.ini, which is always safe; guessing at it is not.
            return if (record.schema == SCHEMA) record else null
        }
    }

    fun encode(): String = json.encodeToString(ForkProfileRecord.serializer(), this)
}
