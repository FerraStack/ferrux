// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionShortcutsTest {
    @Test
    fun keysMapToShortcuts() {
        assertEquals(SessionShortcut.New, SessionShortcuts.of(KeyEvent.KEYCODE_C))
        assertEquals(SessionShortcut.Move(+1), SessionShortcuts.of(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(SessionShortcut.Move(+1), SessionShortcuts.of(KeyEvent.KEYCODE_N))
        assertEquals(SessionShortcut.Move(-1), SessionShortcuts.of(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(SessionShortcut.Move(-1), SessionShortcuts.of(KeyEvent.KEYCODE_P))
        assertEquals(SessionShortcut.Go(0), SessionShortcuts.of(KeyEvent.KEYCODE_1))
        assertEquals(SessionShortcut.Go(8), SessionShortcuts.of(KeyEvent.KEYCODE_9))
        // Left free for the shell or other uses (zoom is handled by the terminal view).
        assertNull(SessionShortcuts.of(KeyEvent.KEYCODE_0))
        assertNull(SessionShortcuts.of(KeyEvent.KEYCODE_EQUALS))
        assertNull(SessionShortcuts.of(KeyEvent.KEYCODE_A))
    }

    @Test
    fun neighboursWrapAround() {
        val ids = listOf(4L, 7L, 9L)
        assertEquals(7L, SessionShortcuts.neighbour(ids, 4L, +1))
        assertEquals(4L, SessionShortcuts.neighbour(ids, 9L, +1))
        assertEquals(9L, SessionShortcuts.neighbour(ids, 4L, -1))
        assertEquals(4L, SessionShortcuts.neighbour(ids, 99L, +1))
        assertNull(SessionShortcuts.neighbour(listOf(4L), 4L, +1))
        assertNull(SessionShortcuts.neighbour(emptyList(), 0L, -1))
    }
}
