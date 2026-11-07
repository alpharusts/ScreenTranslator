package ru.screentranslator

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Служба держит плавающую кнопку поверх всех приложений.
 * Нажатие: снимок экрана → распознавание текста → перевод поверх оригинала.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_START = "ru.screentranslator.START"
        const val ACTION_STOP = "ru.screentranslator.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "translator"
        private const val NOTIFICATION_ID = 1
        private const val BUTTON_LABEL = "译"

        @Volatile
        var isRunning = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager
    private lateinit var engine: TranslationEngine

    // Захват экрана
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val imageLock = Any()
    private var latestImage: Image? = null
    private var screenW = 0
    private var screenH = 0
    private var screenDpi = 0

    // Окна поверх приложений
    private var button: TextView? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var resultView: TranslationOverlayView? = null
    private var busy = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            scope.launch {
                toast("Захват экрана остановлен. Запустите переводчик снова.")
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        engine = TranslationEngine()
        val channel = NotificationChannel(
            CHANNEL_ID, "Переводчик экрана", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        when (intent.action) {
            ACTION_STOP -> stopSelf()
            ACTION_START -> {
                startAsForeground() // на Android 14+ обязательно ДО getMediaProjection
                val data = intent.resultData()
                if (data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    startCapture(intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED), data)
                    showButton()
                    isRunning = true
                } catch (e: Exception) {
                    toast("Не удалось запустить захват экрана: ${e.message}")
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun Intent.resultData(): Intent? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else getParcelableExtra(EXTRA_RESULT_DATA)

    private fun startAsForeground() {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), flags
        )
        val openIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), flags
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Переводчик экрана включён")
            .setContentText("Нажмите плавающую кнопку «$BUTTON_LABEL», чтобы перевести экран")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as Icon?, "Остановить", stopIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------- Захват экрана ----------

    private fun startCapture(resultCode: Int, data: Intent) {
        releaseCapture()
        val thread = HandlerThread("screen-capture").apply { start() }
        val handler = Handler(thread.looper)
        captureThread = thread
        captureHandler = handler

        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = manager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("система не выдала доступ к экрану")
        // Android 14+: колбэк нужно зарегистрировать до createVirtualDisplay
        mp.registerCallback(projectionCallback, handler)
        projection = mp

        readScreenSize()
        val reader = createReader()
        virtualDisplay = mp.createVirtualDisplay(
            "ScreenTranslator", screenW, screenH, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
    }

    private fun readScreenSize() {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        screenDpi = metrics.densityDpi
    }

    /** Держим всегда один последний кадр экрана — по нажатию берём его мгновенно. */
    @SuppressLint("WrongConstant")
    private fun createReader(): ImageReader {
        val reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            val image = try { r.acquireLatestImage() } catch (e: Exception) { null }
            if (image != null) {
                synchronized(imageLock) {
                    latestImage?.close()
                    latestImage = image
                }
            }
        }, captureHandler)
        imageReader = reader
        return reader
    }

    private fun grabBitmap(): Bitmap? {
        synchronized(imageLock) {
            val image = latestImage ?: return null
            return try { imageToBitmap(image) } catch (e: Exception) { null }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val src = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val rowBytes = w * plane.pixelStride
        // Убираем выравнивание строк, которое добавляет видеодрайвер
        val packed = ByteBuffer.allocate(rowBytes * h)
        val row = ByteArray(rowBytes)
        for (y in 0 until h) {
            src.position(y * rowStride)
            src.get(row)
            packed.put(row)
        }
        packed.rewind()
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(packed) }
    }

    /** Поворот экрана: меняем размер виртуального дисплея под новую ориентацию. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val vd = virtualDisplay ?: return
        val oldW = screenW
        val oldH = screenH
        readScreenSize()
        if (oldW == screenW && oldH == screenH) return

        removeResult()
        val oldReader = imageReader
        val newReader = createReader()
        vd.resize(screenW, screenH, screenDpi)
        vd.surface = newReader.surface
        captureHandler?.post {
            oldReader?.setOnImageAvailableListener(null, null)
            synchronized(imageLock) {
                latestImage?.close()
                latestImage = null
            }
            oldReader?.close()
        }
        keepButtonOnScreen()
    }

    private fun releaseCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        projection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        projection = null
        imageReader?.setOnImageAvailableListener(null, null)
        synchronized(imageLock) {
            latestImage?.close()
            latestImage = null
        }
        imageReader?.close()
        imageReader = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
    }

    // ---------- Плавающая кнопка ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun showButton() {
        if (button != null) return
        val d = resources.displayMetrics.density
        val size = (56 * d).toInt()
        val view = TextView(this).apply {
            text = BUTTON_LABEL
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(230, 61, 90, 254))
                setStroke((2 * d).toInt(), Color.WHITE)
            }
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - size - (12 * d).toInt()
            y = screenH / 3
        }
        view.setOnTouchListener(DragOrTapListener(params))
        windowManager.addView(view, params)
        button = view
        buttonParams = params
    }

    /** Перетаскивание кнопки пальцем; короткое касание — перевод. */
    private inner class DragOrTapListener(
        private val params: WindowManager.LayoutParams
    ) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop
        private var startX = 0
        private var startY = 0
        private var downX = 0f
        private var downY = 0f
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        params.x = (startX + dx.toInt()).coerceIn(0, (screenW - params.width).coerceAtLeast(0))
                        params.y = (startY + dy.toInt()).coerceIn(0, (screenH - params.height).coerceAtLeast(0))
                        windowManager.updateViewLayout(v, params)
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragging) onTranslateClicked()
            }
            return true
        }
    }

    private fun keepButtonOnScreen() {
        val p = buttonParams ?: return
        val b = button ?: return
        p.x = p.x.coerceIn(0, (screenW - p.width).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (screenH - p.height).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(b, p) }
    }

    private fun setLoading(loading: Boolean) {
        button?.apply {
            text = if (loading) "…" else BUTTON_LABEL
            alpha = if (loading) 0.6f else 1f
        }
    }

    // ---------- Перевод ----------

    private fun onTranslateClicked() {
        if (busy) return
        val btn = button ?: return
        busy = true
        btn.visibility = View.INVISIBLE // прячем кнопку, чтобы она не попала в снимок
        scope.launch {
            try {
                delay(250)
                val bitmap = withContext(Dispatchers.Default) { grabBitmap() }
                btn.visibility = View.VISIBLE
                setLoading(true)
                if (bitmap == null) {
                    toast("Не удалось снять экран, попробуйте ещё раз")
                    return@launch
                }
                if (!engine.modelsDownloaded()) {
                    toast("Скачиваю языковые модели (только в первый раз)…")
                    engine.downloadModels()
                }
                val blocks = engine.translateScreen(bitmap)
                bitmap.recycle()
                if (blocks.isEmpty()) {
                    toast("Китайский или английский текст не найден")
                } else {
                    showResult(blocks)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Ошибка перевода: ${e.message ?: "неизвестно"}")
            } finally {
                btn.visibility = View.VISIBLE
                setLoading(false)
                busy = false
            }
        }
    }

    private fun showResult(blocks: List<TranslatedBlock>) {
        removeResult()
        val view = TranslationOverlayView(this, blocks) { removeResult() }
        val params = WindowManager.LayoutParams(
            screenW, screenH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windowManager.addView(view, params)
        resultView = view
    }

    private fun removeResult() {
        resultView?.let { runCatching { windowManager.removeView(it) } }
        resultView = null
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        removeResult()
        button?.let { runCatching { windowManager.removeView(it) } }
        button = null
        releaseCapture()
        engine.close()
        super.onDestroy()
    }
}
