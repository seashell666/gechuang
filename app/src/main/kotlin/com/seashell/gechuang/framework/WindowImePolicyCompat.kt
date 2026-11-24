package com.seashell.gechuang.framework

import android.content.Context
import android.view.WindowManager
import com.seashell.gechuang.logging.GechuangLog

/**
 * Android normally hides the software keyboard for app-owned virtual displays.
 * SystemUI owns these displays, so switching their IME policy to LOCAL lets the
 * real IME open inside the same VirtualDisplay. The active slot surface then
 * shows the keyboard together with the app, without moving any task.
 */
object WindowImePolicyCompat {
    private const val IME_POLICY_LOCAL = 0

    fun allowLocalIme(context: Context, displayId: Int): Result<Unit> = runCatching {
        val windowManager = context.getSystemService(WindowManager::class.java)
            ?: error("WindowManager unavailable")
        val method = generateSequence<Class<*>>(windowManager.javaClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { candidate ->
                candidate.name == "setDisplayImePolicy" &&
                    candidate.parameterTypes.size == 2 &&
                    candidate.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    candidate.parameterTypes[1] == Int::class.javaPrimitiveType
            }
            ?.apply { isAccessible = true }
            ?: error("setDisplayImePolicy unavailable")
        method.invoke(windowManager, displayId, IME_POLICY_LOCAL)
        GechuangLog.info("ime_policy", "Display $displayId allows local IME")
    }.onFailure { error ->
        GechuangLog.warn("ime_policy", "Unable to set local IME policy display=$displayId", error)
    }
}
