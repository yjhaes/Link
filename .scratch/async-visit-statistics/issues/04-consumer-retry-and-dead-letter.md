Status: resolved
Type: task
Blocked by: 02

# 04: 消费失败有限重试并进入单一死信队列

**What to build:** 统计数据库暂时失败或消息无效时，消费者不误ACK、不无限重投；可恢复错误有限重试，最终拒绝进入单一DLQ，数据库已提交后的重投不重复增加PV。

**依赖说明：** 02 — 需要Consumer及真实持久化路径；不依赖发布恢复实现。

## Acceptance criteria

- [x] 保存确认/仅事件重复正常返回，繁忙/失败/提交不确定传播；不照搬吞错误recoverer或AUTO提前确认。
- [x] 按cause分类，暂时性连接/锁等待/死锁/繁忙/未知提交每轮最多3次（含首次），间隔200ms/500ms；沿用原ID/冻结数据。
- [x] 格式/转换/未知schema/字段校验/永久约束或SQL错误直接拒绝，不重试；耗尽拒绝且不requeue，不用默认无限立即重投。
- [x] 增加durable direct DLX和classic DLQ及精确死信key，配置业务队列死信路由；DLQ无自动回流或消费补偿。
- [x] DLQ ready上限1000条或4MiB、TTL24小时和drop-head策略明确；不把classic死信转移称为可靠保管，不创建自动重放工具。
- [x] 真实MySQL/RabbitMQ验证暂时失败后恢复、永久错误零重试、本轮耗尽单DLQ，以及正常保存/合法重复的ACK行为。
- [x] 真正落库后、ACK前中断独立消费连接/进程，恢复重投后同ID仍一行；其他约束失败不被吞为重复。
- [x] 该片控制转换/框架ErrorHandler/recoverer及业务错误日志，不泄露消息、摘要或驱动秘密；明确stateless三次不是跨重启累计上限，x-death不是普通requeue计数。

## Comments

- 2026-10-02：用户已确认8张任务的粒度、交付范围和直接阻塞关系。本轮仅发布任务，尚未认领或开始实施；父规格内容和状态保持不变。

## Answer

- 实现独立同步 `VisitConsumer`：解码一次、保留冻结事件，沿受控 cause 分类，每轮最多首次加两次重试，等待 200 ms/500 ms；仅 SAVED / DUPLICATE 正常返回，未知/null 结果、永久错误和耗尽均抛无敏感 cause 的 AmqpRejectAndDontRequeueException。AUTO、batch size 1、禁止默认 requeue 保持明确。
- MySQL 持久化根据 SQL cause 区分连接/锁等待/死锁、未知提交和已明确拒绝的永久 SQL/约束错误，只有 uq_visit_event 的 1062 返回 DUPLICATE；CHECK 和其他唯一键错误不误吞。
- 声明 durable direct shortlink.visit.dlx、durable classic shortlink.visit.stats.dlq 与精确 visit.failed.v1 绑定。`ops/visit-consumer-policies.json` 与受控 PowerShell 脚本设置主队列 DLX/key、DLQ 1000 ready / 4 MiB / 24 h / drop-head；没有 DLQ 消费或回流。可变设置通过显式运维 policy 配置，不删除已有队列/积压。
- `SafeDependencyConsoleEncoder` 保留每条 JDBC/Hikari/AMQP/native Rabbit 日志事件、级别、logger、线程和时间，替换驱动/框架消息及 Throwable 为固定 database/mq 类别。范围包含核心 Hikari/Spring JDBC；没有关闭日志级别。原拟全局 OFF 方案被自动审批拒绝，已用保留观测事件的安全编码替代并通过审批/验证。
- TDD：首次消费者边界测试在 VisitConsumer 尚不存在时编译失败；cause 包装重试测试先因直接拒绝失败，再补分类实现。真实测试使用独立 MySQL short_link_consumer_test、RabbitMQ vhost link-consumer-test，不停止共享 broker 或清理其他库/vhost。
- 验证：`mvn -Dtest=SafeDependencyConsoleEncoderTest,VisitConsumerTest,VisitConsumerIntegrationTest,VisitStatisticsApiTest#exhaustedStatisticsPoolDoesNotTakeOverCoreCreationIssuanceOrStateTransactions test`，14 项通过，0 failure/error。7 项真实 MQ/MySQL 验证锁故障后恢复、耗尽恰好 3 次并单 DLQ、坏 payload/schema 零 DB 尝试、CHECK/其他唯一键一次直接拒绝、同 event 重复 ACK，以及真实 INSERT 提交后 AUTO ACK 前由管理 API 强制中断独立消费连接、重新投递仍单行。捕获日志金丝雀证明 WARN/ERROR 仍出现而 payload/hash/password/JDBC cause/native Rabbit cause 不出现；真实故障输出也仅安全类别。
- 边界：三次是本轮上限，重启/未 ACK 重投可以开始新轮；x-death 不计普通 requeue。classic 死信和 TTL/容量淘汰可丢失，DLQ 只是诊断样本。后续 05 在每次真实 persist 前加窗口校验；06 必须扩展同一主队列 policy，避免较高优先级 policy 覆盖 DLX 配置；07 审核运维文档。

- 合并集成 tip e813f06 后于本分支 716f1eb 重跑同一 14 项验证，全部通过；发布连接的外部销毁归属、有界框架执行器、原生安全异常处理与恢复循环完整保留。
