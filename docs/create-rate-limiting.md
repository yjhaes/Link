# 匿名创建准入

当前实现仅覆盖 `POST /api/links`。创建按 Servlet 的连接对端 IP 分桶，忽略 `Forwarded` 和 `X-Forwarded-For`；IPv6 地址经数值解析规范化，同一个地址的不同文本形式共享桶。共享出口仍共享额度，换 IP 可以取得新桶；当前没有可信代理规则。

`short-link.rate-limit.create-capacity` 默认 3，`create-refill-interval` 默认 6s，每个间隔补充一个令牌。对应环境变量为 `SHORT_LINK_CREATE_RATE_CAPACITY` 与 `SHORT_LINK_CREATE_RATE_REFILL_INTERVAL`。容量必须为 1～1,000,000，间隔按毫秒解析必须为 1ms～24h，容量乘间隔不得超过七天；非法值在 Spring 启动绑定时失败。这些上限用于数值和状态生命周期保护，不是经过性能验证的预算。时间文本仅在构造绑定时解析一次，准入直接使用已校验毫秒值。

应用只接收 JSON 创建，基础配置显式关闭 Servlet multipart 解析，避免 DispatcherServlet 在拦截器之前解析 multipart。HTTP 在请求体反序列化、参数校验、发号和插入之前判定。获准消耗一个令牌，后续参数或业务失败不退还。拒绝返回 `429 RATE_LIMIT_EXCEEDED`、`Cache-Control: no-store` 和向上取整秒数的 `Retry-After`；等待提示不保证下一次必获准。限流依赖无法确认时返回业务前 `503 RATE_LIMIT_UNAVAILABLE`，不发号或插入、不返回已保存短码。既有 `CREATE_CACHE_COORDINATION_UNCONFIRMED` 继续表示数据库已提交、缓存协调未确认，页面保留此短码和恢复提示。

Redis Lua 在一次短且有界的执行内读取、用 `TIME` 补充、判断、扣减和设置闲置 TTL。初始化满桶，补充不超过容量，拒绝不扣负数。TTL 为容量乘补充间隔，因此空桶能在过期前补满；TTL 不是全局内存上限，也不是严格滚动窗口次数限制。Key 位于 `shortlink:rate-limit:v1:create:`，与跳转缓存隔离。非法数字、负令牌、超容量状态或未来时间拒绝确认，不将损坏桶当成新桶放行。

正常调用用固定脚本 `EVALSHA`；只有明确 `NOSCRIPT`（尚未执行）才用 `EVAL` 重载。超时、断连、OOM及其他不确定结果不重试。限流使用独立 Lettuce 客户端，每次准入建立并关闭一个连接，关闭自动重连，拒绝断连时命令，限制客户端队列为 8；不会在恢复连接后重放上一次不确定扣减。下次 HTTP 请求可建立新连接，恢复判定。应用关闭时销毁客户端。此选择有每次连接成本，目前未做吞吐承诺；它保留缓存客户端既有恢复行为。复用 `spring.data.redis` 的主机/端口、用户名/密码、数据库、SSL或 URL 配置；提供 URL 时以 URL 中的数据库为准（与 Boot 缓存客户端一致），仅无 URL 时使用独立 database 属性。复用连接/命令超时（基础配置各 200ms），两个超时不构成 HTTP 总截止。

页面提交契约测试为 `node src/test/js/create-rate-limit-page.test.mjs`。集成验收包括最后令牌并发、独立 Spring 上下文共享桶、IPv6规范化、补充/容量/TTL、异常状态、脚本清空恢复，以及 Redis 真正执行后丢弃 TCP 响应的503与单次扣减。既有缓存故障测试显式允许准入，继续单独验证已提交协调故障。
