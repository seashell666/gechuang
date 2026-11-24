package com.seashell.gechuang.xposed

import android.util.Log
import com.seashell.gechuang.logging.GechuangLog
import com.seashell.gechuang.launcher.ColorOsLauncherHook
import com.seashell.gechuang.launcher.MiuiHomeRecentsHook
import com.seashell.gechuang.multiapp.OplusMultiAppHook
import com.seashell.gechuang.framework.TaskSurfaceCompat
import com.seashell.gechuang.safety.EmergencyDisable
import com.seashell.gechuang.safety.FeatureGate
import com.seashell.gechuang.systemui.SystemUiHook
import com.seashell.gechuang.systemserver.SystemServerHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

class GechuangHookEntry : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        GechuangLog.info("module", "GechuangHookEntry loaded; systemServer=${param.isSystemServer}")
        if (param.isSystemServer) {
            log(Log.INFO, "Gechuang", "system_server module generation loaded")
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log(Log.INFO, "Gechuang", "system_server starting callback received")
        if (EmergencyDisable.isRequested() ||
            !FeatureGate.SYSTEM_SERVER_PATCHES.defaultEnabled
        ) {
            log(Log.WARN, "Gechuang", "system_server hooks disabled by safety gate")
            return
        }
        SystemServerHook.install(this, param.classLoader)
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return
        val packageName = param.packageName
        GechuangLog.info(
            "dispatcher",
            "Observed package=$packageName",
        )

        if (packageName == "com.android.systemui") {
            if (!EmergencyDisable.isRequested() && FeatureGate.SYSTEM_UI.defaultEnabled) {
                TaskSurfaceCompat.initialize(param.defaultClassLoader)
                SystemUiHook.install(this, param.defaultClassLoader)
            }
            return
        }

        if (packageName == "com.seashell.gechuang") {
            return
        }

        if (packageName == "com.oplus.multiapp") {
            if (!EmergencyDisable.isRequested() &&
                FeatureGate.LAUNCHER_TRANSFORM.defaultEnabled
            ) {
                OplusMultiAppHook.install(this, param.defaultClassLoader)
            }
            return
        }

        if (packageName == "com.miui.home") {
            if (!EmergencyDisable.isRequested() && FeatureGate.LAUNCHER_TRANSFORM.defaultEnabled) {
                MiuiHomeRecentsHook.install(this, param.defaultClassLoader)
            }
            return
        }

        if (EmergencyDisable.isRequested() || !FeatureGate.LAUNCHER_TRANSFORM.defaultEnabled) return

        ColorOsLauncherHook.install(this, packageName, param.defaultClassLoader)
    }
}
