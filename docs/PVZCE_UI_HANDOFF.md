# PVZCE FCL 界面交接（2026-10-15）

状态：实现已完成，Android 编译与模拟器验证待本分支 GitHub run 实际结果补记；当前不声称构建或实机游戏验证成功。

- 仓库：https://github.com/pvzce/PVZCELauncher
- 开发分支：`dev/pvzl-fcl-ui-20261015-01`
- 最新 main 起点 / 基准完整 SHA：`bc9639cca2ce67ea63e630cc982501b3d75f8f04`。
- 实现提交 SHA：以本文件首次提交的 GitHub commit 为准；后续验证记录将明确补记完整 SHA，避免在同一提交中自引用。
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

## 验证记录（待补 GitHub 结果）

本地云端 Java17 module compiler：

- `bash tools/check_pvzce_profile.sh`：44 项通过。
- `bash tools/check_generic_java.sh`：43 项通过，含无害 Echo JVM 真运行。
- `python3 tools/audit_generic_launcher.py`：源码 XML / 三变体身份 / MC 隔离 / JNI 审计通过。
- `git diff --check`：通过。

计划在已有工作流运行原相关单元测试、Checkstyle、arm64 debug/fordebug 开发 APK、最终 Manifest/签名审计；随后在 Android30 x86_64 模拟器运行 `PvzceLauncherUiTest`，检查真实 FCL Activity 导航、浅暗色对比度/截图、配置重建持久化、系统选择器与 FCL 文件浏览器路由、随附 JRE 安装、无游戏文件错误、真实日志读取。使用 x86_64 仅做 UI 测试，下载 APK 保持 arm64。

本地未检测到 SDK/NDK/emulator 或 /dev/kvm，未冒充本地构建和实机运行。无真实游戏 shadow JAR；任何模拟器 UI 成功不等于游戏渲染、音频、输入兼容性通过。既有两项 controlconverter 金样失败未修复，相关测试通过也不表示完整工程测试全绿。
