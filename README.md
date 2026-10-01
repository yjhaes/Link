# Short Link

一个用于 Java 后端实习项目的短链接服务。当前实现覆盖匿名创建永久与限时短链接、MySQL 持久化、302 跳转，以及到期和禁用状态判断。

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

维护者可直接在 MySQL 中禁用或重新启用映射；当前没有对应的公开 API。每次维护都必须先确认 MySQL 状态变更已提交，再在受信任内部应用上下文调用同一短码的 `recoverCacheCoordination`，正常返回后才报告维护完成。禁用示例：

```sql
START TRANSACTION;
UPDATE short_link SET enabled = FALSE WHERE short_code = 'Ab12';
COMMIT;
```

MySQL 提交后，在连接同一 MySQL 主写库及 Redis 的内部维护代码中协调缓存：

```java
applicationContext.getBean(ShortLinkCreationService.class).recoverCacheCoordination("Ab12");
```

重新启用时同样先提交 MySQL，再调用上述协调入口：

```sql
START TRANSACTION;
UPDATE short_link SET enabled = TRUE WHERE short_code = 'Ab12';
COMMIT;
```

若协调入口异常返回，记录并报告缓存协调未确认，Redis 恢复后按同一短码重试。MySQL 状态变更已经提交，不要因此回滚或反向修改 MySQL，也不要用简单手工删除 Key 替代该受控完成步骤。

维护记录分两步报告：“数据库状态变更已提交”和“缓存协调已确认、操作完成”。仅 COMMIT、仅 DEL 或绕过受控流程的直接 SQL 都不代表阶段 4 操作完成。协调重试仅轮换版本并清除结果，不重设之前的 enabled 值、不重新创建映射。

未过期的禁用映射缓存独立 DISABLED 结果，保留 expiresAt；`short-link.redirect-cache.disabled-ttl`（环境变量 `SHORT_LINK_DISABLED_CACHE_TTL`）默认上限 15 秒，实际抖动为 13.5～15 秒，命中不续期。命中时业务到期则返回 410，并按版本条件转换为 EXPIRED。

禁用不会删除映射或释放短码；重新启用同一映射后，短码可恢复跳转。重新启用已过期的映射仍返回 `410 LINK_EXPIRED`。完成 MySQL 提交并确认缓存版本轮换后，新请求会加载当前数据库状态；先前查询不能再用旧版本回填，与维护操作重叠的请求仍可能按之前读到的快照完成。

将来若增加应用内启用、禁用、删除映射或修改原始 URL／有效时长的操作，也必须在对应的 MySQL 事务提交后轮换该短码的缓存版本；本项目当前不增加这些接口。

## 测试

不依赖外部 MySQL 或 Redis 的五组测试可单独运行：

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.tools/maven-repository' '-Dtest=ShortLinkUseCasesTest,RedirectLoadCoalescingTest,PermutedShortCodeEncoderTest,RedisRedirectCacheTest,RedirectCachePropertiesTest' test
```

`PermutedShortCodeEncoderTest` 位于 `shortcode` 测试包。这里使用被 Git 忽略的工作区 Maven 缓存，适合默认缓存目录不可写的环境；正常环境也可省略 `-Dmaven.repo.local` 参数。完整测试集除上述测试外还包含下述两个集成测试。

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
