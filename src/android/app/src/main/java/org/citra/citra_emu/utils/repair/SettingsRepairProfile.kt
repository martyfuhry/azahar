// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils.repair

import org.citra.citra_emu.features.settings.model.AbstractSetting
import org.citra.citra_emu.features.settings.model.BooleanSetting
import org.citra.citra_emu.features.settings.model.IntSetting
import org.citra.citra_emu.utils.Log

/**
 * The tier tables: which settings this fork claims to know better about, and how strongly.
 *
 * The assignments come from `docs/fork/settings-audit.md` §5 and are not invented here. Adding an
 * entry is a claim about the setting, so it wants the same two arguments the audit uses: what the
 * value buys, and what the legitimate other side is.
 *
 * The upstream default of each entry is read from the setting's own [AbstractSetting.defaultValue]
 * rather than written down a second time. For every key listed here that value is also what the
 * emulator actually uses on Android when the key is blank — `jni/config.cpp` reads all of them
 * through `ReadSetting`, which passes the shared default straight through. That was not true of
 * `shaders_accurate_mul` until the hardcoded `false` fallback beside it was removed.
 */
object SettingsRepairProfile {
    /**
     * Tier 1 — always repaired, even over an explicit change. Both entries fail the "is there
     * anybody for whom the other value is right" test outright: off means the CPU doing work the
     * hardware exists to do, at every resolution and on every device.
     *
     * **This is the whole of tier 1 and it should stay that way.** Anything that needs an argument
     * to belong here belongs in tier 2.
     */
    private fun tier1(): List<RepairEntry> = listOf(
        entry(
            BooleanSetting.HW_SHADER,
            RepairTier.CORRECTNESS,
            true,
            "Hardware shaders were off, so the 3DS geometry pipeline was being interpreted on " +
                "the CPU instead of handed to the GPU: slower, hotter and worse for battery."
        ),
        entry(
            BooleanSetting.CPU_JIT,
            RepairTier.CORRECTNESS,
            true,
            "The CPU recompiler was off, so the ARM11 was being interpreted instruction by " +
                "instruction. Nothing playable runs that way."
        )
    )

    /**
     * Tier 2 — repaired only where the value is still ours or has demonstrably never been chosen.
     * Each of these has a legitimate other side, which is why provenance decides.
     */
    private fun tier2(): List<RepairEntry> = listOf(
        entry(
            BooleanSetting.ASYNC_SHADERS,
            RepairTier.OPINION,
            true,
            "Compiles pipelines on worker threads, trading a moment of pop-in for the stutter " +
                "a cold shader cache otherwise causes on a mobile driver."
        ),
        entry(
            BooleanSetting.DISK_SHADER_CACHE,
            RepairTier.OPINION,
            true,
            "Keeps compiled shaders between sessions, so the second time through an area is " +
                "smooth."
        ),
        entry(
            BooleanSetting.SHADER_JIT,
            RepairTier.OPINION,
            true,
            "Runs the software vertex path through a recompiler rather than an interpreter. " +
                "Same computation, faster engine."
        ),
        entry(
            BooleanSetting.SHADERS_ACCURATE_MUL,
            RepairTier.OPINION,
            true,
            "Emulates the PICA200's multiply rules exactly. Without it some games render in " +
                "the wrong colours — Pokemon X renders a starter Pokemon almost entirely white."
        ),
        entry(
            BooleanSetting.LINEAR_FILTERING,
            RepairTier.OPINION,
            true,
            "Smooths the final scale to the panel, which no internal resolution lands on exactly."
        ),
        entry(
            BooleanSetting.USE_SKIP_DUPLICATE_FRAMES,
            RepairTier.OPINION,
            true,
            "Skips presenting a frame identical to the last one, which is most of them in a " +
                "30 fps title."
        ),
        entry(
            BooleanSetting.SIMULATE_3DS_GPU_TIMINGS,
            RepairTier.OPINION,
            false,
            "A workaround for a handful of specific titles, not a global setting; on, it costs " +
                "frame time everywhere else."
        ),
        entry(
            IntSetting.TEXTURE_FILTER,
            RepairTier.OPINION,
            0,
            "A texture filter upscales the artwork rather than the render resolution, costs " +
                "tens of megabytes of GPU memory, and the scaled-texture path has open Adreno " +
                "crash reports."
        ),
        entry(
            IntSetting.TEXTURE_SAMPLING,
            RepairTier.OPINION,
            0,
            "Letting the game choose its own sampling is right; the overrides make some games " +
                "look wrong and fix nothing."
        ),
        entry(
            IntSetting.DELAY_RENDER_THREAD_US,
            RepairTier.OPINION,
            0,
            "A per-title workaround, not a global setting: any delay here is frame time given " +
                "away."
        )
    )

    /**
     * Tier 3 — genuine taste. These belong to the first-run profile in `ThorDefaults`, and the
     * pass skips every entry in this tier unconditionally.
     *
     * **Listing a key here does not protect it and omitting one does not expose it.** The pass
     * only ever looks at keys in [entries], so everything in the settings screen that is not in
     * tier 1 or tier 2 is already untouched by construction. This list exists so that the
     * boundary is written down in the code as well as in the audit, and so that the immunity can
     * be tested rather than merely asserted. It is representative of the audit's tier 3 rather
     * than exhaustive of it: the float-valued entries (`volume`) and the whole-block entries (the
     * stereoscopy, Cardboard and custom-layout coordinates) are omitted because naming them adds
     * nothing that their absence from tiers 1 and 2 does not already guarantee.
     *
     * The hesitations are recorded in the audit: `use_vsync` is right off on Android but somebody
     * with visible tearing genuinely wants it on; `cpu_clock_percentage` has broken values but
     * overclocking is a legitimate per-game tactic; `graphics_api` is right on Adreno but
     * switching renderers mid-life is a bigger decision than a repair pass should make.
     */
    private fun tier3(): List<RepairEntry> = listOf(
        taste(IntSetting.RESOLUTION_FACTOR),
        taste(IntSetting.SCREEN_LAYOUT),
        taste(IntSetting.SECONDARY_DISPLAY_LAYOUT),
        taste(BooleanSetting.ENABLE_SECONDARY_DISPLAY),
        taste(BooleanSetting.SWAP_SCREEN),
        taste(IntSetting.ORIENTATION_OPTION),
        taste(IntSetting.ASPECT_RATIO),
        taste(BooleanSetting.UPRIGHT_SCREEN),
        taste(IntSetting.SCREEN_GAP),
        taste(BooleanSetting.USE_FRAME_LIMIT),
        taste(IntSetting.FRAME_LIMIT),
        taste(IntSetting.TURBO_LIMIT),
        taste(IntSetting.CPU_CLOCK_SPEED),
        taste(IntSetting.AUTOSAVE_MODE),
        taste(BooleanSetting.ENABLE_AUDIO_STRETCHING),
        taste(BooleanSetting.NEW_3DS),
        taste(IntSetting.EMULATED_REGION),
        taste(BooleanSetting.VSYNC),
        taste(BooleanSetting.USE_INTEGER_SCALING),
        taste(IntSetting.GRAPHICS_API),
        taste(BooleanSetting.PERF_OVERLAY_ENABLE)
    )

    /** Every entry the pass considers, in the order it considers them. */
    fun entries(): List<RepairEntry> = tier1() + tier2() + tier3()

    /** The settings the pass may write, by key, so a change can be applied to the right one. */
    fun settingFor(key: String): AbstractSetting? = BooleanSetting.from(key) ?: IntSetting.from(key)

    private fun entry(
        setting: AbstractSetting,
        tier: RepairTier,
        opinion: Any,
        why: String
    ): RepairEntry {
        if (setting.defaultValue.javaClass != opinion.javaClass) {
            // A typed mistake in the table would otherwise show up as a setting that is repaired
            // on every single launch, forever, because the strings never compare equal.
            Log.error(
                "[SettingsRepair] ${setting.key} has a ${opinion.javaClass.simpleName} opinion " +
                    "but a ${setting.defaultValue.javaClass.simpleName} default"
            )
        }
        return RepairEntry(
            key = setting.key!!,
            tier = tier,
            opinion = opinion.toString(),
            upstreamDefault = setting.defaultValue.toString(),
            why = why
        )
    }

    private fun taste(setting: AbstractSetting): RepairEntry = RepairEntry(
        key = setting.key!!,
        tier = RepairTier.TASTE,
        opinion = setting.defaultValue.toString(),
        upstreamDefault = setting.defaultValue.toString(),
        why = "Taste: set once on first run and never repaired."
    )
}
