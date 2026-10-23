# PVZCE 随附运行组件

APK 只包含一个 Android JRE 25（`app_runtime/java/jre25`）。`universal.tar.xz` 与每个目标架构的 `bin-*.tar.xz` 是同一 JRE 的公共部分和原生部分，不是多套 JRE。构建 JDK 仍为 17，与游戏运行时无关。

支持 ARM、ARM64、x86_64；上游随附 JRE 25 没有 32 位 x86 压缩包，因此构建与 CI 不再提供 x86 目标。全架构包也只包含这三个支持的 ABI。`filterJreAssets` 对全架构和单架构构建统一使用 JRE 25 白名单，Sync 会清除上一次构建遗留的资产。

内置渲染器保留 VirGL、Zink、Freedreno，默认与未知/旧 ID 的回退均为 VirGL。旧实例中的 JRE 8/17/21 选择在界面加载时回退到唯一的 JRE 25，保存时写回；被移除的渲染器选择同样回退到 VirGL。不会删除游戏、存档或导入文件，也不会在升级时主动删除用户设备上的旧运行时目录。

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
