package com.seashell.gechuang.framework

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.hardware.input.InputManager
import android.view.InputEvent
import android.view.MotionEvent
import com.seashell.gechuang.logging.GechuangLog
import java.lang.reflect.Method

/**
 * Sends a touch that happened on the workspace surface back into the virtual
 * display that owns that surface. SystemUI holds INJECT_EVENTS, so this needs
 * no user permission and no Accessibility service.
 */
@SuppressLint("BlockedPrivateApi")
object InputInjectionCompat {
    private val inputManager: InputManager? by lazy {
        runCatching {
            val type = Class.forName("android.hardware.input.InputManager")
            val getInstance = type.getDeclaredMethod("getInstance").apply { isAccessible = true }
            getInstance.invoke(null) as? InputManager
        }.onFailure { error ->
            GechuangLog.warn("input_injection", "InputManager unavailable", error)
        }.getOrNull()
    }

    private val injectMethod: Method? by lazy {
        runCatching {
            InputManager::class.java.getDeclaredMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        }.onFailure { error ->
            GechuangLog.warn("input_injection", "injectInputEvent unavailable", error)
        }.getOrNull()
    }

    private val setDisplayIdMethod: Method? by lazy {
        runCatching {
            InputEvent::class.java.getDeclaredMethod(
                "setDisplayId",
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        }.onFailure { error ->
            GechuangLog.warn("input_injection", "InputEvent.setDisplayId unavailable", error)
        }.getOrNull()
    }

    fun injectMotion(
        displayId: Int,
        event: MotionEvent,
        scaleX: Float,
        scaleY: Float,
    ): Result<Unit> = runCatching {
        val manager = checkNotNull(inputManager) { "InputManager unavailable" }
        val inject = checkNotNull(injectMethod) { "injectInputEvent unavailable" }
        val setDisplay = checkNotNull(setDisplayIdMethod) { "setDisplayId unavailable" }
        val copy = MotionEvent.obtain(event)
        try {
            if (scaleX != 1f || scaleY != 1f) {
                copy.transform(Matrix().apply { setScale(scaleX, scaleY) })
            }
            setDisplay.invoke(copy, displayId)
            // 0 == INJECT_INPUT_EVENT_MODE_ASYNC. The dispatcher copies the event
            // before returning, so recycling here cannot race the app.
            inject.invoke(manager, copy, 0)
            Unit
        } finally {
            copy.recycle()
        }
    }.onFailure { error ->
        GechuangLog.warn(
            "input_injection",
            "Touch injection failed displayId=$displayId action=${event.actionMasked}",
            error,
        )
    }
}
