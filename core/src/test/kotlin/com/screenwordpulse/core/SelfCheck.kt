package com.screenwordpulse.core

import kotlin.system.exitProcess

private var failures = 0
private var checks = 0

private fun check(name: String, ok: Boolean, extra: () -> String = { "" }) {
    checks++
    if (ok) println("  ok   $name") else { failures++; println("  FAIL $name  ${extra()}") }
}

private fun section(s: String) = println("\n== $s")

fun main() {
    val doc = Sim.DOC

    section("Склейка: проход сверху вниз с шумом OCR")
    run {
        val starts = (0..18 step 2).toList()
        val over = Sim.noisyOverrides(doc, starts).toMutableMap()
        over[2 to 6] = Sim.Over(doc[6].replace("12 500", "12 600"), 0.70f)
        val s = ScanSession()
        var ts = 0L
        for ((f, st) in starts.withIndex()) { s.onFrame(Sim.frame(doc, st, ts, over = over, frameIdx = f)); ts += 1500 }
        s.onFrame(Sim.frame(doc, 18, ts))                      // «задержались» на последнем блоке
        val got = s.current.lines.map { it.text }
        check("ровно ${doc.size} строк, без дублей", got.size == doc.size) { "got ${got.size}" }
        check("порядок и текст совпадают с оригиналом", got == doc) {
            got.zip(doc).firstOrNull { it.first != it.second }?.toString() ?: "размер"
        }
        val digitLine = s.current.lines[6]
        check("цифры: побеждает прочтение с большинством голосов (12 500)", digitLine.text.contains("12 500"))
        check("цифры: расхождение помечено needsCheck", digitLine.needsCheck)
        val exp = s.exportForAi()
        check("экспорт: метка [Лист 1]", exp.startsWith("[Лист 1]"))
        check("экспорт: спорные цифры помечены", exp.contains("12 500 тенге. [нечёткий фрагмент: цифры читались по-разному]"))
        check("экспорт: пропусков нет", !exp.contains("пропущена часть страницы"))
        check("поиск «12500» находит сумму", s.search("12500").any { it.text.contains("12 500") })
        check("поиск телефона 87011234567", s.search("87011234567").any { it.text.contains("701 123 45 67") })
        check("поиск слова «неустойки»", s.search("неустойки").size == 1)
        check("поиск пункта 4.3", s.search("4.3").any { it.text.startsWith("4.3.") })
        check("нумерация пунктов цела — предупреждений нет", s.clauseWarnings().isEmpty()) { s.clauseWarnings().toString() }
        println("  отчёт: " + s.report().summaryRu())
    }

    section("Пропуск при быстром движении: красный, вибрация, возврат закрывает дыру")
    run {
        val s = ScanSession()
        s.onFrame(Sim.frame(doc, 0, 0)); s.onFrame(Sim.frame(doc, 2, 1500))
        val v = s.onFrame(Sim.frame(doc, 14, 3000))
        check("пропуск обнаружен", v.skipped)
        check("подсказка «вернитесь выше»", v.hint == Hint.GO_BACK_UP)
        check("вибросигнал", v.vibrate)
        check("все строки красные", v.lines.all { it.quality == Quality.RED })
        val v2 = s.onFrame(Sim.frame(doc, 14, 3200))
        check("второй кадр сразу: вибрация не спамит (cooldown)", !v2.vibrate)
        check("до возврата в тексте помечена дыра", s.exportForAi().contains("[пропущена часть страницы]"))
        var ts = 5000L
        for (st in listOf(6, 8, 10, 12, 14)) { s.onFrame(Sim.frame(doc, st, ts)); ts += 1500 }
        val got = s.current.lines.map { it.text }
        check("после возврата дыра закрыта", !s.exportForAi().contains("пропущена часть страницы"))
        check("и порядок верный (строки 0..19)", got == doc.subList(0, 20)) { "size=${got.size}" }
    }

    section("Цвета: жёлтый → зелёный после повторного прочтения")
    run {
        val s = ScanSession()
        val ov = mapOf((0 to 3) to Sim.Over(doc[3], 0.72f), (1 to 3) to Sim.Over(doc[3], 0.72f))
        val a = s.onFrame(Sim.frame(doc, 0, 0, over = ov, frameIdx = 0))
        val qa = a.lines[3].quality
        val b = s.onFrame(Sim.frame(doc, 0, 1500, over = ov, frameIdx = 1))
        check("первое чтение 0.72 — жёлтое", qa == Quality.YELLOW)
        check("второе чтение — зелёное (подтверждена)", b.lines[3].quality == Quality.GREEN)
        check("остальные уверенные — зелёные сразу", a.lines.filterIndexed { i, _ -> i != 3 }.all { it.quality == Quality.GREEN })
    }

    section("Смаз, блик, скорость, темнота")
    run {
        val s = ScanSession()
        val blur = s.onFrame(Sim.frame(doc, 0, 0, sharp = 0.1f))
        check("смаз: все красные", blur.lines.all { it.quality == Quality.RED })
        check("смаз: ничего не сохранено", s.current.lines.isEmpty())
        check("смаз: подсказка «задержитесь»", blur.hint == Hint.HOLD_STEADY)

        val glareBox = NBox(0f, 0f, 1f, 0.34f)               // накрывает 2 верхние строки
        val g = s.onFrame(Sim.frame(doc, 0, 1000, glare = listOf(glareBox)))
        check("блик: строки под бликом красные", g.lines.take(2).all { it.quality == Quality.RED })
        check("блик: строки ниже — зелёные", g.lines.drop(2).all { it.quality == Quality.GREEN })
        check("блик: подсказка", g.hint == Hint.REDUCE_GLARE)
        check("блик: красные строки не сохранены", s.current.lines.size == 4)

        val s2 = ScanSession()
        s2.onFrame(Sim.frame(doc, 0, 0))
        val fast = s2.onFrame(Sim.frame(doc, 3, 200))        // 0.5 высоты кадра за 0.2 с = 2.5/с
        check("быстро: скорость > порога", fast.scrollSpeed >= 1.0f) { "speed=${fast.scrollSpeed}" }
        check("быстро: красные + «медленнее»", fast.lines.all { it.quality == Quality.RED } && fast.hint == Hint.SLOW_DOWN)
        check("быстро: новые строки не фиксируются", s2.current.lines.size == 6)

        val s3 = ScanSession()
        s3.onFrame(Sim.frame(doc, 0, 0))
        val slow = s3.onFrame(Sim.frame(doc, 2, 1000))       // 0.33/с — нормально
        check("нормальный темп: зелёные, без подсказки", slow.lines.all { it.quality == Quality.GREEN } && slow.hint == Hint.NONE) { "${slow.hint} ${Sim.verdictRow(slow)}" }
        val dark = ScanSession().onFrame(Sim.frame(doc, 0, 0, brightness = 0.1f))
        check("темно: подсказка про вспышку", dark.hint == Hint.LOW_LIGHT)
    }

    section("Нумерация пунктов")
    run {
        val d2 = doc.filterIndexed { i, _ -> i != 15 }        // выкинули 4.2 (между 4.1 и 4.3)
        val s = ScanSession()
        var ts = 0L
        for (st in (0..17 step 2)) { s.onFrame(Sim.frame(d2, st, ts)); ts += 1500 }
        val w = s.clauseWarnings()
        check("пропуск п. 4.2 найден", w.any { it.message.contains("4.2") }) { w.toString() }
    }

    section("Сопоставление строк")
    check("«o» латиницей = «о» кириллицей", LineMatcher.compare(Sig.of("Договор подряда"), Sig.of("Дoговор пoдряда")) != null)
    check("п. 4.1 ≠ п. 4.2 (короткие, цифры разные)", LineMatcher.compare(Sig.of("п. 4.1"), Sig.of("п. 4.2")) == null)
    val cm = LineMatcher.compare(Sig.of("Срок 30 календарных дней"), Sig.of("Срок 60 календарных дней"))
    check("30 vs 60 дней: склеены, но помечены конфликтом цифр", cm != null && cm.conflict)
    check("разные пункты не склеиваются", LineMatcher.compare(Sig.of(doc[3]), Sig.of(doc[4])) == null)
    check("обрезанное прочтение = та же строка", LineMatcher.compare(Sig.of(doc[7]), Sig.of(doc[7].take(40)))?.frameIsSub == true)
    check("шум отфильтрован", TextNorm.isNoise("_ _ _ _ _") && TextNorm.isNoise(" . ") && !TextNorm.isNoise("1.1. Текст"))

    section("Метрики кадра")
    run {
        val w = 200; val h = 300
        val sharp = ByteArray(w * h) { i -> if ((i % w) / 2 % 2 == 0) 20 else (235).toByte() }
        val blurred = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0; var n = 0
            for (dx in -4..3) { val xx = x + dx; if (xx in 0 until w) { s += sharp[y * w + xx].toInt() and 0xFF; n++ } }
            blurred[y * w + x] = (s / n).toByte()
        }
        val a = LumaMetrics.sharpness(Luma(sharp, w, h))
        val b = LumaMetrics.sharpness(Luma(blurred, w, h))
        check("резкость: чёткий > смазанного", a > 0.6f && b < 0.25f) { "sharp=$a blurred=$b" }
        val img = ByteArray(w * h) { i -> if (i / w < 60) 255.toByte() else 120 }   // верх выбелен
        val gl = LumaMetrics.glare(Luma(img, w, h))
        check("блик найден только в выбеленной зоне", gl.isNotEmpty() && gl.all { it.bottom <= 0.25f + 1e-3f }) { gl.size.toString() }
        check("яркость", LumaMetrics.brightness(Luma(img, w, h)) in 0.4f..0.6f)
        val r = Luma(byteArrayOf(1, 2, 3, 4, 5, 6), 2, 3).rotated(90)
        check("поворот 90° по часовой", r.w == 3 && r.h == 2 && r.data.toList() == listOf<Byte>(5, 3, 1, 6, 4, 2))
        val r2 = Luma(byteArrayOf(1, 2, 3, 4, 5, 6), 2, 3).rotated(270)
        check("поворот 270°", r2.data.toList() == listOf<Byte>(2, 4, 6, 1, 3, 5))
    }

    section("Листы")
    run {
        val s = ScanSession()
        s.onFrame(Sim.frame(doc, 0, 0))
        val sh = s.nextSheet()
        s.onFrame(Sim.frame(doc, 6, 2000))
        check("второй лист начат отдельно", sh.number == 2 && s.sheets[0].lines.size == 6 && s.sheets[1].lines.size == 6)
        val exp = s.exportForAi()
        check("экспорт содержит [Лист 1] и [Лист 2]", exp.contains("[Лист 1]") && exp.contains("[Лист 2]"))
        s.rescanSheet(1)
        check("пересъёмка очищает только один лист", s.sheets[0].lines.isEmpty() && s.sheets[1].lines.size == 6)
        s.reset()
        check("новая сессия", s.sheets.size == 1 && s.current.lines.isEmpty())
    }

    println("\nПроверок: $checks, провалено: $failures")
    if (failures > 0) exitProcess(1)
}
