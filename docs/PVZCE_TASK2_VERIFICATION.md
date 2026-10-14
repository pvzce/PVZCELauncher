# PVZCE Launcher 任务二实际验证报告

## 结果与分支

2026-10-14 在云端工作区执行，没有依赖用户电脑。

- 仓库：<https://github.com/pvzce/PVZCELauncher>
- 开发分支：`dev/pvzl-generic-java-20261014-01`。
- 读取的任务一远端 HEAD：`ed7172f54c848bcdfcc40262f4bf1aed20cf643d`，实现提交 `03e884fbdb4dc540504ddb087a4ad6602e03eef3`。任务一文档明确已结束，未并发修改运行中的任务。
- 当前账号重新确认 `CSneko`，目标仓库 `fork=true`，parent 为 `FCL-Team/FoldCraftLauncher`，默认分支 main，具备 pull/push/admin。
- 未重复 Fork、不向上游提交、不创建 PR、不强推、不修改 main 或 smoke 分支。推送采用远端 HEAD 检查与 expected_sha 条件更新。
- **Android 编译与 arm64 APK 构建成功，实际 APK Manifest 和签名验证通过；完整单元测试并非全通过。**
- 没有实际游戏文件，没有 Android 安装/图形/输入/游戏运行成功证据。APK 是开发测试产物，不是正式发布；版本号继承基准项目，不代表 PVZCE 正式版本。

本报告所在提交是本次修复与验证交接；完整最终 SHA 在提交后另行补记，并以远端实际 HEAD 为准。

## 工具链与实际环境

初检 Git 2.51.1、系统 OpenJDK 17.0.20；SDK/NDK/CMake 不存在，先前缓存也已清空。磁盘初检可用约 29 GiB。没有读到受版本管理的 AGENTS.md。

Adoptium JDK 元数据接口返回 HTTP 403，停止该下载，没有改用绕过限制的端点。使用环境已有 OpenJDK 17 的 `jdk.compiler`、`jdk.jlink`、`jdk.jartool` modules，在可写工具目录建立正常 launcher，补齐 javac/jlink/jmod/jar 调用入口并修正 libjli.so 加载环境；实际 javac/jlink/jmod 版本均为 17.0.20。没有变更系统目录。

从官方 Android repository XML 读取版本/下载地址/校验和。command-line tools 19.0 下载 SHA-1 校验通过；最新工具切换到 Android CLI 的下载器连接失败，因此使用同一官方仓库中可用的 19.0 Java sdkmanager。读取 SDK 许可并按已授权工具安装范围安装指定包，没有启用遥测或 update-all。

| 工具 | 实际值 |
| --- | --- |
| Gradle | 项目 wrapper 8.14.4 |
| SDK Platform | android-35，revision 2 |
| Build Tools | 35.0.0 |
| NDK | 27.0.12077973 / r27，clang 18.0.1 |
| CMake | 3.22.1-g37088a8 |
| Platform Tools | Gradle 正常安装 37.0.1 |
| JDK | OpenJDK 17.0.20 |

缓存、SDK、Android user home、临时目录均在当前可写 workspace 的 `build-tools`。每次命令重新继承 HTTP/HTTPS proxy，环境代理 CA 导入私有 truststore，未关闭 TLS 验证。sdkmanager 包安装曾遗留嵌套 staging 路径；在该新建工具目录中恢复正确的 build-tools/NDK 布局并保留残留备份，随后 aapt2、clang、CMake 与实际 native 构建通过。没有删除用户项目文件或改动 Git 源文件来掩盖工具链错误。

所有构建使用下面的环境变量，另外私有用户 Gradle properties 设置实际 proxy 和 truststore：

```sh
export JAVA_HOME=/workspace/scratch/02f98f1528ad/build-tools/jdk17
export ANDROID_HOME=/workspace/scratch/02f98f1528ad/build-tools/sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME=/workspace/scratch/02f98f1528ad/build-tools/android-user
export GRADLE_USER_HOME=/workspace/scratch/02f98f1528ad/build-tools/gradle
export JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/workspace/scratch/02f98f1528ad/build-tools/tmp -Djavax.net.ssl.trustStore=/workspace/scratch/02f98f1528ad/build-tools/cacerts'
export PATH="$JAVA_HOME/bin:$PATH"
```

## 实际命令与结果

| 命令（均在仓库运行） | 实际结果 |
| --- | --- |
| `PVZCE_JAVA="$JAVA_HOME/bin/java" bash tools/check_generic_java.sh` | 43 项通过；4 次真实无害 fixture JVM 启动，非用户游戏、非 Android JVM |
| `python tools/audit_generic_launcher.py` | source XML/变体身份/默认数据/JNI 名称/入口网络隔离通过 |
| `bash gradlew :FCL:testFordebugUnitTest :FCL:checkstyle :FCL:assembleFordebug :FCL:processReleaseMainManifest :FCL:processDebugMainManifest -Darch=arm64 --no-daemon --console=plain --max-workers=2` | 主应用 Kotlin/Java 编译通过；完整单元测试 33 项、2 项失败，整体 BUILD FAILED；没有将此写为通过 |
| `bash gradlew :FCL:checkstyle :FCL:assembleFordebug :FCL:processReleaseMainManifest :FCL:processDebugMainManifest -Darch=arm64 --no-daemon --console=plain --max-workers=2` | BUILD SUCCESSFUL in 40s；105 actionable tasks；主项目 Checkstyle、CMake/NDK、fordebug APK 和三个实际 merged Manifest 完成 |
| `bash gradlew :FCL:testFordebugUnitTest --tests com.tungsten.fcl.generic.JavaLaunchSpecTest :FCL:assembleDebug -Darch=arm64 --no-daemon --console=plain --max-workers=2` | BUILD SUCCESSFUL in 1m 36s；146 actionable tasks；7 项通用启动配置测试通过，debug APK 完成。过滤测试不代表完整测试集通过 |
| `apkanalyzer manifest print <debug或fordebug APK>` + `python tools/audit_built_manifests.py <解码 Manifest> <release merged Manifest>` | 两个最终 APK 及 release 合并 Manifest 的包名、组件、providers、自定义权限、入口禁用与 placeholder 消除通过 |
| `apksigner verify --verbose <debug或fordebug APK>` | 两个 APK 均 Verifies，v2=true；标准 Android Debug 证书，非 FCL 原签名 |
| `aapt2 dump badging <APK>`、ZipFile 资产检查、`readelf -h/-Ws <libfcl.so>` | 应用名 PVZCE Launcher，唯一桌面入口 GenericLauncherActivity，arm64-v8a；37 个 native libraries；JRE 8/17/21/25 和两个 LWJGL 版本资产存在；libfcl 为 ELF64/AArch64，保留 JNI redirectStdio/setupExitTrap 导出 |
| `git diff --check` | 通过 |
| 云端 `adb devices -l` | ADB server 未能启动：execl ENOENT / server didn't ACK。没有安装 APK、没有连用户电脑，没有设备运行结果 |

早期失败包括 SDK 缺失、缺少 jlink launcher、测试代码使用 Android API 中没有的 Files.writeString，均先记录再修复重跑；没有以跳过源码编译或禁用 TLS 的方式制造成功。完整单元测试的失败另见下一节。

## 完整单元测试：31/33，而非全部通过

- 新增 `JavaLaunchSpecTest`：7 项，0 failure、0 error。
- GameComponentTypeTest：3 项通过。
- BMCLAPIDownloadProviderTest：10 项通过。
- RealLayoutStressTest：4 项通过。
- ControlConverterTest：9 项中 2 项失败，分别是 `goldenFclToZlByteMatch` 和 `goldenFclToZlSemanticMatch`。

差异定位到 `$.layers[1].joystickButtons[0].deadZoneRatio`：金样 0.5，实际 0.0；另有 `canLock` 金样 true、实际 false。核对任务一基准 `bfb304fc8b4b3b1bd276f82f22fb4a9bcb2a5f72` 后，controlconverter 源码、测试源码和 fixture 均无差异，也不引用新增 LauncherFeatures/FCLPath。当前 FCL DirectionEventData 的默认 canLock=false；fixture 没有显式给出对应字段。未擅自更新 Go 金样或改变未接入的高级控制转换语义来获得绿色测试。此兼容性分歧仍待专门核查，完整测试失败如实保留。

## 本次修复与文件变化

- `JavaLaunchSpec.java`：损坏 classpath archive 与非法 JAR Manifest Main-Class 在启动前拒绝；阻止 `--`、`--dry-run`、`--list-modules` 等结束/改变入口的 launcher 参数进入 JVM options 字段。扩展测试先复现损坏 classpath 被错误接受，再修复。
- `GenericJvmActivity.java`：每个会话首次日志重新开始文件，同步日志写入，避免追加上次会话和并发日志写入交叉。
- `FCL/src/test/.../JavaLaunchSpecTest.java`：新增 7 项工程单元测试。
- `tools/GenericLaunchChecks.java`：38 增至 43 项独立行为检查。
- `tools/audit_built_manifests.py`：新增实际 Manifest 检查，不以源码 placeholder 替换冒充最终检查。
- `tools/check_changed_java_style.py`：过滤与 Checkstyle 10.12.5 不匹配的缓存 ANTLR runtime；主要验证仍使用真正 `:FCL:checkstyle`。
- README、架构说明与本报告更新；许可证、归属、第三方清单、namespace/JNI/JVM 类名不改变。

## 身份、隔离、架构与许可

| 变体 | 实际 applicationId | 实际 FileProvider | 状态 |
| --- | --- | --- | --- |
| debug | com.pvzce.pvzl | com.pvzce.pvzl.provider | arm64 APK 已编译并解码检查 |
| fordebug | com.pvzce.pvzl.debug | com.pvzce.pvzl.debug.provider | arm64 APK 已编译并解码检查 |
| release | com.pvzce.pvzl | com.pvzce.pvzl.provider | merged Manifest 已检查；未构建/签名/发布正式版本 |

DocumentsProvider、crash provider、AndroidX startup provider 和自动生成的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` 均以前述包名为前缀。两个不同 PVZCE 包之间没有 authority/自定义权限冲突，也不沿用 FCL 安装包标识。保留 `com.tungsten.fcl` namespace 及 JNI class 名是 ABI 需求，实际 Android component owner 是 PVZCE applicationId。debug/release 彼此同包名，不宣称它们可同时安装。

默认数据仍在各包 files/cache/getDir(runtime)，TouchController socket 按 packageName，FileProvider 不暴露整个共享存储。Manifest 中仍声明部分原项目存储/网络/音频/前台服务权限，不能宣称权限已最小化；源码默认目录与权限标识隔离已检查，但没有真实设备上的安装共存/数据迁移证据。

架构仍为独立配置页 → JavaLaunchSpec 校验 → GenericJavaLauncher → 既有 FCLauncher/FCLBridge/VMLauncher → GLFW/SDL surface，保留 Android JVM/JRE、渲染、输入、运行时安装能力。Minecraft 账户、版本/资产/模组/整合包页面、后台下载、更新及启动入口默认 gate 关闭，相关代码保留。无 Minecraft profile/version JSON 依赖；用户自己的游戏 Java 网络不受旧 launcher gate 限制。

根 GPL-3.0 与已有作者头、Oracle ZipFileSystem BSD 风格、androidnsbypass MIT/BSD 条款原样保留。原清单的 Terracotta AGPL-3.0、EasyTier/TouchController LGPL-3.0、OpenAL LGPL、OpenJDK GPLv2+Classpath Exception、LWJGL BSD 等继续适用；原清单 LWJGLX unknown license 和二进制 notice 完整性仍待正式分发前审核。禁用功能不等于移除其许可义务。

## 产物、限制与未完成项

| 产物 | 字节数 | SHA-256 |
| --- | --- | --- |
| PVZCE-Launcher-debug-1.3.3.9-arm64-v8a.apk | 179247613 | 8b885a3740497b84e249355667394384dbbc74447d1d427ea8fdf9a6e7c41faa |
| PVZCE-Launcher-fordebug-1.3.3.9-arm64-v8a.apk | 179247733 | 090bec143e403b4bd5b0805c53b89b65599bf5b6eadf170cdd77349c45dbc3d4 |

两份 APK 已在本次云端 workspace 生成并校验；下载文件保存流程因授权配置不可读失败，没有成功保存为可交付下载文件，也没有绕过该边界或改用 GitHub release/大文件 Git 提交。本次只提交源码、测试和报告；无正式发布、无 CI dispatch。临时 workspace 产物不保证跨会话保留，应按已提交源码与本报告命令重建。

尚未完成：真实 Android 安装与运行、JRE 安装/GLFW/SDL/输入生命周期实机验证、用户游戏兼容性、其他 ABI 构建、上述 2 项控制转换金样分歧、完整触屏控制器/手柄映射/软件键盘/AWT 菜单接入、第三方完整许可审计。构建还保留上游 LWJGL AAR namespace 重复和 libglxshim.so 两个来源的警告（当前 AGP 选择 app module 版本），不能据构建成功声称这些运行时兼容性已验证。
