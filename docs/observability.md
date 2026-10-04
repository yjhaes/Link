# 安全日志与最小观测

业务端口返回服务端生成的 `X-Request-ID`，不信任客户端同名头。同步请求日志使用该值；请求结束或异常时清理/恢复线程 MDC。异步发布、消费与维护不保证延续请求身份，日志中的 `requestId=none` 是合法状态；不承诺完整分布式追踪。

管理端口沿用 [健康与管理边界](health.md)，仅 localhost 的 health/info/metrics。实际指标可经 `/actuator/metrics/{name}?tag=group:create&tag=result:rejected` 查询；无需 Prometheus/Grafana。探针与静态请求不占业务额度。

## 指标含义

所有新增指标为每进程观察，重启归零；并发快照近似一致，不能作为可靠计费或审计账本。HTTP计数在同步处理完成后记录，实际回源在途在 SQL 开始前增加，并且只在 SQL 完成/失败后减少，HTTP客户端取消不会提前释放。

| 指标 | 含义与单位 | 标签 |
| --- | --- | --- |
| `shortlink.http.requests` | 完成请求的 COUNT/TOTAL_TIME/MAX，秒；成功跳转与429/503均分别分类 | route/group/result |
| `http.server.requests`（及 active） | Boot实际HTTP计时；自动标签经允许列表替换，避免404路径、任意method/exception扩张 | route/group/result |
| `shortlink.rate.admission` | 实际调用限流后的 allowed/rejected/unavailable；获准后400也算allowed，鉴权或格式拒绝不算限流调用 | group/result |
| `shortlink.redirect.load.inflight`、`.rejected` | 真正 SQL 查询在途数、无许可立即拒绝累计次数；缓存命中/共享等待不占用 | 无 |
| `shortlink.cache.read` | 实际每次缓存读取的固定状态，包括重复检查；failed为抛出依赖异常，unavailable为无确认结果 | result |
| `shortlink.cache.write` | 尝试写入结果 stored/version_changed/failed；版本改变返回false不算依赖失败 | result |
| `jdbc.connections.*`、`hikaricp.*` | Boot/Hikari现有两池资源观察，核心 dataSource 与 stats/visit-statistics分别命名 | 固定 name/pool |
| `shortlink.mq.event.outcomes` | 本地交接获准、满、过期、限额、编码失败、关闭丢弃等已有快照计数 | result |
| `shortlink.mq.publish.outcomes` | attempt/accepted/return/nack/unknown/send-failed/recovery/recovery-failed；accepted仅代表 broker confirm，绝不表示已写访问日志 | result |
| `shortlink.mq.local.pending`、`.oldest.age` | 本地待发条数、最老待发年龄（秒），不是broker ready | 无 |
| `shortlink.mq.publish.unconfirmed`、`.recovering`、`.duration` | 本地未确认发布槽、恢复状态0/1、已有终局发布累计耗时（秒） | 无 |
| `shortlink.mq.consumer.deliveries`、`.persistence.attempts`、`.outcomes` | 交付次数、入账尝试、saved/duplicate/expired/invalid/attempt_failed/exhausted/permanent/interrupted，重试与交付不是PV | outcomes使用result |
| `shortlink.mq.consumer.inflight`、`.processing.duration` | 消费在途、累计处理时间（秒），包含重试 | 无 |
| `shortlink.mq.consumer.completed.average` | 自该消费者对象创建以来的累计完成均速（events_per_second），不是最近窗口吞吐 | 无 |
| `shortlink.mq.consumer.event.delay`、`.samples` | 已处理事件从发生到成功处理的累计延迟（秒）和样本数；可相除得均值，不是HTTP延迟，saved/duplicate的现有口径保持 | 无 |
| `shortlink.statistics.write.attempts`、`.outcomes`、`.failures`、`.duration`、`.inflight` | 复用写入快照，区分saved/duplicate/dropped/failed/uncertain；固定错误类别与累计耗时（秒） | result或category |
| `shortlink.statistics.query.timeouts` | 现有查询超时累计次数 | 无 |
| `shortlink.cleanup.backlog.known`、`.backlog`、`.backlog.lower.bound` | 最近成功SQL观察是否存在、过期行数、是否仅为下界；未知时known=0，其余NaN，绝不伪装0 | 无 |
| `shortlink.cleanup.observation.age` | 最近成功观察年龄（秒）；未知NaN，失败后的旧观察可能陈旧 | 无 |
| `shortlink.cleanup.last.deleted`、`.last.outcome` | 最近维护轮删除行数、固定结果one-hot；不是删除累计counter | last.outcome使用result |

新增 HTTP 模板仅为 `/api/links`、`/s/{code}`、`/api/links/{code}/enabled`、`/api/internal/links/{code}/stats`、`/visits`；其余统一 `other`。group仅 create/redirect/management_write/management_query/other；HTTP结果仅 success/redirect/invalid/rejected/unavailable/failed（active为未完成的自动观察）。IP、短码、原始URL、Cookie、访客摘要、eventId、requestId、任意路径或method从不成为标签。

MQ ready/unacked、队列policy和路由仍通过受限 RabbitMQ Management 与部署冒烟核验；这些本地指标没有伪造broker队列积压。HTTP302不证明统计已入账，更不证明用户打开了目标网站。

## 输出与保护边界

标准输出保留固定操作/结果/错误类别及必要耗时、服务端requestId。启动、终止关闭、缓存/限流/发布降级及实际成功后的恢复可见；重要已提交但缓存协调未确认单独 ERROR 记录安全 `shortCode`，用于既有恢复入口定位。恢复仅反映一次真实成功操作，不承诺全依赖持续健康，不修改业务重试、消费意图或启停状态。

成功跳转不逐条INFO。HTTP失败每个固定group/result最多每30秒一个样本；业务依赖错误按固定类别采样并记录 observedCount；驱动日志按固定依赖类别和级别每30秒最多一个样本。恢复日志同样有界。完整累计次数看指标，样本不承诺保留每个错误事件；重要已提交协调日志保留单次短码定位。

安全编码器明确保护 Hikari/MySQL/Spring JDBC、Spring AMQP/native Rabbit及既有安全适配器、Lettuce/Spring Redis 命名空间，以及 Spring Web/Tomcat HTTP框架的WARN/ERROR（例如PageNotFound原始路径），并兜底替换本应用携带Throwable的事件。保持级别、时间、logger、thread和服务端请求上下文，丢弃驱动文本与堆栈。业务源日志只输出受控类别/枚举，已移除访问事件ID及原始异常。禁止秘密、原始URL/查询串/请求体/Cookie/访客摘要/IP/完整Referer或UA/MQ payload进入普通日志。

这不是全局任意日志自动脱敏器：新依赖、新日志源、任意无Throwable业务文本以及新增MDC字段需要重新审核。保护发生于生产控制台编码边界，另行添加文件/网络appender必须配置同等保护。独立canary测试覆盖真实业务错误及Redis/JDBC/MQ原始异常、高频Redis驱动事件；Compose验收从真实请求和故障查询指标、检查实际应用输出，证据见 `.scratch/final-hardening/08-verification.md`。
