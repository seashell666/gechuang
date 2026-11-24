# NeXtep MIUI 14 / Android 13 适配记录

## 目标

把开源侧边工作台跑在小米 11（MIUI 14 / Android 13 / SDK 33）上，并放到可独立维护的自有仓库：保留原版界面、排版、图标、关于页和更新入口，只做系统兼容与项目身份的最小改动。

## 编译环境

全部在本机 `C:\windows软件编译` 下新建，未污染系统：

- JDK 21.0.13.1（Adoptium）
- Android SDK：`platforms;android-36.1`、`build-tools;36.1.0`、`platform-tools`（cmdline-tools 12.0）
- Gradle Wrapper 9.5.0 / AGP 9.3.0
- 构建参数：`JAVA_HOME` + `ANDROID_HOME` 环境变量；本机通过 `GRADLE_OPTS` 走 127.0.0.1:10809 代理

> 注意：`local.properties` 写中文路径会被 Java properties 读坏，必须用 `ANDROID_HOME` 环境变量。

## 代码级适配

| 问题 | 处理 |
| --- | --- |
| 上游最低 SDK 35 | `minSdk` 降到 33 |
| Android 13 没有 `VirtualDisplayConfig$Builder.setRequestedRefreshRate` / `DisplayManager.createVirtualDisplay(VirtualDisplayConfig)` | `VirtualDisplayFactory` 在 SDK < 34 时回退旧的六参 `createVirtualDisplay(name,w,h,dpi,surface,flags)` |
| Android 13 没有 `WindowContainerTransaction#setDensityDpi` | `WindowContainerTransactionCompat.invokeOptional`：反射失败静默跳过 |
| Android 13 没有 `LayoutParams#setWallpaperTouchEventsEnabled` | SDK >= 34 才调用，避免每次建窗报栈 |
| A14 的 `SystemUIAppComponentFactoryBase` 组织器链在 MIUI 不存在 | `TaskSurfaceCompat` 增加 Android 13 回退：`SystemUIFactory.getInstance().mWMComponent`、`getShellInit().mShellTaskOrganizer`，找不到就 fail-open |
| MIUI 桌面 `com.miui.home` 没有工作区运行时 | 触发协调器识别到 MIUI 桌面时跳过 launcher，只切换 SystemUI；避免等 launcher 60 秒超时并回滚 |
| Android 13 拦截 `SurfaceControl.Transaction#apply` 导致 `BLASTBufferQueue::mergeWithNextTransaction` SIGSEGV（fault 0x80） | SDK < 34 不装 task_surface_transaction_guard |
| SystemUI 内嵌 SurfaceView 在 A13 首次绘制触发同一类 BLAST 崩溃 | SDK < 34 将视频预览 SurfaceView 设为 GONE；普通小窗/主窗不受影响 |
| 界面与图标 | 恢复原版资源、布局、按钮和关于页，不再品牌化重做 |
| 更新检查只认 `vX.Y.Z`，遇到 `4.0.5-miui.1` 解析失败 | `UpdateChecker.versionParts` 支持 `-miui.N` 后缀；版本比较按较短一方补 0，避免数组越界 |

## 签名与发布

- Release keystore：本机 `...\new-chat-1\keys\gechuang-release.jks`（不进仓库，`keystore.properties` 已被 gitignore）
- 证书：`CN=GeChuang, O=seashell`，SHA-256 `70aba0cc8e3fc22fbf8133f46b4cc20b6ad1df4aad79be9603323f2ada4fd024`

## 真机验证（2026-11-23）

设备：小米 11 / MIUI 14 `V14.0.10.0.TKBCNXM` / Android 13 / Magisk 28.1 / Vector 2.2。

当前构建（原版界面恢复版）已安装并在真机回归，安装包 SHA-256 `02cf155b0fd0ecd7fdcd5bf01059a45075c16e0792d2557f0091d7592f09979b`。

通过：

- 右上角状态栏左滑手势打开与关闭工作台（约 1–2 秒），重启 SystemUI 后再次打开正常
- 顶栏应用图标启动系统设置到大窗，真实渲染且可点击
- 侧栏 `+` 生成小窗，小窗内真实应用渲染；点小窗可把它提到主位
- 侧栏左/右切换，主窗与小窗布局随之重排
- 顶栏右上角退出图标关闭工作台；反复开关不崩溃
- 关于页保留原版结构、原版致谢和更新入口；仅署名行为“维护 · seashell666 / 原作者 · lujinxin”
- 手动点“检查更新”能访问自有仓库 Release 并返回“暂无更新”；App 启动不自动联网、不弹授权/收费/原版校验窗
- Vector 模块启用，作用域保持 `android`、`com.android.systemui`、`com.miui.home`、`com.miui.personalassistant`；旧 `io.github.yishisanren.panes` 已停用

限制：

- 返回键不退出工作台（MIUI 无 ColorOS launcher 运行时）；用退出图标或手势
- 视频预览层在 A13 隐藏（见上表）
- 四指手势未完整验证

## 2026-11-24 七窗口常驻虚拟屏架构

这一版把侧栏从“每槽一块虚拟屏 + 跨屏搬任务”改成“侧格只当预览，点侧格只切换输出画面”，不再让 Android 在切换时重建 Activity。

### 结构

- 1 个真实主格：Display 0，保持抖音等主应用的原生触控。
- 6 个常驻 VirtualDisplay 侧格：`Gechuang-slot-0` 至 `Gechuang-slot-5`，合计 7 个任务窗口。
- 槽 0/1 保持 1080×2400 / 420 dpi，供前两个侧格后台播放视频。
- 槽 2–5 使用 720×1600 / 280 dpi，保留相同 dp 布局，换内存与 GPU 余量；任务只在进入时启动一次，之后不搬。
- `ActiveSlotView` 为每个槽持有独立 `TextureView`/`SurfaceTexture`；活动层窗口 `GechuangActiveSlot` 只显示当前槽，不覆盖顶栏和侧栏。
- `InputInjectionCompat` 把物理触摸按 `938×2085 → 槽 canvas` 比例注入对应虚拟屏，真实输入法可落在侧格。
- 虚拟屏使用 `PUBLIC | PRESENTATION | TRUSTED | OWN_CONTENT_ONLY | OWN_DISPLAY_GROUP`（3083），每个屏设置 `IME_POLICY_LOCAL`，解决 IME 进程看不到 private display 的问题。

### 行为

| 操作 | 结果 |
| --- | --- |
| 点已占用侧格 | 只把该槽输出切到活动层，任务 id 不变，不触发 `wm_relaunch_resume_activity` |
| 再点同一侧格 | 关活动层，焦点回主格；侧格任务继续留在原虚拟屏 |
| 侧格之间切换 | 每槽独立纹理，视频进度继续；槽 0/1 后台播放 |
| 四圆钮第二个 | 由 MIUI Home 广播任务到 SystemUI；工作台未开时先激活，轮询等槽 Ready 后再放入，不再丢任务 |
| 从最近任务打开工作台 | 最近任务出现时自动 suspend 工作台，避免顶栏/侧栏叠在 recents 上；入口触发后恢复常驻槽 |
| 输入法 | 侧格点搜索框可弹真实键盘，键盘画在活动层里 |
| 松手悬挂 | 最近任务小窗悬挂区停留 ≥700ms → 进格子；<700ms 松手保持 MIUI 原生小窗 |

### 本轮真机验证

- 六侧槽全部创建，侧栏显示 6 个槽位；可同时存在 1 主 + 6 侧任务。
- 槽 0 设置、槽 2 Chrome 分别以 1080p/720p 放大，活动层显示正常，触摸注入可点搜索框并弹出输入法。
- 从最近任务四圆钮入口把 Chrome 放入侧格，日志 `Opened recents task=2078 in slot`；工作台处于 suspend 时 `active=true → Resumed after unlock → Opened` 成功。
- `dumpsys meminfo`：7,424,224K 总内存，Free 3,251,585K（含 cached），未出现 OOM。
- 已知：最近任务卡片长按菜单、松手悬挂的真实手指拖拽需人工回归；`WindowManagerGlobal.getRootViews` 反射在 A13 缺失，仅作为状态栏手势兜底警告。

### 相关文件

- `display/VirtualDisplaySlot.kt`：常驻虚拟屏、preview/active 双输出、槽 2–5 降分辨率
- `display/SlotTaskCoordinator.kt`：侧格 overlay 激活/退出，不搬任务
- `systemui/ActiveSlotView.kt`：每槽独立 TextureView，硬切换无渐隐
- `systemui/GechuangWindowController.kt`：`GechuangActiveSlot` 窗口、单活动视图命中、触摸注入
- `framework/InputInjectionCompat.kt`、`WindowImePolicyCompat.kt`、`VirtualDisplayFlagsCompat.kt`
- `launcher/MiuiHomeRecentsHook.kt`：四圆钮入口、700ms 悬挂判定
- `systemui/SystemUiRuntime.kt`：最近任务 suspend/resume、入口轮询重试

## 2026-11-24：1B 原生镜像 + 热冷分级 + 3A 顶栏（4.0.7-miui.1）

- 第一侧格改为原生镜像：`TaskSurfaceCompat.mirrorTaskIntoSurface` 用 display 0 的 task leash，侧格内直接显示主格当前应用，豆包/抖音均验证动态播放；失败退 480p/15 缩略。
- 侧格再点退出：`GechuangSideExit` 独立可点击窗口覆盖侧栏，点击日志 `Exit requested from sidebar tap`，恢复主格镜像。
- 底部上滑：`SystemUiRuntime.homeMonitor` 每 350ms 轮询前台，检测到 `com.miui.home` 即 `suspendForLock()`，工作台暂收、任务保留；`resumeAfterLock()` 用 `bringTaskToFront` 回主格任务。
- 顶栏 3A：4 个 60dp 大图标 + 4 个 30dp 固定控制钮（侧栏/最近任务/设置/退出），只横向滚应用；最近任务页替换页内容，新增底部“返回应用”按钮（88×32dp、elevation 24）一键回应用条，点卡片也会自动回应用条。
- 启动：`WorkspaceSwapAnimator` handoff 1100/1600ms 降到 350/900ms。真机点最近任务卡片打开设置约 0.8s 出页面。
- 广播兜底：`WorkspaceControlBrokerReceiver` 增加 2.5s `finishOnce`，避免 SystemUI 未及时回包时宿主 ANR。
- 性能：主格抖音真实视频 30.2fps、127 帧/4.2s、中位 33.2ms、>33ms 仅 3 帧；一主一热侧时 Douyin 64%（单核口径）、豆包 0.4%、SystemUI 4.5%、整机 24%。
- 未完成：音频跟随当前操作区未实现；五侧格满载未重测；冷槽首次点开仍需 2–5s；最近任务首屏加载约 1–2s。因此本轮先发 **pre-release**，不标正式版。

### 相关文件

- `display/TaskMirrorView.kt`：原生镜像 SurfaceView + 480p/15 兜底
- `systemui/ControlAreaPager.kt`：禁横滑翻页、返回应用按钮
- `systemui/GechuangWindowController.kt`：GechuangSideExit、suspend/resume 主格
- `systemui/TopAppStripView.kt`、`systemui/WorkspaceSwapAnimator.kt`
- `trigger/WorkspaceControlBrokerReceiver.kt`：广播 2.5s 兜底

## 许可


见仓库根目录的 LICENSE 与 THIRD_PARTY_NOTICES.md。
