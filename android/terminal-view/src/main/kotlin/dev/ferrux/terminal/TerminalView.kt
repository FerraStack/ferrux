// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.util.AttributeSet
import android.util.SparseArray
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import dev.ferrux.terminal.SnapshotLayout as L
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A terminal widget: draws a [TerminalBackend] snapshot on a Canvas and turns
 * touch, hardware-keyboard and IME input into terminal input.
 *
 * The view does not poll. Call [onScreenUpdated] (from any thread) whenever the
 * backend reports new output.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** How the soft keyboard is asked to behave. Some IMEs only work well in one mode. */
    enum class ImeMode(internal val inputType: Int) {
        /** Regular text keyboard: composition (CJK, swipe) works; autocorrect is not requested. */
        TEXT(InputType.TYPE_CLASS_TEXT),

        /** Character-at-a-time input; works around keyboards that buffer words. */
        CHAR_BASED(
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        ),

        /** No editor semantics: most keyboards then send raw key events. */
        RAW(InputType.TYPE_NULL),
    }

    /** Selection handles and the Copy/Paste action mode. */
    internal val selection = TerminalSelection(this)

    /** The terminal being shown. Setting it resizes the backend to this view's grid. */
    var backend: TerminalBackend? = null
        set(value) {
            if (field === value) return
            // A selection belongs to the session it was made in.
            selection.finish(clear = true)
            field = value
            composing = ""
            lastResize = 0 to 0
            updateGridSize()
            invalidate()
        }

    /** One-shot modifiers (e.g. from an extra-keys row) applied to the next key or text. */
    var stickyModifiers: StickyModifiers? = null

    // Character-based input by default (as in Termux): word-composing IMEs
    // re-compose committed text, which a terminal cannot take back.
    var imeMode: ImeMode = ImeMode.CHAR_BASED
        set(value) {
            if (field == value) return
            field = value
            inputMethodManager.restartInput(this)
        }

    /** Blink the cursor while the view is focused. Off by default (steady block, as in Termux). */
    var cursorBlink: Boolean = false
        set(value) {
            field = value
            restartBlink()
        }

    /**
     * Called after the user changed the font size (pinch zoom, Ctrl+Alt+= / Ctrl+Alt+-)
     * with the new size in pixels, e.g. to persist [textSizeSp].
     */
    var onTextSizeChanged: ((Float) -> Unit)? = null

    /** Font size in pixels. */
    var textSizePx: Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, DEFAULT_TEXT_SIZE_SP, resources.displayMetrics,
    )
        set(value) {
            val clamped = value.coerceIn(minTextSizePx, maxTextSizePx)
            if (clamped == field) return
            field = clamped
            updateFontMetrics()
            updateGridSize()
            invalidate()
        }

    /** Font size in sp, so it follows the system font scale. Persist this rather than pixels. */
    var textSizeSp: Float
        get() {
            val metrics = resources.displayMetrics
            // Android 14+ scales large sp sizes non-linearly; invert that exactly.
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                TypedValue.deriveDimension(TypedValue.COMPLEX_UNIT_SP, textSizePx, metrics)
            } else {
                textSizePx / (metrics.density * resources.configuration.fontScale)
            }
        }
        set(value) {
            textSizePx = sp(value)
        }

    /** Scales the font as a user action, so [onTextSizeChanged] fires. */
    private fun zoomBy(factor: Float) {
        val before = textSizePx
        textSizePx *= factor
        if (textSizePx != before) onTextSizeChanged?.invoke(textSizePx)
    }

    /** Terminal grid size derived from the view size and font metrics. */
    var rows: Int = 0
        private set
    var columns: Int = 0
        private set

    private val minTextSizePx = sp(MIN_TEXT_SIZE_SP)
    private val maxTextSizePx = sp(MAX_TEXT_SIZE_SP)

    // Bundled JetBrains Mono (OFL, see licenses/JetBrainsMono-OFL.txt). Glyphs it
    // lacks (CJK, emoji, …) fall back to the system fonts automatically.
    private val regularFace = loadFont(R.font.jetbrains_mono_regular, Typeface.NORMAL)
    private val boldFace = loadFont(R.font.jetbrains_mono_bold, Typeface.BOLD)
    private val italicFace = loadFont(R.font.jetbrains_mono_italic, Typeface.ITALIC)
    private val boldItalicFace = loadFont(R.font.jetbrains_mono_bold_italic, Typeface.BOLD_ITALIC)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = regularFace
        isSubpixelText = true
        // A terminal grid must never merge cells: no ligatures or contextual alternates.
        fontFeatureSettings = "'liga' 0, 'calt' 0"
    }
    private val fillPaint = Paint()
    private val linePaint = Paint().apply { style = Paint.Style.STROKE }

    private var cellWidth = 1f
    private var cellHeight = 1f
    private var baselineOffset = 0f

    private var snapshot = IntArray(L.requiredSize(24, 80))
    /** Cell index → base character plus its zero-width characters, for this frame. */
    private val combining = SparseArray<String>()
    /** OSC 8 hyperlinks of this frame. */
    private val links = ArrayList<L.Link>()

    // Geometry of the last drawn frame, for hit testing and the selection UI.
    internal var frameRows = 0
        private set
    internal var frameCols = 0
        private set
    internal var frameDisplayOffset = 0
        private set
    private var frameHistory = 0
    private var frameModes = 0

    // Scrolling: drag and fling distances become lines; the indicator fades out
    // after the last scroll unless the view stays scrolled back.
    private val scrollLines = LineAccumulator()
    private val flingLines = LineAccumulator()
    private val scroller = OverScroller(context)
    private var lastFlingY = 0
    private var flingRow = 0
    private var flingCol = 0
    private var lastScrollAt = 0L
    private var scrollbarAlpha = 0f
    private val scrollbarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val jumpPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val jumpStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val scrollRect = RectF()

    /** The "back to the live screen" button, shown while scrolled back. */
    private val jumpRect = RectF()
    internal val cellWidthPx: Float get() = cellWidth
    internal val cellHeightPx: Float get() = cellHeight

    private val tapCounter = TapCounter(
        ViewConfiguration.getDoubleTapTimeout().toLong(),
        ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat(),
    )

    /** Shows the keyboard after a single tap, unless it turns into a double tap. */
    private val showKeyboardRunnable = Runnable { showKeyboard() }
    private val charBuffer = CharArray(512)
    private var composing = ""
    private var lastResize = 0 to 0
    private var scaling = false
    private var cursorOn = true
    private val blinkRunnable = object : Runnable {
        override fun run() {
            cursorOn = !cursorOn
            invalidate()
            postDelayed(this, BLINK_INTERVAL_MS)
        }
    }

    private val inputMethodManager: InputMethodManager
        get() = context.getSystemService(InputMethodManager::class.java)

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onTap(e)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!scaling) onLongPressAt(e.x, e.y)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaling || selection.isDraggingFromLongPress) return true
            tapCounter.reset()
            // Finger moving down (dy < 0) reveals older output: scroll back.
            val lines = scrollLines.add(-dy, cellHeight)
            if (lines != 0) scrollBy(lines, rowAt(e2.y), colAt(e2.x))
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaling || selection.isDraggingFromLongPress) return true
            // Off the main screen every line is a key press or wheel event: keep flings short.
            val velocity = if (scrollsHistory()) vy else vy * PROGRAM_FLING_FACTOR
            lastFlingY = 0
            flingLines.reset()
            flingRow = rowAt(e2.y)
            flingCol = colAt(e2.x)
            scroller.fling(0, 0, 0, velocity.toInt(), 0, 0, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2)
            postOnAnimation(flingStep)
            return true
        }
    }).apply {
        // Taps are counted by [tapCounter] (double tap = word, triple tap = line);
        // the detector's own double-tap handling would swallow the second tap.
        setOnDoubleTapListener(null)
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            textSizePx *= detector.scaleFactor
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scaling = false
            onTextSizeChanged?.invoke(textSizePx)
        }
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = "Terminal"
        updateFontMetrics()
    }

    private fun loadFont(id: Int, fallbackStyle: Int): Typeface =
        runCatching { resources.getFont(id) }.getOrNull()
            ?: Typeface.create(Typeface.MONOSPACE, fallbackStyle)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Take focus as soon as we are on screen so hardware keys and IME
        // input reach the terminal rather than other widgets.
        post { requestFocus() }
        restartBlink()
    }

    /** One animation frame of a fling: scroll by the lines the scroller covered. */
    private val flingStep = object : Runnable {
        override fun run() {
            if (!scroller.computeScrollOffset()) return
            val y = scroller.currY
            val lines = flingLines.add((y - lastFlingY).toFloat(), cellHeight)
            lastFlingY = y
            if (lines != 0) {
                val atEnd = scrollsHistory() &&
                    ((lines > 0 && frameDisplayOffset >= frameHistory) || (lines < 0 && frameDisplayOffset == 0))
                if (atEnd) {
                    scroller.forceFinished(true)
                    return
                }
                scrollBy(lines, flingRow, flingCol)
            }
            postOnAnimation(this)
        }
    }

    /** Whether a scroll gesture moves through history (rather than going to the program). */
    private fun scrollsHistory(): Boolean = frameModes and (L.MODE_ALT_SCREEN or L.MODE_MOUSE) == 0

    /**
     * Scrolls by [lines] (positive = older output) at a cell: history on the main
     * screen, arrow keys or wheel events for full-screen programs (decided natively).
     */
    private fun scrollBy(lines: Int, row: Int, col: Int) {
        val b = backend ?: return
        b.scrollGesture(lines, row, col)
        if (scrollsHistory()) lastScrollAt = SystemClock.uptimeMillis()
        selection.onScrolled()
        invalidate()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // Mouse wheels and trackpads.
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val lines = (event.getAxisValue(MotionEvent.AXIS_VSCROLL) * WHEEL_LINES).roundToInt()
            if (lines != 0) scrollBy(lines, rowAt(event.y), colAt(event.x))
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onDetachedFromWindow() {
        linkMode?.finish()
        removeCallbacks(blinkRunnable)
        removeCallbacks(flingStep)
        scroller.forceFinished(true)
        removeCallbacks(showKeyboardRunnable)
        selection.onDetached()
        super.onDetachedFromWindow()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        restartBlink()
    }

    /** Shows the cursor solid and restarts the blink timer (after input or output). */
    private fun restartBlink() {
        removeCallbacks(blinkRunnable)
        cursorOn = true
        if (cursorBlink && isAttachedToWindow && hasFocus()) postDelayed(blinkRunnable, BLINK_INTERVAL_MS)
        invalidate()
    }

    /** Requests a redraw. Safe to call from any thread. */
    fun onScreenUpdated() {
        // Output keeps the cursor visible, like most terminals.
        post { restartBlink() }
    }

    fun showKeyboard() {
        requestFocus()
        inputMethodManager.showSoftInput(this, 0)
    }

    private fun isKeyboardShown(): Boolean =
        rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true

    // ---------------------------------------------------------------- layout

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun updateFontMetrics() {
        textPaint.textSize = textSizePx
        val fm = textPaint.fontMetrics
        cellWidth = max(1f, textPaint.measureText("M"))
        // Line height from the font's ascent+descent (JetBrains Mono's include room
        // for descenders and accents), rounded up to whole pixels so rows never
        // overlap and clip each other.
        cellHeight = max(1f, kotlin.math.ceil(fm.descent - fm.ascent))
        baselineOffset = kotlin.math.round(-fm.ascent)
        linePaint.strokeWidth = max(1f, textSizePx / 14f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGridSize()
    }

    private fun updateGridSize() {
        val usableW = width - paddingLeft - paddingRight
        val usableH = height - paddingTop - paddingBottom
        if (usableW <= 0 || usableH <= 0) return
        columns = max(1, (usableW / cellWidth).toInt())
        rows = max(1, (usableH / cellHeight).toInt())
        val size = rows to columns
        val b = backend ?: return
        if (size != lastResize) {
            lastResize = size
            b.resize(rows, columns)
        }
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val b = backend
        if (b == null) {
            canvas.drawColor(Color.BLACK)
            return
        }
        var n = b.snapshot(snapshot)
        if (n < 0) {
            snapshot = IntArray(-n)
            n = b.snapshot(snapshot)
        }
        if (n < L.HEADER_SIZE) {
            canvas.drawColor(Color.BLACK)
            return
        }
        val s = snapshot
        val rows = s[L.H_ROWS]
        val cols = s[L.H_COLS]
        val defaultBg = s[L.H_DEFAULT_BG]
        canvas.drawColor(defaultBg)
        if (n < L.requiredSize(rows, cols)) return
        frameRows = rows
        frameCols = cols
        frameDisplayOffset = s[L.H_DISPLAY_OFFSET]
        frameHistory = s[L.H_HISTORY]
        frameModes = s[L.H_MODES]
        readCombining(rows, cols, n)
        val selectionOffset = L.selectionOffset(s, rows, cols, n)
        L.readLinks(s, if (selectionOffset < 0) -1 else selectionOffset + L.SELECTION_SIZE, n, links)

        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        for (row in 0 until rows) {
            drawRowBackground(canvas, row, cols, defaultBg)
            drawRowText(canvas, row, cols)
        }
        drawCursor(canvas, rows, cols)
        canvas.restore()
        drawScrollIndicators(canvas, rows, s[L.H_DEFAULT_FG], defaultBg)
        selection.onFrame(s, selectionOffset)
    }

    /**
     * The scrollbar (while scrolling, and as long as the view is scrolled back) and
     * the round "back to live" button in the bottom-right corner while scrolled back.
     */
    private fun drawScrollIndicators(canvas: Canvas, rows: Int, fg: Int, bg: Int) {
        val offset = frameDisplayOffset
        val since = SystemClock.uptimeMillis() - lastScrollAt
        scrollbarAlpha = when {
            frameHistory == 0 -> 0f
            offset > 0 || since < SCROLLBAR_LINGER_MS -> 1f
            since < SCROLLBAR_LINGER_MS + SCROLLBAR_FADE_MS -> 1f - (since - SCROLLBAR_LINGER_MS).toFloat() / SCROLLBAR_FADE_MS
            else -> 0f
        }
        val density = resources.displayMetrics.density
        if (scrollbarAlpha > 0f) {
            val track = rows * cellHeight
            Scrollbar.thumb(rows, frameHistory, offset, track, SCROLLBAR_MIN_THUMB_DP * density)?.let { (top, height) ->
                val w = SCROLLBAR_WIDTH_DP * density
                val right = width - SCROLLBAR_MARGIN_DP * density
                scrollRect.set(right - w, paddingTop + top, right, paddingTop + top + height)
                scrollbarPaint.color = fg
                scrollbarPaint.alpha = (scrollbarAlpha * SCROLLBAR_MAX_ALPHA).toInt()
                canvas.drawRoundRect(scrollRect, w / 2, w / 2, scrollbarPaint)
            }
            // Keep fading out once scrolling stopped at the live screen.
            if (offset == 0) {
                val linger = SCROLLBAR_LINGER_MS - since
                if (linger > 0) postInvalidateDelayed(linger) else postInvalidateOnAnimation()
            }
        }
        if (offset > 0) {
            val r = JUMP_RADIUS_DP * density
            val cx = width - paddingRight - JUMP_MARGIN_DP * density - r
            val cy = height - paddingBottom - JUMP_MARGIN_DP * density - r
            jumpRect.set(cx - r, cy - r, cx + r, cy + r)
            jumpPaint.color = blend(bg, fg, 0.16f)
            canvas.drawCircle(cx, cy, r, jumpPaint)
            jumpStroke.color = fg
            jumpStroke.strokeWidth = 1.8f * density
            val a = r * 0.36f
            canvas.drawLine(cx, cy - a, cx, cy + a, jumpStroke)
            canvas.drawLine(cx - a * 0.8f, cy + a * 0.2f, cx, cy + a, jumpStroke)
            canvas.drawLine(cx + a * 0.8f, cy + a * 0.2f, cx, cy + a, jumpStroke)
        } else {
            jumpRect.setEmpty()
        }
    }

    /** [from] moved towards [to] by [t] (0..1), opaque. */
    private fun blend(from: Int, to: Int, t: Float): Int {
        fun mix(a: Int, b: Int) = (a + (b - a) * t).roundToInt()
        return Color.rgb(
            mix(Color.red(from), Color.red(to)),
            mix(Color.green(from), Color.green(to)),
            mix(Color.blue(from), Color.blue(to)),
        )
    }

    /** The link (OSC 8 or a URL in the text) at a viewport cell of the last frame. */
    internal fun linkSpanAt(row: Int, col: Int): L.Link? {
        if (row !in 0 until frameRows || col !in 0 until frameCols) return null
        val cell = row * frameCols + col
        return links.firstOrNull { cell in it }
    }

    /** The URI of the link at a viewport cell of the last frame, if any. */
    internal fun linkAt(row: Int, col: Int): String? = linkSpanAt(row, col)?.uri

    private fun readCombining(rows: Int, cols: Int, n: Int) {
        combining.clear()
        val s = snapshot
        var i = L.combiningOffset(rows, cols)
        val count = s[i++]
        repeat(count) {
            if (i + 2 > n) return
            val cell = s[i]
            val len = s[i + 1]
            i += 2
            if (i + len > n) return
            val base = s[L.HEADER_SIZE + cell * L.INTS_PER_CELL + L.C_CODEPOINT]
            val text = StringBuilder().appendCodePoint(if (base == 0) ' '.code else base)
            for (k in 0 until len) text.appendCodePoint(s[i + k])
            combining.put(cell, text.toString())
            i += len
        }
    }

    private fun cellIndex(row: Int, col: Int, cols: Int) =
        L.HEADER_SIZE + (row * cols + col) * L.INTS_PER_CELL

    /** Draws runs of identical non-default background colours as single rectangles. */
    private fun drawRowBackground(canvas: Canvas, row: Int, cols: Int, defaultBg: Int) {
        val s = snapshot
        val top = row * cellHeight
        var col = 0
        while (col < cols) {
            val bg = s[cellIndex(row, col, cols) + L.C_BG]
            var end = col + 1
            while (end < cols && s[cellIndex(row, end, cols) + L.C_BG] == bg) end++
            if (bg != defaultBg) {
                fillPaint.color = bg
                canvas.drawRect(col * cellWidth, top, end * cellWidth, top + cellHeight, fillPaint)
            }
            col = end
        }
    }

    /**
     * Draws glyphs in runs of the same style. A run is drawn with one drawText call
     * when its glyphs are exactly cell-sized (the common monospace case); otherwise
     * (fallback fonts, CJK, emoji) each cell is drawn at its own grid position.
     */
    private fun drawRowText(canvas: Canvas, row: Int, cols: Int) {
        val s = snapshot
        val baseline = row * cellHeight + baselineOffset
        var col = 0
        while (col < cols) {
            val idx = cellIndex(row, col, cols)
            val cp = s[idx + L.C_CODEPOINT]
            val flags = s[idx + L.C_FLAGS]
            if (cp == 0 || cp == ' '.code || flags and (L.F_WIDE_SPACER or L.F_HIDDEN) != 0) {
                // Nothing to draw, but decorations still apply to blank cells.
                if (flags and L.F_WIDE_SPACER == 0) drawDecorations(canvas, row, col, col + 1, flags, s[idx + L.C_FG])
                col++
                continue
            }
            val fg = s[idx + L.C_FG]
            // Collect a run of same-style, single-width, drawable cells.
            var len = 0
            var end = col
            while (end < cols && len < charBuffer.size - 2) {
                val i = cellIndex(row, end, cols)
                val c = s[i + L.C_CODEPOINT]
                val f = s[i + L.C_FLAGS]
                if (s[i + L.C_FG] != fg || f != flags || c == 0 || f and (L.F_WIDE or L.F_COMBINING) != 0) break
                len += Character.toChars(c, charBuffer, len)
                end++
            }
            if (end == col) {
                // A wide or combining character: draw one cell on its own.
                end = col + if (flags and L.F_WIDE != 0) 2 else 1
                applyStyle(fg, flags)
                val cluster = if (flags and L.F_COMBINING != 0) combining[row * cols + col] else null
                if (cluster != null) {
                    canvas.drawText(cluster, col * cellWidth, baseline, textPaint)
                } else {
                    len = Character.toChars(cp, charBuffer, 0)
                    canvas.drawText(charBuffer, 0, len, col * cellWidth, baseline, textPaint)
                }
            } else {
                applyStyle(fg, flags)
                val runWidth = (end - col) * cellWidth
                if (abs(textPaint.measureText(charBuffer, 0, len) - runWidth) < 0.5f) {
                    canvas.drawText(charBuffer, 0, len, col * cellWidth, baseline, textPaint)
                } else {
                    var x = col
                    var offset = 0
                    while (x < end) {
                        val cpLen = Character.charCount(Character.codePointAt(charBuffer, offset))
                        canvas.drawText(charBuffer, offset, cpLen, x * cellWidth, baseline, textPaint)
                        offset += cpLen
                        x++
                    }
                }
            }
            drawDecorations(canvas, row, col, end, flags, fg)
            col = end
        }
    }

    private fun applyStyle(fg: Int, flags: Int) {
        textPaint.color = if (flags and L.F_DIM != 0) dim(fg) else fg
        val bold = flags and L.F_BOLD != 0
        val italic = flags and L.F_ITALIC != 0
        textPaint.typeface = when {
            bold && italic -> boldItalicFace
            bold -> boldFace
            italic -> italicFace
            else -> regularFace
        }
    }

    private fun dim(color: Int): Int = Color.argb(
        Color.alpha(color),
        Color.red(color) * 2 / 3,
        Color.green(color) * 2 / 3,
        Color.blue(color) * 2 / 3,
    )

    private fun drawDecorations(canvas: Canvas, row: Int, start: Int, end: Int, flags: Int, fg: Int) {
        val decorations = L.F_UNDERLINE or L.F_DOUBLE_UNDERLINE or L.F_UNDERCURL or L.F_STRIKEOUT or
            L.F_HYPERLINK or L.F_URL
        if (flags and decorations == 0) return
        linePaint.color = if (flags and L.F_DIM != 0) dim(fg) else fg
        val x0 = start * cellWidth
        val x1 = end * cellWidth
        val top = row * cellHeight
        val underlineY = top + baselineOffset + linePaint.strokeWidth * 1.5f
        if (flags and (L.F_UNDERLINE or L.F_UNDERCURL or L.F_DOUBLE_UNDERLINE) == 0 &&
            flags and (L.F_HYPERLINK or L.F_URL) != 0
        ) {
            // Links get a quieter underline than SGR 4, so styled links stay distinct.
            linePaint.alpha = LINK_UNDERLINE_ALPHA
            canvas.drawLine(x0, underlineY, x1, underlineY, linePaint)
            linePaint.alpha = 0xFF
        }
        // TODO(M4): draw undercurl as a wave instead of a plain underline.
        if (flags and (L.F_UNDERLINE or L.F_UNDERCURL or L.F_DOUBLE_UNDERLINE) != 0) {
            canvas.drawLine(x0, underlineY, x1, underlineY, linePaint)
        }
        if (flags and L.F_DOUBLE_UNDERLINE != 0) {
            val y = underlineY + linePaint.strokeWidth * 2
            canvas.drawLine(x0, y, x1, y, linePaint)
        }
        if (flags and L.F_STRIKEOUT != 0) {
            val y = top + cellHeight / 2
            canvas.drawLine(x0, y, x1, y, linePaint)
        }
    }

    private fun drawCursor(canvas: Canvas, rows: Int, cols: Int) {
        val s = snapshot
        val row = s[L.H_CURSOR_ROW]
        val col = s[L.H_CURSOR_COL]
        if (row !in 0 until rows || col !in 0 until cols) return
        val left = col * cellWidth
        val top = row * cellHeight

        if (composing.isNotEmpty()) {
            drawComposing(canvas, left, top, s[cellIndex(row, col, cols) + L.C_FG])
            return
        }

        val shapeBits = s[L.H_CURSOR_SHAPE]
        val idx = cellIndex(row, col, cols)
        val fg = s[idx + L.C_FG]
        val bg = s[idx + L.C_BG]
        val wide = s[idx + L.C_FLAGS] and L.F_WIDE != 0
        val right = left + cellWidth * if (wide) 2 else 1
        // The cursor uses the palette's cursor colour on the default background,
        // and the cell's own foreground on coloured cells.
        val cursorColor = if (bg == s[L.H_DEFAULT_BG]) CURSOR_COLOR else fg
        fillPaint.color = cursorColor
        val shape = shapeBits and L.CURSOR_SHAPE_MASK
        if (shape == L.CURSOR_HIDDEN) return
        if (!hasFocus()) {
            // Unfocused: a steady hollow box, like desktop terminals.
            linePaint.color = cursorColor
            canvas.drawRect(left, top, right, top + cellHeight, linePaint)
            return
        }
        if (!cursorOn) return
        when (shape) {
            L.CURSOR_BLOCK -> if (shapeBits and L.CURSOR_HOLLOW != 0) {
                linePaint.color = cursorColor
                canvas.drawRect(left, top, right, top + cellHeight, linePaint)
            } else {
                canvas.drawRect(left, top, right, top + cellHeight, fillPaint)
                // Redraw the glyph under the block in the cell's background colour.
                val cp = s[idx + L.C_CODEPOINT]
                if (cp != 0 && cp != ' '.code) {
                    applyStyle(bg, s[idx + L.C_FLAGS] and L.F_DIM.inv())
                    val len = Character.toChars(cp, charBuffer, 0)
                    canvas.drawText(charBuffer, 0, len, left, top + baselineOffset, textPaint)
                }
            }
            L.CURSOR_UNDERLINE -> {
                val h = max(2f, cellHeight / 8)
                canvas.drawRect(left, top + cellHeight - h, right, top + cellHeight, fillPaint)
            }
            L.CURSOR_BAR -> {
                val w = max(2f, cellWidth / 6)
                canvas.drawRect(left, top, left + w, top + cellHeight, fillPaint)
            }
            else -> Unit // hidden
        }
    }

    /** Draws IME pre-edit text inline at the cursor, underlined. */
    private fun drawComposing(canvas: Canvas, left: Float, top: Float, fg: Int) {
        applyStyle(fg, 0)
        val textWidth = textPaint.measureText(composing)
        fillPaint.color = snapshot[L.H_DEFAULT_BG]
        canvas.drawRect(left, top, left + textWidth, top + cellHeight, fillPaint)
        canvas.drawText(composing, left, top + baselineOffset, textPaint)
        linePaint.color = fg
        val y = top + cellHeight - linePaint.strokeWidth
        canvas.drawLine(left, y, left + textWidth, y, linePaint)
    }

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Touching the screen stops a fling, like in any list.
                scroller.forceFinished(true)
                scrollLines.reset()
            }
            // Long press, then drag: extend the selection word by word, up to where the
            // finger lifts.
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> if (selection.isDraggingFromLongPress) {
                if (!scaling) selection.extendLongPress(rowAt(event.y), colAt(event.x))
                if (event.actionMasked == MotionEvent.ACTION_UP) selection.endLongPress()
            }
            MotionEvent.ACTION_CANCEL ->
                if (selection.isDraggingFromLongPress) selection.endLongPress()
        }
        return true
    }

    private fun rowAt(y: Float): Int =
        ((y - paddingTop) / cellHeight).toInt().coerceIn(0, max(0, frameRows - 1))

    private fun colAt(x: Float): Int =
        ((x - paddingLeft) / cellWidth).toInt().coerceIn(0, max(0, frameCols - 1))

    /** One, two or three taps: keyboard / select word / select line. */
    private fun onTap(e: MotionEvent) {
        removeCallbacks(showKeyboardRunnable)
        if (linkMode != null) {
            // A tap anywhere closes the link menu.
            linkMode?.finish()
            tapCounter.reset()
            return
        }
        if (!jumpRect.isEmpty && jumpRect.contains(e.x, e.y)) {
            tapCounter.reset()
            backend?.scroll(-frameDisplayOffset)
            invalidate()
            return
        }
        if (frameModes and L.MODE_MOUSE != 0 && !selection.isActive) {
            // The program asked for mouse events: a tap is a left click there.
            tapCounter.reset()
            val b = backend ?: return
            val row = rowAt(e.y)
            val col = colAt(e.x)
            b.mouseEvent(TerminalKeys.MOUSE_LEFT, TerminalKeys.MOUSE_PRESS, row, col, 0)
            b.mouseEvent(TerminalKeys.MOUSE_LEFT, TerminalKeys.MOUSE_RELEASE, row, col, 0)
            if (!isKeyboardShown()) showKeyboard()
            return
        }
        when (tapCounter.onTap(e.eventTime, e.x, e.y)) {
            1 -> when {
                // A tap outside the toolbar ends selection mode, like in text fields.
                selection.isActive -> selection.finish()
                // A link (OSC 8 or a URL in the text): offer to open or copy it.
                linkSpanAt(rowAt(e.y), colAt(e.x)) != null ->
                    showLinkMenu(linkSpanAt(rowAt(e.y), colAt(e.x))!!)
                isKeyboardShown() -> showKeyboard()
                // Wait out a possible double tap: showing the keyboard resizes the grid,
                // which would move the text under the second tap.
                else -> postDelayed(showKeyboardRunnable, ViewConfiguration.getDoubleTapTimeout().toLong())
            }
            2 -> selectAt(SelectionKind.WORD, e.x, e.y, fromLongPress = false)
            else -> selectAt(SelectionKind.LINE, e.x, e.y, fromLongPress = false)
        }
    }

    private fun onLongPressAt(x: Float, y: Float) {
        tapCounter.reset()
        removeCallbacks(showKeyboardRunnable)
        if (!selectAt(SelectionKind.WORD, x, y, fromLongPress = true)) {
            // Nothing but blanks under the finger: offer Paste on its own.
            showPasteMenu(x, y)
        }
    }

    /** Selects the word or line at a point; false if there is only blank space there. */
    private fun selectAt(kind: Int, x: Float, y: Float, fromLongPress: Boolean): Boolean {
        val b = backend ?: return false
        if (frameRows == 0) return false
        selection.finish(clear = false)
        if (!b.startSelection(kind, rowAt(y), colAt(x)) || b.selectionText() == null) {
            b.clearSelection()
            invalidate()
            return false
        }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        selection.start(fromLongPress)
        return true
    }

    private var linkMode: ActionMode? = null
    private var linkMenuTitles: List<String> = emptyList()
    private var linkMenuUri: String? = null

    /** The floating toolbar for a tapped link: Open link (safe schemes only) and Copy link. */
    private fun showLinkMenu(link: L.Link) {
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        val uri = link.uri
        val openable = TerminalLinks.isOpenable(uri)
        linkMenuUri = uri
        linkMode = startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                val titles = ArrayList<String>()
                if (openable) {
                    val title = resources.getString(R.string.ferrux_terminal_open_link)
                    menu.add(Menu.NONE, MENU_OPEN_LINK, 0, title).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    titles += title
                }
                val copy = resources.getString(R.string.ferrux_terminal_copy_link)
                menu.add(Menu.NONE, MENU_COPY_LINK, 1, copy).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                titles += copy
                linkMenuTitles = titles
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                mode.finish()
                when (item.itemId) {
                    MENU_OPEN_LINK -> openUri(this@TerminalView, uri)
                    MENU_COPY_LINK -> context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Ferrux", uri))
                }
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                if (linkMode === mode) {
                    linkMode = null
                    linkMenuUri = null
                    linkMenuTitles = emptyList()
                }
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                // The whole link, which may span rows.
                val cols = max(1, frameCols)
                val top = link.start / cols
                val bottom = (link.end - 1) / cols
                val single = top == bottom
                outRect.set(
                    paddingLeft + if (single) (link.start % cols * cellWidth).toInt() else 0,
                    paddingTop + (top * cellHeight).toInt(),
                    if (single) (paddingLeft + ((link.end - 1) % cols + 1) * cellWidth).toInt() else width - paddingRight,
                    paddingTop + ((bottom + 1) * cellHeight).toInt(),
                )
            }
        }, ActionMode.TYPE_FLOATING)
    }

    /** `key=value` diagnostics for tests: grid geometry and the selection UI state. */
    fun debugState(): Map<String, String> {
        val location = IntArray(2).also(::getLocationOnScreen)
        val selected = backend?.selectionText().orEmpty()
        return linkedMapOf(
            "origin" to "${location[0] + paddingLeft},${location[1] + paddingTop}",
            "cell" to "$cellWidth,$cellHeight",
            "rows" to frameRows.toString(),
            "cols" to frameCols.toString(),
            "offset" to frameDisplayOffset.toString(),
            "history" to frameHistory.toString(),
            "modes" to frameModes.toString(),
            "scrollbar" to if (scrollbarAlpha > 0f) "1" else "0",
            "jumpButton" to if (jumpRect.isEmpty) "" else jumpRect.run {
                "${location[0] + left.toInt()},${location[1] + top.toInt()},${location[0] + right.toInt()},${location[1] + bottom.toInt()}"
            },
            "selection" to selected.replace("\\", "\\\\").replace("\n", "\\n"),
            "links" to links.joinToString("|") { "${it.start}-${it.end}=${it.uri}" },
            "linkMenu" to if (linkMode != null) linkMenuTitles.joinToString("|") else "",
            "linkMenuUri" to linkMenuUri.orEmpty(),
        ) + selection.debugState()
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        // Back leaves selection mode before it closes the keyboard or the app.
        if (keyCode == KeyEvent.KEYCODE_BACK && selection.isActive) {
            if (event.action == KeyEvent.ACTION_UP) selection.finish()
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }

    // ---------------------------------------------------------------- IME

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = imeMode.inputType
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return TerminalInputConnection(this)
    }

    internal fun setComposingText(text: String) {
        if (text == composing) return
        composing = text
        invalidate()
    }

    /**
     * Sends text as if typed (IME commits, extra keys). Sticky modifiers apply;
     * a `\n` is sent as an Enter key press.
     */
    fun sendText(text: String) {
        val b = backend ?: return
        if (text.isEmpty()) return
        // Sticky modifiers apply to the first code point only: Ctrl + a swiped
        // word "cd" must send ^C followed by "d", not ^C^D.
        var mods = consumeSticky()
        val plain = StringBuilder()
        fun flushPlain() {
            if (plain.isNotEmpty()) {
                b.sendText(plain.toString(), 0)
                plain.setLength(0)
            }
        }
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '\n'.code -> {
                    flushPlain()
                    b.sendKey(TerminalKeys.ENTER, mods)
                    mods = 0
                }
                mods != 0 -> {
                    flushPlain()
                    b.sendText(String(Character.toChars(cp)), mods)
                    mods = 0
                }
                else -> plain.appendCodePoint(cp)
            }
        }
        flushPlain()
        onUserInput()
    }

    /** Whether a sticky modifier is armed for the next key. */
    internal fun hasActiveModifier(): Boolean = stickyModifiers?.isActive() == true

    /** Tells the IME our (empty) shadow buffer was reset, so it doesn't re-compose old text. */
    internal fun resetImeSelection() {
        inputMethodManager.updateSelection(this, 0, 0, -1, -1)
    }

    // ---------------------------------------------------------------- clipboard

    /** Pastes the clipboard's text (bracketed paste when the program enabled it). */
    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        if (text.isEmpty()) return
        backend?.paste(text)
        onUserInput()
    }

    /** Copies the selection to the clipboard and leaves selection mode. */
    fun copySelection() {
        val text = backend?.selectionText()
        if (text != null) {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("Ferrux", text))
        }
        selection.finish()
    }

    /** The menu for a long press on blank space: Paste (if there is a clip) and Select all. */
    private fun showPasteMenu(x: Float, y: Float) {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                if (clipboard?.hasPrimaryClip() == true) {
                    menu.add(Menu.NONE, MENU_PASTE, 0, android.R.string.paste)
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                }
                menu.add(Menu.NONE, MENU_SELECT_ALL, 1, android.R.string.selectAll)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                mode.finish()
                when (item.itemId) {
                    MENU_PASTE -> pasteFromClipboard()
                    MENU_SELECT_ALL -> backend?.let {
                        it.selectAll()
                        selection.start(fromLongPress = false)
                    }
                }
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) = Unit

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                outRect.set(x.toInt(), y.toInt(), x.toInt() + 1, y.toInt() + 1)
            }
        }, ActionMode.TYPE_FLOATING)
    }

    /** Sends a [TerminalKeys] special key as if pressed; sticky modifiers apply. */
    fun sendKey(key: Int) {
        backend?.sendKey(key, consumeSticky()) ?: return
        onUserInput()
    }

    private fun consumeSticky(): Int = stickyModifiers?.consume() ?: 0

    /** Jump back to the live screen when the user types while scrolled back. */
    private fun onUserInput() {
        if (selection.isActive) selection.finish()
        restartBlink()
        if (snapshot[L.H_DISPLAY_OFFSET] > 0) backend?.scroll(-snapshot[L.H_DISPLAY_OFFSET])
    }

    // ---------------------------------------------------------------- hardware keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val b = backend ?: return super.onKeyDown(keyCode, event)
        if (isSystemKey(keyCode)) return super.onKeyDown(keyCode, event)

        if (keyCode == KeyEvent.KEYCODE_PASTE ||
            (keyCode == KeyEvent.KEYCODE_V && event.isCtrlPressed && event.isShiftPressed)
        ) {
            pasteFromClipboard()
            return true
        }
        if (selection.isActive &&
            (keyCode == KeyEvent.KEYCODE_COPY ||
                (keyCode == KeyEvent.KEYCODE_C && event.isCtrlPressed && event.isShiftPressed))
        ) {
            copySelection()
            return true
        }
        // Ctrl+Alt+= / + / - zoom, as in Termux; plain Ctrl+- stays ^_ for readline.
        if (event.isCtrlPressed && event.isAltPressed) {
            val factor = when (keyCode) {
                KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> ZOOM_STEP
                KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 1f / ZOOM_STEP
                else -> 0f
            }
            if (factor != 0f) {
                zoomBy(factor)
                return true
            }
        }
        val meta = event.metaState
        val special = specialKey(keyCode)
        if (special != 0) {
            b.sendKey(special, metaToMods(meta) or consumeSticky())
            onUserInput()
            return true
        }

        // Printable key: resolve the character without Ctrl/Meta/left Alt (the
        // native encoder applies those), but keep Shift/Caps and right Alt,
        // which is AltGr on many layouts (German AltGr+Q = '@').
        val rightAlt = meta and KeyEvent.META_ALT_RIGHT_ON != 0
        var strip = KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK or KeyEvent.META_ALT_LEFT_ON
        if (!rightAlt) strip = strip or KeyEvent.META_ALT_ON
        var unicode = event.getUnicodeChar(meta and strip.inv())
        var mods = metaToMods(meta)
        if (rightAlt) {
            if (unicode != 0) {
                // AltGr produced a character; it is not an Alt modifier.
                if (meta and KeyEvent.META_ALT_LEFT_ON == 0) mods = mods and TerminalKeys.MOD_ALT.inv()
            } else {
                // No AltGr mapping on this layout: treat right Alt as Alt.
                unicode = event.getUnicodeChar(
                    meta and (KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK or KeyEvent.META_ALT_MASK).inv(),
                )
            }
        }
        if (unicode == 0) return super.onKeyDown(keyCode, event)
        mods = mods or consumeSticky()
        if (unicode and KeyCharacterMap.COMBINING_ACCENT != 0) {
            // TODO(M4): dead-key composition for hardware keyboards.
            return true
        }
        b.sendText(String(Character.toChars(unicode)), mods and TerminalKeys.MOD_SHIFT.inv())
        onUserInput()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (backend == null || isSystemKey(keyCode)) return super.onKeyUp(keyCode, event)
        return true
    }

    private fun isSystemKey(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_MENU -> true
        else -> false
    }

    companion object {
        private const val DEFAULT_TEXT_SIZE_SP = 13f
        private const val BLINK_INTERVAL_MS = 530L
        private const val MENU_PASTE = 1
        private const val MENU_SELECT_ALL = 2
        private const val MENU_OPEN_LINK = 3
        private const val MENU_COPY_LINK = 4

        /** Cursor colour of the default "Ferrux Night" palette (ferrux-term). */
        private const val CURSOR_COLOR = 0xFFF29E4C.toInt()
        private const val MIN_TEXT_SIZE_SP = 6f
        private const val MAX_TEXT_SIZE_SP = 40f
        private const val LINK_UNDERLINE_ALPHA = 0x99

        /** Lines per notch of a mouse wheel. */
        private const val WHEEL_LINES = 3f

        /** Flings sent to programs as keys/wheel events are scaled down by this. */
        private const val PROGRAM_FLING_FACTOR = 0.35f
        private const val SCROLLBAR_WIDTH_DP = 3f
        private const val SCROLLBAR_MARGIN_DP = 2f
        private const val SCROLLBAR_MIN_THUMB_DP = 32f
        private const val SCROLLBAR_MAX_ALPHA = 0x80
        private const val SCROLLBAR_LINGER_MS = 900L
        private const val SCROLLBAR_FADE_MS = 300L
        private const val JUMP_RADIUS_DP = 20f
        private const val JUMP_MARGIN_DP = 14f

        /** Font size change per Ctrl+Alt+= / Ctrl+Alt+- press. */
        private const val ZOOM_STEP = 1.1f

        /** Maps Android meta state to [TerminalKeys] modifier bits. */
        fun metaToMods(metaState: Int): Int {
            var mods = 0
            if (metaState and KeyEvent.META_SHIFT_ON != 0) mods = mods or TerminalKeys.MOD_SHIFT
            if (metaState and KeyEvent.META_ALT_ON != 0) mods = mods or TerminalKeys.MOD_ALT
            if (metaState and KeyEvent.META_CTRL_ON != 0) mods = mods or TerminalKeys.MOD_CTRL
            if (metaState and KeyEvent.META_META_ON != 0) mods = mods or TerminalKeys.MOD_META
            return mods
        }

        /** Maps an Android key code to a [TerminalKeys] special key, or 0. */
        fun specialKey(keyCode: Int): Int = when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> TerminalKeys.ENTER
            KeyEvent.KEYCODE_TAB -> TerminalKeys.TAB
            KeyEvent.KEYCODE_DEL -> TerminalKeys.BACKSPACE
            KeyEvent.KEYCODE_ESCAPE -> TerminalKeys.ESCAPE
            KeyEvent.KEYCODE_DPAD_UP -> TerminalKeys.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> TerminalKeys.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> TerminalKeys.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> TerminalKeys.RIGHT
            KeyEvent.KEYCODE_MOVE_HOME -> TerminalKeys.HOME
            KeyEvent.KEYCODE_MOVE_END -> TerminalKeys.END
            KeyEvent.KEYCODE_PAGE_UP -> TerminalKeys.PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> TerminalKeys.PAGE_DOWN
            KeyEvent.KEYCODE_INSERT -> TerminalKeys.INSERT
            KeyEvent.KEYCODE_FORWARD_DEL -> TerminalKeys.DELETE
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
                TerminalKeys.F1 + (keyCode - KeyEvent.KEYCODE_F1)
            else -> 0
        }
    }
}
