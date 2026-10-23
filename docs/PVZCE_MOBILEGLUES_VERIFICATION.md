# MobileGlues 2.0.0 集成验证（2026-10-23）

MobileGlues 2.0.0 已内置并设为默认，保留先前 JRE 25 精简。来源、源码版本、ABI、环境变量和许可见 [运行组件说明](PVZCE_RUNTIME_BUNDLE.md)。没有将上游插件应用的界面、服务或辅助 JNI 合入启动器。

| 检查 | 结果 |
| --- | --- |
| 官方 APK SHA-256 与签名证书 | 与发布说明一致 |
| 三架构库校验值、ELF machine、GL/EGL 导出与动态依赖 | ARM、ARM64、x86_64 均通过；仅依赖 Android 系统库 |
| `testFordebugUnitTest --tests 'com.tungsten.fcl.generic.*'` | 7 个套件，43 项测试，0 失败/错误/跳过 |
| MobileGlues 环境与目录测试 | 私有目录创建、GL/EGL 加载配置、目录不可用时失败均通过 |
| `:FCL:checkstyle` | 通过，使用仓库完整规则 |
| `:FCL:compileFordebugAndroidTestKotlin` | 通过；已更新旧 JRE/渲染器断言并补充默认项、旧配置回退检查 |
| `:FCL:assembleDebug :FCL:assembleFordebug -Darch=arm64` | 均通过 |
| 两份 ARM64 APK 的资源、签名与合并 Manifest 审计 | 通过；Debug 与 Fordebug 身份/Provider 保持隔离 |
| `:FCL:assembleDebug -Darch=all` | 通过；只包含 ARM、ARM64、x86_64 三个受支持 ABI |
| 通用 APK 的资源与签名审计 | 通过；只有 JRE 25，三份 MobileGlues 校验值保持原样，四种保留后端所需库与 10 份来源/许可文件齐全 |
| PVZCE 预检 / 通用 Java 检查 / 音频检查 | 44 项 PVZCE 预检、43 项通用 Java 检查及音频检查通过 |
| 源码 XML、入口、身份、JNI 名称与资源审计 | 通过 |
| `git diff --check` | 通过 |

构建输出为 Android Debug 签名的开发包，没有创建正式发布。首次扩展 Android 测试编译发现旧测试仍引用已移除的 Krypton 常量；已同步为新的默认 ID，修正后完整命令通过。

本环境没有可用 Android 设备；Android UI 测试仅完成编译，没有执行。没有加载 Android GPU 驱动、创建真实 OpenGL 3.2 core 上下文或运行游戏。原生入口/版本与校验检查不等于图形 API 一致性证明；游戏绘制、着色器/MSAA、输入/音频和具体 GPU 兼容性仍需实机验证。
