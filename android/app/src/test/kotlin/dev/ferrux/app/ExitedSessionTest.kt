// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app

import dev.ferrux.terminal.TerminalKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitedSessionTest {
    @Test
    fun keepPolicy() {
        assertTrue(KeepExited.ON_ERROR.keeps(1))
        assertTrue(KeepExited.ON_ERROR.keeps(128 + 9))
        assertFalse(KeepExited.ON_ERROR.keeps(0))
        assertTrue(KeepExited.ALWAYS.keeps(0))
        assertFalse(KeepExited.NEVER.keeps(3))
    }

    @Test
    fun bannerNamesTheCodeOrSignal() {
        val code = ExitBanner.text(3)
        assertTrue(code, code.contains("[Process completed (code 3) — press Enter to restart, Esc to close]"))
        assertTrue("starts on a new line", code.startsWith("\r\n"))
        assertTrue("hides the cursor", code.endsWith("\u001b[?25l"))
        assertTrue(ExitBanner.text(128 + 9).contains("(signal 9)"))
    }

    @Test
    fun exitedBackendTurnsKeysIntoThePrompt() {
        var restarts = 0
        var closes = 0
        // Only input is exercised, so no native call is made.
        val backend = ExitedSessionBackend(42, onRestart = { restarts++ }, onClose = { closes++ })
        assertTrue(backend.sendKey(TerminalKeys.ENTER, 0))
        backend.sendText("\n", 0)
        backend.sendText("x", 0)
        backend.sendKey(TerminalKeys.UP, 0)
        assertEquals(2, restarts)
        assertEquals(0, closes)
        backend.sendKey(TerminalKeys.ESCAPE, 0)
        assertEquals(1, closes)
        assertFalse(backend.mouseEvent(TerminalKeys.MOUSE_LEFT, TerminalKeys.MOUSE_PRESS, 0, 0, 0))
    }
}
