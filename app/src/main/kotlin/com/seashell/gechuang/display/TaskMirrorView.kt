package com.seashell.gechuang.display

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.ImageView
import com.seashell.gechuang.framework.TaskSurfaceCompat
import com.seashell.gechuang.logging.GechuangLog
import java.util.concurrent.Executors

/**
 * Side-slot preview of the main task.
 *
 * The main task stays on display 0. We try a compositor mirror first
 * (SurfaceControl.mirrorSurface), which is a layer reference rather than a
 * screenshot/encode/decode path. If MIUI blocks the hidden API, fall back to a
 * low-resolution, low-frame-rate task capture so the workspace still shows the
 * main task instead of the stale side app.
 */
class TaskMirrorView(context: Context) : FrameLayout(context) {
    private val surfaceView = SurfaceView(context).apply {
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderMediaOverlay(true)
        setZOrderOnTop(true)
        isClickable = false
        isFocusable = false
    }
    private val fallbackView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        setBackgroundColor(Color.BLACK)
        visibility = GONE
        isClickable = false
        isFocusable = false
    }
    private val handler = Handler(Looper.getMainLooper())
    private val captureExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "gechuang-mirror-preview").apply { isDaemon = true }
    }
    private var taskId: Int = -1
    private var sourceBounds: Rect = Rect()
    private var sourceScale: Float = 1f
    private var sourceTranslationX: Float = 0f
    private var sourceTranslationY: Float = 0f
    private var destWidth: Int = 0
    private var destHeight: Int = 0
    private var nativeMirror: Any? = null
    private var generation: Int = 0
    private var fallbackRunning = false
    private var surfaceReady = false
    var onTap: (() -> Unit)? = null

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = false
        setOnClickListener { onTap?.invoke() }
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        surfaceView.setOnClickListener { onTap?.invoke() }
        addView(fallbackView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                if (taskId >= 0) tryNativeMirror()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                detachNative()
            }
        })
        visibility = GONE
    }

    fun show(
        taskId: Int,
        sourceBounds: Rect,
        width: Int,
        height: Int,
        sourceScale: Float = 1f,
        sourceTranslationX: Float = 0f,
        sourceTranslationY: Float = 0f,
    ) {
        if (taskId < 0 || width <= 0 || height <= 0) return
        generation += 1
        this.taskId = taskId
        this.sourceBounds.set(sourceBounds)
        this.sourceScale = sourceScale
        this.sourceTranslationX = sourceTranslationX
        this.sourceTranslationY = sourceTranslationY
        destWidth = width
        destHeight = height
        visibility = VISIBLE
        surfaceView.visibility = VISIBLE
        fallbackView.visibility = GONE
        fallbackRunning = false
        if (surfaceReady && surfaceView.holder.surface.isValid) {
            tryNativeMirror()
        } else {
            // SurfaceView will call back. Keep the old side preview hidden
            // meanwhile so the user never sees the wrong app during the swap.
            postDelayed({ if (generation > 0 && taskId >= 0 && !surfaceReady) startFallback() }, 180L)
        }
        GechuangLog.info(
            "task_mirror",
            "Mirror requested task=$taskId source=$sourceBounds dest=${width}x$height",
        )
    }

    fun hide() {
        generation += 1
        taskId = -1
        fallbackRunning = false
        detachNative()
        fallbackView.visibility = GONE
        fallbackView.setImageDrawable(null)
        surfaceView.visibility = GONE
        visibility = GONE
        GechuangLog.info("task_mirror", "Mirror hidden")
    }

    private fun tryNativeMirror() {
        if (taskId < 0) return
        val result = TaskSurfaceCompat.mirrorTaskIntoSurface(
            taskId = taskId,
            surfaceView = surfaceView,
            destinationWidth = destWidth,
            destinationHeight = destHeight,
            sourceBounds = sourceBounds,
            sourceScale = sourceScale,
            sourceTranslationX = sourceTranslationX,
            sourceTranslationY = sourceTranslationY,
        )
        result.onSuccess { mirror ->
            nativeMirror = mirror
            fallbackView.visibility = GONE
            fallbackRunning = false
            GechuangLog.info("task_mirror", "Native mirror attached task=$taskId")
        }.onFailure { error ->
            GechuangLog.warn("task_mirror", "Native mirror unavailable task=$taskId; using capture fallback", error)
            startFallback()
        }
    }

    private fun detachNative() {
        val mirror = nativeMirror
        nativeMirror = null
        TaskSurfaceCompat.releaseTaskMirror(mirror)
    }

    private fun startFallback() {
        if (taskId < 0 || fallbackRunning) return
        fallbackRunning = true
        surfaceView.visibility = GONE
        fallbackView.visibility = VISIBLE
        fallbackView.setBackgroundColor(Color.BLACK)
        scheduleCapture(generation)
    }

    private fun scheduleCapture(token: Int) {
        if (!fallbackRunning || token != generation || taskId < 0) return
        val capturedTaskId = taskId
        val capturedBounds = Rect(sourceBounds)
        val capturedWidth = destWidth
        val capturedHeight = destHeight
        captureExecutor.execute {
            val bitmap = TaskSurfaceCompat.capturePreview(capturedTaskId, capturedBounds)
            if (bitmap == null) {
                handler.postDelayed({ scheduleCapture(token) }, FALLBACK_FRAME_MS)
                return@execute
            }
            val scaled = scaleForPreview(bitmap, capturedWidth, capturedHeight)
            handler.post {
                if (!fallbackRunning || token != generation || taskId != capturedTaskId) {
                    scaled.recycle()
                    return@post
                }
                fallbackView.setImageBitmap(scaled)
                handler.postDelayed({ scheduleCapture(token) }, FALLBACK_FRAME_MS)
            }
        }
    }

    private fun scaleForPreview(source: Bitmap, width: Int, height: Int): Bitmap {
        val maxWidth = minOf(width, FALLBACK_MAX_WIDTH)
        val maxHeight = minOf(height, FALLBACK_MAX_HEIGHT)
        if (source.width <= maxWidth && source.height <= maxHeight) return source
        val scale = minOf(maxWidth.toFloat() / source.width, maxHeight.toFloat() / source.height)
        val scaled = Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== source) source.recycle()
        return scaled
    }

    override fun onDetachedFromWindow() {
        hide()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val FALLBACK_FRAME_MS = 66L
        const val FALLBACK_MAX_WIDTH = 480
        const val FALLBACK_MAX_HEIGHT = 1067
    }
}
