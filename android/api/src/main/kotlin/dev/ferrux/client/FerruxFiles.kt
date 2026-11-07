// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.client

import android.os.ParcelFileDescriptor
import dev.ferrux.api.FileStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * File operations confined to Ferrux's `$HOME` (docs/API.md §6.5). Paths are
 * absolute or relative to `$HOME`; anything resolving outside fails with
 * [FerruxException.Api.isOutsideHome].
 */
class FerruxFiles internal constructor(private val connection: FerruxConnection) {
    /** Describes a file; a symlink is described itself (`lstat`). */
    suspend fun stat(path: String): FileStat = connection.remote { it.stat(path) }

    /** The whole directory, sorted by name (fetched in pages). */
    suspend fun list(path: String): List<FileStat> {
        val all = mutableListOf<FileStat>()
        while (true) {
            val page = connection.remote { it.list(path, all.size, PAGE) }
            all += page
            if (page.size < PAGE) return all
        }
    }

    /** Opens a file; [mode] as in `ParcelFileDescriptor.parseMode` ("r", "w", "wa", "rw", "rwt"). */
    suspend fun open(path: String, mode: String): ParcelFileDescriptor =
        connection.remote { it.openFile(path, mode) }

    suspend fun readBytes(path: String): ByteArray = open(path, "r").let { pfd ->
        withContext(Dispatchers.IO) { ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() } }
    }

    suspend fun readText(path: String): String = readBytes(path).decodeToString()

    /** Creates or truncates [path] and writes [data]. */
    suspend fun writeBytes(path: String, data: ByteArray) {
        val pfd = open(path, "w")
        withContext(Dispatchers.IO) { ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { it.write(data) } }
    }

    suspend fun writeText(path: String, text: String) = writeBytes(path, text.encodeToByteArray())

    /** Writes to a temporary file next to [path], then renames it over [path]. */
    suspend fun writeAtomically(path: String, data: ByteArray) {
        val slash = path.lastIndexOf('/')
        val dir = if (slash >= 0) path.substring(0, slash + 1) else ""
        val tmp = "$dir.${path.substring(slash + 1)}.ferrux-${System.nanoTime()}.tmp"
        writeBytes(tmp, data)
        try {
            rename(tmp, path)
        } catch (e: FerruxException) {
            runCatching { delete(tmp) }
            throw e
        }
    }

    /** `mkdir -p`. */
    suspend fun mkdirs(path: String) = connection.remote { it.mkdirs(path) }

    suspend fun rename(from: String, to: String) = connection.remote { it.rename(from, to) }

    /** Deletes a file, symlink or directory (non-empty ones only with [recursive]). */
    suspend fun delete(path: String, recursive: Boolean = false) =
        connection.remote { it.delete(path, recursive) }

    private companion object {
        const val PAGE = 500
    }
}
