package com.seashell.gechuang.display

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.display.VirtualDisplay
import android.view.Surface
import android.view.TextureView
import com.seashell.gechuang.framework.UserTargetedActivityLauncher
import com.seashell.gechuang.framework.WindowImePolicyCompat
import com.seashell.gechuang.logging.GechuangLog
import com.seashell.gechuang.workspace.WorkspaceLayoutProfile

/**
 * One permanent VirtualDisplay per slot.
 *
 * The display is created at the recorded canvas size and is never resized or
 * moved when the user promotes a slot. Its output Surface can be swapped
 * between the sidebar preview TextureView and the one full-size active
 * TextureView; changing a VirtualDisplay's output surface does not change the
 * display configuration, so the Activity inside is not relaunched.
 *
 * The display also survives a preview TextureView being detached (for example
 * while the workspace is hidden). Only an explicit release destroys it.
 */
class VirtualDisplaySlot(
    context: Context,
    private val index: Int,
) : TextureView.SurfaceTextureListener {
    interface Listener {
        fun onDisplayReady(slotIndex: Int, displayId: Int)
        fun onDisplayReleased(slotIndex: Int, displayId: Int?, reason: String)
        fun onDisplayFailed(slotIndex: Int, message: String)
    }

    private enum class OutputMode { NONE, PREVIEW, ACTIVE }

    val view = TaskSwitcherView(context, index)

    private val applicationContext = context.applicationContext ?: context
    private val factory = VirtualDisplayFactory(applicationContext)
    private var active = false
    private var suspended = false
    private var virtualDisplay: VirtualDisplay? = null
    private var previewSurface: Surface? = null
    private var activeSurface: Surface? = null
    private var outputSurface: Surface? = null
    private var outputMode = OutputMode.NONE
    private var coldPreview = false
    private var activeTexture: SurfaceTexture? = null
    private var activeGeometry: SlotGeometry? = null
    private var layoutProfile: WorkspaceLayoutProfile? = null
    private var pendingLaunch: Intent? = null
    private var freshSurfaceRequired = false
    private var surfaceGeneration = 1
    private var listener: Listener? = null

    internal var contentFrameSequence = 0L
        private set

    init {
        view.textureView.surfaceTextureListener = this
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    fun displayId(): Int? = virtualDisplay?.display?.displayId

    fun geometry(): SlotGeometry? = activeGeometry

    fun setLayoutProfile(profile: WorkspaceLayoutProfile) {
        if (layoutProfile == profile) return
        layoutProfile = profile
        val texture = view.textureView.surfaceTexture
        if (texture != null) onSurfaceTextureSizeChanged(texture, view.textureView.width, view.textureView.height)
    }

    fun sourceBounds(): WorkspaceLayoutProfile.Source = checkNotNull(layoutProfile).source

    fun isSuspended(): Boolean = suspended

    fun activate() {
        active = true
        suspended = false
        if (freshSurfaceRequired) {
            freshSurfaceRequired = false
            surfaceGeneration += 1
            view.replaceTextureView(this)
            GechuangLog.info("virtual_display", "Replaced TextureView slot=$index generation=$surfaceGeneration")
        }
        if (virtualDisplay == null) {
            val texture = view.textureView.surfaceTexture
            if (view.textureView.isAvailable && texture != null) {
                createDisplay(texture, view.textureView.width, view.textureView.height)
            }
        } else {
            virtualDisplay?.display?.displayId?.let { displayId ->
                WindowImePolicyCompat.allowLocalIme(applicationContext, displayId)
            }
            applyOutput()
        }
    }

    /** Used only for a real teardown (workspace feature disabled/uninstalled). */
    fun deactivate() {
        active = false
        suspended = false
        pendingLaunch = null
        releaseDisplay("workspace disabled")
        freshSurfaceRequired = true
    }

    /**
     * Hide the workspace without destroying the apps inside. The displays stay
     * alive with no output surface; they are reattached on the next show.
     */
    fun suspendForWorkspaceHidden() {
        suspended = true
        outputMode = OutputMode.NONE
        virtualDisplay?.setSurface(null)
        releaseSurface(outputSurface)
        outputSurface = null
        releaseSurface(activeSurface)
        activeSurface = null
        GechuangLog.info("virtual_display", "Suspended slot=$index displayId=${displayId()} (tasks retained)")
    }

    fun resumeForWorkspaceShown() {
        suspended = false
        active = true
        if (outputMode == OutputMode.NONE && !coldPreview) outputMode = OutputMode.PREVIEW
        if (virtualDisplay == null) {
            val texture = view.textureView.surfaceTexture
            if (view.textureView.isAvailable && texture != null) {
                createDisplay(texture, view.textureView.width, view.textureView.height)
            }
        } else {
            virtualDisplay?.display?.displayId?.let { displayId ->
                WindowImePolicyCompat.allowLocalIme(applicationContext, displayId)
            }
            applyOutput()
        }
    }

    /** Lock-screen path: keep the tasks and displays, only detach the output. */
    fun setSuspended(value: Boolean) {
        if (value) {
            suspended = true
            outputMode = OutputMode.NONE
            virtualDisplay?.setSurface(null)
            releaseSurface(outputSurface)
            outputSurface = null
            releaseSurface(activeSurface)
            activeSurface = null
            return
        }
        if (!suspended) return
        suspended = false
        if (outputMode == OutputMode.NONE && !coldPreview) outputMode = OutputMode.PREVIEW
        if (virtualDisplay == null) {
            val texture = view.textureView.surfaceTexture
            if (active && view.textureView.isAvailable && texture != null) {
                createDisplay(texture, view.textureView.width, view.textureView.height)
            }
        } else {
            applyOutput()
        }
        virtualDisplay?.display?.displayId?.let { displayId ->
            pendingLaunch?.let { intent ->
                pendingLaunch = null
                launchNow(intent, displayId)
            }
        }
    }

    fun updateSuspendedOutput(screenAwake: Boolean) {
        if (!suspended) return
        if (!screenAwake) {
            virtualDisplay?.setSurface(null)
            releaseSurface(outputSurface)
            outputSurface = null
            return
        }
        applyOutput()
    }

    /** Attach the sidebar preview TextureView as the display output. */
    fun usePreviewOutput() {
        if (coldPreview) {
            outputMode = OutputMode.NONE
            virtualDisplay?.setSurface(null)
            releaseSurface(outputSurface)
            outputSurface = null
            return
        }
        if (outputMode == OutputMode.PREVIEW) return
        outputMode = OutputMode.PREVIEW
        applyOutput()
    }

    /**
     * Stop cold-slot compositing while the workspace is visible. The
     * VirtualDisplay and its task stay alive; only its output is detached, so
     * the last preview frame remains visible and the main task gets the GPU.
     */
    fun suspendColdPreview() {
        if (index < 2) return
        coldPreview = true
        if (outputMode == OutputMode.ACTIVE) return
        outputMode = OutputMode.NONE
        virtualDisplay?.setSurface(null)
        releaseSurface(outputSurface)
        outputSurface = null
        GechuangLog.info("virtual_display", "Cold preview suspended slot=$index")
    }

    /** Attach the one full-size active TextureView as the display output. */
    fun useActiveOutput(texture: SurfaceTexture, canvasWidth: Int, canvasHeight: Int) {
        activeTexture = texture
        texture.setDefaultBufferSize(canvasWidth, canvasHeight)
        outputMode = OutputMode.ACTIVE
        applyOutput()
    }

    fun clearActiveOutput() {
        if (outputMode != OutputMode.ACTIVE) return
        outputMode = if (coldPreview) OutputMode.NONE else OutputMode.PREVIEW
        releaseSurface(activeSurface)
        activeSurface = null
        activeTexture = null
        if (outputMode == OutputMode.NONE) {
            virtualDisplay?.setSurface(null)
            releaseSurface(outputSurface)
            outputSurface = null
        } else {
            applyOutput()
        }
    }

    fun launch(intent: Intent): Result<Unit> {
        val displayId = virtualDisplay?.display?.displayId
        if (suspended || displayId == null) {
            pendingLaunch = Intent(intent)
            view.showWaiting()
            return Result.success(Unit)
        }
        return launchNow(intent, displayId)
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (!active) return
        if (virtualDisplay == null) {
            createDisplay(texture, width, height)
        } else if (outputMode != OutputMode.ACTIVE && !coldPreview) {
            outputMode = OutputMode.PREVIEW
            applyOutput()
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        if (!active || suspended) return
        if (width <= 0 || height <= 0) return
        val profile = checkNotNull(layoutProfile) { "Recorded layout must precede slot activation" }
        val geometry = slotCanvas(profile)
        // TextureView resets the producer buffer to its view size during layout.
        // Restore the recorded canvas even when the VirtualDisplay did not resize.
        texture.setDefaultBufferSize(geometry.width, geometry.height)
        val scale = minOf(width.toFloat() / geometry.width, height.toFloat() / geometry.height)
        view.textureView.setTransform(Matrix().apply {
            setScale(geometry.width * scale / width, geometry.height * scale / height)
            postTranslate(
                (width - geometry.width * scale) / 2f,
                (height - geometry.height * scale) / 2f,
            )
        })
        // Never resize the display just because its preview changed size. The
        // canvas is fixed for the lifetime of this slot; that is what keeps the
        // Activity from being relaunched on every promotion.
        if (virtualDisplay != null && geometry == activeGeometry) return
        activeGeometry = geometry
        val display = virtualDisplay ?: return
        if (outputSurface == null) return
        runCatching {
            texture.setDefaultBufferSize(geometry.width, geometry.height)
            display.resize(geometry.width, geometry.height, geometry.densityDpi)
        }.onSuccess {
            GechuangLog.info(
                "virtual_display",
                "Resized slot=$index generation=$surfaceGeneration geometry=$geometry",
            )
        }.onFailure { error ->
            GechuangLog.error(
                "virtual_display",
                "Resize/rebind failed slot=$index generation=$surfaceGeneration",
                error,
            )
            releaseDisplay("resize/rebind failed")
            createDisplay(texture, width, height)
        }
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        releaseSurface(previewSurface)
        previewSurface = null
        if (outputMode == OutputMode.PREVIEW) {
            outputMode = OutputMode.NONE
            virtualDisplay?.setSurface(null)
            releaseSurface(outputSurface)
            outputSurface = null
        }
        GechuangLog.info(
            "virtual_display",
            "Preview surface destroyed slot=$index displayId=${displayId()} (display retained)",
        )
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        contentFrameSequence += 1
    }

    private fun createDisplay(texture: SurfaceTexture, width: Int, height: Int) {
        if (!active || suspended || virtualDisplay != null || width <= 0 || height <= 0) return
        val geometry = slotCanvas(checkNotNull(layoutProfile))
        activeGeometry = geometry
        texture.setDefaultBufferSize(geometry.width, geometry.height)
        onSurfaceTextureSizeChanged(texture, width, height)
        val createdSurface = Surface(texture)
        previewSurface = createdSurface
        factory.create(index, geometry, createdSurface)
            .onSuccess { display ->
                virtualDisplay = display
                outputMode = OutputMode.PREVIEW
                outputSurface = createdSurface
                WindowImePolicyCompat.allowLocalIme(applicationContext, display.display.displayId)
                listener?.onDisplayReady(index, display.display.displayId)
                GechuangLog.info(
                    "virtual_display",
                    "Created slot=$index generation=$surfaceGeneration " +
                        "displayId=${display.display.displayId} geometry=$geometry",
                )
                if (activeTexture != null) {
                    val pendingTexture = activeTexture
                    if (pendingTexture != null && pendingTexture != texture) {
                        useActiveOutput(pendingTexture, geometry.width, geometry.height)
                    } else {
                        applyOutput()
                    }
                } else {
                    applyOutput()
                }
                if (index >= 2) suspendColdPreview()
                pendingLaunch?.let { intent ->
                    pendingLaunch = null
                    launchNow(intent, display.display.displayId)
                }
            }
            .onFailure {
                releaseSurface(createdSurface)
                previewSurface = null
                view.showWaiting()
                listener?.onDisplayFailed(index, it.message ?: "display unavailable")
            }
    }

    private fun slotCanvas(profile: WorkspaceLayoutProfile): SlotGeometry {
        val base = profile.canvas
        // Main-side first slot keeps native resolution. The first side slot is
        // 720p/30 so it can still switch quickly; the cold slots are 480p/24.
        // A fixed canvas is what keeps an Activity from being relaunched when
        // its output is promoted, so this is deliberately chosen once per slot.
        if (index == 0) return base
        if (index == 1) {
            return SlotGeometry(
                width = base.width * 2 / 3,
                height = base.height * 2 / 3,
                densityDpi = base.densityDpi * 2 / 3,
            )
        }
        return SlotGeometry(
            width = base.width * 4 / 9,
            height = base.height * 4 / 9,
            densityDpi = base.densityDpi * 4 / 9,
        )
    }

    private fun targetFrameRate(): Float = when (index) {
        0 -> 60f
        1 -> 30f
        else -> 24f
    }

    private fun applyOutput() {
        val display = virtualDisplay
        if (display == null || !active || suspended ||
            (outputMode == OutputMode.PREVIEW && coldPreview)) {
            releaseSurface(outputSurface)
            outputSurface = null
            display?.setSurface(null)
            return
        }
        val texture = when (outputMode) {
            OutputMode.ACTIVE -> activeTexture
            OutputMode.PREVIEW -> view.textureView.surfaceTexture?.takeIf {
                view.textureView.isAvailable
            }
            OutputMode.NONE -> null
        }
        if (texture == null) {
            releaseSurface(outputSurface)
            outputSurface = null
            display.setSurface(null)
            return
        }
        val geometry = activeGeometry ?: layoutProfile?.canvas ?: return
        texture.setDefaultBufferSize(geometry.width, geometry.height)
        val replacement = Surface(texture)
        runCatching {
            replacement.setFrameRate(targetFrameRate(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }.onFailure {
            GechuangLog.warn("virtual_display", "Unable to set frame rate slot=$index", it)
        }
        releaseSurface(outputSurface)
        outputSurface = replacement
        if (outputMode == OutputMode.ACTIVE) {
            releaseSurface(activeSurface)
            activeSurface = replacement
        }
        display.setSurface(replacement)
        GechuangLog.info(
            "virtual_display",
            "Output attached slot=$index mode=$outputMode displayId=${display.display.displayId}",
        )
    }

    private fun launchNow(intent: Intent, displayId: Int): Result<Unit> = runCatching {
        val options = ActivityOptions.makeBasic().apply { setLaunchDisplayId(displayId) }
        UserTargetedActivityLauncher.start(
            applicationContext,
            Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            options.toBundle(),
        ).getOrThrow()
        GechuangLog.info("virtual_display", "Launched slot=$index displayId=$displayId intent=$intent")
    }.onFailure { error ->
        view.showWaiting()
        GechuangLog.error("virtual_display", "Launch failed slot=$index displayId=$displayId", error)
    }

    private fun releaseDisplay(reason: String) {
        val releasedDisplayId = virtualDisplay?.display?.displayId
        runCatching { virtualDisplay?.setSurface(null) }
        virtualDisplay?.release()
        virtualDisplay = null
        outputMode = OutputMode.NONE
        releaseSurface(outputSurface)
        outputSurface = null
        releaseSurface(previewSurface)
        previewSurface = null
        releaseSurface(activeSurface)
        activeSurface = null
        activeTexture = null
        activeGeometry = null
        view.showWaiting()
        GechuangLog.info(
            "virtual_display",
            "Released slot=$index generation=$surfaceGeneration reason=$reason",
        )
        listener?.onDisplayReleased(index, releasedDisplayId, reason)
    }

    private fun releaseSurface(surface: Surface?) {
        if (surface == null) return
        runCatching { surface.release() }
    }
}
