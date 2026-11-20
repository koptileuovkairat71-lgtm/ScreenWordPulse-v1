package com.screenwordpulse.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object TextNorm {
    // Латинские двойники кириллицы: OCR часто путает «о» и «o». Используется только для сравнения.
    private val homoglyph = mapOf(
        'a' to 'а', 'c' to 'с', 'e' to 'е', 'o' to 'о', 'p' to 'р', 'x' to 'х',
        'y' to 'у', 'k' to 'к', 'm' to 'м', 't' to 'т', 'b' to 'в', 'h' to 'н',
    )
    private val spaces = Regex("\\s+")

    fun clean(s: String): String = s.replace(spaces, " ").trim()

    /** Ключ для сравнения: буквы и цифры в нижнем регистре, ё=е, латинские двойники → кириллица. */
    fun key(s: String): String {
        val sb = StringBuilder(s.length)
        for (raw in s.lowercase()) {
            var c = if (raw == 'ё') 'е' else raw
            c = homoglyph[c] ?: c
            if (c.isLetterOrDigit()) sb.append(c)
        }
        return sb.toString()
    }

    fun digits(s: String): String = s.filter { it.isDigit() }

    /** Мусорная строка: пусто, одна буква, линия «_____», точки-разделители. */
    fun isNoise(s: String): Boolean {
        val t = clean(s)
        if (t.isEmpty()) return true
        val alnum = t.count { it.isLetterOrDigit() }
        if (alnum < 2) return true
        return alnum.toFloat() / t.length < 0.5f
    }

    /** Расстояние Левенштейна с ранним выходом: вернёт max+1, если расстояние больше max. */
    fun distanceBounded(a: String, b: String, max: Int): Int {
        if (abs(a.length - b.length) > max) return max + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                val v = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val t = prev; prev = cur; cur = t
        }
        return min(prev[b.length], max + 1)
    }
}

/** Подпись строки для сопоставления. */
class Sig(val key: String, val digits: String) {
    companion object {
        fun of(text: String) = Sig(TextNorm.key(text), TextNorm.digits(text))
    }
}

/**
 * Результат сравнения двух строк. conflict — буквы совпали, а цифры нет (опасно: 12 500 vs 12 600).
 * sheetIsSub — строка в памяти обрезана, новая читалась полнее; frameIsSub — наоборот.
 */
class Match(val weight: Float, val conflict: Boolean, val sheetIsSub: Boolean, val frameIsSub: Boolean)

object LineMatcher {
    /** a — то, что уже в памяти, b — новая строка. null — это разные строки. */
    fun compare(a: Sig, b: Sig): Match? {
        val maxLen = max(a.key.length, b.key.length)
        val minLen = min(a.key.length, b.key.length)
        if (maxLen == 0) return null
        val conflict = a.digits != b.digits
        // На коротких строках с разными цифрами ("п. 4.1" / "п. 4.2") не склеиваем вообще.
        if (conflict && maxLen < 10) return null
        var allowed = when {
            maxLen < 4 -> 0
            maxLen < 8 -> 1
            else -> (maxLen * 0.2f).toInt()
        }
        if (conflict) allowed = min(allowed, max(1, (maxLen * 0.08f).toInt()))
        val d = TextNorm.distanceBounded(a.key, b.key, allowed)
        if (d <= allowed) {
            val sim = 1f - d.toFloat() / maxLen
            val w = sim * (1f + min(maxLen, 40) / 40f) * (if (conflict) 0.5f else 1f)
            return Match(w, conflict, sheetIsSub = false, frameIsSub = false)
        }
        // Одна и та же строка, но OCR в одном из кадров прочитал её частично.
        if (minLen >= 10 && minLen >= 0.4f * maxLen) {
            val aShorter = a.key.length < b.key.length
            val longer = if (aShorter) b.key else a.key
            val shorter = if (aShorter) a.key else b.key
            if (longer.contains(shorter)) {
                val w = 0.6f * (1f + min(minLen, 40) / 40f)
                return Match(w, false, sheetIsSub = aShorter, frameIsSub = !aShorter)
            }
        }
        return null
    }
}

/** Выравнивание двух последовательностей строк (как LCS, но с весами): порядок сохраняется. */
object LineAligner {
    class Result(val matchForB: IntArray, val matches: Array<Match?>, val count: Int)

    fun align(a: List<Sig>, b: List<Sig>): Result {
        val n = a.size
        val m = b.size
        val matchForB = IntArray(m) { -1 }
        val matches = arrayOfNulls<Match>(m)
        if (n == 0 || m == 0) return Result(matchForB, matches, 0)
        val w = Array(n) { arrayOfNulls<Match>(m) }
        val dp = Array(n + 1) { FloatArray(m + 1) }
        for (i in 1..n) {
            for (j in 1..m) {
                val mt = LineMatcher.compare(a[i - 1], b[j - 1])
                w[i - 1][j - 1] = mt
                var best = max(dp[i - 1][j], dp[i][j - 1])
                if (mt != null) best = max(best, dp[i - 1][j - 1] + mt.weight)
                dp[i][j] = best
            }
        }
        var i = n
        var j = m
        var count = 0
        while (i > 0 && j > 0) {
            val mt = w[i - 1][j - 1]
            if (mt != null && abs(dp[i][j] - (dp[i - 1][j - 1] + mt.weight)) < 1e-5f) {
                matchForB[j - 1] = i - 1
                matches[j - 1] = mt
                count++
                i--; j--
            } else if (dp[i - 1][j] >= dp[i][j - 1]) i-- else j--
        }
        return Result(matchForB, matches, count)
    }
}
