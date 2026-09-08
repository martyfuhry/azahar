// Copyright 2025-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.display

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.features.settings.model.BooleanSetting
import org.citra.citra_emu.features.settings.model.IntSetting
import org.citra.citra_emu.utils.Log

class SecondaryDisplay(val context: Context) : DisplayManager.DisplayListener {
    private var pres: SecondaryDisplayPresentation? = null
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    var preferredDisplayId = -1
    var currentDisplayId = -1

    val availableDisplays: List<Display>
        get() = getSecondaryDisplays()

    init {
        displayManager.registerDisplayListener(this, null)
    }

    fun updateSurface() {
        val surface = pres?.getSurfaceHolder()?.surface
        if (surface != null && surface.isValid) {
            NativeLibrary.secondarySurfaceChanged(surface)
        } else {
            Log.warning("SecondaryDisplay Attempted to update null or invalid surface")
        }
    }

    fun destroySurface() {
        NativeLibrary.secondarySurfaceDestroyed()
    }

    private fun getSecondaryDisplays(): List<Display> {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val currentDisplayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display.displayId
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.displayId
        }
        val displays = dm.displays
        val presDisplays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        return displays.filter {
            val isPresentable = presDisplays.any { pd -> pd.displayId == it.displayId }
            val isNotDefaultOrPresentable =
                (it != null && it.displayId != Display.DEFAULT_DISPLAY) || isPresentable

            isNotDefaultOrPresentable &&
                it.displayId != currentDisplayId &&
                it.state != Display.STATE_OFF &&
                it.isValid
        }
    }

    fun updateDisplay() {
        // return early if the parent context is dead or dying
        if (context is android.app.Activity && (context.isFinishing || context.isDestroyed)) {
            return
        }

        val secondaryWanted = BooleanSetting.ENABLE_SECONDARY_DISPLAY.boolean &&
            // Theoretically, the NONE option is no longer selectable, but
            // I am leaving this in for backwards compatibility
            IntSetting.SECONDARY_DISPLAY_LAYOUT.int != SecondaryDisplayLayout.NONE.int
        val displays = if (secondaryWanted) availableDisplays else emptyList()

        if (displays.isEmpty()) {
            // Nothing to present on. This used to fall back to a hidden 1920x1080
            // VirtualDisplay, which cost the emulation thread a second render and present of
            // every single frame (one fence wait, one fullscreen renderpass, one blit, one
            // queue submit, one queue present) plus a second 1080p swapchain in VRAM, for
            // pixels that no one could ever see. Show no Presentation at all instead; the
            // native side drops its secondary swapchain when the surface goes away and
            // rebuilds it the moment a real display shows up again.
            currentDisplayId = -1
            releasePresentation()
            return
        }

        val displayToUse = if (preferredDisplayId >= 0 &&
            displays.any { it.displayId == preferredDisplayId }
        ) {
            currentDisplayId = preferredDisplayId
            displays.first { it.displayId == preferredDisplayId }
        } else {
            val default = displayManager.displays.first { it.displayId == Display.DEFAULT_DISPLAY }
            // prioritize displays that have a different name from the default display, as
            // some devices such as the Odin 2 create a permanent virtual display with the same
            // name as the default display that should be skipped in most cases
            currentDisplayId = displays.firstOrNull {
                it.name != default.name && !it.name.contains("Built", true)
            }?.displayId
                ?: displays[0].displayId
            displays.first { it.displayId == currentDisplayId }
        }

        // if our presentation is already on the right display, ignore
        if (pres?.display == displayToUse) return

        // otherwise, make a new presentation
        releasePresentation()

        try {
            pres = SecondaryDisplayPresentation(context, displayToUse, this)
            pres?.show()
        }
        // catch BadTokenException and InvalidDisplayException,
        // the display became invalid asynchronously, so we can assign to null
        // until onDisplayAdded/Removed/Changed is called and logic retriggered
        catch (_: WindowManager.BadTokenException) {
            pres = null
        } catch (_: WindowManager.InvalidDisplayException) {
            pres = null
        }
    }

    fun releasePresentation() {
        val hadPresentation = pres != null
        try {
            pres?.dismiss()
        } catch (_: Exception) { }
        pres = null
        // dismiss() detaches the decor view synchronously on the main thread, so the
        // SurfaceHolder callback above normally tells the core already. It does not fire when
        // the Presentation never got as far as showing a surface (the display went away first),
        // and leaving a stale ANativeWindow behind keeps the core rendering a second frame to a
        // window nothing displays. The native call is a no-op once the surface is released.
        if (hadPresentation) {
            NativeLibrary.secondarySurfaceDestroyed()
        }
    }

    fun release() {
        displayManager.unregisterDisplayListener(this)
    }

    override fun onDisplayAdded(displayId: Int) {
        updateDisplay()
    }

    override fun onDisplayRemoved(displayId: Int) {
        updateDisplay()
    }
    override fun onDisplayChanged(displayId: Int) {
        updateDisplay()
    }
}
class SecondaryDisplayPresentation(
    context: Context,
    display: Display,
    val parent: SecondaryDisplay
) : Presentation(context, display) {
    private lateinit var surfaceView: SurfaceView
    private var touchscreenPointerId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )

        // Initialize SurfaceView
        surfaceView = SurfaceView(context)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.debug("SecondaryDisplay Surface created")
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                Log.debug("SecondaryDisplay Surface changed: ${width}x$height")
                parent.updateSurface()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.debug("SecondaryDisplay Surface destroyed")
                parent.destroySurface()
            }
        })

        this.surfaceView.setOnTouchListener { _, event ->

            val pointerIndex = event.actionIndex
            val pointerId = event.getPointerId(pointerIndex)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    if (touchscreenPointerId == -1) {
                        touchscreenPointerId = pointerId
                        NativeLibrary.onSecondaryTouchEvent(
                            event.getX(pointerIndex),
                            event.getY(pointerIndex),
                            true
                        )
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(touchscreenPointerId)
                    if (index != -1) {
                        NativeLibrary.onSecondaryTouchMoved(
                            event.getX(index),
                            event.getY(index)
                        )
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pointerId == touchscreenPointerId) {
                        NativeLibrary.onSecondaryTouchEvent(0f, 0f, false)
                        touchscreenPointerId = -1
                    }
                }
            }
            true
        }

        setContentView(surfaceView) // Set SurfaceView as content
    }

    // Publicly accessible method to get the SurfaceHolder
    fun getSurfaceHolder(): SurfaceHolder = surfaceView.holder
}
