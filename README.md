# PVZCE Launcher

为 [PVZCE（pvzce/pvzce）](https://github.com/pvzce/pvzce) 提供 Android Java / LWJGL 运行入口，基于 [FoldCraftLauncher](https://github.com/FCL-Team/FoldCraftLauncher) 改造。

目前提供开发测试包：已实现 PVZCE 启动预设、扩展内容导入和基础输入适配，**尚未完成真实 Android 设备上的游戏兼容性验证**。启动器不附带游戏 JAR 或原版 PvZ 素材。

## 下载开发 APK

**2026-10-14 已验证构建：[下载 arm64 APK 与校验记录（ZIP）](https://github.com/pvzce/PVZCELauncher/actions/runs/38586280525/artifacts/11905367976) · [成功运行记录](https://github.com/pvzce/PVZCELauncher/actions/runs/38586280525)**。源码提交为 [`4e76cce77e7a7a85792b98e6b7510f0ba3801370`](https://github.com/pvzce/PVZCELauncher/commit/4e76cce77e7a7a85792b98e6b7510f0ba3801370)；该下载包于北京时间 **2026-10-28 23:12** 到期。

打开 [GitHub Actions · PVZCE Android APK](https://github.com/pvzce/PVZCELauncher/actions/workflows/pvzce-build.yml)，选择成功的运行，在页面底部 **Artifacts** 下载 `PVZCE-Launcher-arm64-<完整提交 SHA>`，解压后安装 APK。下载通常需要登录 GitHub；artifact 保留 14 天，过期后可重新运行工作流。

| APK | applicationId | 用途 |
| --- | --- | --- |
| `PVZCE-Launcher-debug-…-arm64-v8a.apk` | `com.pvzce.pvzl` | 常规开发测试包 |
| `PVZCE-Launcher-fordebug-…-arm64-v8a.apk` | `com.pvzce.pvzl.debug` | 可与前一包并存的测试包 |

当前工作流构建 **arm64-v8a**，适合对应 ABI 的 Android 设备，minSdk 26（Android 8.0）。两种包均使用标准 Android Debug 签名，不是正式发行版；不同构建环境生成的 Debug 证书可能不同，不能保证直接覆盖之前手动构建的包。卸载会删除应用私有游戏数据，请先通过应用 DocumentsProvider 备份。

下载包附带 `SHA256SUMS`、最终 Manifest、预检/签名结果和 `BUILD_INFO.txt`；请用其中的源码 SHA 识别构建。未成功的运行不会被标为可用 APK。

## 启动 PVZCE

1. 打开启动器，点击 **Apply PVZCE preset**。它选择 Android JRE 25、patched LWJGL 3.4.1、游戏自带的 KnotClient 入口和应用私有游戏目录。VirGL 是可调整的渲染起点，PVZCE 需要 OpenGL 3.2 core。
2. 从真实 PVZCE 工程运行 `./gradlew :pvzce-game:shadowJar` 得到游戏 fat JAR，再用 **Import JAR / dependency files** 导入。预设会检查必要的游戏/加载器条目，不需要 Minecraft 账户或版本 JSON。
3. 点击 **Install / repair selected bundled runtime**。JRE、LWJGL 等从 APK 内置资产安装，不会下载游戏。
4. 根据需要导入内容：模组用 **Import PVZCE mods (JAR)**；资源包、数据包用对应 **directory** 按钮，选择直接包含 `assets/` 或 `data/` 的目录。当前游戏读取目录包，不把 ZIP 当作已启用的包。代码模组需要重启，资源/数据包由游戏界面启用与重载。
5. 点击 **Validate and launch**。`--enable-native-access=ALL-UNNAMED` 和唯一的 `--gameDir` 由预设生成；额外 JVM/程序参数每行填写一个完整参数。

游戏数据保存在当前包的 `files/games/pvzce`：`mods/`、`resourcepacks/`、`datapacks/`、`config/`、`saves/`。可通过既有 DocumentsProvider 管理和备份；导入不覆盖同名内容。

## 游戏操作与功能边界

- 触摸模拟鼠标；捕获指针时发送相对移动。**Keys** 展开/隐藏按键面板，支持左右键切换、滚轮、Esc/Enter/Space、工具 1–5，以及节奏模式默认键 S D F J K L 的多指按住/松开。
- 聊天/命令：先用 **Chat T** 或 **/** 打开游戏文本框，再点 **Text** 用系统输入法输入文字，发送后用 **Enter** 提交。支持中文等 BMP 字符；现有 JNI char 桥不支持 emoji 等补充平面字符。硬件键盘保留。
- 保留 Android JVM/JRE、LWJGL、渲染、音频、SDL/Cacio 后端和通用 JAR/classpath 模式；可取消 PVZCE profile 后自行配置其他 Java 程序。
- Minecraft 认证、版本/资产下载、模组/整合包平台、更新及旧 Minecraft 启动入口仍默认禁用，源码保留。PVZCE 的加载器与扩展内容独立于这些服务。
- 尚未完成：实机安装/渲染/音频/输入生命周期验证、其他 ABI 构建、完整可编辑控制器、手柄映射、AWT 专用菜单。游戏的桌面 Wayland 触控不适用于这里的 Android 桥接。

## 构建与验证

构建启动器使用 **JDK 17**；游戏运行使用内置 Android **JRE 25**，两者用途不同。使用项目 Gradle wrapper，SDK Platform 35、Build Tools 35.0.0、NDK 27.0.12077973、CMake 3.22.1：

```bash
bash tools/check_pvzce_profile.sh
bash tools/check_generic_java.sh
python3 tools/audit_generic_launcher.py
bash gradlew :FCL:testFordebugUnitTest --tests com.tungsten.fcl.generic.JavaLaunchSpecTest \
  :FCL:checkstyle :FCL:assembleDebug :FCL:assembleFordebug \
  -Darch=arm64 --no-daemon --console=plain --max-workers=2
```

GitHub 工作流执行上述相关检查，构建两种 APK，并检查最终 APK Manifest 和签名。它不使用上游发布密钥、不创建 Release，也不上传游戏文件。此次开发分支推送触发一次构建，合并 main 不重复触发；以后可在 Actions 页面 **Run workflow** 手动构建。

**完整测试集并非全绿**：前次完整运行中有两项既有 controlconverter 金样差异尚待解决；工作流运行通用配置相关单元测试与独立预检，不把它们冒充完整测试集。静态检查和编译成功也不代表真实 PVZCE 游戏已在 Android 上运行成功。

## 架构、交接与许可

- [PVZCE 功能映射、配置、验证与剩余问题](docs/PVZCE_GAME_SUPPORT.md)
- [通用入口、包名共存与 JNI 架构](docs/PVZCE_GENERIC_LAUNCHER.md)
- [任务一历史交接](docs/PVZCE_TASK1_HANDOFF.md) · [任务二历史验证报告](docs/PVZCE_TASK2_VERIFICATION.md)
- [原项目说明、完整致谢及第三方清单](docs/UPSTREAM_README_zh-CN.md)

启动器遵循 [GPL-3.0](LICENSE)，保留 FCL-Team、HMCL、Amethyst/Pojav 等原有归属与第三方许可。保留 `com.tungsten.fcl` 等 namespace/JNI 类名以维持 ABI，安装包身份已改为 PVZCE。

游戏及其 Fabric 派生代码、字体、依赖和素材分别遵循游戏仓库的 [LICENSE](https://github.com/pvzce/pvzce/blob/master/LICENSE) 与 [THIRD-PARTY.md](https://github.com/pvzce/pvzce/blob/master/THIRD-PARTY.md)。启动器可导入内容不表示授予其分发许可；启动器原有 LWJGLX 等许可未明项仍需正式分发前核查。
