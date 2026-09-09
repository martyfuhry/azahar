// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils

import android.content.Context
import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.R
import org.citra.citra_emu.features.settings.model.AbstractBooleanSetting
import org.citra.citra_emu.features.settings.model.AbstractIntSetting
import org.citra.citra_emu.features.settings.model.AbstractSetting
import org.citra.citra_emu.features.settings.model.BooleanSetting
import org.citra.citra_emu.features.settings.model.IntSetting
import org.citra.citra_emu.features.settings.model.Settings

/**
 * The four things a player can pick on the Graphics screen. Ordinals are the persisted values
 * and must match `R.array.graphicsPresetValues`.
 */
enum class GraphicsPreset(
    val value: Int,
    @StringRes val titleId: Int,
    @StringRes val summaryId: Int
) {
    BATTERY_SAVER(0, R.string.graphics_preset_battery, R.string.graphics_preset_battery_summary),
    BALANCED(1, R.string.graphics_preset_balanced, R.string.graphics_preset_balanced_summary),
    BEST_LOOKING(2, R.string.graphics_preset_best, R.string.graphics_preset_best_summary),
    CUSTOM(3, R.string.graphics_preset_custom, R.string.graphics_preset_custom_summary);

    companion object {
        /** The three presets that actually write settings, in the order the picker shows them. */
        val choices = listOf(BATTERY_SAVER, BALANCED, BEST_LOOKING, CUSTOM)

        fun from(value: Int): GraphicsPreset =
            values().firstOrNull { it.value == value } ?: CUSTOM
    }
}

/**
 * One picker at the top of the Graphics screen that stands in for the handful of graphics
 * settings that actually change what a game looks like or how fast it runs, so that playing a
 * 3DS game well does not require knowing what xBRZ is.
 *
 * ## What the presets change, and what they deliberately do not
 *
 * Only **one** setting genuinely trades quality against speed on this hardware: the internal
 * resolution. Its cost is quadratic — every render target and every depth buffer is
 * `factor x factor` times as many pixels — so 3x is 56% of the fragment work of 4x and 2x is
 * 25% of it. That is the whole of the difference between the three presets, and pretending
 * otherwise by scattering unrelated toggles across them would be dishonest.
 *
 * The rest of the entries are the same in all three: they are settings that have a right
 * answer on an Adreno handheld, and picking any preset repairs them if they were changed.
 *
 * - `texture_filter` stays **off** in every preset. A filter does not make a 3DS game render at
 *   a higher resolution — it upscales the artwork the game supplies — and it is expensive in a
 *   way that is easy to miss: with any filter selected,
 *   `rasterizer_cache.h` (`params.res_scale = filter != NoFilter ? resolution_scale_factor : 1`)
 *   allocates every *sampled* texture at the render scale as well, so at 4x each texture costs
 *   sixteen times its normal video memory and every upload runs an extra render pass. The
 *   emulator also still has open crash reports against the scaled-texture path.
 * - `texture_sampling` stays **Game controlled**. Overriding it makes some games look wrong and
 *   fixes nothing.
 * - `use_hw_shader` stays **on** in every preset, Battery saver included. It is not a
 *   quality/speed trade: with it off, `pica_core.cpp` refuses to set `accelerate_draw`, so the
 *   3DS' vertex shaders are interpreted on the CPU and the whole geometry pipeline is emulated
 *   in software instead of being handed to the GPU. That is slower *and* hotter *and* worse for
 *   battery, so there is no tier that wants it off. It is listed here because it is not
 *   theoretical: Marty's Thor was found with `use_hw_shader = false` written into `config.ini`
 *   alongside `resolution_factor = 4`, which is the worst combination available — see
 *   `docs/fork/baselines/2026-09-09-thor-diagnostics.md` §7. Nothing in this fork writes that
 *   value, so it was set by hand; a preset is now how it gets put back.
 * - `async_shader_compilation` and `use_disk_shader_cache` stay **on**: they are the difference
 *   between a smooth first hour and a stutter every time a new effect appears, and they cost
 *   nothing but a moment of pop-in.
 * - `shaders_accurate_mul` stays **on**. Android's config reader defaults it off
 *   (`jni/config.cpp`) while the desktop default is on; with it off, Pokémon X/Y renders some
 *   surfaces in the wrong colour on Adreno. It costs a little shader throughput and buys
 *   correctness the player cannot diagnose.
 * - `filter_mode` (the final scale to the panel) stays **on**, because no preset renders at
 *   exactly the panel's pixel count.
 *
 * ## Why the resolution is computed rather than hardcoded
 *
 * The 3DS top screen is 400x240. A display can only show what it has pixels for, so the useful
 * render scale is set by the panel: fitting 400x240 into the panel at its own aspect ratio, the
 * largest scale that is not simply thrown away is `min(long / 400, short / 240)`.
 *
 * On the AYN Thor's 1080x1920 top panel that is `min(4.8, 4.5)` = 4.5, so 4x is the largest
 * whole factor that still lands inside the panel (and its 1080x1240 bottom panel saturates at
 * 3.4x, i.e. 4x already oversamples it). On a 720p handheld the same arithmetic gives 3x. The
 * factor is therefore derived from the display rather than written down, and capped at
 * [MAX_USEFUL_SCALE] because past that point the extra pixels are invisible on any panel a 3DS
 * emulator is held in front of while the memory cost keeps growing.
 */
object GraphicsPresets {
    /** `Core::kScreenTopWidth` / `kScreenTopHeight` — the 3DS top screen, in its own pixels. */
    private const val TOP_SCREEN_WIDTH = 400f
    private const val TOP_SCREEN_HEIGHT = 240f

    /**
     * The ceiling for "Best looking". 4x is 1600x960 for the top screen, which is more pixels
     * than any handheld panel shows; going higher costs memory and frame time for nothing.
     */
    private const val MAX_USEFUL_SCALE = 4

    /** Used when neither the display nor the resources can be queried at all. */
    private const val FALLBACK_SCALE = 2

    // Values that have no Kotlin enum to name them; see src/common/settings.h.
    private const val TEXTURE_FILTER_NONE = 0
    private const val TEXTURE_SAMPLING_GAME_CONTROLLED = 0

    private const val UNSET = -1

    private val preferences
        get() = PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)

    /**
     * The largest whole internal resolution factor this display can actually show, read from the
     * panel rather than from a device list. See the class comment for the arithmetic.
     */
    val nativeScale: Int
        get() {
            val panel = panelPixels() ?: return FALLBACK_SCALE
            val (long, short) = panel
            val fit = minOf(long / TOP_SCREEN_WIDTH, short / TOP_SCREEN_HEIGHT)
            return fit.toInt().coerceIn(1, MAX_USEFUL_SCALE)
        }

    /** The internal resolution factor [preset] uses on this device. */
    fun resolutionFor(preset: GraphicsPreset): Int {
        val best = nativeScale
        return when (preset) {
            GraphicsPreset.BEST_LOOKING -> best
            GraphicsPreset.BALANCED -> (best - 1).coerceAtLeast(1)
            GraphicsPreset.BATTERY_SAVER -> (best - 2).coerceAtLeast(1)
            GraphicsPreset.CUSTOM -> IntSetting.RESOLUTION_FACTOR.int
        }
    }

    /**
     * The settings [preset] owns, with the value it gives each one. Every entry here is also a
     * setting shown further down the Graphics screen, so the player can always see what the
     * preset did and change it — which is what flips the preset to Custom.
     */
    private fun valuesFor(preset: GraphicsPreset): List<Pair<AbstractSetting, Any>> = listOf(
        IntSetting.RESOLUTION_FACTOR to resolutionFor(preset),
        IntSetting.TEXTURE_FILTER to TEXTURE_FILTER_NONE,
        IntSetting.TEXTURE_SAMPLING to TEXTURE_SAMPLING_GAME_CONTROLLED,
        BooleanSetting.HW_SHADER to true,
        BooleanSetting.ASYNC_SHADERS to true,
        BooleanSetting.DISK_SHADER_CACHE to true,
        BooleanSetting.SHADERS_ACCURATE_MUL to true,
        BooleanSetting.LINEAR_FILTERING to true
    )

    /** [preset] as a profile the shared [SettingsProfile] writer understands. */
    fun profileFor(preset: GraphicsPreset): List<ProfileEntry> =
        valuesFor(preset).map { (setting, value) -> ProfileEntry(setting) { set(setting, value) } }

    /** True when every setting [preset] owns already holds the preset's value. */
    fun matches(preset: GraphicsPreset): Boolean =
        preset != GraphicsPreset.CUSTOM && valuesFor(preset).all { (s, v) -> holds(s, v) }

    /**
     * The preset in force. Nothing stored yet means nothing has been picked and nothing has been
     * hand-edited either, so name the preset the current configuration already matches rather
     * than calling a stock setup "Custom".
     */
    var active: GraphicsPreset
        get() {
            val stored = preferences.getInt(Settings.PREF_GRAPHICS_PRESET, UNSET)
            if (stored != UNSET) {
                return GraphicsPreset.from(stored)
            }
            // Best first: on a low-resolution panel two presets can land on the same factor,
            // and the better-looking name is the one to show.
            return listOf(
                GraphicsPreset.BEST_LOOKING,
                GraphicsPreset.BALANCED,
                GraphicsPreset.BATTERY_SAVER
            ).firstOrNull { matches(it) } ?: GraphicsPreset.CUSTOM
        }
        set(value) {
            preferences.edit().putInt(Settings.PREF_GRAPHICS_PRESET, value.value).apply()
        }

    /**
     * Writes [preset] and remembers it. Returns the number of settings changed.
     *
     * Custom is not a configuration, it is the absence of one: selecting it only stops the
     * preset from claiming credit for whatever the player has set by hand.
     */
    fun apply(settings: Settings, preset: GraphicsPreset): Int {
        if (preset == GraphicsPreset.CUSTOM) {
            active = preset
            return 0
        }
        val applied = SettingsProfile.write(settings, profileFor(preset), force = true)
        active = preset
        Log.info("[GraphicsPresets] Applied $preset: ${applied.joinToString(", ")}")
        return applied.size
    }

    /**
     * Called from the settings screen after the player edits a setting by hand. If the edit
     * moved a setting the active preset owns away from the preset's value, the configuration is
     * no longer that preset and the picker has to say so.
     */
    fun onSettingEdited(setting: AbstractSetting) {
        val preset = active
        if (preset == GraphicsPreset.CUSTOM) {
            return
        }
        val key = setting.key ?: return
        val owned = valuesFor(preset).firstOrNull { it.first.key == key } ?: return
        if (holds(owned.first, owned.second)) {
            return
        }
        active = GraphicsPreset.CUSTOM
    }

    /** The one-line explanation shown under [preset] in the picker, in plain language. */
    fun summaryFor(context: Context, preset: GraphicsPreset): String = when (preset) {
        GraphicsPreset.CUSTOM -> context.getString(preset.summaryId)
        else -> context.getString(preset.summaryId, resolutionFor(preset))
    }

    /**
     * The display's own pixel count as (long side, short side), independent of how the device is
     * being held. `Display.getMode()` reports the panel, not the current window, which is what
     * the resolution arithmetic wants; the display metrics are a fallback for the case where no
     * display manager is reachable (unit tests, an odd OEM build).
     */
    private fun panelPixels(): Pair<Float, Float>? {
        val fromDisplay = try {
            val manager = CitraApplication.appContext
                .getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            manager.getDisplay(Display.DEFAULT_DISPLAY)?.mode
                ?.let { Pair(it.physicalWidth, it.physicalHeight) }
        } catch (e: Exception) {
            Log.error("[GraphicsPresets] Could not read the display size: ${e.message}")
            null
        }
        val metrics = Resources.getSystem().displayMetrics
        val (width, height) = fromDisplay
            ?.takeIf { it.first > 0 && it.second > 0 }
            ?: Pair(metrics.widthPixels, metrics.heightPixels)
        if (width <= 0 || height <= 0) {
            return null
        }
        return Pair(maxOf(width, height).toFloat(), minOf(width, height).toFloat())
    }

    private fun set(setting: AbstractSetting, value: Any) {
        when {
            setting is AbstractIntSetting && value is Int -> setting.int = value
            setting is AbstractBooleanSetting && value is Boolean -> setting.boolean = value
            else -> Log.error("[GraphicsPresets] Cannot write ${setting.key} = $value")
        }
    }

    private fun holds(setting: AbstractSetting, value: Any): Boolean = when {
        setting is AbstractIntSetting && value is Int -> setting.int == value
        setting is AbstractBooleanSetting && value is Boolean -> setting.boolean == value
        else -> false
    }
}
