// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app

import dev.ferrux.terminal.TerminalKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitedSessionTest {
    @Test
    fun noticeNamesTheExitCodeOrSignal() {
        assertEquals("exit code 3", ExitNotice.status(3))
        assertEquals("exit code 128", ExitNotice.status(128))
        assertEquals("signal 9", ExitNotice.status(137))
        val text = ExitNotice.text(3)
        assertTrue(text.startsWith("\u001b[0m\r\n"))
        assertTrue(text.contains("[Process completed (exit code 3) — press Enter to restart, or swipe to close]"))
        assertTrue("hides the cursor", text.endsWith("\u001b[?25l"))
    }

    @Test
    fun enterRestartsEscClosesAndOtherInputIsDropped() {
        val calls = mutableListOf<String>()
        val backend = ExitedSessionBackend(42, onRestart = { calls += "restart" }, onClose = { calls += "close" })
        // None of these may reach the (native) session.
        backend.sendText("ls", 0)
        backend.sendKey(TerminalKeys.UP, 0)
        backend.paste("rm -rf")
        backend.write(byteArrayOf(1, 2))
        assertEquals(emptyList<String>(), calls)

        backend.sendKey(TerminalKeys.ENTER, 0)
        backend.sendText("\n", 0)
        backend.sendKey(TerminalKeys.ESCAPE, 0)
        assertEquals(listOf("restart", "restart", "close"), calls)
    }
}
