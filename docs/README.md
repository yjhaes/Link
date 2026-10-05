# 项目文档导航

先完成运行演示，再根据需要学习原理或排查故障。当前行为以使用说明为准，历史验收只证明其注明的源码版本与环境；领域术语统一见 [CONTEXT.md](../CONTEXT.md)。

## 目录分类

| 目录 | 内容 |
| --- | --- |
| [入门与使用](入门与使用/) | 本机配置、IDEA 启动、业务 API、统计查询 |
| [架构与原理](架构与原理/) | 当前架构、缓存与限流、访问采集、异步统计与幂等 |
| [面试准备](面试准备/README.md) | 项目介绍、技术专题、简历素材、模拟面试与复习清单 |
| [故障排查](故障排查/) | Redis 恢复、统计排查、健康检查、日志与指标 |
| [测试与验证](测试与验证/) | 测试入口、CI、验收证据与性能观察 |
| [历史与维护](历史与维护/) | 历史设计方案、文档维护记录 |

已有 `adr/`、`agents/`、`images/` 目录保留原位置和名称。下面按分类提供阅读入口。

## 入门与使用

第一次运行只需阅读前两项，不需要先理解恢复协议、死信重放或连接池预算。

- [首页](../README.md)：技术栈、IDEA 快速启动和最短演示。
- [本机配置与 IDEA 启动](入门与使用/local-secrets.md)：首次手工准备、本地 YAML、运行配置和常见启动问题。
- [业务 API](入门与使用/api.md)：创建、跳转、管理接口及错误契约。
- [统计查询](入门与使用/visit-statistics-query.md)：日期、PV/UV 和访问明细分页。

## 面试准备

按“短码生成→Redis 缓存与限流→MQ 异步统计”阅读，不必一次读完全部材料。

- [面试阅读导航](面试准备/README.md)：按必会、进阶、扩展分级，覆盖项目介绍、数据库、缓存、MQ、限流与 Java 并发、测试及模拟追问。
- [简历素材与面试边界](面试准备/portfolio.md)：已实现的亮点及其证据。
- [缓存优化面试提纲](面试准备/cache-interview.md)：穿透、击穿、雪崩、缓存一致性及项目实现边界；先读概念对照和一分钟介绍。

## 架构与原理

- [当前架构](架构与原理/architecture.md)：目录、职责、依赖和关键时序。
- [发号与 Base62](adr/0002-permuted-auto-id-base62.md)：短码如何生成；[短码主键](adr/0001-short-code-as-primary-key.md)：如何约束唯一性。
- [Redis 缓存](adr/0003-redis-cache-aside-for-redirects.md)、[负缓存与版本协议](adr/0004-negative-cache-for-redirects.md)、[启禁用](adr/0005-enabled-state-api.md)。
- [创建限流](架构与原理/create-rate-limiting.md)、[管理限流](架构与原理/management-rate-limiting.md)：Lua 令牌桶及故障边界。
- [访问采集](架构与原理/visit-collection.md)：访问事件、匿名身份及日志；[异步统计](架构与原理/async-visit-statistics.md)、[消费者幂等](架构与原理/consumer-idempotency.md)：消息处理与防重复入账。
- [同步统计决策](adr/0006-synchronous-visit-statistics.md)、[RabbitMQ 决策](adr/0007-rabbitmq-visit-statistics.md)、[工程化收尾](adr/0008-http-rate-limiting-and-final-hardening.md)：历史取舍。同步采集边界已被异步设计替代，统计口径继续有效。

## 故障排查（按需阅读）

这些材料不是正常启动时的操作清单。先根据症状找对应入口。

| 遇到的问题 | 阅读入口 |
| --- | --- |
| 应用启动失败、账号错误、端口冲突 | [常见启动问题](入门与使用/local-secrets.md#常见启动问题) |
| 不知道哪个依赖异常 | [健康检查](故障排查/health.md)、[日志与指标](故障排查/observability.md) |
| 跳转正常但统计不增加、需要观察积压 | [统计调试与排查](故障排查/visit-statistics-operations.md) |
| 首次设置队列策略，或出现死信 | [本地辅助脚本与死信排查](../ops/README.md) |
| Redis 协调未确认，或恢复了旧数据 | [Redis 恢复](故障排查/redis-recovery.md) |

## 测试与验证

- [测试入口](测试与验证/testing.md)、[CI 与报告](测试与验证/ci.md)：重新执行回归的方式。
- [正式验证](测试与验证/verification.md)：历史证据的源码版本、结果及限制。
- [有限性能与故障观察](测试与验证/performance-and-failures.md)：历史观察条件及可解释范围。
- 展示截图：[首页](images/home.png)、[Swagger](images/swagger.png)。

## 历史与维护

- [架构优化原方案](历史与维护/architecture-optimization-plan.md)、[最终收尾原设计](历史与维护/final-hardening.md)：已实施，正文保留设计时点的事实和目标。
- [.scratch 任务与讨论](../.scratch/)：历史规格、讨论和验收。
- [文档维护记录与规则](历史与维护/document-maintenance.md)：文档职责及本轮调整。
- 代理阅读约定：[领域文档](agents/domain.md)、[任务跟踪](agents/issue-tracker.md)、[分流标签](agents/triage-labels.md)。
