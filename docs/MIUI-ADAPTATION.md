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

## 许可

见仓库根目录的 LICENSE 与 THIRD_PARTY_NOTICES.md。
