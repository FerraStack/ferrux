// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app.debug

import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.ferrux.app.SessionManager
import dev.ferrux.app.ui.ActiveTerminal
import dev.ferrux.app.FerruxAppNative
import dev.ferrux.terminal.TerminalNative
import dev.ferrux.terminal.SnapshotLayout
import java.io.File
import java.util.Locale

/**
 * Debug-build test hook: dumps the session state and the visible terminal
 * grid of the current session to `files/debug-screen.txt` (read with
 * `adb shell run-as <pkg> cat files/debug-screen.txt`) and to logcat.
 *
 * ```
 * adb shell am broadcast -a dev.ferrux.debug.DUMP_SCREEN -p dev.ferrux
 * ```
 *
 * Format: one `key=value` header line, then `---`, then the grid text.
 *
 * It also writes `files/debug-ui.txt`, one `key=value` per line: the terminal
 * view's geometry and selection state ([dev.ferrux.terminal.TerminalView.debugState])
 * and the clipboard text. Newlines in values are written as `\n`.
 *
 * In the header, `view` is the identity of the on-screen TerminalView (0 if none)
 * and `textSizeSp` its font size, so CI can tell a kept view from a recreated one.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SHOW_IME) {
            // Lets CI bring the soft keyboard up before typing, so no keystroke is
            // injected while the IME is still starting.
            ActiveTerminal.view?.get()?.showKeyboard()
            return
        }
        val manager = SessionManager.active
        val sessions = manager?.sessions?.value.orEmpty()
        val current = manager?.current?.value ?: 0L
        var rows = 0
        var cols = 0
        var offset = 0
        var text = ""
        if (current != 0L && FerruxAppNative.loadError == null) {
            val header = IntArray(SnapshotLayout.requiredSize(1, 1))
            var buf = header
            var n = TerminalNative.snapshot(current, buf)
            if (n < 0) {
                buf = IntArray(-n)
                n = TerminalNative.snapshot(current, buf)
            }
            if (n >= SnapshotLayout.HEADER_SIZE) {
                rows = buf[SnapshotLayout.H_ROWS]
                cols = buf[SnapshotLayout.H_COLS]
                offset = buf[SnapshotLayout.H_DISPLAY_OFFSET]
            }
            text = TerminalNative.screenText(current).orEmpty()
        }
        val dump = buildString {
            append("sessions=${sessions.size} current=$current rows=$rows cols=$cols offset=$offset")
            // Runs on the main thread, so the view can be read directly.
            val view = ActiveTerminal.view?.get()
            append(" view=").append(view?.let { System.identityHashCode(it) } ?: 0)
            append(" textSizeSp=").append(view?.let { "%.2f".format(Locale.ROOT, it.textSizeSp) } ?: "-")
            // Exit code per session, "-" while running.
            append(" exits=").append(sessions.joinToString("|") { it.exitCode?.toString() ?: "-" })
            append(" titles=").append(sessions.joinToString("|") { it.title })
            append("\n---\n")
            append(text)
            append('\n')
        }
        File(context.filesDir, "debug-screen.txt").writeText(dump)
        dump.lineSequence().forEach { Log.i(TAG, it) }

        // Runs on the main thread, so the view can be read directly.
        val ui = buildString {
            ActiveTerminal.view?.get()?.debugState()?.forEach { (k, v) -> append(k).append('=').append(v).append('\n') }
            val clip = runCatching {
                context.getSystemService(ClipboardManager::class.java)?.primaryClip
                    ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            }.getOrNull()
            append("clipboard=").append(clip.orEmpty().replace("\\", "\\\\").replace("\n", "\\n")).append('\n')
        }
        File(context.filesDir, "debug-ui.txt").writeText(ui)
        ui.lineSequence().forEach { Log.i(TAG, "ui: $it") }
    }

    private companion object {
        const val TAG = "FerruxDump"
        const val ACTION_SHOW_IME = "dev.ferrux.debug.SHOW_IME"
    }
}
