package com.screenwordpulse.core

import kotlin.math.abs
import kotlin.math.roundToInt

data class SheetReport(val number: Int, val label: String, val labelLoc: String, val lines: Int,
                       val chars: Int, val confirmedChars: Int, val yellowLines: Int, val gaps: Int)

data class CaptureReport(val sheets: List<SheetReport>) {
    val totalChars: Int get() = sheets.sumOf { it.chars }
    val percentClear: Int
        get() = if (totalChars == 0) 0 else (100.0 * sheets.sumOf { it.confirmedChars } / totalChars).roundToInt()

    /** «96% текста чётко, 1 жёлтый фрагмент на Листе 2; возможен пропуск на Листе 1». */
    fun summaryRu(): String {
        if (totalChars == 0) return "Текст пока не распознан"
        val parts = ArrayList<String>()
        parts.add("$percentClear% текста чётко")
        for (s in sheets) if (s.yellowLines > 0)
            parts.add("${s.yellowLines} ${plural(s.yellowLines, "жёлтый фрагмент", "жёлтых фрагмента", "жёлтых фрагментов")} на ${s.labelLoc}")
        val s = parts.joinToString(", ")
        val gaps = sheets.filter { it.gaps > 0 }
        return if (gaps.isEmpty()) s else s + "; возможен пропуск: " + gaps.joinToString(", ") { it.label }
    }

    private fun plural(n: Int, one: String, few: String, many: String): String {
        val m100 = n % 100
        val m10 = n % 10
        return when {
            m100 in 11..14 -> many
            m10 == 1 -> one
            m10 in 2..4 -> few
            else -> many
        }
    }
}

/**
 * Сессия съёмки: принимает кадры (результат OCR + метрики), решает, какого цвета каждая строка,
 * копит текст листов без дублей и умеет отдать его ИИ в формате [Лист N].
 * Потоково-небезопасна: вызывайте из одного потока или под lock.
 */
class ScanSession(val tuning: Tuning = Tuning(), private val kind: SourceKind = SourceKind.CAMERA) {
    val sheets: MutableList<Sheet> = mutableListOf(Sheet(1, kind))
    var current: Sheet = sheets[0]; private set

    private class Prev(val sigs: List<Sig>, val ys: List<Float>, val ts: Long)
    private var prev: Prev? = null
    private var lastVibrate = Long.MIN_VALUE / 2

    fun onFrame(f: FrameInput): FrameVerdict {
        val t = tuning
        val usable = f.lines.filter { !TextNorm.isNoise(it.text) }.sortedBy { it.box.top }
        val pre = usable.map { QualityAnalyzer.classifyLine(it, f, t) }
        val sigs = usable.map { Sig.of(it.text) }

        val speed = estimateSpeed(sigs, usable, f.timestampMs)
        val fast = speed >= t.tooFastSpeed
        val slow = speed >= t.slowSpeed
        val severeBlur = f.sharpness < t.severeBlur
        val frameBad = fast || severeBlur

        val cands = usable.mapIndexed { i, l ->
            Cand(TextNorm.clean(l.text), l.confidence, sigs[i],
                commit = !frameBad && pre[i].quality != Quality.RED && !pre[i].partial)
        }
        val sheetHadLines = current.lines.isNotEmpty()
        val res = SheetStitcher.merge(current, cands)
        val skipped = sheetHadLines && !severeBlur && usable.size >= t.minLinesForSkip && res.matched == 0

        val verdicts = usable.mapIndexed { i, l ->
            val sl = res.lineFor[i]
            val committed = cands[i].commit && sl != null
            var q = pre[i].quality
            if (committed && q != Quality.RED) q = if (sl!!.isConfirmed(t)) Quality.GREEN else Quality.YELLOW
            if (fast) q = Quality.RED else if (slow && q == Quality.GREEN) q = Quality.YELLOW
            if (skipped) q = Quality.RED
            LineVerdict(l, q, committed)
        }

        val hint = when {
            skipped -> Hint.GO_BACK_UP
            fast -> Hint.SLOW_DOWN
            f.brightness < t.lowLight -> Hint.LOW_LIGHT
            severeBlur -> Hint.HOLD_STEADY
            pre.any { it.glare } -> Hint.REDUCE_GLARE
            slow -> Hint.SLOW_DOWN
            verdicts.any { it.quality == Quality.YELLOW } -> Hint.HOLD_STEADY
            else -> Hint.NONE
        }
        val vibrate = skipped && f.timestampMs - lastVibrate >= t.vibrateCooldownMs
        if (vibrate) lastVibrate = f.timestampMs

        prev = if (usable.isEmpty()) prev else Prev(sigs, usable.map { it.box.centerY }, f.timestampMs)
        return FrameVerdict(verdicts, hint, vibrate, speed, skipped, current.number)
    }

    /** Скорость ведения: медианный сдвиг общих строк между соседними кадрами / время. */
    private fun estimateSpeed(sigs: List<Sig>, lines: List<OcrLine>, ts: Long): Float {
        val p = prev ?: return 0f
        val dt = (ts - p.ts) / 1000f
        if (dt < 0.03f || dt > 2.5f) return 0f
        val al = LineAligner.align(p.sigs, sigs)
        if (al.count < 2) return 0f
        val d = ArrayList<Float>()
        for (j in sigs.indices) {
            val i = al.matchForB[j]
            if (i >= 0) d.add(lines[j].box.centerY - p.ys[i])
        }
        d.sort()
        return abs(d[d.size / 2]) / dt
    }

    // ---- управление листами ----

    fun nextSheet(): Sheet {
        current = Sheet(sheets.last().number + 1, kind).also { sheets.add(it) }
        prev = null
        return current
    }

    /** Переснять один проблемный лист: очищаем его и снова делаем текущим. */
    fun rescanSheet(number: Int): Sheet? {
        val s = sheets.firstOrNull { it.number == number } ?: return null
        s.lines.clear()
        current = s
        prev = null
        return s
    }

    /** Досканировать лист (ничего не стирая), например при открытии прошлой сессии. */
    fun selectSheet(number: Int): Sheet? {
        val s = sheets.firstOrNull { it.number == number } ?: return null
        current = s
        prev = null
        return s
    }

    fun reset() {
        sheets.clear()
        current = Sheet(1, kind).also { sheets.add(it) }
        prev = null
    }

    // ---- результаты ----

    fun report(): CaptureReport = CaptureReport(sheets.map { s ->
        var chars = 0; var ok = 0; var yellow = 0; var gaps = 0
        for (l in s.lines) {
            val c = l.sig.key.length
            chars += c
            if (l.isConfirmed(tuning)) ok += c else yellow++
            if (l.gapBefore) gaps++
        }
        SheetReport(s.number, s.label, s.labelLoc, s.lines.size, chars, ok, yellow, gaps)
    })

    fun clauseWarnings(): List<ClauseWarning> = ClauseChecker.check(sheets)

    fun search(query: String): List<SearchHit> = OfflineSearch.search(sheets, query)

    /**
     * Текст для ИИ. Метки совпадают с системным промптом (docs/AI_System_Prompt.txt):
     * «[Лист N]», «[нечёткий фрагмент]», «[пропущена часть страницы]».
     */
    fun exportForAi(): String {
        val sb = StringBuilder()
        for (s in sheets) {
            if (s.lines.isEmpty()) continue
            sb.append('[').append(s.label).append("]\n")
            for (l in s.lines) {
                if (l.gapBefore) sb.append("[пропущена часть страницы]\n")
                sb.append(l.text)
                when {
                    l.needsCheck -> sb.append(" [нечёткий фрагмент: цифры читались по-разному]")
                    l.confidence < tuning.confirmConfidence -> sb.append(" [нечёткий фрагмент]")
                }
                sb.append('\n')
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }
}

/** Оценка качества одной строки в одном кадре (до учёта памяти листа). */
internal data class LinePre(val quality: Quality, val partial: Boolean, val glare: Boolean)

internal object QualityAnalyzer {
    fun classifyLine(line: OcrLine, f: FrameInput, t: Tuning): LinePre {
        var q = when {
            line.confidence >= t.greenConfidence -> Quality.GREEN
            line.confidence >= t.yellowConfidence -> Quality.YELLOW
            else -> Quality.RED
        }
        val b = line.box
        val partial = b.top < t.edgeMargin || b.bottom > 1f - t.edgeMargin
        if (partial && q == Quality.GREEN) q = Quality.YELLOW
        val area = b.area
        var overlap = 0f
        for (g in f.glare) overlap += b.intersectionArea(g)
        val glare = area > 0f && overlap / area > t.glareOverlap
        if (glare) q = Quality.RED
        if (f.sharpness < t.severeBlur) q = Quality.RED
        else if (f.sharpness < t.mildBlur && q == Quality.GREEN) q = Quality.YELLOW
        return LinePre(q, partial, glare)
    }
}
