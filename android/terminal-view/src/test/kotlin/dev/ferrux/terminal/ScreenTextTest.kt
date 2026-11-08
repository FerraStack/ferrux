// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.terminal

import dev.ferrux.terminal.SnapshotLayout as L
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenTextTest {
    /** A snapshot of [lines] (padded to [cols]); `\u0000` marks a wide character's spacer. */
    private fun snapshot(cols: Int, vararg lines: String): Pair<IntArray, Int> {
        val rows = lines.size
        val s = IntArray(L.requiredSize(rows, cols))
        s[L.H_ROWS] = rows
        s[L.H_COLS] = cols
        lines.forEachIndexed { row, line ->
            var col = 0
            var i = 0
            while (i < line.length && col < cols) {
                val cp = line.codePointAt(i)
                i += Character.charCount(cp)
                val cell = L.HEADER_SIZE + (row * cols + col) * L.INTS_PER_CELL
                if (cp == 0) s[cell + L.C_FLAGS] = L.F_WIDE_SPACER else s[cell + L.C_CODEPOINT] = cp
                col++
            }
        }
        return s to L.requiredSize(rows, cols)
    }

    @Test
    fun rowsAreTrimmedAndSkipWideSpacers() {
        val (s, n) = snapshot(8, "~ \$ ls", "", "日\u0000本\u0000 x")
        assertEquals(listOf("~ \$ ls", "", "日本 x"), ScreenText.rows(s, n))
    }

    @Test
    fun aTooShortSnapshotHasNoRows() {
        assertEquals(emptyList<String>(), ScreenText.rows(IntArray(3), 3))
    }

    @Test
    fun newOutputIsWhatAppearedBelowTheEditedLine() {
        val typing = listOf("~ \$ echo hi")
        val done = listOf("~ \$ echo hi", "hi", "~ \$", "")
        assertEquals(listOf("hi", "~ \$"), ScreenText.newOutput(typing, done))
    }

    @Test
    fun newOutputFollowsScrolling() {
        val old = listOf("a", "b", "c", "~ \$ seq 2")
        val new = listOf("b", "c", "~ \$ seq 2", "1", "2", "~ \$")
        assertEquals(listOf("1", "2", "~ \$"), ScreenText.newOutput(old, new))
    }

    @Test
    fun typingAloneIsNotNewOutput() {
        assertEquals(emptyList<String>(), ScreenText.newOutput(listOf("x", "~ \$ ec"), listOf("x", "~ \$ echo")))
        assertEquals(emptyList<String>(), ScreenText.newOutput(listOf("x", "~ \$"), listOf("x", "~ \$")))
    }

    @Test
    fun aReplacedScreenIsAllNewAndCapped() {
        val old = listOf("one", "two", "~ \$ vim")
        val new = (1..20).map { "line $it" }
        assertEquals((9..20).map { "line $it" }, ScreenText.newOutput(old, new))
        assertEquals(listOf("first"), ScreenText.newOutput(emptyList(), listOf("first", "")))
    }

    @Test
    fun wordsAndCharacters() {
        val t = "  ls -la  /tmp"
        assertEquals(2 until 4, ScreenText.next(t, 0, ScreenText.WORD))
        assertEquals(5 until 8, ScreenText.next(t, 4, ScreenText.WORD))
        assertEquals(10 until 14, ScreenText.next(t, 8, ScreenText.WORD))
        assertNull(ScreenText.next(t, 14, ScreenText.WORD))
        assertEquals(10 until 14, ScreenText.previous(t, 14, ScreenText.WORD))
        assertEquals(5 until 8, ScreenText.previous(t, 10, ScreenText.WORD))
        assertNull(ScreenText.previous(t, 2, ScreenText.WORD))

        val e = "a😀b"
        assertEquals(1 until 3, ScreenText.next(e, 1, ScreenText.CHARACTER))
        assertEquals(1 until 3, ScreenText.previous(e, 3, ScreenText.CHARACTER))
        assertNull(ScreenText.next(e, 4, ScreenText.CHARACTER))
        assertNull(ScreenText.previous(e, 0, ScreenText.CHARACTER))
    }
}
