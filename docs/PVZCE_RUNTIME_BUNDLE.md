# PVZCE 随附运行组件

APK 只包含一个 Android JRE 25（`app_runtime/java/jre25`）。`universal.tar.xz` 与每个目标架构的 `bin-*.tar.xz` 是同一 JRE 的公共部分和原生部分，不是多套 JRE。构建 JDK 仍为 17，与游戏运行时无关。

支持 ARM、ARM64、x86_64；上游随附 JRE 25 没有 32 位 x86 压缩包，因此构建与 CI 不再提供 x86 目标。全架构包也只包含这三个支持的 ABI。`filterJreAssets` 对全架构和单架构构建统一使用 JRE 25 白名单，Sync 会清除上一次构建遗留的资产。

内置渲染器为 MobileGlues 2.0.0、VirGL、Zink、Freedreno，默认与未知/已移除 ID 的回退均为 MobileGlues。旧实例中的 JRE 8/17/21 选择在界面加载时回退到唯一的 JRE 25，保存时写回；被移除的渲染器选择同样回退到 MobileGlues。不会删除游戏、存档或导入文件，也不会在升级时主动删除用户设备上的旧运行时目录。

移除的资源：

- JRE 8、17、21 的所有公共/架构压缩包和版本文件。
- Holy GL4ES（项目标注 OpenGL 2.1）的 `libgl4es_114.so`。
- VGPU（项目标注 OpenGL 2.1+）的 `libvgpu.so`。
- Krypton/NG-GL4ES AAR：原启动路径明确设置 `LIBGL_GL=31`，上游默认值也是 31。核查随附 ARM64 二进制的导出表，缺少 OpenGL 3.2 core 的 `glProvokingVertex`、`glFramebufferTexture` 等入口；上游 `src/glx/lookup.c` 通过 `dlsym(RTLD_DEFAULT, name)` 解析入口。`src/gl/init.c` 允许设置 32 至 46，但 `src/gl/getter.cpp` 直接用这个配置生成版本报告，修改版本字符串不能证明完整支持 3.2 core，因此不通过提升版本覆写来保留该资源。
- 只供旧 Java 8 使用的 Cacio 资产和安装行；JRE 25 所需的 `caciocavallo17` 保留。

保留 Mesa/VirGL/Zink/Freedreno 的二进制及依赖（包括 ANGLE、Turnip）、两个 patched LWJGL 版本、JNA、OpenAL、SDL 与 SPIRV-Cross。SPIRV-Cross 仍是 LWJGL 的独立能力，不随 Krypton 删除。第三方插件机制未更改：安装在设备上的外部渲染器插件不属于本仓库随附二进制，本次没有验证这些插件的能力。

保留的 Mesa 路径能够提供 3.2 以上桌面 OpenGL 功能，但实际设备仍需满足对应 GLES/Vulkan/驱动要求。原有 Mesa 版本覆写不是设备能力证明；此次源码/打包验证不代替实机创建 OpenGL 3.2 core 上下文和运行游戏。

验证命令：

```bash
python3 tools/audit_runtime_bundle.py
python3 tools/audit_runtime_bundle.py path/to/built.apk
bash tools/check_pvzce_profile.sh
bash tools/check_generic_java.sh
bash tools/check_audio_compatibility.sh
bash gradlew :FCL:testFordebugUnitTest --tests 'com.tungsten.fcl.generic.*' :FCL:assembleDebug -Darch=arm64 --no-daemon --max-workers=2
```

资源审计已接入 PVZCE CI 的源码检查与每个 APK 的产物检查，避免打包时重新混入旧 JRE 或已删除的渲染器。

## MobileGlues 集成

固定采用官方稳定版 [V2.0.0](https://github.com/MobileGL-Dev/MobileGlues-release/releases/tag/V2.0.0)。发布 APK SHA-256 为 `a7e1eb29731fdece7ab3af7fb00692b856f1808a7544436e7b3eb6c08355581a`，签名证书 SHA-256 为 `324f4efaff81632373dec9bc714a904b64740249410b551b61805340e42ff5d5`，均与官方说明匹配。只提取 ARM、ARM64、x86_64 的 `libmobileglues.so`；不引入插件 APK 的 Java、Compose、服务或辅助 JNI。上游也有 x86 库，但随附 JRE 25 不支持此 ABI，因此没有引入。

Renderer 对象的 GL/EGL 库均为 `libmobileglues.so`。使用发布 APK 元数据提供的环境：`LIBGL_ES=3`、`POJAV_RENDERER=opengles3`、`POJAVEXEC_EGL=libmobileglues.so`、`LIBGL_EGL=libmobileglues.so`、`MG_COUNT_LAUNCH=1`。`MG_DIR_PATH` 在任何 dlopen 之前设置为各应用包私有 files/mobileglues，并先确认目录创建成功；避免库退回 `/sdcard/MG`。既有桥接负责 GLFW 上下文，SDL 使用同一库的绝对 GL/EGL 路径；MobileGlues 分支不会注入 Mesa 的 GL/GLSL 版本覆写。

新实例、通用 Java 默认、恢复 PVZCE 预设、旧入口与已移除/未知 ID 回退均使用 MobileGlues；显式选择的 VirGL/Zink/Freedreno 或外部插件不会被覆盖。外部插件机制保留，但默认渲染器不依赖扫描或安装插件。

官方插件描述为 OpenGL 4.0，宿主最低 GLES 3.0、推荐 GLES 3.2。检查了三个 ABI 的 ELF 架构、系统动态依赖、GL/EGL 导出；它们无需额外非系统 DT_NEEDED 库。源码中部分函数仍为简化实现/桩，因此导出检查与版本报告均不等价于完整 OpenGL core 一致性验证。PVZCE 的 OpenGL 3.2 core、MSAA、着色器、绘制及输入/音频仍需真实设备与游戏验证。

每个 APK 随附 `assets/licenses/mobileglues` 中的 LGPL-2.1 原文、第三方许可与 NOTICE、固定源码和二进制来源记录。renderer 源码提交 `f56af31d3e490d95808b3adc4ace20234249a37a` 来自 2.0.0 发布插件提交 `c0e48848ef3850e326606ba092774edbb7d904ff` 的 submodule 指针；可递归拉取源码和构建脚本后替换动态库重建。`python3 tools/vendor_mobileglues.py [官方APK路径]` 可从固定、校验后的发布包重现当前三份库；正常构建无需联网下载它们。`audit_runtime_bundle.py` 验证各架构库的校验值、依赖、入口和 APK 内的许可文件，CI 对源码及 APK 执行同一检查。
