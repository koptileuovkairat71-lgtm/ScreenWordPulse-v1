package com.screenwordpulse.camera

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.screenwordpulse.core.FrameInput
import com.screenwordpulse.core.FrameVerdict
import com.screenwordpulse.core.Luma
import com.screenwordpulse.core.LumaMetrics
import com.screenwordpulse.core.ScanSession
import com.screenwordpulse.ocr.OcrEngine

/**
 * Кадр CameraX -> Luma -> метрики -> OCR (с троттлингом) -> ScanSession.
 * - OCR не чаще 1 раза в 400 мс
 * - При сильном смазе OCR пропускаем: кадр заведомо красный
 * - Даунскейл для OCR до 1280 px по длинной стороне
 */
class FrameAnalyzer(
    private val ocr: OcrEngine,
    private val session: ScanSession,
    private val onVerdict: (FrameVerdict, aspect: Float) -> Unit,
    private val minOcrIntervalMs: Long = 400L,
    private val severeBlurThreshold: Float = 0.25f,
    private val maxOcrSide: Int = 1280,
) : ImageAnalysis.Analyzer {

    @Volatile private var lastOcrMs = 0L
    @Volatile private var busy = false

    override fun analyze(image: ImageProxy) {
        try {
            if (busy) return
            val now = SystemClock.elapsedRealtime()
            val lumaFull = extractLuma(image).rotated(image.imageInfo.rotationDegrees)
            val sharpness = LumaMetrics.sharpness(lumaFull)
            val glare = LumaMetrics.glare(lumaFull)
            val brightness = LumaMetrics.brightness(lumaFull)
            // Смаз - сразу вердикт без OCR
            if (sharpness < severeBlurThreshold) {
                val verdict = synchronized(session) {
                    session.onFrame(FrameInput(now, sharpness, glare, brightness, emptyList()))
                }
                onVerdict(verdict, lumaFull.w.toFloat() / lumaFull.h)
                return
            }
            if (now - lastOcrMs < minOcrIntervalMs) return
            busy = true
            lastOcrMs = now
            val luma = downscale(lumaFull, maxOcrSide)
            val lines = ocr.recognize(toBitmap(luma))
            // Координаты OCR уже в 0..1, даунскейл их не меняет
            val verdict = synchronized(session) {
                session.onFrame(FrameInput(now, sharpness, glare, brightness, lines))
            }
            onVerdict(verdict, lumaFull.w.toFloat() / lumaFull.h)
        } finally {
            busy = false
            image.close()
        }
    }

    private fun downscale(l: Luma, maxSide: Int): Luma {
        val longest = maxOf(l.w, l.h)
        if (longest <= maxSide) return l
        val scale = maxSide.toFloat() / longest
        val nw = maxOf(1, (l.w * scale).toInt())
        val nh = maxOf(1, (l.h * scale).toInt())
        val out = ByteArray(nw * nh)
        for (y in 0 until nh) {
            val sy = (y / scale).toInt().coerceIn(0, l.h - 1)
            for (x in 0 until nw) {
                val sx = (x / scale).toInt().coerceIn(0, l.w - 1)
                out[y * nw + x] = l.data[sy * l.w + sx]
            }
        }
        return Luma(out, nw, nh)
    }

    private fun extractLuma(image: ImageProxy): Luma {
        val p = image.planes[0]
        val w = image.width; val h = image.height
        val buf = p.buffer
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            buf.position(y * p.rowStride)
            buf.get(out, y * w, w)
        }
        return Luma(out, w, h)
    }

    private fun toBitmap(l: Luma): Bitmap {
        val px = IntArray(l.w * l.h)
        for (i in px.indices) {
            val v = l.data[i].toInt() and 0xFF
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(px, l.w, l.h, Bitmap.Config.ARGB_8888)
    }
}
