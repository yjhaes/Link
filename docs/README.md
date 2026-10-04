# 项目文档导航

当前运行方式和实现事实以使用说明为准；设计文档解释取舍，验收记录只证明其注明的源码版本与环境。领域术语统一见 [CONTEXT.md](../CONTEXT.md)。

## 运行与使用

- [本地配置与启动](local-secrets.md)：秘密初始化、设施账号、环境变量与直接运行。

- [业务 API](api.md)：创建、跳转、管理及错误契约；[统计查询](visit-statistics-query.md)：日期、PV/UV及分页。
- [访问采集](visit-collection.md)：身份、隐私、统计池与日志清理。
- [统计调试与排查](visit-statistics-operations.md)：停采、暂停消费、关停、资源预算和积压观察；[本地辅助脚本与死信排查](../ops/README.md)：已有 broker 维护与人工重放。
- [Redis 恢复](redis-recovery.md)、[创建限流](create-rate-limiting.md)、[管理限流](management-rate-limiting.md)、[健康检查](health.md)、[日志与指标](observability.md)。

## 架构与设计

- [当前架构](architecture.md)：实际目录、职责、资源所有权与关键时序。
- [异步统计设计](async-visit-statistics.md)、[消费者幂等设计](consumer-idempotency.md)：设计理由、业务边界及验收场景；操作步骤引用运行说明。
- 架构决策：[短码主键](adr/0001-short-code-as-primary-key.md)、[发号与 Base62](adr/0002-permuted-auto-id-base62.md)、[Redis 缓存](adr/0003-redis-cache-aside-for-redirects.md)、[负缓存](adr/0004-negative-cache-for-redirects.md)、[启禁用](adr/0005-enabled-state-api.md)、[同步统计](adr/0006-synchronous-visit-statistics.md)、[RabbitMQ](adr/0007-rabbitmq-visit-statistics.md)、[最终工程化](adr/0008-http-rate-limiting-and-final-hardening.md)。同步采集边界已由异步设计替代，仍有效的统计口径保留。

## 测试与证据

- [正式验证入口](verification.md)：各项证据的源码版本、结果和限制。
- [分层测试](testing.md)、[CI](ci.md)：重新执行方式与报告规则。
- [有限性能与故障观察](performance-and-failures.md)、[简历素材](portfolio.md)。
- 展示截图：[首页](images/home.png)、[Swagger](images/swagger.png)。

## 历史材料

- [架构优化原方案](architecture-optimization-plan.md)、[最终收尾原设计](final-hardening.md)：已实施，正文保留其余设计时点的事实和目标。
- [.scratch 任务与讨论](../.scratch/)：历史规格、任务、讨论和验收，正式文档仍引用其中部分记录。
- [文档维护记录与规则](document-maintenance.md)：文档职责、清理候选及维护检查。
- 代理阅读约定：[领域文档](agents/domain.md)、[任务跟踪](agents/issue-tracker.md)、[分流标签](agents/triage-labels.md)。
