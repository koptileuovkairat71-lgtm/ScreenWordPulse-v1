package com.screenwordpulse.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.screenwordpulse.core.FrameVerdict
import com.screenwordpulse.core.Quality

/** AR-подсветка строк: зелёная / жёлтая / красная рамка поверх превью (превью — FIT_CENTER). */
class OverlayView(context: Context) : View(context) {
    @Volatile private var verdict: FrameVerdict? = null
    @Volatile private var aspect = 3f / 4f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }

    fun show(v: FrameVerdict, frameAspect: Float) {
        verdict = v; aspect = frameAspect
        postInvalidate()
    }

    fun clear() { verdict = null; postInvalidate() }

    override fun onDraw(c: Canvas) {
        val v = verdict ?: return
        // прямоугольник, который реально занимает кадр внутри вью при FIT_CENTER
        val vw = width.toFloat(); val vh = height.toFloat()
        val fw: Float; val fh: Float
        if (vw / vh > aspect) { fh = vh; fw = vh * aspect } else { fw = vw; fh = vw / aspect }
        val ox = (vw - fw) / 2f; val oy = (vh - fh) / 2f
        for (lv in v.lines) {
            val color = when (lv.quality) {
                Quality.GREEN -> 0xFF2ECC71.toInt()
                Quality.YELLOW -> 0xFFF1C40F.toInt()
                Quality.RED -> 0xFFE74C3C.toInt()
            }
            val b = lv.line.box
            val r = RectF(ox + b.left * fw, oy + b.top * fh, ox + b.right * fw, oy + b.bottom * fh)
            fill.color = (color and 0x00FFFFFF) or 0x40000000
            stroke.color = color
            c.drawRect(r, fill)
            c.drawRect(r, stroke)
        }
    }
}
