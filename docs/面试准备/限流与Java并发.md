# 限流与 Java 并发

返回 [面试导航](README.md)。本篇区分“单位时间的请求额度”“正在执行的查询数量”和“相同任务的共享”，再解释使用到的 Java 原子操作。

## 必会回答

### Q1. 为什么短链接需要限流？

匿名创建可能不断消耗 ID 和存储；大量合法无效短码可能绕过同码负缓存收益；管理查询也会占用数据库资源。按请求组设额度，目标是减少滥用和高频请求造成的压力。

这不是分布式抗 DDoS 能力；不同 IP、代理或共享网络都有边界，不能把本机项目说成完整反滥用平台。

### Q2. 为什么选择令牌桶？

| 算法 | 面试可解释的特征 | 项目状态 |
| --- | --- | --- |
| 固定窗口 | 实现简单，但窗口边界可能集中通过请求 | 扩展知识 |
| 滑动窗口日志 | 记录时间更细，但需要维护更多请求状态 | 扩展知识 |
| 令牌桶 | 以容量允许突发，以补充速率控制持续请求 | 当前实现 |

例如创建桶容量 3、每 6 秒补 1 个令牌：闲置后可以先通过 3 次，随后额度逐步恢复。它不是“严格任意一分钟最多 N 次”，也不能用启动容量推导实际安全 QPS。[Redis 限流说明](https://redis.io/docs/latest/develop/use-cases/rate-limiter/)。

### Q3. 令牌怎样计算，Lua 负责什么？

```text
tokens = min(capacity, oldTokens + (now - oldTime) / refillInterval)
有至少 1 个令牌：扣 1，允许
不足：拒绝并计算到下一个令牌的等待时间
保存 tokens/time，设置闲置 TTL
```

Redis 脚本内读取 TIME、补充、判断、扣减和保存，避免两个应用分别读到最后一个令牌并都放行。脚本短且有界，原子性只覆盖这次 Redis 操作，不覆盖后续 MySQL 业务。

代码允许小数令牌以累计恢复；`Retry-After` 向上取整到秒，表示当前判断下的等待起点，其他请求可能先消耗额度。

### Q4. 为什么使用 Redis TIME，而不是各应用的时间？

多个应用共享桶状态，使用 Redis 的时间减少应用时钟差异对补充计算的影响。代码也校验非法状态或时间倒退等情况，不能把异常状态当作稳定配额。

桶的闲置 TTL 不短于空桶补满时间。Redis 重启或清理使额度重置是接受的边界；TTL 不等于全局内存上限。

### Q5. 四组额度为什么不同？

| 请求组 | 维度 | 默认起点 | Redis 限流失败 |
| --- | --- | --- | --- |
| 创建 | 连接对端 IP | 容量 3，每 6 秒补 1 | 业务前 503，拒绝 |
| 跳转 GET/HEAD | 连接对端 IP，跨短码共享 | 容量 60，每秒补 10 | 放行限流阶段，继续核心跳转 |
| 管理写 | 鉴权后的共享分组 | 容量 5，每秒补 1 | 业务前 503，拒绝 |
| 管理查询 | 鉴权后的共享分组 | 容量 5，每秒补 1 | 查询前 503，拒绝 |

创建和管理较保守，跳转优先；跳转限流放行仍受实际 SQL 并发准入约束。所有数值可配置，只是本地验证起点。

### Q6. 为什么不用 Cookie 或用户传的 X-Forwarded-For 限流？

匿名 Cookie 可清除，不是稳定防刷身份；直接相信任意用户提交的代理头会给请求伪造来源的机会。当前使用连接对端 IP，忽略任意 Forwarded/X-Forwarded-For，管理分组不把真实令牌写入 Redis key。

将来置于可信反向代理后，需要先明确代理链和可信配置，再改变来源解析；不能只取一个请求头就宣称识别真实用户。

### Q7. EVALSHA 缺脚本和执行超时为何不同？

正常用 EVALSHA；NOSCRIPT 明确表示该次脚本没有执行，可通过 EVAL 加载并执行。执行超时则可能已经扣过令牌，仅响应丢失，盲重试可能重复扣减。

获准后业务失败也不退令牌。当前没有幂等扣费或 Redis/MySQL 联合事务，按请求组故障策略处理不确定结果。

## Java 并发追问

### Q8. 限流与 Semaphore 有什么区别？

令牌桶控制额度与补充速率；Semaphore 控制同时执行的实际查询数量。一个控制请求进入速度，一个限制正在占用的资源，可以同时存在。

跳转 SQL 的每实例准入默认 4，`tryAcquire` 失败立即返回 503，不等待许可。缓存命中和等待共享加载结果者不占 SQL 许可；真正执行查询的请求才占，查询结束或失败后释放。

“并发 4”不是“每秒只能 4 个请求”，更不是集群总并发 4。多个应用实例共享 MySQL 时，总压力仍可能叠加。

### Q9. 为什么用 putIfAbsent，而不是 get 后 put？

`ConcurrentHashMap` 的单次操作安全，不代表多个操作组合自动原子。两个线程都 `get` 到不存在后再 `put`，可能各自开始查询。

当前 `putIfAbsent` 原子地选出一个加载者，其他线程拿到同一个 Future。key 包含短码和缓存版本；不同短码或版本不共享结果，不能用一个全局锁串行所有跳转。

### Q10. CompletableFuture 是否意味着请求自动异步？

不是。当前加载者在自己的调用线程查询数据库，Future 用于向等待者共享结果和异常；等待者的 `get(timeout)` 仍等待当前 HTTP 线程。

只有显式选择异步执行方式和执行器时，任务才按相应方式调度。这里的“加载合并”与 MQ 的“后台统计发布”是两个不同机制。[Java 17 CompletableFuture API](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/CompletableFuture.html)。

### Q11. 为什么等待者超时后不 cancel 共享任务？

其他请求仍可能等待同一个任务。只因一个等待者预算耗尽就取消共享加载，会伤害其他请求，也不能保证底层 JDBC 查询真正停止。

当前等待者默认 200ms 后重读缓存，仍未命中才独立回源，允许额外查询。任务成功/失败后清理，使用 `remove(key, task)` 条件移除；失败不会永久留下坏 Future。

### Q12. finally 和线程中断怎样处理？

查询许可必须在实际查询结束/失败后释放，不能异常时漏掉许可，也不能在查询仍进行时提前释放。中断相关等待时恢复线程中断标记，再按当前用例的失败规则退出。

不要把所有异常都吞掉返回空结果，数据库失败与业务不存在不同；也不要把“共享等待 200ms”“Redis 超时 200ms”相加就承诺 HTTP 总耗时上限。

### Q13. 有界队列和原子结算为什么重要？

统计交接用 `ArrayBlockingQueue` 的有限容量，发布未确认尝试也有限制，避免把慢依赖压力变成无限内存积压。容量耗尽后按 best-effort 规则拒绝新交接，而不是让 HTTP 无限制等待。

发布 ACK、超时、关闭等可能竞争结算，同一尝试只能确定一次终态并正确归还许可。当前使用原子状态进行竞争控制；单个原子变量仍不能代替对整个流程不变量和资源所有权的理解。

### Q14. 现有实现还有哪些性能取舍？

当前限流每次准入使用一个新的有界连接，并关闭后释放；这样避免离线排队或自动重放不确定扣减，但会有连接开销。要进一步优化，先测量这一成本，再讨论连接复用、失败状态和重连策略。

扩大线程池、Semaphore、连接池或队列容量不是通用提速办法，可能只是把压力推给同一 MySQL/Redis。没有测量结果，不把“用了并发工具”写成高吞吐证明。

## 源码与测试入口

- [令牌桶实现](../../src/main/java/com/example/shortlink/ratelimit/RedisRateLimiter.java)、[限流参数](../../src/main/java/com/example/shortlink/ratelimit/RateLimitProperties.java)。
- [跳转限流拦截器](../../src/main/java/com/example/shortlink/api/ratelimit/RedirectRateLimitInterceptor.java)、[跳转与共享加载](../../src/main/java/com/example/shortlink/service/RedirectService.java)。
- [回源保护测试](../../src/test/java/com/example/shortlink/service/RedirectLoadProtectionTest.java)、[加载合并测试](../../src/test/java/com/example/shortlink/service/RedirectLoadCoalescingTest.java)。
- [创建限流说明](../架构与原理/create-rate-limiting.md)、[管理限流说明](../架构与原理/management-rate-limiting.md)、[验收与版本边界](../测试与验证/verification.md)。
