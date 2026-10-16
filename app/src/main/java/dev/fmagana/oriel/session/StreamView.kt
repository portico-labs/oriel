package dev.fmagana.oriel.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import dev.fmagana.oriel.protocol.InputEvent
import dev.fmagana.oriel.protocol.PointerAction
import dev.fmagana.oriel.protocol.ScrollPhase
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The picture and the touch layer over it, sized by its parent to the video's aspect ratio, so a
 * position is simply a fraction of this view. Direct touch, as Sill's iPad app: a tap clicks there,
 * a long press right-clicks, one or two fingers scroll (with the Mac's rubber band and a coast
 * after a flick). A stylus or a mouse is the Mac's mouse: down, drag, up, hover. Keys from a
 * hardware keyboard and the on-screen keyboard go to the Mac while this view has focus.
 */
class StreamView(context: Context) : FrameLayout(context) {
    var send: (InputEvent) -> Unit = {}
    var onSurface: (android.view.Surface?) -> Unit = {}

    private val surfaceView = SurfaceView(context)
    private val main = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressMs = 450L

    init {
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = onSurface(holder.surface)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) = onSurface(null)
        })
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // MARK: positions

    private fun fx(x: Float) = (x / width.coerceAtLeast(1)).toDouble().coerceIn(0.0, 1.0)
    private fun fy(y: Float) = (y / height.coerceAtLeast(1)).toDouble().coerceIn(0.0, 1.0)

    private fun pointer(action: PointerAction, x: Float, y: Float) = send(InputEvent.Pointer(action, fx(x), fy(y)))

    // MARK: fingers

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var panning = false
    private var longPressed = false
    private var velocity: VelocityTracker? = null
    private val longPress = Runnable {
        longPressed = true
        pointer(PointerAction.MOVE, downX, downY)
        pointer(PointerAction.RIGHT_DOWN, downX, downY)
        pointer(PointerAction.RIGHT_UP, downX, downY)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    // A stylus or mouse button held down: the Mac's mouse button.
    private var mouseButton: PointerAction? = null

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val tool = e.getToolType(0)
        if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_MOUSE) return mouse(e)
        return finger(e)
    }

    private fun centroid(e: MotionEvent): Pair<Float, Float> {
        var x = 0f; var y = 0f
        val n = e.pointerCount.coerceAtMost(2)
        for (i in 0 until n) { x += e.getX(i); y += e.getY(i) }
        return x / n to y / n
    }

    private fun finger(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                stopMomentum()
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                panning = false
                longPressed = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().apply { addMovement(e) }
                main.postDelayed(longPress, longPressMs)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // The centroid jumps when a finger joins or leaves: start measuring again from here.
                main.removeCallbacks(longPress)
                val (cx, cy) = centroidExcluding(e)
                lastX = cx; lastY = cy
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (longPressed) return true
                val (cx, cy) = centroid(e)
                if (!panning && (hypot(cx - downX, cy - downY) > touchSlop || e.pointerCount > 1)) {
                    main.removeCallbacks(longPress)
                    panning = true
                    send(InputEvent.ScrollGesture(ScrollPhase.BEGAN, fx(cx), fy(cy)))
                }
                if (panning) {
                    val dx = cx - lastX
                    val dy = cy - lastY
                    if (dx != 0f || dy != 0f) {
                        send(InputEvent.Scroll(fx(cx), fy(cy), (dx / width).toDouble(), (dy / height).toDouble()))
                    }
                }
                lastX = cx; lastY = cy
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(longPress)
                velocity?.addMovement(e)
                when {
                    longPressed -> Unit
                    panning -> {
                        send(InputEvent.ScrollGesture(ScrollPhase.ENDED, fx(lastX), fy(lastY)))
                        velocity?.computeCurrentVelocity(1000)
                        startMomentum(velocity?.xVelocity ?: 0f, velocity?.yVelocity ?: 0f, lastX, lastY)
                    }
                    else -> {
                        pointer(PointerAction.MOVE, e.x, e.y)
                        pointer(PointerAction.LEFT_DOWN, e.x, e.y)
                        pointer(PointerAction.LEFT_UP, e.x, e.y)
                    }
                }
                panning = false
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longPress)
                if (panning) send(InputEvent.ScrollGesture(ScrollPhase.ENDED, fx(lastX), fy(lastY)))
                panning = false
                velocity?.recycle(); velocity = null
            }
        }
        return true
    }

    private fun centroidExcluding(e: MotionEvent): Pair<Float, Float> {
        val skip = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        var x = 0f; var y = 0f; var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == skip || n == 2) continue
            x += e.getX(i); y += e.getY(i); n++
        }
        return if (n == 0) lastX to lastY else x / n to y / n
    }

    // MARK: the coast after a flick, generated here as the iPad app does

    private var momentum: Runnable? = null

    private fun startMomentum(vx0: Float, vy0: Float, x: Float, y: Float) {
        if (hypot(vx0, vy0) < 300f) return
        var vx = vx0; var vy = vy0
        send(InputEvent.ScrollGesture(ScrollPhase.MOMENTUM_BEGAN, fx(x), fy(y)))
        val step = object : Runnable {
            override fun run() {
                val dt = 1f / 60f
                send(InputEvent.Scroll(fx(x), fy(y), (vx * dt / width).toDouble(), (vy * dt / height).toDouble()))
                vx *= 0.95f; vy *= 0.95f
                if (abs(vx) < 20f && abs(vy) < 20f) {
                    momentum = null
                    send(InputEvent.ScrollGesture(ScrollPhase.MOMENTUM_ENDED, fx(x), fy(y)))
                } else {
                    main.postDelayed(this, 16)
                }
            }
        }
        momentum = step
        main.post(step)
    }

    private fun stopMomentum() {
        momentum?.let {
            main.removeCallbacks(it)
            momentum = null
            send(InputEvent.ScrollGesture(ScrollPhase.MOMENTUM_ENDED, fx(lastX), fy(lastY)))
        }
    }

    // MARK: stylus and mouse

    private fun mouse(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                val secondary = e.buttonState and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                pointer(PointerAction.MOVE, e.x, e.y)
                val down = if (secondary) PointerAction.RIGHT_DOWN else PointerAction.LEFT_DOWN
                mouseButton = down
                pointer(down, e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> pointer(PointerAction.MOVE, e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val up = if (mouseButton == PointerAction.RIGHT_DOWN) PointerAction.RIGHT_UP else PointerAction.LEFT_UP
                mouseButton = null
                pointer(up, e.x, e.y)
            }
        }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> { pointer(PointerAction.MOVE, e.x, e.y); return true }
            MotionEvent.ACTION_SCROLL -> {
                // A wheel's notches: plain deltas at the pointer, no gesture around them.
                val notch = 48f
                val dx = -e.getAxisValue(MotionEvent.AXIS_HSCROLL) * notch
                val dy = e.getAxisValue(MotionEvent.AXIS_VSCROLL) * notch
                send(InputEvent.Scroll(fx(e.x), fy(e.y), (dx / width).toDouble(), (dy / height).toDouble()))
                return true
            }
        }
        return super.onGenericMotionEvent(e)
    }

    // MARK: keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = key(event, down = true) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = key(event, down = false) || super.onKeyUp(keyCode, event)

    private fun key(event: KeyEvent, down: Boolean): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return false
        val usage = KeyMap.hidUsage(event.keyCode)
        if (usage != null) {
            send(InputEvent.Key(usage, down, KeyMap.modifiers(event.metaState)))
            return true
        }
        val ch = event.unicodeChar
        if (down && ch != 0 && !Character.isISOControl(ch)) {
            send(InputEvent.Text(String(Character.toChars(ch))))
            return true
        }
        return false
    }

    fun showKeyboard(show: Boolean) {
        val imm = context.getSystemService(InputMethodManager::class.java)
        if (show) {
            requestFocus()
            imm.showSoftInput(this, 0)
        } else {
            imm.hideSoftInputFromWindow(windowToken, 0)
        }
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // A visible-password field: keyboards commit each character as it is typed, with no
        // suggestions, no autocorrect and nothing composed behind the Mac's back.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            private var composing = ""

            /** Types the difference between what the Mac has from this composition and `text`. */
            private fun typeTowards(text: String) {
                val common = composing.commonPrefixWith(text).length
                val erase = composing.length - common
                val out = "\u0008".repeat(erase) + text.substring(common)
                if (out.isNotEmpty()) send(InputEvent.Text(out))
            }

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                typeTowards(text.toString())
                composing = ""
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                typeTowards(text.toString())
                composing = text.toString()
                return true
            }

            override fun finishComposingText(): Boolean {
                composing = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0) send(InputEvent.Text("\u0008".repeat(beforeLength)))
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> key(event, true)
                    KeyEvent.ACTION_UP -> key(event, false)
                }
                return true
            }
        }
    }
}
