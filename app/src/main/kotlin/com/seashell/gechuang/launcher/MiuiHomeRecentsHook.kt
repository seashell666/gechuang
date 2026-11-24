package com.seashell.gechuang.launcher

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import com.seashell.gechuang.logging.GechuangLog
import com.seashell.gechuang.xposed.HookGuard
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/** MIUI Home integration.
 *
 * 1. Replaces the second circular recents-menu button (split screen on MIUI 14)
 *    with "进入格子".
 * 2. Watches the small-window "松手悬挂" hold target. Holding inside it for 700ms
 *    sends the card's exact task into the first empty workspace slot.
 *
 * All access uses reflection against the launcher's own class loader; MIUI Home
 * versions differ, so missing members are logged and skipped rather than crashing
 * the launcher. */
object MiuiHomeRecentsHook {
    private val pendingHolds = WeakHashMap<Any, Runnable>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun install(module: XposedModule, classLoader: ClassLoader) {
        HookGuard.run("miui_recents_menu") { installMenu(module, classLoader) }
        HookGuard.run("miui_recents_hold") { installHold(module, classLoader) }
    }

    private fun installMenu(module: XposedModule, classLoader: ClassLoader) {
        val menuClass = classLoader.loadClass("com.miui.home.recents.views.RecentMenuView")
        listOf(
            "onFinishInflate",
            "updateNormalShowingMenuInfoS",
            "updateNormalShowingMenuInfoT",
            "updateShowingMenuInfoIfNeed",
        ).forEach { name ->
            val method = menuClass.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty()
            } ?: return@forEach
            method.isAccessible = true
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    (chain.thisObject as? View)?.let(::applyMenuEntry)
                    return result
                }
            })
        }
        GechuangLog.info("miui_recents", "RecentMenuView entry installed")
    }

    private fun applyMenuEntry(menu: View) {
        val image = field(menu, "mMenuItemMultiWindow") as? ImageView ?: return
        if (image.tag != TAG_GRID_ENTRY) {
            image.tag = TAG_GRID_ENTRY
            image.contentDescription = "进入格子"
            runCatching { image.setImageDrawable(MiuiHomeWorkspaceBridge.menuIcon(image.context)) }
            val container = field(menu, "mMenuItemMultiWindowContainer") as? View
            val click = View.OnClickListener { view ->
                GechuangLog.info("miui_recents", "Grid entry clicked")
                val task = field(menu, "mTask")
                val identity = task?.let(::taskIdentity)
                if (identity != null) {
                    GechuangLog.info(
                        "miui_recents",
                        "Grid entry task=${identity.first} user=${identity.second} component=${identity.third}",
                    )
                    MiuiHomeWorkspaceBridge.requestSlot(
                        view.context,
                        identity.first,
                        identity.second,
                        identity.third,
                    )
                } else {
                    GechuangLog.warn("miui_recents", "Recents menu task has no identity")
                }
                runCatching { menu.javaClass.getMethod("removeMenu", Boolean::class.javaPrimitiveType)
                    .invoke(menu, true) }
                dismissRecents(view)
            }
            image.setOnClickListener(click)
            image.isClickable = true
            container?.setOnClickListener(click)
            container?.isClickable = true
            GechuangLog.info("miui_recents", "Grid entry listener attached image=${image.id} container=${container?.id}")
        }
    }

    /** MIUI's own menu handlers leave recents open only for entries that start
     * another in-launcher surface. Our entry hands the task to SystemUI, so
     * collapse recents to HOME instead of leaving the card grid on top of the
     * workspace. */
    private fun dismissRecents(view: View) {
        view.postDelayed({
            runCatching {
                val appClass = view.context.classLoader
                    .loadClass("com.miui.home.launcher.Application")
                val launcher = appClass.getMethod("getLauncher").invoke(null)
                    ?: return@runCatching
                val container = launcher.javaClass.getMethod("getRecentsContainer").invoke(launcher)
                    ?: return@runCatching
                container.javaClass.getMethod("dismissRecentsToHome").invoke(container)
            }.onFailure { GechuangLog.warn("miui_recents", "Unable to dismiss recents", it) }
        }, 80L)
    }

    private fun installHold(module: XposedModule, classLoader: ClassLoader) {
        val navClass = classLoader.loadClass("com.miui.home.recents.NavStubView")
        val updates = navClass.declaredMethods.filter { it.name == "updateTopWindowCrop" }
        if (updates.isEmpty()) {
            GechuangLog.warn("miui_recents", "NavStubView.updateTopWindowCrop not found")
            return
        }
        // MIUI has both no-arg and MotionEventPosition overloads. The hold flag is
        // normally refreshed by the positional overload, so hook every variant.
        updates.forEach { update ->
            update.isAccessible = true
            module.hook(update).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    evaluateHold(chain.thisObject ?: return result)
                    return result
                }
            })
        }
        listOf("exitTopWindowCrop", "finish").forEach { name ->
            navClass.declaredMethods.filter { it.name == name }.forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        chain.thisObject?.let(::cancel)
                        return chain.proceed()
                    }
                })
            }
        }
        GechuangLog.info("miui_recents", "Small-window hold entry installed")
    }

    private fun evaluateHold(nav: Any) {
        val crop = field(nav, "mTopWindowCrop")
        val hold = crop != null && runCatching {
            crop.javaClass.getMethod("isInHoldState").invoke(crop) == true
        }.getOrDefault(false)
        val openContent = crop?.let {
            runCatching { it.javaClass.getMethod("getOpenContent").invoke(it) as? Int }.getOrNull()
        }
        if (hold && (openContent == null || openContent == 1)) {
            schedule(nav)
        } else {
            cancel(nav)
        }
    }

    private fun schedule(nav: Any) {
        if (pendingHolds.containsKey(nav)) return
        val runnable = Runnable {
            pendingHolds.remove(nav)
            if (!isStillHolding(nav)) return@Runnable
            val task = field(nav, "mRunningTaskInfo")
            val identity = task?.let(::runningTaskIdentity)
            val component = (field(nav, "mRunningTaskComponentName") as? ComponentName)
                ?: identity?.third
            val taskId = identity?.first
                ?: (field(nav, "mRunningTaskId") as? Int)
            val userId = identity?.second
                ?: (field(nav, "mRunningTaskUserId") as? Int) ?: 0
            if (taskId == null || taskId < 0 || component == null) {
                GechuangLog.warn("miui_recents", "Hold task has no identity task=$taskId component=$component")
                return@Runnable
            }
            val context = (nav as? View)?.context
            runCatching {
                nav.javaClass.getMethod(
                    "finish",
                    Boolean::class.javaPrimitiveType,
                    Runnable::class.java,
                ).invoke(nav, true, null as Runnable?)
            }.onFailure { GechuangLog.warn("miui_recents", "Unable to close recents", it) }
            if (context == null) return@Runnable
            // Let the recents transition finish before the workspace window is
            // requested; otherwise HOME can cover the just-created sidebar.
            mainHandler.postDelayed({
                MiuiHomeWorkspaceBridge.requestSlot(context, taskId, userId, component)
            }, 320L)
        }
        pendingHolds[nav] = runnable
        mainHandler.postDelayed(runnable, HOLD_TRIGGER_MS)
    }

    private fun cancel(nav: Any) {
        pendingHolds.remove(nav)?.let(mainHandler::removeCallbacks)
    }

    private fun isStillHolding(nav: Any): Boolean = runCatching {
        val crop = field(nav, "mTopWindowCrop") ?: return@runCatching false
        crop.javaClass.getMethod("isInHoldState").invoke(crop) == true
    }.getOrDefault(false)

    private fun taskIdentity(task: Any): Triple<Int, Int, ComponentName>? = runCatching {
        val key = field(task, "key") ?: return@runCatching null
        val id = field(key, "id") as? Int ?: return@runCatching null
        val userId = field(key, "userId") as? Int ?: 0
        val component = componentOf(key) ?: return@runCatching null
        Triple(id, userId, component)
    }.getOrNull()

    private fun runningTaskIdentity(task: Any): Triple<Int, Int, ComponentName>? = runCatching {
        val id = field(task, "taskId") as? Int ?: return@runCatching null
        val userId = field(task, "userId") as? Int ?: 0
        val component = componentOf(task) ?: return@runCatching null
        Triple(id, userId, component)
    }.getOrNull()

    private fun componentOf(value: Any): ComponentName? {
        (field(value, "component") as? ComponentName)?.let { return it }
        listOf("getComponent", "getTopComponent", "getBaseComponent").forEach { name ->
            runCatching { value.javaClass.getMethod(name).invoke(value) as? ComponentName }
                .getOrNull()?.let { return it }
        }
        (field(value, "topActivity") as? ComponentName)?.let { return it }
        return field(value, "baseActivity") as? ComponentName
    }

    private fun field(target: Any, name: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            try {
                val field: Field = type.getDeclaredField(name)
                field.isAccessible = true
                return field.get(target)
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private const val HOLD_TRIGGER_MS = 700L
    private const val TAG_GRID_ENTRY = "nextep_grid_entry"
}
