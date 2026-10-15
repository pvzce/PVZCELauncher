# PVZCE FCL 界面交接（2026-10-15）

状态：本轮工作已结束。FCL 界面实现、相关检查与 arm64 开发 APK 已完成并推送；**实际 UI 验证被软件模拟器系统服务异常阻塞，四项 instrumentation 未开始、没有实际截图；未合并 main**。不是仍在运行，也不声称 UI 或真实游戏验证成功。最终构建源码为 `9a461a099302558a20d492662ae03e708ff044bc`；本文后续交接提交仅改 Markdown，不改变该 APK 源码。

- 仓库：https://github.com/pvzce/PVZCELauncher
- 开发分支：`dev/pvzl-fcl-ui-20261015-01`
- 最新 main 起点 / 基准完整 SHA：`bc9639cca2ce67ea63e630cc982501b3d75f8f04`。
- 首次实现提交 SHA：`6122b159a464c6df71ef73bb77749b501e57d18c`；后续修复与最终验证 SHA 见下方记录。
- 核查账号 CSneko；目标为 FCL-Team/FoldCraftLauncher 的 Fork，main 默认，pull/push/admin 均可用。无 tracked AGENTS.md。未 Fork、未操作上游或旧 smoke 分支。
- 游戏只读核查 master `e40b28edca5265b9537d22c9a220ce51bc68b2e7`：游戏 build.gradle 的 KnotClient/native-access/shadowJar；PvzceGameProvider 的 gameDir；PvzceResourceManager 的目录包；KeyBindings 的工具/节奏/聊天。没有修改游戏仓库。

## 界面与真实行为

布局从原 FCL `activity_main.xml` 的左菜单、中央页面、右启动卡片适配为 `activity_pvzce_launcher.xml`。沿用 FCLActivity、FCLMenuView、FCLButton、FCLEditText、FCLCheckBox、FCLTextView、FCLDynamicIsland 与 ThemeEngine，不使用依赖 Minecraft 状态的 UIManager/MainActivity。

| 可达操作 | 实际行为 |
| --- | --- |
| 主页 / 内容 / 设置 / 高级 / 日志 | 独立页面切换；返回键先回主页；页面索引与字段重建恢复 |
| 右侧选择 / 导入游戏 JAR | 系统 OpenMultipleDocuments → GameContentImporter.file → 私有 games/imported；PVZCE 模式一次一个、清空额外 classpath |
| 恢复 PVZCE 启动预设 | 确认后设 KnotClient、JRE25、patched LWJGL3.4.1、VirGL、工作目录；保留 JAR，不删除游戏数据 |
| 右侧运行时与渲染 | 设置页真实运行时、渲染器、LWJGL 选择，不进入旧版本设置 |
| 安装 / 修复所选运行组件 | RuntimeUtils 从 APK 资产安装所选 JRE、LWJGL、Cacio8/17 与 JNA；忙碌时禁用配置与执行控件 |
| 启动游戏 / 启动 Java | 原 JavaLaunchSpec → PvzceProfile.configure 或通用 validate → GenericJavaLauncher.prepare → GenericJvmActivity；校验错误显示真实原因 |
| 模组 JAR | 验证预设/工作目录 → SAF → GameContentImporter.file，检查 fabric.mod.json 后放 mods，不混入 classpath |
| 资源包 / 数据包目录 | 验证工作目录 → OpenDocumentTree → GameContentImporter.tree → resourcepacks/datapacks；不接受 ZIP，未完成拷贝不被游戏枚举 |
| 浏览游戏数据 | 检查实际目录 → 原 FCL FileBrowserActivity，FILE_BROWSER 模式，不是占位页或联网下载器 |
| 高级 / 保存配置 | 保留六个原配置字段、PVZCE/通用模式、runtime/renderer/lwjgl；保留旧 generic_launch 键，切后台也保存 |
| 刷新日志 / 导出日志 | 读取实际 latest_jar_executor.log 尾部 64 KiB；系统 CreateDocument 导出完整文件。无日志明确提示，不网络上传 |
| 主题模式 / 恢复植物绿 | 原 launcher.themeMode 与 AppCompatDelegate；ThemeEngine/DataStore 持久化主色。亮 #2E7D32 / 暗 #A5D6A7，对应白/黑按钮文字 |

账号、Minecraft 版本/资产下载、CurseForge/Modrinth/整合包、更新、旧控制器/大厅/下载进度等控件不被实例化。源码与隔离开关保留，未重开旧 Splash/Main/网络调用。第一次使用默认 PVZCE；已存在的通用配置和自定义颜色不被强制改写。

## 保留和许可

- applicationId 保持 `com.pvzce.pvzl`，fordebug 为 `com.pvzce.pvzl.debug`；Manifest、Provider、权限、私有目录、namespace/JNI/JVM 桥接名不改。
- 本轮不改 JVM 启动器、Android 运行后端、输入/导入校验；Java25、唯一 gameDir、native-access、中文 BMP 提交和工具/节奏键保持原逻辑。
- 原 FCL/HMCL 等归属、根 GPL-3.0 与第三方声明保留。无新增库、游戏源码/素材或游戏 JAR。LWJGLX 的既有许可待核查项继续见通用文档。
- 只构建 Android Debug 签名的开发 APK；没有使用原发布密钥或创建 Release。

## 本地检查与云端验证范围

本地云端 Java17 module compiler：

- `bash tools/check_pvzce_profile.sh`：44 项通过。
- `bash tools/check_generic_java.sh`：43 项通过，含无害 Echo JVM 真运行。
- `python3 tools/audit_generic_launcher.py`：源码 XML / 三变体身份 / MC 隔离 / JNI 审计通过。
- `git diff --check`：通过。

已有工作流已运行相关单元测试、Checkstyle、arm64 debug/fordebug 开发 APK、最终 Manifest/签名审计并成功；UI 部分在 Android30 x86_64 模拟器尝试运行 `PvzceLauncherUiTest`，检查真实 FCL Activity 导航、浅暗色对比度/截图、配置重建持久化、系统选择器与 FCL 文件浏览器路由、随附 JRE 安装、无游戏文件错误、真实日志读取。使用 x86_64 仅做 UI 测试，下载 APK 保持 arm64。

本地未检测到 SDK/NDK/emulator 或 /dev/kvm，未冒充本地构建和实机运行。无真实游戏 shadow JAR；任何模拟器 UI 成功不等于游戏渲染、音频、输入兼容性通过。既有两项 controlconverter 金样失败未修复，相关测试通过也不表示完整工程测试全绿。

## 首轮实际结果与修复

[run 38680027122](https://github.com/pvzce/PVZCELauncher/actions/runs/38680027122)，源码 `6122b159a464c6df71ef73bb77749b501e57d18c`：相关单元测试、Checkstyle、arm64 debug/fordebug APK、最终 Manifest 与签名步骤全部成功。整轮仍是 failure：UI 测试步骤在 `adb: command not found` 退出，未运行 instrumentation，也没有实际 UI 截图。进一步读取诊断发现 emulator 与 avdmanager 未共用 AVD 路径（Unknown AVD name）。已补装 platform-tools、将 platform-tools/emulator 加入 GITHUB_PATH，并显式统一 ANDROID_AVD_HOME，等待新 run。没有将 517 字节的诊断 artifact 当成 UI 成功证据。

同时复核并修正 FCLButton 构造会清除 XML minHeight 的行为：在入口中显式恢复最小 48dp 触控高度、右侧按钮长文本换行；导航选中标签增加高对比底色；FCLDynamicIsland 使用持续标题，避免原动画自动隐藏导致中央页面跳动；浏览器关闭无关外部选择按钮。增加实际打开 FCL 文件浏览器与日志导出选择器的 UI 验证，强化运行组件安装完成和具体缺失 JAR 错误的断言。

第二轮 [run 38680917302](https://github.com/pvzce/PVZCELauncher/actions/runs/38680917302)，源码 `304a0d654a0c231cb6e5705b8d8997ea4ce9c19b`：arm64 构建、相关检查和 Manifest/签名均通过；模拟器步骤失败，诊断收集卡在 adb 设备等待。给收集命令补上超时，分支内新提交取消已失效的旧运行，以获取诊断并继续修复。尚未因此合并 main。

操作进度 / 保存 / 导入结果改为右侧全局可滚动区域，避免切到设置或内容页后看不到处理状态。

模拟器配置进一步限制 userdata 2GiB / RAM 2GiB，使用当前官方文档的软件 `-gpu swiftshader` 与关闭 Vulkan（仅影响 UI 测试模拟器，不改 APK 运行后端），并在失败时直接打印 emulator 日志，避免诊断只留在无法读取的运行机上。来源：https://developer.android.com/studio/run/emulator-acceleration 与 https://developer.android.com/studio/run/emulator-troubleshooting 。被替代的排队 run 不视为成功测试。

## KVM 诊断与最终候选修正

[run 38682279088](https://github.com/pvzce/PVZCELauncher/actions/runs/38682279088)，源码 `3f931dd86f982c49e2b3e4d4c0c5ddf54b97030b`：两个 arm64 APK 与检查再次成功；模拟器明确报告 `This user doesn't have permissions to use KVM (/dev/kvm)`，等待设备超时。因此没有运行 UI instrumentation，也没有截图。1339 字节的 UI-named artifact 是诊断日志，不是截图证据。磁盘与内存检查不是失败原因。

不修改 KVM 权限或用户组。改为真实 `emulator -accel-check` 检查，缺少加速时尝试官方文档列出的 `-accel off` + `ANDROID_I_WANT_MY_TCG=yes` 软件模拟（官方说明不受支持且很慢）；采用已从 Google SDK 仓库 XML 核实存在的 Android30 AOSP default x86_64 镜像，启动最多等待 15 分钟，运行组件安装测试最多 10 分钟。不会把该尝试等同 KVM 可用或游戏设备兼容。参考：https://developer.android.com/studio/run/emulator-commandline#common 。

复核文件浏览器原按钮后增加默认关闭的 `gameDirectoryMode` 配置，仅 PVZCE 的游戏数据浏览启用：隐藏共享目录 / 外部选择 / 重复确认；原私有目录快捷键改为返回真实配置工作目录的“游戏目录”，保留管理、返回和关闭。UI 测试会真实打开该 Activity，执行返回上层后再用快捷键回到游戏目录。新增选项不改变其他旧调用者的默认行为。

在 Android 构建阶段先编译 `:FCL:compileFordebugAndroidTestKotlin`，这样即使模拟器环境不可用也能检验 UI 测试源码是否可编译。UI 标题补充无障碍说明、状态区域使用 polite live region。

截图保存优先使用 instrumentation 的 `additionalTestOutputDir`，由 AGP 在卸载测试 APK 前自动带回 host；工作流额外收集 `connected_android_test_additional_output`，并在 UI 测试成功后检查八张实际 PNG 是否存在且大于 1KiB。应用外部私有文件只用于手动 runner 的回退，不把卸载后失效的 adb pull 当作可靠截图通道。参考 Android 工具源码 UtpConfigFactoryTest / AndroidDeviceDescriptor 对 additionalTestOutputDir 的定义。

## 首个完整 UI 候选的独立 APK 核查

源码完整 SHA：[`1df359548ca778985153d7764b82735c00f0425c`](https://github.com/pvzce/PVZCELauncher/commit/1df359548ca778985153d7764b82735c00f0425c)。
实际工作流：[run 38683832870](https://github.com/pvzce/PVZCELauncher/actions/runs/38683832870)，job `116254495818`。

以下步骤已成功，与后续 UI 步骤的结论分开记录：

```bash
bash tools/check_pvzce_profile.sh                       # 44 项
bash tools/check_generic_java.sh                        # 43 项，含真实 Echo JVM
python3 tools/audit_generic_launcher.py
bash gradlew :FCL:testFordebugUnitTest --tests com.tungsten.fcl.generic.JavaLaunchSpecTest \
  :FCL:checkstyle :FCL:compileFordebugAndroidTestKotlin \
  :FCL:assembleDebug :FCL:assembleFordebug \
  -Darch=arm64 --no-daemon --console=plain --max-workers=2
```

Gradle 相关单元测试 XML 经下载后独立读取：7 tests、0 failures、0 errors、0 skipped。Checkstyle、UI 测试 Kotlin 编译与两个 arm64 APK 构建通过。不是完整测试集通过。

[实际 APK artifact 11949079177](https://github.com/pvzce/PVZCELauncher/actions/runs/38683832870/artifacts/11949079177)：358652997 字节，14 天保留，到期 `2026-10-29T05:01:51Z`（北京时间 2026-10-29 13:01:51）。已下载整个 ZIP，独立计算校验值，并与 GitHub digest、内部 SHA256SUMS 对照：

| 文件 | SHA-256 |
| --- | --- |
| artifact ZIP | `d3fd33b8ffdc683c9c5c5899d8892ed51b101ad1e889ac815d3334d8f52b9692` |
| debug APK | `7b894125eba11983d4043f20e1d260e236580f1f60d9c852342d56f99a8a5713` |
| fordebug APK | `a631c2ae9b6a57eff534b7bae11451995bb462cb8aeb90b46001b94de40e32c0` |

读取 ZIP 中的 BUILD_INFO.txt、JUnit XML、Manifest、签名、aapt2 badging 后重新执行：

```bash
python3 tools/audit_built_manifests.py debug-AndroidManifest.xml fordebug-AndroidManifest.xml
```

结果通过。实际包名分别 `com.pvzce.pvzl` / `com.pvzce.pvzl.debug`；document.provider、provider、crashreporterinitprovider、androidx-startup authorities 与动态接收器权限全部以各自 applicationId 为前缀且互不冲突；旧 Minecraft 组件保持禁用，独立入口仍为 GenericLauncherActivity。两份 APK 均为 Android Debug 签名。minSdk 26、targetSdk 34、compileSdk 35，versionCode 1339 / versionName 1.3.3.9。实际打开 APK ZIP 确认 native lib 只有 arm64-v8a，并存在 JRE25 的 bin-arm64 / universal / version 资产及 patched LWJGL3.4.1 JAR。没有声称已在同一设备上同时安装原 FCL 和这两个包。

## 本轮文件与保留范围

- `GenericLauncherActivity.kt`：FCL 组件、五页导航、配置、主题、全局状态与真实动作。
- `activity_pvzce_launcher.xml` / `pvzce_action_button.xml`：原 FCL 风格布局、侧栏、右侧启动卡片。
- `pvzce_ids.xml` / `pvzce_strings.xml` / `pvzce_colors.xml`（亮暗）：中文文案、稳定控件 ID、配色。
- `colors.xml` / `ThemePreference.kt`：FCL 默认主题色。
- `FileBrowser.java` / `FileBrowserActivity.java`：仅显式 gameDirectoryMode 调用启用的游戏目录快捷行为。
- `PvzceLauncherUiTest.kt`：四项实际 Activity instrumentation；不是网页原型或合成截图。
- `pvzce-build.yml`：原 wrapper 构建、相关检查、开发 APK、模拟器验证及证据上传。
- `README.md` / `PVZCE_GAME_SUPPORT.md` / `PVZCE_GENERIC_LAUNCHER.md` / 本文：使用、许可、实现与验证交接。

没有修改 JavaLaunchSpec/PvzceProfile/GenericJavaLauncher/GenericJvmActivity、输入桥、native 后端、Manifest、applicationId、许可证或游戏仓库。未覆盖旧 smoke 分支、未强推、未使用发布密钥。

被替代的 run `38681984467`（仅排队）与 `38683580317`（构建未完成）均为 cancelled，不能算成功构建；`38680917302` 的最终结论也是 cancelled，APK 构建已完成但 UI 步骤失败后诊断收集被后续分支提交取消。


## 软件模拟器实际结果与安装修复

`38683832870` 最终为 failure。软件模拟器实际在 324888 ms 后报告 boot completed，但 ddmlib 安装 x86_64 APK 时 `Failed to install-write all apks`，其后 UTP 查询 activity/package 服务不可用。下载 [诊断 artifact 11950060615](https://github.com/pvzce/PVZCELauncher/actions/runs/38683832870/artifacts/11950060615) 并核对 ZIP digest `b68c203d8e8a2373af1b5f9c7ba2cef6e1f45713978977595b9788f3b69ea7a0`：实际测试 XML tests=0，PNG=0。没有用零 tests/零 failures 当作成功。

修复限于测试环境：在启动模拟器之前用 wrapper 构建 x86_64 应用和测试 APK，避免与 TCG 争用 CPU；降低 UI 测试显示分辨率、userdata 4GiB；检查 activity/package 连续就绪；用官方 adb `install --no-streaming --no-incremental` 分别安装两个 APK，直接记录安装结果；`am instrument -w` 运行完全相同的四项 Activity 测试，必须出现 `OK (4 tests)` 且没有失败/进程崩溃标记，另有八张 PNG 检查。测试不被删除或跳过。直接 runner 的输出目录由 adb 在测试后收集，无自动卸载丢失问题。日志收集限定 main/system/crash，避免内核缓冲耗尽诊断超时。来源：https://android.googlesource.com/platform/packages/modules/adb/+/HEAD/docs/user/adb.1.md 和 https://developer.android.com/studio/test/command-line 。当前不推断安装失败根因是应用代码或磁盘，也不修改主机权限。


## 安装修复后的 APK 核查

源码 [`9a461a099302558a20d492662ae03e708ff044bc`](https://github.com/pvzce/PVZCELauncher/commit/9a461a099302558a20d492662ae03e708ff044bc)，[run 38685683742](https://github.com/pvzce/PVZCELauncher/actions/runs/38685683742)，job `116259858528`。44 + 43 独立检查、源码隔离审计、7 项相关单元测试、Checkstyle、UI 测试源码编译、两个 arm64 APK、最终 Manifest/签名检查再次成功。

[最新 APK artifact 11949812303](https://github.com/pvzce/PVZCELauncher/actions/runs/38685683742/artifacts/11949812303) 358652998 字节，到期 `2026-10-29T05:20:52Z`（北京时间 2026-10-29 13:20:52）。已实际下载新 ZIP，重新核对 BUILD_INFO 源码与 run、内部 SHA256SUMS、7/0/0/0 JUnit XML、两包 Android Debug 签名、arm64-only native libs、JRE25/LWJGL3.4.1 资产，并重新执行最终 Manifest 审计；全部通过。

| 文件 | SHA-256 |
| --- | --- |
| artifact ZIP | `32ba92d8465a48e79a0062983ed71c23cbcb79a447fffc8d775cc45d1ddd49f9` |
| debug APK | `c4f799b6304507d1ee68ec7abd656345f89d7a658e305c371da437051b9666a0` |
| fordebug APK | `a8aebd4d64a4f7ddcda6c170e052577d4676b2a3d3fbd7e85a88683d88dd2b1e` |


## 最终状态、阻塞与主分支决策

[run 38685683742](https://github.com/pvzce/PVZCELauncher/actions/runs/38685683742) 最终 **failure**，失败发生在应用安装，尚未进入 instrumentation：

- arm64 Gradle 构建 `BUILD SUCCESSFUL in 5m 42s`；7 项相关单测、Checkstyle、UI 测试 Kotlin 编译、两个开发 APK、真实 Manifest 和 Debug 签名均通过。
- x86_64 `:FCL:assembleFordebug :FCL:assembleFordebugAndroidTest -Darch=x86_64 --no-daemon --console=plain --max-workers=2` 为 `BUILD SUCCESSFUL in 31s`。
- KVM 真实检查返回无权限。没有修改权限或用户组；官方标注不受支持的 TCG 软件模式实际在 329081 ms 后报告 boot completed。
- activity/package 服务已连续三次响应，设备 /data 仍有 5.6 GiB 可用；随后 `adb install --no-streaming --no-incremental -r FCL/build/outputs/apk/fordebug/*-x86_64.apk` 成功推送 185556785 字节，但 Package Manager 返回 `cmd: Failure calling service package: Broken pipe (32)`，命令退出 224。
- 下载 [实际最终诊断 artifact 11950062905](https://github.com/pvzce/PVZCELauncher/actions/runs/38685683742/artifacts/11950062905)，28627 字节、ZIP SHA-256 `375022b1525cac0981c947e104c9dc107a25e0025f936ac46f2d28d6e1166ec5`，独立校验通过。只有六个诊断文件，PNG **0**，没有 instrumentation 运行输出，也没有 UI JUnit 结果。不是四项 UI 测试成功。
- logcat 包含安装前 `com.android.settings/.FallbackHome` 输入 ANR、Watchdog 长等待和 64980 ms 的系统 UI 慢调度；后续 service list 缺少 activity/package。宿主机仍有 74 GiB 磁盘、约 14 GiB 可用内存。证据表明软件模拟器系统服务不稳定；没有据此推断应用界面断言失败，也没有把根因虚构为 APK 磁盘不足。
- 模拟器实际显示为 720x1280；`-dpi-device` 被 emulator 37.2.12 明确忽略，仍是 420 dpi，不声称 320 dpi 生效。没有可供人工视觉审查的 Activity 截图。

**主分支决策：不合并。** 用户要求关键验证失败时保留开发分支；实际 UI 安装/运行这一关键检查未完成。main 保留 `bc9639cca2ce67ea63e630cc982501b3d75f8f04`；旧 smoke 分支未动。实现和本次真实 APK 均保存在 `dev/pvzl-fcl-ui-20261015-01`，可供继续验证。

后续需要合法可用的硬件加速云端 Android 运行器或云端 Android 设备，使用现有 wrapper 构建并运行同一组 `PvzceLauncherUiTest` 四项测试，取得八张真实 PNG 后检查浅暗色、导航、按钮可达性与文字布局；相关检查成功且远端 main 无冲突时再安全合并。运行真实 PVZCE 仍需用户依法提供的游戏 shadow JAR / 游戏素材，不能从此次 APK 构建推断其图形、音频和输入兼容性。既有两项 controlconverter 金样失败继续保留。

README 和适配文档已更新；最终补记只修改 Markdown，工作流 paths-ignore 不触发重复构建。后续任务应读取分支实际 HEAD 和本文记录的已构建源码 SHA，而不是假定 main 已更新。
