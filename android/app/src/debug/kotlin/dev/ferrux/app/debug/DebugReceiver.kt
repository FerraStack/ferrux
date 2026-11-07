// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.ferrux.app.SessionManager
import dev.ferrux.terminal.FerruxNative
import dev.ferrux.terminal.SnapshotLayout
import java.io.File

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
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = SessionManager.active
        val sessions = manager?.sessions?.value.orEmpty()
        val current = manager?.current?.value ?: 0L
        var rows = 0
        var cols = 0
        var text = ""
        if (current != 0L && FerruxNative.loadError == null) {
            val header = IntArray(SnapshotLayout.requiredSize(1, 1))
            var buf = header
            var n = FerruxNative.snapshot(current, buf)
            if (n < 0) {
                buf = IntArray(-n)
                n = FerruxNative.snapshot(current, buf)
            }
            if (n >= SnapshotLayout.HEADER_SIZE) {
                rows = buf[SnapshotLayout.H_ROWS]
                cols = buf[SnapshotLayout.H_COLS]
            }
            text = FerruxNative.screenText(current).orEmpty()
        }
        val dump = buildString {
            append("sessions=${sessions.size} current=$current rows=$rows cols=$cols")
            append(" titles=").append(sessions.joinToString("|") { it.title })
            append("\n---\n")
            append(text)
            append('\n')
        }
        File(context.filesDir, "debug-screen.txt").writeText(dump)
        dump.lineSequence().forEach { Log.i(TAG, it) }
    }

    private companion object {
        const val TAG = "FerruxDump"
    }
}
