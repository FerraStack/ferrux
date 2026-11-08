// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** First-run bootstrap installation, as shown by the setup screen. */
sealed interface BootstrapState {
    /** Not installed; waiting for the user to start the download. */
    data object Idle : BootstrapState

    /** [total] is -1 while the size is unknown. */
    data class Downloading(val done: Long, val total: Long) : BootstrapState

    data class Verifying(val done: Long, val total: Long) : BootstrapState

    /** Progress in zip entries. */
    data class Extracting(val done: Long, val total: Long) : BootstrapState

    data object Finishing : BootstrapState

    data object Installed : BootstrapState

    data class Failed(val message: String) : BootstrapState
}

/**
 * Downloads the bootstrap pinned in `ferrux-env` (see docs/PACKAGES.md) and
 * installs it through [FerruxAppNative.installBootstrap], which verifies the
 * SHA-256 before extracting anything.
 *
 * The download uses Android's HTTP stack (system proxy and CA settings);
 * verification and extraction run in Rust. One instance per process.
 */
class BootstrapInstaller(private val context: Context) {
    private val _state = MutableStateFlow(
        if (FerruxAppNative.isBootstrapped()) BootstrapState.Installed else BootstrapState.Idle,
    )
    val state: StateFlow<BootstrapState> = _state.asStateFlow()

    @Volatile private var worker: Thread? = null

    /** Whether there is a bootstrap for this device's ABI at all. */
    val available: Boolean get() = FerruxAppNative.bootstrapInfo() != null

    /** Starts (or restarts after a failure) the download and install. */
    @Synchronized
    fun start() {
        if (worker?.isAlive == true || _state.value == BootstrapState.Installed) return
        worker = Thread(::installNow, "ferrux-bootstrap").apply { start() }
    }

    private fun installNow() {
        val info = FerruxAppNative.bootstrapInfo()
        if (info == null) {
            _state.value = BootstrapState.Failed("No bootstrap is available for this device's CPU.")
            return
        }
        val (url, sha256, release) = info
        val archive = File(context.cacheDir, "$release.zip")
        try {
            _state.value = BootstrapState.Downloading(0, -1)
            download(url, archive)
            val progress = ProgressPoller().apply { start() }
            val error = try {
                FerruxAppNative.installBootstrap(archive.absolutePath, sha256)
            } finally {
                progress.finish()
            }
            if (error != null) throw IOException(error)
            _state.value = BootstrapState.Installed
            Log.i(FerruxApplication.TAG, "bootstrap $release installed")
        } catch (e: IOException) {
            Log.e(FerruxApplication.TAG, "bootstrap installation failed", e)
            _state.value = BootstrapState.Failed(e.message ?: e.toString())
        } finally {
            // Successful or not, the archive is not needed again: a retry
            // downloads afresh rather than trusting a partial or corrupt file.
            archive.delete()
        }
    }

    private fun download(url: String, dest: File) {
        val part = File(dest.path + ".part")
        val connection = URL(url).openConnection() as HttpURLConnection
        // GitHub redirects release assets to its CDN (HTTPS → HTTPS, followed
        // automatically).
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("User-Agent", "Ferrux")
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("Download failed: HTTP $code from ${connection.url.host}")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (done - lastReport >= 256 * 1024 || done == total) {
                            lastReport = done
                            _state.value = BootstrapState.Downloading(done, total)
                        }
                    }
                    if (total >= 0 && done != total) {
                        throw IOException("Download interrupted ($done of $total bytes)")
                    }
                }
            }
            if (!part.renameTo(dest)) throw IOException("Cannot move the download into place")
        } finally {
            connection.disconnect()
            part.delete()
        }
    }

    /** Mirrors [FerruxAppNative.bootstrapProgress] into [state] while installing. */
    private inner class ProgressPoller : Thread("ferrux-bootstrap-progress") {
        @Volatile private var done = false

        override fun run() {
            while (!done) {
                val (stage, current, total) = FerruxAppNative.bootstrapProgress().map(String::toLong)
                when (stage) {
                    1L -> _state.value = BootstrapState.Verifying(current, total)
                    2L -> _state.value = BootstrapState.Extracting(current, total)
                    3L -> _state.value = BootstrapState.Finishing
                }
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }

        fun finish() {
            done = true
            interrupt()
            join()
        }
    }
}
