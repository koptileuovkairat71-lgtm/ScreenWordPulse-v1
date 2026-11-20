package com.screenwordpulse.core

/** Пример: имитация съёмки договора. Запуск: ./build-core.sh demo  или  gradle :core:demo */
fun main() {
    val doc = Sim.DOC
    val starts = (0..18 step 2).toList()
    val over = Sim.noisyOverrides(doc, starts).toMutableMap()
    over[2 to 6] = Sim.Over(doc[6].replace("12 500", "12 600"), 0.70f)

    val s = ScanSession()
    println("Камера ведётся по листу сверху вниз. 🟩 строка зафиксирована, 🟨 задержитесь, 🟥 не прочитано\n")
    var ts = 0L
    for ((f, st) in starts.withIndex()) {
        val v = s.onFrame(Sim.frame(doc, st, ts, over = over, frameIdx = f))
        println("кадр %2d  строки %2d–%2d  %s  %s".format(f + 1, st + 1, st + 6, Sim.verdictRow(v), v.hint.ru))
        ts += 1500
    }
    val last = s.onFrame(Sim.frame(doc, 18, ts))   // дошли до конца и «задержались» — OCR дочитывает спорные строки
    println("кадр 11  строки 19–24  %s  %s".format(Sim.verdictRow(last), last.hint.ru))

    println("\n--- Теперь пользователь резко дёрнул камеру: перескочили через блок ---")
    val s2 = ScanSession()
    ts = 0
    for (st in listOf(0, 2, 14)) {
        val v = s2.onFrame(Sim.frame(doc, st, ts)); ts += 1500
        println("кадр  строки %2d–%2d  %s  %s%s".format(st + 1, st + 6, Sim.verdictRow(v), v.hint.ru, if (v.vibrate) "   📳 вибрация" else ""))
    }

    println("\n--- Итог по первой съёмке ---")
    println(s.report().summaryRu())
    println("\nТекст для ИИ:\n")
    println(s.exportForAi())
}
