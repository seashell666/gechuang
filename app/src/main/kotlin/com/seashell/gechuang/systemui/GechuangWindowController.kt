package com.seashell.gechuang.systemui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import com.seashell.gechuang.R
import com.seashell.gechuang.config.AppLanguage
import com.seashell.gechuang.display.SlotState
import com.seashell.gechuang.display.SlotTaskCoordinator
import com.seashell.gechuang.display.SlotTaskDrag
import com.seashell.gechuang.display.VirtualDisplaySlot
import com.seashell.gechuang.display.WORKSPACE_SLOT_COUNT
import com.seashell.gechuang.framework.TaskSurfaceCompat
import com.seashell.gechuang.framework.InputInjectionCompat
import com.seashell.gechuang.framework.ActivityTaskManagerCompat
import com.seashell.gechuang.logging.GechuangLog
import com.seashell.gechuang.safety.FeatureGate
import com.seashell.gechuang.task.MainTaskPresentationCoordinator
import com.seashell.gechuang.task.TaskRepository
import com.seashell.gechuang.trigger.TriggerBroadcastContract
import com.seashell.gechuang.workspace.SidebarSide
import com.seashell.gechuang.workspace.SystemServerWorkspaceBridge
import com.seashell.gechuang.workspace.WorkspaceGeometry
import com.seashell.gechuang.workspace.WorkspaceTransitionSpec

class GechuangWindowController(
    private val context: Context,
    private val mainTaskPresenter: MainTaskPresentationCoordinator,
    private val onSidebarSideRequested: (SidebarSide) -> Unit,
    private val onSettingsRequested: () -> Unit,
    private val onAddAppRequested: () -> Unit,
    private val onExitRequested: () -> Unit,
    private val onViewportChanged: () -> Unit,
) {
    private data class WindowRecord(val view: View, val title: String)

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val taskRepository = TaskRepository(context)
    private val viewport = WorkspaceViewport(context, onViewportChanged)
    private val records = mutableListOf<WindowRecord>()
    @Volatile private var rotationPanelHosts: List<WorkspacePanelHost> = emptyList()
    private var initialized = false
    private var slotCoordinator: SlotTaskCoordinator? = null
    private var topAppStrip: TopAppStripView? = null
    private var controlAreaPager: ControlAreaPager? = null
    private var sidebarView: FrameLayout? = null
    private var contentHighlightView: View? = null
    private var slotWindows: List<VirtualDisplaySlot> = emptyList()
    private val activeSlotViews = mutableListOf<ActiveSlotView>()
    private var activeSlotView: ActiveSlotView? = null
    private var activeExitChip: android.widget.TextView? = null
    private var activeSlotIndex: Int? = null
    private var activeSlotTaskId: Int? = null
    private var activeSideExitOverlay: View? = null
    private var suspendedMainTaskId: Int? = null
    private var lastMainTaskId: Int? = null
    private val activeSlotListener = object : ActiveSlotView.Listener {
        override fun onActiveSurfaceReady(view: ActiveSlotView) {
            if (activeSlotIndexFor(view) == activeSlotIndex) attachActiveSurface()
        }

        override fun onActiveSurfaceDestroyed(view: ActiveSlotView) {
            val index = activeSlotIndexFor(view)
            if (index < 0) return
            slotWindows.getOrNull(index)?.clearActiveOutput()
        }

        override fun onActiveTouch(view: ActiveSlotView, event: MotionEvent) {
            val index = activeSlotIndexFor(view)
            GechuangLog.info(
                "slot_overlay",
                "Active touch slot=$index current=$activeSlotIndex action=${event.actionMasked}",
            )
            if (index != activeSlotIndex) return
            injectActiveTouch(view, event)
        }

        override fun onExitRequested(view: ActiveSlotView) {
            GechuangLog.info("slot_overlay", "Exit requested from active slot=${activeSlotIndex}")
            slotCoordinator?.deactivateSlotOverlay()
        }
    }
    private var slotBackgroundSwipeEnabled = true
    private var frostStrength = 50

    private fun activeSlotIndexFor(view: ActiveSlotView): Int = activeSlotViews.indexOfFirst { it === view }

    private fun activeWindowView(): View? =
        records.firstOrNull { it.title == "GechuangActiveSlot" }?.view
    private val swapAnimator = WorkspaceSwapAnimator(context)
    private val internalDrag = WorkspaceInternalDragController(
        context, ::geometry, { slotWindows.map { it.view } },
        ::highlightInternalDragTarget, ::dropInternalPayload,
        {
            topAppStrip?.finishInternalAppDrag()
            controlAreaPager?.finishInternalTaskDrag()
        },
    )
    private val backdropGeneration = java.util.concurrent.atomic.AtomicInteger()
    private val backdropWorker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Gechuang-Backdrop")
    }
    private val backdropHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var wallpaperBitmap: android.graphics.Bitmap? = null
    private var wallpaperRotation = 0
    private var homePreview: Bitmap? = null
    private var homePreviewBounds = Rect()
    private val homePreviewGeneration = java.util.concurrent.atomic.AtomicInteger()
    private val homePreviewWorker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Gechuang-HomePreview").apply { isDaemon = true }
    }
    private var homePreviewCapturePending = false
    private var resumeContentGeneration = 0
    private var pendingResumeContent: Runnable? = null
    private var resumeFrameObserver: ViewTreeObserver? = null
    private var resumeFrameCallback: Runnable? = null
    private var rotationDrawObserver: ViewTreeObserver? = null
    private var rotationDrawCallback: ViewTreeObserver.OnPreDrawListener? = null
    private var sidebarSide = SystemServerWorkspaceBridge.sidebarSide(context)
    private var suspended = false
    private var workspaceVisible = false
    private val wallpaperReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_WALLPAPER_CHANGED -> {
                    clearHomePreview()
                    // Retain the displayed frame while the new wallpaper starts rendering.
                    // OEM live services may change content without changing component/id.
                    backdropGeneration.incrementAndGet()
                }
                Intent.ACTION_USER_PRESENT -> Unit
                else -> return
            }
            if (!suspended) refreshWallpaperBackdrop()
        }
    }

    init {
        // Warm the cache before the first workspace entry, including after unlocking.
        // Window creation is deliberately lazy; wallpaper observation must not be.
        runCatching {
            context.registerReceiver(
                wallpaperReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_WALLPAPER_CHANGED)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
                Context.RECEIVER_EXPORTED,
            )
        }.onFailure { GechuangLog.warn("wallpaper_backdrop", "Wallpaper change listener unavailable", it) }
        backdropHandler.post { refreshWallpaperBackdrop() }
        mainTaskPresenter.onHomePresented = {
            backdropHandler.post {
                if (!suspended && workspaceVisible) refreshWallpaperBackdrop()
            }
            backdropHandler.postDelayed({
                if (!suspended && workspaceVisible) captureHomePreview(geometry())
            }, WorkspaceTransitionSpec.DURATION_MS + HOME_PREVIEW_SETTLE_DELAY_MS)
        }
    }

    fun suspendForLock() {
        cancelPendingRotationDraw()
        cancelPendingResumeContent()
        suspended = true
        showSidebarExitOverlay(false)
        suspendedMainTaskId = mainTaskPresenter.currentTaskId()
            ?: lastMainTaskId
            ?: taskRepository.runningTasks()
                .firstOrNull {
                    it.displayId == Display.DEFAULT_DISPLAY &&
                        it.component.packageName != "com.miui.home"
                }?.taskId
        cancelDragVisuals()
        clearHomePreview()
        backdropGeneration.incrementAndGet()
        slotCoordinator?.setSuspended(true)
        topAppStrip?.setWorkspaceVisible(false)
        controlAreaPager?.setWorkspaceState(workspaceVisible, locked = true)
        // Keep TextureViews attached: GONE/removeView would destroy their virtual displays.
        records.forEach { record ->
            record.view.animate().cancel()
            record.view.alpha = 0f
            val params = record.view.layoutParams as WindowManager.LayoutParams
            params.flags = (params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) and
                WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER.inv()
            windowManager.updateViewLayout(record.view, params)
        }
    }

    fun resumeAfterLock(): WorkspaceGeometry {
        check(records.size == EXPECTED_WINDOW_COUNT) { "Retained SystemUI windows are unavailable" }
        cancelPendingResumeContent()
        workspaceVisible = true
        suspended = false
        // Wake reuses attached windows and the recorded layout. Re-running show()
        // starts a fresh alpha=0 entry animation and repeats all display setup.
        val geometry = geometry()
        applyWindowGeometry(geometry, refreshControls = false)
        sidebarView?.let { applySlotLayout(it, geometry) }
        records.forEach { record ->
            if (record.title == "GechuangActiveSlot" && activeSlotIndex == null) {
                record.view.visibility = View.GONE
                return@forEach
            }
            record.view.animate().cancel()
            record.view.visibility = View.VISIBLE
            record.view.alpha = 1f
            record.view.translationX = 0f
            record.view.translationY = 0f
        }
        resumeContentAfterFrame()
        (suspendedMainTaskId ?: mainTaskPresenter.currentTaskId())?.let { taskId ->
            taskRepository.bringTaskToFront(taskId).onFailure {
                GechuangLog.warn("workspace_unlock", "Unable to restore main task=$taskId", it)
            }
        }
        if (activeSlotIndex != null) showSidebarExitOverlay(true)
        return geometry
    }

    fun updateSuspendedOutputs(screenAwake: Boolean) {
        if (workspaceVisible && suspended) slotCoordinator?.updateSuspendedOutputs(screenAwake)
    }

    private fun resumeContentAfterFrame() {
        val token = resumeContentGeneration
        val startedAt = SystemClock.uptimeMillis()
        val resumeContent = Runnable {
            if (token != resumeContentGeneration || suspended || !workspaceVisible) return@Runnable
            cancelPendingResumeContent()
            val geometry = currentGeometry()
            // Commit the retained panel contents before reconnecting virtual
            // displays or querying media sessions through synchronous Binder calls.
            runCatching { topAppStrip?.setWorkspaceVisible(true) }.onFailure {
                GechuangLog.warn("workspace_unlock", "Top controls resume pending", it)
            }
            runCatching {
                controlAreaPager?.setLandscape(geometry.isLandscape, geometry.isControlOnLeft)
                controlAreaPager?.setWorkspaceState(workspaceVisible, locked = false)
                controlAreaPager?.refreshHost()
            }.onFailure {
                GechuangLog.warn("workspace_unlock", "Video host resume pending", it)
            }
            runCatching { slotCoordinator?.setSuspended(false) }.onFailure {
                GechuangLog.warn("workspace_unlock", "Slot outputs resume pending", it)
            }
        }
        pendingResumeContent = resumeContent
        val host = records.firstOrNull { it.title == "GechuangTopBar" }?.view
        val observer = host?.viewTreeObserver
        if (host?.isAttachedToWindow == true && host.isHardwareAccelerated && observer?.isAlive == true) {
            val onFrame = Runnable {
                backdropHandler.post {
                    if (token != resumeContentGeneration) return@post
                    GechuangLog.info("workspace_unlock", "Retained panel frame submitted in ${SystemClock.uptimeMillis() - startedAt}ms")
                    resumeContent.run()
                }
            }
            resumeFrameObserver = observer
            resumeFrameCallback = onFrame
            observer.registerFrameCommitCallback(onFrame)
            host.invalidate()
            // A dropped callback must not strand the retained slot outputs.
            backdropHandler.postDelayed(resumeContent, RESUME_FRAME_FALLBACK_DELAY_MS)
        } else {
            backdropHandler.post(resumeContent)
        }
    }

    private fun cancelPendingResumeContent() {
        resumeContentGeneration += 1
        pendingResumeContent?.let(backdropHandler::removeCallbacks)
        pendingResumeContent = null
        val observer = resumeFrameObserver
        resumeFrameCallback?.let { callback ->
            if (observer?.isAlive == true) observer.unregisterFrameCommitCallback(callback)
        }
        resumeFrameObserver = null
        resumeFrameCallback = null
    }

    fun show(): WorkspaceGeometry {
        initializeIfNeeded()
        check(records.size == EXPECTED_WINDOW_COUNT) { "SystemUI windows are unavailable" }
        workspaceVisible = true
        taskRepository.foregroundTask()
            ?.takeIf { it.component.packageName != "com.miui.home" }
            ?.let { lastMainTaskId = it.taskId }
        records.forEach { record ->
            if (record.title == "GechuangActiveSlot" && activeSlotIndex == null) {
                record.view.visibility = View.GONE
                return@forEach
            }
            val view = record.view
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.translationX = 0f
            view.alpha = 0f
            view.translationY = when (record.title) {
                "GechuangTopBar" -> -dp(ENTER_OFFSET_DP)
                else -> dp(ENTER_OFFSET_DP)
            }
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(WORKSPACE_ENTER_DURATION_MS)
                .setInterpolator(transitionInterpolator)
                .start()
        }
        refreshWallpaperBackdrop()
        topAppStrip?.setWorkspaceVisible(true)
        controlAreaPager?.setWorkspaceState(visible = true, locked = false)
        topAppStrip?.refresh()
        if (FeatureGate.VIRTUAL_DISPLAY_SLOTS.defaultEnabled) {
            slotCoordinator?.activate()
            slotCoordinator?.setSuspended(false)
        }
        return geometry()
    }

    fun hide() {
        cancelPendingRotationDraw()
        cancelPendingResumeContent()
        workspaceVisible = false
        cancelDragVisuals()
        clearHomePreview()
        suspended = false
        backdropGeneration.incrementAndGet()
        // Keep the side VirtualDisplays and their activities alive. Releasing
        // them here was the second cause of the "re-enter/splash again" effect.
        slotCoordinator?.setSuspended(true)
        activeSlotView?.hide()
        topAppStrip?.setWorkspaceVisible(false)
        controlAreaPager?.setWorkspaceState(visible = false, locked = false)
        records.forEach { record ->
            val view = record.view
            view.animate().cancel()
            val params = view.layoutParams as WindowManager.LayoutParams
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER.inv()
            windowManager.updateViewLayout(view, params)
            view.animate()
                .alpha(0f)
                .translationY(
                    if (record.title == "GechuangTopBar") -dp(EXIT_OFFSET_DP)
                    else dp(EXIT_OFFSET_DP),
                )
                .setDuration(WORKSPACE_EXIT_DURATION_MS)
                .setInterpolator(transitionInterpolator)
                .withEndAction {
                    view.visibility = View.GONE
                    view.translationX = 0f
                    view.translationY = 0f
                }
                .start()
        }
    }

    /** Side slot was promoted by the coordinator: show its existing virtual
     * display on the one full-size active TextureView. No task is moved. */
    internal fun showActiveSlot(slotIndex: Int, taskId: Int) {
        val previous = activeSlotIndex
        if (previous != null && previous != slotIndex) {
            activeSlotViews.getOrNull(previous)?.hide()
            slotWindows.getOrNull(previous)?.let { slot ->
                slot.view.hideMainMirror()
                slot.clearActiveOutput()
                slot.usePreviewOutput()
            }
        }
        activeSlotIndex = slotIndex
        activeSlotTaskId = taskId
        val slot = slotWindows.getOrNull(slotIndex) ?: return
        val geometry = slot.geometry() ?: return
        val displayId = slot.displayId() ?: return
        val view = activeSlotViews.getOrNull(slotIndex) ?: return
        activeSlotView = view
        // Keep exactly one hit-testable active surface. Six alpha=0 TextureViews
        // stacked in one window can otherwise swallow the first ACTION_DOWN.
        activeSlotViews.forEachIndexed { index, candidate ->
            if (index != slotIndex) candidate.hideImmediately()
        }
        activeWindowView()?.let { windowView ->
            windowView.animate().cancel()
            windowView.alpha = 1f
            windowView.visibility = View.VISIBLE
            if (windowView is android.view.ViewGroup) {
                val chip = activeExitChip ?: android.widget.TextView(context).apply {
                    text = "返回主格"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER
                    setPadding(dp(12).toInt(), dp(8).toInt(), dp(12).toInt(), dp(8).toInt())
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.argb(190, 18, 30, 36))
                        cornerRadius = dp(18)
                        setStroke(dp(1).toInt(), android.graphics.Color.argb(120, 255, 255, 255))
                    }
                    isClickable = true
                    setOnClickListener {
                        GechuangLog.info("slot_overlay", "Exit requested from chip")
                        slotCoordinator?.deactivateSlotOverlay()
                    }
                }.also {
                    activeExitChip = it
                    windowView.addView(it, android.widget.FrameLayout.LayoutParams(dp(104).toInt(), dp(38).toInt(),
                        android.view.Gravity.TOP or android.view.Gravity.END).apply {
                        topMargin = dp(10).toInt()
                        marginEnd = dp(10).toInt()
                    })
                }
                chip.visibility = View.VISIBLE
                chip.bringToFront()
            }
        }
        view.showInWindow(displayId, geometry.width, geometry.height)
        attachActiveSurface()
        updateContentPanelTouchable()
        showMainMirrorOnSlot(slot, slotIndex)
        showSidebarExitOverlay(true)
        GechuangLog.info(
            "slot_overlay",
            "Window showing slot=$slotIndex task=$taskId display=$displayId " +
                "content=${contentRect()} canvas=${geometry.width}x${geometry.height}",
        )
    }

    internal fun hideActiveSlot() {
        val previous = activeSlotIndex
        activeSlotIndex = null
        activeSlotTaskId = null
        activeSlotView?.hide()
        activeSlotView = null
        activeExitChip?.visibility = View.GONE
        // Do not leave an invisible full-size layer over the main app. A
        // transparent window that is still attached can swallow the first touch
        // on some MIUI builds, which makes the main task look frozen.
        activeWindowView()?.let { windowView ->
            windowView.animate().cancel()
            windowView.alpha = 0f
            windowView.visibility = View.GONE
        }
        previous?.let { index ->
            slotWindows.getOrNull(index)?.let { slot ->
                slot.view.hideMainMirror()
                slot.clearActiveOutput()
                slot.usePreviewOutput()
            }
        }
        updateContentPanelTouchable()
        showSidebarExitOverlay(false)
        GechuangLog.info("slot_overlay", "Window hiding previous=$previous")
    }

    private fun showSidebarExitOverlay(show: Boolean) {
        GechuangLog.info(
            "slot_overlay",
            "Sidebar exit layer request show=$show records=${records.map { it.title }} sidebar=${sidebarView != null}",
        )
        val existing = activeSideExitOverlay
        if (!show) {
            if (existing != null) {
                runCatching { windowManager.removeViewImmediate(existing) }
                activeSideExitOverlay = null
            }
            return
        }
        if (existing != null) return
        val sidebarRecord = records.firstOrNull { it.title == "GechuangSidebar" } ?: run {
            GechuangLog.warn("slot_overlay", "Sidebar record is unavailable for exit layer")
            return
        }
        val source = sidebarRecord.view.layoutParams as? WindowManager.LayoutParams ?: run {
            GechuangLog.warn(
                "slot_overlay",
                "Sidebar layout params are ${sidebarRecord.view.layoutParams?.javaClass?.name}",
            )
            return
        }
        val geometry = geometry()
        val view = View(context).apply {
            isClickable = true
            isFocusable = false
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "返回主格"
            setOnClickListener {
                GechuangLog.info("slot_overlay", "Exit requested from sidebar tap")
                slotCoordinator?.deactivateSlotOverlay()
            }
        }
        val params = WindowManager.LayoutParams(
            geometry.sidebarWidth,
            geometry.contentHeight,
            source.type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = if (sidebarSide == SidebarSide.LEFT) 0 else geometry.contentRight
            y = geometry.topHeight
            title = "GechuangSideExit"
            setFitInsetsTypes(0)
        }
        runCatching {
            windowManager.addView(view, params)
            activeSideExitOverlay = view
            GechuangLog.info("slot_overlay", "Sidebar exit layer added x=${params.x} w=${params.width}")
        }.onFailure {
            GechuangLog.warn("slot_overlay", "Unable to add sidebar exit layer", it)
        }
    }

    private fun showMainMirrorOnSlot(slot: VirtualDisplaySlot, slotIndex: Int) {
        val mainTaskId = mainTaskPresenter.currentTaskId()
            ?: taskRepository.runningTasks()
                .firstOrNull { it.displayId == Display.DEFAULT_DISPLAY }?.taskId
        if (mainTaskId != null) lastMainTaskId = mainTaskId
        if (mainTaskId == null) {
            slot.view.hideMainMirror()
            GechuangLog.warn("slot_overlay", "No main task for slot=$slotIndex mirror")
            return
        }
        val width = slot.view.textureView.width.takeIf { it > 0 } ?: slot.view.width
        val height = slot.view.textureView.height.takeIf { it > 0 } ?: slot.view.height
        if (width <= 0 || height <= 0) {
            GechuangLog.warn("slot_overlay", "Side preview has no size slot=$slotIndex")
            return
        }
        val bounds = TaskSurfaceCompat.taskBoundsForMirror(mainTaskId) ?: Rect()
        val currentGeometry = geometry()
        slot.view.showMainMirror(
            mainTaskId,
            bounds,
            width,
            height,
            currentGeometry.contentScale,
            currentGeometry.contentTranslationX,
            currentGeometry.contentTranslationY,
        )
        GechuangLog.info(
            "slot_overlay",
            "Mirroring main task=$mainTaskId into slot=$slotIndex preview=${width}x$height " +
                "sourceScale=${currentGeometry.contentScale}",
        )
    }

    private fun attachActiveSurface() {
        val index = activeSlotIndex ?: return
        val view = activeSlotView ?: return
        if (!view.isSurfaceReady) return
        val slot = slotWindows.getOrNull(index) ?: return
        val geometry = slot.geometry() ?: return
        val texture = view.surfaceTexture ?: return
        slot.useActiveOutput(texture, geometry.width, geometry.height)
        GechuangLog.info("slot_overlay", "Attached active surface slot=$index display=${slot.displayId()}")
    }

    private fun injectActiveTouch(view: ActiveSlotView, event: MotionEvent) {
        val index = activeSlotIndex ?: return
        val slot = slotWindows.getOrNull(index) ?: return
        val displayId = slot.displayId() ?: return
        val geometry = slot.geometry() ?: return
        val width = view.textureView.width
        val height = view.textureView.height
        if (width <= 0 || height <= 0) return
        GechuangLog.info(
            "slot_overlay",
            "Touch action=${event.actionMasked} local=${event.x},${event.y} view=${width}x$height " +
                "display=$displayId canvas=${geometry.width}x${geometry.height}",
        )
        InputInjectionCompat.injectMotion(
            displayId = displayId,
            event = event,
            scaleX = geometry.width.toFloat() / width,
            scaleY = geometry.height.toFloat() / height,
        )
    }

    private fun contentRect(): Rect {
        val geometry = geometry()
        return Rect(geometry.contentLeft, geometry.contentTop, geometry.contentRight, geometry.contentBottom)
    }

    private fun updateContentPanelTouchable() {
        // The active slot lives in its own content-sized window; the transparent
        // content panel must stay non-touchable so the main app keeps receiving input.
        val record = records.firstOrNull { it.title == "GechuangActiveSlot" } ?: return
        val params = record.view.layoutParams as? WindowManager.LayoutParams ?: return
        val touchable = activeSlotIndex != null && !suspended
        params.flags = if (touchable) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        // TYPE_PHONE carries a legacy 0.8 alpha on this ROM. When the active
        // slot is hidden, the window must be fully transparent as well as
        // non-touchable; otherwise a stale layer can block the main app.
        params.alpha = if (touchable) 1.0f else 0.0f
        windowManager.updateViewLayout(record.view, params)
        GechuangLog.info(
            "slot_overlay",
            "Active window touchable=$touchable active=$activeSlotIndex alpha=${params.alpha} " +
                "flags=0x${Integer.toHexString(params.flags)}",
        )
    }

    fun launchInSlot(intent: Intent): Result<Unit> {        if (!FeatureGate.VIRTUAL_DISPLAY_SLOTS.defaultEnabled) {
            return Result.failure(IllegalStateException("VirtualDisplay slot feature disabled"))
        }
        return slotCoordinator?.launchInSlot(intent)
            ?: Result.failure(IllegalStateException("VirtualDisplay slot unavailable"))
    }

    fun openTaskInSlot(taskId: Int, userId: Int, component: android.content.ComponentName): Boolean {
        if (!FeatureGate.VIRTUAL_DISPLAY_SLOTS.defaultEnabled) return false
        return slotCoordinator?.openTaskInSlot(taskId, userId, component) == true
    }

    fun onTasksChanged() {
        taskRepository.foregroundTask()
            ?.takeIf { it.component.packageName != "com.miui.home" }
            ?.let { lastMainTaskId = it.taskId }
        slotCoordinator?.onTasksChanged()
        if (workspaceVisible && !suspended) controlAreaPager?.onTasksChanged()
    }

    internal fun energySnapshot() = android.os.Bundle().apply {
        putInt("slot_count", slotWindows.count { it.displayId() != null })
        putLong("slot_frames", slotWindows.sumOf { it.contentFrameSequence })
    }

    fun openFromLauncher(intent: Intent): Result<Unit> = slotCoordinator
        ?.openFromLauncher(intent)
        ?: Result.failure(IllegalStateException("Slot coordinator unavailable"))

    fun currentGeometry(refreshViewport: Boolean = false): WorkspaceGeometry =
        viewport.geometry(sidebarSide, refreshViewport)

    fun projectDisplayRotation(rotation: Int) {
        viewport.projectRotation(rotation)
    }

    fun stagePanelRotation(rotation: Int) {
        rotationPanelHosts.forEach { it.stageRotation(rotation) }
    }

    fun whenRotationFrameReady(onReady: (View) -> Unit) {
        cancelPendingRotationDraw()
        val host = records.firstOrNull { it.title == "GechuangTopBar" }?.view as? WorkspacePanelHost ?: return
        val observer = host.viewTreeObserver
        val callback = ViewTreeObserver.OnPreDrawListener {
            if (host.isProjectionReady()) {
                cancelPendingRotationDraw()
                onReady(host)
            }
            true
        }
        rotationDrawObserver = observer
        rotationDrawCallback = callback
        observer.addOnPreDrawListener(callback)
        host.requestLayout()
        host.invalidate()
    }

    private fun cancelPendingRotationDraw() {
        rotationDrawCallback?.let { callback ->
            rotationDrawObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(callback)
        }
        rotationDrawCallback = null
        rotationDrawObserver = null
    }

    fun setNavigationButtonsEnabled(enabled: Boolean) {
        viewport.setNavigationButtonsEnabled(enabled)
    }

    fun setSlotBackgroundSwipeEnabled(enabled: Boolean) {
        if (slotBackgroundSwipeEnabled == enabled) return
        slotBackgroundSwipeEnabled = enabled
        slotWindows.forEach { it.view.setBackgroundSwipeEnabled(enabled) }
    }

    fun reconfigure(rotationOnly: Boolean = false): Result<WorkspaceGeometry> = runCatching {
        clearHomePreview()
        initializeIfNeeded()
        check(records.size == EXPECTED_WINDOW_COUNT) { "SystemUI windows are unavailable" }
        val geometry = geometry()
        applyWindowGeometry(geometry, refreshControls = !rotationOnly)
        if (rotationOnly) {
            // Only project the existing windows into the new display coordinates.
            // Their logical content, slot buffers and wallpaper frame stay intact.
            backdropGeneration.incrementAndGet()
            controlAreaPager?.setLandscape(geometry.isLandscape, geometry.isControlOnLeft, refreshHost = false)
        } else {
            sidebarView?.let { applySlotLayout(it, geometry) }
            refreshWallpaperBackdrop()
        }
        GechuangLog.info("workspace_configuration", "Reconfigured geometry=$geometry")
        geometry
    }

    fun setSidebarSide(side: SidebarSide): Result<WorkspaceGeometry> = runCatching {
        clearHomePreview()
        initializeIfNeeded()
        check(records.size == EXPECTED_WINDOW_COUNT) { "SystemUI windows are unavailable" }
        sidebarSide = side
        val geometry = geometry()
        applyWindowGeometry(geometry)
        topAppStrip?.setSidebarSide(side)
        sidebarView?.let { panel -> applySlotLayout(panel, geometry) }
        applySidebarBackdrop(geometry)
        records.firstOrNull { it.title == "GechuangSidebar" }?.view?.let { sidebar ->
            sidebar.animate().cancel()
            sidebar.translationY = 0f
            sidebar.alpha = 0f
            sidebar.translationX = if (side == SidebarSide.LEFT) {
                -dp(SIDEBAR_ENTER_OFFSET_DP)
            } else {
                dp(SIDEBAR_ENTER_OFFSET_DP)
            }
            sidebar.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(SIDEBAR_SWITCH_DURATION_MS)
                .setInterpolator(transitionInterpolator)
                .start()
        }
        GechuangLog.info("sidebar_side", "Applied side=$side geometry=$geometry")
        geometry
    }

    fun slotStatesDescription(): String = slotCoordinator
        ?.slotStates()
        ?.mapIndexed { index, state -> "${index + 1}:$state" }
        ?.joinToString(" | ")
        ?: "unavailable"

    internal fun visibleSlotTaskIds(): Set<Int> = if (!workspaceVisible || suspended) emptySet() else {
        slotCoordinator?.slotStates().orEmpty().filterIsInstance<SlotState.Occupied>()
            .mapTo(mutableSetOf()) { it.taskId }
    }

    private fun initializeIfNeeded() {
        if (initialized) return
        initialized = true
        val windowType = SystemUiWindowTypeResolver.resolve() ?: run {
            initialized = false
            return
        }
        val geometry = geometry()

        addWindow(
            title = "GechuangTopBar",
            view = TopAppStripView(
                context = context,
                initialSidebarSide = sidebarSide,
                onAppClicked = { intent ->
                    slotCoordinator?.openInMain(
                        intent,
                        autoMinimizeCurrent = SystemUiRuntime.shouldAutoMinimizeMainOnTopAppSwitch(),
                    )?.onFailure { error ->
                        GechuangLog.warn("top_apps", "Unable to open ${intent.component} in main", error)
                    }
                },
                onSidebarSideRequested = onSidebarSideRequested,
                onSettingsRequested = onSettingsRequested,
                onTasksRequested = { controlAreaPager?.toggleTasksPage() },
                onAddAppRequested = onAddAppRequested,
                onExitRequested = onExitRequested,
                onFrostStrengthChanged = { strength ->
                    if (frostStrength != strength) {
                        frostStrength = strength
                        refreshWallpaperBackdrop()
                    }
                },
                onAppDragStarting = { source, intent ->
                    workspaceVisible && !suspended && internalDrag.start(
                        source, WorkspaceInternalDragController.Payload.App(intent),
                    )
                },
                onAppDragTouch = internalDrag::touch,
                monitorSlots = ::energySnapshot,
            ).also { topAppStrip = it }.let { strip ->
                ControlAreaPager(
                    context, strip,
                    onTaskClicked = { task ->
                        controlAreaPager?.toggleTasksPage()
                        slotCoordinator?.openRecentInMain(task,
                            autoMinimizeCurrent = SystemUiRuntime.shouldAutoMinimizeMainOnTopAppSwitch())
                            ?.onFailure { error ->
                                GechuangLog.warn("recent_tasks", "Unable to open task=${task.taskId}", error)
                                android.widget.Toast.makeText(context,
                                    AppLanguage.string(context, R.string.control_task_open_failed),
                                    android.widget.Toast.LENGTH_SHORT).show()
                            }
                    },
                    onTaskDragStarting = { source, task ->
                        workspaceVisible && !suspended && internalDrag.start(source,
                            WorkspaceInternalDragController.Payload.RecentTask(task))
                    },
                    onTaskDragTouch = internalDrag::touch,
                    excludedTaskIds = {
                        visibleSlotTaskIds() + listOfNotNull(taskRepository.foregroundTask()?.taskId)
                    },
                    onFloatingPipBoundsChanged = ::setFloatingPipBounds,
                ).also { controlAreaPager = it }
            },
            width = geometry.screenWidth,
            height = geometry.topHeight,
            gravity = Gravity.TOP,
            touchable = true,
            windowType = windowType,
        )
        addWindow(
            title = "GechuangSidebar",
            view = sidebarPanel(geometry),
            width = geometry.sidebarWidth,
            height = geometry.contentHeight,
            gravity = Gravity.TOP or if (sidebarSide == SidebarSide.LEFT) {
                Gravity.START
            } else {
                Gravity.END
            },
            y = geometry.topHeight,
            touchable = FeatureGate.TASK_SWAP.defaultEnabled,
            windowType = windowType,
        )
        addWindow(
            title = "GechuangContentPanel",
            view = FrameLayout(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                addView(View(context).also { contentHighlightView = it })
            },
            width = geometry.contentWidth,
            height = geometry.contentHeight,
            gravity = Gravity.TOP or if (sidebarSide == SidebarSide.LEFT) {
                Gravity.END
            } else {
                Gravity.START
            },
            y = geometry.topHeight,
            windowType = windowType,
        )
        addWindow(
            title = "GechuangActiveSlot",
            view = FrameLayout(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                activeSlotViews.clear()
                repeat(WORKSPACE_SLOT_COUNT) { index ->
                    val activeView = ActiveSlotView(context).apply {
                        setListener(activeSlotListener)
                        alpha = 0f
                        visibility = View.INVISIBLE
                    }
                    activeSlotViews += activeView
                    addView(
                        activeView,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
            },
            width = geometry.contentWidth,
            height = geometry.contentHeight,
            gravity = Gravity.TOP or Gravity.LEFT,
            x = geometry.contentLeft,
            y = geometry.contentTop,
            touchable = true,
            windowType = windowType,
        )
        if (records.size != EXPECTED_WINDOW_COUNT) {
            records.forEach { record ->
                try {
                    windowManager.removeView(record.view)
                } catch (error: Throwable) {
                    GechuangLog.warn("systemui_window", "Cleanup failed for ${record.title}", error)
                }
            }
            records.clear()
            slotCoordinator = null
            topAppStrip = null
            controlAreaPager = null
            sidebarView = null
            contentHighlightView = null
            activeSlotView = null
            activeSlotViews.clear()
            activeSlotIndex = null
            activeSlotTaskId = null
            slotWindows = emptyList()
            initialized = false
        }
    }

    private fun addWindow(
        title: String,
        view: View,
        width: Int,
        height: Int,
        gravity: Int,
        x: Int = 0,
        y: Int = 0,
        touchable: Boolean = false,
        windowType: Int,
    ) {
        val interactionFlags = if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val params = WindowManager.LayoutParams(
            width,
            height,
            windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                interactionFlags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            this.x = x
            this.y = y
            this.title = title
            // A change in display axes is not a physical panel move. WM's default
            // move animation otherwise interpolates old/new x/y across the screen.
            runCatching {
                val privateFlags = WindowManager.LayoutParams::class.java.getField("privateFlags")
                privateFlags.setInt(this, privateFlags.getInt(this) or 0x40) // PRIVATE_FLAG_NO_MOVE_ANIMATION
            }.onFailure { GechuangLog.warn("systemui_window", "Unable to disable panel move animation", it) }
        }
        val hostedView = if (title == "GechuangContentPanel" || title == "GechuangActiveSlot") view
            else WorkspacePanelHost(context, view, managesTouchRegion = title == "GechuangSidebar")
        val record = WindowRecord(hostedView, title)
        configureWindow(record, params, geometry())
        hostedView.visibility = View.GONE
        try {
            windowManager.addView(hostedView, params)
            records += record
            rotationPanelHosts = records.mapNotNull { it.view as? WorkspacePanelHost }
            GechuangLog.info("systemui_window", "Added $title")
        } catch (error: Throwable) {
            GechuangLog.error("systemui_window", "Could not add $title", error)
        }
    }

    private fun setFloatingPipBounds(bounds: Rect?) {
        rotationPanelHosts.forEach { it.setFloatingPipBounds(bounds) }
    }

    private fun applyWindowGeometry(geometry: WorkspaceGeometry, refreshControls: Boolean = true) {
        cancelDragVisuals()
        records.forEach { record ->
            val params = record.view.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            configureWindow(record, params, geometry)
            windowManager.updateViewLayout(record.view, params)
        }
        if (refreshControls) {
            controlAreaPager?.setLandscape(geometry.isLandscape, geometry.isControlOnLeft)
            controlAreaPager?.refreshHost()
        }
    }

    private fun configureWindow(
        record: WindowRecord,
        params: WindowManager.LayoutParams,
        geometry: WorkspaceGeometry,
    ) {
        val touchable = !suspended && (record.title == "GechuangTopBar" ||
            (record.title == "GechuangSidebar" && FeatureGate.TASK_SWAP.defaultEnabled) ||
            record.title == "GechuangActiveSlot")
        params.flags = if (touchable) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (record.title == "GechuangActiveSlot") params.alpha = 1.0f
        // A foreground app normally hides live wallpaper, so screenshotWallpaper()
        // has no surface to read on entry. Keep that surface available behind the
        // workspace until exit/lock, without sending control gestures to it.
        params.flags = if (record.title == "GechuangTopBar" && workspaceVisible && !suspended) {
            params.flags or WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
        } else params.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER.inv()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            params.setWallpaperTouchEventsEnabled(false)
        }
        // WorkspaceGeometry explicitly reserves navigation space in display coordinates.
        // FLAG_LAYOUT_IN_SCREEN alone does not disable inset fitting: a bottom navigation
        // inset can make WindowManager shift the entire full-height sidebar upward.
        // Apply this on both creation and reconfiguration, for either sidebar and rotation.
        params.setFitInsetsTypes(0)
        params.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        params.gravity = Gravity.TOP or Gravity.LEFT
        setWindowBounds(record.title, params, geometry)
        if (record.title == "GechuangContentPanel") {
            contentHighlightView?.layoutParams = FrameLayout.LayoutParams(
                geometry.contentWidth, geometry.contentHeight, Gravity.TOP or Gravity.LEFT,
            ).apply {
                leftMargin = geometry.contentLeft
                topMargin = geometry.contentTop
            }
        }
        // WM chooses these bounds during its rotation layout, before the
        // configuration callback can reach SystemUI's main looper. Without them
        // a portrait-sized window can be placed in landscape for an entire frame.
        runCatching {
            val field = WindowManager.LayoutParams::class.java.getField("paramsForRotation")
            val rotations = Array(4) { rotation ->
                WindowManager.LayoutParams().apply {
                    copyFrom(params)
                    field.set(this, null)
                    setWindowBounds(record.title, this, geometry.rotatedTo(rotation))
                }
            }
            field.set(params, rotations)
            (record.view as? WorkspacePanelHost)?.setRotationFrames(rotations.mapIndexed { rotation, bounds ->
                WorkspacePanelProjection.Frame(bounds.width, bounds.height, geometry.rotatedTo(rotation).panelRotationDegrees)
            })
        }.onFailure { GechuangLog.warn("systemui_window", "Rotation-specific panel bounds unavailable", it) }
        (record.view as? WorkspacePanelHost)?.setProjection(
            geometry.panelRotationDegrees, params.width, params.height,
        )
    }

    private fun setWindowBounds(title: String, params: WindowManager.LayoutParams, geometry: WorkspaceGeometry) {
        when (title) {
            "GechuangTopBar" -> {
                params.width = geometry.controlWidth
                params.height = geometry.controlHeight
                params.x = geometry.controlLeft
                params.y = geometry.controlTop
            }
            "GechuangSidebar" -> {
                params.width = geometry.sidebarPhysicalWidth
                params.height = geometry.sidebarPhysicalHeight
                params.x = geometry.sidebarLeft
                params.y = geometry.sidebarTop
            }
            "GechuangContentPanel" -> {
                // The transparent, non-touchable root observes full-display insets.
                // Its drag highlight child and visible tasks use the safe bounds.
                params.width = geometry.screenWidth
                params.height = geometry.screenHeight
                params.x = 0
                params.y = 0
            }
            "GechuangActiveSlot" -> {
                params.width = geometry.contentWidth
                // Leave the system gesture strip uncovered; otherwise the active
                // TextureView eats the bottom-up recents gesture before SystemUI
                // can start its transition.
                params.height = (geometry.contentHeight - dp(18).toInt()).coerceAtLeast(1)
                params.x = geometry.contentLeft
                params.y = geometry.contentTop
            }
        }
    }

    private fun sidebarPanel(geometry: WorkspaceGeometry): View = object : FrameLayout(context) {
        private val dividerPaint = android.graphics.Paint().apply {
            color = Color.argb(90, 255, 255, 255)
        }

        override fun dispatchDraw(canvas: android.graphics.Canvas) {
            super.dispatchDraw(canvas)
            val thickness = resources.displayMetrics.density
            val edge = if (logicalSidebarSide(this@GechuangWindowController.geometry()) == SidebarSide.LEFT) width - thickness else 0f
            canvas.drawRect(edge, 0f, edge + thickness, height.toFloat(), dividerPaint)
            slotWindows.drop(1).forEach { slot ->
                val y = slot.view.top.toFloat()
                canvas.drawRect(0f, y - thickness, width.toFloat(), y, dividerPaint)
            }
        }
    }.apply {
        sidebarView = this
        setBackgroundColor(Color.TRANSPARENT)
        if (FeatureGate.VIRTUAL_DISPLAY_SLOTS.defaultEnabled) {
            slotWindows = List(WORKSPACE_SLOT_COUNT) { index -> VirtualDisplaySlot(context, index) }
            slotWindows.forEach { slot ->
                slot.view.setBackgroundSwipeEnabled(slotBackgroundSwipeEnabled)
                addView(slot.view)
            }
            applySlotLayout(this, geometry)
            slotCoordinator = SlotTaskCoordinator(
                context = context,
                slots = slotWindows,
                mainTaskPresenter = mainTaskPresenter,
                geometryProvider = ::geometry,
                returnAfterSharing = SystemUiRuntime::shouldReturnAfterSharing,
                openDockedPip = { taskId -> controlAreaPager?.openDockedPip(taskId) == true },
                onSwapAnimation = ::animateDragSwap,
                onMainSlotAnimation = ::animateMainSlotSwap,
                onInternalSlotDrag = { source, drag ->
                    internalDrag.start(source, WorkspaceInternalDragController.Payload.Slot(drag))
                },
                onInternalDragTouch = internalDrag::touch,
                onActiveSlotChanged = { slotIndex, taskId ->
                    if (slotIndex == null || taskId == null) {
                        hideActiveSlot()
                    } else {
                        showActiveSlot(slotIndex, taskId)
                    }
                },
            )
        }
    }

    private fun cancelDragVisuals() {
        internalDrag.cancel()
        slotWindows.forEach { it.view.cancelBackgroundSwipe() }
        slotCoordinator?.onSlotDragEnded()
        slotCoordinator?.cancelPendingExchange()
        swapAnimator.cancel()
    }

    private fun highlightInternalDragTarget(target: WorkspaceInternalDragController.Target?) {
        slotWindows.forEachIndexed { index, slot ->
            slot.view.setDropHighlighted(target == WorkspaceInternalDragController.Target.Slot(index))
        }
        topAppStrip?.setDismissTargetHighlighted(target == WorkspaceInternalDragController.Target.Background)
        contentHighlightView?.background =
            if (target == WorkspaceInternalDragController.Target.Main) {
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.argb(25, 62, 190, 198))
                    setStroke(dp(2).toInt(), Color.rgb(80, 216, 224))
                    cornerRadius = dp(8)
                }
            } else android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
    }

    private fun dropInternalPayload(
        payload: WorkspaceInternalDragController.Payload,
        target: WorkspaceInternalDragController.Target,
    ): Boolean {
        val coordinator = slotCoordinator ?: return false
        return when (target) {
            is WorkspaceInternalDragController.Target.Slot -> when (payload) {
                is WorkspaceInternalDragController.Payload.Slot -> coordinator.onSlotDropped(target.index, payload.drag)
                is WorkspaceInternalDragController.Payload.App -> coordinator.onIntentDropped(target.index, payload.intent)
                is WorkspaceInternalDragController.Payload.RecentTask -> coordinator.onRecentTaskDropped(target.index, payload.task)
            }
            WorkspaceInternalDragController.Target.Main -> when (payload) {
                is WorkspaceInternalDragController.Payload.Slot -> coordinator.dropSlotIntoMain(payload.drag)
                is WorkspaceInternalDragController.Payload.RecentTask -> coordinator.openRecentInMain(payload.task,
                    autoMinimizeCurrent = SystemUiRuntime.shouldAutoMinimizeMainOnTopAppSwitch()).isSuccess
                is WorkspaceInternalDragController.Payload.App -> false
            }
            WorkspaceInternalDragController.Target.Background ->
                payload is WorkspaceInternalDragController.Payload.Slot && coordinator.dismissDraggedSlot(payload.drag)
        }
    }

    private fun animateMainSlotSwap(slotIndex: Int, move: () -> Boolean, onFinished: () -> Unit) {
        animateSwap(slotIndex, null, null, move, onFinished)
    }

    private fun animateDragSwap(
        drag: SlotTaskDrag,
        targetIndex: Int?,
        move: () -> Boolean,
        onFinished: () -> Unit,
    ) {
        animateSwap(drag.slotIndex, targetIndex, drag, move, onFinished)
    }

    private fun animateSwap(
        sourceIndex: Int,
        targetIndex: Int?,
        drag: SlotTaskDrag?,
        move: () -> Boolean,
        onFinished: () -> Unit,
    ) {
        fun withoutPreview() {
            try { move() } finally { onFinished() }
        }
        if (!workspaceVisible || suspended || !WorkspaceWallpaperBackdrop.canCapture(context)) {
            withoutPreview()
            return
        }
        val geometry = geometry()
        val previewHost = contentHighlightView?.parent as? FrameLayout
        val sourceSlot = slotWindows.getOrNull(sourceIndex)
        val sourceRect = Rect()
        if (previewHost == null || sourceSlot == null || !sourceSlot.view.getScreenVisibleRect(sourceRect)) {
            withoutPreview()
            return
        }
        val states = slotCoordinator?.slotStates().orEmpty()
        val sourceState = states.getOrNull(sourceIndex)
        val parkingMain = drag == null && targetIndex == null && sourceState is SlotState.Ready
        if (!parkingMain && sourceState !is SlotState.Occupied) {
            withoutPreview()
            return
        }
        val mainRect = Rect(geometry.contentLeft, geometry.contentTop, geometry.contentRight, geometry.contentBottom)
        if (homePreviewBounds != mainRect) clearHomePreview()
        // Use only a settled HOME frame. Never wait for a display capture on the
        // UI thread or accept an in-flight capture containing this swap overlay.
        homePreviewGeneration.incrementAndGet()
        val targetSlot = targetIndex?.let { slotWindows.getOrNull(it) }
        val targetRect = Rect(mainRect)
        if (targetIndex != null) {
            if (targetSlot == null || !targetSlot.view.getScreenVisibleRect(targetRect)) {
                withoutPreview()
                return
            }
        }
        val backdrop = WorkspaceWallpaperBackdrop.crop(wallpaperBitmap, 0, 0, frostStrength)
        val mainTaskId = if (targetIndex == null) mainTaskPresenter.currentTaskId() else null
        val mainPreview = mainTaskId?.let {
            TaskSurfaceCompat.capturePreview(it, mainRect)
        }
        fun slotPreview(slot: VirtualDisplaySlot): android.graphics.Bitmap? {
            val source = slot.sourceBounds()
            return slot.view.capturePreview(source.width, source.height)
        }
        val sourcePreview = if (parkingMain) mainPreview else slotPreview(sourceSlot)
        val targetOccupied = targetIndex != null && states.getOrNull(targetIndex) is SlotState.Occupied
        val targetPreview = when {
            parkingMain -> null
            targetSlot != null && targetOccupied -> slotPreview(targetSlot)
            targetIndex == null -> mainPreview
            else -> null
        }
        val needsTargetPreview = !parkingMain && (targetOccupied || mainTaskId != null)
        if (sourcePreview == null || (needsTargetPreview && targetPreview == null)) {
            GechuangLog.warn(
                "workspace_swap",
                "Swap preview missing source=${sourcePreview != null} target=${targetPreview != null} " +
                    "parkingMain=$parkingMain targetIndex=$targetIndex mainTaskId=$mainTaskId",
            )
            sourcePreview?.recycle()
            if (targetPreview !== sourcePreview) targetPreview?.recycle()
            withoutPreview()
            return
        }
        val from = RectF(if (parkingMain) mainRect else sourceRect)
        val to = RectF(if (parkingMain) sourceRect else targetRect)
        val start = if (drag != null) {
            val point = drag.dropPoint ?: android.graphics.PointF(to.centerX(), to.centerY())
            val halfWidth = sourceRect.width() * 0.45f
            val halfHeight = sourceRect.height() * 0.45f
            RectF(point.x - halfWidth, point.y - halfHeight, point.x + halfWidth, point.y + halfHeight)
        } else RectF(from)
        val incomingTaskId = (sourceState as? SlotState.Occupied)?.taskId
        val returningHomePackage = if (parkingMain) TriggerBroadcastContract.resolveHomePackage(context) else null
        val stationaryBackground = if (parkingMain) {
            homePreview?.copy(Bitmap.Config.ARGB_8888, false)
        } else null
        val previewSlots = listOfNotNull(sourceSlot, targetSlot)
        var sourceFrame = sourceSlot.contentFrameSequence
        var targetFrame = targetSlot?.contentFrameSequence ?: 0L
        previewSlots.forEach { it.view.beginExchangePreview() }
        try {
            swapAnimator.animate(
                sourcePreview, targetPreview, start, from, to,
                host = previewHost,
                backdrop = backdrop,
                stationaryBackground = stationaryBackground,
                durationMs = SystemUiRuntime.swapAnimationDurationMs(),
                returningHome = parkingMain,
                stabilizeMain = !parkingMain,
                move = {
                    // Ignore frames produced while the old tasks were still in
                    // place. Handoff needs a buffer after the logical exchange.
                    sourceFrame = sourceSlot.contentFrameSequence
                    targetFrame = targetSlot?.contentFrameSequence ?: 0L
                    move()
                },
                isLiveReady = {
                    val currentStates = slotCoordinator?.slotStates().orEmpty()
                    fun slotReady(index: Int, slot: VirtualDisplaySlot, frame: Long): Boolean =
                        when (currentStates.getOrNull(index)) {
                            is SlotState.Ready -> true
                            is SlotState.Occupied -> slot.view.textureView.isAvailable && slot.contentFrameSequence > frame
                            else -> false
                        }
                    val sourceReady = slotReady(sourceIndex, sourceSlot, sourceFrame)
                    val targetReady = if (targetIndex != null && targetSlot != null) {
                        slotReady(targetIndex, targetSlot, targetFrame)
                    } else if (parkingMain) {
                        returningHomePackage != null &&
                            taskRepository.foregroundTask()?.component?.packageName == returningHomePackage
                    } else {
                        val promoted = taskRepository.findTask(incomingTaskId ?: -1)
                        promoted != null &&
                            mainTaskPresenter.currentTaskId() == incomingTaskId &&
                            promoted.visible &&
                            !promoted.component.className.contains("Splash", ignoreCase = true)
                    }
                    sourceReady && targetReady
                },
                onFinished = {
                    previewSlots.forEach { it.view.endExchangePreview() }
                    onFinished()
                    if (parkingMain) backdropHandler.postDelayed({
                        if (!suspended && workspaceVisible) captureHomePreview(currentGeometry())
                    }, HOME_PREVIEW_SETTLE_DELAY_MS)
                },
            )
        } catch (error: Throwable) {
            swapAnimator.cancel()
            previewSlots.forEach { it.view.endExchangePreview() }
            if (!sourcePreview.isRecycled) sourcePreview.recycle()
            if (targetPreview != null && !targetPreview.isRecycled) targetPreview.recycle()
            if (stationaryBackground != null && !stationaryBackground.isRecycled) stationaryBackground.recycle()
            throw error
        }
    }

    private fun captureHomePreview(geometry: WorkspaceGeometry) {
        if (homePreviewCapturePending || suspended || !workspaceVisible || swapAnimator.isRunning) return
        val bounds = Rect(geometry.contentLeft, geometry.contentTop, geometry.contentRight, geometry.contentBottom)
        val token = homePreviewGeneration.incrementAndGet()
        homePreviewCapturePending = true
        homePreviewWorker.execute {
            val result = runCatching {
                if (token != homePreviewGeneration.get() || !WorkspaceWallpaperBackdrop.canCapture(context)) {
                    return@runCatching null
                }
                val foreground = taskRepository.foregroundTask() ?: return@runCatching null
                if (foreground.component.packageName != TriggerBroadcastContract.resolveHomePackage(context)) {
                    return@runCatching null
                }
                // The synchronous platform listener may wait seconds while the
                // display wakes. Its buffer wait must never block SystemUI drawing.
                val snapshot = TaskSurfaceCompat.captureDisplayPreview(bounds) ?: return@runCatching null
                val stillHome = runCatching { taskRepository.foregroundTask()?.taskId == foreground.taskId }
                    .getOrDefault(false)
                if (snapshot.width != bounds.width() || snapshot.height != bounds.height() || !stillHome) {
                    snapshot.recycle()
                    null
                } else snapshot
            }
            backdropHandler.post {
                homePreviewCapturePending = false
                val snapshot = result.getOrNull()
                val current = currentGeometry()
                val currentBounds = Rect(current.contentLeft, current.contentTop, current.contentRight, current.contentBottom)
                if (token != homePreviewGeneration.get() || suspended || !workspaceVisible ||
                    swapAnimator.isRunning || bounds != currentBounds || !WorkspaceWallpaperBackdrop.canCapture(context)
                ) {
                    snapshot?.recycle()
                    // A lock/configuration change can supersede a slow capture.
                    // Warm the new lifecycle once that old request has drained.
                    if (!suspended && workspaceVisible && !swapAnimator.isRunning) {
                        backdropHandler.postDelayed({
                            if (!suspended && workspaceVisible) captureHomePreview(currentGeometry())
                        }, HOME_PREVIEW_SETTLE_DELAY_MS)
                    }
                    return@post
                }
                result.exceptionOrNull()?.let {
                    GechuangLog.warn("workspace_swap", "HOME preview unavailable", it)
                }
                clearHomePreview()
                homePreview = snapshot
                if (snapshot != null) homePreviewBounds.set(bounds)
            }
        }
    }

    private fun clearHomePreview() {
        homePreviewGeneration.incrementAndGet()
        homePreview?.recycle()
        homePreview = null
        homePreviewBounds.setEmpty()
    }

    private fun applySlotLayout(panel: FrameLayout, geometry: WorkspaceGeometry) {
        panel.invalidate()
        val profile = viewport.layout()
        val bounds = profile.slots
        slotWindows.forEachIndexed { index, slot ->
            slot.setLayoutProfile(profile)
            slot.view.setBackgroundSwipeSide(logicalSidebarSide(geometry))
            slot.view.layoutParams = FrameLayout.LayoutParams(
                bounds[index].width,
                bounds[index].height,
                Gravity.TOP,
            ).apply {
                marginStart = if (logicalSidebarSide(geometry) == SidebarSide.RIGHT) profile.gapPixels else 0
                marginEnd = if (logicalSidebarSide(geometry) == SidebarSide.LEFT) profile.gapPixels else 0
                topMargin = bounds[index].top
            }
        }
        activeSlotIndex?.let { index ->
            val slotGeometry = slotWindows.getOrNull(index)?.geometry() ?: return@let
            activeSlotView?.updateWindowContent(slotGeometry.width, slotGeometry.height)
        }
    }

    private fun refreshWallpaperBackdrop(attempt: Int = 0) {
        val geometry = geometry()
        // Use the last frame immediately; surface capture runs off the UI thread.
        applyWallpaperBackdrop(geometry)
        val previous = wallpaperBitmap
        val generation = backdropGeneration.incrementAndGet()
        backdropWorker.execute {
            if (generation != backdropGeneration.get()) return@execute
            val identity = WorkspaceWallpaperBackdrop.identity(context)
            val captured = WorkspaceWallpaperBackdrop.capture(
                context, geometry.screenWidth, geometry.screenHeight,
            )
            val currentIdentity = WorkspaceWallpaperBackdrop.identity(context)
            // Do not associate a frame taken during a wallpaper change with the
            // new wallpaper. The next retry will capture the settled surface.
            val bitmap = captured?.takeUnless {
                identity != null && currentIdentity != null && identity != currentIdentity
            }
            if (bitmap !== captured) captured?.recycle()
            val fitted = if (bitmap == null && previous != null &&
                (previous.width != geometry.screenWidth || previous.height != geometry.screenHeight)
            ) WorkspaceWallpaperBackdrop.fit(previous, geometry.screenWidth, geometry.screenHeight)
            else null
            backdropHandler.post {
                if (generation != backdropGeneration.get() || !WorkspaceWallpaperBackdrop.canCapture(context)) {
                    bitmap?.recycle()
                    fitted?.recycle()
                    return@post
                }
                if (bitmap != null) {
                    wallpaperBitmap = bitmap
                    wallpaperRotation = geometry.displayRotation
                } else if (fitted != null) {
                    wallpaperBitmap = fitted
                    wallpaperRotation = geometry.displayRotation
                }
                // A failed capture or identity query must not erase a valid frame.
                applyWallpaperBackdrop(geometry)
                // A waking live engine can briefly expose its initial frame even
                // when capture succeeds. Refresh again after it has started drawing.
                if (attempt < BACKDROP_SETTLE_REFRESHES ||
                    (bitmap == null && attempt < BACKDROP_MAX_RETRIES)
                ) {
                    backdropHandler.postDelayed({
                        if (generation == backdropGeneration.get() && !suspended &&
                            workspaceVisible
                        ) refreshWallpaperBackdrop(attempt + 1)
                    }, minOf(1_000L, 200L * (attempt + 1)))
                }
            }
        }
    }

    private fun applyWallpaperBackdrop(geometry: WorkspaceGeometry) {
        val panel = geometry.rotatedTo(0)
        (records.firstOrNull { it.title == "GechuangTopBar" }?.view as? WorkspacePanelHost)?.setPanelBackground(
            WorkspaceWallpaperBackdrop.crop(wallpaperBitmap, panel.controlLeft, panel.controlTop,
                frostStrength, wallpaperRotation, panel.displayRotation),
        )
        topAppStrip?.setMonitorWallpaperLight(WorkspaceWallpaperBackdrop.isLight(
            wallpaperBitmap, panel.controlLeft, panel.controlTop, panel.controlWidth, panel.controlHeight,
        ))
        applySidebarBackdrop(geometry)
    }

    private fun applySidebarBackdrop(geometry: WorkspaceGeometry) {
        val panel = geometry.rotatedTo(0)
        (records.firstOrNull { it.title == "GechuangSidebar" }?.view as? WorkspacePanelHost)?.setPanelBackground(
            WorkspaceWallpaperBackdrop.crop(wallpaperBitmap, panel.sidebarLeft, panel.sidebarTop,
                frostStrength, wallpaperRotation, panel.displayRotation),
        )
    }

    private fun logicalSidebarSide(geometry: WorkspaceGeometry): SidebarSide =
        geometry.panelSidebarSide

    private fun dp(value: Int): Float = value * context.resources.displayMetrics.density

    private fun geometry(): WorkspaceGeometry = currentGeometry()


    private val transitionInterpolator = WorkspaceTransitionSpec.interpolator

    private companion object {
        const val EXPECTED_WINDOW_COUNT = 4
        const val ACTIVE_SLOT_HIDE_SETTLE_MS = 150L
        const val BACKDROP_SETTLE_REFRESHES = 2
        const val BACKDROP_MAX_RETRIES = 8
        const val HOME_PREVIEW_SETTLE_DELAY_MS = 100L
        const val RESUME_FRAME_FALLBACK_DELAY_MS = 250L
        const val WORKSPACE_ENTER_DURATION_MS = WorkspaceTransitionSpec.DURATION_MS
        const val WORKSPACE_EXIT_DURATION_MS = WorkspaceTransitionSpec.DURATION_MS
        const val SIDEBAR_SWITCH_DURATION_MS = 220L
        const val ENTER_OFFSET_DP = 12
        const val EXIT_OFFSET_DP = 8
        const val SIDEBAR_ENTER_OFFSET_DP = 28
    }
}
