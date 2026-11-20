package com.screenwordpulse.core

enum class SourceKind { CAMERA, SCREEN }

/**
 * Строка в памяти листа. Хранит все варианты прочтения, различающиеся цифрами, и голосует:
 * цифры в договоре нельзя «усреднять», поэтому расхождение помечается needsCheck.
 */
class SheetLine internal constructor(initialText: String, initialConf: Float) {
    private class Variant(var votes: Int, var bestText: String, var bestConf: Float)

    private val variants = LinkedHashMap<String, Variant>()

    var text: String = ""; private set
    var confidence: Float = 0f; private set
    var seen: Int = 1; private set
    var needsCheck: Boolean = false; private set
    var gapBefore: Boolean = false; internal set
    internal var sig: Sig = Sig("", ""); private set

    init {
        variants[TextNorm.digits(initialText)] = Variant(1, initialText, initialConf)
        recompute()
    }

    internal fun observe(newText: String, conf: Float, m: Match) {
        seen++
        if (m.frameIsSub) return                       // новое прочтение короче — ничего не теряем
        if (m.sheetIsSub) {                            // новое прочтение полнее — заменяем
            variants.clear()
            variants[TextNorm.digits(newText)] = Variant(1, newText, conf)
            recompute()
            return
        }
        val v = variants.getOrPut(TextNorm.digits(newText)) { Variant(0, newText, conf) }
        v.votes++
        if (conf >= v.bestConf) { v.bestConf = conf; v.bestText = newText }
        recompute()
    }

    private fun recompute() {
        val sorted = variants.values.sortedWith(compareByDescending<Variant> { it.votes }.thenByDescending { it.bestConf })
        val top = sorted[0]
        text = top.bestText
        confidence = top.bestConf
        needsCheck = sorted.size > 1 && sorted[0].votes - sorted[1].votes < 2
        sig = Sig.of(text)
    }

    /** Зелёная: уверенно прочитана (или подтверждена повторным прочтением), цифры не спорят. */
    fun isConfirmed(t: Tuning): Boolean =
        !needsCheck && (confidence >= t.greenConfidence || (seen >= t.confirmSeen && confidence >= t.confirmConfidence))

    /** Для пометки «[нечёткий фрагмент]» при передаче ИИ. */
    fun isUncertain(t: Tuning): Boolean = needsCheck || confidence < t.confirmConfidence
}

class Sheet(val number: Int, val kind: SourceKind = SourceKind.CAMERA) {
    val lines: MutableList<SheetLine> = mutableListOf()

    /** Метка для ИИ и для ссылок в ответе: «Лист 2» / «Экран: фрагмент 2». */
    val label: String get() = if (kind == SourceKind.CAMERA) "Лист $number" else "Экран: фрагмент $number"
    val labelLoc: String get() = if (kind == SourceKind.CAMERA) "Листе $number" else "экране (фрагмент $number)"
}

/** Кандидат на попадание в память листа из текущего кадра. commit=false — только для проверки перекрытия. */
internal class Cand(val text: String, val conf: Float, val sig: Sig, val commit: Boolean)

internal class MergeResult(val lineFor: Array<SheetLine?>, val matched: Int, val added: Int)

/** Склейка кадров: строки не дублируются, лучшее прочтение побеждает, порядок сохраняется. */
internal object SheetStitcher {
    fun merge(sheet: Sheet, cands: List<Cand>): MergeResult {
        val n = sheet.lines.size
        val m = cands.size
        val al = LineAligner.align(sheet.lines.map { it.sig }, cands.map { it.sig })
        val lineFor = arrayOfNulls<SheetLine>(m)

        var minS = Int.MAX_VALUE
        var maxS = -1
        for (j in 0 until m) {
            val s = al.matchForB[j]
            if (s < 0) continue
            val sl = sheet.lines[s]
            lineFor[j] = sl
            if (s < minS) minS = s
            if (s > maxS) maxS = s
            if (cands[j].commit) sl.observe(cands[j].text, cands[j].conf, al.matches[j]!!)
        }
        // Кадр «перекинул мост» через ранее помеченный пропуск — пропуска больше нет.
        if (al.count > 0) for (k in (minS + 1)..maxS) sheet.lines[k].gapBefore = false

        val inserts = ArrayList<Pair<Int, SheetLine>>()
        if (al.count > 0) {
            var pos = minS
            for (j in 0 until m) {
                if (al.matchForB[j] >= 0) pos = al.matchForB[j] + 1
                else if (cands[j].commit) {
                    val nl = SheetLine(cands[j].text, cands[j].conf)
                    inserts.add(pos to nl); lineFor[j] = nl
                }
            }
        } else {
            // Нет перекрытия с тем, что уже в памяти: если память не пуста — между ними пропуск.
            for (j in 0 until m) {
                if (!cands[j].commit) continue
                val nl = SheetLine(cands[j].text, cands[j].conf)
                if (n > 0 && inserts.isEmpty()) nl.gapBefore = true
                inserts.add(n to nl); lineFor[j] = nl
            }
        }
        if (inserts.isNotEmpty()) {
            val byPos = inserts.groupBy({ it.first }, { it.second })
            val out = ArrayList<SheetLine>(n + inserts.size)
            for (idx in 0..n) {
                byPos[idx]?.let { out.addAll(it) }
                if (idx < n) out.add(sheet.lines[idx])
            }
            sheet.lines.clear()
            sheet.lines.addAll(out)
        }
        return MergeResult(lineFor, al.count, inserts.size)
    }
}
