package com.seashell.gechuang.systemui

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import com.seashell.gechuang.logging.GechuangLog

/**
 * The one full-size output used when a side slot is promoted. It is deliberately
 * a separate TextureView from the sidebar preview: a VirtualDisplay's output
 * surface can be swapped between the two without changing the display size, so
 * the app inside never goes through an Activity relaunch.
 *
 * Side slots are preview-only; only this active surface receives injected touch.
 */
class ActiveSlotView(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {
    interface Listener {
        fun onActiveSurfaceReady(view: ActiveSlotView)
        fun onActiveSurfaceDestroyed(view: ActiveSlotView)
        fun onActiveTouch(view: ActiveSlotView, event: MotionEvent)
        fun onExitRequested(view: ActiveSlotView)
    }

    val textureView = TextureView(context)
    private val exitView = android.widget.TextView(context).apply {
        text = "返回主格"
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.argb(180, 18, 30, 36))
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.argb(120, 255, 255, 255))
        }
        isClickable = true
        setOnClickListener { listener?.onExitRequested(this@ActiveSlotView) }
        visibility = View.GONE
    }

    private var listener: Listener? = null
    private var canvasWidth = 0
    private var canvasHeight = 0
    private var contentRect = Rect()
    private var displayId: Int? = null
    private var active = false
    private var surfaceReady = false
    private var windowMode = false

    init {
        setBackgroundColor(Color.BLACK)
        alpha = 0f
        visibility = View.VISIBLE
        addView(
            textureView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.LEFT),
        )
        textureView.surfaceTextureListener = this
        isClickable = true
        addView(
            exitView,
            LayoutParams(dp(104), dp(38), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(10)
                marginEnd = dp(10)
            },
        )
        setOnTouchListener { _, event ->
            if (!active) return@setOnTouchListener false
            true.also { listener?.onActiveTouch(this, event) }
        }
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    val isActive: Boolean get() = active
    val activeDisplayId: Int? get() = displayId
    val surfaceTexture: SurfaceTexture? get() = textureView.surfaceTexture
    val isSurfaceReady: Boolean get() = surfaceReady && textureView.isAvailable

    fun showFor(displayId: Int, content: Rect, canvasWidth: Int, canvasHeight: Int) {
        this.displayId = displayId
        updateContent(content, canvasWidth, canvasHeight)
        active = true
        visibility = View.VISIBLE
        exitView.visibility = View.VISIBLE
        applyLayout()
        animate().cancel()
        alpha = 1f
    }

    /** The active surface has its own content-sized SystemUI window, so it never
     * covers the top strip or the sidebar and never steals their touches. */
    fun showInWindow(displayId: Int, canvasWidth: Int, canvasHeight: Int) {
        this.displayId = displayId
        windowMode = true
        this.canvasWidth = canvasWidth.coerceAtLeast(1)
        this.canvasHeight = canvasHeight.coerceAtLeast(1)
        active = true
        visibility = View.VISIBLE
        exitView.visibility = View.VISIBLE
        bringToFront()
        applyLayout()
        animate().cancel()
        // Switching is an output-routing operation, not a panel transition. A
        // hard cut avoids the 120 ms fade that made an instant switch feel laggy.
        alpha = 1f
    }

    fun hideImmediately() {
        active = false
        exitView.visibility = View.GONE
        animate().cancel()
        alpha = 0f
        visibility = View.INVISIBLE
    }

    fun updateWindowContent(canvasWidth: Int, canvasHeight: Int) {
        this.canvasWidth = canvasWidth.coerceAtLeast(1)
        this.canvasHeight = canvasHeight.coerceAtLeast(1)
        if (active && windowMode) applyLayout()
    }

    fun updateContent(content: Rect, canvasWidth: Int, canvasHeight: Int) {
        this.contentRect = Rect(content)
        this.canvasWidth = canvasWidth.coerceAtLeast(1)
        this.canvasHeight = canvasHeight.coerceAtLeast(1)
        if (active) applyLayout()
    }

    fun hide() {
        if (!active) return
        active = false
        exitView.visibility = View.GONE
        animate().cancel()
        alpha = 0f
    }

    fun releaseSurfaceBinding() {
        // The TextureView owns its SurfaceTexture. Nothing to release here; the
        // VirtualDisplay owner detaches its own Surface wrapper.
        surfaceReady = false
    }

    fun refreshTransform() {
        applyLayout()
    }

    private fun applyLayout() {
        if (windowMode) {
            textureView.layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
                Gravity.TOP or Gravity.LEFT,
            )
            textureView.surfaceTexture?.setDefaultBufferSize(canvasWidth, canvasHeight)
            textureView.post { applyTransform() }
            return
        }
        if (contentRect.isEmpty || canvasWidth <= 0 || canvasHeight <= 0) return
        val params = textureView.layoutParams as? LayoutParams ?: LayoutParams(
            contentRect.width(), contentRect.height(), Gravity.TOP or Gravity.LEFT,
        )
        params.width = contentRect.width().coerceAtLeast(1)
        params.height = contentRect.height().coerceAtLeast(1)
        params.leftMargin = contentRect.left
        params.topMargin = contentRect.top
        textureView.layoutParams = params
        textureView.surfaceTexture?.setDefaultBufferSize(canvasWidth, canvasHeight)
        textureView.post { applyTransform() }
    }

    private fun applyTransform() {
        val textureWidth = textureView.width
        val textureHeight = textureView.height
        if (textureWidth <= 0 || textureHeight <= 0 || canvasWidth <= 0 || canvasHeight <= 0) return
        if (windowMode) {
            // In window mode the TextureView is MATCH_PARENT and the buffer is
            // already stretched to the view. Scaling again would leave a black
            // strip on the right/bottom (it made Settings look 813 px wide).
            textureView.setTransform(Matrix())
            GechuangLog.info(
                "active_slot",
                "Window layout container=${width}x${height} texture=${textureWidth}x${textureHeight} canvas=${canvasWidth}x${canvasHeight}",
            )
            return
        }
        val matrix = Matrix().apply {
            setScale(textureWidth.toFloat() / canvasWidth, textureHeight.toFloat() / canvasHeight)
        }
        textureView.setTransform(matrix)
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        surfaceReady = true
        texture.setDefaultBufferSize(canvasWidth.coerceAtLeast(1), canvasHeight.coerceAtLeast(1))
        applyTransform()
        GechuangLog.info(
            "active_slot",
            "Surface ready displayId=$displayId size=${width}x${height} canvas=${canvasWidth}x${canvasHeight}",
        )
        listener?.onActiveSurfaceReady(this)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        texture.setDefaultBufferSize(canvasWidth.coerceAtLeast(1), canvasHeight.coerceAtLeast(1))
        applyTransform()
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        surfaceReady = false
        GechuangLog.info("active_slot", "Surface destroyed displayId=$displayId")
        listener?.onActiveSurfaceDestroyed(this)
        // The VirtualDisplay is intentionally kept alive by its owner.
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
