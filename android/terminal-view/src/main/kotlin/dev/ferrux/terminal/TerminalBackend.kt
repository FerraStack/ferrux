// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.terminal

/**
 * What [TerminalView] talks to. Decouples the widget from where the terminal
 * actually lives: an in-process `libferrux` session ([NativeSessionBackend]),
 * or later a remote session (e.g. Ferride rendering a PTY owned by Ferrux).
 *
 * Implementations must be callable from the UI thread without blocking for long.
 * Selection support is optional: the defaults make a backend read-only.
 */
interface TerminalBackend {
    /** See [FerruxNative.snapshot] for the contract and [SnapshotLayout] for the format. */
    fun snapshot(out: IntArray): Int

    fun sendKey(key: Int, mods: Int): Boolean

    fun sendText(text: String, mods: Int)

    fun paste(text: String)

    fun write(data: ByteArray)

    fun resize(rows: Int, cols: Int)

    fun scroll(lines: Int)

    /** See [FerruxNative.startSelection]. */
    fun startSelection(kind: Int, row: Int, col: Int): Boolean = false

    fun updateSelection(row: Int, col: Int) = Unit

    fun setSelection(startRow: Int, startCol: Int, endRow: Int, endCol: Int) = Unit

    fun selectAll() = Unit

    fun clearSelection() = Unit

    /** See [FerruxNative.selectionText]. */
    fun selectionText(): String? = null
}

/** [TerminalBackend] backed by a session in `libferrux.so`. */
class NativeSessionBackend(val sessionId: Long) : TerminalBackend {
    override fun snapshot(out: IntArray): Int = FerruxNative.snapshot(sessionId, out)

    override fun sendKey(key: Int, mods: Int): Boolean = FerruxNative.sendKey(sessionId, key, mods)

    override fun sendText(text: String, mods: Int) = FerruxNative.sendText(sessionId, text, mods)

    override fun paste(text: String) = FerruxNative.paste(sessionId, text)

    override fun write(data: ByteArray) = FerruxNative.write(sessionId, data)

    override fun resize(rows: Int, cols: Int) = FerruxNative.resize(sessionId, rows, cols)

    override fun scroll(lines: Int) = FerruxNative.scroll(sessionId, lines)

    override fun startSelection(kind: Int, row: Int, col: Int): Boolean =
        FerruxNative.startSelection(sessionId, kind, row, col)

    override fun updateSelection(row: Int, col: Int) = FerruxNative.updateSelection(sessionId, row, col)

    override fun setSelection(startRow: Int, startCol: Int, endRow: Int, endCol: Int) =
        FerruxNative.setSelection(sessionId, startRow, startCol, endRow, endCol, false)

    override fun selectAll() = FerruxNative.selectAll(sessionId)

    override fun clearSelection() = FerruxNative.clearSelection(sessionId)

    override fun selectionText(): String? = FerruxNative.selectionText(sessionId)
}

/**
 * Source of one-shot ("sticky") modifiers, e.g. CTRL/ALT toggles on an extra-keys row.
 * [consume] returns the active modifiers (a [TerminalKeys] `MOD_*` mask) and clears
 * any that should only apply to a single key press.
 */
fun interface StickyModifiers {
    fun consume(): Int

    /** Whether any modifier is armed, i.e. the next key will be modified. */
    fun isActive(): Boolean = false
}
