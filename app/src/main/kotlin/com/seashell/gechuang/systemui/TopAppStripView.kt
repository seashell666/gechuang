package com.seashell.gechuang.systemui

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.seashell.gechuang.R
import com.seashell.gechuang.config.AppLanguage
import com.seashell.gechuang.config.WorkspaceConfigClient
import com.seashell.gechuang.logging.GechuangLog
import com.seashell.gechuang.workspace.SidebarSide
import com.seashell.gechuang.workspace.WorkspaceControlMetrics

/**
 * Compact top app strip.
 *
 * Only the app strip is visible. The side toggle, settings and exit buttons float
 * over the right edge of the strip, so the app icons can scroll underneath them.
 */
class TopAppStripView(
    context: Context,
    initialSidebarSide: SidebarSide,
    private val onAppClicked: (Intent) -> Unit,
    private val onSidebarSideRequested: (SidebarSide) -> Unit,
    private val onSettingsRequested: () -> Unit,
    private val onTasksRequested: () -> Unit,
    private val onAddAppRequested: () -> Unit,
    private val onExitRequested: () -> Unit,
    private val onFrostStrengthChanged: (Int) -> Unit,
    private val onAppDragStarting: (View, Intent) -> Boolean,
    private val onAppDragTouch: (View, MotionEvent) -> Boolean,
    @Suppress("UNUSED_PARAMETER") monitorSlots: () -> android.os.Bundle,
) : FrameLayout(context) {
    private val repository = TopAppRepository(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val appRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(4), 0)
    }
    private val appScroll = object : HorizontalScrollView(context) {
        private var downX = 0f
        private var downY = 0f
        private var previousX = 0f
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                downX = event.x
                downY = event.y
                previousX = event.x
            }
            val handled = super.dispatchTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_MOVE && !internalAppDragActive) {
                val dx = event.x - downX
                val dy = event.y - downY
                val direction = if (event.x < previousX) 1 else -1
                if (kotlin.math.abs(dx) > touchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy) &&
                    !canScrollHorizontally(direction)) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                previousX = event.x
            }
            return handled
        }
    }.apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        clipToPadding = true
        setPadding(0, 0, 0, 0)
        addView(appRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }
    private var sidebarSide = initialSidebarSide
    private val sideToggleButton = SideToggleButton(context).apply {
        side = sidebarSide
        contentDescription = sideDescription(sidebarSide)
        setOnClickListener {
            onSidebarSideRequested(if (sidebarSide == SidebarSide.LEFT) SidebarSide.RIGHT else SidebarSide.LEFT)
        }
    }
    private val exitButton = ExitButton(context).apply {
        contentDescription = AppLanguage.string(context, R.string.window_exit)
        setOnClickListener { onExitRequested() }
    }
    private val settingsButton = GearButton(context).apply {
        contentDescription = AppLanguage.string(context, R.string.open_settings)
        setOnClickListener { onSettingsRequested() }
    }
    private val tasksButton = TasksButton(context).apply {
        contentDescription = "最近任务"
        setOnClickListener { onTasksRequested() }
    }
    private val controls = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(3), 0, dp(3), 0)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(Color.argb(255, 8, 14, 18))
        }
        addView(sideToggleButton, LinearLayout.LayoutParams(dp(BUTTON_SIZE_DP), dp(BUTTON_SIZE_DP)))
        addView(tasksButton, LinearLayout.LayoutParams(dp(BUTTON_SIZE_DP), dp(BUTTON_SIZE_DP)))
        addView(settingsButton, LinearLayout.LayoutParams(dp(BUTTON_SIZE_DP), dp(BUTTON_SIZE_DP)))
        addView(exitButton.apply { scaleX = if (sidebarSide == SidebarSide.LEFT) -1f else 1f },
            LinearLayout.LayoutParams(dp(BUTTON_SIZE_DP), dp(BUTTON_SIZE_DP)))
    }
    private var loading = false
    private var refreshPending = false
    private var lastRefreshAt = 0L
    private var refreshGeneration = 0
    private var refreshRetryCount = 0
    private val refreshRetry = Runnable { refresh(force = true) }
    private var internalAppDragActive = false
    private var deferredApps: List<TopAppRepository.AppEntry>? = null
    private var controlsChangedListener: (() -> Unit)? = null
    private var videoActionListener: (() -> Unit)? = null
    private val dismissHint = TextView(context).apply {
        text = AppLanguage.string(context, R.string.window_drop_background)
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.argb(230, 28, 77, 90))
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        val rowTop = dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP)
        val rowParams = LayoutParams(
            dp(APP_TILE_WIDTH_DP * PINNED_APP_SLOTS + 8),
            dp(APP_TILE_HEIGHT_DP),
            Gravity.TOP,
        ).apply {
            topMargin = rowTop
        }
        addView(appScroll, rowParams)
        addView(controls, LayoutParams(LayoutParams.WRAP_CONTENT, dp(APP_TILE_HEIGHT_DP), Gravity.TOP or Gravity.END).apply {
            topMargin = rowTop
            rightMargin = dp(4)
        })
        addView(dismissHint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP)
        })
    }

    private val configChangedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == com.seashell.gechuang.config.WorkspaceConfigContract.ACTION_CHANGED ||
                intent.action == android.content.Intent.ACTION_LOCALE_CHANGED) {
                refreshRetryCount = 0
                refresh(force = true)
            }
        }
    }
    private var workspaceVisible = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        GechuangLog.info("top_apps", "Attached appStrip children=${appRow.childCount}")
        context.registerReceiver(configChangedReceiver,
            android.content.IntentFilter(com.seashell.gechuang.config.WorkspaceConfigContract.ACTION_CHANGED).apply {
                addAction(android.content.Intent.ACTION_LOCALE_CHANGED)
            },
            Context.RECEIVER_EXPORTED)
        if (appRow.childCount == 0) refresh(force = true)
        controlsChangedListener?.invoke()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refreshRetry)
        context.unregisterReceiver(configChangedReceiver)
        super.onDetachedFromWindow()
    }

    fun setWorkspaceVisible(visible: Boolean) {
        GechuangLog.info("top_apps", "Workspace visible=$visible children=${appRow.childCount}")
        if (visible && !workspaceVisible) refreshRetryCount = 0
        workspaceVisible = visible
        if (!visible) removeCallbacks(refreshRetry)
        else if (appRow.childCount == 0) refresh(force = true)
        controlsChangedListener?.invoke()
    }

    internal fun setMonitorControlsSelected(@Suppress("UNUSED_PARAMETER") selected: Boolean) = Unit

    internal fun setMonitorWallpaperLight(@Suppress("UNUSED_PARAMETER") light: Boolean) = Unit

    fun setVideoActionState(@Suppress("UNUSED_PARAMETER") canCapture: Boolean,
        @Suppress("UNUSED_PARAMETER") embedded: Boolean) = Unit

    fun setOnVideoActionRequested(listener: () -> Unit) {
        videoActionListener = listener
    }

    fun setOnControlsChanged(listener: () -> Unit) {
        controlsChangedListener = listener
    }

    fun setSidebarSide(side: SidebarSide) {
        sidebarSide = side
        sideToggleButton.side = side
        sideToggleButton.contentDescription = sideDescription(side)
        exitButton.scaleX = if (side == SidebarSide.LEFT) -1f else 1f
    }

    fun setDismissTargetHighlighted(highlighted: Boolean) {
        dismissHint.visibility = if (highlighted) VISIBLE else GONE
    }

    fun finishInternalAppDrag() {
        internalAppDragActive = false
        parent?.requestDisallowInterceptTouchEvent(false)
        deferredApps?.let { entries -> deferredApps = null; renderApps(entries) }
    }

    fun isInternalAppDragActive(): Boolean = internalAppDragActive

    fun canScrollHorizontallyAt(x: Float, y: Float, direction: Int): Boolean {
        val bounds = Rect(appScroll.scrollX, appScroll.scrollY,
            appScroll.scrollX + appScroll.width, appScroll.scrollY + appScroll.height)
        offsetDescendantRectToMyCoords(appScroll, bounds)
        return bounds.contains(x.toInt(), y.toInt()) && appScroll.canScrollHorizontally(direction)
    }

    /** True when the finger is on the app strip itself. Page paging must never
     * steal that gesture; the strip scrolls, and at its end it simply stops. */
    fun containsAppStrip(x: Float, y: Float): Boolean {
        val bounds = Rect(appScroll.scrollX, appScroll.scrollY,
            appScroll.scrollX + appScroll.width, appScroll.scrollY + appScroll.height)
        offsetDescendantRectToMyCoords(appScroll, bounds)
        return bounds.contains(x.toInt(), y.toInt())
    }

    fun refresh(force: Boolean = false) {
        removeCallbacks(refreshRetry)
        GechuangLog.info("top_apps", "Refresh requested force=$force loading=$loading")
        val now = System.currentTimeMillis()
        if (loading) {
            refreshPending = refreshPending || force
            return
        }
        if (!force && now - lastRefreshAt < REFRESH_INTERVAL_MS) return
        loading = true
        val generation = ++refreshGeneration
        Thread({
            // Render from the last verified snapshot first. The provider call can
            // be blocked by OEM background policy; it must not leave the strip empty.
            val cached = WorkspaceConfigClient.cachedOrNull(context)
            if (cached != null) AppLanguage.updateHostLanguage(cached.languageTag)
            val initial = runCatching {
                repository.load(
                    if (cached?.manualAppOrder == true) cached.appComponents else emptyList(),
                    manualOrder = cached?.manualAppOrder == true,
                )
            }
            mainHandler.post {
                if (generation != refreshGeneration) return@post
                loading = false
                cached?.let { onFrostStrengthChanged(it.frostStrength) }
                initial.onSuccess { snapshot ->
                    refreshRetryCount = 0
                    lastRefreshAt = System.currentTimeMillis()
                    renderApps(snapshot.visibleApps)
                }.onFailure { error ->
                    GechuangLog.error("top_apps", "Unable to load App strip", error)
                    scheduleRefreshRetry()
                }
                if (refreshPending) {
                    refreshPending = false
                    refresh(force = true)
                }
            }
            // A live query keeps the snapshot fresh, but it runs separately so a
            // blocked provider cannot hold up the visible App strip.
            Thread({
                val live = runCatching { WorkspaceConfigClient.queryOrNull(context) }.getOrNull()
                if (live == null) return@Thread
                AppLanguage.updateHostLanguage(live.languageTag)
                val refreshed = runCatching {
                    repository.load(
                        if (live.manualAppOrder) live.appComponents else emptyList(),
                        manualOrder = live.manualAppOrder,
                    )
                }
                mainHandler.post {
                    if (generation != refreshGeneration) return@post
                    onFrostStrengthChanged(live.frostStrength)
                    refreshed.onSuccess { snapshot ->
                        refreshRetryCount = 0
                        lastRefreshAt = System.currentTimeMillis()
                        renderApps(snapshot.visibleApps)
                    }.onFailure { error ->
                        GechuangLog.error("top_apps", "Unable to refresh App strip", error)
                    }
                }
            }, "Gechuang-TopAppsLive").start()
        }, "Gechuang-TopApps").start()
    }

    private fun scheduleRefreshRetry() {
        if (!isAttachedToWindow) return
        removeCallbacks(refreshRetry)
        val delay = minOf(1_000L * (1L shl refreshRetryCount), REFRESH_INTERVAL_MS)
        refreshRetryCount = (refreshRetryCount + 1).coerceAtMost(4)
        postDelayed(refreshRetry, delay)
    }

    private fun renderApps(entries: List<TopAppRepository.AppEntry>) {
        if (internalAppDragActive) { deferredApps = entries; return }
        val scrollX = appScroll.scrollX
        appRow.removeAllViews()
        if (entries.isEmpty()) {
            appRow.addView(messageView(AppLanguage.string(context, R.string.apps_none_available)))
        } else {
            val visible = entries.take(PINNED_APP_SLOTS)
            visible.forEach { entry -> appRow.addView(appTile(entry)) }
            if (entries.size > PINNED_APP_SLOTS) {
                appRow.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(12), 1)
                })
            }
            entries.drop(PINNED_APP_SLOTS).forEach { entry -> appRow.addView(appTile(entry)) }
        }
        appScroll.post { appScroll.scrollTo(scrollX, 0) }
        controlsChangedListener?.invoke()
        GechuangLog.info("top_apps", "Rendered ${entries.size} launcher activities scroll=${appScroll.width}x${appScroll.height} row=${appRow.width} children=${appRow.childCount}")
    }

    private fun appTile(entry: TopAppRepository.AppEntry): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isLongClickable = true
        contentDescription = entry.label
        addView(
            ImageView(context).apply {
                setImageDrawable(entry.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            LinearLayout.LayoutParams(dp(APP_ICON_SIZE_DP), dp(APP_ICON_SIZE_DP)),
        )
        layoutParams = LinearLayout.LayoutParams(dp(APP_TILE_WIDTH_DP), LayoutParams.MATCH_PARENT)
        setOnClickListener { onAppClicked(Intent(entry.launchIntent)) }
        setOnLongClickListener { tile ->
            val started = onAppDragStarting(tile, Intent(entry.launchIntent))
            internalAppDragActive = started
            if (started) parent?.requestDisallowInterceptTouchEvent(true)
            GechuangLog.info(
                "top_apps",
                "Internal drag started=$started component=${entry.launchIntent.component}",
            )
            started
        }
        setOnTouchListener { tile, event -> onAppDragTouch(tile, event) }
    }

    private fun addTile(): View = TextView(context).apply {
        text = "+"
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        contentDescription = "添加应用"
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(9).toFloat()
            setColor(Color.argb(30, 255, 255, 255))
            setStroke(dp(1), Color.argb(120, 255, 255, 255))
        }
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(dp(APP_TILE_WIDTH_DP - 4), dp(APP_ICON_SIZE_DP)).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        setOnClickListener { onAddAppRequested() }
    }

    private fun messageView(message: String) = TextView(context).apply {
        text = message
        setTextColor(Color.LTGRAY)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(16), 0)
    }

    private fun sideDescription(side: SidebarSide): String = if (side == SidebarSide.LEFT) {
        AppLanguage.string(context, R.string.window_side_left)
    } else {
        AppLanguage.string(context, R.string.window_side_right)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val APP_TILE_WIDTH_DP = 60
        const val APP_ICON_SIZE_DP = 60
        const val APP_TILE_HEIGHT_DP = 60
        const val PINNED_APP_SLOTS = 4
        const val BUTTON_SIZE_DP = 30
        const val CONTROL_CLUSTER_WIDTH_DP = BUTTON_SIZE_DP * 4 + 12
        const val REFRESH_INTERVAL_MS = 15_000L
    }
}

private class SideToggleButton(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        strokeCap = Paint.Cap.SQUARE
    }
    var side: SidebarSide = SidebarSide.RIGHT
        set(value) {
            field = value
            invalidate()
        }

    init { isClickable = true }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val iconWidth = minOf(dp(24f), width * 0.62f)
        val iconHeight = minOf(dp(20f), height * 0.5f)
        val left = (width - iconWidth) / 2f
        val top = (height - iconHeight) / 2f
        val gap = dp(2.4f)
        val half = (iconWidth - gap) / 2f
        val leftRect = RectF(left, top, left + half, top + iconHeight)
        val rightRect = RectF(left + half + gap, top, left + iconWidth, top + iconHeight)
        paint.color = Color.WHITE
        paint.style = if (side == SidebarSide.LEFT) Paint.Style.FILL else Paint.Style.STROKE
        canvas.drawRoundRect(leftRect, dp(2f), dp(2f), paint)
        paint.style = if (side == SidebarSide.RIGHT) Paint.Style.FILL else Paint.Style.STROKE
        canvas.drawRoundRect(rightRect, dp(2f), dp(2f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.1f)
        paint.alpha = 210
        canvas.drawLine(left, top + iconHeight + dp(3f), left + iconWidth, top + iconHeight + dp(3f), paint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

private abstract class ActionGlyphButton(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init { isClickable = true }

    final override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = minOf(width.toFloat(), height.toFloat(), dp(40f)) * 0.28f
        drawGlyph(canvas, paint, width / 2f, height / 2f, radius)
    }

    abstract fun drawGlyph(canvas: Canvas, paint: Paint, cx: Float, cy: Float, radius: Float)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

private class TasksButton(context: Context) : ActionGlyphButton(context) {
    override fun drawGlyph(canvas: Canvas, paint: Paint, cx: Float, cy: Float, radius: Float) {
        val cardWidth = radius * 0.72f
        val cardHeight = radius * 1.05f
        val gap = radius * 0.22f
        paint.style = Paint.Style.FILL
        val left = cx - cardWidth - gap / 2f
        val top = cy - cardHeight / 2f
        canvas.drawRoundRect(left, top, left + cardWidth, top + cardHeight,
            radius * 0.16f, radius * 0.16f, paint)
        val right = cx + gap / 2f
        canvas.drawRoundRect(right, top, right + cardWidth, top + cardHeight,
            radius * 0.16f, radius * 0.16f, paint)
    }
}

private class GearButton(context: Context) : ActionGlyphButton(context) {
    private val ringPath = Path()

    override fun drawGlyph(canvas: Canvas, paint: Paint, cx: Float, cy: Float, radius: Float) {
        val gearRadius = radius * 0.82f
        paint.style = Paint.Style.FILL
        repeat(8) { index ->
            canvas.save()
            canvas.rotate(index * 45f, cx, cy)
            canvas.drawRoundRect(
                cx - gearRadius * 0.17f,
                cy - gearRadius,
                cx + gearRadius * 0.17f,
                cy - gearRadius * 0.56f,
                gearRadius * 0.08f,
                gearRadius * 0.08f,
                paint,
            )
            canvas.restore()
        }
        ringPath.rewind()
        ringPath.fillType = Path.FillType.EVEN_ODD
        ringPath.addCircle(cx, cy, gearRadius * 0.68f, Path.Direction.CW)
        ringPath.addCircle(cx, cy, gearRadius * 0.28f, Path.Direction.CW)
        canvas.drawPath(ringPath, paint)
        paint.style = Paint.Style.STROKE
    }
}

private class ExitButton(context: Context) : ActionGlyphButton(context) {
    override fun drawGlyph(canvas: Canvas, paint: Paint, cx: Float, cy: Float, radius: Float) {
        val scale = radius * 1.15f / 12f
        fun x(value: Float) = cx + (value - 12f) * scale
        fun y(value: Float) = cy + (value - 12f) * scale
        paint.strokeWidth = resources.displayMetrics.density * 1.35f

        val box = Path().apply {
            moveTo(x(9f), y(7f))
            lineTo(x(5f), y(7f))
            lineTo(x(5f), y(21f))
            lineTo(x(17f), y(21f))
            lineTo(x(17f), y(15f))
        }
        canvas.drawPath(box, paint)

        val arrow = Path().apply {
            moveTo(x(8f), y(14f))
            lineTo(x(11f), y(17f))
            lineTo(x(17f), y(11f))
            lineTo(x(19f), y(13f))
            lineTo(x(21f), y(3f))
            lineTo(x(11f), y(5f))
            lineTo(x(13f), y(7f))
            close()
        }
        canvas.drawPath(arrow, paint)
    }
}
