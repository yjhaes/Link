# Short Link

一个用于 Java 后端实习项目的短链接服务。当前实现覆盖匿名创建永久与限时短链接、MySQL 持久化、302 跳转，以及内部启用/禁用接口、到期判断和有限缓存同步重试。

## 技术栈

- Java 17
- Spring Boot 3.5.16
- MyBatis-Plus 3.5.17
- MySQL
- Redis，用版本化缓存加速已访问的可跳转短链接；Redis 不可用时跳转会回退到 MySQL
- Maven 3.9.16（通过 Maven Wrapper 固定）

## 代码组织

应用根包为 `com.example.shortlink`：`api` 处理 HTTP 输入输出，`service` 编排创建、跳转与协调恢复，`service.error` 表达业务失败，`shortcode` 保存发号契约与编码规则，`cache` 集中跳转缓存契约、快照与 Redis 实现，`persistence` 负责数据库映射和 MySQL 发号。完整目录、依赖方向与扩展位置见 [架构说明](docs/architecture.md)。

## 初始化数据库

在本地 MySQL 中创建数据库：

```sql
CREATE DATABASE short_link CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE DATABASE short_link_test CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
```

应用启动时会执行版本控制中的 `schema.sql`。表使用短码作主键，映射记录中的到期时间允许为空。

通过环境变量提供连接信息；默认开发地址为 `localhost:3306/short_link`，用户名默认为 `root`，密码默认为 `123456`。需要其他密码时设置 `DB_PASSWORD`，不要把真实凭据提交到仓库。

跳转缓存默认连接 `localhost:6379`，可通过 `REDIS_HOST` 和 `REDIS_PORT` 配置 Redis 地址。正值缓存 TTL 上限默认 5 分钟，可通过 `SHORT_LINK_REDIRECT_CACHE_TTL` 调整；每次写入会随机缩短 0%～10%，命中不会续期。

不存在短码的 `404` 结果也会缓存，上限默认 30 秒，可通过 `SHORT_LINK_NOT_FOUND_CACHE_TTL` 配置；默认写入 TTL 为 27～30 秒，命中不续期。成功查库后才能建立该结果，查询错误仍返回 `500`。持续更换不同的合法短码仍可能逐码回源，每个条目及协调占位均有有限 TTL。

## 网页

启动应用后访问 `http://localhost:8080/`，可在页面中输入原始网址，选择永久有效或按分钟设置期限，生成后查看短码和到期时间，并复制或打开短链接。页面由 Spring Boot 直接提供，无需额外的前端构建步骤。若应用对外使用其他地址，请设置 `SHORT_LINK_BASE_URL`，使生成的短链接指向可访问的服务地址。

## 运行

PowerShell：

```powershell
$env:DB_URL = 'jdbc:mysql://localhost:3306/short_link?serverTimezone=UTC'
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = '<本地数据库密码>'
.\mvnw.cmd spring-boot:run
```

## 接口

创建永久短链接，省略 `validMinutes` 或将它设为 `null` 即可：

```http
POST /api/links
Content-Type: application/json

{"originalUrl":"https://example.com/article?id=17#summary"}
```

按分钟设置有效时长时，`validMinutes` 必须是 1 至 5,256,000 的整数：

```http
POST /api/links
Content-Type: application/json

{"originalUrl":"https://example.com/article?id=17#summary","validMinutes":60}
```

成功返回 `201 Created`，JSON 中包含 `shortCode`、`shortUrl` 和 `expiresAt`；永久链接的 `expiresAt` 为 `null`。响应 `Location` 指向新短链接。

`201` 表示 MySQL 创建已确认提交，且 Redis 缓存版本轮换和旧结果清除已确认。缓存可保留无原始 URL 的有限期占位，不预热原始 URL。收到 `201` 后才开始的请求会按新映射使用；与创建重叠的旧请求仍允许按之前的查询快照返回 `404`，但其旧版本回填会被拒绝。

数据库创建已确认提交，但缓存协调失败或超时时，返回专门的 `503` 和 `Cache-Control: no-store`：

```json
{
  "code": "CREATE_CACHE_COORDINATION_UNCONFIRMED",
  "message": "Database creation committed; cache coordination unconfirmed.",
  "shortCode": "Ab12"
}
```

这表示映射已经保存，缓存协调尚未确认。服务日志带有同一短码，供维护者定位。数据库失败或提交未确认继续返回普通 `500`，不能据此宣称映射已保存。Redis 响应超时也可能发生在命令已执行之后。

维护者在连接同一 MySQL 主写库与 Redis 的受信任内部应用上下文中调用恢复入口：

```java
applicationContext.getBean(ShortLinkCreationService.class).recoverCacheCoordination("Ab12");
```

该入口不暴露为公开 HTTP API，需由维护代码取得 Spring 管理的服务实例。它确认映射存在后，仅轮换缓存版本并清除结果，正常返回表示协调已确认；异常表示需按同一短码重试。重复调用安全，不再次发号、不插入映射，也不按旧请求改写启用状态。数据库状态已变更时也可复用这一协调步骤，必须先确认 SQL 已提交。

创建接口没有请求幂等键。网络响应完全丢失后重复 `POST /api/links` 仍可能创建另一条独立映射；收到上述 `503` 时应保留短码并恢复协调，不能把重复 POST 当作协调重试。网页会展示已保存短码及恢复提示。

访问未过期且启用的短码 `GET /s/{code}` 后，系统返回 `302` 和原始 URL 的 `Location`；到期时或之后访问返回 `410 LINK_EXPIRED`；未过期但已禁用时返回 `403 LINK_DISABLED`。过期判断优先于禁用状态。格式错误或不存在的短码返回 `404 LINK_NOT_FOUND`。失败和跳转响应均带 `Cache-Control: no-store`。

内部管理者通过以下接口禁用映射；启用时将 `enabled` 改为 `true`。通过独立环境变量 `SHORT_LINK_INTERNAL_TOKEN` 配置管理秘密（对应 `short-link.internal-token`），无默认秘密；未配置或配置为空时接口关闭，返回 `404 RESOURCE_NOT_FOUND`。非空值必须至少包含 32 个 UTF-8 字节，否则启动失败。部署时使用 HTTPS，令牌仅通过 `X-Internal-Token` 请求头传递。

秘密应由密码学随机源生成至少 32 个随机字节，再编码为适合 HTTP 请求头的文本，例如在 PowerShell 7 中用 `[Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))` 生成后存入部署秘密配置。不要使用示例测试令牌；管理秘密应与将来的访客摘要 HMAC 密钥独立，不放入 URL、Cookie、页面存储、响应或日志。

配置后，缺失、错误或重复的令牌头返回 `401 INTERNAL_UNAUTHORIZED`；精确安全比较不 trim。鉴权在请求体及参数解析前完成，拒绝请求不进入 MySQL/Redis 状态业务；管理成功和错误响应均带 `Cache-Control: no-store`。公开创建与普通跳转继续无需令牌，不增加用户或恢复 HTTP API。

```http
PUT /api/links/Ab12/enabled
Content-Type: application/json
X-Internal-Token: <部署配置中的管理秘密>

{"enabled":false}
```

`enabled` 必须是真正的 JSON 布尔值，不能省略、设为 `null`、字符串或数字。成功返回 `200`、`Cache-Control: no-store`，以及：

```json
{"shortCode":"Ab12","enabled":false}
```

响应表示本次已提交操作的状态，后续合法变更仍可能覆盖它。短码、原始 URL 和有效期保持不变。非法或不存在短码返回 `404 LINK_NOT_FOUND`；已过期映射的任何状态操作返回 `410 LINK_EXPIRED`，到期当刻也拒绝，过期优先于重复状态；重复启用和重复禁用分别返回 `409 LINK_ALREADY_ENABLED`、`409 LINK_ALREADY_DISABLED`。这些业务拒绝不更新数据库、不同步缓存，均带 `no-store`。无效请求体返回 `400 INVALID_REQUEST`。

同一码的判断和更新使用 MySQL 行锁事务，等待锁之后重新判断当前状态和有效期；并发操作按数据库提交顺序生效。数据库只更新一次，提交后轮换缓存版本并清除结果，确认成功才返回 `200`。完成后新开始的访问看到当前状态；重叠的旧访问允许按原快照完成，其迟到旧回填会被版本校验拒绝。自然过期与后续合法变更仍正常生效。

缓存同步在当前请求内总共最多尝试三次，两次重试前分别等待 50ms、100ms；任何一次成功立即停止。只重试缓存版本轮换，不重写数据库状态，所以早期操作的延迟重试不会覆盖后来提交的反向操作。创建接口的原有同步策略保持不变。

三次均未确认成功时，数据库仍保留已提交状态，返回专用 `503` 和 `no-store`：

```json
{
  "code":"LINK_STATE_CACHE_COORDINATION_UNCONFIRMED",
  "message":"Database state update committed; cache coordination unconfirmed.",
  "shortCode":"Ab12"
}
```

收到 `503` 后保留短码。服务不在后台继续尝试，也没有持久恢复任务；长故障或进程退出后不保证自动恢复。等待被中断时保留线程中断状态并停止尝试，同样报告数据库已提交、协调未确认。Redis 的 200ms 单次超时与累计 150ms 等待不是整个 HTTP 请求的耗时上限。

恢复时仍由受信任内部应用上下文调用先前的 `recoverCacheCoordination(shortCode)`，仅同步缓存，不回写旧 enabled。重复 PUT 同一状态仍会返回 `409`，不能代替协调恢复。同步超时可能发生在 Redis 已执行之后，因此恢复允许安全重复。数据库更新失败或提交未确认使用普通 `500`，不同步缓存，不能声明状态已提交。

历史直接 SQL 维护仍必须区分“数据库状态变更已提交”和“缓存协调已确认、操作完成”；仅 COMMIT、仅 DEL 或绕过受控流程都不代表维护完成。直接 SQL 绕过接口业务校验，不应作为普通管理操作或过期后修改状态的依据。

未过期的禁用映射缓存独立 DISABLED 结果，保留 expiresAt；`short-link.redirect-cache.disabled-ttl`（环境变量 `SHORT_LINK_DISABLED_CACHE_TTL`）默认上限 15 秒，实际抖动为 13.5～15 秒，命中不续期。命中时业务到期则返回 410，并按版本条件转换为 EXPIRED。

禁用不会删除映射或释放短码；未过期映射重新启用后原短链接恢复跳转。启用与过期仍为独立状态，但管理 API 禁止过期后的启用和禁用。已提交的合法操作在同步重试期间过期仍继续同步，后续跳转按有效期返回 `410 LINK_EXPIRED`。

将来若增加删除映射或修改原始 URL／有效时长的操作，也必须在对应的 MySQL 事务提交后轮换该短码的缓存版本；本次不增加这些接口。状态管理与有限重试的决策见 [ADR-0005](docs/adr/0005-enabled-state-api.md)，缓存故障边界仍见 [受控恢复说明](docs/redis-recovery.md)。

## 测试

不依赖外部 MySQL 或 Redis 的测试可单独运行：

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.tools/maven-repository' '-Dtest=ShortLinkUseCasesTest,ShortLinkStateServiceTest,RedirectLoadCoalescingTest,PermutedShortCodeEncoderTest,RedisRedirectCacheTest,RedirectCachePropertiesTest,InternalManagementApiTest,InternalManagementDisabledApiTest,InternalManagementConfigurationTest' test
```

`PermutedShortCodeEncoderTest` 位于 `shortcode` 测试包。管理边界测试使用真实 MVC 和业务服务，以及数据库/缓存边界的测试替身，独立验证拒绝无副作用、鉴权先于损坏请求体解析、精确比较、重复头、未配置关闭、配置长度及公开接口不需令牌。这里使用被 Git 忽略的工作区 Maven 缓存，适合默认缓存目录不可写的环境；正常环境也可省略 `-Dmaven.repo.local` 参数。完整测试集还包含原 MySQL/Redis 回归和真实 RabbitMQ 异步、故障、资源与生命周期验收。

`ShortLinkApiTest` 连接真实 MySQL。默认测试库为本机 `short_link_test`，用户名为 `root`，密码为 `123456`；其他环境可按需设置测试连接变量：

```powershell
$env:MYSQL_TEST_URL = 'jdbc:mysql://localhost:3306/short_link_test?serverTimezone=UTC'
$env:MYSQL_TEST_USERNAME = 'root'
$env:MYSQL_TEST_PASSWORD = '<本地测试数据库密码>'
.\mvnw.cmd test
```

`RedisRedirectIntegrationTest` 使用 Docker/Testcontainers 启动 MySQL 8.4 与 Redis 7.2，验证版本化结构、有限 TTL 占位、轮换与条目丢失后的旧回填拒绝、重复跳转、SQL 次数、大小写隔离，以及 MySQL 提交后受控版本协调对禁用和重新启用跳转的影响；每个测试前都会清理 Redis。完整测试集需要 Docker 可用。其他集成测试通过 HTTP 接口及真实 MySQL 验证永久与限时创建、期限持久化、有效期边界、302 跳转、禁用与重新启用状态和错误响应。

创建与 404 的集成验收还覆盖：独立 Spring 应用上下文共享真实 Redis/MySQL 的迟到旧查询竞态；创建后的新请求可见性；404 命中不查库及自然到期；查询失败不产生负缓存；503 部分完成、协调执行后响应超时与重复恢复；恢复不增加映射或发号，也不恢复已经禁用的状态。服务和 Redis 边界测试使用可控随机输入验证 TTL 抖动端点及未确认提交的失败语义。

### 实例内跳转加载合并

跳转 miss 按短码和当前 Redis 版本共享正在进行的数据库加载，各服务实例独立持有任务；302、404、403、410 都适用。不同短码及不同版本并行，不使用长期本地缓存或跨实例 Redis 锁。加载成功或失败后清理任务，数据库失败保持 500，不建立拒绝缓存。

`SHORT_LINK_REDIRECT_LOAD_WAIT`（`short-link.redirect-cache.load-wait`）默认 `200ms`，必须为正且能用纳秒表示。它只限制等待共享任务的预算，并非 HTTP 总耗时上限。等待超时后重读一次缓存，仍 miss 则独立查询，不取消其他请求使用的任务。每个请求使用快照时重新检查业务过期。

Redis 版本无法确认时独立读取 MySQL，不共享旧任务，也不无条件回填。不同实例允许各回源一次。

### Redis 故障和恢复

`SHORT_LINK_REDIRECT_CACHE_ENABLED` 默认 `true`，维护期间设为 `false` 并重启可停用缓存读写，让跳转独立查 MySQL；创建可能已提交但仍返回协调未确认的 503，维护协调也不能报告完成。缓存开关不代替停止和排空所有旧实例。

重启、切换或恢复旧快照后，按 [Redis 受控恢复说明](docs/redis-recovery.md) 停用旧缓存、清理全部旧格式和旧版本，再恢复按需加载。即时可见要求以同一 Redis 主实例且已确认协调更新未丢失为前提；未经清理的故障切换或旧快照恢复不具备这一保证。说明包含 PowerShell 7 清理步骤、真实超时和旧数据恢复演示，以及穿透、集中到期、整体不可用和命中热点的压力边界。

### RabbitMQ 异步访问统计

正常 GET 冻结最小化访问事件后立即尝试有界本地交接，后台发布并消费写 MySQL；HTTP 不等待统计网络或 SQL。采集默认关闭，消费者默认开启；MQ 启动/运行故障仍允许核心跳转，best-effort 允许漏记且无同步回退。查询只计已记录事件，可能晚于刚发生的访问。Cookie、上海统计日、30 日窗口、管理鉴权和原缓存协议保持。

部署、policy、开关、人工暂停及死信处理见 [运维说明](ops/README.md)，消息和成功边界见 [异步设计](docs/async-visit-statistics.md)，完整测试及同条件测量见 [验证记录](.scratch/async-visit-statistics/verification.md)。测试使用 `MYSQL_TEST_URL`、`RABBIT_TEST_PORT`、`RABBIT_MANAGEMENT_URL`、`REDIS_PORT`，另需 Docker 供 RedisRedirectIntegrationTest 启动真实 MySQL/Redis；隔离 vhost 可设置 `SHORT_LINK_STATS_RABBIT_VIRTUAL_HOST`。test profile 默认禁消费，仅真实异步测试显式启用并关闭上下文，避免不同 Clock 的消费者互相抢消息。
