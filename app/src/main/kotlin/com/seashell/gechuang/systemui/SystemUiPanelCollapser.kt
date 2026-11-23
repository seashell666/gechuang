package com.seashell.gechuang.systemui

import android.content.Context
import com.seashell.gechuang.logging.GechuangLog

object SystemUiPanelCollapser {
    fun collapse(context: Context) {
        runCatching {
            val manager = context.getSystemService("statusbar")
                ?: error("StatusBarManager unavailable")
            manager.javaClass.getMethod("collapsePanels").invoke(manager)
        }.onFailure { error ->
            GechuangLog.warn("systemui_panel", "Unable to collapse SystemUI panels", error)
        }
    }
}
