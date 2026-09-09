// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils

import android.view.InputDevice
import android.view.KeyEvent
import androidx.preference.PreferenceManager
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.features.settings.model.view.InputBindingSetting

/**
 * Applies a default gamepad mapping the first time a physical controller is seen while no
 * binding exists, so a handheld with a built-in pad works without visiting Settings.
 *
 * The button and axis tables are the same ones the "Auto-Map Controller" dialog writes; the
 * only thing this object adds is choosing the face-button layout without asking the user to
 * press A. Known handhelds are looked up by USB vendor/product ID, everything else gets the
 * positional (Xbox-style) layout that most Android controllers report.
 *
 * Seeding happens at most once per install. It never runs while any binding exists, so a user
 * who has mapped even a single button is left alone, and clearing all bindings afterwards does
 * not bring the defaults back (the Auto-Map dialog does that on demand).
 */
object ControllerAutoMapper {
    private const val PREF_SEEDED = "ControllerAutoMap_Seeded"

    /** A built-in or well-known controller whose face-button layout is known up front. */
    private data class Profile(val vendorId: Int, val productId: Int, val nintendoLayout: Boolean)

    private val profiles = listOf(
        // AYN Odin / Odin 2 / Thor built-in pad ("Odin Controller") in Thor/Odin mode. The
        // east button is labelled A and reports KEYCODE_BUTTON_A, so the Nintendo table keeps
        // the shell labels and the 3DS labels aligned.
        Profile(0x2020, 0x0111, nintendoLayout = true),
        // The same pad after the system-wide "Xbox" layout switch: the product ID changes and
        // the south button reports KEYCODE_BUTTON_A.
        Profile(0x2020, 0x0112, nintendoLayout = false)
    )

    private var seededThisProcess = false

    /** Seeds from whichever physical gamepad is connected right now, if nothing is mapped. */
    fun seedFromConnectedDevices() {
        if (seededThisProcess) return
        for (id in InputDevice.getDeviceIds()) {
            val device: InputDevice = InputDevice.getDevice(id) ?: continue
            if (isPhysicalGamepad(device)) {
                seedIfUnmapped(device)
                return
            }
        }
    }

    /**
     * Seeds from [device] if it is a physical gamepad and no binding exists yet.
     * Returns true when a mapping was written, so the caller can re-read its bindings.
     */
    fun seedIfUnmapped(device: InputDevice?): Boolean {
        if (seededThisProcess || device == null || !isPhysicalGamepad(device)) return false
        seededThisProcess = true

        val prefs = PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
        val description = "${device.name} (vendor=0x%04x product=0x%04x)"
            .format(device.vendorId, device.productId)
        val alreadySeeded = prefs.getBoolean(PREF_SEEDED, false)
        val hasBinding = InputBindingSetting.hasAnyBinding()
        if (alreadySeeded || hasBinding) {
            // A pad whose buttons do nothing in-game got here and was turned away; say which of
            // the two gates did it, because "seeded but nothing is bound" is a broken install
            // while "a binding exists" is the user's own mapping and is meant to win.
            Log.info(
                "[ControllerAutoMapper] Not seeding for $description: " +
                    "already seeded once=$alreadySeeded, a binding exists=$hasBinding"
            )
            return false
        }
        if (InputBindingSetting.isJoyCon(device)) {
            Log.info("[ControllerAutoMapper] Seeding Joy-Con bindings for $description")
            InputBindingSetting.applyJoyConBindings()
        } else {
            val profile = profiles.firstOrNull {
                it.vendorId == device.vendorId && it.productId == device.productId
            }
            val nintendoLayout = profile?.nintendoLayout ?: false
            val axisDpad = InputBindingSetting.usesAxisDpad(device)
            Log.info(
                "[ControllerAutoMapper] Seeding ${if (nintendoLayout) "Nintendo" else "Xbox"} " +
                    "layout, ${if (axisDpad) "axis" else "button"} d-pad for $description" +
                    if (profile == null) " (no profile, using positional default)" else ""
            )
            InputBindingSetting.applyAutoMapBindings(nintendoLayout, axisDpad)
        }
        prefs.edit().putBoolean(PREF_SEEDED, true).apply()
        return true
    }

    /**
     * Whether a physical gamepad is attached right now. Used for defaults that only make sense
     * without one, such as the on-screen touch overlay.
     */
    fun isPhysicalGamepadConnected(): Boolean = InputDevice.getDeviceIds().any { id ->
        InputDevice.getDevice(id)?.let { isPhysicalGamepad(it) } == true
    }

    private fun isPhysicalGamepad(device: InputDevice): Boolean {
        if (device.isVirtual) return false
        val sources = device.sources
        val isController =
            sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        return isController &&
            device.hasKeys(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B).any { it }
    }
}
