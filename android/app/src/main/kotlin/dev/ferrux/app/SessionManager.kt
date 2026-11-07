// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.ferrux.terminal.FerruxNative
import dev.ferrux.terminal.SessionEvent
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** UI-facing state of one terminal session. */
data class SessionInfo(
    val id: Long,
    val title: String,
    /** Exit status once the process has ended (128 + signal for signals), else null. */
    val exitCode: Int? = null,
)

/**
 * Kotlin-side registry of the native sessions owned by [FerruxService].
 *
 * A single daemon thread blocks in [FerruxNative.awaitChange], drains per-session
 * events and notifies output listeners, so views redraw only when something changed.
 */
class SessionManager(private val context: Context) {
    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions.asStateFlow()

    private val _current = MutableStateFlow(0L)

    /** Id of the session shown in the UI, or 0 when there is none. */
    val current: StateFlow<Long> = _current.asStateFlow()

    private val _bells = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    val bells: SharedFlow<Long> = _bells.asSharedFlow()

    private val _clipboardRequest = MutableStateFlow<ClipboardRequest?>(null)

    /**
     * An OSC 52 clipboard write waiting for the user's answer (policy [ClipboardPolicy.ASK]).
     * Only the latest one is kept; it stays pending while the app is in the background.
     */
    val clipboardRequest: StateFlow<ClipboardRequest?> = _clipboardRequest.asStateFlow()

    private val outputListeners = CopyOnWriteArraySet<Runnable>()
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        active = this
    }

    @Volatile private var running = true
    private val eventThread = Thread(::eventLoop, "ferrux-events").apply {
        isDaemon = true
        start()
    }

    /** Listeners run on the event thread; they must only post work (e.g. invalidate). */
    fun addOutputListener(listener: Runnable) = outputListeners.add(listener)

    fun removeOutputListener(listener: Runnable) = outputListeners.remove(listener)

    /**
     * Starts the default shell in a new session and makes it current.
     *
     * @throws java.io.IOException if spawning fails
     */
    fun create(rows: Int = 24, cols: Int = 80): Long {
        val env = FerruxNative.defaultEnv()
        val home = env.firstOrNull { it.startsWith("HOME=") }?.substringAfter('=')
            ?.let { File(it) } ?: File(context.filesDir, "home")
        home.mkdirs()
        // TODO(M3): once the bootstrap is installed, defaultShell() returns $PREFIX/bin/login.
        val id = FerruxNative.createSession(
            FerruxNative.defaultShell(), emptyArray(), env, home.absolutePath, rows, cols,
        )
        _sessions.update { it + SessionInfo(id, title = "Session ${it.size + 1}") }
        _current.value = id
        // The event thread only drains sessions it knows about; catch anything
        // (e.g. an immediate exit) that happened before the list was updated.
        drainEvents(id)
        return id
    }

    fun select(id: Long) {
        if (_sessions.value.any { it.id == id }) _current.value = id
    }

    fun kill(id: Long) {
        FerruxNative.destroySession(id)
        _sessions.update { list -> list.filterNot { it.id == id } }
        if (_current.value == id) _current.value = _sessions.value.lastOrNull()?.id ?: 0L
    }

    fun killAll() {
        _sessions.value.forEach { kill(it.id) }
    }

    /** Kills every session and stops the event thread. */
    fun shutdown() {
        if (active === this) active = null
        running = false
        killAll()
        eventThread.interrupt()
    }

    private fun eventLoop() {
        var seq = 0L
        while (running) {
            val next = try {
                FerruxNative.awaitChange(seq, AWAIT_TIMEOUT_MS)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(FerruxApplication.TAG, "native library missing; event loop stopped", e)
                return
            }
            if (next == seq) continue
            seq = next
            for (session in _sessions.value) drainEvents(session.id)
            outputListeners.forEach(Runnable::run)
        }
    }

    private fun drainEvents(id: Long) {
        val raw = FerruxNative.pollEvents(id) ?: return
        for (encoded in raw) {
            when (val event = SessionEvent.parse(encoded)) {
                is SessionEvent.Title -> updateSession(id) { it.copy(title = event.title) }
                is SessionEvent.Exited -> {
                    // Like closing a terminal window on `exit`: the session goes away.
                    // TODO(M4): optionally keep failed sessions open to show their output.
                    Log.i(FerruxApplication.TAG, "session $id exited with ${event.code}")
                    updateSession(id) { it.copy(exitCode = event.code) }
                    mainHandler.post { kill(id) }
                }
                is SessionEvent.Bell -> _bells.tryEmit(id)
                is SessionEvent.Clipboard -> mainHandler.post { onClipboardWrite(id, event.text) }
                is SessionEvent.Failed -> {
                    Log.e(FerruxApplication.TAG, "session $id failed: ${event.message}")
                    updateSession(id) { it.copy(title = "${it.title} (error)") }
                }
                null -> Unit
            }
        }
    }

    private inline fun updateSession(id: Long, crossinline change: (SessionInfo) -> SessionInfo) {
        _sessions.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    /**
     * Any program, even `cat` of an untrusted file, can send OSC 52, so by default the
     * user is asked before it replaces the clipboard (issue #12).
     */
    private fun onClipboardWrite(id: Long, text: String) {
        val session = _sessions.value.firstOrNull { it.id == id } ?: return
        when (FerruxSettings.get(context).clipboardWrites.value) {
            ClipboardPolicy.OFF -> Log.i(FerruxApplication.TAG, "OSC 52 write from session $id ignored")
            ClipboardPolicy.ALLOW -> setClipboard(text)
            ClipboardPolicy.ASK -> _clipboardRequest.value = ClipboardRequest(id, session.title, text)
        }
    }

    /** Answers the pending [clipboardRequest]. */
    fun answerClipboard(request: ClipboardRequest, allow: Boolean) {
        if (allow) setClipboard(request.text)
        _clipboardRequest.compareAndSet(request, null)
    }

    private fun setClipboard(text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Ferrux", text))
    }

    companion object {
        private const val AWAIT_TIMEOUT_MS = 500

        /** The live manager (owned by [FerruxService]); used by debug tooling. */
        @Volatile
        var active: SessionManager? = null
            private set
    }
}
