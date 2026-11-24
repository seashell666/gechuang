package com.seashell.gechuang.systemui

import android.graphics.Rect
import android.os.IBinder
import android.view.SurfaceControl
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import com.seashell.gechuang.logging.GechuangLog

/**
 * MIUI's WM Shell does not expose Transitions.TransitionObserver to the
 * SystemUI class loader. We only need the earliest possible recents signal, so
 * observe the transition-ready entry point directly instead of registering an
 * observer proxy.
 */
internal object WorkspaceRecentsTransitionHook {
    fun install(module: XposedModule, loader: ClassLoader) {
        val shell = loader.loadClass("com.android.wm.shell.transition.Transitions")
        val infoType = loader.loadClass("android.window.TransitionInfo")
        val transaction = SurfaceControl.Transaction::class.java
        val tokenType = IBinder::class.java
        val ready = runCatching {
            shell.getDeclaredMethod("onTransitionReadyInner", tokenType, infoType, transaction, transaction)
        }.getOrElse {
            shell.getDeclaredMethod("onTransitionReady", tokenType, infoType, transaction, transaction)
        }
        val recentsType = runCatching {
            shell.getField("TRANSIT_START_RECENTS_TRANSITION").getInt(null)
        }.recoverCatching {
            infoType.getField("TRANSIT_START_RECENTS_TRANSITION").getInt(null)
        }.getOrDefault(-1)
        val getType = infoType.getMethod("getType")
        val getChanges = infoType.getMethod("getChanges")
        val changeType = loader.loadClass("android.window.TransitionInfo\$Change")
        val getMode = changeType.getMethod("getMode")
        val getTaskInfo = changeType.getMethod("getTaskInfo")

        module.hook(ready).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                runCatching {
                    val info = chain.getArg(1)
                    val type = getType.invoke(info) as? Int ?: return@runCatching
                    GechuangLog.info("workspace_recents_handoff", "Transition ready type=$type")
                    if (type == recentsType) {
                        GechuangLog.info(
                            "workspace_recents_handoff",
                            "Recents transition ready type=$type; hiding workspace before first frame",
                        )
                        SystemUiRuntime.onSystemRecentsStarted()
                    }
                }.onFailure {
                    GechuangLog.warn("workspace_recents_handoff", "Recents ready hook failed", it)
                }
                return chain.proceed()
            }
        })
        GechuangLog.info(
            "workspace_recents_handoff",
            "Hook installed ready=${ready.name} recentsType=$recentsType",
        )
    }
}
