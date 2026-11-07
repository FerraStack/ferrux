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
        val id = spawnShell(rows, cols)
        _sessions.update { it + SessionInfo(id, title = "Session ${it.size + 1}") }
        _current.value = id
        // The event thread only drains sessions it knows about; catch anything
        // (e.g. an immediate exit) that happened before the list was updated.
        drainEvents(id)
        return id
    }

    private fun spawnShell(rows: Int, cols: Int): Long {
        val env = FerruxNative.defaultEnv()
        val home = env.firstOrNull { it.startsWith("HOME=") }?.substringAfter('=')
            ?.let { File(it) } ?: File(context.filesDir, "home")
        home.mkdirs()
        // TODO(M3): once the bootstrap is installed, defaultShell() returns $PREFIX/bin/login.
        return FerruxNative.createSession(
            FerruxNative.defaultShell(), emptyArray(), env, home.absolutePath, rows, cols,
        )
    }

    /**
     * Replaces an exited session with a fresh shell in the same place in the list
     * (same name), and shows it.
     *
     * @throws java.io.IOException if spawning fails
     */
    fun restart(id: Long, rows: Int = 24, cols: Int = 80): Long {
        val old = _sessions.value.firstOrNull { it.id == id } ?: return 0L
        val newId = spawnShell(rows, cols)
        _sessions.update { list -> list.map { if (it.id == id) SessionInfo(newId, title = old.title) else it } }
        if (_current.value == id) _current.value = newId
        FerruxNative.destroySession(id)
        drainEvents(newId)
        return newId
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
                    Log.i(FerruxApplication.TAG, "session $id exited with ${event.code}")
                    updateSession(id) { it.copy(exitCode = event.code) }
                    mainHandler.post { onExited(id, event.code) }
                }
                is SessionEvent.Bell -> _bells.tryEmit(id)
                is SessionEvent.Clipboard -> mainHandler.post { setClipboard(event.text) }
                is SessionEvent.Failed -> {
                    Log.e(FerruxApplication.TAG, "session $id failed: ${event.message}")
                    updateSession(id) { it.copy(title = "${it.title} (error)") }
                }
                null -> Unit
            }
        }
    }

    /**
     * Like closing a terminal window on `exit`, a session goes away when its process
     * ends, except that (by default) a failed one stays, so its output can be read,
     * with a prompt to restart (Enter) or close (Esc); see [ExitedSessionBackend].
     */
    private fun onExited(id: Long, code: Int) {
        if (_sessions.value.none { it.id == id }) return
        if (FerruxSettings.get(context).keepExited.value.keeps(code)) {
            FerruxNative.printLocal(id, ExitBanner.text(code))
        } else {
            kill(id)
        }
    }

    private inline fun updateSession(id: Long, crossinline change: (SessionInfo) -> SessionInfo) {
        _sessions.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private fun setClipboard(text: String) {
        // TODO(M4): make OSC 52 clipboard writes a user setting (privacy).
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
