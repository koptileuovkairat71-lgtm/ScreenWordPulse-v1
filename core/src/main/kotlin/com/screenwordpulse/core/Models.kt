package com.screenwordpulse.core

/** Прямоугольник в нормализованных координатах кадра (0..1), top — сверху. */
data class NBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val height: Float get() = bottom - top
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = maxOf(0f, right - left) * maxOf(0f, bottom - top)

    fun intersectionArea(o: NBox): Float {
        val w = minOf(right, o.right) - maxOf(left, o.left)
        val h = minOf(bottom, o.bottom) - maxOf(top, o.top)
        return if (w > 0f && h > 0f) w * h else 0f
    }
}

/** Одна строка, распознанная OCR в одном кадре. confidence: 0..1. */
data class OcrLine(val text: String, val confidence: Float, val box: NBox)

/**
 * Всё, что приложение знает об одном кадре.
 * @param sharpness резкость 0..1 (см. [LumaMetrics.sharpness])
 * @param glare области бликов (см. [LumaMetrics.glare])
 * @param brightness средняя яркость 0..1
 */
data class FrameInput(
    val timestampMs: Long,
    val sharpness: Float,
    val glare: List<NBox> = emptyList(),
    val brightness: Float = 0.5f,
    val lines: List<OcrLine>,
)

enum class Quality { GREEN, YELLOW, RED }

enum class Hint(val ru: String) {
    NONE(""),
    LOW_LIGHT("Темно — включите вспышку"),
    HOLD_STEADY("Задержитесь на полсекунды"),
    SLOW_DOWN("Ведите медленнее"),
    GO_BACK_UP("Пропуск! Вернитесь выше"),
    REDUCE_GLARE("Блик — наклоните лист или смените свет"),
}

/** Итог по строке для AR-подсветки. committed — строка попала в память листа. */
data class LineVerdict(val line: OcrLine, val quality: Quality, val committed: Boolean)

data class FrameVerdict(
    val lines: List<LineVerdict>,
    val hint: Hint,
    /** true — нужно коротко вибрировать (проскочили фрагмент). */
    val vibrate: Boolean,
    /** Скорость ведения в высотах кадра за секунду (0 — неизвестна). */
    val scrollSpeed: Float,
    val skipped: Boolean,
    val sheetNumber: Int,
)

/** Все пороги в одном месте — подбираются на реальных съёмках. */
data class Tuning(
    val greenConfidence: Float = 0.85f,
    val yellowConfidence: Float = 0.60f,
    /** Строка «подтверждена», если прочитана ≥ confirmSeen раз с уверенностью ≥ confirmConfidence. */
    val confirmSeen: Int = 2,
    val confirmConfidence: Float = 0.70f,
    val severeBlur: Float = 0.25f,
    val mildBlur: Float = 0.45f,
    /** Строка у самого края кадра считается обрезанной. */
    val edgeMargin: Float = 0.012f,
    val glareOverlap: Float = 0.25f,
    /** Скорость в высотах кадра/сек: выше slow — предупреждаем, выше tooFast — строки не фиксируем. */
    val slowSpeed: Float = 0.5f,
    val tooFastSpeed: Float = 1.0f,
    val minLinesForSkip: Int = 3,
    val lowLight: Float = 0.25f,
    val vibrateCooldownMs: Long = 1500L,
)
