# 实施续接记录

2026-10-02，用户曾要求“06完成就暂停”，随后回复“继续”。现在01～08、整体验收和最终审查修正均已完成。以下保留实施过程与后续维护依据。

## 已保存状态

- 集成分支：`codex/async-visit-statistics`。
- 06 合并提交：`4a3f4c5`；实现分支最终提交：`a2910ea`。
- 固定审查基线：`8a3d7ebfcf34fd02378cf30ebb7b08bb0f222294`。
- 01～08 与父规格均已 resolved；审查修正代码合并为 `9b33501`。
- [07 积压观测和资源预算](issues/07-backlog-observability-and-budgets.md)、[08 整体验收](issues/08-async-visit-verification.md)及最终审查已完成，结果见 [verification.md](verification.md)和[code-review.md](code-review.md)。
- 实施沿用当前模型。最终 Standards Review 与 Spec Review 必须分别启动 **GPT-6.1 Sol / high** 子代理；工具参数为 `model: gpt-6.1-sol`、`reasoning_effort: high`，使用独立上下文。
- 遵循 implement-spec：每张任务在独立工作树实施、TDD、同步集成分支后由 merger 合并。本地 Markdown 任务单记录验收，不创建 PR。

## 验证依据

各任务的 Answer 保存具体结果。06 的 10 项针对性测试分两轮全部通过；恢复后完整回归两轮各259项通过，审查修正后51项针对性回归通过，均无失败、错误或跳过。

[verification.md](verification.md) 保存同步基线及新会话公平比较；本机 `.tools/async-baseline/short-link.jar` 保留原同步可执行包，最终安全测量 JSON 在 `.tools/async-verification/`。最终样本均值7.016→3.126ms，最终均330PV/1UV；独立补充样本处理延迟431.718ms，不能与查询可见等待混同。开发样本不构成生产SLA。

## 07 必须保留的边界

- `ops/visit-consumer-policies.json` 的业务策略已同时包含 DLX/key、10000/16MiB/24h/reject-publish。后续保持**同一策略**维护；另建重叠普通 policy 会覆盖 DLX。
- DLQ 为1000/4MiB/24h/drop-head，无自动回流；业务容量、TTL、DLQ淘汰及prefetch已完成真实缩小配额验收。
- 发布观察、关联资源清理和关闭有界；仅操作发布连接。5 秒是观察或待发预算，不是发送调用总截止。
- CCF `stop()` 会同步 reset；当前受控工厂覆盖生命周期 stop，并通过显式后台 owner 关闭。`destroyMethod=""` 不会单独禁用 DisposableBean；当前使用 externally-managed destroy 注册，后续不能移除。
- 消费窗口每次持久化尝试前重新检查，EXPIRED 是独立消费终局，不能与数据库 SAVED/DUPLICATE 混淆。
- 测试 profile 默认禁消费；真实异步 HTTP/MQ 测试显式启用并关闭上下文，避免不同 Clock 的缓存上下文抢消息。
- `SafeDependencyConsoleEncoder` 保留依赖日志事件、严重级别和出处，以固定类别替换原文/Throwable。全局关闭日志曾被自动审批拒绝；采用的脱敏方案随后获批。当前还保护受控工厂和 listener 的继承日志名称。
- 发布框架探究笔记：`C:/Users/86198/.codex/visualizations/2026/10/02/01a0fbf7-6034-7d20-8301-e7cb1d7270ec/mq-publisher-notes.md`。
- 累计差异检查已通过；全量回归与最终两轴审查、建议修正已记录，但不据此承诺可靠入账或固定延迟。

## 续接测试环境

- MySQL：localhost:13306，测试用户 root，测试密码 123456。
- RabbitMQ：AMQP localhost:15672；management localhost:15673；测试 guest/guest。
- Redis：localhost:16379。
- 数据库：`short_link_test`、`short_link_consumer_test`、`short_link_lifecycle_test`；基线测量使用独立 `short_link_benchmark`。
- vhost：`/`、`link-consumer-test`、`link-lifecycle-test`，guest 权限已配置。并行任务使用独立数据库及 vhost。
- 环境：`MYSQL_TEST_URL=jdbc:mysql://127.0.0.1:13306/<测试库>?serverTimezone=UTC`，`RABBIT_TEST_PORT=15672`，`RABBITMQ_PORT=15672`，`REDIS_PORT=16379`。生产/程序化应用上下文的 vhost 使用 `RABBITMQ_VIRTUAL_HOST`；测试隔离可显式传 `-Dshort-link.stats.rabbit.virtual-host=<vhost>`，并核对具体测试 fixture 的 vhost 参数。
- PowerShell 命令使用 pwsh。最终回复简体中文。

辅助实施工作树归档；当前 d401 集成工作树保留。后续基于集成分支新建任务工作树即可。

## 本轮续接结果

07/08已完成；08两轮完整259项全部通过，最终无并行测试的公平jar比较与独立处理延迟见verification.md。下一步为根代理GPT-6.1 Sol/high双轴审查和修复，父规格仍claimed。上述暂停状态为历史记录。
