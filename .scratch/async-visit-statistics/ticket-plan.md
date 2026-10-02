# 阶段 6：已确认任务拆分

用户于2026-10-02确认8张任务的粒度、交付范围和直接阻塞关系。任务已分别发布为独立本地Markdown issue，初始状态均为ready-for-agent；本轮仅发布任务，尚未认领或开始实施。父[完整规格](spec.md)的内容及状态保持不变。

## 拆分原则

- 01先完成窄范围持久化结果反馈，保持同步HTTP回归，为消费ACK提供明确依据；没有广泛机械迁移。
- 02贯通GET、事件交接、RabbitMQ、Consumer、MySQL及查询，不把依赖、DTO或队列声明拆成孤立层任务。
- 03和04分别交付发布及消费故障路径；各任务包含自身实现、配置、测试和必要说明，08仅负责整体证据，不接管前面应有的测试。
- 只列直接阻塞边，省略传递依赖；每张任务可独立验证，并应适合一个新的上下文窗口。
- 保持已接受规格的最小部署、跳转优先、best-effort及资源预算，不自动扩展到outbox、集群、自动重放或其他可选优化。

## 已发布任务

| 编号 | 任务 | Blocked by | 初始Status |
| --- | --- | --- | --- |
| 01 | [明确统计持久化结果，保持跳转降级](issues/01-visit-persistence-outcomes.md) | None | ready-for-agent |
| 02 | [正常GET通过RabbitMQ异步形成访问日志](issues/02-rabbitmq-visit-roundtrip.md) | 01 | ready-for-agent |
| 03 | [发布故障和背压不阻塞短链跳转](issues/03-publish-failure-and-backpressure.md) | 02 | ready-for-agent |
| 04 | [消费失败有限重试并进入单一死信队列](issues/04-consumer-retry-and-dead-letter.md) | 02 | ready-for-agent |
| 05 | [迟到事件遵守冻结时间和30日窗口](issues/05-late-visits-and-retention-window.md) | 04 | ready-for-agent |
| 06 | [分离停采与暂停消费，并有界启停](issues/06-collection-and-consumer-lifecycle.md) | 03, 04 | ready-for-agent |
| 07 | [积压可观测且队列容量与TTL生效](issues/07-backlog-observability-and-budgets.md) | 06 | ready-for-agent |
| 08 | [验证异步收益并完成阶段验收](issues/08-async-visit-verification.md) | 05, 07 | ready-for-agent |

每张任务的完整交付行为和验收条件在各自文件中维护；本文是索引，不是合并任务单。当前表格记录发布时状态，实施时以各任务的Status行为准。

## 可执行前沿

- 当前仅01无阻塞；按项目编号优先规则，下一张可认领任务是01。这表示执行资格，不表示本次已开始执行。
- 01完成后02可开始；02完成后03与04均可开始，二者不互相阻塞。
- 04完成后05可开始，不必等待03；03与04都完成后06可开始。
- 06完成后07可开始，不依赖05；05与07都完成后08可开始。
- 所有阻塞边指向已存在前置任务，无循环或重复传递边；认领及验收按本地生命周期更新。

## 范围与覆盖

- 01持久化反馈；02正常异步闭环/协议/身份；03发布故障/资源；04ACK/幂等/重试/DLQ；05时间/保留/查询；06开关/启停/人工维护；07积压/容量/TTL/观测；08整体证据和说明。
- 每片保留相关核心和统计回归，使用HTTP行为、真实RabbitMQ和MySQL作为主要验收边界，不测试私有实现。
- 未新增页面、数据库表、统计微服务、公开运维接口或复杂可靠性扩展；发布任务不等于执行任务，本轮未改Java、Maven、配置、SQL或测试。

## Comments

- 2026-10-02：用户调用to-tickets；已提出8张任务的可审阅草案及直接依赖。
- 2026-10-02：用户回复“确认”，已按批准内容发布8张独立任务，状态ready-for-agent；父规格内容及状态不变，未认领、运行实现测试或开始编码。
