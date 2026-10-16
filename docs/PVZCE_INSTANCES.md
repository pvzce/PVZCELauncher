# 独立实例与可视化内容管理

本轮基于 main `88fcc7fc4a368f90e1bc5ebeaebbe580e9f4512c`，仅修改启动器。
音频修复 [PR #2](https://github.com/pvzce/PVZCELauncher/pull/2) 仍为独立分支，
本轮不改它涉及的 `GenericJavaLauncher`、`GenericJvmActivity` 或音频后端。
两者都以 main 为基准，本轮的启动仍走相同的 `JavaLaunchSpec` → 通用 Java 启动路径。

## 用户流程与 FCL 复用

- 主页选择当前实例，可新建、复制配置到空实例、重命名、归档、恢复归档。
  新建默认使用 PVZCE 配置；复制配置保留 JAR/运行组件/参数，但新实例的内容目录为空。
  JAR 和只读运行库可以共享，各实例的模组、资源包、数据包、配置与存档位于独立目录。
- 内容页分为模组、资源包、数据包、存档四类。点击行多选，长按重命名，勾选启用/禁用，
  信息按钮显示元数据；支持搜索、全选、反选、取消、批量移入回收站和导出所选备份。
- 原 FCL 的 `page_manage_resource_pack.xml`、`item_resource_pack.xml` 直接复用；
  搜索/左右操作栏/选中栏切换/启用勾选/长按重命名沿用 `ResourcePackListPage` 与
  `ResourcePackListAdapter` 的交互。对话框复用 `EditDialog`、`FCLAlertDialog`、`FCLDialog`；
  主导航、植物主题、浅色/深色继续使用已有 FCL 组件和 ThemeEngine。
  原上游实现也已通过 GitHub 只读核对：
  [FCL 资源包管理](https://github.com/FCL-Team/FoldCraftLauncher/blob/main/FCL/src/main/java/com/tungsten/fcl/ui/manage/ResourcePackListPage.kt)。
- 将原布局中的在线下载动作改为离线备份；不启用 Minecraft 账号、下载平台或原 Minecraft 入口。
  保存目录、包开关及模组识别均由新的 PVZCE 存储层提供，不调用 Minecraft 的 options.txt/NBT 管理器。

## 数据保存与迁移

首次运行将原 `generic_launch` 的启动字段登记为“默认实例”，保留原 JAR 路径、工作目录和参数；
不搬移、复制、删除原游戏内容。旧 SharedPreferences 中的启动字段保留，之后只在那里记录全局页码。
实例配置集中保存在应用私有 `launcher_instances/registry.properties`，通过临时文件、同步写入和替换保存。
注册表不可读时报告错误，保留文件，不静默覆盖成默认值。

新实例目录为应用私有 `pvzce_instances/<UUID>`；新导入的游戏 JAR 位于 `instance_jars/<UUID>`。
改变高级页目录不会搬移内容。相同、嵌套、符号链接别名指向另一实例的目录都拒绝登记，
归档实例也保留目录占用，避免恢复时与其他实例混用。归档只改变登记状态，至少保留一个可用实例。
自定义 JVM/程序参数中额外指定的外部文件路径由用户自行配置，不自动改写。
共享缓存、运行组件和最近一次启动的全局日志仍为共享数据；它们不冒充每实例私有数据。

文件选择器请求在发起时保存实例 ID、目录、类型和选中内容，返回时重新核验目标；
Activity 重建后不会改用另一实例的当前输入框。内容 IO 在后台执行，期间锁定配置与操作控件。
进程内操作锁跨 Activity 重建保留，新界面等待旧 IO 完成后重载配置；旧界面不能释放新操作的锁。
切换实例立即清空旧列表，避免显示上一实例的数据。

## 实际游戏格式

本轮游戏只读基准 `b8fe0227f9367ef0195c7f75d3bd7c5db725aece`：

- 模组读取 JAR 内的 `fabric.mod.json`，禁用通过 `.jar` ↔ `.jar.disabled`；不把模组塞入启动 classpath。
- 资源包/数据包使用实际目录根中的 assets/ 或 data/。开关读写
  `config/pvzce-packs.json` 的 `disabled` 名称数组，保留未知 JSON 字段及未安装包的记录。
  [游戏 PackSelection](https://github.com/pvzce/pvzce/blob/b8fe0227f9367ef0195c7f75d3bd7c5db725aece/pvzce-game/src/main/java/com/pvzce/common/resource/PackSelection.java)
  按裸目录名保存开关，所以两类同名包必然共享状态；界面会确认这一联动。重命名保留禁用状态，
  且不抢用另一类包的现有名字。损坏的开关文件拒绝修改，原文件保留。
- 存档按 saves/ 下的完整世界目录管理，保留 profile.dat、levels/、level_status/ 等所有文件。
  不套用 Minecraft 的根 level.dat 检测或 NBT 编辑。

## 移除、备份与恢复

移除先经 FCL 确认对话框，再移动到当前游戏目录的 `.pvzce-launcher-trash/<UUID>`，
附恢复元数据。它位于游戏扫描的四个内容目录之外。可从内容页恢复；有同名目标时拒绝覆盖。
不提供永久清空回收站动作，旧数据不会随实例归档被删除。

导入支持模组 JAR、实际包/存档目录，以及本管理器导出的 ZIP。导出 ZIP 包含所选完整目录，
并有 `.pvzce-backup.json` 记录内容类型及包启用状态。导入备份核对类型并恢复包开关；
若与另一类同名包的状态冲突，拒绝导入。普通无说明 ZIP 可以导入，但须直接包含正确的顶层项。
同名项不覆盖；目录先复制/解压到扫描目录以外的临时位置，全部校验后才移入。
多个 SAF 文件按文件提交，前面已成功导入的文件不会因后续文件失败而删除。
导入存档时须选择含 profile.dat、levels/ 或 level_status/ 的世界根目录，避免将整个 saves/ 再套一层。
单批移入失败时尝试回滚已移入项；IO 错误仍会显示，不能把批量操作说成跨文件系统事务。

导入/导出限制为 32 层、20000 项和 ZIP 解压/导出 1 GiB；元数据读取限 1 MiB。
拒绝路径穿越、绝对 ZIP 路径、重复文件项及通过符号链接读写外部数据。
SAF 目录导入沿用已有复制器的 32 层/20000 项限制。
模组和包操作离线进行，需要重启游戏后生效。存档为文件级管理，不解析或编辑内部进度。

## 验证

本轮本地使用完整 JDK 17 和真实 JUnit：实例存储与内容管理回归检查已通过，
覆盖迁移、配置/目录隔离、归档恢复、多个存储句柄、损坏配置、共享包名、模组开关、
回收站冲突、完整二进制存档备份往返、包开关备份、ZIP 路径穿越及符号链接。
这些文件系统 fixture 不是实际游戏运行或 Android 界面证据。

Android instrumentation 已适配新的内容页，并新增实例切换、重建后保持选择及可视化禁用模组检查。
也覆盖在 Activity 重建时保持操作锁并在 IO 结束后恢复控件。

### 本轮实际构建结果

- [草稿 PR #3](https://github.com/pvzce/PVZCELauncher/pull/3)，分支
  `feat/pvzce-instance-manager-20261016`。与音频 PR #2 的只读合并检查无冲突，
  未将任一 PR 合并 main，也未改动游戏仓库。
- [CI run 38861551125](https://github.com/pvzce/PVZCELauncher/actions/runs/38861551125)，
  构建代码 `9d3f9f5dc44bcbc65b0a0f4f916eaf7384334ede`：通用/PVZCE 预检、
  `com.tungsten.fcl.generic.*` 相关单元测试、Checkstyle、Android instrumentation 源码编译、
  两个 arm64 开发 APK，以及最终包名/Manifest/开发签名验证全部成功。
- [APK 与验证记录 artifact 12029317973](https://github.com/pvzce/PVZCELauncher/actions/runs/38861551125/artifacts/12029317973)
  已上传，API 确认未过期，358754721 字节，含 debug/fordebug APK、SHA256SUMS、BUILD_INFO、
  单元测试和验证结果；北京时间 2026-10-30 14:37:37 到期。后续提交仅补记 Markdown，
  不改变该 APK 的实现代码。此包不包含尚未合并的独立音频修复 PR #2。
- 截至北京时间 2026-10-16 14:39，后续 Android UI 冒烟步骤仍在运行，
  **未把 instrumentation 编译成功当作 UI 实测通过**，也没有声称获得截图或整轮工作流成功。
  实时结论以 CI 链接为准。
- 首次 CI 的测试夹具使用了 Android 编译 stub 中缺少的 `Files.readString/writeString`，
  已改用兼容的字节读写 API；相关 Java/Kotlin 实现与测试在最新构建通过。
  中间旧提交的构建已取消，仅引用上述最新代码的产物。

仍需真机复核系统文件选择器、主题/横竖屏布局、大型目录 IO，以及真实模组/包/存档在游戏中的加载。
没有实际用户游戏 JAR、模组依赖和设备运行证据，不宣称端到端游戏兼容性完成。
