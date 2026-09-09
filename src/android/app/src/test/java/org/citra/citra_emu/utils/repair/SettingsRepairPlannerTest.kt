// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils.repair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repair pass decides whether to overwrite settings in somebody's real config.ini, so the
 * rule that makes that decision is tested exhaustively here rather than on a device.
 *
 * The entries below mirror the real tier tables in `SettingsRepairProfile`, which cannot be built
 * on the JVM because the settings enums resolve their keys through JNI. Where the two could drift
 * apart, [exampleOpinionWeHaveNotShippedYet] is the exception on purpose: every tier-2 opinion in
 * the shipping table currently happens to equal the upstream default, so no shipping entry
 * exercises the branch where our opinion and the default disagree. That branch is the whole point
 * of tier 2 the first time we change our mind about a key we own, so it is tested anyway.
 */
class SettingsRepairPlannerTest {
    private val hwShader = RepairEntry(
        key = "use_hw_shader",
        tier = RepairTier.CORRECTNESS,
        opinion = "true",
        upstreamDefault = "true",
        why = "Hardware shaders were off."
    )

    private val cpuJit = RepairEntry(
        key = "use_cpu_jit",
        tier = RepairTier.CORRECTNESS,
        opinion = "true",
        upstreamDefault = "true",
        why = "The CPU recompiler was off."
    )

    private val textureFilter = RepairEntry(
        key = "texture_filter",
        tier = RepairTier.OPINION,
        opinion = "0",
        upstreamDefault = "0",
        why = "Texture filters cost memory and stability for no render resolution."
    )

    private val accurateMul = RepairEntry(
        key = "shaders_accurate_mul",
        tier = RepairTier.OPINION,
        opinion = "true",
        upstreamDefault = "true",
        why = "Some games render in the wrong colours without it."
    )

    /** A tier-2 key where our opinion is not the upstream default. See the class comment. */
    private val exampleOpinionWeHaveNotShippedYet = RepairEntry(
        key = "use_skip_duplicate_frames",
        tier = RepairTier.OPINION,
        opinion = "false",
        upstreamDefault = "true",
        why = "Hypothetical: suppose we came to prefer the non-default here."
    )

    private val resolutionFactor = RepairEntry(
        key = "resolution_factor",
        tier = RepairTier.TASTE,
        opinion = "4",
        upstreamDefault = "1",
        why = "Taste."
    )

    /** The tiers as they actually ship today. */
    private val shipping = listOf(
        hwShader,
        cpuJit,
        textureFilter,
        accurateMul,
        resolutionFactor
    )

    private val everything = listOf(
        hwShader,
        cpuJit,
        textureFilter,
        accurateMul,
        exampleOpinionWeHaveNotShippedYet,
        resolutionFactor
    )

    private fun config(vararg pairs: Pair<String, String>): (String) -> String? {
        val values = pairs.toMap()
        return { key -> values[key] }
    }

    // ---------------------------------------------------------------- the provenance rule

    @Test
    fun `tier 2 value we last wrote is still ours and is updated to the current opinion`() {
        val record = ForkProfileRecord(
            owned = mapOf(exampleOpinionWeHaveNotShippedYet.key to "true")
        )
        val outcome = SettingsRepairPlanner.plan(
            entries = listOf(exampleOpinionWeHaveNotShippedYet),
            live = config(exampleOpinionWeHaveNotShippedYet.key to "true"),
            record = record
        )

        assertEquals(1, outcome.changes.size)
        val change = outcome.changes.single()
        assertEquals("true", change.from)
        assertEquals("false", change.to)
        assertEquals(RepairRule.TIER2_STILL_OURS, change.rule)
        assertEquals("false", outcome.owned[exampleOpinionWeHaveNotShippedYet.key])
        assertTrue(outcome.ceded.isEmpty())
    }

    @Test
    fun `tier 2 value the user moved after we wrote it is ceded permanently`() {
        val record = ForkProfileRecord(owned = mapOf(textureFilter.key to "0"))
        val outcome = SettingsRepairPlanner.plan(
            entries = listOf(textureFilter),
            live = config(textureFilter.key to "3"),
            record = record
        )

        assertTrue(outcome.changes.isEmpty())
        assertTrue(outcome.ceded.contains(textureFilter.key))
        assertNull(outcome.owned[textureFilter.key])

        // "Permanently" is the claim, so check it survives the value coming back on its own.
        val later = SettingsRepairPlanner.plan(
            entries = listOf(textureFilter),
            live = config(textureFilter.key to "0"),
            record = ForkProfileRecord(owned = outcome.owned, ceded = outcome.ceded.toList())
        )
        assertTrue(later.changes.isEmpty())
        assertTrue(later.ceded.contains(textureFilter.key))
    }

    @Test
    fun `tier 2 value equal to the upstream default has never been chosen so we apply ours`() {
        val outcome = SettingsRepairPlanner.plan(
            entries = listOf(exampleOpinionWeHaveNotShippedYet),
            live = config(exampleOpinionWeHaveNotShippedYet.key to "true"),
            record = null
        )

        val change = outcome.changes.single()
        assertEquals("true", change.from)
        assertEquals("false", change.to)
        assertEquals(RepairRule.TIER2_NEVER_TOUCHED, change.rule)
        assertEquals("false", outcome.owned[exampleOpinionWeHaveNotShippedYet.key])
    }

    @Test
    fun `tier 2 value that is neither ours nor the default is a decision and is ceded`() {
        val outcome = SettingsRepairPlanner.plan(
            entries = listOf(exampleOpinionWeHaveNotShippedYet),
            live = config(exampleOpinionWeHaveNotShippedYet.key to "maybe"),
            record = null
        )

        assertTrue(outcome.changes.isEmpty())
        assertTrue(outcome.ceded.contains(exampleOpinionWeHaveNotShippedYet.key))
    }

    @Test
    fun `tier 2 value that already equals our opinion is recorded without being written`() {
        val outcome = SettingsRepairPlanner.plan(
            entries = listOf(textureFilter),
            live = config(textureFilter.key to "0"),
            record = null
        )

        assertTrue(outcome.changes.isEmpty())
        assertEquals("0", outcome.owned[textureFilter.key])
    }

    @Test
    fun `a blank key reads as the upstream default rather than as no value`() {
        // The audit's shaders_accurate_mul case: the key was blank, so the emulator was using its
        // own fallback and the user had never chosen anything.
        val outcome = SettingsRepairPlanner.plan(
            entries = listOf(exampleOpinionWeHaveNotShippedYet),
            live = config(),
            record = null
        )

        assertEquals(RepairRule.TIER2_NEVER_TOUCHED, outcome.changes.single().rule)
        assertEquals("true", outcome.changes.single().from)
    }

    // ---------------------------------------------------------------- tier 1 and tier 3

    @Test
    fun `tier 1 is repaired over an explicit user change`() {
        // Marty's actual configuration: hardware shaders turned off by hand, months ago.
        val outcome = SettingsRepairPlanner.plan(
            entries = everything,
            live = config(
                hwShader.key to "false",
                cpuJit.key to "true",
                resolutionFactor.key to "4"
            ),
            record = null
        )

        val change = outcome.changes.single { it.key == hwShader.key }
        assertEquals("false", change.from)
        assertEquals("true", change.to)
        assertEquals(RepairTier.CORRECTNESS, change.tier)
        assertEquals(RepairRule.TIER1_ALWAYS, change.rule)
        // Tier 1 does not consult provenance, so it neither claims nor cedes the key.
        assertNull(outcome.owned[hwShader.key])
        assertTrue(!outcome.ceded.contains(hwShader.key))
    }

    @Test
    fun `tier 1 is repaired even when we previously ceded nothing and the record is stale`() {
        val stale = ForkProfileRecord(
            lastAppliedVersionCode = 1,
            owned = mapOf(textureFilter.key to "0"),
            ceded = listOf(accurateMul.key)
        )
        val outcome = SettingsRepairPlanner.plan(
            entries = everything,
            live = config(
                hwShader.key to "false",
                cpuJit.key to "false",
                textureFilter.key to "0",
                accurateMul.key to "false",
                // Already at our opinion, so it contributes nothing and tier 1 stands alone.
                exampleOpinionWeHaveNotShippedYet.key to "false"
            ),
            record = stale
        )

        val repaired = outcome.changes.map { it.key }.toSet()
        assertEquals(setOf(hwShader.key, cpuJit.key), repaired)
        // The ceded tier-2 key stays ceded and untouched, even though it disagrees with us.
        assertTrue(outcome.ceded.contains(accurateMul.key))
    }

    @Test
    fun `tier 1 that is blank and defaults to the right value is left alone`() {
        val outcome = SettingsRepairPlanner.plan(
            entries = listOf(hwShader, cpuJit),
            live = config(),
            record = null
        )

        assertTrue(outcome.changes.isEmpty())
    }

    @Test
    fun `tier 3 is never touched, whatever it is set to`() {
        val outcome = SettingsRepairPlanner.plan(
            entries = everything,
            live = config(
                resolutionFactor.key to "1",
                hwShader.key to "true",
                cpuJit.key to "true"
            ),
            record = null
        )

        assertTrue(outcome.changes.none { it.key == resolutionFactor.key })
        assertNull(outcome.owned[resolutionFactor.key])
        assertTrue(!outcome.ceded.contains(resolutionFactor.key))
    }

    // ---------------------------------------------------------------- whole passes

    @Test
    fun `a first run with no config and no record changes nothing and claims what it can`() {
        val outcome = SettingsRepairPlanner.plan(
            entries = listOf(hwShader, cpuJit, textureFilter, accurateMul, resolutionFactor),
            live = config(),
            record = null
        )

        assertTrue(outcome.changes.isEmpty())
        assertEquals(
            setOf(textureFilter.key, accurateMul.key),
            outcome.owned.keys
        )
        assertTrue(outcome.ceded.isEmpty())
    }

    @Test
    fun `an upgrade with a stale config repairs tier 1, cedes a decision and claims the rest`() {
        // The device this was written for: hardware shaders off by hand, a texture filter chosen
        // deliberately, and everything else inherited.
        val outcome = SettingsRepairPlanner.plan(
            // The shipping tier tables, where every tier-2 opinion currently equals the upstream
            // default; the hypothetical entry is deliberately not in this one.
            entries = shipping,
            live = config(
                hwShader.key to "false",
                textureFilter.key to "3",
                accurateMul.key to "true",
                resolutionFactor.key to "4"
            ),
            record = null
        )

        assertEquals(listOf(hwShader.key), outcome.changes.map { it.key })
        assertTrue(outcome.ceded.contains(textureFilter.key))
        assertEquals("true", outcome.owned[accurateMul.key])
        assertNull(outcome.owned[textureFilter.key])
    }

    @Test
    fun `the pass is idempotent`() {
        val live = mutableMapOf(
            hwShader.key to "false",
            textureFilter.key to "3",
            exampleOpinionWeHaveNotShippedYet.key to "true"
        )
        val first = SettingsRepairPlanner.plan(everything, { live[it] }, null)
        assertTrue(first.changes.isNotEmpty())
        for (change in first.changes) {
            live[change.key] = change.to
        }

        val second = SettingsRepairPlanner.plan(
            everything,
            { live[it] },
            ForkProfileRecord(owned = first.owned, ceded = first.ceded.toList())
        )
        assertTrue(second.changes.isEmpty())
        assertEquals(first.owned, second.owned)
        assertEquals(first.ceded, second.ceded)

        val third = SettingsRepairPlanner.plan(
            everything,
            { live[it] },
            ForkProfileRecord(owned = second.owned, ceded = second.ceded.toList())
        )
        assertTrue(third.changes.isEmpty())
    }

    @Test
    fun `a kill between writing the settings and writing the record cedes rather than clobbers`() {
        // The pass writes settings first and the record second, so the only state a kill can
        // leave behind is a new value with an old record. That must resolve pessimistically.
        val key = exampleOpinionWeHaveNotShippedYet.key
        val recordFromBeforeTheWrite = ForkProfileRecord(owned = mapOf(key to "true"))
        val live = mapOf(key to "false")

        val outcome = SettingsRepairPlanner.plan(
            listOf(exampleOpinionWeHaveNotShippedYet),
            { live[it] },
            recordFromBeforeTheWrite
        )

        assertTrue(outcome.changes.isEmpty())
        assertTrue(outcome.ceded.contains(key))
    }

    @Test
    fun `a missing or corrupt record falls back to reading the config as its own evidence`() {
        // ForkProfileStore hands the planner a null record for a sidecar that is absent,
        // truncated, not JSON, or written by a schema this build does not know. All four are the
        // same case here: no history, so the values decide, and a deliberate one is still ceded.
        assertNull(ForkProfileRecord.parse(""))
        assertNull(ForkProfileRecord.parse("{\"schema\": 1, \"owned\": {"))
        assertNull(ForkProfileRecord.parse("not json at all"))
        assertNull(ForkProfileRecord.parse("{\"schema\": 99}"))

        val outcome = SettingsRepairPlanner.plan(
            entries = shipping,
            live = config(hwShader.key to "false", textureFilter.key to "3"),
            record = ForkProfileRecord.parse("{\"schema\": 99}")
        )

        assertEquals(listOf(hwShader.key), outcome.changes.map { it.key })
        assertTrue(outcome.ceded.contains(textureFilter.key))
    }

    @Test
    fun `a record survives a round trip through the sidecar`() {
        val record = ForkProfileRecord(
            lastAppliedVersionCode = 40000000,
            lastAppliedVersionName = "thor-v1-rc3",
            owned = mapOf(textureFilter.key to "0", accurateMul.key to "true"),
            ceded = listOf(exampleOpinionWeHaveNotShippedYet.key),
            lastRepair = ForkProfileRepair(
                at = "2026-09-09T13:40:00Z",
                fromVersionCode = 33735322,
                changed = listOf(
                    ForkProfileChange(
                        key = hwShader.key,
                        from = "false",
                        to = "true",
                        tier = 1,
                        rule = RepairRule.TIER1_ALWAYS.name,
                        why = hwShader.why
                    )
                )
            )
        )

        assertEquals(record, ForkProfileRecord.parse(record.encode()))
    }

    @Test
    fun `a record from a build with extra fields is still readable`() {
        // An older build must not lose provenance because a newer one wrote a field it does not
        // know: unreadable means "no history", which is the one outcome worth avoiding.
        val text = "{\"schema\": 1, \"owned\": {\"texture_filter\": \"0\"}, \"somethingNew\": 7}"
        val record = ForkProfileRecord.parse(text)

        assertEquals(mapOf("texture_filter" to "0"), record?.owned)
    }
}
