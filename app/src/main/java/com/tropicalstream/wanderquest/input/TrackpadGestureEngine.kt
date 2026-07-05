package com.tropicalstream.wanderquest.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.max

/**
 * Self-contained gesture engine for the X3 Pro temple trackpads, built to
 * the on-device-tuned contract documented in the dossier (§4) and the
 * MercurySDK reference (TempleAction semantics: click = trigger,
 * double-click = back, slide = focus move):
 *
 *  - RIGHT temple pad = `cyttsp5_mt`. A LIGHT tap on the pad surface is a
 *    plain touch DOWN/UP MotionEvent. A FIRM physical click arrives as a
 *    hardware KEY (KEYCODE_BUTTON_A / KEYCODE_DPAD_CENTER) — and one firm
 *    click can be observed on BOTH paths (gotcha #11), so taps from the
 *    two sources are deduped against each other.
 *  - LEFT temple pad = `cyttsp6_mt` = the system volume pad. Ignored.
 *  - Match input devices by NAME; device ids shuffle across reboots.
 *
 * Feed it from the Activity's dispatchTouchEvent / dispatchKeyEvent /
 * dispatchGenericMotionEvent.
 */
class TrackpadGestureEngine {

    companion object {
        const val SHORT_TAP_MAX_MS = 300L      // touch path: max press for a tap
        const val DOUBLE_TAP_WINDOW_MS = 300L  // single-vs-double decision window
        const val LONG_TAP_MIN_MS = 600L       // press-and-hold threshold

        // Key path (dossier §4.3, tuned on hardware)
        const val KEY_TAP_MAX_MS = 400L
        const val ECHO_MIN_GAP_MS = 40L        // same-source keycode echo filter
        const val CROSS_SOURCE_DEDUP_MS = 250L // touch+key seeing one physical tap

        const val GENERIC_SCROLL_SCALE = 22f
        private const val TAP_MOVE_TOLERANCE_MIN_PX = 18f
        private const val TAP_MOVE_TOLERANCE_RATIO = 0.04f
        private const val SWIPE_MIN_PX = 26f

        const val LEFT_ARM_DEVICE = "cyttsp6"

        private const val SRC_TOUCH = 0
        private const val SRC_KEY = 1
    }

    /** Single confirmed temple tap (fires after the double-tap window). */
    var onTap: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var onTripleTap: (() -> Unit)? = null
    var onLongTap: (() -> Unit)? = null
    /** Vertical swipe on the pad: -1 = up, +1 = down. */
    var onSwipeVertical: ((direction: Int) -> Unit)? = null
    /** Horizontal swipe on the pad: -1 = back(ward), +1 = forward. */
    var onSwipeHorizontal: ((direction: Int) -> Unit)? = null
    /** LEFT temple tap — "use special item" (volume swipes still pass through). */
    var onLeftTap: (() -> Unit)? = null
    /** Raw input trace for on-device diagnosis (shown on title/debug HUD). */
    var debugSink: ((String) -> Unit)? = null

    // left-arm tap state (cyttsp6: tap ≤300 ms, ≤30 px PEAK — TapInsight-tuned)
    private var leftDownMs = 0L
    private var leftStartX = 0f
    private var leftStartY = 0f
    private var leftPeakMove = 0f
    private var leftTracking = false

    private val handler = Handler(Looper.getMainLooper())

    // ---- unified tap-streak state (fed by BOTH touch and key paths) ----
    private var lastTapMs = 0L
    private var lastTapSource = -1
    private var tapStreak = 0
    private val resolveTaps = Runnable {
        val streak = tapStreak
        tapStreak = 0
        when {
            streak >= 3 -> onTripleTap?.invoke()
            streak == 2 -> onDoubleTap?.invoke()
            streak == 1 -> onTap?.invoke()
        }
    }

    private fun registerTap(source: Int) {
        val now = SystemClock.uptimeMillis()
        val gap = now - lastTapMs
        if (lastTapMs > 0L) {
            // One physical tap echoing across the other path — swallow it.
            if (source != lastTapSource && gap < CROSS_SOURCE_DEDUP_MS) {
                debugSink?.invoke("tap dedup (src $source, ${gap}ms)")
                return
            }
            // Same-source keycode echo.
            if (source == lastTapSource && gap < ECHO_MIN_GAP_MS) return
        }
        lastTapMs = now
        lastTapSource = source
        tapStreak += 1
        debugSink?.invoke("tap x$tapStreak (src ${if (source == SRC_KEY) "key" else "touch"})")
        handler.removeCallbacks(resolveTaps)
        if (tapStreak >= 3) {
            resolveTaps.run()
        } else {
            handler.postDelayed(resolveTaps, DOUBLE_TAP_WINDOW_MS)
        }
    }

    // ---- key path state ----
    private var keyDownMs = 0L
    private var keyTracking = false
    private var keyLongFired = false
    private val keyLongCheck = Runnable {
        if (keyTracking) {
            keyLongFired = true
            debugSink?.invoke("long-press (key)")
            onLongTap?.invoke()
        }
    }

    // ---- touch path state ----
    private var touchDownMs = 0L
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchAccumX = 0f
    private var touchAccumY = 0f
    private var touchTracking = false
    private var touchMovedTooFar = false
    private var touchLongFired = false
    private var swipeFiredForGesture = false
    private var screenW = 640
    private var screenH = 480
    private val touchLongCheck = Runnable {
        if (touchTracking && !touchMovedTooFar && !swipeFiredForGesture) {
            touchLongFired = true
            debugSink?.invoke("long-press (touch)")
            onLongTap?.invoke()
        }
    }

    fun setScreenSize(width: Int, height: Int) {
        if (width > 0) screenW = width
        if (height > 0) screenH = height
    }

    /** True when the event comes from the LEFT (volume) pad — ignore it. */
    fun isLeftArmDevice(deviceId: Int): Boolean {
        val name = InputDevice.getDevice(deviceId)?.name ?: return false
        return name.contains(LEFT_ARM_DEVICE, ignoreCase = true)
    }

    private fun deviceName(deviceId: Int): String =
        InputDevice.getDevice(deviceId)?.name ?: "id$deviceId"

    /**
     * Raw trackpad MotionEvents: light taps, long-press, and swipes.
     * Returns true when consumed. Left-arm (volume) events are swallowed.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        if (isLeftArmDevice(event.deviceId)) {
            // The left pad is the system volume pad — we never navigate with
            // it, but a quick TAP (≤300 ms, ≤30 px travel) is the "use item"
            // button. Volume swipes exceed the tolerance and pass untouched.
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    leftDownMs = SystemClock.uptimeMillis()
                    leftStartX = event.x
                    leftStartY = event.y
                    leftPeakMove = 0f
                    leftTracking = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (leftTracking) {
                        // PEAK displacement — an out-and-back volume scrub
                        // must not register as a tap.
                        leftPeakMove = max(
                            leftPeakMove,
                            max(abs(event.x - leftStartX), abs(event.y - leftStartY))
                        )
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (leftTracking) {
                        leftTracking = false
                        val held = SystemClock.uptimeMillis() - leftDownMs
                        if (held <= 300L && leftPeakMove <= 30f) {
                            debugSink?.invoke("left-arm tap")
                            onLeftTap?.invoke()
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> leftTracking = false
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                debugSink?.invoke("touch DOWN ${deviceName(event.deviceId)}")
                touchDownMs = SystemClock.uptimeMillis()
                touchStartX = event.x
                touchStartY = event.y
                touchAccumX = 0f
                touchAccumY = 0f
                touchTracking = true
                touchMovedTooFar = false
                touchLongFired = false
                swipeFiredForGesture = false
                handler.postDelayed(touchLongCheck, LONG_TAP_MIN_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchTracking) {
                    touchAccumX = event.x - touchStartX
                    touchAccumY = event.y - touchStartY
                    val tol = max(TAP_MOVE_TOLERANCE_MIN_PX, TAP_MOVE_TOLERANCE_RATIO * minOf(screenW, screenH))
                    if (abs(touchAccumX) > tol || abs(touchAccumY) > tol) {
                        touchMovedTooFar = true
                        handler.removeCallbacks(touchLongCheck)
                    }
                    maybeFireSwipe()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(touchLongCheck)
                if (touchTracking) {
                    touchTracking = false
                    val held = SystemClock.uptimeMillis() - touchDownMs
                    val isTap = event.actionMasked == MotionEvent.ACTION_UP &&
                        !touchMovedTooFar && !swipeFiredForGesture &&
                        !touchLongFired && held <= SHORT_TAP_MAX_MS
                    if (isTap) registerTap(SRC_TOUCH)
                }
            }
        }
        return true
    }

    private fun maybeFireSwipe() {
        if (swipeFiredForGesture) return
        // Raw pad coords are not always screen-normalized; use generous
        // absolute thresholds scaled against whatever metrics we were fed.
        val minSwipe = max(SWIPE_MIN_PX, 0.06f * minOf(screenW, screenH))
        val ax = abs(touchAccumX)
        val ay = abs(touchAccumY)
        if (ay >= minSwipe && ay > ax * 1.3f) {
            swipeFiredForGesture = true
            handler.removeCallbacks(touchLongCheck)
            debugSink?.invoke("swipe ${if (touchAccumY > 0) "down" else "up"}")
            onSwipeVertical?.invoke(if (touchAccumY > 0) 1 else -1)
        } else if (ax >= minSwipe * 1.6f && ax > ay * 1.3f) {
            swipeFiredForGesture = true
            handler.removeCallbacks(touchLongCheck)
            debugSink?.invoke("swipe ${if (touchAccumX > 0) "fwd" else "back"}")
            onSwipeHorizontal?.invoke(if (touchAccumX > 0) 1 else -1)
        }
    }

    /**
     * Temple FIRM-click path. MUST be called FIRST in dispatchKeyEvent so a
     * focused view can't swallow the click. Returns true when consumed.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val isTapKey = event.keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER
        if (!isTapKey) {
            debugSink?.invoke("key ${event.keyCode} a=${event.action} (passed)")
            return false
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    debugSink?.invoke("key DOWN ${event.keyCode}")
                    keyDownMs = SystemClock.uptimeMillis()
                    keyTracking = true
                    keyLongFired = false
                    handler.postDelayed(keyLongCheck, LONG_TAP_MIN_MS)
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(keyLongCheck)
                if (!keyTracking) return true
                keyTracking = false
                if (keyLongFired) return true
                val held = SystemClock.uptimeMillis() - keyDownMs
                if (held < KEY_TAP_MAX_MS) registerTap(SRC_KEY)
                return true
            }
        }
        return true
    }

    /** ACTION_SCROLL deltas from dispatchGenericMotionEvent. */
    fun onGenericMotion(event: MotionEvent): Boolean {
        val src = event.source
        val pointerClass = (src and InputDevice.SOURCE_CLASS_POINTER) != 0 ||
            (src and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
            (src and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD
        if (!pointerClass) return false
        if (event.actionMasked != MotionEvent.ACTION_SCROLL) return false
        if (isLeftArmDevice(event.deviceId)) return true
        if (touchTracking) return true // pad emits both MOVE and SCROLL — don't double-fire
        var dx = event.getAxisValue(MotionEvent.AXIS_HSCROLL) * GENERIC_SCROLL_SCALE
        var dy = event.getAxisValue(MotionEvent.AXIS_VSCROLL) * GENERIC_SCROLL_SCALE
        if (dx == 0f && dy == 0f) {
            dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X) * GENERIC_SCROLL_SCALE
            dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y) * GENERIC_SCROLL_SCALE
        }
        if (abs(dy) >= SWIPE_MIN_PX && abs(dy) > abs(dx)) {
            debugSink?.invoke("scroll v ${"%.0f".format(dy)}")
            onSwipeVertical?.invoke(if (dy > 0) -1 else 1) // scroll convention inverted
            return true
        }
        if (abs(dx) >= SWIPE_MIN_PX && abs(dx) > abs(dy)) {
            debugSink?.invoke("scroll h ${"%.0f".format(dx)}")
            onSwipeHorizontal?.invoke(if (dx > 0) 1 else -1)
            return true
        }
        return true
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
    }
}
