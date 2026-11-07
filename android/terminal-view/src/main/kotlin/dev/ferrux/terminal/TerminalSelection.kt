// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.terminal

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.PopupWindow
import android.widget.Toast
import dev.ferrux.terminal.SnapshotLayout as L
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Text selection UI for [TerminalView]: two drag handles and a floating action
 * mode with Copy, Open link, Paste, Select all and Share.
 *
 * The handles are small popup windows, like TextView's, so they can hang below
 * the last row and over the extra keys. The selection itself lives in the
 * engine: this class reads it back from every frame ([onFrame]) and edits it
 * through the [TerminalBackend], so it stays on its text while the view scrolls
 * and disappears when the program overwrites it.
 */
internal class TerminalSelection(private val view: TerminalView) {
    /** Selection mode is on: the handles and the action mode are shown. */
    var isActive = false
        private set

    /** A long press started the selection and the finger is still down. */
    var isDraggingFromLongPress = false
        private set

    private var actionMode: ActionMode? = null
    private var actionModeQueued = false
    private var menuTitles: List<String> = emptyList()

    private val startHandleLazy = lazy { Handle(isStart = true) }
    private val endHandleLazy = lazy { Handle(isStart = false) }
    private val startHandle by startHandleLazy
    private val endHandle by endHandleLazy
    private var dragged: Handle? = null

    /** The other end while a handle is dragged: a grid line (viewport row − offset) and column. */
    private var anchorLine = 0
    private var anchorCol = 0
    private var lastAutoScroll = 0L

    /** Selection record of the last frame: kind, startRow, startCol, endRow, endCol. */
    private val current = IntArray(L.SELECTION_SIZE)

    /** Bumped by [start], so a finish posted for an older selection is ignored. */
    private var generation = 0
    private val windowLocation = IntArray(2)
    private val screenLocation = IntArray(2)

    // ------------------------------------------------------------ lifecycle

    /** Enters selection mode after the backend selected something. */
    fun start(fromLongPress: Boolean) {
        isActive = true
        isDraggingFromLongPress = fromLongPress
        generation++
        view.invalidate()
    }

    /** The finger that long-pressed moved: extend by words. */
    fun extendLongPress(row: Int, col: Int) {
        view.backend?.updateSelection(row, col)
        view.invalidate()
    }

    /** The finger that long-pressed lifted: show the action mode. */
    fun endLongPress() {
        isDraggingFromLongPress = false
        view.invalidate()
    }

    /** Leaves selection mode; [clear] also removes the selection from the engine. */
    fun finish(clear: Boolean = true) {
        val wasActive = isActive
        isActive = false
        isDraggingFromLongPress = false
        dragged = null
        current.fill(0)
        hideHandles()
        val mode = actionMode
        actionMode = null
        mode?.finish()
        menuTitles = emptyList()
        if (clear && wasActive) view.backend?.clearSelection()
        view.invalidate()
    }

    /** Called from onDraw with the snapshot and the offset of its selection record. */
    fun onFrame(s: IntArray, offset: Int) {
        if (!isActive) return
        if (offset < 0 || s[offset] == L.SELECTION_NONE) {
            // The program overwrote the selected text: leave selection mode.
            val gen = generation
            view.post { if (isActive && generation == gen) finish(clear = false) }
            return
        }
        var changed = false
        for (i in 0 until L.SELECTION_SIZE) {
            if (current[i] != s[offset + i]) {
                current[i] = s[offset + i]
                changed = true
            }
        }
        // Window changes are not allowed during a draw pass: apply them right after.
        view.post(::positionHandles)
        if (isDraggingFromLongPress || dragged != null) return
        val mode = actionMode
        if (mode == null) {
            if (!actionModeQueued) {
                actionModeQueued = true
                view.post(::startActionMode)
            }
        } else if (changed) {
            mode.invalidateContentRect()
        }
    }

    /** Scrolling moves the selection: keep the toolbar out of the way meanwhile. */
    fun onScrolled() {
        actionMode?.hide(ActionMode.DEFAULT_HIDE_DURATION.toLong())
    }

    fun onDetached() = finish(clear = false)

    // ------------------------------------------------------------ action mode

    private fun startActionMode() {
        actionModeQueued = false
        if (!isActive || actionMode != null || isDraggingFromLongPress || dragged != null) return
        actionMode = view.startActionMode(callback, ActionMode.TYPE_FLOATING)
    }

    private val callback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = true

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.clear()
            val hasText = view.backend?.selectionText() != null
            val titles = ArrayList<String>()
            fun add(id: Int, title: CharSequence) {
                menu.add(Menu.NONE, id, titles.size, title)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                titles.add(title.toString())
            }
            val res = view.resources
            if (hasText) add(ID_COPY, res.getString(android.R.string.copy))
            if (linkForSelection() != null) add(ID_OPEN, res.getString(R.string.ferrux_terminal_open_link))
            if (clipboard?.hasPrimaryClip() == true) add(ID_PASTE, res.getString(android.R.string.paste))
            add(ID_SELECT_ALL, res.getString(android.R.string.selectAll))
            if (hasText) add(ID_SHARE, res.getString(R.string.ferrux_terminal_share))
            menuTitles = titles
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                ID_COPY -> {
                    copy()
                    finish()
                }
                ID_OPEN -> {
                    val url = linkForSelection()
                    finish()
                    if (url != null) open(url)
                }
                ID_PASTE -> {
                    finish()
                    view.pasteFromClipboard()
                }
                ID_SELECT_ALL -> {
                    view.backend?.selectAll()
                    view.invalidate()
                    mode.invalidate()
                }
                ID_SHARE -> {
                    val text = view.backend?.selectionText()
                    finish()
                    if (text != null) share(text)
                }
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            // Destroyed by the system rather than by finish(): leave selection mode too.
            if (actionMode === mode) {
                actionMode = null
                finish()
            }
        }

        override fun onGetContentRect(mode: ActionMode, v: View, outRect: Rect) {
            selectionRect(outRect)
        }
    }

    /** The on-screen part of the selection, in view coordinates. */
    private fun selectionRect(out: Rect) {
        val rows = view.frameRows
        val padLeft = view.paddingLeft
        val padTop = view.paddingTop
        val r0 = current[1].coerceIn(0, maxOf(0, rows - 1))
        val r1 = current[3].coerceIn(0, maxOf(0, rows - 1))
        val top = padTop + (r0 * view.cellHeightPx).toInt()
        val bottom = padTop + ((r1 + 1) * view.cellHeightPx).toInt()
        if (current[1] == current[3] && current[1] in 0 until rows) {
            out.set(
                padLeft + (current[2] * view.cellWidthPx).toInt(),
                top,
                padLeft + ((current[4] + 1) * view.cellWidthPx).toInt(),
                bottom,
            )
        } else {
            out.set(0, top, view.width, bottom)
        }
    }

    private val clipboard: ClipboardManager?
        get() = view.context.getSystemService(ClipboardManager::class.java)

    private fun copy() {
        val text = view.backend?.selectionText() ?: return
        clipboard?.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
        // Android 13+ confirms copies with its own clipboard overlay.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(view.context, R.string.ferrux_terminal_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            view.context.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(view.context, R.string.ferrux_terminal_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun open(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            view.context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(view.context, R.string.ferrux_terminal_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    /** An OSC 8 link where the selection starts, else the selected text if it is one URL. */
    fun linkForSelection(): String? {
        val row = current[1]
        if (current[0] != L.SELECTION_NONE && row in 0 until view.frameRows) {
            view.linkAt(row, current[2])?.takeIf(TerminalLinks::isOpenable)?.let { return it }
        }
        return view.backend?.selectionText()?.let(TerminalLinks::urlIn)
    }

    // ------------------------------------------------------------ handles

    private fun hideHandles() {
        if (startHandleLazy.isInitialized()) startHandle.hide()
        if (endHandleLazy.isInitialized()) endHandle.hide()
    }

    private fun shownHandles(): List<Handle> =
        listOf(startHandleLazy, endHandleLazy).filter { it.isInitialized() && it.value.isShowing }.map { it.value }

    private fun positionHandles() {
        if (!isActive || !view.isAttachedToWindow || current[0] == L.SELECTION_NONE) {
            hideHandles()
            return
        }
        view.getLocationInWindow(windowLocation)
        view.getLocationOnScreen(screenLocation)
        startHandle.place(current[1], current[2])
        endHandle.place(current[3], current[4])
    }

    private fun onHandleDown(handle: Handle) {
        dragged = handle
        // The other end stays put; remember it as a grid line so autoscroll can't move it.
        val (row, col) = if (handle.isStart) current[3] to current[4] else current[1] to current[2]
        anchorLine = row - view.frameDisplayOffset
        anchorCol = col
        actionMode?.hide(ActionMode.DEFAULT_HIDE_DURATION.toLong())
    }

    /** [x], [y]: where the handle's hotspot should go, in view coordinates. */
    private fun onHandleMoved(x: Float, y: Float) {
        val b = view.backend ?: return
        actionMode?.hide(ActionMode.DEFAULT_HIDE_DURATION.toLong())
        val rows = view.frameRows
        val cols = view.frameCols
        if (rows <= 0 || cols <= 0) return
        val ch = view.cellHeightPx
        val cw = view.cellWidthPx
        // The hotspot sits on the bottom edge of its row.
        val row = floor((y - view.paddingTop - ch / 2) / ch).toInt().coerceIn(0, rows - 1)
        val boundary = ((x - view.paddingLeft) / cw).roundToInt().coerceIn(0, cols)
        val anchorRow = anchorLine + view.frameDisplayOffset
        val beforeAnchor = row < anchorRow || (row == anchorRow && boundary <= anchorCol)
        // A start handle marks the left edge of its cell, an end handle the right edge.
        val col = if (beforeAnchor) boundary.coerceAtMost(cols - 1) else (boundary - 1).coerceAtLeast(0)
        b.setSelection(row, col, anchorRow, anchorCol)
        // Dragging past the top or bottom edge scrolls, at most every AUTOSCROLL_MS.
        val now = SystemClock.uptimeMillis()
        if (now - lastAutoScroll >= AUTOSCROLL_MS) {
            if (y < view.paddingTop) {
                b.scroll(1)
                lastAutoScroll = now
            } else if (y > view.height - view.paddingBottom + ch) {
                b.scroll(-1)
                lastAutoScroll = now
            }
        }
        view.invalidate()
    }

    private fun onHandleUp() {
        dragged = null
        actionMode?.hide(0)
        view.invalidate()
    }

    /** Diagnostics for tests: `key=value` pairs describing the selection UI. */
    fun debugState(): Map<String, String> = linkedMapOf(
        "selecting" to if (isActive) "1" else "0",
        "dragging" to if (isDraggingFromLongPress || dragged != null) "1" else "0",
        "actionMode" to if (actionMode != null) menuTitles.joinToString("|") else "",
        "handles" to shownHandles().size.toString(),
        "startHandle" to shownHandles().firstOrNull { it.isStart }?.screenBounds().orEmpty(),
        "endHandle" to shownHandles().firstOrNull { !it.isStart }?.screenBounds().orEmpty(),
        "link" to (if (isActive) linkForSelection() else null).orEmpty(),
    )

    /** One selection handle: the theme's text-select drawable in a tiny popup window. */
    private inner class Handle(val isStart: Boolean) : View(view.context) {
        private val drawable: Drawable? = view.context.obtainStyledAttributes(
            intArrayOf(if (isStart) android.R.attr.textSelectHandleLeft else android.R.attr.textSelectHandleRight),
        ).let { a ->
            try {
                a.getDrawable(0)
            } finally {
                a.recycle()
            }
        }
        private val density = view.resources.displayMetrics.density
        private val w = drawable?.intrinsicWidth?.takeIf { it > 0 } ?: (FALLBACK_SIZE_DP * density).toInt()
        private val h = drawable?.intrinsicHeight?.takeIf { it > 0 } ?: (FALLBACK_SIZE_DP * density).toInt()

        /** The pixel of the drawable that touches the text, as in TextView. */
        private val hotspotX = if (isStart) w * 3 / 4 else w / 4
        private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FALLBACK_COLOR }

        private val popup = PopupWindow(this, w, h, false).apply {
            isClippingEnabled = false
            isSplitTouchEnabled = true
            windowLayoutType = WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL
        }

        /** Hotspot in view coordinates, and the popup's window position. */
        private var localX = 0f
        private var localY = 0f
        private var windowX = Int.MIN_VALUE
        private var windowY = Int.MIN_VALUE
        private var touchDx = 0f
        private var touchDy = 0f

        init {
            contentDescription = if (isStart) "Selection start handle" else "Selection end handle"
        }

        val isShowing: Boolean get() = popup.isShowing

        fun place(row: Int, col: Int) {
            if (row !in 0 until view.frameRows || !view.isShown) {
                hide()
                return
            }
            localX = view.paddingLeft + (if (isStart) col else col + 1) * view.cellWidthPx
            localY = view.paddingTop + (row + 1) * view.cellHeightPx
            val x = windowLocation[0] + localX.roundToInt() - hotspotX
            val y = windowLocation[1] + localY.roundToInt()
            if (popup.isShowing) {
                if (x != windowX || y != windowY) popup.update(x, y, -1, -1)
            } else {
                popup.showAtLocation(view, Gravity.NO_GRAVITY, x, y)
            }
            windowX = x
            windowY = y
        }

        fun hide() {
            if (popup.isShowing) popup.dismiss()
        }

        /** `left,top,right,bottom` on screen. */
        fun screenBounds(): String {
            if (!popup.isShowing) return ""
            val left = screenLocation[0] + localX.roundToInt() - hotspotX
            val top = screenLocation[1] + localY.roundToInt()
            return "$left,$top,${left + w},${top + h}"
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(w, h)

        override fun onDraw(canvas: Canvas) {
            val d = drawable
            if (d != null) {
                d.setBounds(0, 0, w, h)
                d.draw(canvas)
            } else {
                // A teardrop-ish fallback: a circle with the corner at the hotspot squared off.
                val r = w / 2f
                canvas.drawCircle(r, h - r, r, fallbackPaint)
                if (isStart) canvas.drawRect(r, 0f, w.toFloat(), r, fallbackPaint)
                else canvas.drawRect(0f, 0f, r, r, fallbackPaint)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            view.getLocationOnScreen(screenLocation)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDx = e.rawX - (screenLocation[0] + localX)
                    touchDy = e.rawY - (screenLocation[1] + localY)
                    onHandleDown(this)
                }
                MotionEvent.ACTION_MOVE ->
                    onHandleMoved(e.rawX - touchDx - screenLocation[0], e.rawY - touchDy - screenLocation[1])
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onHandleUp()
            }
            return true
        }
    }

    private companion object {
        const val ID_COPY = 1
        const val ID_OPEN = 2
        const val ID_PASTE = 3
        const val ID_SELECT_ALL = 4
        const val ID_SHARE = 5
        const val CLIP_LABEL = "Ferrux"
        const val AUTOSCROLL_MS = 60L
        const val FALLBACK_SIZE_DP = 22
        const val FALLBACK_COLOR = 0xFFF29E4C.toInt()
    }
}
