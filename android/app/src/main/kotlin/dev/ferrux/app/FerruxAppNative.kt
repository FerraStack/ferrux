// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app

import dev.ferrux.terminal.TerminalNative

/**
 * The Ferrux app natives in `libferrux.so` (crates/ferrux-android): Ferrux's Linux
 * environment and spawning with it. The terminal natives (input, snapshots, …) are
 * on [TerminalNative], which `libferrux.so` also registers. See docs/REUSE.md.
 */
object FerruxAppNative {
    /**
     * Loads `libferrux.so` (once) and returns the load error, or null. Check this
     * before any other native call; it also makes [TerminalNative] use this library.
     */
    @JvmStatic
    val loadError: Throwable?
        get() = TerminalNative.load("ferrux")

    /** Initialises the filesystem layout. Idempotent. */
    @JvmStatic external fun init(filesDir: String, nativeLibDir: String)

    /** Whether the bootstrap (`$PREFIX` with a shell) is installed. */
    @JvmStatic external fun isBootstrapped(): Boolean

    /**
     * The bootstrap pinned for this ABI as `[url, sha256, release]`, or null if
     * there is none (unsupported ABI).
     */
    @JvmStatic external fun bootstrapInfo(): Array<String>?

    /**
     * Verifies [archive] against [sha256] and installs it as `$PREFIX` (staged,
     * then atomically renamed). Blocking; call off the main thread.
     *
     * @return null on success, otherwise an error message
     */
    @JvmStatic external fun installBootstrap(archive: String, sha256: String): String?

    /**
     * Progress of a running [installBootstrap] as decimal `[stage, done, total]`;
     * stage 0 = idle, 1 = verifying (bytes), 2 = extracting (files), 3 = finishing.
     */
    @JvmStatic external fun bootstrapProgress(): Array<String>

    /** Program to run for a new interactive session. */
    @JvmStatic external fun defaultShell(): String

    /**
     * Launch specification for the default interactive shell: `[program, arg0, ...args]`,
     * where `arg0` is empty when `argv[0]` should default to `program`.
     */
    @JvmStatic external fun defaultShellCommand(): Array<String>

    /** Default session environment as `KEY=VALUE` strings. */
    @JvmStatic external fun defaultEnv(): Array<String>

    /**
     * Spawns [program] (with optional [arg0] for `argv[0]`, and [args] after it) on a new PTY.
     *
     * @return a session id > 0, usable with [TerminalNative]
     * @throws java.io.IOException if the PTY or process could not be created
     */
    @JvmStatic external fun createSession(
        program: String,
        arg0: String?,
        args: Array<String>,
        env: Array<String>,
        cwd: String,
        rows: Int,
        cols: Int,
    ): Long
}
