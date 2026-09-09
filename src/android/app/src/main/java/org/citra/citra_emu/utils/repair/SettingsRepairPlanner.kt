// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils.repair

/**
 * Which claim this fork is making about a setting. The tier is a statement about *the setting*,
 * not about the person using it.
 *
 * See `docs/fork/settings-audit.md` §5 for the reasoning and for the tier lists themselves.
 */
enum class RepairTier(val number: Int) {
    /**
     * Tier 1 — wrong. Repaired on every launch, even over a change the user made deliberately.
     *
     * Two tests both have to pass: the effect is large enough to feel, and there is genuinely no
     * hardware, game or person for which the other value is right. Two entries that are obviously
     * right beat a longer list that argues, so this tier is deliberately tiny.
     */
    CORRECTNESS(1),

    /**
     * Tier 2 — we know better, but a reasonable person could differ. Repaired subject to
     * provenance: we only move a value that is still ours, or that the user has demonstrably
     * never touched. See [SettingsRepairPlanner.plan].
     */
    OPINION(2),

    /**
     * Tier 3 — genuine taste. Written once by the first-run profile and never touched again by
     * the repair pass, whatever it is set to.
     */
    TASTE(3)
}

/** Why a change happened, so that a repaired setting is never a mystery. */
enum class RepairRule {
    /** Tier 1: the value was not the correct one. Provenance does not apply. */
    TIER1_ALWAYS,

    /** Tier 2: the live value is the one we last wrote, so it is still ours to update. */
    TIER2_STILL_OURS,

    /** Tier 2: the live value is the upstream default, so nobody has ever chosen it. */
    TIER2_NEVER_TOUCHED
}

/**
 * One setting the repair pass has an opinion about.
 *
 * [upstreamDefault] is the value the emulator actually uses on Android when the key is blank —
 * which is what a blank or absent key means, and is not always what `settings.h` says, because
 * `jni/config.cpp` overrides some fallbacks. It is read from the setting's own declared default
 * rather than written down here, so the two cannot drift apart.
 */
data class RepairEntry(
    val key: String,
    val tier: RepairTier,
    val opinion: String,
    val upstreamDefault: String,
    val why: String
)

/** A single write the pass wants to make, with everything needed to explain or undo it. */
data class RepairChange(
    val key: String,
    val from: String,
    val to: String,
    val tier: RepairTier,
    val rule: RepairRule,
    val why: String
)

/**
 * What a pass decided: the writes to make, and the provenance to record afterwards.
 *
 * [owned] and [ceded] are the complete new state of the record, not a delta.
 */
data class RepairOutcome(
    val changes: List<RepairChange>,
    val owned: Map<String, String>,
    val ceded: Set<String>
)

/**
 * The repair pass itself: a pure function of the live configuration and the provenance record.
 *
 * It is deliberately free of Android — no settings singletons, no files, no context — so that the
 * rule that decides whether to overwrite somebody's configuration can be tested exhaustively
 * without a device.
 */
object SettingsRepairPlanner {
    /**
     * Works out what to change.
     *
     * @param entries the tier tables; see `SettingsRepairProfile`.
     * @param live the value config.ini currently carries for a key, or null when the key is blank
     *   or absent — which is the same thing, and means the emulator is using its own default.
     * @param record what we last wrote, or null when there is no record: a first run, a wiped
     *   sidecar, or a config that predates the record entirely. A null record is not a reason to
     *   do nothing and not a licence to overwrite; see the no-provenance rule below.
     *
     * **The provenance rule.** For a tier-2 key we only move a value we still own:
     *
     *  - the live value is what we last wrote → it is ours, so update it to the current opinion;
     *  - the live value differs from what we last wrote → the user moved it, so cede it
     *    permanently and never touch that key again.
     *
     * **The no-provenance rule**, for a tier-2 key we have no record for — the first pass on an
     * existing install, or an opinion we have only just formed. There is no history to consult,
     * so the config file is read as its own evidence:
     *
     *  - it already equals our opinion → nothing to do, and it is ours from now on;
     *  - it equals the upstream default → nobody has ever chosen it, so apply our opinion;
     *  - it is neither → somebody deliberately moved it off the default, so cede it permanently.
     *
     * The last row is why a genuinely wrong value like `use_hw_shader = false` cannot be fixed by
     * tier 2: it is neither our opinion nor the default, so tier 2 would cede it. That is not a
     * gap. A setting that has to be repaired over a deliberate choice is the definition of tier 1
     * and belongs there — wanting to force something out of tier 2 is a signal it is mis-tiered,
     * not that the rule is too weak.
     *
     * The pass is idempotent: applying [RepairOutcome.changes] and running again produces none.
     */
    fun plan(
        entries: List<RepairEntry>,
        live: (String) -> String?,
        record: ForkProfileRecord?
    ): RepairOutcome {
        val owned = LinkedHashMap(record?.owned ?: emptyMap())
        val ceded = LinkedHashSet(record?.ceded ?: emptyList())
        val changes = mutableListOf<RepairChange>()

        for (entry in entries) {
            // A blank key is not a value: the emulator falls back to its own default, so that is
            // what the user is actually running and what we have to compare against.
            val effective = live(entry.key) ?: entry.upstreamDefault

            when (entry.tier) {
                // Never touched, whatever it says. Taste is set once by the first-run profile.
                RepairTier.TASTE -> continue

                RepairTier.CORRECTNESS -> {
                    if (effective != entry.opinion) {
                        changes += RepairChange(
                            key = entry.key,
                            from = effective,
                            to = entry.opinion,
                            tier = entry.tier,
                            rule = RepairRule.TIER1_ALWAYS,
                            why = entry.why
                        )
                    }
                }

                RepairTier.OPINION -> {
                    if (ceded.contains(entry.key)) {
                        continue
                    }
                    val lastWritten = owned[entry.key]
                    if (lastWritten != null) {
                        if (effective == lastWritten) {
                            if (effective != entry.opinion) {
                                changes += RepairChange(
                                    key = entry.key,
                                    from = effective,
                                    to = entry.opinion,
                                    tier = entry.tier,
                                    rule = RepairRule.TIER2_STILL_OURS,
                                    why = entry.why
                                )
                            }
                            owned[entry.key] = entry.opinion
                        } else {
                            // They moved it after we wrote it. It is theirs now, for good.
                            owned.remove(entry.key)
                            ceded.add(entry.key)
                        }
                    } else {
                        when {
                            effective == entry.opinion -> owned[entry.key] = entry.opinion

                            effective == entry.upstreamDefault -> {
                                changes += RepairChange(
                                    key = entry.key,
                                    from = effective,
                                    to = entry.opinion,
                                    tier = entry.tier,
                                    rule = RepairRule.TIER2_NEVER_TOUCHED,
                                    why = entry.why
                                )
                                owned[entry.key] = entry.opinion
                            }

                            else -> ceded.add(entry.key)
                        }
                    }
                }
            }
        }

        return RepairOutcome(changes = changes, owned = owned, ceded = ceded)
    }
}
