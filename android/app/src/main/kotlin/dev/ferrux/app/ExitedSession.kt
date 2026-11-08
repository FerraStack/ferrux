// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app

import dev.ferrux.terminal.NativeSessionBackend
import dev.ferrux.terminal.TerminalBackend
import dev.ferrux.terminal.TerminalKeys

/** The notice written into a session whose process has ended. */
object ExitNotice {
    private const val ESC = "\u001b"

    /** "exit code 3", or "signal 9" for a process killed by a signal (status 128 + n). */
    fun status(code: Int): String = if (code > 128) "signal ${code - 128}" else "exit code $code"

    /**
     * Terminal text for [code]: reset attributes, start a new line, the notice in
     * dim text, then hide the cursor, since nothing reads input any more.
     */
    fun text(code: Int): String =
        "$ESC[0m\r\n$ESC[2m[Process completed (${status(code)}) — press Enter to restart, or swipe to close]$ESC[0m$ESC[?25l"
}

/**
 * Shows an exited session (its last screen stays readable and scrollable) but turns
 * input into the notice's choices: Enter restarts the shell, Esc closes the session,
 * and everything else is dropped, since no program reads it any more.
 */
class ExitedSessionBackend(
    val sessionId: Long,
    private val onRestart: () -> Unit,
    private val onClose: () -> Unit,
) : TerminalBackend by NativeSessionBackend(sessionId) {
    override fun sendKey(key: Int, mods: Int): Boolean {
        when (key) {
            TerminalKeys.ENTER -> onRestart()
            TerminalKeys.ESCAPE -> onClose()
        }
        return true
    }

    override fun sendText(text: String, mods: Int) {
        if (text == "\r" || text == "\n") onRestart()
    }

    override fun paste(text: String) = Unit

    override fun write(data: ByteArray) = Unit

    // A swipe only scrolls the scrollback; no program receives arrow keys or mouse events.
    override fun scrollGesture(lines: Int, row: Int, col: Int) = scroll(lines)

    override fun mouseEvent(button: Int, action: Int, row: Int, col: Int, mods: Int): Boolean = false
}
