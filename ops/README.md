# Consumer policies and safe logs

新环境运行完整演示使用 [全栈 Compose 入口](../docs/compose.md)：一次秘密初始化后构建启动，自动创建项目账号/vhost并重复应用 policy；默认只发布 localhost 应用、独立Actuator管理端口和 MQ Management，持久卷及受控恢复见该文档。以下手工 policy 入口保留给已有设施维护。

主队列策略同时包含 DLX/key、10000 条 ready 或 16 MiB、24 小时 TTL、reject-publish；所有字段保持在同一最高优先级普通 policy。EXPIRED 是合法消费终局，AUTO 确认且不写 MySQL。

## 部署和维护

配置单节点 RabbitMQ 的受限账号及 vhost，设置 `RABBITMQ_HOST/PORT/USERNAME/PASSWORD/VIRTUAL_HOST`，先在目标 vhost 应用本目录 policy 脚本，再启动应用并核验拓扑、binding、effective policy 和消费者。主拓扑是 durable direct exchange `shortlink.visit.x`、durable classic 队列 `shortlink.visit.stats.q`、routing key `visit.occurred.v1`，追加单一 DLX/DLQ。声明冲突先核验现存类型/持久化属性，规划受控迁移，应用不会删除积压来恢复。MQ 声明/消费后台启动，MQ 故障仍能启动核心；核心原有 MySQL/schema 初始化依赖仍存在。

采集默认关闭，启用需 `SHORT_LINK_STATS_ENABLED=true`、有效 `SHORT_LINK_VISITOR_HMAC_KEY` 和 `SHORT_LINK_VISITOR_KEY_VERSION`；管理/查询使用独立 `SHORT_LINK_INTERNAL_TOKEN`。所有实例身份密钥及版本一致，不使用演示密钥或 guest 作生产凭据。关闭采集并重启只停止新事件/Cookie，历史消息继续消费、查询和清理，停采期间不补采。

消费者默认开启。持续 DB 故障时将所有实例 `SHORT_LINK_STATS_CONSUMER_ENABLED=false` 并重启，发布继续直至有限预算允许丢弃。受信任进程内也可对 `visitListener` stop/start；人为暂停不被 MQ 恢复覆盖，没有公开运维 HTTP 端点。暂停不是瞬间撤回预取消息。修复 DB 后核验容量、错误类别、ready/unacked 和处理速率再恢复消费。

正常关停停止新交接和恢复任务，不无限 drain；本地未发送事件允许丢失，未确认可已送达，未 ACK 可重投。生命周期等待有界不代表 OS 进程总截止。窗口限制清理后过期消息的重放，唯一键只保护仍保留的日志。

## 观测和人工死信排查

通过受信任应用上下文读取 `AsyncVisitRecorder.snapshot()`、`VisitConsumer.snapshot()`、`VisitWriteObservations.snapshot()` 及清理 snapshot，配合 broker ready/unacked、容量/TTL 观测。区分事件接受/丢弃、发布尝试/confirm/return/nack/unknown、消费 delivery/持久化尝试/保存/重复/expired/失败。`processedEventDelayMillis/count` 是已保存或重复事件的平均延迟；`completedPerSecond` 是启动后的完成均速，不是瞬时吞吐、broker 最老事件或消费水位。collectionEnabled 只代表配置，generatedAt 只代表生成查询时间。

DLQ 是有限诊断样本，classic 转移及 TTL/容量淘汰允许丢失，没有自动消费者/回流。使用受限 Management 在受控环境查看少量消息，不将 payload、身份摘要、连接秘密或驱动原文复制到日志/工单。根据安全类别确认格式/schema/长度/时间错误、DB 永久故障或重试耗尽并先修复原因。主队列满的 reject-publish/nack 不是死信；主队列 TTL 与 30 日业务窗口独立。

确需人工重放时由维护者决定，通过标准 broker 发布工具将原 UTF-8 JSON body 发布到上述 exchange/key，保留 schemaVersion、eventId、occurredAt、statDate 和其余最小化字段，使用持久消息并核验路由/确认。禁止修改 ID/时间绕过校验；超窗确认丢弃，未来日期拒绝，已记录同 ID 去重。观察最终数据库查询，confirm 不代表保存。项目没有自动补偿或重放工具，不保证跨重启累计三次上限。

## 能力与面试说明

当前能力包括有界本地缓冲、分离连接、confirm/returns、有限发布恢复、AUTO ACK、eventId 幂等、分类有限重试/DLQ、窗口/容量/TTL/prefetch、安全日志和人工暂停。批量、并发优化、自动暂停/探测及受控重放工具尚未实现；outbox、事务消息、quorum、MANUAL ACK、永久去重和端到端恰好一次仅属知识范围。

面试围绕实测 JDBC 等待移出 HTTP 后的变化、本地接受/broker confirm/MySQL 提交三个成功边界、AUTO/NONE、提交至 ACK 间隙重投、新 GET 与旧 eventId、冻结时间/隐私、背压/积压及 best-effort 为何不引入 outbox。结果见 [验收记录](../.scratch/async-visit-statistics/verification.md)，不宣称生产 SLA 或大规模吞吐。

Apply `set-visit-consumer-policies.ps1` with explicit ManagementUrl, VirtualHost and a PSCredential. It changes only the two exact queue policies; it does not delete queues, purge backlog or create consumers. Credentials and management response bodies are never printed by the script. Use an appropriately restricted management endpoint and account.

Policy priority 20 configures the business queue DLX/key, 10000 ready messages or 16 MiB, 24-hour TTL and reject-publish, and the sole DLQ limits: 1000 ready messages or 4 MiB, 24-hour resident TTL, drop-head. The DLQ declaration fixes durable classic type and has no return DLX. These limits do not include unacked messages. Classic dead-letter transfer and subsequent TTL/overflow can lose events; this is diagnostic sampling, not reliable compensation storage. A higher-priority matching ordinary policy would replace this definition, so main-queue capacity/TTL settings must extend the same policy rather than shadow its DLX fields. Existing incompatible declarations require a controlled operator decision; the application never deletes accumulated messages to repair a declaration.

One synchronous listener delivery makes at most three persistence attempts, with 200 ms and 500 ms pauses. SAVED/DUPLICATE and valid EXPIRED returns allow AUTO ACK; EXPIRED never enters MySQL. Controlled busy/transient/uncertain causes retry; permanent/invalid/unrecognized causes reject immediately. Exhaustion rejects without requeue. The decoded event stays unchanged throughout this round. A process crash or unacked connection loss can start a fresh three-attempt round; x-death counts dead-letter events, not ordinary requeues. There is no automatic DLQ consumer or replay.

The console encoder emits bounded samples of JDBC/Hikari/AMQP/native Rabbit events, retaining severity, logger, timestamp and thread while replacing unsafe dependency messages and exceptions with a fixed database/mq category. Each category and severity emits at most one sample per 30 seconds; metrics provide cumulative outcomes, so logs are not a complete event history. This scope includes core Hikari/Spring JDBC logs as well as statistics, because these dependencies share logger names. Safe application categories remain readable. Driver text, connection credentials, payloads and visitor hashes are not printed by these dependency events; troubleshooting uses safe application outcomes and broker/DB metrics. Logger levels remain unchanged, while repeated dependency events are suppressed at the output boundary. Additional Redis, HTTP framework and business protection boundaries and their limits are documented in [observability](../docs/observability.md). Any extra appender introduced later must use the same encoding boundary before emitting dependency data.

代码资源所有权见 [实际架构](../docs/architecture.md)：应用 MQ 生命周期由 `stats.messaging.VisitMqRuntime` 集中，发布恢复只重建发布连接，终止由后台 adapter 执行并共用有限等待预算。统计持久化仅确认保存；本轮目录重构没有改动本目录拓扑、policy 或部署参数。

本地独立秘密初始化与默认采集/消费行为见 [本地秘密说明](../docs/local-secrets.md)。初始化只生成配置，不创建数据库或 broker 账号。
