package com.tropicalstream.wanderquest.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * Binocular side-by-side layout for the RayNeo X3 Pro.
 *
 * Holds exactly ONE child (the logical 640x480 viewport), measures it to
 * half the physical width (1280x480 -> 640x480), and draws it twice:
 * once at x=0 for the left eye and once translated by logicalWidth for
 * the right eye. The wearer's brain fuses the two identical images.
 *
 * This is the dual-draw path proven on-device by TapInsight / Everyday /
 * Moonlight. (The vendor MirroringView alternative is deliberately not
 * used — it steals SurfaceView layers and ships behind a fallback for a
 * reason.)
 */
class BinocularSbsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var remapCurrentTouchSequence = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val child = getChildAt(0) ?: return
        val logicalWidth = logicalViewportWidth(measuredWidth)
        val logicalHeight = measuredHeight.coerceAtLeast(0)
        child.measure(
            MeasureSpec.makeMeasureSpec(logicalWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(logicalHeight, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val child = getChildAt(0) ?: return
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val child = getChildAt(0) ?: return
        val logicalWidth = logicalViewportWidth(width)
        val drawTime = drawingTime
        canvas.save()
        canvas.clipRect(0, 0, logicalWidth, height)
        drawChild(canvas, child, drawTime)
        canvas.restore()
        canvas.save()
        canvas.translate(logicalWidth.toFloat(), 0f)
        canvas.clipRect(0, 0, logicalWidth, height)
        drawChild(canvas, child, drawTime)
        canvas.restore()
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        // Both halves must redraw whenever logical content changes.
        invalidate()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val logicalWidth = logicalViewportWidth(width)
        if (logicalWidth <= 0) return super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // Latch per gesture so a drag crossing the seam stays consistent.
            remapCurrentTouchSequence = ev.getX(0) >= logicalWidth
        }
        if (!remapCurrentTouchSequence) return super.dispatchTouchEvent(ev)
        val remapped = MotionEvent.obtain(ev)
        remapped.offsetLocation(-logicalWidth.toFloat(), 0f)
        val handled = super.dispatchTouchEvent(remapped)
        remapped.recycle()
        return handled
    }

    private fun logicalViewportWidth(totalWidth: Int) = (totalWidth / 2).coerceAtLeast(0)
}
