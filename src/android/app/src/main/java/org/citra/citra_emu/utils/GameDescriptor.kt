// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils

import android.os.ParcelFileDescriptor

/**
 * Owns the file descriptor a title is booted from.
 *
 * A launcher hands a game over as a `content://` URI, which the core cannot open by name, so the
 * front-end opens it once and boots `fd://<n>` instead. `Common::IOFile::Open` *dups* whatever
 * descriptor it is given, so this one has to stay open for as long as the core might reopen the
 * ROM, and be closed exactly once when it lets go — a leak here holds a provider's file open for
 * the life of the process, and a double close hands the number to whatever opened a file next.
 *
 * That used to be a pair of lifecycle callbacks in EmulationFragment, one opening and one
 * closing, which was enough while a fragment only ever ran one title. A launcher can now switch
 * titles into a running session, so a single fragment outlives more than one descriptor and the
 * handover — close the outgoing one, exactly once, and only after the core has stopped — is
 * expressed here instead.
 *
 * [closeFd] exists so the rule can be tested off-device; the default is the real close.
 */
class GameDescriptor(private val closeFd: (Int) -> Unit = { closeRawFd(it) }) {
    /** The descriptor currently held, or null when this owns nothing. */
    var fd: Int? = null
        private set

    /**
     * Takes ownership of [newFd], closing whatever was held before it. Pass null to hand the
     * outgoing descriptor back with nothing replacing it.
     */
    fun adopt(newFd: Int?) {
        release()
        fd = newFd
    }

    /** Closes what is held, if anything. Doing this twice closes nothing the second time. */
    fun release() {
        val current = fd ?: return
        fd = null
        closeFd(current)
    }

    companion object {
        /**
         * Closes a descriptor this never adopted — one opened for a launch that was then
         * abandoned, which must not be leaked and must not reach [adopt] either.
         */
        fun closeRawFd(rawFd: Int) {
            ParcelFileDescriptor.adoptFd(rawFd).close()
        }
    }
}
