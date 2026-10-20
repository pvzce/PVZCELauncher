# PVZCE Launcher

为 [PVZCE（pvzce/pvzce）](https://github.com/pvzce/pvzce) 提供 Android Java / LWJGL 运行入口，基于 [FoldCraftLauncher](https://github.com/FCL-Team/FoldCraftLauncher) 改造。

目前提供开发测试包：已实现 PVZCE 启动预设、独立实例、可视化内容管理和基础输入适配，**尚未完成真实 Android 设备上的游戏兼容性验证**。启动器不附带游戏 JAR 或原版 PvZ 素材。

## 下载开发 APK

**2026-10-16 独立实例与内容管理候选包：[下载 arm64 APK 与验证记录](https://github.com/pvzce/PVZCELauncher/actions/runs/38861551125/artifacts/12029317973) · [草稿 PR #3](https://github.com/pvzce/PVZCELauncher/pull/3)**。
构建代码 `9d3f9f5dc44bcbc65b0a0f4f916eaf7384334ede` 的相关测试、Checkstyle、两个 APK 和最终身份/签名检查已通过；后续 UI 冒烟步骤仍在运行，未宣称 UI 或真机游戏加载通过。功能与验证范围见 [独立实例记录](docs/PVZCE_INSTANCES.md)。此包不包含尚未合并的独立音频修复 PR #2。

以下为此前界面包的历史记录，不包含本轮实例功能。

**2026-10-15 FCL 界面候选包：[下载 arm64 APK 与校验记录（ZIP）](https://github.com/pvzce/PVZCELauncher/actions/runs/38685683742/artifacts/11949812303) · [构建与 UI 检查记录](https://github.com/pvzce/PVZCELauncher/actions/runs/38685683742)**。源码为 [`9a461a099302558a20d492662ae03e708ff044bc`](https://github.com/pvzce/PVZCELauncher/commit/9a461a099302558a20d492662ae03e708ff044bc)；两个 APK、相关检查及最终 Manifest/签名验证已通过；**软件模拟器安装失败，四项 UI 测试未开始、没有截图，整轮工作流为 failure，开发分支尚未合并 main**。诊断与后续验证条件见下方交接文档。下载包于北京时间 **2026-10-29 13:20:52** 到期。

打开 [GitHub Actions · PVZCE Android APK](https://github.com/pvzce/PVZCELauncher/actions/workflows/pvzce-build.yml)，核对运行中 APK 构建和身份验证步骤的结果，并另看 UI 检查结论；在页面底部 **Artifacts** 下载 `PVZCE-Launcher-arm64-<完整提交 SHA>`，解压后安装 APK。下载通常需要登录 GitHub；artifact 保留 14 天，过期后可重新运行工作流。

| APK | applicationId | 用途 |
| --- | --- | --- |
| `PVZCE-Launcher-debug-…-arm64-v8a.apk` | `com.pvzce.pvzl` | 常规开发测试包 |
| `PVZCE-Launcher-fordebug-…-arm64-v8a.apk` | `com.pvzce.pvzl.debug` | 可与前一包并存的测试包 |

当前工作流构建 **arm64-v8a**，适合对应 ABI 的 Android 设备，minSdk 26（Android 8.0）。两种包均使用标准 Android Debug 签名，不是正式发行版；不同构建环境生成的 Debug 证书可能不同，不能保证直接覆盖之前手动构建的包。卸载会删除应用私有游戏数据，请先通过应用 DocumentsProvider 备份。

下载包附带 `SHA256SUMS`、最终 Manifest、预检/签名结果和 `BUILD_INFO.txt`；请用其中的源码 SHA 识别构建。APK 产物在后续模拟器步骤之前上传，因此 APK 构建通过与整轮工作流 / UI 通过分别记录。

## 启动 PVZCE

1. 主页选择当前实例，可新建、复制配置、重命名、归档或恢复；旧配置原地登记为默认实例，旧数据保留。首次打开默认使用 **PVZCE 预设**：Android JRE 25、patched LWJGL 3.4.1、游戏自带 KnotClient 与私有游戏目录。已有配置不会被覆盖；主页的 **恢复 PVZCE 启动预设** 可在确认后重设启动字段，保留已选 JAR。
2. 从真实 PVZCE 工程执行 `./gradlew :pvzce-game:shadowJar` 获得游戏 fat JAR，再点击右侧 **选择 / 导入游戏 JAR**。没有内置或虚构的游戏下载地址，也不需要 Minecraft 账号或版本 JSON。
3. 右侧 **运行时与渲染** 打开设置页，点击 **安装 / 修复所选运行组件**，从 APK 安装 JRE、LWJGL 与 AWT/JNA 支持；PVZCE 需要 OpenGL 3.2 core，VirGL 是可调整的初始选择，设备兼容性尚未验证。
4. 左侧 **内容** 页分为模组、资源包、数据包、存档。沿用 FCL 的搜索、多选、勾选启用、长按重命名和详情流程；支持移入可恢复回收站、导出完整备份 ZIP、导入 JAR / 目录 / 备份。包根须含 `assets/` 或 `data/`，存档须选择世界目录；ZIP 先解包校验，不作为 ZIP 文件直接交给游戏加载。包开关写入 PVZCE 自己的配置，两类同名包共用开关并会提示。修改后重启游戏生效。
5. 点击右侧 **启动游戏**，执行已有校验并进入独立 Android JVM。错误会显示实际原因，未提供有效游戏 JAR 时不会显示启动成功。
6. 左侧 **高级** 保留 JAR/classpath、主类、逐行 JVM/游戏参数、工作目录配置；取消 **使用 PVZCE 启动预设校验** 可运行自行提供的通用 Java 程序。**保存配置** 和切后台会持久化当前实例配置；目录不得与另一实例相同或嵌套，修改路径不搬移数据。**浏览游戏数据** 复用原 FCL 文件浏览器。页面、内容分类和搜索支持重建恢复，后台操作锁跨重建保留。
7. **日志** 页读取实际 `latest_jar_executor.log` 末尾 64 KiB，可刷新或通过系统文件选择器导出完整日志；不会上传到网络。

界面复用 FCL 的侧边菜单、启动卡片布局、动态标题、按钮、输入框、勾选框、文件浏览器与 ThemeEngine；没有重新开启旧 Main/Splash/账号初始化链路。默认主题为植物绿：浅色 `#2E7D32`，暗色 `#A5D6A7`；设置中可跟随系统、选择浅/深色或恢复植物绿，已有自定义颜色会保留。PVZCE 账号与在线工坊从主页进入；Minecraft 下载/更新、整合包平台、无实现的大厅/控制器等入口仍禁用。

游戏内 **Keys** 面板、工具 1–5、节奏 S/D/F/J/K/L、聊天 T 与 /、中文 BMP 文字提交、鼠标/滚轮行为保持原实现。高级运行路径和 Java 25、native-access、唯一 `--gameDir`、Android 图形/音频/SDL/Cacio 后端保持不变。

独立实例、内容管理及验证边界见 [PVZCE_INSTANCES.md](docs/PVZCE_INSTANCES.md)；此前 UI 历史记录见 [PVZCE_UI_HANDOFF.md](docs/PVZCE_UI_HANDOFF.md)。本功能分支不包含尚未合并的独立音频修复 PR #2；两者的只读合并检查无冲突，联合运行仍需真机复核。

## 游戏操作与功能边界

- 触摸模拟鼠标；捕获指针时发送相对移动。**Keys** 展开/隐藏按键面板，支持左右键切换、滚轮、Esc/Enter/Space、工具 1–5，以及节奏模式默认键 S D F J K L 的多指按住/松开。
- 聊天/命令：先用 **Chat T** 或 **/** 打开游戏文本框，再点 **Text** 用系统输入法输入文字，发送后用 **Enter** 提交。支持中文等 BMP 字符；现有 JNI char 桥不支持 emoji 等补充平面字符。硬件键盘保留。
- 保留 Android JVM/JRE、LWJGL、渲染、音频、SDL/Cacio 后端和通用 JAR/classpath 模式；可取消 PVZCE profile 后自行配置其他 Java 程序。
- Minecraft 认证、版本/资产下载、模组/整合包平台、更新及旧 Minecraft 启动入口仍默认禁用，源码保留。PVZCE 的加载器与扩展内容独立于这些服务。
- 尚未完成：实机安装/渲染/音频/输入生命周期验证、其他 ABI 构建、完整可编辑控制器、手柄映射、AWT 专用菜单。游戏的桌面 Wayland 触控不适用于这里的 Android 桥接。

## 在线工坊与 PVZCE 账号

主页新增“账号与 API”和“在线工坊”。先保存自己部署的 pvzce-site HTTPS 根地址，可通过 Casdoor 登录，
查看/编辑本站资料及退出；公开模组与数据包支持匿名搜索、筛选、版本详情和下载。
选择目标实例后校验游戏 JAR 的实际版本、依赖与 SHA-256，模组放入 mods，数据包解压后安装到 datapacks。
更新保留原启用状态，旧版本进入回收站，本地修改不会被覆盖；本地内容详情可打开对应工坊版本。
账号按 API 隔离并加密保存在不参与备份的目录；缓存、失败重试、依赖解析限制和第二阶段交接见
[在线工坊说明](docs/PVZCE_WORKSHOP.md)。作品上传及向游戏交接登录属于第二阶段，当前不声称已实现。

## 构建与验证

构建启动器使用 **JDK 17**；游戏运行使用内置 Android **JRE 25**，两者用途不同。使用项目 Gradle wrapper，SDK Platform 35、Build Tools 35.0.0、NDK 27.0.12077973、CMake 3.22.1：

```bash
bash tools/check_pvzce_profile.sh
bash tools/check_generic_java.sh
python3 tools/audit_generic_launcher.py
bash gradlew :FCL:testFordebugUnitTest --tests 'com.tungsten.fcl.generic.*' \
  :FCL:checkstyle :FCL:compileFordebugAndroidTestKotlin \
  :FCL:assembleDebug :FCL:assembleFordebug \
  -Darch=arm64 --no-daemon --console=plain --max-workers=2
```

GitHub 工作流执行上述相关检查，构建两种 APK，并检查最终 APK Manifest 和签名。它不使用上游发布密钥、不创建 Release，也不上传游戏文件。此次开发分支推送触发一次构建，合并 main 不重复触发；以后可在 Actions 页面 **Run workflow** 手动构建。

**完整测试集并非全绿**：前次完整运行中有两项既有 controlconverter 金样差异尚待解决；工作流运行通用配置相关单元测试与独立预检，不把它们冒充完整测试集。静态检查和编译成功也不代表真实 PVZCE 游戏已在 Android 上运行成功。

## 架构、交接与许可

- [独立实例、可视化内容管理、备份恢复](docs/PVZCE_INSTANCES.md)
- [PVZCE 功能映射、配置、验证与剩余问题](docs/PVZCE_GAME_SUPPORT.md)
- [通用入口、包名共存与 JNI 架构](docs/PVZCE_GENERIC_LAUNCHER.md)
- [任务一历史交接](docs/PVZCE_TASK1_HANDOFF.md) · [任务二历史验证报告](docs/PVZCE_TASK2_VERIFICATION.md)
- [原项目说明、完整致谢及第三方清单](docs/UPSTREAM_README_zh-CN.md)

启动器遵循 [GPL-3.0](LICENSE)，保留 FCL-Team、HMCL、Amethyst/Pojav 等原有归属与第三方许可。保留 `com.tungsten.fcl` 等 namespace/JNI 类名以维持 ABI，安装包身份已改为 PVZCE。

游戏及其 Fabric 派生代码、字体、依赖和素材分别遵循游戏仓库的 [LICENSE](https://github.com/pvzce/pvzce/blob/master/LICENSE) 与 [THIRD-PARTY.md](https://github.com/pvzce/pvzce/blob/master/THIRD-PARTY.md)。启动器可导入内容不表示授予其分发许可；启动器原有 LWJGLX 等许可未明项仍需正式分发前核查。
