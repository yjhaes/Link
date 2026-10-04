# 本地健康检查

应用默认使用两个端口：8080 提供页面和业务 API，127.0.0.1:8081 提供只读健康、信息和指标。管理端口不使用业务 `X-Internal-Token` 鉴权，只供本机访问。

## 先看三个地址

| 地址 | 回答的问题 |
| --- | --- |
| `http://localhost:8080/livez` | 应用自身是否处于存活状态？不检查外部设施 |
| `http://localhost:8080/readyz` | 应用是否就绪，核心 MySQL 是否可用？ |
| `http://localhost:8081/actuator/health/dependencies` | Redis、MQ、统计连接池及采集/消费状态如何？ |

`/livez` 和 `/readyz` 只返回状态。查看具体依赖问题用第三个地址。

## 如何理解结果

| 情况 | 核心就绪状态 | 实际影响 |
| --- | --- | --- |
| Redis 不可用 | 可能仍 UP | 创建和管理可能返回 503；跳转降级查 MySQL，并受回源并发限制 |
| MQ 不可用 | 可能仍 UP | 正常跳转继续，统计可能漏记 |
| 核心 MySQL 不可用 | readiness 返回 503 | 核心业务无法保证可用；liveness 仍可 UP |
| 统计连接池不可用 | 核心组不受影响 | 统计处理或查询异常，依赖组返回 503 |
| 应用尚未就绪或正在关闭 | readiness 不可用 | 暂不接受正常流量 |

**UP 不等于所有功能都正常，也不等于 PV 已入账。** 探针只报告状态，不修复数据、不重启应用、不改变消费开关。

采集与消费详情同时显示配置意图和实际状态。例如消费者配置开启，但被手动停止时，会显示 enabled/stopped；检查健康不会把它重新启动。

MQ 状态只观察已有连接，没有确认队列策略、路由或消息是否落库。连接变化可能延迟显示；队列积压另用 [观察脚本](../ops/README.md)。

## 其他只读入口

- `/actuator/health`：所有组的汇总状态，可因非核心依赖故障返回 503。
- `/actuator/info`：基本构建信息。
- `/actuator/metrics`：指标列表；具体含义见 [观测说明](observability.md)。

管理端口也提供 `/actuator/health/liveness` 和 `/actuator/health/readiness`，与业务端口的两个探针对应。业务端口不提供 Actuator 接口。

## 实现与验证参考

健康错误只显示固定类别，不返回异常正文、SQL、连接地址或秘密。env、configprops、heapdump、loggers、shutdown、mappings 等端点不开放，JMX 暴露也关闭。

核心池与统计池分别观察；默认数据库、Redis、Rabbit 等聚合指示器关闭，避免统计故障混入核心就绪判定。HTTP 指标使用路由模板或固定类别，不用短码、IP 或原始 URL 作为标签。

`HealthManagementHttpTest` 验证双端口、统计池故障、错误信息保护、消费意图及关闭状态。此页说明既有行为，本轮文档调整没有重新运行这些测试。
