package com.seashell.gechuang.systemui

import android.view.WindowManager
import com.seashell.gechuang.logging.GechuangLog

object SystemUiWindowTypeResolver {
    private val candidates = listOf(
        // SystemUI is allowed to use this system-only legacy type. Its layer is above
        // applications but below game assistants, system dialogs and the IME. Status-bar
        // panel types cover those windows even when their owners bring them to the front.
        "TYPE_PHONE",
    )

    fun resolve(): Int? {
        for (candidate in candidates) {
            try {
                val field = WindowManager.LayoutParams::class.java.getDeclaredField(candidate)
                field.isAccessible = true
                val value = field.getInt(null)
                GechuangLog.info("systemui_window", "Selected window type=$candidate")
                return value
            } catch (error: Throwable) {
                GechuangLog.debug("systemui_window", "Unavailable window type=$candidate: $error")
            }
        }
        GechuangLog.warn("systemui_window", "No validated SystemUI window type candidate")
        return null
    }
}
