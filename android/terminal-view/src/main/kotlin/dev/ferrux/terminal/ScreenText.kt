// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.terminal

import dev.ferrux.terminal.SnapshotLayout as L

/**
 * Plain-text helpers for screen readers: the visible rows as strings, the output
 * that is new since the last look, and character/word segments within a row.
 * Pure functions, unit-tested on the JVM.
 */
internal object ScreenText {
    /**
     * The visible rows of a snapshot ([n] ints of [s]) as text, trailing blanks
     * trimmed. Wide characters count once; zero-width characters are left out.
     */
    fun rows(s: IntArray, n: Int): List<String> {
        if (n < L.HEADER_SIZE) return emptyList()
        val rows = s[L.H_ROWS]
        val cols = s[L.H_COLS]
        if (n < L.requiredSize(rows, cols)) return emptyList()
        val out = ArrayList<String>(rows)
        val line = StringBuilder(cols)
        for (row in 0 until rows) {
            line.setLength(0)
            for (col in 0 until cols) {
                val i = L.HEADER_SIZE + (row * cols + col) * L.INTS_PER_CELL
                val flags = s[i + L.C_FLAGS]
                if (flags and L.F_WIDE_SPACER != 0) continue
                val cp = s[i + L.C_CODEPOINT]
                if (cp == 0 || flags and L.F_HIDDEN != 0) line.append(' ') else line.appendCodePoint(cp)
            }
            out += line.trimEnd().toString()
        }
        return out
    }

    /**
     * Lines of [new] that were not on screen in [old]: output that scrolled in or
     * was printed below what was there. The old last line is the one being edited
     * (the prompt and what is typed), so it is never repeated; the typed text has
     * already been spoken by the keyboard. A cleared or replaced screen counts as
     * all new. At most [maxLines] (the latest) are returned.
     */
    fun newOutput(old: List<String>, new: List<String>, maxLines: Int = 12): List<String> {
        val before = old.dropLastWhile { it.isEmpty() }
        val after = new.dropLastWhile { it.isEmpty() }
        if (after.isEmpty()) return emptyList()
        if (before.isEmpty()) return after.takeLast(maxLines)
        // Find how far the old screen scrolled up (shift), comparing all old lines
        // except the last, which may have been edited since.
        val stable = before.dropLast(1)
        var shift = 0
        while (shift <= stable.size) {
            val rest = stable.size - shift
            if (rest <= after.size && (0 until rest).all { stable[shift + it] == after[it] }) break
            shift++
        }
        // after[stable.size - shift] is where the old (edited) last line is now.
        val from = stable.size - shift + 1
        if (shift > stable.size || from > after.size) return after.takeLast(maxLines)
        return after.subList(from, after.size).takeLast(maxLines)
    }

    /** Segment kinds for [next] / [previous], matching AccessibilityNodeInfo granularities. */
    const val CHARACTER = 1
    const val WORD = 2

    /**
     * The next [granularity] segment of [text] at or after [cursor], as
     * `start until end` (UTF-16 indices), or null at the end. Words are runs of
     * non-blank characters.
     */
    fun next(text: String, cursor: Int, granularity: Int): IntRange? {
        var start = cursor.coerceIn(0, text.length)
        if (granularity == WORD) {
            while (start < text.length && text[start].isWhitespace()) start++
            if (start >= text.length) return null
            var end = start
            while (end < text.length && !text[end].isWhitespace()) end++
            return start until end
        }
        if (start >= text.length) return null
        return start until start + Character.charCount(text.codePointAt(start))
    }

    /** The [granularity] segment of [text] ending at or before [cursor], or null at the start. */
    fun previous(text: String, cursor: Int, granularity: Int): IntRange? {
        var end = cursor.coerceIn(0, text.length)
        if (granularity == WORD) {
            while (end > 0 && text[end - 1].isWhitespace()) end--
            if (end <= 0) return null
            var start = end
            while (start > 0 && !text[start - 1].isWhitespace()) start--
            return start until end
        }
        if (end <= 0) return null
        return end - Character.charCount(text.codePointBefore(end)) until end
    }
}
