# 实施续接记录

2026-10-02，用户曾要求“06完成就暂停”，06完成后已保存状态。用户随后回复“继续”，现已恢复执行07及后续验收、审查。以下保留暂停时的续接依据。

## 已保存状态

- 集成分支：`codex/async-visit-statistics`。
- 06 合并提交：`4a3f4c5`；实现分支最终提交：`a2910ea`。
- 固定审查基线：`8a3d7ebfcf34fd02378cf30ebb7b08bb0f222294`。
- 01～06 已 resolved；父规格保持 claimed，因为整体实现、验收与审查尚未完成。
- 下一张可执行任务为 [07 积压观测和资源预算](issues/07-backlog-observability-and-budgets.md)。随后执行 [08 整体验收](issues/08-async-visit-verification.md)，再进行 code-review 和修复。
- 实施沿用当前模型。最终 Standards Review 与 Spec Review 必须分别启动 **GPT-6.1 Sol / high** 子代理；工具参数为 `model: gpt-6.1-sol`、`reasoning_effort: high`，使用独立上下文。
- 遵循 implement-spec：每张任务在独立工作树实施、TDD、同步集成分支后由 merger 合并。本地 Markdown 任务单记录验收，不创建 PR。

## 验证依据

各任务的 Answer 保存具体结果。06 的 10 项针对性测试分两轮全部通过：最终生命周期类 3 项，以及关闭/暂停/启动恢复/发布故障 7 项。没有在暂停前执行整体验收或最终双轴审查。

[verification.md](verification.md) 保存同步基线数据及测量方法；本机 `.tools/async-baseline/short-link.jar` 保存实施前同步可执行包，测量原始数据为 `.tools/async-baseline/measurement.json`。后续 08 应使用同等环境和 [measure-http.ps1](measure-http.ps1)，记录异步结果、丢弃和延迟，不能从当前数据宣称异步收益。

## 07 必须保留的边界

- `ops/visit-consumer-policies.json` 的业务策略目前只有 DLX/key；在**同一策略**里增加主队列容量、TTL、reject-publish。RabbitMQ 每个队列选择一条最高优先级普通 policy，另建重叠策略会覆盖 DLX。
- DLQ 已配置 1000/4MiB/24h/drop-head，无自动回流；业务队列 10000/16MiB/24h/reject-publish 及真实容量/TTL/prefetch验收待 07。
- 发布观察、关联资源清理和关闭有界；仅操作发布连接。5 秒是观察或待发预算，不是发送调用总截止。
- CCF `stop()` 会同步 reset；当前受控工厂覆盖生命周期 stop，并通过显式后台 owner 关闭。`destroyMethod=""` 不会单独禁用 DisposableBean；当前使用 externally-managed destroy 注册，后续不能移除。
- 消费窗口每次持久化尝试前重新检查，EXPIRED 是独立消费终局，不能与数据库 SAVED/DUPLICATE 混淆。
- 测试 profile 默认禁消费；真实异步 HTTP/MQ 测试显式启用并关闭上下文，避免不同 Clock 的缓存上下文抢消息。
- `SafeDependencyConsoleEncoder` 保留依赖日志事件、严重级别和出处，以固定类别替换原文/Throwable。全局关闭日志曾被自动审批拒绝；采用的脱敏方案随后获批。当前还保护受控工厂和 listener 的继承日志名称。
- 发布框架探究笔记：`C:/Users/86198/.codex/visualizations/2026/10/02/01a0fbf7-6034-7d20-8301-e7cb1d7270ec/mq-publisher-notes.md`。
- 当前累计差异检查仍有新增 EOF 空行，续接时整理；最终全量回归和审查尚未执行，不能宣传整体规格已验收。

## 续接测试环境

本轮创建的测试容器在暂停时停止，保留数据；续接可用 Docker 启动：`link-async-test-mysql`、`link-async-test-rabbit`、`link-async-test-redis`。

- MySQL：localhost:13306，测试用户 root，测试密码 123456。
- RabbitMQ：AMQP localhost:15672；management localhost:15673；测试 guest/guest。
- Redis：localhost:16379。
- 数据库：`short_link_test`、`short_link_consumer_test`、`short_link_lifecycle_test`；基线测量使用独立 `short_link_benchmark`。
- vhost：`/`、`link-consumer-test`、`link-lifecycle-test`，guest 权限已配置。并行任务使用独立数据库及 vhost。
- 环境：`MYSQL_TEST_URL=jdbc:mysql://127.0.0.1:13306/<测试库>?serverTimezone=UTC`，`RABBIT_TEST_PORT=15672`，`REDIS_PORT=16379`，需要隔离时设置 `SHORT_LINK_STATS_RABBIT_VIRTUAL_HOST`。
- Maven：`C:/apache-maven-3.9.16/bin/mvn.cmd`，显式参数 `-Dmaven.repo.local=C:/Users/86198/.m2/repository`。默认缓存路径错误指向 C:/.m2。Git 元数据、Maven 缓存和 Docker 管道需要正常 sandbox escalation；此前均已按授权测试范围执行。
- PowerShell 命令使用 pwsh。最终回复简体中文。

辅助实施工作树归档；当前 d401 集成工作树保留。后续基于集成分支新建任务工作树即可。
