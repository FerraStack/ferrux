// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app.api

/**
 * JNI bridge to the API core in `libferrux.so` (crates/ferrux-android/src/api.rs,
 * crates/ferrux-host), bound with `RegisterNatives` when `FerruxAppNative` loads the
 * library. `api_natives_match_kotlin_externals` keeps this file and the Rust table in sync.
 *
 * Errors are thrown as `IllegalStateException("E<code>: message")` (see
 * `dev.ferrux.api.ApiErrors`), which Binder passes to the client unchanged. Returned descriptors are owned by the caller.
 */
object ApiNative {
    /** Returns `[handle, pid, stdin, stdout, stderr, pty]`; absent fds are -1. */
    @JvmStatic external fun spawn(
        argv: Array<String>,
        env: Array<String>,
        clearEnv: Boolean,
        cwd: String?,
        pty: Boolean,
        mergeStderr: Boolean,
        rows: Int,
        cols: Int,
        owner: Long,
    ): LongArray

    @JvmStatic external fun resize(handle: Long, rows: Int, cols: Int)

    @JvmStatic external fun signal(handle: Long, signo: Int)

    @JvmStatic external fun kill(handle: Long)

    /** Hangs up and forgets the handle. */
    @JvmStatic external fun close(handle: Long)

    /** Closes every handle of [owner]; returns how many there were. */
    @JvmStatic external fun closeOwner(owner: Long): Int

    /** The owner of [handle], or 0 if there is no such handle. */
    @JvmStatic external fun ownerOf(handle: Long): Long

    @JvmStatic external fun liveCount(): Int

    @JvmStatic external fun ownerLimit(): Int

    /** Blocks up to [timeoutMs]; returns `[handle, owner, exitCode, signal]*`. */
    @JvmStatic external fun awaitExits(timeoutMs: Int): LongArray

    /** `[prefix, home, tmpdir, "1" if bootstrapped else "0"]`. */
    @JvmStatic external fun info(): Array<String>

    /** A NUL-separated record: type, size, mtime ms, mode, path, name, symlink target. */
    @JvmStatic external fun stat(path: String): String

    @JvmStatic external fun list(path: String, offset: Int, limit: Int): Array<String>

    @JvmStatic external fun openFile(path: String, mode: String): Int

    @JvmStatic external fun mkdirs(path: String)

    @JvmStatic external fun rename(from: String, to: String)

    @JvmStatic external fun delete(path: String, recursive: Boolean)
}
