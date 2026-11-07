// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.terminal

/** Key and modifier codes shared with `ferrux-android` (must match the Rust side). */
object TerminalKeys {
    const val ENTER = 1
    const val TAB = 2
    const val BACKSPACE = 3
    const val ESCAPE = 4
    const val UP = 5
    const val DOWN = 6
    const val LEFT = 7
    const val RIGHT = 8
    const val HOME = 9
    const val END = 10
    const val PAGE_UP = 11
    const val PAGE_DOWN = 12
    const val INSERT = 13
    const val DELETE = 14

    /** F1 is `F1`, F12 is `F1 + 11`. */
    const val F1 = 101
    const val F12 = 112

    const val MOD_SHIFT = 1
    const val MOD_ALT = 2
    const val MOD_CTRL = 4
    const val MOD_META = 8

    // Mouse buttons and actions for [FerruxNative.mouseEvent] (`ferrux_term::mouse`).
    const val MOUSE_LEFT = 0
    const val MOUSE_MIDDLE = 1
    const val MOUSE_RIGHT = 2
    const val MOUSE_NONE = 3
    const val MOUSE_WHEEL_UP = 4
    const val MOUSE_WHEEL_DOWN = 5
    const val MOUSE_PRESS = 0
    const val MOUSE_RELEASE = 1
    const val MOUSE_MOTION = 2
}

/** Layout of the IntArray filled by [FerruxNative.snapshot] (must match the Rust side). */
object SnapshotLayout {
    const val HEADER_SIZE = 10
    const val INTS_PER_CELL = 4

    // Header indices.
    const val H_ROWS = 0
    const val H_COLS = 1
    const val H_CURSOR_ROW = 2
    const val H_CURSOR_COL = 3
    const val H_CURSOR_SHAPE = 4
    const val H_DISPLAY_OFFSET = 5
    const val H_DEFAULT_BG = 6
    const val H_MODES = 7

    /** Lines of scrollback above the screen (0 on the alternate screen). */
    const val H_HISTORY = 8
    const val H_DEFAULT_FG = 9

    // Cursor shape (low byte) and flags.
    const val CURSOR_HIDDEN = 0
    const val CURSOR_BLOCK = 1
    const val CURSOR_UNDERLINE = 2
    const val CURSOR_BAR = 3
    const val CURSOR_SHAPE_MASK = 0xFF
    const val CURSOR_HOLLOW = 0x100

    // Mode flags.
    const val MODE_APP_CURSOR = 1 shl 0
    const val MODE_BRACKETED_PASTE = 1 shl 1
    const val MODE_ALT_SCREEN = 1 shl 2
    const val MODE_MOUSE = 1 shl 3

    /** DECSET 1007: scrolling on the alternate screen sends arrow keys. */
    const val MODE_ALTERNATE_SCROLL = 1 shl 4

    // Per-cell offsets.
    const val C_CODEPOINT = 0
    const val C_FG = 1
    const val C_BG = 2
    const val C_FLAGS = 3

    // Cell flags.
    const val F_BOLD = 1 shl 0
    const val F_ITALIC = 1 shl 1
    const val F_UNDERLINE = 1 shl 2
    const val F_STRIKEOUT = 1 shl 3
    const val F_DIM = 1 shl 4
    const val F_WIDE = 1 shl 5
    const val F_WIDE_SPACER = 1 shl 6
    const val F_HIDDEN = 1 shl 7
    const val F_DOUBLE_UNDERLINE = 1 shl 8
    const val F_UNDERCURL = 1 shl 9

    /** Zero-width characters follow the base character; see the combining table. */
    const val F_COMBINING = 1 shl 10

    /** Last cell of a soft-wrapped row (the line continues on the next row). */
    const val F_WRAPPED = 1 shl 11

    /** Selected; the cell's background is already the selection colour. */
    const val F_SELECTED = 1 shl 12

    /** Part of an OSC 8 hyperlink (see the link table). */
    const val F_HYPERLINK = 1 shl 13

    /** Part of a URL found in the text (see the link table). */
    const val F_URL = 1 shl 14

    // Link kinds in the link table.
    const val LINK_HYPERLINK = 1
    const val LINK_URL = 2

    /** Ints in the selection record: `kind, startRow, startCol, endRow, endCol`. */
    const val SELECTION_SIZE = 5

    // Selection record kinds.
    const val SELECTION_NONE = 0
    const val SELECTION_TEXT = 1
    const val SELECTION_BLOCK = 2

    /**
     * Minimum size for a [rows]×[cols] snapshot. After the cells come three
     * variable-length sections:
     * 1. the combining table: `count`, then `count` × (`cellIndex, n, codepoint × n`);
     * 2. the selection record ([SELECTION_SIZE] ints, viewport rows, both ends inclusive);
     * 3. the link table: `count`, then `count` × (`startCell, endCell, n, codepoint × n`).
     * When it doesn't fit, [FerruxNative.snapshot] returns the exact size needed.
     */
    fun requiredSize(rows: Int, cols: Int): Int =
        HEADER_SIZE + rows * cols * INTS_PER_CELL + 1 + SELECTION_SIZE + 1

    /** Offset of the combining table in a snapshot with [rows]×[cols] cells. */
    fun combiningOffset(rows: Int, cols: Int): Int = HEADER_SIZE + rows * cols * INTS_PER_CELL

    /**
     * Offset of the selection record in snapshot [s] of length [n], or -1 if
     * the snapshot is truncated.
     */
    fun selectionOffset(s: IntArray, rows: Int, cols: Int, n: Int): Int {
        var i = combiningOffset(rows, cols)
        if (i >= n) return -1
        val count = s[i++]
        repeat(count) {
            if (i + 2 > n) return -1
            i += 2 + s[i + 1]
        }
        return if (i + SELECTION_SIZE <= n) i else -1
    }

    /** One OSC 8 hyperlink: cells [start, end) in row-major order. */
    /** A link (OSC 8 or a URL in the text): cells [start, end) in row-major order. */
    data class Link(val start: Int, val end: Int, val kind: Int, val uri: String) {
        operator fun contains(cell: Int) = cell in start until end
    }

    /** Decodes the link table that starts at [offset] into [out] (cleared first). */
    fun readLinks(s: IntArray, offset: Int, n: Int, out: MutableList<Link>) {
        out.clear()
        if (offset < 0 || offset >= n) return
        var i = offset
        val count = s[i++]
        repeat(count) {
            if (i + 4 > n) return
            val start = s[i]
            val end = s[i + 1]
            val kind = s[i + 2]
            val len = s[i + 3]
            i += 4
            if (len < 0 || i + len > n) return
            val uri = StringBuilder(len)
            for (k in 0 until len) uri.appendCodePoint(s[i + k])
            out.add(Link(start, end, kind, uri.toString()))
            i += len
        }
    }
}

/** Selection kinds for [FerruxNative.startSelection] (must match `ferrux_term::SelectionKind`). */
object SelectionKind {
    const val SIMPLE = 0
    const val WORD = 1
    const val LINE = 2
    const val BLOCK = 3
}

/** A decoded entry from [FerruxNative.pollEvents]. */
sealed interface SessionEvent {
    data class Title(val title: String) : SessionEvent
    data object Bell : SessionEvent
    data class Exited(val code: Int) : SessionEvent
    data class Clipboard(val text: String) : SessionEvent
    data class Failed(val message: String) : SessionEvent

    companion object {
        /** Parses one encoded event; returns null for unknown kinds (forward compatibility). */
        fun parse(raw: String): SessionEvent? {
            if (raw.isEmpty()) return null
            val payload = raw.substring(1)
            return when (raw[0]) {
                'T' -> Title(payload)
                'B' -> Bell
                'X' -> Exited(payload.toIntOrNull() ?: -1)
                'C' -> Clipboard(payload)
                'E' -> Failed(payload)
                else -> null
            }
        }
    }
}
