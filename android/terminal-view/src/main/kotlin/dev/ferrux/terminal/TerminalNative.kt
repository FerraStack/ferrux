// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.terminal

/**
 * The terminal natives (crates/ferrux-jni-core): sessions, input, snapshots,
 * selection and events. Nothing here knows about Ferrux's Linux environment, so
 * any app embedding `terminal-view` can use it (see docs/REUSE.md).
 *
 * The natives are bound with `RegisterNatives` by whichever library the host
 * loads with [load]: `libferruxterm.so` (shipped with `terminal-view`) by default,
 * or the host's own library that links `ferrux-jni-core` (Ferrux: `libferrux.so`).
 *
 * All functions are thread-safe on the Rust side. Session ids are process-global
 * and stay valid until [destroySession]; calls with an unknown id are no-ops
 * (or return 0 / null).
 */
object TerminalNative {
    /** The terminal-only library packaged with `terminal-view`. */
    const val DEFAULT_LIBRARY = "ferruxterm"

    private var loaded = false
    private var error: Throwable? = null

    /**
     * Loads the native library that registers these natives, once per process;
     * later calls return the first result whatever [library] they name. Returns
     * the load error, or null on success. Hosts with their own library (Ferrux,
     * Ferride) call this with its name before anything else touches this object.
     */
    @JvmStatic
    @JvmOverloads
    @Synchronized
    fun load(library: String = DEFAULT_LIBRARY): Throwable? {
        if (!loaded) {
            loaded = true
            error =
                try {
                    System.loadLibrary(library)
                    null
                } catch (e: UnsatisfiedLinkError) {
                    e
                }
        }
        return error
    }

    /**
     * Non-null when the native library could not be loaded (for example when the
     * APK was built with `-Pferrux.skipCargo=true`). Every `external` call would
     * then throw [UnsatisfiedLinkError], so callers should check this first. Loads
     * [DEFAULT_LIBRARY] if [load] wasn't called yet.
     */
    @JvmStatic
    val loadError: Throwable?
        get() = load()

    /**
     * Adopts a PTY master [fd] (e.g. from `ParcelFileDescriptor.detachFd()`, such as
     * one returned by the Ferrux API) as a session without a local process. Takes
     * ownership of [fd]; [destroySession] closes it.
     *
     * @return a session id > 0
     * @throws java.io.IOException if the fd can't be used as a terminal
     */
    @JvmStatic external fun attachPty(fd: Int, rows: Int, cols: Int): Long

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
     * A scroll gesture (drag, fling, wheel) at a viewport cell; positive [lines] go
     * towards older output. Scrolls history on the main screen, sends Up/Down keys
     * on the alternate screen (DECSET 1007), or wheel events when the program
     * enabled mouse reporting.
     */
    @JvmStatic external fun scrollGesture(id: Long, lines: Int, row: Int, col: Int)

    /**
     * Reports a mouse event (see the `MOUSE_*` codes in [TerminalKeys]) at a viewport
     * cell. Returns false when the program did not ask for this kind of event.
     */
    @JvmStatic external fun mouseEvent(id: Long, button: Int, action: Int, row: Int, col: Int, mods: Int): Boolean

    /**
     * Copies the visible screen into [out] using the [SnapshotLayout] format.
     *
     * @return the number of ints written, `-(needed size)` if [out] is too small,
     *   or 0 if there is no such session.
     */
    @JvmStatic external fun snapshot(id: Long, out: IntArray): Int

    /**
     * Starts a selection at a viewport cell. [kind] is a [SelectionKind]: WORD and
     * LINE immediately cover the word or line there. Returns false for an unknown
     * session or kind. Selection calls don't wake [awaitChange]; redraw yourself.
     */
    @JvmStatic external fun startSelection(id: Long, kind: Int, row: Int, col: Int): Boolean

    /** Moves the free end of the selection; word/line selections keep whole words/lines. */
    @JvmStatic external fun updateSelection(id: Long, row: Int, col: Int)

    /** Selects the cells from start to end (inclusive, either order); rows may be off-screen. */
    @JvmStatic external fun setSelection(
        id: Long,
        startRow: Int,
        startCol: Int,
        endRow: Int,
        endCol: Int,
        block: Boolean,
    )

    /** Selects the whole scrollback and screen. */
    @JvmStatic external fun selectAll(id: Long)

    @JvmStatic external fun clearSelection(id: Long)

    /**
     * The selected text (soft wraps rejoined, trailing whitespace dropped), or null
     * when nothing but blanks is selected.
     */
    @JvmStatic external fun selectionText(id: Long): String?

    /** The visible screen as text (rows joined by `\n`), or null if there is no such session. */
    @JvmStatic external fun screenText(id: Long): String?

    /** Shows [text] on the session's screen without sending it to the program. */
    @JvmStatic external fun feedLocal(id: Long, text: String)

    /** The session process's working directory while it runs, else null. */
    @JvmStatic external fun cwd(id: Long): String?

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
