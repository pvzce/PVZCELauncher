# PVZCE 创作者工坊（第二阶段）

## 实际范围

从第一阶段草稿 [PR #4](https://github.com/pvzce/PVZCELauncher/pull/4) 的
`5a6983f6134a807c958b434fa4073e62d557c6cb` 创建独立分支
`feat/pvzce-workshop-stage2-20261019`，第一阶段分支没有被改写。
本阶段只修改 Android 启动器；不合并、不创建正式 Release。

2026-10-19 核对后端 `pvzce/site` main
`34e55bf950ac5d9c634d79e73ed1071dfc5313d0` 的 `internal/httpapi/workshop.go`、
`internal/workshop/{projects,uploads,quota,moderation,package}.go` 和工坊文档。
游戏 master 为 `a7a4b5bffc448e408bd6e953ec29a6c621649304`，仅作格式/启动协议参考。
游戏登录仍被后端与游戏能力阻塞，见 [登录交接契约](PVZCE_GAME_LOGIN.md)。

## 操作流程

1. 在原“账号与 API”页面保存服务根地址，通过现有 Casdoor + HttpOnly Cookie 登录。
   写操作沿用第一阶段加密会话和 CSRF/Origin，不使用虚构的原生 Bearer 登录。
2. 打开“我的作品”，刷新作者/协作者作品、额度及已登记游戏版本，支持前后翻页。
   私有作者详情直接从带 Cookie 的 API 获取，不落入公共工坊缓存。
3. 新建作品填写 slug、标题、类型、说明、许可证、标签和内容标注；也可编辑已有作品。
   **编辑资料或更换图标会让作品退回草稿并退出公开，需要重新审核**；类型不能更改。
4. 打开作品后创建版本：版本号、更新日志、stable/beta、兼容游戏版本、依赖。
   依赖按每行 `版本ID required|optional|conflict` 填写，只能引用服务器可见的公开版本。
   后端不提供按版本 ID 反查作品的接口，因此仍需从公开工坊详情找到依赖 ID。
   版本元数据不可编辑、版本号不能复用；更改声明或替换失败包须创建新版本。
5. 在“上传 / 审核”选择版本，再通过系统文件选择器选择模组 JAR 或数据包 ZIP。
   图标支持 PNG/JPEG，上传后明确点击“采用此图标”。文件显示为生成的 ASCII 名称，
   以避免 Unicode 文件名进入 HTTP Header；包内的 ID、版本及元数据不被改写。
6. 展示账号 used/reserved/daily_quota_bytes、单文件上限、日切时区；来源 IP 限额仍由服务端检查。
   读取文件时扣除服务器预留的 1 字节护栏，支持取消，复制到私有临时目录同时计算 SHA-256。
   包做有界 ZIP/JAR 路径、重复条目、加密/链接、展开大小、CRC、JSON/核心元数据预检；
   数据包禁止代码/嵌套包；模组校对版本和 PVZCE 依赖；图标检查类型与尺寸。
   **完整格式判定与病毒扫描以服务器为准**，本机预检不执行模组代码。
7. 原始 PUT 显示已发送字节。发送完毕后仍可能等待后端格式验证、ClamAV 和存储，
   这些步骤没有独立进度接口，界面只显示“等待校验与扫描”，不伪造百分比。
   HTTP 200 也可能返回 invalid/infected/failed：文件状态和 metadata 原样作为文本展示。
   failed 可申请重扫；infected 不提供绕过扫描的发布操作。
8. 上传不等于发布。分别为**作品**与**所选版本**填写原因、提交审核。
   draft/rejected 可 submit → pending；可 withdraw → withdrawn；withdrawn 可 resume → draft。
   驳回原因从私有详情 reviews 显示；可修改作品或新建修正版，再提交。
   界面不提供作者 approve/reject；只有服务器审核通过且满足格式/扫描条件才公开下载。

## 接口映射

写接口使用 `Cookie: pvzce_session=…`、`X-CSRF-Token`、`Origin`，禁止重定向。
只发送后端写 DTO，不发送 owner_id、角色、审核人或 state 等响应字段。

| 用途 | 已有接口 |
| --- | --- |
| 我的作品 / 私有详情 | GET `/api/v1/author/projects?page=N`，GET `/api/v1/projects/{id}` |
| 额度 / 游戏版本 | GET `/api/v1/upload-usage`，GET `/api/v1/game-versions` |
| 创建 / 编辑作品 | POST `/api/v1/projects`，PATCH `/api/v1/projects/{id}` |
| 创建版本 | POST `/api/v1/projects/{id}/versions` |
| 文件上传 | PUT `/api/v1/projects/{id}/uploads/package?version_id={vid}` 或 `/uploads/icon` |
| 选用图标 / 重扫 | POST `/api/v1/projects/{id}/icon`，POST `/api/v1/files/{id}/rescan` |
| 状态转换 | POST `/api/v1/projects/{id}/transitions` 或 `/versions/{vid}/transitions` |

上传是固定 Content-Length 的原始流，附 `Upload-Filename`、`Upload-SHA256`、
`Idempotency-Key`。不使用 multipart、不宣称断点续传。

## 绑定、取消与重试

文件选择开始时固定 API、账号 ID、作品 ID、版本 ID、用途和类型，回调不读取当前下拉框来重定向。
上传由 AndroidViewModel 持有，旋转/Activity 重建不启动第二次请求；文件选择目标也写入非敏感 SavedState。
离开并返回作者页面会复核本地登录身份，401 清除此 API 会话与私有页面；账号变化会清空作者表单并中止旧操作。
页面 API 固定，设置改变后须关闭作者页重新打开，旧请求不被自动转移到新服务。

取消断开 HTTP 连接，但不承诺已撤销服务器接收、扫描或额度扣除。网络结果不确定时保留原临时文件和
Idempotency-Key；点击“核对并重试”先查目标详情中相同用途/版本/SHA-256 的文件，再按原 key 请求。
不自动换 key 绕过 409 或声称零重复计费；失败/过期 key 的服务器语义可能要求刷新状态后重新选择。
已确认文件后若详情刷新失败，会保留确认结果并要求手动刷新，不提示重复上传。

进程被终止或退出作者 Activity 后不会后台续传，也不会自动重发；下次先刷新作品、文件和额度。
临时文件只在 cacheDir，结束时删除；进程异常留下的目录会在下次进入清理。没有持久化 Cookie/CSRF
到上传队列，也没有把作者数据、短时游戏凭证或上传文件放入实例及内容备份。

## 验证与限制

新增确定性单测覆盖写 DTO、字节长度、依赖/转换校验、安全包预检、原始 HTTP 上传头/内容、
冲突和不匹配确认、取消、能力缺失、凭证有效期/来源绑定、非序列化凭证和所有输出分块边界脱敏。
Android instrumentation 增加作者表单重建与选择目标绑定测试。

第一阶段远程 [run 39163682186](https://github.com/pvzce/PVZCELauncher/actions/runs/39163682186)
APK 构建、预检、单测、Manifest/签名均成功，实际 instrumentation 输出 `OK (8 tests)`；
工作流最终失败是仍匹配 `OK (4 tests)`。本阶段按当前指定测试类的实际测试集更新为 9，继续要求真实通过输出和无失败标记。

本阶段最终本地验证：相关 JUnit 52 项通过，Checkstyle 通过，新增 instrumentation Kotlin 源码编译通过，
`:FCL:assembleDebug` 与 `:FCL:assembleFordebug` 两种 arm64 APK 构建成功。
独立 PVZCE/通用 Java/音频预检与入口隔离审计通过；最终 APK 的 Manifest、非导出作者/账号/登录 Activity、
包名与 provider 共存、arm64 ABI 及 Android Debug 签名另行核验。
这些是定向验证，既有全库 controlconverter 金样差异未在本阶段扩大处理。本环境无连接设备，新增 instrumentation 只编译；
没有提供实际部署地址、测试账号和真实作品，不宣称已实测线上 Casdoor、真实上传/审核、生产额度或游戏登录。
