# 面向 pvzce/pvzce 的启动器适配

本轮仅修改 PVZCELauncher；游戏仓库只读核对。核对日期 2026-10-14，游戏基准
[`e40b28edca5265b9537d22c9a220ce51bc68b2e7`](https://github.com/pvzce/pvzce/tree/e40b28edca5265b9537d22c9a220ce51bc68b2e7)，
启动器基准 `e9c58d62fbea2b20ef61f4b0f6363f0e3b5d9eae`。
没有打包或虚构游戏 JAR、下载地址、原版素材；没有修改游戏、上游或 smoke 分支，也不发布正式版本。用户随后明确要求完成 README、构建工作流并合并 main，合并结果以最终远端交接为准。

## 依据与组件选择

| 实际游戏代码/配置 | 本轮启动器行为 |
| --- | --- |
| `gradle.properties` 的 Java 25；`pvzce-game/build.gradle` 的 native-access 参数 | PVZCE 预设选择 bundled jre25；启动时读取真实 release 元数据，拒绝旧运行时，添加 `--enable-native-access=ALL-UNNAMED` |
| application/shadowJar 的入口 `net.fabricmc.loader.impl.launch.knot.KnotClient` | 使用游戏自带的加载器；检查 fat JAR 的 KnotClient、PvzceGame、PvzceGameProvider 和服务注册条目；不启用 Minecraft Fabric 下载/安装器 |
| GLFW 平台 API、OpenGL 3.2 core、LWJGL 3.3.6、STB、OpenAL | 预设选择已有 Android patched LWJGL 3.4.1（3.3.3 缺少所需平台 API），保留 Android native/render/audio 后端；预设 VirGL 作为可调整的起点，不承诺任何设备 GPU 兼容 |
| `PvzceGameProvider` 的 `--gameDir` 与五个子目录 | 自动从 Working directory 生成唯一 `--gameDir`；预设目录为本包 `files/games/pvzce`，建立 mods/resourcepacks/datapacks/config/saves；不使用桌面的 `~/.pvzce` |
| Fabric 派生 mod loader 的 JAR 与 `fabric.mod.json` | 新增离线 mods JAR 导入；基础格式校验后复制到游戏 mods，不加入 Java classpath；依赖/版本/运行错误仍由游戏加载器报告 |
| `PvzceResourceManager` 只枚举 resourcepacks/datapacks 中的目录 | 新增 SAF 目录导入：选择直接包含 assets/ 或 data/ 的包目录，不把 ZIP 当成已启用的包 |
| `KeyBindings`：工具 1–5、节奏 S D F J K L、聊天 T、命令 /、F2/F3/F10 | 新增可隐藏虚拟按键面板；工具/节奏键支持独立按住/松开、多指组合；游戏内更改绑定后，面板仍是这些实体键，可使用硬件键盘匹配自定义绑定 |
| GLFW mouse/cursor/scroll 与游戏设置、编辑器、聊天输入 | 保留绝对触摸鼠标、捕获时相对位移；增加左右触摸按钮切换、滚轮与系统输入法文字提交对话框 |

源码依据：[游戏构建配置](https://github.com/pvzce/pvzce/blob/e40b28edca5265b9537d22c9a220ce51bc68b2e7/pvzce-game/build.gradle)、
[GameProvider](https://github.com/pvzce/pvzce/blob/e40b28edca5265b9537d22c9a220ce51bc68b2e7/pvzce-loader/src/main/java/com/pvzce/launcher/PvzceGameProvider.java)、
[窗口](https://github.com/pvzce/pvzce/blob/e40b28edca5265b9537d22c9a220ce51bc68b2e7/pvzce-game/src/main/java/com/pvzce/client/PvzceWindow.java)、
[资源管理器](https://github.com/pvzce/pvzce/blob/e40b28edca5265b9537d22c9a220ce51bc68b2e7/pvzce-game/src/main/java/com/pvzce/common/resource/PvzceResourceManager.java)、
[键位](https://github.com/pvzce/pvzce/blob/e40b28edca5265b9537d22c9a220ce51bc68b2e7/pvzce-game/src/main/java/com/pvzce/client/input/KeyBindings.java)。

## 使用

1. 点击 **Apply PVZCE preset**，确认替换启动字段；保留已选 JAR，不迁移/删除已有游戏文件。通用模式仍可通过取消 PVZCE profile 使用。
2. 用 **Import JAR / dependency files** 导入自己从真实游戏工程 `:pvzce-game:shadowJar` 构建的 fat JAR。PVZCE 模式每次导入一个游戏 JAR，导入成功后切换所选路径并清空额外 classpath；旧文件保留。同名目标不覆盖，可通过 DocumentsProvider 管理旧文件。
3. 点击 **Install / repair selected bundled runtime** 安装 jre25/LWJGL。运行时来自 APK 资产，导入文件来自系统选择器，没有新增远程下载器。
4. 模组用 **Import PVZCE mods (JAR)**；资源包、数据包用对应的 **directory** 按钮。选择包根目录，保持内部 assets/<namespace>/… 或 data/<namespace>/…；修改后在游戏资源包界面启用/重载，代码模组需要重启。
5. 保留实际游戏主类。JVM/程序参数仍每行一个；native-access 和 gameDir 由预设生成，不要在程序参数里重复指定 gameDir，也不要添加桌面 Wayland/X11 或 Fabric development 覆盖。游戏自己的 versionType 等参数可以照实际代码提供。
6. 游戏窗口点 **Keys** 展开/隐藏面板。工具、节奏按键支持按住；触摸默认左键，**Touch L/R** 切换后日志会显示当前模式。聊天先点 **Chat T** 或 **/** 打开游戏文本框，再点 **Text** 用系统输入法输入并发送，最后点 **Enter** 提交。Text 不自动发送 Enter。

## 输入、导入与运行边界

- 文字对话框支持中文等 BMP 字符；现有 JNI char 桥只有 UTF-16 char，emoji 等补充平面字符会提示不支持，不拆成两个损坏字符发送。它不是完整的游戏内 IME composition 接口。
- 虚拟按键在 touch cancel、隐藏面板、失焦、切后台、销毁 surface 时释放。主触点 ID 固定，第二指离开不会把第一指的鼠标位置换到第二指。物理鼠标按钮通过独立 button 事件映射，右键/中键不再额外产生左键。聊天 T 和 / 同时发送对应字符与配对松开事件，匹配游戏的 suppressNextChar 逻辑。
- SAF 导入拒绝路径分隔符、上级目录、逃逸 symlink、重复目标；先复制到临时文件/目录（未完成的目录在 pack 根之外，不被游戏枚举），单文件/单目录完成后才放到最终位置，失败清理该 staging。多文件批次不是整体事务：前面已成功导入的文件保留，后面失败会显示错误。
- 目录导入上限为 32 层、20000 项；不解压 ZIP、不运行安装脚本、不下载缺失依赖、不覆盖已有内容。删除/替换和备份可通过既有 DocumentsProvider 操作。
- 游戏当前的 Wayland 触控依赖 Linux libwayland/FFM。Android patched GLFW 报告 X11 兼容平台，没有真实 Wayland display；不强行启用桌面 Wayland。此次使用 Android 触摸→鼠标事件，不能等同游戏原生 Wayland 多触点交互；多指节奏通过独立虚拟键提供。
- Minecraft 账户、版本/资产下载、CurseForge/Modrinth/整合包平台、Minecraft 更新和原启动入口继续禁用。PVZCE 自己的模组/资源包不需要这些平台。现有 Cacio/AWT、SDL、JRE、渲染器和音频后端保留。
- 未接入完整可编辑控制器、手柄映射、AWT 专用菜单或联网大厅；不根据 README 的功能描述推断游戏已有可用的 TCP 服务端。

## 验证与剩余问题

- `bash tools/check_pvzce_profile.sh`：纯 Java 预检/输入检查通过，覆盖 runtime 版本、真实入口约定、必要条目缺失、参数冲突、目录生成、中文、非法 UTF-16、组合键/重复按下/取消释放、导入路径和 symlink。JAR/release 是明确标记的校验 fixture，不是游戏文件或 Android JRE，没有用它宣称真实游戏运行成功。
- `bash tools/check_generic_java.sh`：原有通用启动行为检查通过，包含真实无害 Echo JVM 启动。
- `python3 tools/audit_generic_launcher.py` 与 `git diff --check`：通过；本轮不修改 applicationId、Manifest、Provider、JNI 类名或 feature gates。
- 本轮 `bash gradlew :FCL:compileFordebugKotlin :FCL:compileFordebugJavaWithJavac :FCL:checkstyle -Darch=arm64 --no-daemon --console=plain --max-workers=2`：BUILD SUCCESSFUL in 8m 20s，49 个任务执行。另用真实 Checkstyle 10.12.5 检查 5 个改动/新增 Java 文件（含独立 harness），通过。
- 本轮 `bash tools/check_pvzce_profile.sh`：44 项通过；原 `check_generic_java.sh`：43 项通过。未将这两组独立检查冒充实机或完整测试集。
- `.github/workflows/pvzce-build.yml`：独立 arm64 开发 APK 构建，运行相关测试/检查、两个变体构建、真实 APK Manifest 与签名验证，上传 APK/校验值/构建源码 SHA；使用固定 SHA 的官方 checkout/setup-java/upload-artifact。本轮新增工作流及三个调整的旧工作流均通过 actionlint 1.7.12。旧上游 build/check/release jobs 在目标 Fork 中跳过。没有使用发布 secrets 或创建 Release。
- SDK/NDK 缓存本轮重建，继承代理及 TLS 信任。平台包首次 sdkmanager 安装遇到 ZIP 失败，使用官方稳定 platform-35_r02.zip 且 SHA-1 校验通过后安装，保留失败 installer 状态；没有忽略校验或关闭 TLS。早期 Gradle wrapper 下载超时与代理连接拒绝不算成功，已修正重跑。
- GitHub 构建的具体 run/artifact 结果将在核实后交接。前次任务二成功生成的 APK 不包含本轮变化，不能当成本轮 APK。
- 没有 Android 设备、没有真实游戏 shadow JAR 的端到端运行证据；Java 25 内嵌 JVM、Fabric child classloader 与 patched LWJGL 版本替换、OpenGL 3.2、OpenAL/STB、游戏绘图/输入/音频、存档/编辑器/资源热重载都仍需实机核查。
- 原任务二两项 controlconverter 金样失败仍留在历史验证报告中；本轮不改该转换器，也未据此宣称完整工程测试全通过。

## 许可

保留启动器及游戏各自的许可证与作者归属。本轮不复制游戏源代码、Fabric Loader 或游戏素材进启动器。
游戏 GPL-3.0、其 Fabric 派生部分 Apache-2.0、OFL 字体及第三方依赖/素材范围以游戏
[THIRD-PARTY.md](https://github.com/pvzce/pvzce/blob/e40b28edca5265b9537d22c9a220ce51bc68b2e7/THIRD-PARTY.md) 为准。
可导入并不表示启动器授予内容许可；现有启动器第三方许可待核查项继续见通用架构说明。
