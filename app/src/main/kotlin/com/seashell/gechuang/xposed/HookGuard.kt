package com.seashell.gechuang.xposed

import com.seashell.gechuang.logging.GechuangLog

object HookGuard {
    inline fun run(feature: String, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (error: Throwable) {
        GechuangLog.error(feature, "Hook initialization failed open", error)
        false
    }
}
