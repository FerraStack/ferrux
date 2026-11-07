// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapCounterTest {
    private val counter = TapCounter(timeoutMs = 300, slopPx = 20f)

    @Test
    fun countsSingleDoubleAndTripleTaps() {
        assertEquals(1, counter.onTap(1_000, 10f, 10f))
        assertEquals(2, counter.onTap(1_200, 15f, 12f))
        assertEquals(3, counter.onTap(1_450, 12f, 8f))
        // A fourth tap starts a new series.
        assertEquals(1, counter.onTap(1_600, 12f, 8f))
    }

    @Test
    fun slowOrDistantTapsStartOver() {
        assertEquals(1, counter.onTap(1_000, 10f, 10f))
        assertEquals(1, counter.onTap(1_301, 10f, 10f), "too slow")
        assertEquals(2, counter.onTap(1_500, 10f, 10f))
        assertEquals(1, counter.onTap(1_600, 100f, 10f), "too far")
    }

    @Test
    fun resetForgetsTheSeries() {
        counter.onTap(1_000, 0f, 0f)
        counter.reset()
        assertEquals(1, counter.onTap(1_100, 0f, 0f))
    }

    private fun assertEquals(expected: Int, actual: Int, message: String) = assertEquals(message, expected, actual)
}

class LineAccumulatorTest {
    @Test
    fun carriesTheRemainderAcrossSteps() {
        val acc = LineAccumulator()
        assertEquals(0, acc.add(10f, 30f))
        assertEquals(0, acc.add(10f, 30f))
        assertEquals(1, acc.add(15f, 30f)) // 35 px
        assertEquals(2, acc.add(55f, 30f)) // 5 + 55 = 60 px
        assertEquals(-1, acc.add(-30f, 30f))
        acc.reset()
        assertEquals(0, acc.add(29f, 30f))
        assertEquals(0, acc.add(1f, 0f), "no line height yet")
    }

    private fun assertEquals(expected: Int, actual: Int, message: String) = assertEquals(message, expected, actual)
}

class ScrollbarTest {
    @Test
    fun thumbTracksTheOffset() {
        // 20 rows over 180 lines of history in a 1000 px track: thumb is 100 px.
        val bottom = Scrollbar.thumb(rows = 20, history = 180, offset = 0, trackHeight = 1000f, minThumb = 48f)!!
        assertEquals(900f, bottom.first, 0.01f)
        assertEquals(100f, bottom.second, 0.01f)
        val top = Scrollbar.thumb(20, 180, 180, 1000f, 48f)!!
        assertEquals(0f, top.first, 0.01f)
        val middle = Scrollbar.thumb(20, 180, 90, 1000f, 48f)!!
        assertEquals(450f, middle.first, 0.01f)
    }

    @Test
    fun longHistoryKeepsAMinimumThumb() {
        val thumb = Scrollbar.thumb(20, 10_000, 0, 1000f, 48f)!!
        assertEquals(48f, thumb.second, 0.01f)
        assertEquals(952f, thumb.first, 0.01f)
    }

    @Test
    fun noHistoryNoThumb() {
        assertNull(Scrollbar.thumb(20, 0, 0, 1000f, 48f))
        assertNull(Scrollbar.thumb(0, 10, 0, 1000f, 48f))
    }
}

class TerminalLinksTest {
    @Test
    fun recognisesUrlsAndStripsSentencePunctuation() {
        assertEquals("https://ferrux.dev/docs?a=1", TerminalLinks.urlIn("  https://ferrux.dev/docs?a=1 "))
        assertEquals("https://example.com", TerminalLinks.urlIn("https://example.com."))
        assertEquals("https://example.com/x", TerminalLinks.urlIn("https://example.com/x),"))
        assertEquals("https://www.example.org", TerminalLinks.urlIn("www.example.org"))
        assertEquals("mailto:dev@ferrux.dev", TerminalLinks.urlIn("mailto:dev@ferrux.dev"))
        // Balanced brackets belong to the URL.
        assertEquals(
            "https://en.wikipedia.org/wiki/Rust_(programming_language)",
            TerminalLinks.urlIn("https://en.wikipedia.org/wiki/Rust_(programming_language)"),
        )
    }

    @Test
    fun rejectsTextThatIsNotOneUrl() {
        assertNull(TerminalLinks.urlIn("hello"))
        assertNull(TerminalLinks.urlIn("see https://a.example and https://b.example"))
        assertNull(TerminalLinks.urlIn("ferrux.dev"))
        assertNull(TerminalLinks.urlIn("javascript:alert(1)"))
        assertNull(TerminalLinks.urlIn(""))
    }

    @Test
    fun onlySafeSchemesAreOpenable() {
        assertTrue(TerminalLinks.isOpenable("https://ferrux.dev"))
        assertTrue(TerminalLinks.isOpenable("HTTP://ferrux.dev"))
        assertTrue(TerminalLinks.isOpenable("mailto:a@b.c"))
        assertFalse(TerminalLinks.isOpenable("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(TerminalLinks.isOpenable("file:///data/data/dev.ferrux/files/x"))
        assertFalse(TerminalLinks.isOpenable("javascript:alert(1)"))
        assertFalse(TerminalLinks.isOpenable("no-scheme"))
    }
}

class SnapshotLayoutTest {
    /** A 1×2 snapshot followed by the given tail ints. */
    private fun snapshot(vararg tail: Int): IntArray {
        val head = IntArray(SnapshotLayout.HEADER_SIZE + 2 * SnapshotLayout.INTS_PER_CELL)
        head[SnapshotLayout.H_ROWS] = 1
        head[SnapshotLayout.H_COLS] = 2
        return head + tail
    }

    @Test
    fun findsTheSelectionAfterTheCombiningTable() {
        val s = snapshot(
            1, 0, 2, 0x301, 0x302, // combining: cell 0 has two marks
            SnapshotLayout.SELECTION_TEXT, -3, 1, 0, 1, // selection from history
            0, // no links
        )
        val off = SnapshotLayout.selectionOffset(s, 1, 2, s.size)
        assertEquals(SnapshotLayout.HEADER_SIZE + 8 + 5, off)
        assertEquals(SnapshotLayout.SELECTION_TEXT, s[off])
        assertEquals(-3, s[off + 1])
        assertEquals(SnapshotLayout.requiredSize(1, 2), SnapshotLayout.HEADER_SIZE + 8 + 7)
    }

    @Test
    fun truncatedSnapshotsAreRejected() {
        val s = snapshot(1, 0, 5, 1) // claims 5 marks but has 1
        assertEquals(-1, SnapshotLayout.selectionOffset(s, 1, 2, s.size))
        val links = mutableListOf(SnapshotLayout.Link(0, 1, SnapshotLayout.LINK_URL, "stale"))
        SnapshotLayout.readLinks(s, -1, s.size, links)
        assertTrue(links.isEmpty())
    }

    @Test
    fun decodesLinks() {
        val uri = "https://ferrux.dev/🦀"
        val cps = uri.codePoints().toArray()
        val s = snapshot(0, 0, 0, 0, 0, 0, 1, 0, 2, SnapshotLayout.LINK_HYPERLINK, cps.size, *cps)
        val off = SnapshotLayout.selectionOffset(s, 1, 2, s.size)
        val links = mutableListOf<SnapshotLayout.Link>()
        SnapshotLayout.readLinks(s, off + SnapshotLayout.SELECTION_SIZE, s.size, links)
        assertEquals(listOf(SnapshotLayout.Link(0, 2, SnapshotLayout.LINK_HYPERLINK, uri)), links)
        assertTrue(1 in links[0])
        assertFalse(2 in links[0])
    }
}
