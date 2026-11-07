// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app

import dev.ferrux.terminal.NativeSessionBackend
import dev.ferrux.terminal.TerminalBackend
import dev.ferrux.terminal.TerminalKeys

/** When a session whose process ended stays on screen instead of closing. */
enum class KeepExited {
    /** Always keep it, with the restart prompt. */
    ALWAYS,

    /** Keep it only when the process failed (non-zero exit or a signal), so its output stays readable. */
    ON_ERROR,

    /** Close it right away, like a terminal window. */
    NEVER;

    fun keeps(code: Int): Boolean = when (this) {
        ALWAYS -> true
        ON_ERROR -> code != 0
        NEVER -> false
    }
}

/** The status line printed into a session whose process has ended. */
object ExitBanner {
    private const val ESC = "\u001b"

    fun text(code: Int): String {
        val what = if (code > 128) "signal ${code - 128}" else "code $code"
        // New line, dim, then hide the dead shell's cursor.
        return "\r\n$ESC[0;2m[Process completed ($what) — press Enter to restart, Esc to close]$ESC[0m$ESC[?25l"
    }
}

/**
 * Shows an exited session (its last screen stays readable and scrollable) but turns
 * input into the restart prompt: Enter restarts the shell, Esc closes the session,
 * and everything else is ignored, since no program reads it any more.
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

    override fun mouseEvent(button: Int, action: Int, row: Int, col: Int, mods: Int): Boolean = false
}
