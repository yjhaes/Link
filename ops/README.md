# 本地开发辅助脚本

本目录用于本地配置和 RabbitMQ 调试。应用启动见 [本地配置](../docs/local-secrets.md)，停采与消费启停见 [统计排查](../docs/visit-statistics-operations.md)。

## 脚本用途

| 脚本 | 用途 | 是否修改状态 |
| --- | --- | --- |
| `init-local-secrets.ps1` / `.sh` | 生成本地配置，已有文件保留 | 写入配置文件，不创建设施账号 |
| `test-local-secrets.ps1` | 检查秘密初始化行为 | 运行配置检查 |
| `get-visit-broker-observation.ps1` | 查看队列积压、消费者和资源告警 | 只读 |
| `set-visit-consumer-policies.ps1` | 设置业务队列和 DLQ 的容量、TTL 与死信策略 | 修改两条精确匹配的 policy，不删队列或消息 |

测试入口见 [测试说明](../docs/testing.md)。

## 查看 RabbitMQ 队列

在仓库根目录的 PowerShell 7 中执行，URL 和 vhost 替换为自己的本地配置：

```powershell
$credential = Get-Credential
./ops/get-visit-broker-observation.ps1 -ManagementUrl 'http://localhost:15672' -VirtualHost 'short_link' -Credential $credential
```

输出项如何判断，见 [统计排查](../docs/visit-statistics-operations.md)。脚本不打印凭据或管理接口的原始错误正文。

## 设置队列策略

已有 RabbitMQ 账号、vhost 和所需管理权限后执行：

```powershell
$credential = Get-Credential
./ops/set-visit-consumer-policies.ps1 -ManagementUrl 'http://localhost:15672' -VirtualHost 'short_link' -Credential $credential
```

业务队列最多保存 10000 条待处理消息或 16 MiB 消息体，驻留 TTL 为 24 小时；满队列拒绝新发布。DLQ 最多保存 1000 条或 4 MiB，TTL 为 24 小时，满时移除最早消息，没有自动回流。

这些上限不包含未确认消息和全部存储开销。策略优先级为 20；业务队列的容量、TTL 和死信配置必须在同一条策略中，避免另一条重叠策略覆盖死信设置。队列声明冲突时先检查类型和属性，不通过删除积压解决。

## 排查死信

先查看少量消息，判断是格式错误、数据库错误、重试耗尽还是过期。不要把消息体、身份摘要或秘密复制到日志和任务记录。

项目没有重放工具。确需人工重放时，使用 RabbitMQ 发布工具，保留原 JSON、schemaVersion、eventId、occurredAt、statDate 及其余字段，发送到 `shortlink.visit.x`，routing key 为 `visit.occurred.v1`，使用持久消息。

确认新路由已被 broker 接受后再移除原 DLQ 消息；确认未知时不能认定已完成。最终检查数据库统计，而不是只看发布确认。同 ID 已记录事件不会重复入账；超过 30 个统计日窗口的合法事件会被丢弃，未来日期会被拒绝。不能更换 ID 或时间绕过校验。

详细原理见 [异步设计](../docs/async-visit-statistics.md)、[消费者幂等](../docs/consumer-idempotency.md)；日志和指标说明见 [观测说明](../docs/observability.md)。
