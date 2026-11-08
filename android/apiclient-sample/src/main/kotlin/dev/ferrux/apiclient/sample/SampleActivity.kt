// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.apiclient.sample

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import dev.ferrux.client.FerruxClient
import dev.ferrux.client.FerruxConnection
import dev.ferrux.client.FerruxException
import dev.ferrux.client.FerruxProcess
import dev.ferrux.client.FerruxStatus
import dev.ferrux.client.ProcessSpec
import dev.ferrux.client.PtySize
import dev.ferrux.terminal.NativeSessionBackend
import dev.ferrux.terminal.TerminalNative
import dev.ferrux.terminal.TerminalView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Binds Ferrux like Ferride would, spawns `/system/bin/sh` on a PTY inside Ferrux and
 * renders it from the PTY master fd Ferrux passed back, with the shared terminal-view.
 * It types one command; the status line says "Terminal OK" once the command's output
 * is in the terminal grid (CI checks that line, then takes the screenshot).
 */
class SampleActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var terminal: TerminalView
    private val scope = MainScope()
    private var connection: FerruxConnection? = null
    private var process: FerruxProcess? = null
    private var sessionId = 0L
    @Volatile private var watching = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF1C2029.toInt())
            setPadding(24, 16, 24, 16)
            text = "Connecting to Ferrux…"
        }
        terminal = TerminalView(this)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF0E1116.toInt())
                addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(terminal, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                // Edge-to-edge (targetSdk 35+): keep content clear of the system bars and the IME.
                setOnApplyWindowInsetsListener { v, insets ->
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                    WindowInsets.CONSUMED
                }
            },
        )
        TerminalNative.load()?.let {
            status.text = "terminal-view native library missing: $it"
            return
        }
        scope.launch {
            try {
                start()
            } catch (e: FerruxException) {
                Log.e(TAG, "Ferrux API failed", e)
                status.text = "Ferrux API error: ${e.message}"
            }
        }
    }

    private suspend fun start() {
        val st = FerruxClient.status(this)
        if (st !is FerruxStatus.Ready) {
            status.text = "Ferrux not usable: $st"
            return
        }
        val conn = FerruxClient.connect(this).also { connection = it }
        val info = conn.info
        val p = conn.spawn(ProcessSpec(listOf("/system/bin/sh"), pty = PtySize(24, 80)))
        process = p
        // The Rust terminal takes ownership of the PTY master; Ferrux keeps the process.
        val fds = p.detachFds()
        sessionId = TerminalNative.attachPty(fds.pty, 24, 80)
        terminal.backend = NativeSessionBackend(sessionId)
        status.text = "Ferrux ${info.versionName} · API ${info.apiVersion} · pid ${p.pid} · PTY fd from Ferrux"
        // $((6*7)) is expanded by the shell, so the marker only appears as output.
        TerminalNative.write(sessionId, "echo ferrux-api-\$((6*7)); uname -sm\n".toByteArray())
        Thread(::watch, "sample-redraw").apply {
            isDaemon = true
            start()
        }
    }

    /** Redraws on output and reports once the command's output reached the grid. */
    private fun watch() {
        var seq = 0L
        var reported = false
        while (watching) {
            seq = TerminalNative.awaitChange(seq, 500)
            val screen = TerminalNative.screenText(sessionId) ?: break
            val ok = !reported && screen.lines().any { it.trim() == "ferrux-api-42" }
            runOnUiThread {
                terminal.onScreenUpdated()
                if (ok) status.text = "Terminal OK · ${status.text}"
            }
            if (ok) {
                reported = true
                Log.i(TAG, "terminal OK")
            }
        }
    }

    override fun onDestroy() {
        watching = false
        scope.cancel()
        if (sessionId > 0) TerminalNative.destroySession(sessionId)
        process?.close()
        connection?.close()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "FerruxApiSample"
    }
}
