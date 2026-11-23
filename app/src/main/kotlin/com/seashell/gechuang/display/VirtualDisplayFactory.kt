package com.seashell.gechuang.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.os.Build
import android.view.Surface
import com.seashell.gechuang.framework.VirtualDisplayFlagsCompat
import com.seashell.gechuang.logging.GechuangLog

/**
 * Creates the per-slot virtual displays.
 *
 * Android 14 (API 34) introduced the VirtualDisplayConfig builder and
 * DisplayManager#createVirtualDisplay(VirtualDisplayConfig). Older ROMs such as
 * Android 13 / MIUI 14 only expose the legacy six-argument overload, so the
 * factory keeps both paths and falls back to the legacy one when the new API is
 * unavailable at runtime.
 */
class VirtualDisplayFactory(context: Context) {
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    fun create(index: Int, geometry: SlotGeometry, surface: Surface): Result<VirtualDisplay> =
        runCatching {
            val manager = checkNotNull(displayManager) { "DisplayManager unavailable" }
            var lastFailure: Throwable? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                VirtualDisplayFlagsCompat.resolveCandidates().forEach { flags ->
                    try {
                        // Preview displays need no 120/144 Hz mode simply because
                        // the physical display is fast. The main display stays native.
                        val config = VirtualDisplayConfig.Builder(
                            "Gechuang-slot-$index",
                            geometry.width,
                            geometry.height,
                            geometry.densityDpi,
                        )
                            .setSurface(surface)
                            .setFlags(flags.value)
                            .setRequestedRefreshRate(60f)
                            .build()
                        val display = manager.createVirtualDisplay(config)
                        if (display != null) return@runCatching display
                        lastFailure = IllegalStateException("DisplayManager returned null flags=${flags.names}")
                    } catch (error: Throwable) {
                        lastFailure = error
                        GechuangLog.warn(
                            "virtual_display",
                            "Candidate rejected slot=$index flags=${flags.names}",
                            error,
                        )
                    }
                }
            } else {
                VirtualDisplayFlagsCompat.resolveCandidates().forEach { flags ->
                    try {
                        @Suppress("DEPRECATION")
                        val display = manager.createVirtualDisplay(
                            "Gechuang-slot-$index",
                            geometry.width,
                            geometry.height,
                            geometry.densityDpi,
                            surface,
                            flags.value,
                        )
                        if (display != null) return@runCatching display
                        lastFailure = IllegalStateException("DisplayManager returned null flags=${flags.names}")
                    } catch (error: Throwable) {
                        lastFailure = error
                        GechuangLog.warn(
                            "virtual_display",
                            "Legacy candidate rejected slot=$index flags=${flags.names}",
                            error,
                        )
                    }
                }
            }
            throw lastFailure ?: IllegalStateException("No VirtualDisplay flag candidate")
        }.onSuccess { display ->
            GechuangLog.info(
                "virtual_display",
                "Created slot=$index displayId=${display.display.displayId} geometry=$geometry",
            )
        }.onFailure { error ->
            GechuangLog.error("virtual_display", "Creation failed slot=$index geometry=$geometry", error)
        }
}
