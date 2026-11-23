package com.seashell.gechuang.systemui

import com.seashell.gechuang.logging.GechuangLog
import io.github.libxposed.api.XposedInterface

class SystemUiStartupHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val target = chain.thisObject
        val result = chain.proceed()
        try {
            val context = SystemUiContextResolver.fromHookTarget(target)
            if (context == null) {
                GechuangLog.warn("systemui_startup", "Startup target did not expose a Context")
            } else {
                SystemUiRuntime.initialize(context)
            }
        } catch (error: Throwable) {
            GechuangLog.error("systemui_startup", "Initialization failed open", error)
        }
        return result
    }
}
