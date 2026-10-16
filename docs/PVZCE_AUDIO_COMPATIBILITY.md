# PVZCE 新版无声与卡顿：启动器修复候选（2026-10-16）

## 范围与版本依据

仅修改 `pvzce/PVZCELauncher`，基于 main
`88fcc7fc4a368f90e1bc5ebeaebbe580e9f4512c`。游戏本体按用户要求视为正常，只读参考，
不修改游戏。当前没有用户实际运行的新旧 JAR、设备型号或故障日志，不能声称已复现用户问题。

游戏仓库没有 GitHub Releases。上次适配记录引用的
`e40b28edca5265b9537d22c9a220ce51bc68b2e7` 与本轮检查时 master
`b8fe0227f9367ef0195c7f75d3bd7c5db725aece` 只是**源码对比基准**，不是已确认的正常/异常版本。
两者之间 `SoundEngine` 的变化集中在声音资源命名空间与事件解析；初始化仍走
LWJGL `ALC10.alcOpenDevice`，未发现足以把用户故障直接归因于该变化的证据。

## 可确认的问题与修复

1. `DefaultLauncher` 原有 FCL 游戏路径显式传入
   `-Dorg.lwjgl.openal.libname=<APK nativeLibraryDir>/libopenal.so` 和
   `-Dorg.lwjgl.system.allocator=system`，通用 Java/PVZCE 路径缺少这两项。
   APK 的 `openal-soft-release.aar` 将 OpenAL 放在 `jni/<ABI>/libopenal.so`，
   `lwjgl-3.4.1-natives-release.aar` 的 LWJGL assets 原生目录里没有 OpenAL。
   新增 `LwjglRuntimeOptions`，保留现有 LWJGL/GL 路径，补齐与 FCL 一致的这两项默认值。
   用户明确提供的同名 JVM 参数仍在默认参数之后，保持覆盖能力。

   LWJGL 的 ALC 按 `org.lwjgl.openal.libname` 选择后端；绝对库名会直接加载该文件，
   相对库名可能先从游戏 fat JAR 中发现、提取桌面原生库。`FCLauncher` 预加载 OpenAL
   本身不能表达 LWJGL 之后必须选中哪份库。固定路径排除这种选择歧义。
   system allocator 避免在没有相应模块/原生库时尝试桌面分配器，但它**不是已证实的卡顿根因**。

2. `GenericJvmActivity.onLog` 原先每条日志新建/关闭 `FileWriter`，然后向主线程提交一个
   拼接并替换整段 TextView 文本的任务，日志面板隐藏时也执行。新增 `GameSessionLog`：
   每个会话复用一个 UTF-8 writer，完整文件仍逐条刷新；屏幕仅保留最近 16000 个 UTF-16
   单元且不从代理对中间截断，同一批日志仅请求一个 100ms 后的 UI 更新，隐藏时不设置 TextView。
   打开面板和 JVM 退出时立即显示最新尾部；JVM 返回后继续接收 stdout 管道中尚未读完的诊断，
   用户关闭会话/销毁界面时关闭 writer，之后的回调不重新打开文件。
   写文件失败仍保留屏幕日志。此处修复的是可确认的日志负担，未把它等同用户设备的全部性能问题。

## 验证

- `bash tools/check_audio_compatibility.sh`：通过。真实子 JVM 验证参数与含空格/中文的路径、
  显式用户覆盖；真实文件验证完整 UTF-8 输出、替换旧会话、1 万条突发仅一个刷新请求、
  有界尾部、代理对边界、多个日志线程、磁盘路径不可用、退出后的回调。
- `bash tools/check_pvzce_profile.sh`：通过。
- `python3 tools/audit_generic_launcher.py`：通过。
- `git diff --check`：通过。
- `bash tools/check_generic_java.sh`：完整 JDK 17 下通过。首次在环境精简 JDK 21 上因缺少
  `--release 8` 的目标平台数据失败；随后下载官方 Temurin JDK 17 并验证 SHA-256，
  使用该 JDK 重跑通用启动、PVZCE 预检和音频/日志检查，全部通过。
- Checkstyle 10.12.5：本轮三个 Java 生产文件及独立检查 harness 通过。
- Linux 加载选择探针：使用仓库实际 bundled LWJGL 3.4.1 Java JAR、Maven 的同版本 Linux core
  natives、桌面 OpenAL natives JAR，以及系统 OpenAL 的独立副本。设置 `ALSOFT_DRIVERS=null`，
  不播放声音。固定路径前，`/proc/self/maps` 显示加载的是临时提取目录里的桌面 `libopenal.so`；
  固定路径后，映射为指定的独立副本；两次 null device 均能打开。patched core 在 Linux 主机上
  另有预期的 `pojavexec` 缺失提示。这只验证**加载选择**，不是 Android ABI、OpenSL 或游戏验证。
- APK 构建与检查结果将在真实 CI 执行后补记，不沿用以前提交的 APK 结果。

## 真机复核

使用用户实际正常的旧 JAR 和异常的新 JAR，在同一设备、相同工作目录/内容包、渲染器、分辨率和
JRE 下对比；不要删除已有存档。记录 JAR 校验值和完整会话日志。默认参数应指向 APK 自带 OpenAL，
确认高级 JVM 参数没有旧的桌面音频路径覆盖。必要时临时加 `-Dorg.lwjgl.util.DebugLoader=true`，
核实实际加载路径后再关闭诊断。检查音乐/音效、进出关卡、暂停恢复以及隐藏/打开日志时的帧时间。
若仍无声，收集 OpenAL/STB 初始化或解码错误；若仍卡顿，结合帧时间、GC 与渲染器信息继续定位。
本轮没有 Android 设备、实际游戏 JAR 或实测 FPS/听感证据，不能宣称端到端问题已彻底解决。
