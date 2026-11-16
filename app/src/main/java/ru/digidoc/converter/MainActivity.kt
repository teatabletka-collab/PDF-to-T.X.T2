package ru.digidoc.converter

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.googlecode.tesseract.android.TessBaseAPI
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val pickPdf = 101
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var ocrButton: Button

    // Если текст короче этого — считаем, что текстового слоя почти нет
    private val minUsefulTextLength = 80

    private var lastUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 32)
            gravity = Gravity.TOP
        }

        val title = TextView(this).apply {
            text = "DigiDoc Converter"
            textSize = 26f
            setTextColor(Color.parseColor("#1A1A1A"))
        }

        val subtitle = TextView(this).apply {
            text = "PDF → TXT (текст + OCR для сканов)"
            textSize = 15f
            setPadding(0, 8, 0, 28)
            setTextColor(Color.parseColor("#555555"))
        }

        val pdfButton = Button(this).apply {
            text = "Выбрать PDF"
            setOnClickListener { choosePdf() }
        }

        ocrButton = Button(this).apply {
            text = "Принудительно OCR (скан)"
            isEnabled = false
            setOnClickListener {
                lastUri?.let { convertPdf(it, forceOcr = true) }
            }
        }

        status = TextView(this).apply {
            text = "Сначала пробуем текстовый слой.\nЕсли его мало — автоматически запускается OCR (русский + английский)."
            textSize = 14f
            setPadding(0, 24, 0, 12)
            setTextColor(Color.parseColor("#333333"))
        }

        progress = ProgressBar(this).apply {
            visibility = View.GONE
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(pdfButton)
        layout.addView(ocrButton)
        layout.addView(status)
        layout.addView(progress)

        setContentView(layout)
        checkTessData()
    }

    private fun choosePdf() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        startActivityForResult(intent, pickPdf)
    }

    @Deprecated("Deprecated in Android API; kept for broad compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickPdf && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                lastUri = uri
                ocrButton.isEnabled = true
                convertPdf(uri, forceOcr = false)
            }
        }
    }

    private fun convertPdf(uri: Uri, forceOcr: Boolean) {
        progress.visibility = View.VISIBLE
        status.text = if (forceOcr) "Запускаю OCR…" else "Обрабатываю PDF…"

        worker.execute {
            try {
                val temp = File(cacheDir, "input.pdf")
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Не удалось открыть файл" }
                    FileOutputStream(temp).use { output -> input.copyTo(output) }
                }

                val document = PDDocument.load(temp)
                val pageCount = document.numberOfPages

                val textFromLayer = try {
                    PDFTextStripper().getText(document).trim()
                } catch (e: Exception) {
                    ""
                }

                val needOcr = forceOcr || textFromLayer.length < minUsefulTextLength

                val finalText = if (!needOcr) {
                    textFromLayer
                } else {
                    runOnUiThread {
                        status.text = "Текстовый слой слабый ($pageCount стр.). Запускаю OCR…"
                    }
                    performOcr(document, pageCount)
                }

                document.close()

                val outputDir = getExternalFilesDir(null) ?: filesDir
                val output = File(outputDir, "converted_${System.currentTimeMillis()}.txt")
                output.writeText(
                    finalText.ifBlank {
                        "Не удалось извлечь текст.\nПроверьте, что в assets/tessdata лежат eng.traineddata и rus.traineddata."
                    },
                    Charsets.UTF_8
                )

                runOnUiThread {
                    progress.visibility = View.GONE
                    val mode = if (needOcr) "OCR" else "текстовый слой"
                    status.text = "Готово ($mode)!\nФайл:\n${output.absolutePath}\n\nСимволов: ${finalText.length}"
                    Toast.makeText(this, "TXT сохранён", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    status.text = "Ошибка: ${e.localizedMessage}"
                    Toast.makeText(this, "Ошибка обработки", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun performOcr(document: PDDocument, pageCount: Int): String {
        val tessDataPath = prepareTessData()
        if (tessDataPath == null) {
            return "OCR недоступен: не найдены языковые модели.\n" +
                    "Положите eng.traineddata и rus.traineddata в:\n" +
                    "app/src/main/assets/tessdata/"
        }

        val tess = TessBaseAPI()
        
        try {
            val initOk = tess.init(tessDataPath, "rus+eng")
            if (!initOk) {
                return "Не удалось инициализировать Tesseract. Проверьте файлы traineddata."
            }

            tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO

            val renderer = PDFRenderer(document)
            val result = StringBuilder()
            val scale = 2.0f // ≈ 144 DPI

            for (i in 0 until pageCount) {
                runOnUiThread {
                    status.text = "OCR: страница ${i + 1} из $pageCount…"
                }

                val bitmap: Bitmap = try {
                    renderer.renderImage(i, scale, ImageType.RGB)
                } catch (e: Exception) {
                    result.append("\n[Ошибка рендера страницы ${i + 1}]\n")
                    continue
                }

                try {
                    tess.setImage(bitmap)
                    val pageText = tess.utF8Text?.trim().orEmpty()
                    if (pageText.isNotEmpty()) {
                        result.append("----- Страница ${i + 1} -----\n")
                        result.append(pageText)
                        result.append("\n\n")
                    }
                } finally {
                    // ✅ ПРАВИЛЬНО: освобождаем память Bitmap сразу после использования
                    bitmap.recycle()
                }
            }

            return result.toString().trim()
            
        } finally {
            // ✅ ПРАВИЛЬНО: ГАРАНТИРОВАННО освобождаем нативную память Tesseract, 
            // даже если внутри блока try произошла какая-либо ошибка.
            tess.end()
        }
    }

    private fun prepareTessData(): String? {
        val tessDir = File(filesDir, "tessdata")
        if (!tessDir.exists()) tessDir.mkdirs()

        val required = listOf("eng.traineddata", "rus.traineddata")
        var allPresent = true

        for (name in required) {
            val target = File(tessDir, name)
            if (!target.exists() || target.length() < 1000) {
                try {
                    assets.open("tessdata/$name").use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                } catch (e: Exception) {
                    allPresent = false
                }
            }
        }

        return if (allPresent && required.all { File(tessDir, it).exists() }) {
            filesDir.absolutePath
        } else null
    }

    private fun checkTessData() {
        val hasEng = try {
            assets.open("tessdata/eng.traineddata").close()
            true
        } catch (e: Exception) {
            false
        }
        val hasRus = try {
            assets.open("tessdata/rus.traineddata").close()
            true
        } catch (e: Exception) {
            false
        }

        if (!hasEng || !hasRus) {
            status.text = "⚠ OCR пока не готов.\n\n" +
                    "Нужно добавить языковые модели:\n" +
                    "1. Скачай eng.traineddata и rus.traineddata\n" +
                    "2. Положи их в папку:\n" +
                    "   app/src/main/assets/tessdata/\n\n" +
                    "После этого пересобери APK."
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}