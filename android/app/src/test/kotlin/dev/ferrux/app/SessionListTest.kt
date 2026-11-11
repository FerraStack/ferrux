// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SessionListTest {
    private val list = listOf(SessionInfo(1, "a"), SessionInfo(2, "b"), SessionInfo(3, "c"))

    private fun ids(l: List<SessionInfo>) = l.map { it.id }

    @Test
    fun movesUpAndDown() {
        assertEquals(listOf(2L, 1L, 3L), ids(SessionList.moved(list, 2, -1)))
        assertEquals(listOf(1L, 3L, 2L), ids(SessionList.moved(list, 2, +1)))
        assertEquals(listOf(2L, 3L, 1L), ids(SessionList.moved(list, 1, +5)))
    }

    @Test
    fun movesPastTheEndsOrOfUnknownSessionsChangeNothing() {
        assertSame(list, SessionList.moved(list, 1, -1))
        assertSame(list, SessionList.moved(list, 3, +1))
        assertSame(list, SessionList.moved(list, 9, +1))
    }

    @Test
    fun customNamesAreTrimmedAndBlankMeansNone() {
        assertEquals("build", SessionList.customName("  build "))
        assertNull(SessionList.customName("   "))
        assertEquals(SessionList.MAX_NAME_LENGTH, SessionList.customName("x".repeat(100))!!.length)
    }

    @Test
    fun aCustomNameWinsOverTheProgramsTitle() {
        assertEquals("vim", SessionInfo(1, "vim").label)
        assertEquals("notes", SessionInfo(1, "vim", customName = "notes").label)
    }
}
