package com.screenwordpulse.core

/** Яркостная плоскость кадра (Y из YUV): w*h байт, построчно. */
class Luma(val data: ByteArray, val w: Int, val h: Int) {
    fun at(x: Int, y: Int): Int = data[y * w + x].toInt() and 0xFF

    /** Поворот по часовой стрелке на 0/90/180/270 — чтобы кадр был «вертикально». */
    fun rotated(degrees: Int): Luma {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return this
        val out: ByteArray
        return when (d) {
            90 -> {
                out = ByteArray(w * h)
                for (y in 0 until h) for (x in 0 until w) out[x * h + (h - 1 - y)] = data[y * w + x]
                Luma(out, h, w)
            }
            180 -> {
                out = ByteArray(w * h)
                for (i in 0 until w * h) out[w * h - 1 - i] = data[i]
                Luma(out, w, h)
            }
            270 -> {
                out = ByteArray(w * h)
                for (y in 0 until h) for (x in 0 until w) out[(w - 1 - x) * h + y] = data[y * w + x]
                Luma(out, h, w)
            }
            else -> this
        }
    }
}

/** Быстрые метрики кадра без ИИ: резкость, блики, яркость. */
object LumaMetrics {
    private const val SHARP_K = 150.0

    /** Резкость 0..1 по дисперсии лапласиана. ~0.1 — смаз, ~0.7+ — чёткий печатный текст. */
    fun sharpness(l: Luma): Float {
        if (l.w < 3 || l.h < 3) return 0f
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        var y = 1
        while (y < l.h - 1) {
            var x = 1
            while (x < l.w - 1) {
                val lap = 4 * l.at(x, y) - l.at(x - 1, y) - l.at(x + 1, y) - l.at(x, y - 1) - l.at(x, y + 1)
                sum += lap
                sumSq += lap.toDouble() * lap
                n++
                x += 2
            }
            y += 2
        }
        if (n == 0) return 0f
        val mean = sum / n
        val variance = sumSq / n - mean * mean
        return (variance / (variance + SHARP_K)).toFloat()
    }

    fun brightness(l: Luma): Float {
        var s = 0L
        var n = 0
        var i = 0
        while (i < l.data.size) { s += l.data[i].toInt() and 0xFF; n++; i += 7 }
        return if (n == 0) 0f else s.toFloat() / n / 255f
    }

    /**
     * Блики: ячейки сетки, где почти все пиксели «выбелены». Ячейка с обычным текстом
     * содержит тёмные штрихи букв и бликом не считается; белые поля страницы строк не содержат.
     */
    fun glare(l: Luma, gridX: Int = 16, gridY: Int = 24, white: Int = 245, share: Float = 0.9f): List<NBox> {
        val out = ArrayList<NBox>()
        for (gy in 0 until gridY) {
            val y0 = gy * l.h / gridY
            val y1 = (gy + 1) * l.h / gridY
            for (gx in 0 until gridX) {
                val x0 = gx * l.w / gridX
                val x1 = (gx + 1) * l.w / gridX
                var bright = 0
                var total = 0
                var y = y0
                while (y < y1) {
                    var x = x0
                    while (x < x1) {
                        if (l.at(x, y) >= white) bright++
                        total++
                        x += 2
                    }
                    y += 2
                }
                if (total > 0 && bright.toFloat() / total >= share) {
                    out.add(NBox(x0.toFloat() / l.w, y0.toFloat() / l.h, x1.toFloat() / l.w, y1.toFloat() / l.h))
                }
            }
        }
        return out
    }
}
