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
}

/** Layout of the IntArray filled by [FerruxNative.snapshot] (must match the Rust side). */
object SnapshotLayout {
    const val HEADER_SIZE = 8
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

    fun requiredSize(rows: Int, cols: Int): Int = HEADER_SIZE + rows * cols * INTS_PER_CELL
}

/** A decoded entry from [FerruxNative.pollEvents]. */
sealed interface SessionEvent {
    data class Title(val title: String) : SessionEvent
    data object Bell : SessionEvent
    data class Exited(val code: Int) : SessionEvent
    data class Clipboard(val text: String) : SessionEvent

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
                else -> null
            }
        }
    }
}
