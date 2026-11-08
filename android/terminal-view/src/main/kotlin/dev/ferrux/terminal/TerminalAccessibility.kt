// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.terminal

import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityNodeProvider

/**
 * Screen-reader support for [TerminalView] (TalkBack, Switch Access, uiautomator).
 *
 * The terminal is exposed as one virtual text node per visible row with text, so
 * explore-by-touch and swipe navigation read it line by line, and each row can be
 * read by character or word. New output is announced politely through a live-region
 * node at the bottom ("output"), debounced and limited to the latest lines, so a
 * screen reader is not flooded while a program prints.
 *
 * Built on the platform AccessibilityNodeProvider (terminal-view has no AndroidX
 * dependency, so no ExploreByTouchHelper).
 */
internal class TerminalAccessibility(private val view: TerminalView) {

    private val manager: AccessibilityManager =
        view.context.getSystemService(AccessibilityManager::class.java)

    /** Visible rows as exposed to accessibility services (refreshed on every query). */
    private var rows: List<String> = emptyList()

    /**
     * The screen as of the last announcement: new output is diffed against this,
     * not against [rows], which queries refresh without announcing anything.
     * Null until the first look at a session, so its existing screen isn't read out.
     */
    private var spoken: List<String>? = null

    /** The latest announced output (the live-region node's text). */
    private var output = ""

    private var focusedId = AccessibilityNodeProvider.HOST_VIEW_ID
    private var hoveredId = INVALID_ID

    /** Reading position (UTF-16 index) per row, for character/word navigation. */
    private val cursors = HashMap<Int, Int>()

    private var buffer = IntArray(SnapshotLayout.requiredSize(24, 80))

    private val announce = Runnable { announceNewOutput() }

    val provider: AccessibilityNodeProvider = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            if (virtualViewId == AccessibilityNodeProvider.HOST_VIEW_ID) {
                refresh()
                return hostNode()
            }
            return virtualNode(virtualViewId)
        }

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (virtualViewId == AccessibilityNodeProvider.HOST_VIEW_ID) return view.performAccessibilityAction(action, arguments)
            return performVirtualAction(virtualViewId, action, arguments)
        }
    }

    private val enabled: Boolean get() = manager.isEnabled

    /** The screen changed; announce new output once it has been quiet for a moment. Any thread. */
    fun onScreenChanged() {
        if (!enabled) return
        view.removeCallbacks(announce)
        view.postDelayed(announce, ANNOUNCE_DELAY_MS)
    }

    /** A different terminal is shown: start over without announcing its screen. */
    fun reset() {
        view.removeCallbacks(announce)
        rows = emptyList()
        spoken = null
        output = ""
        cursors.clear()
        focusedId = AccessibilityNodeProvider.HOST_VIEW_ID
        hoveredId = INVALID_ID
    }

    /** Explore by touch: reports the row under the finger. Returns whether it was handled. */
    fun onHover(event: MotionEvent): Boolean {
        if (!manager.isTouchExplorationEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val row = view.rowAt(event.y)
                val id = if (row in rows.indices && rows[row].isNotEmpty()) row else INVALID_ID
                setHovered(id)
                return id != INVALID_ID
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                setHovered(INVALID_ID)
                return true
            }
        }
        return false
    }

    private fun setHovered(id: Int) {
        if (id == hoveredId) return
        val previous = hoveredId
        hoveredId = id
        if (id != INVALID_ID) send(id, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
        if (previous != INVALID_ID) send(previous, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
    }

    /** Re-reads the screen into [rows]; tells services if it changed. */
    private fun refresh() {
        val backend = view.backend ?: return
        var n = backend.snapshot(buffer)
        if (n < 0) {
            buffer = IntArray(-n)
            n = backend.snapshot(buffer)
        }
        val now = ScreenText.rows(buffer, n)
        if (now == rows) return
        rows = now
        cursors.clear()
        if (enabled) sendSubtreeChanged(AccessibilityNodeProvider.HOST_VIEW_ID)
    }

    /** Publishes the output that is new since the last announcement as the live output. */
    private fun announceNewOutput() {
        refresh()
        val before = spoken
        spoken = rows
        if (before == null) return
        val fresh = ScreenText.newOutput(before, rows)
        if (fresh.isEmpty()) return
        output = fresh.joinToString("\n").take(MAX_OUTPUT_CHARS)
        if (enabled) sendTextChanged(OUTPUT_ID)
    }

    // ---------------------------------------------------------------- nodes

    private fun hostNode(): AccessibilityNodeInfo {
        val info = AccessibilityNodeInfo(view)
        view.onInitializeAccessibilityNodeInfo(info)
        rows.forEachIndexed { row, text -> if (text.isNotEmpty()) info.addChild(view, row) }
        if (output.isNotEmpty()) info.addChild(view, OUTPUT_ID)
        return info
    }

    private fun virtualNode(id: Int): AccessibilityNodeInfo? {
        val text = textOf(id) ?: return null
        val info = AccessibilityNodeInfo(view, id)
        info.setParent(view)
        info.packageName = view.context.packageName
        info.className = "android.widget.TextView"
        info.text = text
        info.isEnabled = true
        info.isVisibleToUser = true
        info.setBoundsInScreen(boundsOf(id))
        if (id == OUTPUT_ID) {
            // Spoken when it changes; not a stop when swiping through the rows.
            info.liveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            info.viewIdResourceName = OUTPUT_RESOURCE_ID
            info.isImportantForAccessibility = false
            return info
        }
        info.isAccessibilityFocused = focusedId == id
        info.addAction(
            if (focusedId == id) AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            else AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS,
        )
        info.movementGranularities = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER or
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD
        info.addAction(AccessibilityAction.ACTION_NEXT_AT_MOVEMENT_GRANULARITY)
        info.addAction(AccessibilityAction.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY)
        return info
    }

    private fun textOf(id: Int): String? = when (id) {
        OUTPUT_ID -> output.takeIf { it.isNotEmpty() }
        in rows.indices -> rows[id].takeIf { it.isNotEmpty() }
        else -> null
    }

    private fun boundsOf(id: Int): Rect {
        val row = if (id == OUTPUT_ID) (rows.size - 1).coerceAtLeast(0) else id
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val top = view.rowTop(row).toInt()
        val bottom = view.rowTop(row + 1).toInt().coerceAtMost(view.height)
        return Rect(location[0], location[1] + top, location[0] + view.width, location[1] + bottom)
    }

    // ---------------------------------------------------------------- actions

    private fun performVirtualAction(id: Int, action: Int, arguments: Bundle?): Boolean {
        val text = textOf(id) ?: return false
        return when (action) {
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                if (focusedId == id) return false
                val previous = focusedId
                focusedId = id
                if (previous != AccessibilityNodeProvider.HOST_VIEW_ID) {
                    send(previous, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                }
                send(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                true
            }
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                if (focusedId != id) return false
                focusedId = AccessibilityNodeProvider.HOST_VIEW_ID
                send(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                true
            }
            AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY,
            AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY -> {
                val granularity = arguments?.getInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                ) ?: return false
                val kind = when (granularity) {
                    AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER -> ScreenText.CHARACTER
                    AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD -> ScreenText.WORD
                    else -> return false
                }
                val forward = action == AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
                val cursor = cursors[id] ?: if (forward) 0 else text.length
                val segment = (if (forward) ScreenText.next(text, cursor, kind) else ScreenText.previous(text, cursor, kind))
                    ?: return false // at the end: the screen reader moves to the next row
                cursors[id] = if (forward) segment.last + 1 else segment.first
                sendTraversed(id, text, action, granularity, segment)
                true
            }
            else -> false
        }
    }

    // ---------------------------------------------------------------- events

    private fun event(id: Int, type: Int): AccessibilityEvent = AccessibilityEvent(type).apply {
        setSource(view, id)
        packageName = view.context.packageName
        className = if (id == AccessibilityNodeProvider.HOST_VIEW_ID) TerminalView::class.java.name else "android.widget.TextView"
        textOf(id)?.let { text.add(it) }
    }

    private fun dispatch(event: AccessibilityEvent) {
        if (!enabled) return
        view.parent?.requestSendAccessibilityEvent(view, event)
    }

    private fun send(id: Int, type: Int) = dispatch(event(id, type))

    private fun sendSubtreeChanged(id: Int) = dispatch(
        event(id, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
        },
    )

    private fun sendTextChanged(id: Int) = dispatch(
        event(id, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
        },
    )

    private fun sendTraversed(id: Int, text: String, action: Int, granularity: Int, segment: IntRange) = dispatch(
        event(id, AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY).apply {
            this.action = action
            movementGranularity = granularity
            fromIndex = segment.first
            toIndex = segment.last + 1
            this.text.clear()
            this.text.add(text)
        },
    )

    companion object {
        private const val INVALID_ID = Int.MIN_VALUE

        /** Virtual id of the live-region output node (rows use their index). */
        const val OUTPUT_ID = 100_000

        /** Resource id of the output node, for UI tests. */
        const val OUTPUT_RESOURCE_ID = "terminal-output"

        private const val ANNOUNCE_DELAY_MS = 300L
        private const val MAX_OUTPUT_CHARS = 1_000
    }
}
