# 格窗（MIUI 适配版）

侧边多任务工作台：一个**大窗**放当前主应用，侧边可停靠多个**实时小窗**，点哪个小窗就把它提到大窗。为 **Android 13 / MIUI 14** 做了适配。

## 能做什么

- 顶栏：显示系统状态、常用应用，点应用直接进大窗。
- 大窗：真实应用渲染，可操作，不是截图。
- 小窗：最多三个，实时刷新；点小窗内容可切到主位；小窗可左右停靠。
- 手势：从屏幕右上角向左下滑动，打开/关闭工作台。
- 侧栏：可切到左边或右边，布局跟着变。
- 工具入口：系统设置、语言、图标、手势开关等。

## 已实测的设备

| 项目 | 值 |
| --- | --- |
| 手机 | 小米 11（M2011K2C / venus） |
| 系统 | MIUI 14 `V14.0.10.0.TKBCNXM`，Android 13（SDK 33） |
| Root | Magisk 28.1 |
| 模块框架 | Vector 2.2（`/data/adb/lspd/cli`） |

**实测通过**：手势开关工作台、顶栏启动主应用、大窗内真实操作（设置里点 WLAN 生效）、侧栏加小窗、点小窗切主位、侧边左右切换、右上角退出、反复开关不崩。

## 安装

直接下载：[格窗 4.0.5-miui.1](https://github.com/seashell666/gechuang/releases/tag/4.0.5-miui.1)

1. 在 Vector / LSPosed 里启用 **格窗**。
2. 作用域勾选：
   - `android`
   - `com.android.systemui`
   - `com.miui.home`
   - `com.miui.personalassistant`
3. 重启 SystemUI 或重启手机。
4. 打开格窗 App，按需开关“状态栏触发手势”、语言、桌面图标。

> 包名 `com.seashell.gechuang`，模块 ID `gechuang`。需要 Android 13 及以上；当前主要在 MIUI 14 验证。

## 用法

- **打开/关闭**：右上角状态栏向左下滑；或点顶栏最右侧退出图标关闭。
- **主应用**：点顶栏应用图标，或点小窗内容切过去。
- **小窗**：点侧栏 `+`，会把当前主窗应用放进小窗；主窗是桌面时，取最近用过的应用。小窗可左右停靠，点内容切主位。
- **切边**：顶栏左侧第一个图标，切换侧栏在左/右。

## 已知限制（MIUI 14 / Android 13）

- **返回键退出工作台**：MIUI 桌面没有 ColorOS 那套工作区运行时，返回键不会退出；请用右上角退出图标或手势关闭。
- **小窗视频预览**：Android 13 上 SystemUI 内嵌 SurfaceView 会触发 BLAST 事务崩溃，暂将视频预览层隐藏；不影响普通小窗和主窗。
- **四指手势**：代码路径保留，MIUI 上未做完整验证。
- **小窗首次打开**：部分机型上需要先点一次顶栏应用建立主窗，再由侧栏 `+` 添加小窗。

## 构建

环境（本机已验证）：

- JDK 21
- Android SDK：`platforms;android-36.1`、`build-tools;36.1.0`、`platform-tools`
- Gradle Wrapper 9.5.0

```powershell
$env:JAVA_HOME = "C:\windows软件编译\jdk21\jdk-21.0.13.1+2"
$env:ANDROID_HOME = "C:\windows软件编译\android-sdk"
.\gradlew.bat :app:assembleRelease
```

Release 签名读取仓库根目录的 `keystore.properties`（已被 `.gitignore` 忽略）：

```properties
storeFile=C:/path/to/your-release.jks
storePassword=******
keyAlias=gechuang
keyPassword=******
```

## 许可

Apache License 2.0，见 `LICENSE` 与 `THIRD_PARTY_NOTICES.md`。

本项目参考并基于 NeXtep（https://github.com/lujinxin/NeXtep）二次开发。
