# PVZCE 在线工坊与账号（第一阶段）

启动器基线是 main `70702b9b9a351592ada58f92d9bd64b1d62f1e3d`，已包含独立实例与音频修复。
本阶段只修改启动器。只读核对后端 `pvzce/site` main
`34e55bf950ac5d9c634d79e73ed1071dfc5313d0` 的 HTTP 路由、认证、作品/版本/文件模型与包格式说明，
以及游戏 `pvzce/pvzce` master `a7a4b5bffc448e408bd6e953ec29a6c621649304` 的版本常量与目录包发现规则。

## 使用

1. 在主页打开“账号与 API”，填写实际部署的 pvzce-site **HTTPS 根地址**并保存。
   填写的地址应与后端 APP_PUBLIC_URL 一致。没有内置或猜测的生产 API 地址。不接受带凭据、路径、查询或 fragment 的地址。
   `http://localhost`、`127.0.0.1`、`[::1]` 与 Android 模拟器宿主机 `10.0.2.2` 仅用于本地开发。
   手机上的 localhost 是手机本身；不要关闭 TLS 验证或将服务器私钥放入 APK。
2. “登录 / 注册”在独立的 PVZCE WebView 进程打开后端 `/auth/login`，经过 Casdoor 原有流程回到
   同一 API 的 `/account`。启动器通过原生 CookieManager 取得 HttpOnly 的 `pvzce_session`，
   调用 `/api/v1/account/me` 核验，再保存 Cookie、CSRF 与服务端到期时间。
   没有 JS 注入或密码读取，也没有使用 Casdoor 客户端密钥或虚构的 Bearer 登录接口。
3. 可刷新账号、编辑本站显示名/简介、退出当前网站会话。资料编辑复用 `PATCH /api/v1/profile`
   和 `X-CSRF-Token` / Origin 校验。过期或 HTTP 401 提示重新登录并清除该服务的本机会话。
   “仅清除本机登录”用于连接失败或本机记录损坏，不声称撤销了服务器会话。
4. 在主页打开“在线工坊”，选择模组或数据包，按搜索词、单个标签、游戏版本筛选，按更新、下载、
   评分排序及翻页。服务器的已验证游戏版本可在搜索后查看。详情包含作者用户 ID、说明、许可证、
   标签、更新日志、版本、兼容声明、依赖版本 ID、文件大小与 SHA-256；当前公开 API 不返回作者显示名。
   也可按作品 ID 直接打开。匿名可浏览和下载公开、普通分级的内容。
5. 选择安装目标实例及作品版本，确认后下载。目标实例必须是未归档的 PVZCE 实例，且已导入完整游戏
   JAR。启动器读取 JAR 内 `PvzceVersions.class` 的 `GAME_VERSION` 常量，**不加载或执行游戏类**，
   再检查版本兼容、平台依赖、模组元数据依赖和冲突。安装时重新向服务器确认作品/文件仍可见。
6. 支持进度、取消与失败后重新点击下载。当前后端不提供真正的断点续传，因此每次重试重新下载。
   长下载只在应用进程存活时运行；ViewModel 保留 Activity 重建期间的任务。退出该工坊 Activity
   会取消其下载，进程死亡后提示上次任务中断，不冒充后台常驻下载服务。
7. 已安装内容仍从原“内容”页启用/禁用、重命名、回收、恢复、备份。详情中的“查看工坊版本 / 更新”
   回到来源 API 的作品页面，默认选择该内容所在实例。选择新版本下载即执行更新；不自动安装更新。

## 登录、缓存与生命周期

- 规范化 API **scheme + host + 非默认 port** 后的 SHA-256 是会话及缓存命名空间。
  切换地址立即清空当前页面的账号、作品和详情，随后只使用该服务自己的会话。
- 会话使用 AndroidKeyStore AES/GCM 加密，API 地址作为 AAD，保存在
  `noBackupFilesDir/pvzce_sessions/<api-hash>`。会话不会进入实例配置、游戏参数、内容 ZIP 或日志。
- API 地址是非秘密的全局偏好 `pvzce_workshop`；实例来源记录也不包含会话、CSRF 或密码。
- 登录窗口使用独立 `:pvzce_login` 进程，Android 9+ 使用单独 WebView 数据目录。
  每次登录清理该专用浏览器的 Cookie/缓存/站点存储，结束时再次清理 Cookie/站点存储。
  不依赖浏览器 Cookie 对端口的隔离。Android 8 使用该独立进程的默认 WebView 目录。
  WebView 禁止 file/content 访问、混合内容、第三方 Cookie 和非 HTTP(S) 导航，不添加 JS bridge。
  登录页面防截图，默认 SSL 错误处理保持拒绝；WebView 目录排除云备份和设备迁移。
- 原生请求只访问配置的 API，禁止自动跟随重定向，避免将 Cookie 或 CSRF 转发到其他目标。
  公开目录、详情与下载使用匿名客户端，不缓存作者或审核人员才能看到的私有响应。
- `cacheDir/pvzce_workshop/<api-hash>/catalogue.json` 只保存最近一次公开搜索及筛选条件、时间。
  连接失败时只有完全相同的筛选条件可读取缓存，界面明确显示缓存时间；安装仍须在线重新校验。
  部分下载使用 UUID `.download`，失败/取消清理；新任务清理此服务遗留的部分文件。
- 网络 IO 位于 ViewModel 的 IO dispatcher，不保留 Activity。一次安装持有既有 `GameOperationGate`，
  与本地内容操作互斥。`WorkshopTarget` 在发起时固定实例 ID、目录及配置，不读取后续 selected 指针；
  提交前重新核对目标与游戏 JAR 版本。

## 安装、更新与来源记录

- 下载上限 200 MiB；核对版本文件记录、Content-Length、X-Checksum-SHA256 以及实际文件 SHA-256。
- 模组是包含 `fabric.mod.json` 的 JAR，检查 ID、版本、PVZCE 依赖及非 Minecraft 包身份，写入 `mods/`。
  不将模组加入启动 classpath。已有启用模组的同 ID 冲突会阻止安装。
- 数据包 ZIP 解压为 `datapacks/workshop-<api-hash-prefix>-<project-id>/`。
  解压先在游戏扫描目录外完成，要求根 `pack.mcmeta` 的 pack_format=1、字符串说明及 data 目录。
  拒绝穿越、绝对路径、反斜线、驱动器写法、重复/大小写冲突、链接/特殊文件、加密或不支持的压缩、
  class/JAR/嵌套 ZIP。上限 10000 条、32 层、单文件 64 MiB、总展开 512 MiB；大于 1 MiB 的条目限制
  压缩比 200，逐条核对 CRC 和长度。平台的游戏语义校验仍不等于真实游戏运行验证。
- 平台 required 依赖必须已在同 API 下安装、启用且指纹未变；conflict 与反向 conflict 都检查。
  更新不能破坏已启用作品对旧版本的 required 依赖。模组 depends/breaks 也按实例中已启用模组核对；
  支持后端文档的保守范围子集，无法可靠判断的复杂范围拒绝并明确原因。Java/FabricLoader 本体要求
  仍由现有启动预设和游戏加载器检查，不假装已执行所有 Fabric 规则。
- 来源保存于每个实例根目录 `.pvzce-workshop.json`，记录 API、作品/版本/文件 ID、包摘要、
  安装指纹、版本号、游戏版本和平台依赖快照。模组指纹为文件摘要，数据包为稳定目录树摘要。
  只保存反向依赖检查所需快照，不复制完整更新日志。来源记录上限 4 MiB；备份说明超过 1 MiB 时提示分批导出，避免生成自己无法导入的备份。
  这是本地可编辑来源信息，不是平台签名或内容可信度证明。
- 只更新同来源、同作品的一个已知副本。发现本地修改、多副本、其他同名文件或共享包名冲突时拒绝覆盖，
  提示先备份整理。旧版本移到现有 `.pvzce-launcher-trash/`，保留启用/禁用状态；普通 IO 失败尝试回滚。
  进程在提交中被强杀不保证跨文件原子事务，旧内容仍保存在回收站，可能需要手动恢复或整理未登记的新包。
- 来源跟随模组开关改名、手工重命名、回收和恢复。导出 ZIP 在既有 `.pvzce-backup.json` 增加可选
  `origins` 字段；旧备份继续导入，新备份校验来源后恢复。不将 `.pvzce-workshop.json` 当作游戏内容包。

## 第二阶段实现与剩余后端需求

账号入口：`WorkshopSessionStore` / `WorkshopLoginActivity`；API：`WorkshopApi.request()`；
界面/任务：`PvzceWorkshopActivity` / `WorkshopViewModel`；安装与来源：`WorkshopInstaller`、
`WorkshopOrigins`、`WorkshopTarget` 与 `PvzceContentStore`。

1. **作品上传**已由独立的 `WorkshopCreatorActivity` / `WorkshopCreatorViewModel` 对接现有 Cookie/CSRF 接口，
   详见 [创作者说明](PVZCE_CREATOR.md)。私有作者响应不进入公共缓存；上传和审核状态分别展示。
2. **自动解析依赖**缺少直接的版本 ID 查询接口。目前依赖只有 version_id/kind，而版本详情接口要求
   先知道 project_id。建议后端提供公开的 `GET /api/v1/versions/{id}`（或等价批量接口），返回
   project_id、作品种类、版本及依赖，复用现有发布/分级/安全与授权规则。不存在/不可见版本返回 404，
   不暴露待审资源。当前界面显示版本 ID 并阻止缺失依赖，允许按已知作品 ID 手动查找，不全站扫描来猜依赖。
3. **系统浏览器原生授权**没有现成的 app callback/PKCE 或 device authorization 交接 API。
   当前 Casdoor 自有登录通过专用 WebView + 原生 Cookie 核验可对接现有后端；若某第三方身份提供方禁止
   WebView，需要后端支持原生客户端授权后再切系统浏览器，不能假设 Chrome Cookie 可直接读出。
4. **向游戏传登录**已实现受能力声明保护的临时参数与日志脱敏，但后端/游戏尚未支持，当前会离线启动。
   需要签发、单次兑换、受限游戏会话验证/撤销以及游戏参数消费；字段与条件见
   [游戏登录契约](PVZCE_GAME_LOGIN.md)。不把网站 Cookie 交给游戏，不宣称端到端登录可用。

## 第一阶段验证记录

主机真实 JUnit 已验证 HTTP 字节交互、地址/端口隔离、筛选编码、重定向拒绝、401、摘要与取消清理，
以及实际临时文件系统上的安装、旧版本恢复、禁用状态、来源备份往返、本地修改保护、安全解压、
依赖与反向依赖、游戏版本常量读取、目标实例切换和配置变更。
Android 测试源码增加账号/搜索字段重建恢复与 AndroidKeyStore 会话隔离检查。

已使用完整 JDK 17、SDK 35、NDK 27.0.12077973 完成：

```sh
bash tools/check_pvzce_profile.sh
bash tools/check_generic_java.sh
bash tools/check_audio_compatibility.sh
python3 tools/audit_generic_launcher.py
bash gradlew :FCL:testFordebugUnitTest --tests 'com.tungsten.fcl.generic.*' \
  :FCL:checkstyle :FCL:compileFordebugAndroidTestKotlin \
  :FCL:assembleDebug :FCL:assembleFordebug \
  -Darch=arm64 --no-daemon --console=plain --max-workers=2
```

以上相关单测、预检、Checkstyle、instrumentation 源码编译及两个 arm64 APK 构建均通过。
APK 包名分别为 `com.pvzce.pvzl` 与 `com.pvzce.pvzl.debug`，最终 APK 的解码 Manifest、
provider/旧入口隔离、专用登录进程及非导出 Activity、ABI 与 Android Debug 开发签名另行核验。
完整单测集未在本次扩大运行，不将相关回归冒充全库结果。

第一阶段草稿 [PR #4](https://github.com/pvzce/PVZCELauncher/pull/4) 保留不变；第二阶段从它派生独立分支，均未合并或创建正式 Release。
[分支 CI / APK 产物入口](https://github.com/pvzce/PVZCELauncher/actions/workflows/pvzce-build.yml?query=branch%3Afeat%2Fpvzce-workshop-stage1-20261019)
随最终代码推送触发；CI / UI 的实时状态以工作流为准，不能把本地构建通过写成设备界面通过。
本环境 `adb devices` 没有连接设备，没有执行设备 instrumentation 或获取界面截图。
没有提供实际部署的 API 地址、Casdoor 测试账号及真实游戏/作品 JAR，因此不宣称真实 Casdoor 登录、
线上下载、Android 游戏兼容性或完整端到端流程已验证。
