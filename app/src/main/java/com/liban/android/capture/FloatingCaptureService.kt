package com.liban.android.capture

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.liban.android.LibanApplication
import com.liban.android.MainActivity
import com.liban.android.R
import com.liban.android.agent.AnalysisBus
import com.liban.android.agent.LowConfidenceException
import com.liban.android.model.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import kotlin.math.abs

class FloatingCaptureService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var bubbleView: View? = null
    private var resultView: View? = null
    private var analysisJob: Job? = null
    private var cleaningUp = false
    private var serviceDestroyed = false
    private var projectionGeneration = 0L

    private val graph get() = (application as LibanApplication).graph

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                if (resultCode == Activity.RESULT_OK && resultData != null && Settings.canDrawOverlays(this)) {
                    startProjection(resultCode, resultData)
                } else {
                    AnalysisBus.update(AnalysisState.Error("识屏授权无效，请回到理伴重新开启"))
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startProjection(resultCode: Int, data: Intent) {
        val generation = ++projectionGeneration
        cleanupProjection(stopProjection = true)
        cleaningUp = false
        val bounds = windowManager.currentWindowMetrics.bounds
        val width = bounds.width().coerceAtLeast(1)
        val height = bounds.height().coerceAtLeast(1)
        val density = resources.displayMetrics.densityDpi
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)

        val manager = getSystemService(MediaProjectionManager::class.java)
        val activeProjection = manager.getMediaProjection(resultCode, data) ?: run {
            cleanupProjection(stopProjection = false)
            AnalysisBus.update(AnalysisState.Error("系统没有返回有效的录屏会话，请重新授权"))
            stopSelf()
            return
        }
        projection = activeProjection
        activeProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post {
                    if (serviceDestroyed || generation != projectionGeneration) return@post
                    cleanupProjection(stopProjection = false)
                    AnalysisBus.update(AnalysisState.Error("识屏会话已停止，请回到理伴重新授权"))
                    showOpenAppOverlay("识屏已停止", "点击回到理伴重新授权")
                }
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                handler.post { resizeCapture(width, height, generation) }
            }
        }, handler)
        virtualDisplay = activeProjection.createVirtualDisplay(
            "LibanScreenCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            handler,
        )
        AnalysisBus.update(AnalysisState.Idle)
        showBubble()
    }

    private fun resizeCapture(width: Int, height: Int, generation: Long) {
        if (serviceDestroyed || generation != projectionGeneration || width <= 0 || height <= 0) return
        val display = virtualDisplay ?: return
        val replacement = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val previous = imageReader
        imageReader = replacement
        display.resize(width, height, resources.displayMetrics.densityDpi)
        display.surface = replacement.surface
        previous?.close()
    }

    private fun analyzeCurrentFrame() {
        if (analysisJob?.isActive == true || projection == null) return
        analysisJob = serviceScope.launch {
            var bitmap: Bitmap? = null
            var jpeg: ByteArray? = null
            try {
                AnalysisBus.update(AnalysisState.Capturing)
                removeOverlayViews()
                delay(40) // Two display frames at 60Hz so the overlay is absent from capture.
                bitmap = withContext(Dispatchers.Default) { acquireBitmap() }
                if (bitmap == null || isNearlyBlack(bitmap)) {
                    AnalysisBus.update(AnalysisState.NeedsCorrection(null, "未取得有效画面，请手动输入商品信息"))
                    showOpenAppOverlay("无法识别画面", "点击打开理伴手动输入")
                    return@launch
                }
                AnalysisBus.update(AnalysisState.Analyzing)
                showAnalyzingOverlay()
                jpeg = withContext(Dispatchers.Default) { compressForUpload(bitmap) }
                bitmap.recycle()
                bitmap = null
                val (scene, decision) = graph.orchestrator.analyze(jpeg)
                AnalysisBus.update(AnalysisState.Result(scene, decision))
                showResultOverlay(scene, decision)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (error is LowConfidenceException) {
                    AnalysisBus.update(AnalysisState.NeedsCorrection(error.scene, error.message ?: "识别置信度低"))
                    showOpenAppOverlay("请确认商品信息", "点击打开理伴进行修正")
                } else {
                    AnalysisBus.update(AnalysisState.Error(error.message ?: "分析失败"))
                    showOpenAppOverlay("分析失败", "点击打开理伴重试")
                }
            } finally {
                bitmap?.recycle()
                jpeg?.fill(0)
            }
        }
    }

    private suspend fun acquireBitmap(): Bitmap? {
        repeat(20) {
            val image = imageReader?.acquireLatestImage()
            if (image != null) return image.use(::imageToBitmap)
            delay(50)
        }
        return null
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val padded = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        if (cropped !== padded) padded.recycle()
        return cropped
    }

    private fun compressForUpload(source: Bitmap): ByteArray {
        val longest = maxOf(source.width, source.height)
        val scaled = if (longest > 1440) {
            val scale = 1440.0 / longest
            Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true)
        } else source
        return ByteArrayOutputStream().use { output ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, output)
            if (scaled !== source) scaled.recycle()
            output.toByteArray()
        }
    }

    private fun isNearlyBlack(bitmap: Bitmap): Boolean {
        var dark = 0
        var sampled = 0
        val stepX = (bitmap.width / 40).coerceAtLeast(1)
        val stepY = (bitmap.height / 40).coerceAtLeast(1)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                if (Color.red(color) < 12 && Color.green(color) < 12 && Color.blue(color) < 12) dark++
                sampled++
                x += stepX
            }
            y += stepY
        }
        return sampled > 0 && dark.toDouble() / sampled >= 0.98
    }

    private fun showBubble() {
        removeOverlayViews()
        if (!Settings.canDrawOverlays(this) || projection == null) return
        val button = TextView(this).apply {
            text = "理伴\n一下"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 13f
            background = rounded(Color.rgb(103, 80, 164), 56f)
            elevation = dp(8).toFloat()
        }
        val params = overlayParams(dp(68), dp(68), Gravity.END or Gravity.CENTER_VERTICAL).apply { x = dp(12) }
        attachDragAndClick(button, params) { analyzeCurrentFrame() }
        runCatching { windowManager.addView(button, params); bubbleView = button }
            .onFailure { AnalysisBus.update(AnalysisState.Error("无法显示悬浮球：${it.message}")) }
    }

    private fun showAnalyzingOverlay() {
        removeOverlayViews()
        val text = TextView(this).apply {
            this.text = "理伴正在分析…"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = rounded(Color.rgb(70, 70, 78), 24f)
        }
        val params = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { x = dp(12) }
        runCatching { windowManager.addView(text, params); resultView = text }
    }

    private fun showResultOverlay(scene: SceneContext, decision: DecisionResult) {
        removeOverlayViews()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))
            background = rounded(Color.WHITE, 22f, Color.rgb(220, 215, 230))
            elevation = dp(12).toFloat()
        }
        container.addView(label(decision.display.title, 20f, true, riskColor(decision.riskLevel)))
        container.addView(label("${scene.product.name} · ${Money(scene.price.currentCents).yuanText()}", 15f, true))
        container.addView(label(decision.display.summary, 14f))
        decision.display.keyPoints.take(4).forEach { container.addView(label("• $it", 13f)) }
        if (decision.sourceMode != SourceMode.LIVE) {
            container.addView(label(if (decision.sourceMode == SourceMode.FALLBACK) "演示回退数据" else "部分实时数据", 12f, true, Color.rgb(170, 90, 0)))
        }
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(actionButton("还是买") { feedback(decision, UserAction.PURCHASE) })
            addView(actionButton("等等") { feedback(decision, UserAction.DELAY) })
            addView(actionButton("放弃") { feedback(decision, UserAction.CANCEL) })
        })
        val params = overlayParams(dp(340), WindowManager.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        runCatching { windowManager.addView(container, params); resultView = container }
            .onFailure {
                AnalysisBus.update(AnalysisState.Error("悬浮结果卡显示失败，请回到应用查看"))
                showOpenAppOverlay("结果已生成", "点击回到理伴查看")
            }
    }

    private fun feedback(decision: DecisionResult, action: UserAction) {
        serviceScope.launch(Dispatchers.IO) {
            val recorded = graph.repository.recordFeedback(decision.decisionId, action, decision.delayHours)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@FloatingCaptureService, if (recorded) "已记录" else "该决策已反馈", Toast.LENGTH_SHORT).show()
                showBubble()
            }
        }
    }

    private fun showOpenAppOverlay(title: String, subtitle: String) {
        removeOverlayViews()
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = rounded(Color.WHITE, 20f, Color.LTGRAY)
            addView(label(title, 16f, true))
            addView(label(subtitle, 13f))
            setOnClickListener { startActivity(Intent(this@FloatingCaptureService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        val params = overlayParams(dp(300), WindowManager.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        runCatching { windowManager.addView(view, params); resultView = view }
    }

    private fun attachDragAndClick(view: View, params: WindowManager.LayoutParams, click: () -> Unit) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = event.rawX; touchY = event.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (touchX - event.rawX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(event.rawX - touchX) < dp(8) && abs(event.rawY - touchY) < dp(8)) click()
                    true
                }
                else -> false
            }
        }
    }

    private fun overlayParams(width: Int, height: Int, gravity: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { this.gravity = gravity }

    private fun label(text: String, size: Float, bold: Boolean = false, color: Int = Color.DKGRAY) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        setPadding(0, dp(3), 0, dp(3))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun actionButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 12f
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun riskColor(risk: RiskLevel): Int = when (risk) {
        RiskLevel.LOW -> Color.rgb(35, 125, 70)
        RiskLevel.MEDIUM -> Color.rgb(190, 105, 0)
        RiskLevel.HIGH -> Color.rgb(180, 40, 40)
    }

    private fun removeOverlayViews() {
        bubbleView?.let { runCatching { windowManager.removeViewImmediate(it) } }
        resultView?.let { runCatching { windowManager.removeViewImmediate(it) } }
        bubbleView = null
        resultView = null
    }

    private fun cleanupProjection(stopProjection: Boolean) {
        if (cleaningUp) return
        cleaningUp = true
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        val active = projection
        projection = null
        if (stopProjection) runCatching { active?.stop() }
        cleaningUp = false
    }

    override fun onDestroy() {
        serviceDestroyed = true
        projectionGeneration++
        analysisJob?.cancel()
        serviceScope.cancel()
        removeOverlayViews()
        cleanupProjection(stopProjection = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.capture_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingCaptureService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.capture_channel))
            .setContentText(getString(R.string.capture_notification))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_START = "com.liban.android.action.START_CAPTURE"
        const val ACTION_STOP = "com.liban.android.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "liban_capture"
        private const val NOTIFICATION_ID = 1101
    }
}
