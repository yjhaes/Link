# 管理请求限流

管理鉴权先于限流、请求参数和请求体解析。未配置管理令牌仍为 404；缺失、错误、重复令牌仍为 401，此时不访问限流 Redis 或业务数据库。令牌仅供鉴权，不作为 Redis Key 的组成部分。

| 入口 | 共享请求组 | 默认容量 | 每个令牌补充间隔 |
| --- | --- | --- | --- |
| PUT /api/links/{code}/enabled | management-write | 5 | 1s |
| GET/HEAD /api/internal/links/{code}/stats、visits | management-query | 5 | 1s |

查询的两个入口、GET 和隐式 HEAD、不同短码及不同应用实例共享同一查询额度。写入和查询分别计数，且与公开创建、跳转额度隔离。参数位于 `short-link.rate-limit.management-write-capacity`、`management-write-refill-interval`、`management-query-capacity`、`management-query-refill-interval`，启动时校验；令牌桶允许初始突发，不保证任意滚动窗口上限。

额度不足返回 `429 RATE_LIMIT_EXCEEDED`、`Cache-Control: no-store` 和向上取整为秒的 `Retry-After`；该等待提示不承诺下一次必定获准。Redis 无法确认准入返回 `503 RATE_LIMIT_UNAVAILABLE`，表示操作或查询尚未开始。两类拒绝均不执行状态更新或统计查询，不设置统计 Cookie。HEAD 拒绝无响应体。

获准后业务失败不退令牌。原有重复状态 409、过期优先 410、统计 `STATS_BUSY`/`STATS_QUERY_TIMEOUT` 保持；`LINK_STATE_CACHE_COORDINATION_UNCONFIRMED` 的 503 仍表示数据库已提交且缓存协调未确认，与业务前限流 503 区分。现有管理页显示分别对应的提示，令牌仍只保留在当前页面内存中。

验证入口为 `python ops/tests/run.py unit`（MVC 与两个页面测试）和 `integration`/`all`（含隔离真实 Redis 的管理测试）。真实 Redis 验证使用 5 个初始令牌与可配置的 30s 补充间隔，避免机器调度影响突发计数；默认 1s 是配置值，不能把这些测试宣称为生产容量测量。
