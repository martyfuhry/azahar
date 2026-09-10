// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A launcher can switch titles into a running session, so one EmulationFragment now hands one
 * ROM descriptor over to the next. Getting that wrong is worse than the bug it was added for: a
 * leak holds a content provider's file open for the life of the process, and a double close
 * hands a live number to whatever opened a file next. The rule is a few lines with no Android in
 * it, so it is pinned here rather than on a device.
 */
class GameDescriptorTest {
    private val closed = mutableListOf<Int>()
    private val descriptor = GameDescriptor { closed.add(it) }

    @Test
    fun `owns nothing until it adopts something`() {
        assertNull(descriptor.fd)
        assertEquals(emptyList<Int>(), closed)
    }

    @Test
    fun `releasing closes the descriptor exactly once`() {
        descriptor.adopt(7)
        assertEquals(7, descriptor.fd)

        descriptor.release()

        assertEquals(listOf(7), closed)
        assertNull(descriptor.fd)
    }

    @Test
    fun `releasing twice closes nothing the second time`() {
        descriptor.adopt(7)

        descriptor.release()
        descriptor.release()

        assertEquals(listOf(7), closed)
    }

    @Test
    fun `a title switch closes the outgoing descriptor and keeps the incoming one`() {
        descriptor.adopt(7)

        descriptor.adopt(9)

        assertEquals(listOf(7), closed)
        assertEquals(9, descriptor.fd)
    }

    @Test
    fun `a game with no descriptor still hands the outgoing one back`() {
        // Our own game list parcels a Game and passes no URI, so there is nothing to open for it
        descriptor.adopt(7)

        descriptor.adopt(null)

        assertEquals(listOf(7), closed)
        assertNull(descriptor.fd)
    }

    @Test
    fun `every descriptor a session opened is closed exactly once`() {
        listOf(3, 4, 5).forEach { descriptor.adopt(it) }
        descriptor.release()

        assertEquals(listOf(3, 4, 5), closed)
        assertEquals(closed.size, closed.distinct().size)
        assertNull(descriptor.fd)
    }

    @Test
    fun `adopting nothing when nothing is held closes nothing`() {
        descriptor.adopt(null)

        assertEquals(emptyList<Int>(), closed)
        assertNull(descriptor.fd)
    }
}
