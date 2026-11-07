// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.client

import android.os.ParcelFileDescriptor
import dev.ferrux.api.AttachResult
import dev.ferrux.api.SpawnResult
import java.io.Closeable
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * How a process ended: [exitCode] 0..255 for a normal exit, or -1 with the
 * terminating [signal] (9 when killed, e.g. by Android's phantom-process limit).
 */
data class ExitStatus(val exitCode: Int, val signal: Int) {
    val signaled: Boolean get() = signal != 0

    /** Shell-style code: the exit status, or 128 + signal. */
    val shellCode: Int get() = if (signaled) 128 + signal else exitCode
}

/** Raw descriptors handed to native code (e.g. Ferride's Rust host); -1 = absent. */
data class RawFds(val stdin: Int, val stdout: Int, val stderr: Int, val pty: Int, val ptyIsSocket: Boolean)

/**
 * A program running in Ferrux. Pipes mode has [stdin]/[stdout]/[stderr]; PTY mode has
 * [pty] (the PTY master, or a socket for attached sessions when [ptyIsSocket]).
 * [close] hangs it up (SIGHUP, then SIGKILL after 3 s) and closes our descriptors.
 */
class FerruxProcess private constructor(
    private val connection: FerruxConnection,
    val handle: Long,
    val pid: Int,
    /** Ferrux session id for shared/attached sessions, else 0. */
    val sessionId: Long,
    private var stdinFd: ParcelFileDescriptor?,
    private var stdoutFd: ParcelFileDescriptor?,
    private var stderrFd: ParcelFileDescriptor?,
    private var ptyFd: ParcelFileDescriptor?,
    val ptyIsSocket: Boolean,
) : Closeable {
    internal constructor(connection: FerruxConnection, r: SpawnResult) : this(
        connection, r.handle, r.pid, r.sessionId, r.stdin, r.stdout, r.stderr, r.pty, r.ptyIsSocket,
    )

    internal companion object {
        fun attached(connection: FerruxConnection, r: AttachResult) =
            FerruxProcess(connection, r.handle, 0, 0, null, null, null, r.socket, true)
    }

    private val exitDeferred = CompletableDeferred<ExitStatus>()
    private val closed = AtomicBoolean(false)

    /** Completes when the program exits. */
    val exit: Deferred<ExitStatus> get() = exitDeferred

    suspend fun await(): ExitStatus = exitDeferred.await()

    internal fun onExit(status: ExitStatus) {
        exitDeferred.complete(status)
    }

    internal fun onDisconnected(e: FerruxException) {
        exitDeferred.completeExceptionally(e)
    }

    /** The program's stdin (pipes mode). Closing it sends EOF. */
    val stdin: OutputStream? by lazy { stdinFd?.let { ParcelFileDescriptor.AutoCloseOutputStream(it) } }

    /** The program's stdout (pipes mode; includes stderr with `mergeStderr`). */
    val stdout: InputStream? by lazy { stdoutFd?.let { ParcelFileDescriptor.AutoCloseInputStream(it) } }

    val stderr: InputStream? by lazy { stderrFd?.let { ParcelFileDescriptor.AutoCloseInputStream(it) } }

    /** The terminal (PTY mode). Read/write it with [ptyInput]/[ptyOutput] or pass its fd. */
    val pty: ParcelFileDescriptor? get() = ptyFd

    /** Terminal output; does not take ownership of [pty]. */
    fun ptyInput(): InputStream? = ptyFd?.let { FileInputStream(it.fileDescriptor) }

    /** Terminal input; does not take ownership of [pty]. */
    fun ptyOutput(): OutputStream? = ptyFd?.let { java.io.FileOutputStream(it.fileDescriptor) }

    /** stdout (pipes) or terminal output (PTY) as chunks, until EOF. Collect once. */
    fun output(): Flow<ByteArray> = flow {
        val input = stdout ?: ptyInput() ?: return@flow
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = try {
                input.read(buf)
            } catch (_: java.io.IOException) {
                -1 // EIO once the PTY's last slave is closed
            }
            if (n < 0) break
            if (n > 0) emit(buf.copyOf(n))
        }
    }.flowOn(Dispatchers.IO)

    /** Sets the PTY window size (sends SIGWINCH). */
    suspend fun resize(rows: Int, cols: Int) = connection.remote { it.resize(handle, rows, cols) }

    /** Sends a signal to the process group (terminal signals go to the foreground job). */
    suspend fun signal(signo: Int) = connection.remote { it.signal(handle, signo) }

    /** SIGKILL to the process group. */
    suspend fun kill() = connection.remote { it.kill(handle) }

    /**
     * Hands the descriptors to native code, which then owns (and must close) them.
     * Call before touching the stream properties.
     */
    fun detachFds(): RawFds {
        fun take(pfd: ParcelFileDescriptor?) = pfd?.detachFd() ?: -1
        val fds = RawFds(take(stdinFd), take(stdoutFd), take(stderrFd), take(ptyFd), ptyIsSocket)
        stdinFd = null
        stdoutFd = null
        stderrFd = null
        ptyFd = null
        return fds
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        connection.forget(handle)
        if (connection.state.value == ConnectionState.Connected) {
            runCatching { connection.api.close(handle) }
        }
        listOf(stdinFd, stdoutFd, stderrFd, ptyFd).forEach { runCatching { it?.close() } }
    }
}
