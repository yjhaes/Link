Status: ready-for-agent
Type: task
Blocked by: 02

# 04: 消费失败有限重试并进入单一死信队列

**What to build:** 统计数据库暂时失败或消息无效时，消费者不误ACK、不无限重投；可恢复错误有限重试，最终拒绝进入单一DLQ，数据库已提交后的重投不重复增加PV。

**依赖说明：** 02 — 需要Consumer及真实持久化路径；不依赖发布恢复实现。

## Acceptance criteria

- [ ] 保存确认/仅事件重复正常返回，繁忙/失败/提交不确定传播；不照搬吞错误recoverer或AUTO提前确认。
- [ ] 按cause分类，暂时性连接/锁等待/死锁/繁忙/未知提交每轮最多3次（含首次），间隔200ms/500ms；沿用原ID/冻结数据。
- [ ] 格式/转换/未知schema/字段校验/永久约束或SQL错误直接拒绝，不重试；耗尽拒绝且不requeue，不用默认无限立即重投。
- [ ] 增加durable direct DLX和classic DLQ及精确死信key，配置业务队列死信路由；DLQ无自动回流或消费补偿。
- [ ] DLQ ready上限1000条或4MiB、TTL24小时和drop-head策略明确；不把classic死信转移称为可靠保管，不创建自动重放工具。
- [ ] 真实MySQL/RabbitMQ验证暂时失败后恢复、永久错误零重试、本轮耗尽单DLQ，以及正常保存/合法重复的ACK行为。
- [ ] 真正落库后、ACK前中断独立消费连接/进程，恢复重投后同ID仍一行；其他约束失败不被吞为重复。
- [ ] 该片控制转换/框架ErrorHandler/recoverer及业务错误日志，不泄露消息、摘要或驱动秘密；明确stateless三次不是跨重启累计上限，x-death不是普通requeue计数。

## Comments

- 2026-10-02：用户已确认8张任务的粒度、交付范围和直接阻塞关系。本轮仅发布任务，尚未认领或开始实施；父规格内容和状态保持不变。

