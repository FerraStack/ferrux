// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.terminal

/**
 * JNI bridge to `libferrux.so` (crates/ferrux-android).
 *
 * All functions are thread-safe on the Rust side. Session ids are process-global
 * and stay valid until [destroySession]; calls with an unknown id are no-ops
 * (or return 0 / null).
 */
object FerruxNative {
    /**
     * Non-null when `libferrux.so` could not be loaded (for example when the APK was
     * built with `-Pferrux.skipCargo=true`). Every `external` call will then throw
     * [UnsatisfiedLinkError], so callers should check this first.
     */
    @JvmStatic
    val loadError: Throwable? =
        try {
            System.loadLibrary("ferrux")
            null
        } catch (e: UnsatisfiedLinkError) {
            e
        }

    /** Initialises logging and the filesystem layout. Idempotent. */
    @JvmStatic external fun init(filesDir: String, nativeLibDir: String)

    /** Program to run for a new interactive session. */
    @JvmStatic external fun defaultShell(): String

    /** Default session environment as `KEY=VALUE` strings. */
    @JvmStatic external fun defaultEnv(): Array<String>

    /**
     * Spawns [program] (with [args], which exclude argv[0]) on a new PTY.
     *
     * @return a session id > 0
     * @throws java.io.IOException if the PTY or process could not be created
     */
    @JvmStatic external fun createSession(
        program: String,
        args: Array<String>,
        env: Array<String>,
        cwd: String,
        rows: Int,
        cols: Int,
    ): Long

    /** Hangs up the session's process group and forgets the session. */
    @JvmStatic external fun destroySession(id: Long)

    /** Writes raw bytes to the PTY. */
    @JvmStatic external fun write(id: Long, data: ByteArray)

    /** Sends text, encoding every character with [mods] (see [TerminalKeys]). */
    @JvmStatic external fun sendText(id: Long, text: String, mods: Int)

    /** Sends a special key (see [TerminalKeys]). Returns false if the key has no encoding. */
    @JvmStatic external fun sendKey(id: Long, key: Int, mods: Int): Boolean

    /** Pastes text, using bracketed paste if the application enabled it. */
    @JvmStatic external fun paste(id: Long, text: String)

    @JvmStatic external fun resize(id: Long, rows: Int, cols: Int)

    /** Scrolls the viewport; positive [lines] scroll back into history. */
    @JvmStatic external fun scroll(id: Long, lines: Int)

    /**
     * Copies the visible screen into [out] using the [SnapshotLayout] format.
     *
     * @return the number of ints written, `-(needed size)` if [out] is too small,
     *   or 0 if there is no such session.
     */
    @JvmStatic external fun snapshot(id: Long, out: IntArray): Int

    /** The visible screen as text (rows joined by `\n`), or null if there is no such session. */
    @JvmStatic external fun screenText(id: Long): String?

    /**
     * Blocks until the global change counter (bumped on output or events in any
     * session) differs from [lastSeq], or [timeoutMs] elapses. Returns the current counter.
     */
    @JvmStatic external fun awaitChange(lastSeq: Long, timeoutMs: Int): Long

    /**
     * Drains pending events. Each entry is a kind character followed by its payload:
     * `T<title>`, `B` (bell), `X<exit code>`, `C<text>` (OSC 52 clipboard store).
     * See [SessionEvent.parse].
     */
    @JvmStatic external fun pollEvents(id: Long): Array<String>?
}
