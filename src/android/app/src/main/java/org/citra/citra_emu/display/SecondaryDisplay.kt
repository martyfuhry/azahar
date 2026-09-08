// Copyright 2025-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.display

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private val vd: VirtualDisplay
    private val handler = Handler(Looper.getMainLooper())

    /**
     * True between the owning activity's onStart and onStop. A [Presentation] is a Dialog and
     * needs the activity's window token, so showing one while the activity is stopped throws
     * BadTokenException and leaves us with no presentation at all until some later display
     * event happens to arrive. Display callbacks land on the main looper unordered with
     * respect to onStop, which on a lid close is exactly when they arrive.
     */
    private var isStarted = false

    /** Re-run scheduled once per onStart/onResume when the panel we want is still powered off. */
    private val displayRecheck = Runnable { updateDisplay() }

    var preferredDisplayId = -1
    var currentDisplayId = -1

    val availableDisplays: List<Display>
        get() = getSecondaryDisplays()

    init {
        vd = displayManager.createVirtualDisplay(
            HIDDEN_DISPLAY_NAME,
            1920,
            1080,
            320,
            null,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        )
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

    /**
     * Every display this app could put the bottom screen on. [requirePoweredOn] drops panels
     * the system reports as [Display.STATE_OFF]; pass false to see the ones that exist but are
     * still asleep, which is what a panel looks like in the first moments after a lid opens.
     */
    private fun getSecondaryDisplays(requirePoweredOn: Boolean = true): List<Display> {
        val ownDisplayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display.displayId
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.displayId
        }
        val displays = displayManager.displays
        val presDisplays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        return displays.filter {
            val isPresentable = presDisplays.any { pd -> pd.displayId == it.displayId }
            val isNotDefaultOrPresentable =
                (it != null && it.displayId != Display.DEFAULT_DISPLAY) || isPresentable

            isNotDefaultOrPresentable &&
                it.displayId != ownDisplayId &&
                it.name != HIDDEN_DISPLAY_NAME &&
                (!requirePoweredOn || it.state != Display.STATE_OFF) &&
                it.isValid
        }
    }

    /** Virtual displays (ours, screen recorders, the Odin 2's phantom) are private. */
    private fun Display.isVirtual() = (flags and Display.FLAG_PRIVATE) != 0

    /** Set by the system on anything it considers suitable to show a Presentation on. */
    private fun Display.isPresentationDisplay() = (flags and Display.FLAG_PRESENTATION) != 0

    /**
     * Picks the panel the bottom screen belongs on, out of an already-filtered [displays].
     *
     * `Display.getType()`/`TYPE_INTERNAL` are not public API, so "built into the device" is
     * read off the two signals that are: the display is not a virtual one, and Android names
     * internal panels "Built-in Screen". The previous heuristic did the opposite - it skipped
     * anything whose name contained "Built" - which deprioritised the Thor's own bottom panel
     * the moment any other non-default display existed. What it was really guarding against is
     * the Odin 2's permanent phantom display, which carries the *same* name as the default
     * panel, so that is what is tested for instead.
     */
    private fun pickDisplay(displays: List<Display>, defaultName: String?): Display {
        // 1. A second panel built into the device (the Thor's bottom screen).
        displays.firstOrNull {
            !it.isVirtual() && it.name.contains("Built", true) && it.name != defaultName
        }?.let { return it }

        // 2. Anything real the system itself offers for presentations: an external panel, a
        //    cast route, or a built-in second panel under an OEM name.
        displays.firstOrNull { !it.isVirtual() && it.isPresentationDisplay() }?.let { return it }

        // 3. Whatever is left, virtual displays included.
        return displays[0]
    }

    fun updateDisplay() {
        // return early if the parent context is dead or dying
        if (context is android.app.Activity && (context.isFinishing || context.isDestroyed)) {
            cancelDisplayRecheck()
            return
        }

        // A Presentation needs the activity's window token. Running this while the activity is
        // stopped only ever throws BadTokenException and nulls out `pres`; onStart re-runs it.
        if (!isStarted) {
            return
        }

        // Query the display list once: `availableDisplays` re-queries the DisplayManager on
        // every access, so the old code asked for it up to five times per call and could see a
        // different answer each time.
        val displays = availableDisplays

        // (The NONE layout is theoretically no longer selectable, but the check stays for
        // backwards compatibility.)
        val displayToUse = if (displays.isEmpty() || !isSecondaryDisplayWanted()) {
            currentDisplayId = -1
            vd.display
        } else {
            // An explicit choice from the in-game display menu wins over the automatic pick.
            val chosen = displays.firstOrNull { it.displayId == preferredDisplayId }
                ?: pickDisplay(displays, defaultDisplayName())
            currentDisplayId = chosen.displayId
            chosen
        }

        if (displayToUse == null) {
            releasePresentation()
            return
        }

        // If our presentation is already showing on the right display, leave it alone. This
        // compares display *ids*: `availableDisplays` hands out freshly built Display objects,
        // so the old reference comparison never matched a physical panel and every single
        // onDisplayChanged tore the Presentation down and built a new one - a surface destroy
        // and create per callback, which is both a black flash on the bottom panel and the
        // source of most of the secondary-surface races.
        val current = pres
        if (current != null && current.display?.displayId == displayToUse.displayId &&
            current.isShowing
        ) {
            return
        }

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

    private fun defaultDisplayName(): String? =
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.name

    /**
     * Call from the owning activity's onStart. Re-runs the display pick now that a window
     * token exists again.
     */
    fun onActivityStarted() {
        isStarted = true
        updateDisplayAndRecheck()
    }

    /**
     * Call from the owning activity's onResume. A lid open delivers onRestart/onStart before
     * the bottom panel has finished powering on, so the pick is worth repeating here too.
     */
    fun onActivityResumed() {
        isStarted = true
        updateDisplayAndRecheck()
    }

    /** Call from the owning activity's onStop. */
    fun onActivityStopped() {
        isStarted = false
        cancelDisplayRecheck()
        releasePresentation()
    }

    /**
     * Runs the pick, and if a usable panel exists but is still powered off - the lid-open case,
     * where onRestart lands while the bottom screen still reports STATE_OFF and we would
     * otherwise fall back to the hidden virtual display until some unrelated display callback
     * arrived - schedules exactly one re-check. Exactly one: the re-check calls updateDisplay()
     * directly, so a panel that stays off cannot turn this into a polling loop.
     */
    private fun updateDisplayAndRecheck() {
        updateDisplay()
        cancelDisplayRecheck()
        if (context is android.app.Activity && (context.isFinishing || context.isDestroyed)) {
            return
        }
        // Only worth repeating when we fell back to the hidden display while a real panel
        // exists and is merely asleep.
        if (currentDisplayId == -1 &&
            isSecondaryDisplayWanted() &&
            getSecondaryDisplays(requirePoweredOn = false).any { it.state == Display.STATE_OFF }
        ) {
            handler.postDelayed(displayRecheck, DISPLAY_RECHECK_DELAY_MS)
        }
    }

    private fun isSecondaryDisplayWanted(): Boolean =
        BooleanSetting.ENABLE_SECONDARY_DISPLAY.boolean &&
            IntSetting.SECONDARY_DISPLAY_LAYOUT.int != SecondaryDisplayLayout.NONE.int

    private fun cancelDisplayRecheck() {
        handler.removeCallbacks(displayRecheck)
    }

    fun releasePresentation() {
        try {
            pres?.dismiss()
        } catch (_: Exception) { }
        pres = null
    }

    fun releaseVD() {
        cancelDisplayRecheck()
        displayManager.unregisterDisplayListener(this)
        vd.release()
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

    companion object {
        private const val HIDDEN_DISPLAY_NAME = "HiddenDisplay"

        /**
         * How long to wait before looking again when the panel we want is still powered off.
         * Long enough for the panel to come up after a lid open, short enough that the bottom
         * screen is back well inside the "within 1 s of wake" target.
         */
        private const val DISPLAY_RECHECK_DELAY_MS = 500L
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
