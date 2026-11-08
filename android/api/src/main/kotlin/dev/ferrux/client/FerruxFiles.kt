// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.client

import android.os.ParcelFileDescriptor
import dev.ferrux.api.FileStat
import dev.ferrux.api.IFerruxApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
        connection.callOwned({ it.openFile(path, mode) }) { it.close() }

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

    /**
     * Replaces the contents of [path] atomically: writes a temporary file next to the
     * real target, gives it the target's permission bits (so a script keeps `+x`),
     * then renames it over the target. If [path] is a symlink, the file it points to
     * is replaced and the link stays a link. New files get mode 0600.
     */
    suspend fun writeAtomically(path: String, data: ByteArray) {
        val target = resolveSymlinks(path)
        val existing = try {
            stat(target)
        } catch (e: FerruxException.Api) {
            if (!e.isNotFound) throw e
            null
        }
        val slash = target.lastIndexOf('/')
        val dir = if (slash >= 0) target.substring(0, slash + 1) else ""
        val tmp = "$dir.${target.substring(slash + 1)}.ferrux-${System.nanoTime()}.tmp"
        writeBytes(tmp, data)
        try {
            if (existing != null && existing.mode != NEW_FILE_MODE) chmod(tmp, existing.mode)
            rename(tmp, target)
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { delete(tmp) } }
            throw e
        }
    }

    /** Follows symlinks from [path] (up to 16) to the file they name; Ferrux confines each step. */
    private suspend fun resolveSymlinks(path: String): String {
        var p = path
        repeat(MAX_LINKS) {
            val st = try {
                stat(p)
            } catch (e: FerruxException.Api) {
                if (e.isNotFound) return p
                throw e
            }
            val target = st.symlinkTarget
            if (st.type != FileStat.TYPE_SYMLINK || target == null) return st.path
            p = resolveLinkTarget(st.path, target)
        }
        throw FerruxException.Api(IFerruxApi.ERR_INVALID_ARGUMENT, "$path: too many levels of symbolic links")
    }

    /** Sets the permission bits (`mode and 0o7777`), e.g. `0b111_101_101` for rwxr-xr-x. */
    suspend fun chmod(path: String, mode: Int) = connection.remote { it.chmod(path, mode) }

    /** `mkdir -p`. */
    suspend fun mkdirs(path: String) = connection.remote { it.mkdirs(path) }

    suspend fun rename(from: String, to: String) = connection.remote { it.rename(from, to) }

    /** Deletes a file, symlink or directory (non-empty ones only with [recursive]). */
    suspend fun delete(path: String, recursive: Boolean = false) =
        connection.remote { it.delete(path, recursive) }

    private companion object {
        const val PAGE = 500
        const val MAX_LINKS = 16
        const val NEW_FILE_MODE = 0x180 // 0600
    }
}
