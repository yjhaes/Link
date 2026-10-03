---
status: accepted
accepted_date: 2026-10-03
---

# 阶段 7：RabbitMQ 消息重复消费与消费者幂等

2026-10-03 通过 grill-with-docs 完成 Q1～Q10 逐轮确认。基于已经完成的 RabbitMQ 异步访问统计，保留现有实现，明确消息重复处理与统计入账的边界，并确认两类新增测试计划。本阶段设计已接受；新增测试尚未编写或执行，不将设计确认当作实施验收。

本设计延续 [ADR-0007](adr/0007-rabbitmq-visit-statistics.md) 和[阶段 6 设计](async-visit-statistics.md)，不替代既有业务契约，也不新增重复的 ADR。领域术语继续采用根目录 [CONTEXT.md](../CONTEXT.md) 的 single-context 布局。

## 业务契约

跳转优先，统计为 best-effort，允许采集、发布、积压及故障期间漏记。接受同一事件被多次投递或执行；在事件身份和冻结数据不变、事件处于可接收入库窗口的前提下，由 MySQL 唯一键防止重复生成访问日志。窗口外的合法事件不再接收入库。

不承诺端到端不丢、消息只处理一次、严格 Exactly Once 或永久去重。不增加 Redis 幂等层、统计汇总表、分布式事务、outbox 或自动死信回流。

## 已确认决策

| 问题 | 选择 | 结论 |
| --- | --- | --- |
| Q1：保障目标 | A | 接受重复投递，防止有效窗口内同一事件重复入账，保留 best-effort |
| Q2：事件身份 | A | 独立访问生成新 UUID；同事件重试、重投、重放沿用原身份和冻结数据 |
| Q3：幂等存储 | A | 保留直接 INSERT 与 MySQL 事件唯一索引，不增加 Redis SETNX |
| Q4：结果未知 | A | 同事件有限重试；异常或进入 DLQ 不代表数据库未执行 |
| Q5：ACK | A | 保留同步 listener、Spring AUTO、batchSize=1，不改为 MANUAL |
| Q6：重试上限 | A | 每轮最多 3 次，断线重投可开启新一轮，不追踪跨重启累计次数 |
| Q7：统计与事务 | A | 日志为统计权威，不新增汇总表或额外显式写事务 |
| Q8：保留窗口 | A | 窗口内唯一键去重，窗口外不入库，不维护长期去重表 |
| Q9：测试范围 | B | 补并发首次插入，以及结果未知后重试判重、最终 ACK 的完整链路 |
| Q10：最终确认 | A | 设计讨论收束；保留实现，记录测试计划及面试边界 |

## 为什么不能假设只消费一次

数据库提交与 RabbitMQ 消费确认是两个独立操作。消费者可能已经提交日志，却在 ACK 到达 broker 前宕机或断线；broker 无法由自己的确认状态判断数据库是否提交，会重新投递未确认消息。

本地持久化重试也会再次执行同一事件。人工重放或重新发布同一事件则可能产生另一条 broker 消息，即使它的 redelivered=false，也必须使用相同的业务事件身份接受唯一键检查。redelivered=true 不能作为跳过业务的依据，因为此前投递可能尚未写库。参见 [RabbitMQ 可靠性说明](https://www.rabbitmq.com/docs/reliability) 与[确认说明](https://www.rabbitmq.com/docs/confirms)。

当前 Producer 在发送失败或确认未知时不自动补发；不能把通用的生产者重发风险描述成项目已有补发能力。

## eventId 的职责与生成

`VisitEvent.eventId` 标识一次访问事件，不标识一次消费尝试或一个匿名访客。当前 `VisitCollection.collect` 在每个符合统计口径的 GET 请求中调用 `UUID.randomUUID()`，事件随后以冻结数据交给后台处理。

- 新的 GET，包括刷新和客户端重新发起请求，产生新 eventId。
- 同事件的消费重试、RabbitMQ 重投和人工 DLQ 重放保留 eventId、occurredAt、statDate、访客摘要、密钥版本及元数据。
- 不根据短码、访客和日期生成相同 ID，否则会把正常刷新合并并少算 PV。
- 不以数据库自增日志 ID、channel 的 delivery tag 或 redelivered 标记代替事件身份。

同 eventId 必须代表同一份冻结事件；用同 ID 修改业务数据不属于本设计支持的重放契约。当前无需额外增加载荷摘要比对机制。

## MySQL 唯一键作为保障

当前持久化只有一条业务写入：向 `short_link_visit_log` 插入访问日志。`event_id` 为 BINARY(16)，具有 `uq_visit_event(event_id)` 唯一索引。直接尝试插入，确认成功返回 SAVED；仅明确命中事件唯一键的冲突返回 DUPLICATE。其他唯一键、CHECK、SQL 或资源错误不能吞成重复成功。

两个消费者同时插入同一个新 eventId 时，不依赖应用先查后写。数据库唯一约束参与写入竞争，防止生成两条相同事件的已提交日志。先 SELECT 再 INSERT 存在竞争窗口，仍需要唯一索引，还增加一次查询，当前不采用。

不增加 Redis SETNX：先标记 Redis、后写 MySQL，标记成功而写入失败时可能误跳过重试；先写 MySQL、后标记 Redis，标记失败时仍需要 MySQL 去重。带过期时间的锁也不能替代最终业务记录的唯一约束。当前没有减少重复数据库请求的性能证据需要引入额外状态。

## 执行结果与 ACK

| 消费结果 | 当前处理 |
| --- | --- |
| SAVED：数据库明确确认保存 | 正常返回，由容器 ACK |
| DUPLICATE：明确命中事件唯一键 | 正常返回，由容器 ACK，不新增日志 |
| EXPIRED：合法事件已超窗 | 不写库，记录过期丢弃，正常返回，由容器 ACK |
| BUSY、暂时故障或 UNCERTAIN | 沿用原事件有限重试，尚未取得终局前不正常返回 |
| 永久错误、非法消息 | 拒绝且不 requeue，走死信路由 |
| 重试耗尽 | 拒绝且不 requeue，走死信路由；业务结果仍可能未知 |

执行阶段超时、断连或响应丢失，不能证明数据库没有提交。若首次没有提交，原 ID 的重试可以插入；若首次已提交，原 ID 的重试会命中唯一键。无需另行先查数据库决定是否重试。重试耗尽进入 DLQ 时，日志仍可能已经存在，排查或重放不得更换 eventId。

数据库已经明确保存后，关闭 statement 或 connection 失败，不抹掉已确认的 SAVED；这与执行阶段未收到结果不同。

保留同步 listener 与 Spring AUTO、batchSize=1。AUTO 由容器依据 listener 的执行结果确认，不等于 RabbitMQ 的无须等待业务完成的 autoAck；参见 [Spring AMQP 3.2.12 容器说明](https://raw.githubusercontent.com/spring-projects/spring-amqp/v3.2.12/src/reference/antora/modules/ROOT/pages/amqp/containerAttributes.adoc)。不吞持久化失败，也不提交异步落库任务后提前返回。MANUAL 并非业务完成后确认的必要条件，当前没有修改需求。

## 消费重试与 redelivery

每轮最多执行 3 次，包含首次，重试前等待 200ms、500ms。永久错误不重试，暂时错误及提交未知可以重试，耗尽后拒绝且不 requeue。间隔不构成消费总耗时上限。

宕机或断线发生在终局确认／拒绝之前时，未确认消息可能重投并开启新一轮。因此不能说一条消息整个生命周期最多执行 3 次。不维护跨重启累计尝试状态，也不使用无限 requeue 循环。

同一事件可能执行多次，但唯一键仍防止重复入账；持续数据库故障需要观测、人工暂停和恢复，有限重试不等于自动止损。沿用既有 DLQ 排查流程，没有自动回流消费者。

## PV、UV、日志与事务

PV 使用范围内日志 COUNT(*)，UV 使用整个范围的 `(visitor_key_version, visitor_hash)` 去重，访问明细也来自同一张日志表。事件去重与访客去重是不同职责。

| 场景 | 日志 | PV | UV |
| --- | --- | --- | --- |
| 范围内该访客的首个新事件保存 | 增加一行 | 加一 | 加一 |
| 同访客另一个新 eventId 保存 | 增加一行 | 加一 | 不增加 |
| 已保存事件被重复处理 | 不增加 | 不增加 | 不增加 |

上述以同短码、同查询范围、同访客摘要和密钥版本为前提。范围 UV 不能累加每日 UV，密钥版本变化也不能假设访客身份自动合并。

当前 INSERT 使用自动提交，InnoDB 单条语句本身具有事务边界，唯一约束与日志写入一起完成；不是没有事务，而是无需额外显式写事务。现有查询的短只读 REPEATABLE READ 事务继续保留。以后若增加汇总表，首次记录事件与更新汇总需要在同一数据库事务内完成，届时重新设计，不提前添加。

## 30 个统计日与幂等有效期

沿用包含当天的最近 30 个上海自然日，最早日期为 today.minusDays(29)，不是从入库开始计算满 30×24 小时的 TTL。2026-10-03 的窗口为 2026-09-04 至 2026-10-03。

每次实际持久化尝试前检查事件的原统计日，重试跨午夜重新检查。合法超窗事件返回 EXPIRED，确认丢弃；未来统计日及时间／日期不一致按非法数据拒绝。重放不修改原发生时间和统计日。

清理会删除旧日志，也会删除相应事件唯一记录。旧事件重放时先被窗口规则挡住，不会因为唯一记录已经删除而再次入库。因此当前不需要独立的长期去重表，也不提供历史补采或永久幂等。

某次写入可能在窗口检查后跨午夜才完成，留下暂时超窗的物理日志；既有清理追赶，查询始终严格限制允许窗口。不承诺任何瞬间都没有超窗物理行。

## 现有测试证据

本轮阅读源码与历史验收记录，没有重新运行测试。下表说明已有覆盖，不代表本轮新增或通过的测试。

| 已有场景 | 源码与测试方法 |
| --- | --- |
| 真实 MySQL 提交后关闭消费连接，观察 redelivery、SAVED/DUPLICATE 各一次及日志一行 | [VisitConsumerIntegrationTest](../src/test/java/com/example/shortlink/VisitConsumerIntegrationTest.java)，`committedInsertSurvivesConsumerConnectionLossBeforeAckAndRedeliveryStaysSingleRow` |
| ACK 前终止运行时，恢复消费者后判重 | [VisitCollectionLifecycleTest](../src/test/java/com/example/shortlink/VisitCollectionLifecycleTest.java)，`terminalShutdownRequeuesUnackedCommittedEventAndResumeDeduplicatesIt` |
| 真实执行 INSERT 后模拟确认丢失，同 ID 再次写入判重；已确认写入后关闭异常仍为 SAVED | [VisitStatisticsApiTest](../src/test/java/com/example/shortlink/VisitStatisticsApiTest.java)，`persistenceExposesLostConfirmationButPreservesConfirmedSaveAfterCleanupFailure` |
| 两次 GET 经真实 broker/MySQL，两个 eventId、同一访客身份 | [AsyncVisitRoundtripTest](../src/test/java/com/example/shortlink/AsyncVisitRoundtripTest.java)，`repeatedGetsUseNewEventsAndSameCookieIdentityThroughRealBrokerAndDatabase` |
| 冻结事件重试、每轮三次、永久错误拒绝、保存及重复正常返回 | [VisitConsumerTest](../src/test/java/com/example/shortlink/stats/messaging/VisitConsumerTest.java) |
| 数据库失败耗尽进 DLQ、非事件约束不能当成功、清理后重放、跨午夜重新检查窗口 | [VisitConsumerIntegrationTest](../src/test/java/com/example/shortlink/VisitConsumerIntegrationTest.java) |
| 范围 UV 去重和严格查询窗口 | [VisitStatsQueryApiTest](../src/test/java/com/example/shortlink/VisitStatsQueryApiTest.java) |

历史全量和后续维护性重构验证见[阶段 6 验收证据](../.scratch/async-visit-statistics/verification.md)，不能描述为本次重新验证。

## 已接受的新增测试计划：待实现

用户在 Q9 选择 B，确认以下两类新增测试，不仅停留在最小并发验证。生产代码原则上保留；只有后续验证发现问题时才修复。

1. **同 ID 并发首次插入**：使用真实 MySQL，让两个并发持久化调用处理同一个新 eventId；断言一个 SAVED、一个 DUPLICATE，最终一行日志。已有锁超时／准入释放场景不替代此成功竞争验证。
2. **结果未知到最终 ACK 的完整消费链路**：首次 INSERT 真实提交后模拟响应丢失，向消费者暴露 UNCERTAIN；第二次使用原事件得到 DUPLICATE；断言最终消费确认、业务队列无未确认残留、无意外 DLQ，日志及统计仅计一次。查询 PV=1、UV=1、明细一条，使用隔离短码、固定访客及受控统计日。

第二类当前已有持久层未知结果和消费者分类重试的分层覆盖，新增目标是合并完整链路证据。采用隔离的真实 MySQL/RabbitMQ、受控故障注入与有界最终等待，不以 mock 冒充真实提交或 ACK。

后续验收需记录实际测试范围、结果及故障注入边界，新增用例与相关消费、查询回归通过后才能标记测试计划完成。当前不编写或执行这些测试，不创建实施任务。

## 面试表达与完成边界

当前方案已经足以支撑实习面试中的消息幂等讨论，不需要为了面试增加 Redis 幂等层或分布式事务。应结合实际代码与已有测试说明提交与 ACK 间隙、稳定 eventId、数据库唯一键、结果未知的安全重试、AUTO 确认、每轮重试上限及 30 日保留边界。

可以概括为：消息可能重复投递，我们让同一事件保持稳定 ID，用 MySQL 唯一索引防止有效统计窗口内重复生成日志；数据库结果未知时同 ID 重试，明确保存或判重后再由容器确认。PV、UV 都从日志计算，当前不需要 Redis 双写或额外计数事务。统计允许漏记，不宣称端到端 Exactly Once。

设计确认与文档收尾已经完成；阶段 6 已有能力继续保留。两类新增测试尚待另行授权实施，不把计划写成已经具备的验收证据，也不自动开始阶段 8。
