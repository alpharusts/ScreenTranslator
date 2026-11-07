package ru.screentranslator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

private const val HOWTO = "Как пользоваться:\n" +
    "• Нажмите «Запустить» и разрешите съёмку всего экрана.\n" +
    "• Откройте любое приложение или игру и нажмите синюю кнопку «译».\n" +
    "• Перевод появится поверх текста. Коснитесь экрана — он исчезнет.\n" +
    "• Кнопку можно перетаскивать пальцем.\n" +
    "• Выключить — кнопка «Остановить» в уведомлении или здесь."

class MainActivity : ComponentActivity() {

    private lateinit var engine: TranslationEngine
    private lateinit var statusText: TextView
    private lateinit var modelsText: TextView
    private var downloading = false

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, OverlayService::class.java)
                .setAction(OverlayService.ACTION_START)
                .putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(OverlayService.EXTRA_RESULT_DATA, data)
            startForegroundService(intent)
            moveTaskToBack(true) // сворачиваемся, чтобы сразу открыть нужное приложение
        } else {
            toast("Без разрешения на снимок экрана перевод не работает")
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = TranslationEngine()
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        engine.close()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun label(value: String, sizeSp: Float = 15f, bold: Boolean = false) = TextView(this).apply {
            text = value
            textSize = sizeSp
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
        }

        fun button(value: String, onClick: () -> Unit) = Button(this).apply {
            text = value
            isAllCaps = false
            setOnClickListener { onClick() }
        }

        root.addView(label("Китайский и английский → русский", 20f, bold = true))
        root.addView(label("Бесплатно, без подписок и рекламы. Распознавание и перевод работают прямо на телефоне, интернет нужен только один раз — чтобы скачать языковые модели."))
        root.addView(button("1. Разрешить показ поверх других приложений") { openOverlaySettings() })
        root.addView(button("2. Скачать языковые модели (~60 МБ, один раз)") { downloadModels() })
        modelsText = label("")
        root.addView(modelsText)
        root.addView(button("3. Запустить переводчик") { startTranslator() })
        root.addView(button("Остановить") {
            stopService(Intent(this, OverlayService::class.java))
            root.postDelayed({ refreshStatus() }, 300)
        })
        statusText = label("")
        root.addView(statusText)
        root.addView(label(HOWTO, 14f))
        return ScrollView(this).apply { addView(root) }
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun downloadModels() {
        if (downloading) return
        downloading = true
        modelsText.text = "⏳ Скачиваю модели… Это может занять пару минут."
        lifecycleScope.launch {
            try {
                engine.downloadModels()
                modelsText.text = "✓ Языковые модели скачаны"
            } catch (e: Exception) {
                modelsText.text = "✗ Не удалось скачать: ${e.message ?: "ошибка"}. Проверьте интернет."
            } finally {
                downloading = false
            }
        }
    }

    private fun startTranslator() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Сначала разрешите показ поверх других приложений")
            openOverlaySettings()
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // Сразу просим весь экран, а не одно приложение
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        captureLauncher.launch(intent)
    }

    private fun refreshStatus() {
        val overlay = Settings.canDrawOverlays(this)
        statusText.text =
            (if (overlay) "✓ Показ поверх приложений разрешён" else "✗ Нет разрешения на показ поверх приложений") +
                "\n" +
                (if (OverlayService.isRunning) "▶ Переводчик работает" else "■ Переводчик выключен")
        if (!downloading) {
            lifecycleScope.launch {
                val ok = runCatching { engine.modelsDownloaded() }.getOrDefault(false)
                if (!downloading) {
                    modelsText.text = if (ok) "✓ Языковые модели скачаны" else "✗ Модели ещё не скачаны (нужен интернет)"
                }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
