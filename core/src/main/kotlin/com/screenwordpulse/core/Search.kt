package com.screenwordpulse.core

data class SearchHit(val sheetLabel: String, val sheetNumber: Int, val lineIndex: Int, val text: String)

/** Офлайн-поиск по слову, сумме, дате, телефону или номеру пункта — по всем листам сразу. */
object OfflineSearch {
    private val numberToken = Regex("\\d[\\d \\u00A0.,]*\\d|\\d")
    private val numericQuery = Regex("^[\\d\\s+()\\-.,]+$")

    fun search(sheets: List<Sheet>, query: String): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val hits = ArrayList<SearchHit>()
        val numeric = numericQuery.matches(q) && q.any { it.isDigit() }
        val qDigits = TextNorm.digits(q)
        val qKey = TextNorm.key(q)
        for (s in sheets) for ((i, l) in s.lines.withIndex()) {
            val ok = if (numeric) matchNumber(l.text, q, qDigits) else qKey.isNotEmpty() && l.sig.key.contains(qKey)
            if (ok) hits.add(SearchHit(s.label, s.number, i, l.text))
        }
        return hits
    }

    private fun matchNumber(line: String, q: String, qDigits: String): Boolean {
        if (qDigits.length >= 10) {                       // телефон: сравниваем последние 10 цифр
            return TextNorm.digits(line).contains(qDigits.takeLast(10))
        }
        // Сумма/дата/номер пункта: «12500» находит «12 500», но не «112 500».
        if (numberToken.findAll(line).any { TextNorm.digits(it.value) == qDigits }) return true
        // Номер пункта и дата вида 4.3 / 12.05.2026 — по точной подстроке без пробелов.
        return line.replace(" ", "").replace(" ", "").contains(q.replace(" ", ""))
    }
}

data class ClauseWarning(val sheetNumber: Int, val message: String)

/** Предупреждение, если нумерация пунктов оборвалась (4.1, 4.2, 4.4 → пропущен 4.3). */
object ClauseChecker {
    private val multi = Regex("^\\s*(\\d{1,2}(?:\\.\\d{1,2})+)\\.?\\s+\\S")
    private val single = Regex("^\\s*(\\d{1,2})[.)]\\s+\\S")

    fun check(sheets: List<Sheet>): List<ClauseWarning> {
        val last = HashMap<String, Int>()          // «родитель» → последний номер
        val lastLabel = HashMap<String, String>()
        val out = ArrayList<ClauseWarning>()
        for (s in sheets) for (l in s.lines) {
            val num = (multi.find(l.text) ?: single.find(l.text))?.groupValues?.get(1) ?: continue
            val parts = num.split('.')
            val parent = parts.dropLast(1).joinToString(".")
            val n = parts.last().toIntOrNull() ?: continue
            val prev = last[parent]
            if (prev != null && n > prev + 1 && n - prev - 1 <= 3) {
                val missing = (prev + 1 until n).joinToString(", ") { (if (parent.isEmpty()) "" else "$parent.") + it }
                out.add(ClauseWarning(s.number, "Возможно, пропущен п. $missing (между п. ${lastLabel[parent]} и п. $num, ${s.label})"))
            }
            last[parent] = n
            lastLabel[parent] = num
        }
        return out
    }
}
