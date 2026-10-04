# 04 验收记录

日期：2026-10-04（Asia/Shanghai）。基线：4cdef745321e65325419493811a5482a60bd67bc。

## 实现

- 四个固定请求组复用同一 Redis TIME/Lua 原子令牌桶；跳转为对端 IP 的跨码 GET/HEAD 桶，默认容量 60、补充间隔 100ms。管理组扩展只提供共同模块，其 HTTP 接入由 05 实现。
- 跳转 HTTP 廉价格式拒绝在限流及缓存前；明确额度不足为 429、no-store、向上取整 Retry-After，HEAD 无体。Redis 判定不可用继续原跳转流程。
- RedirectService 的全部 selectById 加载路径共用每实例 Semaphore，默认 4、可配置、启动校验。仅实际查询占许可，完成/异常立即释放；缓存命中和共享等待不占额外许可，不新增队列。繁忙为 REDIRECT_LOAD_BUSY 503，不写拒绝负缓存。
- 核心池默认 max8、connection-timeout500ms，可配置；统计池既有预算保持。许可不是预留连接，也不保证 HTTP 总耗时。

## TDD 与运行证据

HTTP 第一轮预期 429、实际 404；实际查询第五个请求第一轮进入查询并失败，证明缺少即时准入；非法新增限流参数第一轮仍启动。对应实现后绿色。

- `RedirectRateLimitApiTest`：5 通过。启用采集，证明 GET/HEAD 拒绝先于依赖、忽略转发头、非法格式无依赖，429/繁忙 503 无 Cookie/事件；HEAD 无体，Redis 判定不可用的正常 GET 仍采集。
- `RedirectLoadProtectionTest`：3 通过。闩锁控制四个查询交叠，第五个立即拒绝；查询异常释放；命中与共享等待、等待超时独立加载及不可确认版本加载均遵循实际查询预算；查询结束、缓存回填尚未完成时许可已可用。
- `RedirectLoadCoalescingTest`：14 通过，原逐请求过期、按版本合并、异常清理和超时行为保持。
- `RedirectResourceConfigurationTest`：2 通过，真实 Spring 参数装配证明默认/覆盖预算，非法查询预算启动失败。
- `CreateRateLimitConfigurationTest`：2 通过，四组默认及非法新增额度配置启动校验。
- `RedirectRateLimitRedisIntegrationTest`：3 通过，真实 Redis 7.4.2 测试容器。两个独立 limiter/MVC 实例跨码 GET/HEAD 共用桶、组间隔离、不同 IP 分离、最后令牌八请求竞争仅一个获准、60 初始容量、SCRIPT FLUSH 后安全恢复。暂停真实容器连接时，实际服务的可控持久化查询边界四个在途、第五个 HTTP 503，无拒绝事件；四个成功 GET 仍产生事件；恢复后限流判定及缓存 hit 正常。故障测试使用与实际应用一致的 200ms 缓存命令超时。
- `RedisRedirectIntegrationTest`：47 通过，真实隔离 MySQL/Redis，原版本协调、负缓存、逐请求有效性、加载合并、多实例及故障恢复回归。fixture 明确允许各限流组，保证原故障测试继续定位缓存协议。
- `CreateRateLimitRedisIntegrationTest`：9 通过，既有 Lua 数值/补充/TTL/不确定响应/客户端故障回归保持。

最终针对性共 85 项通过，0 失败/错误/跳过。命令：

```powershell
./mvnw.cmd -q '-Dtest=RedirectRateLimitApiTest,RedirectLoadProtectionTest,RedirectLoadCoalescingTest,RedirectResourceConfigurationTest,CreateRateLimitConfigurationTest' test
./mvnw.cmd -q '-Dtest=RedirectRateLimitRedisIntegrationTest' test
./mvnw.cmd -q '-Dtest=RedisRedirectIntegrationTest,CreateRateLimitRedisIntegrationTest' test
```

原始 XML 在实现工作树 `target/surefire-reports/`；回归输出在 `target/04-unit-verification.log`、`target/04-cache-redis-regression.log`。本票未执行全套 RabbitMQ 回归、04～08 集成分支最终审查或全栈演示，这些证据由集成阶段补充。真实 Redis 故障预算测试在已确认的可控查询边界控制交叠；不将它宣传为 SQL 吞吐或性能结果。
