// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.terminal

import kotlin.math.abs

/**
 * Counts consecutive taps: 1 for a single tap, 2 for a double tap, 3 for a
 * triple tap. A tap continues the series when it comes within [timeoutMs] of
 * the previous one and within [slopPx] of it; a fourth tap starts over.
 * Pure logic, so it is unit-tested without a device.
 */
class TapCounter(private val timeoutMs: Long, private val slopPx: Float) {
    private var count = 0
    private var lastTime = 0L
    private var lastX = 0f
    private var lastY = 0f

    /** Registers a tap (its up event) and returns its position in the series. */
    fun onTap(timeMs: Long, x: Float, y: Float): Int {
        val continues = count in 1 until MAX_TAPS &&
            timeMs - lastTime in 0..timeoutMs &&
            abs(x - lastX) <= slopPx && abs(y - lastY) <= slopPx
        count = if (continues) count + 1 else 1
        lastTime = timeMs
        lastX = x
        lastY = y
        return count
    }

    /** Forgets the series (e.g. after a long press or a scroll). */
    fun reset() {
        count = 0
    }

    private companion object {
        const val MAX_TAPS = 3
    }
}

/** Recognises links in selected text. */
object TerminalLinks {
    /** Schemes Ferrux opens; everything else (intent:, file:, javascript:, …) is refused. */
    private val openableSchemes = setOf("http", "https", "mailto", "ftp", "sftp", "ssh", "tel", "geo")

    private val urlPattern = Regex("""^(?:(?:https?|ftp|sftp)://|mailto:|www\.)[^\s<>"'`]+$""", RegexOption.IGNORE_CASE)

    /** Trailing punctuation that usually belongs to the sentence, not the URL. */
    private const val TRAILING = ".,;:!?'\")]}>"

    /**
     * The URL [text] consists of (surrounding whitespace and sentence punctuation
     * ignored), normalised to have a scheme, or null if it isn't one URL.
     */
    fun urlIn(text: String): String? {
        var url = text.trim()
        if (url.isEmpty() || url.length > MAX_URL || url.any { it.isWhitespace() }) return null
        while (url.isNotEmpty() && url.last() in TRAILING && !balancedClose(url)) url = url.dropLast(1)
        if (!urlPattern.matches(url)) return null
        if (url.startsWith("www.", ignoreCase = true)) url = "https://$url"
        return url
    }

    /** Whether [uri] (e.g. from an OSC 8 hyperlink) uses a scheme that is safe to open. */
    fun isOpenable(uri: String): Boolean {
        val colon = uri.indexOf(':')
        if (colon <= 0) return false
        return uri.substring(0, colon).lowercase() in openableSchemes
    }

    /** Keeps a closing bracket that matches one inside the URL (Wikipedia-style links). */
    private fun balancedClose(url: String): Boolean {
        val open = when (url.last()) {
            ')' -> '('
            ']' -> '['
            '}' -> '{'
            else -> return false
        }
        return url.count { it == open } >= url.count { it == url.last() }
    }

    private const val MAX_URL = 4096
}
