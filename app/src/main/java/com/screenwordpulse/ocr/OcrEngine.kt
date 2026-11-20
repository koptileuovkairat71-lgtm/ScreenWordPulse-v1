package com.screenwordpulse.ocr

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import com.screenwordpulse.core.NBox
import com.screenwordpulse.core.OcrLine
import java.io.File

/** Любой OCR-движок: на входе кадр, на выходе строки с уверенностью и рамкой (0..1). */
interface OcrEngine {
    fun recognize(bitmap: Bitmap): List<OcrLine>
    fun close()
}

/**
 * Tesseract (rus+kaz+eng) через tess-two.
 * Файлы rus/kaz/eng.traineddata (tessdata_fast) кладутся в app/src/main/assets/tessdata/.
 */
class TesseractOcrEngine(context: Context, languages: String = "rus+kaz+eng") : OcrEngine {
    private val api = TessBaseAPI()
    val ready: Boolean

    init {
        val tessDir = File(context.filesDir, "tessdata").apply { mkdirs() }
        val names = context.assets.list("tessdata") ?: emptyArray()
        for (name in names) {
            val out = File(tessDir, name)
            if (!out.exists()) {
                context.assets.open("tessdata/$name").use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        ready = names.isNotEmpty() && api.init(tessDir.absolutePath, languages)
        if (ready) {
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        }
    }

    override fun recognize(bitmap: Bitmap): List<OcrLine> {
        if (!ready) return emptyList()
        api.setImage(bitmap)
        api.getHOCRText(0)
        val it = api.resultIterator ?: return emptyList()
        val w = bitmap.width.toFloat().coerceAtLeast(1f)
        val h = bitmap.height.toFloat().coerceAtLeast(1f)
        val out = ArrayList<OcrLine>()
        val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
        try {
            it.begin()
            do {
                val text = it.getUTF8Text(level)?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    val r = it.getBoundingRect(level)
                    val conf = (it.confidence(level) / 100f).coerceIn(0f, 1f)
                    out.add(
                        OcrLine(
                            text = text,
                            confidence = conf,
                            box = NBox(r.left / w, r.top / h, r.right / w, r.bottom / h)
                        )
                    )
                }
            } while (it.next(level))
        } finally {
            it.delete()
        }
        return out
    }

    override fun close() {
        api.end()
    }
}
