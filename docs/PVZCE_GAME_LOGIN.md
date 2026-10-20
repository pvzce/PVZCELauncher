# PVZCE 启动登录交接 v1（启动器已实现，后端/游戏待实现）

本文件是启动器实际请求/参数和跨仓库待实现契约的唯一说明。现有
`pvzce/site@34e55bf9` 只有 Casdoor OIDC + HttpOnly Cookie + CSRF 网站会话，
没有以下 launcher/game 接口；`pvzce/pvzce@a7a4b5bf` 也未消费登录参数，
其 `PvzceGameProvider.getLaunchArguments(boolean sanitize)` 目前直接返回全部参数。
**当前不能通过启动器把网站账号登录到游戏**，不能用 Cookie 假冒游戏 Bearer 凭证。

## 启动器已实现的行为

“账号与 API”新增“启动时尝试账号交接”选项，默认关闭。仅 PVZCE 预设启动路径会尝试，普通 Java 启动不交接。
先检查实际游戏 JAR 根目录 `META-INF/pvzce-launcher.json`（最多 16 KiB）：

```json
{"game_login_protocol":1,"sanitized_launch_arguments":true}
```

两个值都满足才允许请求服务器能力；**游戏完成参数消费及脱敏后才可打入这个声明**。
不支持、未登录、会话过期、网络错误或无效响应，均提示原因并本次离线启动，不注入这两个参数。
现有游戏没有声明，所以不会收到未知参数或泄露给旧 getLaunchArguments。

后端声明且签发成功后，启动器把以下字面 token 追加到已校验启动命令的末尾：

```text
--pvzceApiUrl https://site.example
--pvzceLoginCode <32至128位Base64url短码，无填充>
```

例如桌面等价命令（占位符不是真实码；Android 内嵌 JVM 采用同一参数数组，不经过 shell）：

```sh
java -jar pvzce.jar --pvzceApiUrl https://site.example --pvzceLoginCode '<一次性短码>'
```

API 为规范化 origin：HTTPS 根地址，保留非默认端口，不允许路径、userinfo、query、fragment。
HTTP 仅允许启动器既有本机/模拟器开发地址；游戏端也须明确限制开发例外，生产保持 HTTPS。
启动器仅发成对 token；建议游戏也解析 `--pvzceLoginCode=…` 以确保该格式也能脱敏，但重复参数应拒绝登录。
无两个参数时正常离线；只有一个、缺值或无效地址时显示普通错误并离线，不回显值或请求非预期服务。

码对象不实现 Serializable，仅在本次内存准备/桥接阶段存在；不会写入 JavaLaunchSpec、实例表单/配置、
来源记录、上传状态或备份。保存实例与校验配置均拒绝手填保留参数。进入实际 JVM 前再次检查过期，
若等待 Surface 已超过有效期则中止本次启动并提示重启或关闭交接离线启动，不把过期码发给游戏。

FCLauncher 参数日志同时隐藏 `--accessToken` 与 `--pvzceLoginCode` 的分离/等号形式；
FCLBridge 在日志落盘/展示前按已知短码处理原生 stdout/stderr，跨块拆分也不泄露完整短码；
异常记录同样脱敏。游戏自身写文件、HTTP 调试、崩溃报告和第三方模组能看到的参数必须由游戏侧处理。

## 后端必须新增的接口（尚不存在）

沿用站点 `{ "data": ... }` JSON envelope；全部敏感响应 `Cache-Control: no-store`。

### 1. GET /api/v1/launcher/capabilities

公开只读，只有完整配置、可签发/兑换时返回：

```json
{"data":{"game_login_protocol":1}}
```

旧部署 404、503 或 protocol 缺失/不为 1 时启动器不签发。不能先宣布能力再伪造成功响应。

### 2. POST /api/v1/launcher/login-codes

启动器使用现有网站 Cookie、`X-CSRF-Token`、`Origin`；不接受请求自报的 user_id。
由服务端从已验证、未过期、未封禁的站点会话识别账号，限流并绑定 origin、游戏/协议/游戏版本。
请求（game_version 来自实际 JAR 的 PvzceVersions 常量）：

```json
{"protocol":1,"game_id":"pvzce","game_version":"1.0.0"}
```

响应（字段名/参数名已由 GameLoginProtocol 实现）：

```json
{"data":{"protocol":1,"api_url":"https://site.example","login_code":"<至少256位随机熵的Base64url无填充短码>","expires_at":"2026-10-19T01:01:00Z"}}
```

有效期建议 60 秒，**最多 90 秒**，启动器按收到响应时的本机时间检查 future 且不超过 90 秒。
不同 origin、错误协议、格式/时间无效均丢弃并离线；客户端时间明显错误也可能导致离线，不延长服务端期限。
服务端仅保存短码摘要和授权绑定，原子单次兑换，禁止日志记录原码。
签发不刷新或导出 Casdoor 密码/refresh token/客户端密钥，也不把网站 Cookie 传给游戏。

### 3. POST /api/v1/game/login-code/exchange

游戏不携带网站 Cookie/CSRF，仅持一次性码，服务端按摘要原子验证并消费。
建议请求：

```json
{"protocol":1,"game_id":"pvzce","game_version":"1.0.0","login_code":"<启动参数中的短码>"}
```

建议响应：

```json
{"data":{"token_type":"Bearer","access_token":"<受限的不透明游戏会话令牌>","expires_at":"2026-10-19T02:00:00Z","scope":["game:session"],"user":{"id":42,"display_name":"玩家"}}}
```

游戏会话建议最多 1 小时，无 refresh token；不能调用网站作者上传/审核/管理接口。
身份只信后端返回与验证结果，不信启动参数中的账号 ID（本契约根本不传 ID）。
兑换必须拒绝过期/已使用码、错误 game_id/version/protocol、退出/封禁/撤销的站点会话。
同码并发只有一个成功。响应丢失后再次兑换不能重新产生新会话，提示重新从启动器启动获取新码。

### 4. GET /api/v1/game/session；POST /api/v1/game/logout

Authorization `Bearer <游戏令牌>`，不接受网站 Cookie 作为游戏令牌。
GET 返回上述 user、expires_at、scope，服务端每次验证过期、撤销、账号状态及受众；POST 使当前游戏会话失效，成功 204。
游戏首兑后验证会话再显示“已登录”；内存保存令牌，退出/过期切离线，不写存档、配置、命令行或备份。
需要长期登录/账号联机权限应另行设计，不能把这个身份交接等同联机鉴权已完成。

## 游戏实现、错误处理与验收

- 在 Loader/客户端的适当边界消费两个保留参数；保留码的私有内存副本仅用于兑换，及时释放引用。
  `getLaunchArguments(true)` 必须隐藏值；建议 false 的公开参数也不再包含已消费码，避免第三方模组读取。
  两种参数形式、重复/缺值、默认日志、崩溃报告、URL/HTTP 调试输出均须覆盖。
- GET/POST 限定同一已验证 origin，不跟随跨站重定向；凭证放 JSON/Header，禁止 query。
  兑换/会话验证异步、有超时，不能让游戏主线程等待网络而无法进入离线游戏。
- HTTP 400（参数）、401（缺失/过期/重放/撤销）、403（账号禁用/受众）、404（未部署）、409（冲突）、
  429（限流）、503（未配置）及网络错误只显示普通状态；后端可用非敏感 error code 区分原因。
  不把任意响应正文、短码/令牌写到日志或 UI。不循环重试单次码；重新登录需要新启动码。
- 只在签发、兑换、服务端身份验证均成功后显示已登录。任何失败清掉内存凭证、可提示重启登录并继续离线；
  无参数、旧版本、未登录及关闭选项时离线启动回归必须通过。
- 集成验收必须用真实测试部署与账号验证：账号身份一致、有效期、双并发只成功一次、重放失败、退出/封禁撤销、
  origin/协议/游戏不匹配、超时离线、两种参数形式及 stdout 分块脱敏；检索实例/备份/日志/崩溃报告不得发现凭证。
- 以上都实现并验证后再加入能力声明；当前游戏与后端均未接入，不得根据启动器占位契约宣称端到端可用。
