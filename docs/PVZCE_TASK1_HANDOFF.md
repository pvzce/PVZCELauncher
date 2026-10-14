# 任务一实际交接

- 仓库：https://github.com/pvzce/PVZCELauncher
- 分支：`dev/pvzl-generic-java-20261014-01`
- 默认分支与基准：`main`，`bfb304fc8b4b3b1bd276f82f22fb4a9bcb2a5f72`
- 执行时账号：`CSneko`；API 实际核实 pull/push/admin；`fork=true`，parent 为 `FCL-Team/FoldCraftLauncher`。
- 原 smoke 分支和提交没有改动；不向 upstream 写入、不创建 PR、不修改 main、不强推。
- 实现完整 SHA：提交后补记（任务二必须以远端实际 HEAD 为准）。
- 状态：实现、独立 Java 行为检查、源码审计与改动 Java Checkstyle 完成；Android 构建因 SDK 缺失阻塞。不是用户游戏兼容性结论。

## 修改

详见 [架构与使用](PVZCE_GENERIC_LAUNCHER.md)。新增通用配置/校验、离线 JRE 安装、LWJGL/SDL 窗口与基础输入，集中 feature gate，Minecraft UI/services/network/update/launch 隔离；改 applicationId、provider resource、路径/socket/文件共享与签名；保留 namespace、JNI/JVM 类名及许可/归属。

## 已执行

1. `git ls-remote https://github.com/pvzce/PVZCELauncher.git HEAD`：获取真实默认 HEAD；从目标 Fork shallow clone，未重复 Fork。仓库没有受版本管理的 AGENTS.md。
2. `python tools/audit_generic_launcher.py`：通过全部 source XML/identity/disabled entry/network/default-data/JNI-name 审计；这不是最终 merged Manifest 检查。
3. `LD_LIBRARY_PATH=/usr/lib/jvm/java-17-openjdk-amd64/lib PVZCE_JAVA=/usr/lib/jvm/java-17-openjdk-amd64/bin/java bash tools/check_generic_java.sh`：38 项行为检查通过，含四次真实无害 fixture JVM 启动；没有假称测试真实 PVZCE 游戏或 Android native。
4. `GRADLE_USER_HOME=/workspace/scratch/02f98f1528ad/build-tools/gradle LD_LIBRARY_PATH=/usr/lib/jvm/java-17-openjdk-amd64/lib PVZCE_JAVA=/usr/lib/jvm/java-17-openjdk-amd64/bin/java python tools/check_changed_java_style.py`：使用真实 Checkstyle 10.12.5 与仓库 config/checkstyle/checkstyle.xml，12 个新增/修改 Java 文件全部通过。先尝试独立 Gradle Checkstyle worker，但 worker 的 Java 无法加载 libjli.so；改为相同缓存依赖直接运行 Checkstyle main，未弱化规则。
5. `git diff --check`：通过。

## 构建与环境

当前可读环境提供 Java 17.0.20，`javac` 二进制未提供但 `jdk.compiler` module 可用，使用 `java -m jdk.compiler/com.sun.tools.javac.Main` 完成独立编译。Git 2.51.1，云端磁盘初检可用约 29 GiB。

缓存在当前可写 workspace 的 build-tools/gradle，ANDROID_USER_HOME 在 build-tools/android-user，java.io.tmpdir 在 build-tools/tmp。继承实际 HTTP/HTTPS proxy，每次命令重新读取代理环境；导入环境提供的代理 CA 到私有 truststore，未禁用 TLS 校验。原历史 build-tools 路径不能当作当前缓存。

尝试命令：

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
LD_LIBRARY_PATH=/usr/lib/jvm/java-17-openjdk-amd64/lib \
JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/workspace/scratch/02f98f1528ad/build-tools/tmp \
GRADLE_USER_HOME=/workspace/scratch/02f98f1528ad/build-tools/gradle \
ANDROID_USER_HOME=/workspace/scratch/02f98f1528ad/build-tools/android-user \
bash gradlew :FCL:testFordebugUnitTest :FCL:checkstyle :FCL:assembleFordebug \
  -Darch=arm64 --no-daemon --console=plain
```

早期重试先后遇到未继承 proxy 的 Network unreachable、默认 /tmp 不存在，以及不同工具调用间 proxy 端口变化造成 Connection refused；已修正临时目录、proxy/TLS 继承后再次执行。最终重试已成功解析 Gradle 插件并配置 FCL，然后 **BUILD FAILED in 2m 10s**，明确原因：`SDK location not found`。无 APK，Android unit tests、主项目 Checkstyle、Java/Kotlin Android 编译、native 构建和最终 merged Manifest 检查都没有运行完成。修正后的构建配置至少已通过 Gradle Kotlin DSL 配置解析，不能据此宣称应用源码编译通过。

本次未下载 SDK/NDK/CMake；完整工具链准备属于后续任务二。未使用云端 CI 或正式签名/发布。

## 任务二必须核实

- 读取本分支实际远端 HEAD 与交接状态；不能把 smoke 当完整实现。
- 独立进行完整 Android Java/Kotlin 编译、Checkstyle、测试、native 构建和 APK merged Manifest/权限/provider/组件/数据共存验证；未执行不能写为通过。
- 校验 Android 运行时安装、native JVM、LWJGL 渲染、输入和生命周期。无真实游戏文件时不得宣称真实游戏兼容。
- 完整触屏控制器/手柄/软件键盘/AWT 菜单尚未接入此独立窗口，说明限制并按真实结果修复。
- 许可完整性（尤其原清单的 unknown LWJGLX 与 AGPL/LGPL 依赖）保留待核查。
- SDK/NDK/CMake 是否可用须重新检查；只在许可内下载工具，不绕过网络。CI 权限历史失败，未尝试 dispatch，不假称可用。
