Status: resolved
Type: task
Blocked by: 02 — 支持有效时长与过期判断

## What to build

维护者可通过数据库操作停止或恢复一条短链接映射；访问者得到与过期不同的结果，且重新启用不会绕过已到期的期限。

## Acceptance criteria

- [x] 未过期的映射在数据库中被禁用后，访问返回 `403 LINK_DISABLED` 的统一 JSON 错误和 `Cache-Control: no-store`。
- [x] 数据库中重新启用未过期映射后，原短码恢复 `302` 跳转；不创建新的映射或短码。
- [x] 同时已过期且被禁用时优先返回 `410 LINK_EXPIRED`；重新启用已过期映射后仍返回 `410`。
- [x] 过期或禁用的映射保留，短码不复用；本任务不增加禁用、重新启用或详情 API。
- [x] 通过 HTTP 和真实 MySQL 状态变更验证上述行为；受影响测试通过。

## Answer

- 跳转时先判断过期，再判断启用状态；已禁用且未过期的映射返回统一 JSON `403 LINK_DISABLED` 与 `Cache-Control: no-store`。
- 维护者直接通过 MySQL 更新 `enabled`，同一映射重新启用后恢复 `302`；过期映射即使重新启用仍返回 `410 LINK_EXPIRED`。
- 补充 HTTP 与真实 MySQL 集成测试，覆盖禁用、重新启用、记录保留及过期优先行为；Maven 全套验证通过，12 项测试成功。
- 提交：`7a14166`、`621380a`。
