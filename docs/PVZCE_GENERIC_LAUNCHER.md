# PVZCE Launcher 通用 Java / LWJGL 入口

这是基于 FCL-Team/FoldCraftLauncher 的开发分支实现，不是正式发布。原项目源码、许可证和作者归属保留。这里不提供游戏 JAR、虚构主类或游戏下载链接。

面向 `pvzce/pvzce` 的专用预设、游戏扩展目录和新增输入入口见 [PVZCE 游戏适配](PVZCE_GAME_SUPPORT.md)。

## 使用

1. 安装开发 APK 后从桌面打开 **PVZCE Launcher**。首次进入直接显示通用配置页，不需要 Minecraft 账户、版本 JSON、下载器或整合包。
2. 选择 Android JRE（jre8、jre17、jre21 或 jre25），点击 **Install / repair selected bundled runtime**。仅从 APK 自带资产安装该 JRE 以及 LWJGL、Cacio、JNA；不会联网下载。安装失败会显示错误，不自动继续启动。
3. 用 **Import JAR / dependency files** 从 Android 文档选择器导入真实文件。文件复制到应用私有 `files/games/imported`；不覆盖同名文件。一个文件默认填入 JAR，多文件填入 classpath。可用应用 DocumentsProvider 管理游戏数据。需要额外资源目录时，通过该 Provider 建立目录并复制资源。
4. 填写 JAR 或 classpath、真实主类、JVM 参数、程序参数、工作目录，选择渲染器与 LWJGL 版本。classpath、JVM 参数、程序参数均为**每行一个完整参数**，不做 shell 展开或空格分词；路径中的空格不用额外引号。空配置字段表示无参数；尾部空行会报错。工作目录须为存在、可读写的绝对路径；JAR/classpath 相对路径以工作目录解析。
5. 若主类为空，JAR 必须具有真实的 `Main-Class`。Android 入口读取实际 Manifest 后将它与 bundled LWJGL 一起组成 classpath；主类非空时直接使用填写的主类。Manifest 的依赖关系仍由 Java JAR 类加载器处理。不要使用 JVM 参数字段指定 `-jar`、`-cp`、模块入口或 argfile；配置校验会拒绝这些入口冲突。
6. 点击 **Save configuration** 持久化，或 **Validate and launch** 校验并启动。配置只保存于该包的 SharedPreferences。
7. 游戏窗口提供触摸鼠标、鼠标移动/滚轮/左右键、硬件键盘和 Esc/Enter/Space 按钮；指针捕获时触摸发送相对位移。Log 可显示日志，Exit 可结束整个内嵌 JVM 进程。Java 退出后显示退出码和日志路径；关闭后重新打开启动器才能启动下个会话。

独立窗口已增加系统输入法文字提交对话框（BMP 中文等字符）、工具/节奏虚拟键、左右键切换与滚轮。完整可编辑触屏控制器、手柄映射和 AWT 专用菜单仍保留在上游源码中，目前没有接入这些完整交互界面。当前入口侧重 GLFW/LWJGL 路径；Cacio/AWT 支持参数与 SDL surface/JNI 桥保留，但未做 Android 实机兼容验证。宿主 JVM 在同一进程内运行，不能承诺 Activity/进程被系统回收后的会话恢复。

## 架构

- `generic/JavaLaunchSpec.java`：纯 Java 配置、文件与入口校验、字面参数及 classpath 生成，无 Android/Profile/Account/Version JSON 依赖。
- `generic/GenericLauncherActivity.kt`：独立表单、配置持久化、SAF 文件导入、APK 本地运行时安装。
- `generic/GenericJavaLauncher.kt`：Android JRE 架构检查，Cacio 参数、Android LWJGL JAR/natives、JVM 环境配置；调用既有 `FCLauncher.launchJarExecutor`。
- `generic/GenericJvmActivity.java`：独立 TextureView 窗口、GLFW 输入/SDL surface 桥、日志/退出处理；不读写 Minecraft options/profile。
- 保留 `FCLauncher`、`FCLConfig`、`FCLBridge`、`VMLauncher`、`org.lwjgl`、`org.libsdl`、native 渲染、JRE 安装等后端。Android patched LWJGL 放在用户 classpath 之前，避免桌面 GLFW 覆盖 Android 版本。
- JVM 初始化异常会送到日志与退出提示，避免只有后台异常、界面无反馈。

## 功能隔离

`BuildConfig.MINECRAFT_FEATURES=false` 与 Manifest `minecraftEnabled=false` 是当前统一策略（在 `FCL/build.gradle.kts` 设置，`LauncherFeatures` 供源码调用）。默认禁用旧 Splash/Main/Web/Controller/Shell/JVM 页面、整合包导入 alias、下载服务、安装器进程服务和 Terracotta VPN 服务。唯一桌面入口是通用 Launcher。

旧 Minecraft 认证、版本/资产、模组/整合包界面和启动参数代码不被通用入口初始化；既有 launcher 网络运输 `NetworkUtils.createConnection` 在打开连接前拒绝请求，更新检查直接返回空任务，`launchMinecraft` 与 API installer 加入执行护栏。源码保留供未来有针对性重新接入。此策略是入口和网络栈隔离，不是对每个旧类的删除或全局字符串替换；一些旧类直接网络代码仍存在，但当前全部属于禁用、不可到达的旧界面。用户自行提供的 Java 游戏自身联网不受该宿主 feature gate 限制。

未来恢复 Minecraft 功能需要同时更改 build flag 与 Manifest 策略，并重新审计入口、后台请求、许可和配置。仅修改一个布尔值不构成已经验证的恢复流程。

## 包名与共存

| 构建类型 | applicationId | FileProvider |
| --- | --- | --- |
| release | com.pvzce.pvzl | com.pvzce.pvzl.provider |
| debug | com.pvzce.pvzl | com.pvzce.pvzl.provider |
| fordebug | com.pvzce.pvzl.debug | com.pvzce.pvzl.debug.provider |

DocumentsProvider 和 crash init provider 也使用 `${applicationId}`。所有变体与原 FCL（包括 FCL debug）身份不同；PVZCE release/debug 彼此包名相同，不能宣称三种 PVZCE 变体全部可同时安装。

`namespace=com.tungsten.fcl` 与既有 Java/JNI/JVM 桥接类保留；它们是编译/二进制 ABI 名称，不是安装 applicationId。Manifest 相对类名由该 namespace 展开。没有新增自定义权限；旧 file/content 整合包 Intent alias 默认关闭。CrashReporter 的旧 action 常量通过 `setPackage(context.getPackageName())` 限定当前包。

日志、游戏、控制器和 share 默认落在应用私有 `files`，runtime 在 `getDir("runtime")`，cache 在本包 cache/pvzce；旧 `/FCL` 写入点也改为相应 FCLPath。TouchController socket 使用运行时 packageName，避免跨包抢占同一 socket。FileProvider 不再授权整个外部存储根，只声明本包 files/cache/external-files。

签名配置不读取 FCL 发布密钥或其密码，也不使用仓库 debug-key；debug/fordebug 用 Android 标准本地 debug 签名，release 未签名。不发布正式版本。资源/主题和历史 About/README 中的 FCL 归属保留。

## 架构与许可

项目声明 minSdk 26、targetSdk 34、compileSdk 35、Java/Kotlin JVM 17、Gradle wrapper 8.14.4、NDK 27.0.12077973，支持 arm、arm64、x86、x86_64 资产与 native 路径。运行时需与设备 ABI 匹配；这不代表已逐架构构建或实机验证。

根 `LICENSE` 为 GPL-3.0，现有文件还包含 GPL-3.0-or-later 作者头。保留原 README 的 HMCL、FCL-Team、Amethyst/Pojav、Android、LWJGL、OpenJDK、Cacio、GL4ES/Mesa、SDL 等归属与第三方清单。读取并保留 `ZipFileSystem/src/main/resources/LICENSE`（Oracle BSD 风格条款）及 `androidnsbypass/LICENSE*`（包括 BSD-2-Clause）。原 README 同时列出 Terracotta AGPL-3.0、EasyTier/TouchController LGPL-3.0 和 LWJGLX unknown license；禁用入口不等于移除了打包依赖或解决许可问题。尤其 LWJGLX 许可未明与第三方完整二进制 notice 清单仍需正式分发前核查。本次没有重新授权任何上游文件或宣称所有依赖均为 GPL-3.0。

## 验证

- `bash tools/check_generic_java.sh`：JDK 17+ 独立测试，使用真实编译的无害 Echo fixture，检查 JAR/classpath/相对路径、字面参数、工作目录、JVM property，以及无效文件/入口/参数拒绝。
- `python tools/audit_generic_launcher.py`：源码 XML、变体 package/provider、旧组件禁用、默认路径、feature gate 和 JNI 名称静态审计。**不是最终 merged Manifest 检查。**
- `python tools/check_changed_java_style.py`：使用已缓存 Checkstyle 10.12.5 检查改动 Java，提交后设置 `PVZCE_STYLE_BASE` 为本分支基准 SHA。
- Android 相关命令与本次真实结果见交接文档。测试 fixture 不是用户游戏，云端 Java 验证不是 Android native/JVM/图形验证。

## 任务二验证更新

已实际完成 arm64 debug/fordebug APK 构建、APK Signature Scheme v2 验证及 APK 内 Manifest 解码审计。`tools/audit_built_manifests.py` 可检查真实合并或解码后的 Manifest，包含 AndroidX 自动生成的 provider 与动态接收器签名权限。通用启动配置新增 7 项工程单元测试，独立行为测试增至 43 项；损坏 classpath/JAR Manifest 主类与入口冲突参数现在会被提前拒绝。每次 Java 会话重新开始日志，避免混入上次会话内容。

完整工程单元测试仍有 2 项未改动控制布局转换金样失败；无可用的 Android 设备执行证据，不能宣称完成实机共存、图形、输入或游戏兼容性测试。架构、许可与高级输入界面限制继续适用。完整命令、失败原因、产物哈希与交接见 [任务二报告](PVZCE_TASK2_VERIFICATION.md)。
