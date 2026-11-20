package com.screenwordpulse

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.screenwordpulse.camera.FrameAnalyzer
import com.screenwordpulse.core.FrameVerdict
import com.screenwordpulse.core.Hint
import com.screenwordpulse.core.ScanSession
import com.screenwordpulse.ocr.TesseractOcrEngine
import com.screenwordpulse.ui.OverlayView
import java.util.concurrent.Executors

/**
 * Этап 1: камера + подсветка качества + склейка листов без дублей.
 * Интерфейс собран кодом (без XML), чтобы весь экран читался в одном файле.
 */
class MainActivity : AppCompatActivity() {
    private val session = ScanSession()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private lateinit var ocr: TesseractOcrEngine
    private lateinit var preview: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var status: TextView
    private lateinit var hintView: TextView
    private var camera: Camera? = null
    private var torchOn = false

    private val askCamera = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startCamera() else status.text = "Нужен доступ к камере" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        ocr = TesseractOcrEngine(this)
        if (!ocr.ready) {
            status.text = "Нет языковых данных OCR: положите rus/kaz/eng.traineddata в assets/tessdata (см. README)"
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val prev = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor, FrameAnalyzer(ocr, session, ::onVerdict))
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, prev, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onVerdict(v: FrameVerdict, aspect: Float) {
        overlay.show(v, aspect)
        if (v.vibrate) vibrate()
        runOnUiThread {
            val rep = synchronized(session) { session.report().summaryRu() }
            status.text = "${synchronized(session) { session.current.label }}  •  $rep"
            hintView.text = v.hint.ru
            hintView.setBackgroundColor(when (v.hint) {
                Hint.NONE -> Color.TRANSPARENT
                Hint.GO_BACK_UP -> 0xCCE74C3C.toInt()
                else -> 0xCCF1C40F.toInt()
            })
        }
    }

    private fun vibrate() {
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        vib.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // ---------- интерфейс ----------

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        overlay = OverlayView(this)
        root.addView(preview, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        status = TextView(this).apply { setTextColor(Color.WHITE); setBackgroundColor(0x99000000.toInt()); setPadding(24, 24, 24, 24); textSize = 14f }
        hintView = TextView(this).apply { setTextColor(Color.BLACK); textSize = 18f; gravity = Gravity.CENTER; setPadding(24, 16, 24, 16) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(status); addView(hintView) }
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(8, 8, 8, 8) }
        fun btn(t: String, a: () -> Unit) = Button(this).apply { text = t; setOnClickListener { a() } }.also { bar.addView(it) }
        btn("Следующий лист") { synchronized(session) { session.nextSheet() }; overlay.clear() }
        btn("Переснять лист") { synchronized(session) { session.rescanSheet(session.current.number) }; overlay.clear() }
        btn("Вспышка") { torchOn = !torchOn; camera?.cameraControl?.enableTorch(torchOn) }
        btn("Поиск") { showSearch() }
        btn("Скопировать для ИИ") { copyForAi() }
        btn("Новая сессия") { synchronized(session) { session.reset() }; overlay.clear() }
        val scroll = HorizontalScrollView(this).apply { setBackgroundColor(0x99000000.toInt()); addView(bar) }
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun copyForAi() {
        val text = synchronized(session) { session.exportForAi() }
        if (text.isEmpty()) { Toast.makeText(this, "Пока нечего копировать", Toast.LENGTH_SHORT).show(); return }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ScreenWordPulse", text))
        val warn = synchronized(session) { session.clauseWarnings() }
        Toast.makeText(this, if (warn.isEmpty()) "Скопировано" else "Скопировано. " + warn.first().message, Toast.LENGTH_LONG).show()
    }

    private fun showSearch() {
        val input = EditText(this).apply { hint = "слово, сумма, дата, телефон, п. 4.3" }
        AlertDialog.Builder(this).setTitle("Поиск по всем листам").setView(input)
            .setPositiveButton("Найти") { _, _ ->
                val hits = synchronized(session) { session.search(input.text.toString()) }
                val msg = if (hits.isEmpty()) "Ничего не найдено" else hits.take(20).joinToString("\n\n") { "[${it.sheetLabel}] ${it.text}" }
                AlertDialog.Builder(this).setTitle("Найдено: ${hits.size}").setMessage(msg).setPositiveButton("OK", null).show()
            }.setNegativeButton("Отмена", null).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        if (::ocr.isInitialized) ocr.close()
    }
}
