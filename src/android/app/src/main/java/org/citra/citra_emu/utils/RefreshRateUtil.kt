// Copyright 2025-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils
import android.app.Activity
import android.app.Presentation
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.Window
import kotlin.math.abs

object RefreshRateUtil {
    /** The 3DS runs at 60 Hz; that is the only rate the emulator ever wants. */
    const val TARGET_REFRESH_RATE = 60f

    /**
     * Panels routinely advertise 60.000004 Hz (and 59.94 Hz), so an exact float compare against
     * 60f misses the mode we are looking for and leaves the display running at 120 Hz.
     */
    private const val REFRESH_RATE_EPSILON = 0.5f

    // Since Android 15, the OS automatically runs apps categorized as games with a
    // 60hz refresh rate by default, regardless of the refresh rate set by the user.
    //
    // This function sets the refresh rate to either the maximum allowed refresh rate or
    // 60hz depending on the value of the `sixtyHz` parameter.
    //
    // Note: This isn't always the maximum refresh rate that the display is *capable of*,
    // but is instead the refresh rate chosen by the user in the Android system settings.
    // For example, if the user selected 120hz in the settings, but the display is capable
    // of 144hz, 120hz will be treated as the maximum within this function.
    fun enforceRefreshRate(activity: Activity, sixtyHz: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }
        applyPreferredMode(activity.window, activity.display, sixtyHz)
    }

    /**
     * The dual-screen path: the bottom panel lives in a [Presentation], which has a window and a
     * display of its own and so never saw any of this before. Call it from the Presentation's
     * onCreate, before the content view is set.
     */
    fun enforceRefreshRate(presentation: Presentation, sixtyHz: Boolean = true) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }
        applyPreferredMode(presentation.window, presentation.display, sixtyHz)
    }

    /**
     * Asks SurfaceFlinger to run this surface's layer at [rate]. This is the per-layer hint that
     * `preferredDisplayModeId` cannot give: it tells the compositor the buffer cadence is fixed
     * at the source, so it neither picks a refresh rate that needs frames duplicated unevenly
     * nor treats a 60 Hz producer on a 120 Hz panel as a dropped-frame problem. Applied to both
     * the primary SurfaceView and the Presentation's.
     */
    fun requestSurfaceFrameRate(surface: Surface?, rate: Float = TARGET_REFRESH_RATE) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }
        if (surface == null || !surface.isValid) {
            return
        }
        try {
            surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        } catch (e: IllegalArgumentException) {
            Log.warning("[RefreshRateUtil] Could not request ${rate}hz for surface: ${e.message}")
        } catch (_: IllegalStateException) {
            Log.warning("[RefreshRateUtil] Surface went away before the frame rate was set")
        }
    }

    private fun applyPreferredMode(window: Window?, display: Display?, sixtyHz: Boolean) {
        if (window == null || display == null) {
            return
        }

        val supportedModes = display.supportedModes
        if (supportedModes.isEmpty()) {
            return
        }

        // Only ever consider modes with the resolution the panel is already in. A mode id
        // carries a resolution as well as a refresh rate, so picking "the 60hz mode" out of the
        // whole list can silently drop the panel to a lower resolution - on the Thor's 120hz
        // 1080x1920 top screen, the 60hz entry need not be the 1080p one.
        val currentMode = display.mode
        val sameResolution = supportedModes.filter {
            it.physicalWidth == currentMode.physicalWidth &&
                it.physicalHeight == currentMode.physicalHeight
        }

        val candidates = sameResolution.ifEmpty { supportedModes.toList() }
        val newMode = if (sixtyHz) {
            candidates.firstOrNull {
                abs(it.refreshRate - TARGET_REFRESH_RATE) < REFRESH_RATE_EPSILON
            }
        } else {
            candidates.maxByOrNull { it.refreshRate }
        } ?: return

        if (window.attributes.preferredDisplayModeId == newMode.modeId) {
            return
        }

        // WindowManager.LayoutParams read back from a window is a live copy, not the window's
        // own object, so mutating it does nothing until it is assigned back. Without this the
        // whole function was a no-op except when it happened to run before the window was
        // attached (which is why enforceRefreshRate had to be the first thing in onCreate).
        val lp = window.attributes
        lp.preferredDisplayModeId = newMode.modeId
        window.attributes = lp
    }
}
