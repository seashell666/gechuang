package com.seashell.gechuang.framework

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.animation.DecelerateInterpolator
import com.seashell.gechuang.logging.GechuangLog
import com.seashell.gechuang.monitoring.WorkspaceEnergyDiagnostics
import com.seashell.gechuang.workspace.WorkspaceGeometry
import com.seashell.gechuang.workspace.WorkspaceNavigationInsets
import com.seashell.gechuang.workspace.WorkspaceTransitionSpec
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Reflective SystemUI/WM Shell bridge for transforming a real task leash.
 *
 * The task remains fullscreen on display 0. Only its compositor leash is scaled and positioned,
 * matching the reference OneStep implementation and avoiding ColorOS freeform re-layout.
 */
object TaskSurfaceCompat {
    private data class SurfaceFrame(
        val scaleX: Float,
        val scaleY: Float,
        val positionX: Float,
        val positionY: Float,
        val crop: Rect? = null,
    )

    private data class ActivePresentation(
        val taskId: Int,
        val leash: Any,
        val scaleX: Float,
        val scaleY: Float,
        val positionX: Float,
        val positionY: Float,
        val reapplyUntil: Long,
        var lastAppliedAt: Long,
        val crop: Rect? = null,
        val fullBounds: Rect? = null,
        val restoring: Boolean = false,
        val geometry: WorkspaceGeometry,
        @Volatile var frame: SurfaceFrame = SurfaceFrame(scaleX, scaleY, positionX, positionY, crop),
    )

    private const val SYSTEM_UI_FACTORY =
        "com.android.systemui.SystemUIAppComponentFactoryBase"
    private const val INITIALIZER_FIELD = "systemUIInitializer"
    private const val ORGANIZER_PROVIDER_FIELD = "provideShellTaskOrganizerProvider"
    private const val TASKS_FIELD = "mTasks"
    private const val LOCK_FIELD = "mLock"

    @Volatile
    private var hostClassLoader: ClassLoader? = null
    @Volatile
    private var cachedOrganizer: Any? = null
    @Volatile
    private var cachedOrganizerLoader: ClassLoader? = null
    @Volatile
    private var activityManager: ActivityManager? = null
    private val activePresentations = ConcurrentHashMap<Int, ActivePresentation>()
    // A remote card animates a parent of the organizer surface. Only that parent
    // carries the workspace transform until Shell reparents the task at finish.
    private val remoteCardOwners = ConcurrentHashMap<Int, Any>()

    @Synchronized
    internal fun startCardAnimation(
        taskId: Int, owner: Any, start: Any, task: Any, parent: Any?, geometry: WorkspaceGeometry,
    ) {
        if (parent !== task) writeFrame(start, task, SurfaceFrame(1f, 1f, 0f, 0f))
        if (parent != null) writeFrame(start, parent, workspaceFrame(geometry))
        remoteCardOwners[taskId] = owner
    }

    @Synchronized
    internal fun finishCardAnimation(
        taskId: Int, owner: Any, finish: Any, task: Any, geometry: WorkspaceGeometry?,
        opening: Boolean,
    ) {
        if (!remoteCardOwners.remove(taskId, owner) || !isValid(task)) return
        val frame = if (geometry != null && (opening || ownsMainTask(taskId))) {
            workspaceFrame(geometry)
        } else SurfaceFrame(1f, 1f, 0f, 0f)
        writeFrame(finish, task, frame)
    }

    private fun workspaceFrame(geometry: WorkspaceGeometry) = SurfaceFrame(
        geometry.contentScale, geometry.contentScale, geometry.contentTranslationX,
        geometry.contentTranslationY, sourceCrop(geometry),
    )
    private val preparedPresentations = ConcurrentHashMap<Int, ActivePresentation>()
    private data class SlotSurface(val displayId: Int, val leash: Any, val crop: Rect) {
        var lastAppliedAt = 0L
    }
    private val slotSurfaces = ConcurrentHashMap<Int, SlotSurface>()
    private val displayExchanges = ConcurrentHashMap<Int, Long>()
    private val exchangeDisplays = ConcurrentHashMap<Int, Int>()
    private val observedStates = ConcurrentHashMap<Int, TaskInfoCompat.WindowState>()
    private val observedLeashes = ConcurrentHashMap<Int, Any>()
    private data class MethodKey(val type: Class<*>, val name: String, val arguments: List<Class<*>?>)
    private val methodCache = ConcurrentHashMap<MethodKey, Method>()
    private val cropMethods = ConcurrentHashMap<Class<*>, Method>()
    private val presentationAnimators = ConcurrentHashMap<Int, ValueAnimator>()
    private val rotationHandoff = DisplayRotationHandoff()
    private val animationHandler by lazy { Handler(Looper.getMainLooper()) }
    private val transactionGuard = SurfaceTransactionGuard<Any>(
        appendOwnedTransforms = ::appendOwnedTransforms,
        onFailure = { GechuangLog.warn("task_surface", "Native transaction fitting failed open", it) },
    )

    fun <R> interceptTransactionApply(transaction: Any, proceed: () -> R): R =
        transactionGuard.intercept(transaction, proceed)

    fun initialize(classLoader: ClassLoader) {
        hostClassLoader = classLoader
        GechuangLog.info("task_surface", "Host class loader captured: $classLoader")
    }

    fun initializeTaskAccess(context: Context) {
        activityManager = context.getSystemService(ActivityManager::class.java)
    }

    fun observeTaskInfo(info: ActivityManager.RunningTaskInfo, leash: Any? = null) {
        TaskInfoCompat.readWindowState(info)?.let { observeTaskState(info.taskId, it) }
        if (leash != null) observedLeashes[info.taskId] = leash
    }

    fun observeTaskState(taskId: Int, state: TaskInfoCompat.WindowState) {
        observedStates[taskId] = state
        slotSurfaces[taskId]?.let { slot ->
            if (slot.displayId != state.displayId || state.vendorWindowed ||
                state.windowingMode != WINDOWING_MODE_FULLSCREEN
            ) slotSurfaces.remove(taskId, slot)
        }
    }

    fun forgetTaskSurface(taskId: Int) {
        observedStates.remove(taskId)
        observedLeashes.remove(taskId)
        slotSurfaces.remove(taskId)
        // An OEM reparent may vanish/reappear with a replacement leash. Preserve
        // the target transform until reapply binds that leash or the owner restores
        // the task. Missing observed ownership prevents commits to the old surface.
    }

    /** Slot tasks render in display-local coordinates. Moving a fitted main task
     * must clear its matrix and position as well as its physical-display crop. */
    fun fitSlotSurface(taskId: Int, displayId: Int, crop: Rect): Result<Unit> = runCatching {
        require(displayId != Display.DEFAULT_DISPLAY)
        // Do not overwrite the outgoing display-0 snapshot before Shell's
        // reparent. WM owns the move/finish; steady slot fitting runs afterwards.
        if (isDisplayExchanging(taskId)) return@runCatching
        val observed = observedStates[taskId]
        val cached = slotSurfaces[taskId]?.takeIf {
            it.displayId == displayId && it.crop == crop && observed?.displayId == displayId &&
                observedLeashes[taskId] === it.leash && isValid(it.leash)
        }
        val slot = cached ?: run {
            val state = findLiveTaskState(taskId) ?: return@runCatching
            if (state.displayId != displayId || state.vendorWindowed ||
                state.windowingMode != WINDOWING_MODE_FULLSCREEN
            ) return@runCatching
            val leash = findTaskLeash(taskId, requireMainDisplay = false) ?: return@runCatching
            if (!isValid(leash)) return@runCatching
            SlotSurface(displayId, leash, Rect(crop)).also { slotSurfaces[taskId] = it }
        }
        val now = SystemClock.uptimeMillis()
        if (now - slot.lastAppliedAt < STEADY_REAPPLY_INTERVAL_MS) return@runCatching
        val loader = checkNotNull(hostClassLoader)
        val transaction = loader.loadClass("android.view.SurfaceControl\$Transaction")
            .getDeclaredConstructor().newInstance()
        transactionGuard.ownedTransaction {
            try {
                writeFrame(transaction, slot.leash, SurfaceFrame(1f, 1f, 0f, 0f, slot.crop))
                invokeRequired(transaction, "apply")
                slot.lastAppliedAt = now
            } finally {
                invokeOptional(transaction, "close")
            }
        }
    }.onFailure { GechuangLog.warn("slot_surface", "Unable to restore slot coordinates taskId=$taskId", it) }

    /** Reserve fitting before a display move, without touching the small-window surface. */
    fun preparePromotion(taskId: Int, geometry: WorkspaceGeometry): Result<Unit> = runCatching {
        val leash = observedLeashes[taskId]?.takeIf(::isValid)
            ?: findTaskLeash(taskId, requireMainDisplay = false)
            ?: error("WM Shell task leash unavailable for promotion taskId=$taskId")
        check(isValid(leash)) { "Invalid promotion leash taskId=$taskId" }
        preparedPresentations[taskId] = ActivePresentation(
            taskId, leash,
            geometry.contentScale, geometry.contentScale,
            geometry.contentTranslationX, geometry.contentTranslationY,
            SystemClock.uptimeMillis() + REAPPLY_WINDOW_MS, 0L,
            crop = sourceCrop(geometry),
            fullBounds = Rect(0, 0, geometry.screenWidth, geometry.screenHeight),
            geometry = geometry,
        )
    }

    fun cancelPreparedPromotion(taskId: Int) {
        preparedPresentations.remove(taskId)
    }

    fun hasPreparedPromotion(taskId: Int): Boolean = preparedPresentations.containsKey(taskId)

    fun markDisplayExchange(taskId: Int, destinationDisplayId: Int) {
        val now = SystemClock.uptimeMillis()
        displayExchanges.entries.removeIf {
            if (it.value >= now) false else {
                exchangeDisplays.remove(it.key)
                true
            }
        }
        exchangeDisplays[taskId] = destinationDisplayId
        displayExchanges[taskId] = now + REAPPLY_WINDOW_MS
    }

    fun isDisplayExchanging(taskId: Int): Boolean =
        (displayExchanges[taskId] ?: 0L) >= SystemClock.uptimeMillis()

    /** ColorOS may omit the parked task from its HOME/display transition. */
    fun hasRecentSlotMove(): Boolean = exchangeDisplays.any { (taskId, destination) ->
        destination != Display.DEFAULT_DISPLAY && isDisplayExchanging(taskId)
    }

    fun cancelDisplayExchange(taskId: Int) {
        displayExchanges.remove(taskId)
        exchangeDisplays.remove(taskId)
        cancelPreparedPromotion(taskId)
    }

    /** Transient exchange preview, using only this task's layers. Secure layers
     * remain excluded by ScreenCapture's defaults; failure just omits the preview. */
    fun capturePreview(taskId: Int, sourceCrop: Rect? = null, allowHomeRoot: Boolean = false): android.graphics.Bitmap? = runCatching {
        WorkspaceEnergyDiagnostics.previewCaptures.incrementAndGet()
        val leash = findTaskLeash(taskId, allowHomeRoot = allowHomeRoot) ?: return@runCatching null
        // Android 14 renamed the internal capture classes. MIUI 14 / Android 13
        // still exposes the ordinary SurfaceControl layer capture API, and using
        // it is the difference between a covered swap and a bare task move.
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            captureA14(leash, sourceCrop) ?: return@runCatching null
        } else {
            captureA13(leash, sourceCrop) ?: return@runCatching null
        }
    }.onFailure { GechuangLog.warn("task_preview", "Task preview unavailable taskId=$taskId", it) }.getOrNull()

    /**
     * Attach a compositor mirror of a display-0 task to a SurfaceView. This is
     * deliberately a layer reference: no screenshot, no Bitmap, no encode/
     * decode, and no task move. MIUI may block the hidden API; callers must
     * keep a capture fallback.
     */
    @SuppressLint("BlockedPrivateApi")
    fun mirrorTaskIntoSurface(
        taskId: Int,
        surfaceView: android.view.SurfaceView,
        destinationWidth: Int,
        destinationHeight: Int,
        sourceBounds: Rect? = null,
        sourceScale: Float = 1f,
        sourceTranslationX: Float = 0f,
        sourceTranslationY: Float = 0f,
    ): Result<Any> = runCatching {
        require(destinationWidth > 0 && destinationHeight > 0)
        val parent = surfaceControlOf(surfaceView)
            ?: error("SurfaceView surface control is unavailable")
        val source = findTaskLeash(taskId, requireMainDisplay = true)
            ?: observedLeashes[taskId]?.takeIf(::isValid)
            ?: error("Main task leash is unavailable taskId=$taskId")
        val bounds = sourceBounds?.takeIf { it.width() > 0 && it.height() > 0 }
            ?: taskBoundsForMirror(taskId)
            ?: Rect(0, 0, destinationWidth, destinationHeight)
        val scType = Class.forName("android.view.SurfaceControl")
        val mirror = scType.getDeclaredMethod("mirrorSurface", scType).run {
            isAccessible = true
            invoke(null, source)
        } ?: error("SurfaceControl.mirrorSurface returned null")
        val txType = Class.forName("android.view.SurfaceControl\$Transaction")
        val transaction = txType.getDeclaredConstructor().newInstance()
        fun call(name: String, vararg args: Any?) {
            val method = txType.methods.firstOrNull { candidate ->
                candidate.name == name && candidate.parameterCount == args.size + 1 &&
                    candidate.parameterTypes.firstOrNull() == scType
            } ?: error("SurfaceControl.Transaction.$name is unavailable")
            method.invoke(transaction, mirror, *args)
        }
        try {
            call("reparent", parent)
            call("setLayer", Int.MAX_VALUE)
            val desiredScaleX = destinationWidth.toFloat() / bounds.width().coerceAtLeast(1)
            val desiredScaleY = destinationHeight.toFloat() / bounds.height().coerceAtLeast(1)
            // TaskSurfaceCompat already fits the main task into the content
            // rectangle. A compositor mirror inherits that transform, so
            // divide it out before applying the rail-preview transform.
            val fitScaleX = desiredScaleX / sourceScale.coerceAtLeast(0.01f)
            val fitScaleY = desiredScaleY / sourceScale.coerceAtLeast(0.01f)
            call("setPosition", -sourceTranslationX * desiredScaleX, -sourceTranslationY * desiredScaleY)
            call("setMatrix", fitScaleX, 0f, 0f, fitScaleY)
            call("setCrop", Rect(0, 0, bounds.width(), bounds.height()))
            txType.getMethod("apply").invoke(transaction)
        } catch (error: Throwable) {
            runCatching { txType.getMethod("close").invoke(transaction) }
            releaseTaskMirror(mirror)
            throw error
        } finally {
            runCatching { txType.getMethod("close").invoke(transaction) }
        }
        mirror
    }

    fun taskBoundsForMirror(taskId: Int): Rect? =
        observedStates[taskId]?.bounds?.takeIf { it.width() > 0 && it.height() > 0 }
            ?: findLiveTaskState(taskId)?.bounds?.takeIf { it.width() > 0 && it.height() > 0 }

    fun releaseTaskMirror(mirror: Any?) {
        if (mirror == null) return
        runCatching {
            val release = mirror.javaClass.getMethod("release")
            release.invoke(mirror)
        }.onFailure { GechuangLog.warn("task_mirror", "Unable to release mirror layer", it) }
    }

    private fun surfaceControlOf(surfaceView: android.view.SurfaceView): Any? = runCatching {
        val method = android.view.SurfaceView::class.java
            .getDeclaredMethod("getSurfaceControl")
            .apply { isAccessible = true }
        method.invoke(surfaceView)
    }.getOrNull()

    private fun captureA14(leash: Any, sourceCrop: Rect?): android.graphics.Bitmap? = runCatching {
        val surfaceType = Class.forName("android.view.SurfaceControl")
        val captureType = Class.forName("android.window.ScreenCaptureInternal")
        val builderType = Class.forName("android.window.ScreenCaptureInternal\$LayerCaptureArgs\$Builder")
        val builder = builderType.getConstructor(surfaceType).newInstance(leash)
        builderType.getMethod("setFrameScale", Float::class.javaPrimitiveType).invoke(builder, 1f)
        builderType.getMethod("setChildrenOnly", Boolean::class.javaPrimitiveType).invoke(builder, true)
        sourceCrop?.let {
            builderType.getMethod("setSourceCrop", Rect::class.java).invoke(builder, it)
        }
        val args = builderType.getMethod("build").invoke(builder)
        val buffer = captureType.methods.firstOrNull {
            it.name == "captureLayers" && it.parameterCount == 1 && it.parameterTypes[0].isInstance(args)
        }?.invoke(null, args) ?: return@runCatching null
        bufferToBitmap(buffer)
    }.getOrNull()

    private fun captureA13(leash: Any, sourceCrop: Rect?): android.graphics.Bitmap? = runCatching {
        val loader = hostClassLoader ?: return@runCatching null
        val surfaceType = Class.forName("android.view.SurfaceControl", false, loader)
        val capture = allMethods(surfaceType).firstOrNull {
            it.name == "captureLayers" && it.parameterCount == 3 &&
                it.parameterTypes[0] == surfaceType &&
                it.parameterTypes[1] == Rect::class.java &&
                it.parameterTypes[2] == Float::class.javaPrimitiveType
        } ?: return@runCatching null
        capture.isAccessible = true
        val buffer = capture.invoke(null, leash, sourceCrop, 1f)
            ?: return@runCatching null
        val bitmap = bufferToBitmap(buffer) ?: return@runCatching null
        // MIUI 13's layer-capture overload returns the task's full display frame
        // even when a source crop is supplied. Trim it so the preview contains
        // only the workspace viewport, not the top bar and side slots.
        cropToSource(bitmap, sourceCrop).also {
            GechuangLog.info("task_preview", "Android 13 task capture ${it.width}x${it.height}")
        }
    }.onFailure {
        GechuangLog.warn("task_preview", "Android 13 task capture unavailable", it)
    }.getOrNull()

    /** Capture the composed viewport so HOME keeps its rendered wallpaper zoom and offsets. */
    internal fun captureDisplayPreview(sourceCrop: Rect): android.graphics.Bitmap? = runCatching {
        WorkspaceEnergyDiagnostics.previewCaptures.incrementAndGet()
        if (sourceCrop.isEmpty) return@runCatching null
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            captureDisplayA14(sourceCrop) ?: return@runCatching null
        } else {
            captureDisplayA13(sourceCrop) ?: return@runCatching null
        }
    }.onFailure { GechuangLog.warn("task_preview", "Display preview unavailable", it) }.getOrNull()

    private fun captureDisplayA14(sourceCrop: Rect): android.graphics.Bitmap? = runCatching {
        val captureType = Class.forName("android.window.ScreenCaptureInternal")
        val builderType = Class.forName("android.window.ScreenCaptureInternal\$CaptureArgs\$Builder")
        val builder = builderType.getConstructor().newInstance()
        builderType.getMethod("setSourceCrop", Rect::class.java).invoke(builder, sourceCrop)
        builderType.getMethod("setFrameScale", Float::class.javaPrimitiveType).invoke(builder, 1f)
        val args = builderType.getMethod("build").invoke(builder)
        val listener = captureType.getMethod("createSyncCaptureListener").invoke(null)
            ?: return@runCatching null
        val service = Class.forName("android.view.WindowManagerGlobal")
            .getDeclaredMethod("getWindowManagerService").apply { isAccessible = true }.invoke(null)
            ?: return@runCatching null
        // Capture the logical display so crop coordinates follow its current rotation.
        // Keep the platform defaults that exclude secure and protected layers.
        val capture = Class.forName("android.view.IWindowManager").methods.firstOrNull {
            it.name == "captureDisplay" && it.parameterCount == 3 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1].isInstance(args) && it.parameterTypes[2].isInstance(listener)
        } ?: return@runCatching null
        capture.invoke(service, Display.DEFAULT_DISPLAY, args, listener)
        val buffer = invokeRequired(listener, "getBuffer") ?: return@runCatching null
        bufferToBitmap(buffer)
    }.getOrNull()

    private fun captureDisplayA13(sourceCrop: Rect): android.graphics.Bitmap? = runCatching {
        val loader = hostClassLoader ?: return@runCatching null
        val surfaceType = Class.forName("android.view.SurfaceControl", false, loader)
        val token = allMethods(surfaceType).firstOrNull {
            it.name == "getInternalDisplayToken" && it.parameterCount == 0
        }?.apply { isAccessible = true }?.invoke(null)
            ?: return@runCatching null
        val builderType = Class.forName(
            "android.view.SurfaceControl\$DisplayCaptureArgs\$Builder", false, loader,
        )
        val builder = builderType.getConstructor(android.os.IBinder::class.java).newInstance(token)
        allMethods(builderType).firstOrNull {
            it.name == "setSourceCrop" && it.parameterCount == 1 &&
                it.parameterTypes[0] == Rect::class.java
        }?.apply { isAccessible = true }?.invoke(builder, sourceCrop)
            ?: return@runCatching null
        allMethods(builderType).firstOrNull {
            it.name == "setFrameScale" && it.parameterCount == 1 &&
                it.parameterTypes[0] == Float::class.javaPrimitiveType
        }?.apply { isAccessible = true }?.invoke(builder, 1f)
            ?: return@runCatching null
        val args = allMethods(builderType).firstOrNull {
            it.name == "build" && it.parameterCount == 0
        }?.apply { isAccessible = true }?.invoke(builder)
            ?: return@runCatching null
        val capture = allMethods(surfaceType).firstOrNull {
            it.name == "captureDisplay" && it.parameterCount == 1 &&
                it.parameterTypes[0].isInstance(args)
        } ?: return@runCatching null
        capture.isAccessible = true
        val buffer = capture.invoke(null, args) ?: return@runCatching null
        val bitmap = bufferToBitmap(buffer) ?: return@runCatching null
        cropToSource(bitmap, sourceCrop).also {
            GechuangLog.info("task_preview", "Android 13 display capture ${it.width}x${it.height}")
        }
    }.onFailure {
        GechuangLog.warn("task_preview", "Android 13 display capture unavailable", it)
    }.getOrNull()

    private fun cropToSource(
        bitmap: android.graphics.Bitmap,
        source: Rect?,
    ): android.graphics.Bitmap {
        GechuangLog.info(
            "task_preview",
            "crop source=$source bitmap=${bitmap.width}x${bitmap.height}",
        )
        if (source == null || source.isEmpty) return bitmap
        if (bitmap.width == source.width() && bitmap.height == source.height()) return bitmap
        val crop = Rect(source)
        crop.intersect(0, 0, bitmap.width, bitmap.height)
        if (crop.isEmpty || (crop.width() == bitmap.width && crop.height() == bitmap.height)) {
            return bitmap
        }
        return runCatching {
            android.graphics.Bitmap.createBitmap(
                bitmap, crop.left, crop.top, crop.width(), crop.height(),
            ).also { bitmap.recycle() }
        }.getOrElse { bitmap }
    }

    private fun bufferToBitmap(buffer: Any): android.graphics.Bitmap? {
        val hardwareBuffer = runCatching {
            invokeRequired(buffer, "getHardwareBuffer") as? android.hardware.HardwareBuffer
        }.getOrNull()
        return try {
            val source = invokeRequired(buffer, "asBitmap") as? android.graphics.Bitmap
                ?: return null
            try {
                source.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
            } finally {
                source.recycle()
            }
        } finally {
            hardwareBuffer?.close()
        }
    }

    fun present(
        taskId: Int,
        geometry: WorkspaceGeometry,
        animateFromFullscreen: Boolean = false,
    ): Result<Unit> = runCatching {
        require(taskId > 0) { "Invalid taskId=$taskId" }
        // Recovery polling must not restart entry on an already presented task.
        val previous = activePresentations[taskId]
        if (previous != null && !previous.restoring) {
            reapply(taskId, geometry).getOrThrow()
            return@runCatching
        }
        val leash = findTaskLeash(taskId)
            ?: error("WM Shell task leash unavailable for taskId=$taskId")
        check(isValid(leash)) { "WM Shell task leash is invalid for taskId=$taskId" }

        val scaleX = geometry.contentScale
        val scaleY = scaleX
        val now = SystemClock.uptimeMillis()
        val presentation = ActivePresentation(
            taskId = taskId,
            leash = leash,
            scaleX = scaleX,
            scaleY = scaleY,
            positionX = geometry.contentTranslationX,
            positionY = geometry.contentTranslationY,
            reapplyUntil = now + REAPPLY_WINDOW_MS,
            lastAppliedAt = now,
            crop = sourceCrop(geometry),
            fullBounds = Rect(0, 0, geometry.screenWidth, geometry.screenHeight),
            geometry = geometry,
        )
        val prepared = preparedPresentations[taskId]
        activePresentations[taskId] = presentation
        preparedPresentations.remove(taskId)
        if (remoteCardOwners.containsKey(taskId) || !ValueAnimator.areAnimatorsEnabled()) {
            cancelPresentationAnimation(taskId)
            applyPresentation(presentation)
        } else if (previous?.restoring == true || (animateFromFullscreen && prepared == null)) {
            animatePresentation(
                taskId,
                previous?.frame ?: SurfaceFrame(1f, 1f, 0f, 0f),
                presentation,
                WorkspaceTransitionSpec.DURATION_MS,
            )
        } else if (prepared != null || geometry.navigationInsets != WorkspaceNavigationInsets()) {
            // Promotion and recorded-button cold launches fit their native first
            // frame already. Do not shrink it again with a second entry zoom.
            applyPresentation(presentation)
        } else animatePresentation(
            taskId = taskId,
            from = presentation.frame.copy(
                scaleX = presentation.scaleX * PRESENT_ENTER_SCALE,
                scaleY = presentation.scaleY * PRESENT_ENTER_SCALE,
                positionX = presentation.positionX + PRESENT_ENTER_OFFSET_PX,
                positionY = presentation.positionY + PRESENT_ENTER_OFFSET_PX,
            ),
            to = presentation,
            durationMs = PRESENT_DURATION_MS,
        )
        GechuangLog.info(
            "task_surface",
            "Presented taskId=$taskId scale=$scaleX,$scaleY " +
                "position=${geometry.contentLeft},${geometry.contentTop}",
        )
    }.onFailure { error ->
        GechuangLog.error("task_surface", "Presentation failed taskId=$taskId", error)
    }

    fun reapply(taskId: Int, geometry: WorkspaceGeometry): Result<Unit> = runCatching {
        val previous = activePresentations[taskId]
            ?: error("No active surface presentation for taskId=$taskId")
        val now = SystemClock.uptimeMillis()
        if (rotationHandoff.holds(taskId, now)) return@runCatching
        val scaleX = geometry.contentScale
        val scaleY = scaleX
        val crop = sourceCrop(geometry)
        val rotationChanged = previous.geometry.displayRotation != geometry.displayRotation
        val geometryChanged = rotationChanged || previous.scaleX != scaleX ||
            previous.scaleY != scaleY ||
            previous.positionX != geometry.contentTranslationX ||
            previous.positionY != geometry.contentTranslationY || previous.crop != crop
        if (!geometryChanged && now - previous.lastAppliedAt < MIN_REAPPLY_INTERVAL_MS) {
            return@runCatching
        }
        if (!geometryChanged && now > previous.reapplyUntil &&
            now - previous.lastAppliedAt < STEADY_REAPPLY_INTERVAL_MS
        ) {
            return@runCatching
        }
        val currentLeash = findTaskLeash(taskId)
            ?: error("WM Shell task leash unavailable for taskId=$taskId")
        check(isValid(currentLeash)) {
            "WM Shell task leash is invalid for taskId=$taskId"
        }
        val leashChanged = currentLeash !== previous.leash
        val presentation = if (leashChanged || geometryChanged) {
            ActivePresentation(
                taskId = taskId,
                leash = currentLeash,
                scaleX = scaleX,
                scaleY = scaleY,
                positionX = geometry.contentTranslationX,
                positionY = geometry.contentTranslationY,
                reapplyUntil = now + REAPPLY_WINDOW_MS,
                lastAppliedAt = 0L,
                crop = crop,
                fullBounds = Rect(0, 0, geometry.screenWidth, geometry.screenHeight),
                geometry = geometry,
            ).also { activePresentations[taskId] = it }
        } else {
            previous
        }
        if (leashChanged || rotationChanged) {
            // Rotation changes the coordinate space; an old animation frame must
            // never interpolate across it or overwrite a replacement leash.
            cancelPresentationAnimation(taskId)
            applyPresentation(presentation)
        } else if (geometryChanged) {
            animatePresentation(taskId, previous.frame, presentation, REPOSITION_DURATION_MS)
        } else if (!presentationAnimators.containsKey(taskId)) {
            applyPresentation(presentation)
        }
        presentation.lastAppliedAt = now
        if (leashChanged) {
            GechuangLog.info("task_surface", "Rebound changed leash taskId=$taskId")
        }
    }.onFailure { error ->
        GechuangLog.error("task_surface", "Reapply failed taskId=$taskId", error)
    }

    fun ownsMainTask(taskId: Int): Boolean = activePresentations[taskId]?.let {
        !it.restoring && ownedTaskLeash(taskId) === it.leash
    } == true

    /** Activity-only transitions may refer to the owned task only through its parent token. */
    internal fun ownsMainContainer(container: Any?): Boolean = container != null &&
        activePresentations.keys.any { taskId ->
            ownsMainTask(taskId) && observedStates[taskId]?.token == container
        }

    fun beginDisplayRotation(rotation: Int, start: Any, finish: Any): Long? {
        val tasks = activePresentations.keys.filter(::ownsMainTask).toSet()
        if (tasks.isEmpty()) return null
        val ticket = rotationHandoff.begin(rotation, tasks, SystemClock.uptimeMillis())
        // The native start transaction changes the display coordinate space.
        // Fit in that same transaction, then hold polling until the viewport has
        // caught up so an old configuration cannot put the task back in a corner.
        stageDisplayRotation(rotation)
        appendOwnedTransforms(start)
        appendOwnedTransforms(finish)
        return ticket.generation
    }

    fun isDisplayRotationPending(generation: Long): Boolean = rotationHandoff.ticket(generation) != null

    fun finishDisplayRotation(generation: Long, transaction: Any): Boolean {
        val ticket = rotationHandoff.ticket(generation) ?: return false
        return stageDisplayRotation(ticket.rotation, transaction)
    }

    fun completeDisplayRotation(generation: Long) = rotationHandoff.complete(generation)

    /** Include the target placement in the transaction for the replacement panel frame. */
    fun stageDisplayRotation(rotation: Int, transaction: Any? = null): Boolean {
        var retained = false
        activePresentations.forEach { (taskId, previous) ->
            if (!ownsMainTask(taskId)) return@forEach
            retained = true
            if (previous.geometry.displayRotation == rotation) return@forEach
            val geometry = previous.geometry.rotatedTo(rotation)
            val crop = sourceCrop(geometry)
            val next = previous.copy(
                scaleX = geometry.contentScale, scaleY = geometry.contentScale,
                positionX = geometry.contentTranslationX, positionY = geometry.contentTranslationY,
                crop = crop, fullBounds = Rect(0, 0, geometry.screenWidth, geometry.screenHeight),
                geometry = geometry, lastAppliedAt = 0L,
                reapplyUntil = SystemClock.uptimeMillis() + REAPPLY_WINDOW_MS,
                frame = SurfaceFrame(geometry.contentScale, geometry.contentScale,
                    geometry.contentTranslationX, geometry.contentTranslationY, crop),
            )
            if (activePresentations.replace(taskId, previous, next)) {
                // This hook runs on Shell's looper; cancel only this old animator
                // on its own looper. Its updates already reject the replaced owner.
                presentationAnimators.remove(taskId)?.let { animator ->
                    if (Looper.myLooper() == Looper.getMainLooper()) animator.cancel()
                    else animationHandler.post { animator.cancel() }
                }
            }
        }
        // Draw-synchronized transactions can be submitted outside SystemUI's apply hook.
        // Include our matrix in that transaction explicitly instead of relying on
        // the SystemUI Transaction.apply hook to see the eventual commit.
        if (retained && transaction != null) appendOwnedTransforms(transaction)
        return retained
    }

    fun restore(taskId: Int, animateToFullscreen: Boolean = false): Result<Unit> = runCatching {
        val previous = activePresentations[taskId]
        if (animateToFullscreen && !remoteCardOwners.containsKey(taskId) && previous != null && isValid(previous.leash) &&
            ownedTaskLeash(taskId) === previous.leash && ValueAnimator.areAnimatorsEnabled()
        ) {
            preparedPresentations.remove(taskId)
            val restored = previous.copy(
                scaleX = 1f, scaleY = 1f, positionX = 0f, positionY = 0f,
                crop = null, restoring = true,
                frame = SurfaceFrame(1f, 1f, 0f, 0f),
            )
            activePresentations[taskId] = restored
            animatePresentation(taskId, previous.frame, restored, WorkspaceTransitionSpec.DURATION_MS)
            return@runCatching
        }
        // Remove ownership before cancellation so no final frame can revive the transform.
        val pending = preparedPresentations.remove(taskId)
        val leash = activePresentations.remove(taskId)?.leash ?: pending?.leash ?: return@runCatching
        cancelPresentationAnimation(taskId)
        if (!isValid(leash)) {
            GechuangLog.warn(
                "task_surface",
                "Restore skipped because task leash is gone taskId=$taskId",
            )
            return@runCatching
        }
        applyPresentation(
            taskId = taskId,
            leash = leash,
            scaleX = 1f,
            scaleY = 1f,
            positionX = 0f,
            positionY = 0f,
        )
        GechuangLog.info("task_surface", "Restored taskId=$taskId")
    }.onFailure { error ->
        GechuangLog.error("task_surface", "Restore failed taskId=$taskId", error)
    }

    /** Locking must release an exit animation even after its presenter relinquished ownership. */
    fun finishWorkspaceExitAnimations() {
        activePresentations.values.filter { it.restoring }.forEach { restore(it.taskId) }
    }

    private fun applyPresentation(presentation: ActivePresentation) {
        applyPresentation(
            taskId = presentation.taskId,
            leash = presentation.leash,
            scaleX = presentation.scaleX,
            scaleY = presentation.scaleY,
            positionX = presentation.positionX,
            positionY = presentation.positionY,
            crop = presentation.crop,
        )
    }

    private fun animatePresentation(
        taskId: Int,
        from: SurfaceFrame,
        to: ActivePresentation,
        durationMs: Long,
    ) {
        cancelPresentationAnimation(taskId)
        applyPresentation(
            taskId = taskId,
            leash = to.leash,
            scaleX = from.scaleX,
            scaleY = from.scaleY,
            positionX = from.positionX,
            positionY = from.positionY,
            crop = from.crop,
        )
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = durationMs
        animator.interpolator = if (durationMs == WorkspaceTransitionSpec.DURATION_MS) {
            WorkspaceTransitionSpec.interpolator
        } else DecelerateInterpolator(1.6f)
        animator.addUpdateListener { valueAnimator ->
            if (activePresentations[taskId] !== to) {
                cancelPresentationAnimation(taskId)
                return@addUpdateListener
            }
            val progress = valueAnimator.animatedValue as Float
            runCatching {
                applyPresentation(
                    taskId = taskId,
                    leash = to.leash,
                    scaleX = lerp(from.scaleX, to.scaleX, progress),
                    scaleY = lerp(from.scaleY, to.scaleY, progress),
                    positionX = lerp(from.positionX, to.positionX, progress),
                    positionY = lerp(from.positionY, to.positionY, progress),
                    crop = interpolateCrop(from.crop, to.crop, to.fullBounds, progress),
                )
            }.onFailure { error ->
                valueAnimator.cancel()
                GechuangLog.warn(
                    "task_surface_animation",
                    "Frame apply failed taskId=$taskId",
                    error,
                )
            }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (presentationAnimators.remove(taskId, animator) &&
                    activePresentations[taskId] === to
                ) {
                    runCatching { applyPresentation(to) }.onFailure { error ->
                        GechuangLog.warn(
                            "task_surface_animation",
                            "Final frame apply failed taskId=$taskId",
                            error,
                        )
                    }
                    if (to.restoring) activePresentations.remove(taskId, to)
                }
            }
        })
        presentationAnimators[taskId] = animator
        if (durationMs == WorkspaceTransitionSpec.DURATION_MS) {
            // Enter/show and exit/slot parking can perform synchronous window work.
            // Start the clock after that work, retaining the current frame meanwhile.
            animationHandler.post {
                if (presentationAnimators[taskId] === animator && activePresentations[taskId] === to) animator.start()
            }
        } else animator.start()
    }

    private fun cancelPresentationAnimation(taskId: Int) {
        presentationAnimators.remove(taskId)?.cancel()
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress

    private fun interpolateCrop(from: Rect?, to: Rect?, fullBounds: Rect?, progress: Float): Rect? {
        if (progress >= 1f) return to
        val start = from ?: fullBounds ?: return to
        val end = to ?: fullBounds ?: return from
        return Rect(
            lerp(start.left.toFloat(), end.left.toFloat(), progress).toInt(),
            lerp(start.top.toFloat(), end.top.toFloat(), progress).toInt(),
            lerp(start.right.toFloat(), end.right.toFloat(), progress).toInt(),
            lerp(start.bottom.toFloat(), end.bottom.toFloat(), progress).toInt(),
        )
    }

    @Synchronized
    private fun applyPresentation(
        taskId: Int,
        leash: Any,
        scaleX: Float,
        scaleY: Float,
        positionX: Float,
        positionY: Float,
        crop: Rect? = null,
    ) {
        // Frame commits use observed ownership, never Binder or the organizer lock.
        if (remoteCardOwners.containsKey(taskId) || ownedTaskLeash(taskId) !== leash) return
        val frame = SurfaceFrame(scaleX, scaleY, positionX, positionY, crop)
        activePresentations[taskId]?.takeIf { it.leash === leash }?.frame = frame
        val loader = hostClassLoader ?: error("SystemUI host class loader is unavailable")
        val transactionClass = Class.forName(
            "android.view.SurfaceControl\$Transaction",
            false,
            loader,
        )
        val transaction = transactionClass.getDeclaredConstructor().apply {
            isAccessible = true
        }.newInstance()
        transactionGuard.ownedTransaction {
            try {
                writeFrame(transaction, leash, frame)
                invokeRequired(transaction, "apply")
            } finally {
                invokeOptional(transaction, "close")
            }
        }
    }

    @Synchronized
    private fun appendOwnedTransforms(transaction: Any) {
        // WM Shell's animation and finish transactions can be queued before presentation.
        // Fit at commit, after their writes, so no identity-sized frame reaches the display
        // between polling corrections. Use the current animation frame, not its final target.
        activePresentations.values.forEach { presentation ->
            if (remoteCardOwners.containsKey(presentation.taskId) || activePresentations[presentation.taskId] !== presentation ||
                ownedTaskLeash(presentation.taskId) !== presentation.leash ||
                !isValid(presentation.leash)
            ) return@forEach
            writeFrame(transaction, presentation.leash, presentation.frame)
        }
        preparedPresentations.values.forEach { presentation ->
            if (SystemClock.uptimeMillis() > presentation.reapplyUntil) {
                preparedPresentations.remove(presentation.taskId, presentation)
                return@forEach
            }
            // Never scale its virtual-display surface. The live state changes before
            // WM Shell submits the start transaction that makes the promoted task visible.
            if (remoteCardOwners.containsKey(presentation.taskId) || preparedPresentations[presentation.taskId] !== presentation ||
                ownedTaskLeash(presentation.taskId) !== presentation.leash ||
                !isValid(presentation.leash)
            ) return@forEach
            writeFrame(transaction, presentation.leash, presentation.frame)
        }
        slotSurfaces.forEach { (taskId, slot) ->
            val state = observedStates[taskId] ?: return@forEach
            if (slotSurfaces[taskId] != slot || state.displayId != slot.displayId ||
                state.vendorWindowed || state.windowingMode != WINDOWING_MODE_FULLSCREEN ||
                observedLeashes[taskId] !== slot.leash || !isValid(slot.leash) ||
                isDisplayExchanging(taskId)
            ) return@forEach
            // A queued Shell finish may still contain the former main transform.
            // Preserve its visibility/layer writes, but commit local slot coordinates.
            writeFrame(transaction, slot.leash, SurfaceFrame(1f, 1f, 0f, 0f, slot.crop))
        }
    }

    private fun ownedTaskLeash(taskId: Int): Any? {
        val state = observedStates[taskId] ?: return null
        if (state.vendorWindowed || state.displayId != Display.DEFAULT_DISPLAY ||
            state.windowingMode != WINDOWING_MODE_FULLSCREEN ||
            (isDisplayExchanging(taskId) && exchangeDisplays[taskId] != Display.DEFAULT_DISPLAY)
        ) return null
        return observedLeashes[taskId]
    }

    private fun writeFrame(transaction: Any, leash: Any, frame: SurfaceFrame) {
        setCrop(transaction, leash, frame.crop)
        // Keep native visibility, fade, layer order and parenting intact.
        if (!invokeOptional(transaction, "setMatrix", leash, frame.scaleX, 0f, 0f, frame.scaleY)) {
            invokeRequired(transaction, "setScale", leash, frame.scaleX, frame.scaleY)
        }
        invokeRequired(transaction, "setPosition", leash, frame.positionX, frame.positionY)
    }

    private fun sourceCrop(geometry: WorkspaceGeometry) = Rect(
        geometry.availableLeft, geometry.availableTop, geometry.availableRight, geometry.availableBottom,
    )

    private fun setCrop(transaction: Any, leash: Any, crop: Rect?) {
        val candidate = cropMethods[transaction.javaClass] ?: allMethods(transaction.javaClass).firstOrNull { method ->
            method.name in setOf("setWindowCrop", "setCrop") &&
                method.parameterTypes.size == 2 &&
                !method.parameterTypes[1].isPrimitive
        }?.also { cropMethods[transaction.javaClass] = it } ?: return
        runCatching {
            candidate.isAccessible = true
            candidate.invoke(transaction, leash, crop)
        }.onFailure { error ->
            GechuangLog.warn("task_surface", "Unable to set task crop", error)
        }
    }

    private fun findTaskLeash(taskId: Int, requireMainDisplay: Boolean = true, allowHomeRoot: Boolean = false): Any? = runCatching {
        val loader = hostClassLoader ?: error("SystemUI host class loader is unavailable")
        val organizer = organizerFor(loader) ?: error("ShellTaskOrganizer provider is unavailable")
        val tasks = findField(organizer.javaClass, TASKS_FIELD).get(organizer)
            ?: error("ShellTaskOrganizer task map is null")
        val lock = allFields(organizer.javaClass)
            .firstOrNull { it.name == LOCK_FIELD }
            ?.let { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(organizer)
                }.getOrNull()
            }
            ?: organizer
        val appearedInfo = synchronized(lock) {
            invokeRequired(tasks, "get", taskId)
                ?: if (allowHomeRoot) findHomeRoot(tasks, taskId) else null
        } ?: error("Task $taskId is absent from ShellTaskOrganizer")
        // Shell's TaskAppearedInfo is delivered asynchronously. After a display move its
        // cached TaskInfo can still describe the slot, causing a successful promotion to
        // roll back. The same cache can also outlive a move into an OEM floating window.
        // Bootstrap with a live query; commits use callback and coordinator observations.
        if (requireMainDisplay) {
            val state = findLiveTaskState(taskId) ?: return@runCatching null
            if (state.vendorWindowed || state.displayId != Display.DEFAULT_DISPLAY ||
                state.windowingMode != WINDOWING_MODE_FULLSCREEN
            ) {
                return@runCatching null
            }
        }
        val leash = invokeRequired(appearedInfo, "getLeash")
            ?: error("Task $taskId leash is null")
        observedLeashes[taskId] = leash
        leash
    }.onFailure { error ->
        GechuangLog.warn("task_surface", "Task leash lookup failed taskId=$taskId", error)
    }.getOrNull()

    /** Shell's organizer is process-stable. Resolving it on every reapply used to
     * repeat class loading, factory reflection and a stack trace every second,
     * which is measurable main-thread work while a task is scrolling. */
    @Synchronized
    private fun organizerFor(loader: ClassLoader): Any? {
        cachedOrganizer?.takeIf {
            cachedOrganizerLoader === loader && hasField(it.javaClass, TASKS_FIELD)
        }?.let { return it }
        val wmComponent = runCatching {
            val factoryClass = loader.loadClass(SYSTEM_UI_FACTORY)
            val initializer = findField(factoryClass, INITIALIZER_FIELD).get(null)
                ?: error("SystemUI initializer is null")
            invokeRequired(initializer, "getWMComponent")
                ?: error("SystemUI WM component is null")
        }.getOrElse { error ->
            GechuangLog.info(
                "task_surface",
                "A14 initializer unavailable, falling back to Android 13 factory: ${error.message}",
            )
            resolveWmComponentA13(loader)
                ?: error("SystemUI WM component is null")
        }
        val organizer = resolveTaskOrganizer(wmComponent)
            ?: resolveOrganizerA13(wmComponent)
            ?: return null
        cachedOrganizer = organizer
        cachedOrganizerLoader = loader
        return organizer
    }

    private fun findHomeRoot(tasks: Any, taskId: Int): Any? {
        // ColorOS reports the launcher child through ActivityManager but only
        // its HOME root through ShellTaskOrganizer. Resolve that same HOME
        // explicitly; ordinary app previews must never fall back to another task.
        WorkspaceEnergyDiagnostics.taskQueries.incrementAndGet()
        val home = activityManager?.getRunningTasks(MAX_TASK_QUERY)?.firstOrNull { it.taskId == taskId }
            ?: return null
        val homeState = TaskInfoCompat.readWindowState(home) ?: return null
        if (homeState.displayId != Display.DEFAULT_DISPLAY) return null
        val count = invokeRequired(tasks, "size") as? Int ?: return null
        for (index in 0 until count) {
            val appeared = invokeRequired(tasks, "valueAt", index) ?: continue
            val info = invokeRequired(appeared, "getTaskInfo") as? ActivityManager.RunningTaskInfo ?: continue
            if (TaskInfoCompat.readWindowState(info)?.displayId == homeState.displayId && info.topActivity == home.topActivity &&
                invokeRequired(info, "getActivityType") == 2
            ) return appeared
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun findLiveTaskState(taskId: Int): TaskInfoCompat.WindowState? {
        val manager = checkNotNull(activityManager) { "Live task access is unavailable" }
        WorkspaceEnergyDiagnostics.taskQueries.incrementAndGet()
        val taskInfo = manager.getRunningTasks(MAX_TASK_QUERY).firstOrNull { it.taskId == taskId }
            ?: return null
        return TaskInfoCompat.readWindowState(taskInfo)?.also { observeTaskState(taskId, it) }
    }

    /** Android 13 / MIUI exposes the WM shell through the classic SystemUIFactory. */
    private fun resolveWmComponentA13(loader: ClassLoader): Any? = runCatching {
        val factoryClass = loader.loadClass("com.android.systemui.SystemUIFactory")
        val factory = factoryClass.getMethod("getInstance").invoke(null)
            ?: error("SystemUIFactory.getInstance returned null")
        val field = findField(factoryClass, "mWMComponent")
        field.get(factory) ?: error("SystemUIFactory mWMComponent is null")
    }.onFailure { error ->
        GechuangLog.warn("task_surface", "Android 13 WM component unavailable", error)
    }.getOrNull()

    private fun resolveOrganizerA13(wmComponent: Any): Any? {
        val shellInit = runCatching { invokeRequired(wmComponent, "getShellInit") }.getOrNull()
        if (shellInit == null) {
            GechuangLog.warn("task_surface", "Android 13 ShellInit unavailable")
            return null
        }
        var owner: Any? = shellInit
        repeat(2) {
            val current = owner ?: return@repeat
            val organizerField = allFields(current.javaClass)
                .firstOrNull { it.name == "mShellTaskOrganizer" }
            if (organizerField != null) {
                organizerField.isAccessible = true
                val organizer = runCatching { organizerField.get(current) }.getOrNull()
                if (organizer != null && hasField(organizer.javaClass, TASKS_FIELD)) {
                    GechuangLog.info(
                        "task_surface",
                        "Resolved Android 13 ShellTaskOrganizer: ${organizer.javaClass.name}",
                    )
                    return organizer
                }
            }
            owner = allFields(current.javaClass).firstOrNull { it.name == "this\$0" }?.let { field ->
                field.isAccessible = true
                runCatching { field.get(current) }.getOrNull()
            }
        }
        return null
    }

    private fun resolveTaskOrganizer(wmComponent: Any): Any? {
        runCatching {
            val provider = findField(wmComponent.javaClass, ORGANIZER_PROVIDER_FIELD)
                .get(wmComponent)
            val organizer = provider?.let { invokeRequired(it, "get") }
            if (organizer != null && hasField(organizer.javaClass, TASKS_FIELD)) {
                return organizer
            }
        }

        allFields(wmComponent.javaClass).forEach { field ->
            val value = runCatching {
                field.isAccessible = true
                field.get(wmComponent)
            }.getOrNull() ?: return@forEach
            if (hasField(value.javaClass, TASKS_FIELD)) return value
            if (field.name.contains("TaskOrganizer", ignoreCase = true) ||
                value.javaClass.name.contains("Provider", ignoreCase = true)
            ) {
                val provided = runCatching { invokeRequired(value, "get") }.getOrNull()
                if (provided != null && hasField(provided.javaClass, TASKS_FIELD)) {
                    return provided
                }
            }
        }

        allMethods(wmComponent.javaClass)
            .filter { method ->
                method.parameterTypes.isEmpty() &&
                    method.name.contains("TaskOrganizer", ignoreCase = true)
            }
            .forEach { method ->
                val organizer = runCatching {
                    method.isAccessible = true
                    method.invoke(wmComponent)
                }.getOrNull()
                if (organizer != null && hasField(organizer.javaClass, TASKS_FIELD)) {
                    return organizer
                }
            }
        return null
    }

    private fun isValid(leash: Any): Boolean =
        (runCatching { invokeRequired(leash, "isValid") }.getOrNull() as? Boolean) == true

    private fun invokeRequired(target: Any, name: String, vararg arguments: Any?): Any? {
        val method = findCompatibleMethod(target.javaClass, name, arguments)
            ?: error("No compatible ${target.javaClass.name}#$name candidate")
        method.isAccessible = true
        return method.invoke(target, *arguments)
    }

    private fun invokeOptional(target: Any, name: String, vararg arguments: Any?): Boolean {
        val method = findCompatibleMethod(target.javaClass, name, arguments) ?: return false
        return runCatching {
            method.isAccessible = true
            method.invoke(target, *arguments)
        }.isSuccess
    }

    private fun findCompatibleMethod(
        startType: Class<*>,
        name: String,
        arguments: Array<out Any?>,
    ): Method? {
        val key = MethodKey(startType, name, arguments.map { it?.javaClass })
        methodCache[key]?.let { return it }
        return allMethods(startType).firstOrNull { candidate ->
            candidate.name == name &&
                candidate.parameterTypes.size == arguments.size &&
                candidate.parameterTypes.indices.all { index ->
                    val argument = arguments[index]
                    if (argument == null) {
                        !candidate.parameterTypes[index].isPrimitive
                    } else {
                        wraps(candidate.parameterTypes[index]).isAssignableFrom(argument.javaClass)
                    }
                }
        }?.also { methodCache[key] = it }
    }

    private fun findField(type: Class<*>, name: String): Field =
        allFields(type).firstOrNull { it.name == name }?.apply { isAccessible = true }
            ?: error("No field ${type.name}#$name")

    private fun hasField(type: Class<*>, name: String): Boolean =
        allFields(type).any { it.name == name }

    private fun allFields(startType: Class<*>): Sequence<Field> = sequence {
        var type: Class<*>? = startType
        while (type != null && type != Any::class.java) {
            yieldAll(type.declaredFields.asSequence())
            type = type.superclass
        }
    }

    private fun allMethods(startType: Class<*>): Sequence<Method> = sequence {
        var type: Class<*>? = startType
        while (type != null && type != Any::class.java) {
            yieldAll(type.declaredMethods.asSequence())
            type = type.superclass
        }
    }

    private fun wraps(type: Class<*>): Class<*> = when (type) {
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Float::class.javaPrimitiveType -> Float::class.javaObjectType
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        else -> type
    }

    private const val REAPPLY_WINDOW_MS = 2_000L
    private const val MAX_TASK_QUERY = 64
    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val MIN_REAPPLY_INTERVAL_MS = 120L
    private const val STEADY_REAPPLY_INTERVAL_MS = 900L
    private const val PRESENT_DURATION_MS = 240L
    private const val REPOSITION_DURATION_MS = 260L
    private const val PRESENT_ENTER_SCALE = 0.96f
    private const val PRESENT_ENTER_OFFSET_PX = 10f
}
