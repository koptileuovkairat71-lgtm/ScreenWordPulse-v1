package com.screenwordpulse.core

/** Имитация съёмки: «окно» из size строк документа едет сверху вниз, OCR местами ошибается. */
object Sim {
    val DOC = listOf(
        "ДОГОВОР ПОДРЯДА № 17",
        "г. Алматы 12 мая 2026 года",
        "1. Предмет договора",
        "1.1. Подрядчик обязуется выполнить работы по установке кассового оборудования.",
        "1.2. Заказчик обязуется принять результат работ и оплатить его.",
        "2. Стоимость и порядок расчётов",
        "2.1. Сумма договора составляет 12 500 тенге.",
        "2.2. Оплата производится в течение 5 банковских дней после подписания акта.",
        "2.3. За просрочку оплаты начисляется пеня 0,1% за каждый день.",
        "3. Сроки выполнения работ",
        "3.1. Работы начинаются 1 июня 2026 года.",
        "3.2. Срок выполнения работ составляет 30 календарных дней.",
        "3.3. Срок может быть продлён по соглашению сторон.",
        "4. Ответственность сторон",
        "4.1. Стороны несут ответственность в соответствии с законодательством.",
        "4.2. Подрядчик не отвечает за задержку, вызванную действиями Заказчика.",
        "4.3. Уплата неустойки не освобождает от исполнения обязательств.",
        "5. Прочие условия",
        "5.1. Договор может быть расторгнут по письменному уведомлению за 14 дней.",
        "5.2. Споры разрешаются путём переговоров, а при их недостижении — в суде.",
        "5.3. Договор составлен в двух экземплярах, имеющих равную силу.",
        "6. Реквизиты сторон",
        "Подрядчик: ТОО «Альфа», БИН 123456789012",
        "Заказчик: ИП Сидоров, тел. +7 701 123 45 67",
    )

    class Over(val text: String, val conf: Float)

    /** frameIdx → (индекс строки документа → подмена прочтения). */
    fun frame(
        doc: List<String>, start: Int, ts: Long, size: Int = 6,
        over: Map<Pair<Int, Int>, Over> = emptyMap(), frameIdx: Int = 0,
        conf: Float = 0.92f, sharp: Float = 0.8f, glare: List<NBox> = emptyList(), brightness: Float = 0.6f,
    ): FrameInput {
        val lines = ArrayList<OcrLine>()
        for (k in 0 until size) {
            val i = start + k
            if (i >= doc.size) break
            val o = over[frameIdx to i]
            val top = (k + 0.2f) / size
            val bottom = (k + 0.8f) / size
            lines.add(OcrLine(o?.text ?: doc[i], o?.conf ?: conf, NBox(0.05f, top, 0.95f, bottom)))
        }
        return FrameInput(ts, sharp, glare, brightness, lines)
    }

    fun corrupt(s: String): String = if (s.length > 6) s.substring(0, 3) + s[4] + s[3] + s.substring(5) else s
    fun latinO(s: String): String = s.replaceFirst('о', 'o')

    /** Типичная съёмка с шумом: на позиции 2 — мусор (красный), на позиции 4 — «o» латиницей (жёлтый). */
    fun noisyOverrides(doc: List<String>, starts: List<Int>): Map<Pair<Int, Int>, Over> {
        val m = HashMap<Pair<Int, Int>, Over>()
        for ((f, s) in starts.withIndex()) {
            if (s + 2 < doc.size) m[f to s + 2] = Over(corrupt(doc[s + 2]), 0.55f)
            if (s + 4 < doc.size) m[f to s + 4] = Over(latinO(doc[s + 4]), 0.70f)
        }
        return m
    }

    fun verdictRow(v: FrameVerdict): String =
        v.lines.joinToString("") { when (it.quality) { Quality.GREEN -> "🟩"; Quality.YELLOW -> "🟨"; Quality.RED -> "🟥" } }
}
