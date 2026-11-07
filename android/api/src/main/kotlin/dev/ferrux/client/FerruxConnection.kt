// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.client

import android.content.Context
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import dev.ferrux.api.FerruxInfo
import dev.ferrux.api.IFerruxApi
import dev.ferrux.api.IProcessCallback
import dev.ferrux.api.SessionInfo
import dev.ferrux.api.SpawnRequest
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class ConnectionState { Connected, Disconnected, Closed }

/** What to run; see `SpawnRequest` in docs/API.md §5. */
data class ProcessSpec(
    val argv: List<String>,
    val env: Map<String, String> = emptyMap(),
    val clearEnv: Boolean = false,
    /** Absolute or `$HOME`-relative; null = `$HOME`. */
    val cwd: String? = null,
    /** Non-null = PTY mode with this initial size. */
    val pty: PtySize? = null,
    val mergeStderr: Boolean = false,
)

data class PtySize(val rows: Int, val cols: Int)

class ExecResult(val stdout: String, val stderr: String, val status: ExitStatus)

/**
 * A bound connection to Ferrux. All suspending calls run on [Dispatchers.IO].
 * [close] unbinds; Ferrux then closes every process started through this connection.
 */
class FerruxConnection internal constructor(private val context: Context) : Closeable {
    @Volatile internal lateinit var api: IFerruxApi
        private set
    private var serviceConnection: ServiceConnection? = null
    private val closed = AtomicBoolean(false)

    /** Ferrux's version, paths and bootstrap state at connect time. */
    lateinit var info: FerruxInfo
        internal set

    private val _state = MutableStateFlow(ConnectionState.Connected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val processes = ConcurrentHashMap<Long, FerruxProcess>()
    /** Exits that arrived before `spawn` returned (onExit is oneway and can race it). */
    private val earlyExits = ConcurrentHashMap<Long, ExitStatus>()

    /** One callback for the whole connection: Ferrux's lifetime anchor for our processes. */
    private val callback = object : IProcessCallback.Stub() {
        override fun onExit(handle: Long, exitCode: Int, signal: Int) {
            val status = ExitStatus(exitCode, signal)
            val process = processes[handle]
            if (process != null) {
                process.onExit(status)
            } else {
                earlyExits[handle] = status
                // Registered meanwhile?
                processes[handle]?.let { p -> earlyExits.remove(handle)?.let(p::onExit) }
            }
        }
    }

    private val death = IBinder.DeathRecipient { onDisconnected() }

    internal fun attach(connection: ServiceConnection, service: IFerruxApi) {
        serviceConnection = connection
        api = service
        runCatching { service.asBinder().linkToDeath(death, 0) }
    }

    internal fun onReconnected(service: IBinder) {
        // Processes didn't survive; a new connection is needed for new work.
        onDisconnected()
    }

    internal fun onDisconnected() {
        if (_state.value == ConnectionState.Connected) _state.value = ConnectionState.Disconnected
        val gone = FerruxException.Disconnected()
        processes.values.forEach { it.onDisconnected(gone) }
    }

    internal fun forget(handle: Long) {
        processes.remove(handle)
    }

    private suspend fun <T> call(block: (IFerruxApi) -> T): T {
        if (_state.value != ConnectionState.Connected) throw FerruxException.Disconnected()
        return withContext(Dispatchers.IO) { FerruxException.wrap { block(api) } }
    }

    /** Fresh `getInfo()` (e.g. to see the bootstrap state change). */
    suspend fun refreshInfo(): FerruxInfo = call { it.info }.also { info = it }

    /** Starts a program. Close the returned process when done with it. */
    suspend fun spawn(spec: ProcessSpec): FerruxProcess {
        val request = SpawnRequest().apply {
            argv = spec.argv.toTypedArray()
            env = spec.env.map { (k, v) -> "$k=$v" }.toTypedArray()
            clearEnv = spec.clearEnv
            cwd = spec.cwd
            mode = if (spec.pty != null) SpawnRequest.MODE_PTY else SpawnRequest.MODE_PIPES
            mergeStderr = spec.mergeStderr
            spec.pty?.let {
                rows = it.rows
                cols = it.cols
            }
        }
        val result = call { it.spawn(request, callback) }
        val process = FerruxProcess(this, result)
        processes[process.handle] = process
        earlyExits.remove(process.handle)?.let(process::onExit)
        return process
    }

    /**
     * Runs a program to completion with pipes and collects its output, e.g.
     * `exec(listOf("git", "status", "--porcelain"), cwd = "project")`.
     */
    suspend fun exec(
        argv: List<String>,
        cwd: String? = null,
        env: Map<String, String> = emptyMap(),
        stdin: ByteArray? = null,
    ): ExecResult = spawn(ProcessSpec(argv, env = env, cwd = cwd)).use { p ->
        coroutineScope {
            val out = async(Dispatchers.IO) { p.stdout!!.readBytes() }
            val err = async(Dispatchers.IO) { p.stderr!!.readBytes() }
            withContext(Dispatchers.IO) {
                p.stdin!!.use { s -> stdin?.let(s::write) }
            }
            val status = p.await()
            ExecResult(out.await().decodeToString(), err.await().decodeToString(), status)
        }
    }

    /** Terminal sessions shown in the Ferrux app (capability "attach"). */
    suspend fun sessions(): List<SessionInfo> = call { it.listSessions().toList() }

    /** Attaches to a Ferrux session; the process's [FerruxProcess.pty] is a socket. */
    suspend fun attach(sessionId: Long, rows: Int, cols: Int): FerruxProcess {
        val result = call { it.attachSession(sessionId, rows, cols, callback) }
        val process = FerruxProcess.attached(this, result)
        processes[process.handle] = process
        earlyExits.remove(process.handle)?.let(process::onExit)
        return process
    }

    /** File operations under Ferrux's `$HOME`. */
    val files: FerruxFiles = FerruxFiles(this)

    /**
     * Keeps Ferrux in the foreground (its own notification) until the returned handle
     * is closed or this app dies. Call while your app is visible (docs/API.md §6.7).
     */
    suspend fun keepAlive(reason: String): Closeable {
        val token = Binder("ferrux-keepalive")
        call { it.acquireKeepAlive(token, reason) }
        val released = AtomicBoolean(false)
        return Closeable {
            if (released.compareAndSet(false, true) && _state.value == ConnectionState.Connected) {
                runCatching { api.releaseKeepAlive(token) }
            }
        }
    }

    internal suspend fun <T> remote(block: (IFerruxApi) -> T): T = call(block)

    /** Unbinds. Ferrux hangs up every process started through this connection. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        processes.values.toList().forEach { runCatching { it.close() } }
        _state.value = ConnectionState.Closed
        runCatching { api.asBinder().unlinkToDeath(death, 0) }
        serviceConnection?.let { runCatching { context.unbindService(it) } }
    }
}
