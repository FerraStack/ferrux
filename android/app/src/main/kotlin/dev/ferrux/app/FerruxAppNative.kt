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

    /** Program to run for a new interactive session. */
    @JvmStatic external fun defaultShell(): String

    /** Default session environment as `KEY=VALUE` strings. */
    @JvmStatic external fun defaultEnv(): Array<String>

    /**
     * Spawns [program] (with [args], which exclude argv[0]) on a new PTY.
     *
     * @return a session id > 0, usable with [TerminalNative]
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
}
