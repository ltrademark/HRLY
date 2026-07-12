/*
 * HRLY, a simple hourly chime app for Android.
 * Copyright (C) 2025-2026 Ltrademark
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * The HRLY name, logo, and branding are not covered by this license.
 * See TRADEMARK.md.
 */
package com.ltrademark.hourly

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A lightweight "video editor" style trim control: draws the decoded waveform of a
 * tone and lets the user drag a left (start) and right (end) handle to pick the
 * region to keep. The area outside the selection is dimmed. No external libraries:
 * amplitudes are supplied by the caller (decoded with MediaCodec) and everything
 * here is plain Canvas drawing + touch handling.
 */
class WaveformTrimView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density
    private val handleWidth = 12f * density
    private val grabSlop = 24f * density
    private val barGap = 1.5f * density

    private val selectedBar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val dimBar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4A4A4A") }
    private val scrim = Paint().apply { color = Color.parseColor("#88000000") }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val baseline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A4A4A"); strokeWidth = 1f * density
    }
    private val recLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0B0B0"); strokeWidth = 1.5f * density
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)
    }

    private var amps = FloatArray(0)
    private var durationMs = 1

    var startMs = 0
        private set
    var endMs = 0
        private set

    /** Minimum keep-length; mirrors ChimeService.MIN_CROP_MS. */
    var minGapMs = 100

    /**
     * Recommended keep-length (ms). When > 0, a dashed guide is drawn at
     * start + recommendedLenMs so the user can aim the end handle at the length
     * that fits the app's counting methodology. 0 = no guide.
     */
    var recommendedLenMs = 0
        set(value) { field = value; invalidate() }

    /** Fired continuously while a handle is dragged. */
    var onRangeChanged: ((Int, Int) -> Unit)? = null

    private var dragging = 0 // 0 none, 1 start, 2 end
    private val rect = RectF()

    fun setAudio(amplitudes: FloatArray, totalMs: Int) {
        amps = amplitudes
        durationMs = max(1, totalMs)
        if (endMs == 0 || endMs > durationMs) endMs = durationMs
        startMs = startMs.coerceIn(0, durationMs)
        invalidate()
    }

    fun setRange(s: Int, e: Int) {
        startMs = s.coerceIn(0, durationMs)
        endMs = e.coerceIn(0, durationMs)
        if (endMs - startMs < minGapMs) endMs = (startMs + minGapMs).coerceAtMost(durationMs)
        invalidate()
    }

    private fun usableLeft() = paddingLeft + handleWidth / 2f
    private fun usableRight() = width - paddingRight - handleWidth / 2f
    private fun usableWidth() = max(1f, usableRight() - usableLeft())

    private fun timeToX(ms: Int) = usableLeft() + usableWidth() * (ms.toFloat() / durationMs)
    private fun xToTime(x: Float): Int {
        val frac = ((x - usableLeft()) / usableWidth()).coerceIn(0f, 1f)
        return (frac * durationMs).toInt()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val mid = h / 2f
        val left = usableLeft()
        val right = usableRight()
        val startX = timeToX(startMs)
        val endX = timeToX(endMs)

        if (amps.isEmpty()) {
            canvas.drawLine(left, mid, right, mid, baseline)
        } else {
            val slotW = usableWidth() / amps.size
            val barW = max(1f * density, slotW - barGap)
            for (i in amps.indices) {
                val cx = left + slotW * (i + 0.5f)
                val amp = amps[i].coerceIn(0f, 1f)
                val half = max(1f * density, (h / 2f - 2f * density) * amp)
                rect.set(cx - barW / 2f, mid - half, cx + barW / 2f, mid + half)
                val inSel = cx in startX..endX
                canvas.drawRoundRect(rect, barW / 2f, barW / 2f, if (inSel) selectedBar else dimBar)
            }
        }

        // Dim the trimmed-out regions.
        if (startX > left) canvas.drawRect(0f, 0f, startX, h, scrim)
        if (endX < right) canvas.drawRect(endX, 0f, width.toFloat(), h, scrim)

        // Recommended-length guide (dashed), at start + recommendedLenMs.
        if (recommendedLenMs > 0) {
            val recMs = (startMs + recommendedLenMs).coerceAtMost(durationMs)
            val recX = timeToX(recMs)
            if (recX in (left + 1f)..(right - 1f)) canvas.drawLine(recX, 0f, recX, h, recLine)
        }

        drawHandle(canvas, startX, h)
        drawHandle(canvas, endX, h)
    }

    private fun drawHandle(canvas: Canvas, cx: Float, h: Float) {
        rect.set(cx - handleWidth / 2f, 0f, cx + handleWidth / 2f, h)
        canvas.drawRoundRect(rect, handleWidth / 2f, handleWidth / 2f, handlePaint)
        // Grip: two short white lines centered vertically.
        val gx = cx
        val gy = h / 2f
        val gl = 5f * density
        val off = 2.5f * density
        canvas.drawLine(gx - off, gy - gl, gx - off, gy + gl, gripLine)
        canvas.drawLine(gx + off, gy - gl, gx + off, gy + gl, gripLine)
    }

    private val gripLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = 1.5f * density; strokeCap = Paint.Cap.ROUND
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val dStart = abs(x - timeToX(startMs))
                val dEnd = abs(x - timeToX(endMs))
                dragging = when {
                    dStart <= grabSlop && dStart <= dEnd -> 1
                    dEnd <= grabSlop -> 2
                    else -> 0
                }
                if (dragging != 0) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging == 0) return false
                val t = xToTime(x)
                if (dragging == 1) {
                    startMs = min(t, endMs - minGapMs).coerceAtLeast(0)
                } else {
                    endMs = max(t, startMs + minGapMs).coerceAtMost(durationMs)
                }
                onRangeChanged?.invoke(startMs, endMs)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = 0
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return dragging != 0
    }

    companion object {
        private val ACCENT = Color.parseColor("#4C8DFF")
    }
}
