# 短链接服务代码组织

项目使用单模块 Java 17 / Spring Boot / Maven 结构。当前只有短链接这一项业务功能，因此业务包直接位于应用根包 `com.example.shortlink` 下；`LinkApplication` 留在根包，以便 Spring Boot 扫描其子包。

## 目录

```text
src/main/java/com/example/shortlink/
  LinkApplication.java
  api/                 HTTP 控制器、请求与响应、参数反序列化、错误响应
  service/             创建、状态维护、跳转与缓存协调恢复流程，以及用例结果
    error/             业务失败类型，由 api 映射为 HTTP 响应
  shortcode/           发号契约与确定性短码编码规则
  persistence/         MyBatis-Plus 映射、MySQL 发号与映射保存错误分类
  cache/               跳转缓存契约、读取状态、快照、配置与 Redis 实现
  stats/               独立访问事件、身份与元数据规范化、统计池、同步日志记录
src/main/resources/
  application.yml      数据库、Redis 和服务地址配置
  schema.sql           MySQL 表结构
  static/              由 Spring Boot 提供的单页界面
src/test/java/com/example/shortlink/
  ShortLinkApiTest.java
  RedisRedirectIntegrationTest.java
  service/             创建、状态维护、跳转、协调恢复及请求合并测试
  shortcode/           短码编码测试
  cache/               Redis 缓存行为测试
src/test/resources/
  application-test.yml
```

## 模块与依赖

`RedirectDecision` 携带原始 URL 和最终逐请求检查时冻结的时刻。HTTP 边界的
`VisitCollection` 仅在正常 GET 决定后识别 Cookie、摘要和脱敏，再将独立 `VisitEvent`
交给 `VisitRecorder`。`MySqlVisitRecorder` 使用显式统计池、容量 2 的立即准入和独立
自动提交，不加入核心事务、不重新读取映射。统计配置显式声明主核心池，保留原有
MyBatis/JDBC/事务及初始化归属。详见 [采集部署说明](visit-collection.md)。

`ShortLinkStateService` 承担状态维护用例。入口挂起调用者事务，在独立事务中通过 Mapper 的行锁读取当前映射，获取锁后检查有效期和重复目标，再仅更新 enabled；独立事务确认提交后才轮换缓存版本。缓存同步失败在当前请求内最多尝试三次，等待 50/100ms，耗尽或等待中断报告专用部分完成错误；时间等待通过包级构造器的可控依赖进行测试。该用例复用现有 Mapper、Clock 和 RedirectCache，不新增通用仓储或后台任务。详见 [ADR-0005](adr/0005-enabled-state-api.md)。

`ShortLinkCreationService` 承担完整创建用例：URL 与有效时长校验、发号编码、短码冲突重试、提交后的缓存协调和内部协调恢复。`RedirectService` 承担完整跳转用例：短码格式校验、缓存回退、跳转拒绝判定、版本条件回填、实例内加载合并与逐请求到期复查。`api` 直接调用对应服务，把结果或错误转换为 HTTP 响应；没有保留纯转发的旧 façade，请求 DTO 不作为数据库记录使用。

`service` 保留流程编排和 `CreatedShortLink` 这一创建用例的结果。它不包含 HTTP 请求与响应 DTO、缓存协议类型或短码编码实现。`CreatedShortLink` 只包含短码与到期时间，由控制器转换为包含完整短链接的 `CreateLinkResponse`；不为单个用例结果额外建立 `model` 包。

创建服务通过构造函数接收 Mapper、`persistence/MySqlShortLinkWriter`、`shortcode/ShortCodeIdIssuer`、`shortcode/PermutedShortCodeEncoder`、`cache/RedirectCache` 和 `Clock`；跳转服务只接收 Mapper、缓存、Clock 和已有等待预算配置。发号契约与编码规则归属 `shortcode`；实际执行 JDBC 的 `MySqlShortCodeIdIssuer` 留在 `persistence`。`RedirectCache`、`RedirectCacheRead` 与 `RedirectCacheEntry` 同归 `cache`，`RedisRedirectCache` 提供生产实现，封装 Lua、序列化与 TTL。测试仍可在原有发号和缓存接缝上替换实现，无需新增接口或兼容类。

`MySqlShortLinkWriter` 集中插入确认和 MySQL 1062／PRIMARY 错误分类，只将短码主键冲突翻译为 `ShortCodeCollisionException`；其他唯一约束和数据库失败原样传播。创建服务决定重新发号重试一次，数据库知识不进入用例。该 adapter 不提供通用 CRUD。`ShortLinkMapper` 和带表字段注解的 `ShortLinkEntity` 仍由 MyBatis-Plus 管理；当前只有一张映射表，不增加透传的仓储接口或一套对应的纯领域实体。

生产代码的包依赖方向为：

```text
api         -> service、service.error
service     -> cache、persistence、shortcode、service.error
persistence -> shortcode
```

`cache` 不依赖 `service` 或 `persistence`，`shortcode` 不依赖业务流程或数据库实现，因此没有跨包循环。业务编排依赖缓存契约，不直接操作 Redis 实现；数据库实现也不反向调用业务编排或 HTTP 入口。`service` 仍使用数据库实体、Mapper 和缓存结果状态，这是当前项目接受的实现耦合，不将它描述为纯领域层。

`service/error` 中的异常表达创建或跳转失败的原因。`api/ApiExceptionHandler` 把普通错误映射成 `ApiError`；已提交创建的缓存协调未确认则使用带短码的 `CreateCacheCoordinationError`，业务流程无需知道 HTTP 状态码。短码编码规则由 `PermutedShortCodeEncoder` 封装，创建流程只调用 `encode`。

创建时先由 MySQL 分配 ID，再编码并插入映射；发号与映射分别提交。Spring 管理的创建入口挂起调用者事务，让 MyBatis 的非事务插入在返回时已提交。随后轮换 Redis 版本并清除旧结果，确认成功才返回创建完成；协调异常保留已提交短码并报告部分完成。受信任维护代码可调用 `recoverCacheCoordination` 按原短码仅重试缓存协调，不重新写库。

跳转在读取缓存前校验短码格式，再读取带版本的 Redis 条目。缓存 miss 或有限期占位都会继续查询 MySQL；可跳转、不存在、已过期和已禁用结果命中时使用相应快照。每次使用快照仍检查业务到期时间，保持过期优先于禁用的顺序。MySQL 成功查询后的四类结果只按读取时的版本条件回填；数据库查询错误不写成跳转拒绝结果。

Redis 使用 `shortlink:redirect:v2:` 命名空间，结果与版本占位都有有限 TTL。正值、不存在、已过期与已禁用结果各按配置上限写入，并向下抖动 0%～10%；限时正值的 TTL 还受业务剩余有效时长限制。命中不续期，缓存 TTL 不代替业务到期判断。Redis 不可用时回源 MySQL，没有拿到版本就跳过条件回填。

每个 `RedirectService` 实例按“短码＋缓存版本”共享正在进行的数据库加载，成功或失败后清理任务。不同短码或不同版本可以并行；版本无法确认时独立回源，不加入旧任务。等待共享任务的预算默认 200ms，超时后重读一次缓存，仍未命中则独立查询，不取消其他请求正在使用的任务。各请求在使用共享结果时再次检查业务到期时间。该机制允许多个实例各回源一次，也允许超时后的额外查询，不是全局互斥或 HTTP 总耗时保证。

创建、维护和恢复协调仍遵守数据库提交后轮换缓存版本的协议；即时可见依赖所有实例使用同一协议、同一 Redis 主实例且已确认更新未丢失。重启、切换或恢复旧快照时须按 [Redis 受控恢复说明](redis-recovery.md) 清理旧结果与版本。具体规则和一致性范围见 [ADR-0002](adr/0002-permuted-auto-id-base62.md)、[ADR-0003](adr/0003-redis-cache-aside-for-redirects.md) 与 [ADR-0004](adr/0004-negative-cache-for-redirects.md)。

测试目录与生产包对应。HTTP 和 MySQL/Redis 集成测试位于测试根包，覆盖接口可见行为；`service` 测试覆盖创建、恢复协调和并发加载，`shortcode` 测试覆盖固定编码向量及长度边界，`cache` 测试覆盖 TTL 和缓存开关。`RedirectCachePropertiesTest` 验证配置绑定及启动失败；`RedisRedirectCacheTest` 与实现保持同包，以使用包级可见的可控随机源构造器。运行方式见 [README](../README.md)。

## 后续功能的归属

访问统计、RabbitMQ 消息发布与消费、消费者幂等处理应归未来的 `stats` 功能；限流策略与存储归 `ratelimit`，HTTP 拦截入口可留在 `api`。少量类先在功能包内集中，出现实际代码后再细分，不预建空包，也不继续把新功能装入短链接的 `service`。

统计需要按每次访问请求记录，不能放在缓存未命中或共享数据库加载内部，否则会漏记命中请求或把多个访问者合并计数。HTTP 元数据在 Web 入口提取，成功解析后逐请求复查业务到期时间，再触发统计。统计事件不复用缓存状态、HTTP 请求 DTO 或映射实体；消费者去重与统计变更应在同一数据库事务中完成。消息投递、失败处理和限流策略属于后续功能设计，本次包整理不实现这些能力。

## 已有缓存配置与维护操作

`RedirectCacheProperties` 通过 `@ConfigurationProperties("short-link.redirect-cache")` 集中绑定，启动时校验。原配置键与 application.yml 中的环境变量占位保持不变：默认启用，正值／已过期 TTL 上限各 5 分钟，不存在 30 秒，已禁用 15 秒，加载等待预算 200ms。时长显式空值、非法格式、非正值或转换溢出都会导致启动失败；TTL 至少能表示为 1 毫秒，等待预算至少能表示为 1 纳秒。Redis adapter 与跳转用例各自只取得所需的配置值。

已核对所有生产引用和受信任维护说明：`deleteIfVersion` 没有调用或独立维护用途，已删除 interface 操作、Lua 和对应测试桩。维护完成仍通过 `ShortLinkCreationService.recoverCacheCoordination` 轮换版本，而不是手工删除。坏值按原值条件删除的 Redis 私有操作继续保留，用于避免删除并发写入的新值。

这两个用例集中各自的复杂规则；删除创建用例会把验证、重试和完成状态散回 HTTP，删除跳转用例会把并发与版本规则散回调用方。持久化保存 adapter 集中数据库特有分类，缓存 adapter 保留协议深度；已有发号、缓存和 Clock seam 继续支持测试与复用。
