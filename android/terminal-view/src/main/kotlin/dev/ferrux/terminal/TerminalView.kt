// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import dev.ferrux.terminal.SnapshotLayout as L
import kotlin.math.abs
import kotlin.math.max

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

    /** The terminal being shown. Setting it resizes the backend to this view's grid. */
    var backend: TerminalBackend? = null
        set(value) {
            if (field === value) return
            field = value
            composing = ""
            lastResize = 0 to 0
            updateGridSize()
            invalidate()
        }

    /** One-shot modifiers (e.g. from an extra-keys row) applied to the next key or text. */
    var stickyModifiers: StickyModifiers? = null

    var imeMode: ImeMode = ImeMode.TEXT
        set(value) {
            if (field == value) return
            field = value
            inputMethodManager.restartInput(this)
        }

    /** Called after pinch-zoom changes the font size (in pixels), e.g. to persist it. */
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

    /** Terminal grid size derived from the view size and font metrics. */
    var rows: Int = 0
        private set
    var columns: Int = 0
        private set

    private val minTextSizePx = sp(MIN_TEXT_SIZE_SP)
    private val maxTextSizePx = sp(MAX_TEXT_SIZE_SP)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        isSubpixelText = true
    }
    private val fillPaint = Paint()
    private val linePaint = Paint().apply { style = Paint.Style.STROKE }

    private var cellWidth = 1f
    private var cellHeight = 1f
    private var baselineOffset = 0f

    private var snapshot = IntArray(L.requiredSize(24, 80))
    private val charBuffer = CharArray(512)
    private var composing = ""
    private var lastResize = 0 to 0
    private var scrollRemainder = 0f
    private var scaling = false

    private val inputMethodManager: InputMethodManager
        get() = context.getSystemService(InputMethodManager::class.java)

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            showKeyboard()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaling) return true
            // Finger moving down (dy < 0) reveals older output: scroll back.
            scrollRemainder -= dy
            val lines = (scrollRemainder / cellHeight).toInt()
            if (lines != 0) {
                scrollRemainder -= lines * cellHeight
                backend?.scroll(lines)
                invalidate()
            }
            return true
        }
    })

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
        updateFontMetrics()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Take focus as soon as we are on screen so hardware keys and IME
        // input reach the terminal rather than the toolbar.
        post { requestFocus() }
    }

    /** Requests a redraw. Safe to call from any thread. */
    fun onScreenUpdated() {
        postInvalidateOnAnimation()
    }

    fun showKeyboard() {
        requestFocus()
        inputMethodManager.showSoftInput(this, 0)
    }

    // ---------------------------------------------------------------- layout

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun updateFontMetrics() {
        textPaint.textSize = textSizePx
        val fm = textPaint.fontMetrics
        cellWidth = max(1f, textPaint.measureText("M"))
        cellHeight = max(1f, (fm.descent - fm.ascent).let { kotlin.math.ceil(it) })
        baselineOffset = -fm.ascent
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

        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        for (row in 0 until rows) {
            drawRowBackground(canvas, row, cols, defaultBg)
            drawRowText(canvas, row, cols)
        }
        drawCursor(canvas, rows, cols)
        canvas.restore()
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
                if (s[i + L.C_FG] != fg || f != flags || c == 0 || f and L.F_WIDE != 0) break
                len += Character.toChars(c, charBuffer, len)
                end++
            }
            if (end == col) {
                // A wide character (or a style change on the first cell): draw one cell.
                len = Character.toChars(cp, charBuffer, 0)
                end = col + if (flags and L.F_WIDE != 0) 2 else 1
                applyStyle(fg, flags)
                canvas.drawText(charBuffer, 0, len, col * cellWidth, baseline, textPaint)
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
        textPaint.isFakeBoldText = flags and L.F_BOLD != 0
        textPaint.textSkewX = if (flags and L.F_ITALIC != 0) -0.25f else 0f
    }

    private fun dim(color: Int): Int = Color.argb(
        Color.alpha(color),
        Color.red(color) * 2 / 3,
        Color.green(color) * 2 / 3,
        Color.blue(color) * 2 / 3,
    )

    private fun drawDecorations(canvas: Canvas, row: Int, start: Int, end: Int, flags: Int, fg: Int) {
        val decorations = L.F_UNDERLINE or L.F_DOUBLE_UNDERLINE or L.F_UNDERCURL or L.F_STRIKEOUT
        if (flags and decorations == 0) return
        linePaint.color = if (flags and L.F_DIM != 0) dim(fg) else fg
        val x0 = start * cellWidth
        val x1 = end * cellWidth
        val top = row * cellHeight
        val underlineY = top + baselineOffset + linePaint.strokeWidth * 1.5f
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
        fillPaint.color = fg
        when (shapeBits and L.CURSOR_SHAPE_MASK) {
            L.CURSOR_BLOCK -> if (shapeBits and L.CURSOR_HOLLOW != 0) {
                linePaint.color = fg
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
        if (event.actionMasked == MotionEvent.ACTION_DOWN) scrollRemainder = 0f
        return true
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
        val mods = consumeSticky()
        var start = 0
        while (start <= text.length) {
            val nl = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            if (nl > start) b.sendText(text.substring(start, nl), mods)
            if (nl < text.length) b.sendKey(TerminalKeys.ENTER, mods)
            start = nl + 1
        }
        onUserInput()
    }

    /** Sends a [TerminalKeys] special key as if pressed; sticky modifiers apply. */
    fun sendKey(key: Int) {
        backend?.sendKey(key, consumeSticky()) ?: return
        onUserInput()
    }

    private fun consumeSticky(): Int = stickyModifiers?.consume() ?: 0

    /** Jump back to the live screen when the user types while scrolled back. */
    private fun onUserInput() {
        if (snapshot[L.H_DISPLAY_OFFSET] > 0) backend?.scroll(-snapshot[L.H_DISPLAY_OFFSET])
    }

    // ---------------------------------------------------------------- hardware keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val b = backend ?: return super.onKeyDown(keyCode, event)
        if (isSystemKey(keyCode)) return super.onKeyDown(keyCode, event)

        val mods = metaToMods(event.metaState) or consumeSticky()
        val special = specialKey(keyCode)
        if (special != 0) {
            b.sendKey(special, mods)
            onUserInput()
            return true
        }

        // Printable key: resolve the character without Ctrl/Alt/Meta (the native
        // encoder applies those), but with Shift/Caps/AltGr layout handling.
        val stripped = event.metaState and
            (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK).inv()
        val unicode = event.getUnicodeChar(stripped)
        if (unicode == 0) return super.onKeyDown(keyCode, event)
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
        private const val MIN_TEXT_SIZE_SP = 6f
        private const val MAX_TEXT_SIZE_SP = 40f

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
