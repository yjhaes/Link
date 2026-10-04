Status: resolved
Type: task
Blocked by: 01 — 永久短链接通过 Redis 加速跳转

## What to build

维护者沿用现有数据库维护方式禁用或重新启用映射，并在 MySQL 变更提交后清除对应的 Redis 缓存。随后访问者分别得到 `403` 或恢复 `302`，不必等待旧缓存自然过期。

## Acceptance criteria

- [x] 维护流程明确先提交 MySQL 状态变更，再删除对应的 Redis Key；未增加公开的禁用、重新启用或删除接口。
- [x] 已预热的永久链接在禁用并清除缓存后返回 `403 LINK_DISABLED`，且禁用结果不回填；重新启用并清除缓存后原短码恢复 `302`，无需新建映射。
- [x] 维护说明明确 Redis 删除失败不会撤销已提交的 MySQL 状态变更，应报告失败；旧缓存及并发旧值回填可能存活到各自最多 5 分钟的 TTL 到期，不能把该流程描述为强一致。
- [x] 说明将来若增加应用内启用、禁用、删除或修改操作，应在数据库提交后删除对应 Key；本任务不增加这些未来接口。

## Answer

- `README.md` 记录了 MySQL 提交后删除 Redis Key 的禁用／重新启用维护步骤、删除失败的报告与重试行为、TTL 与并发回填的一致性边界，以及未来应用内变更的失效要求；未新增状态操作接口。
- `RedisRedirectIntegrationTest` 新增真实 MySQL 与 Redis 的流程测试，覆盖预热、已提交的禁用状态、显式删 Key、403 且不回填，以及重新启用同一短码后的 302。每个测试前清理 Redis。
- 验证：定向集成测试 8 项通过；完整 Maven 测试集 55 项通过，失败和错误均为 0。
- Standards Review 与 Spec Review 均无发现；两个审查子代理使用 GPT-6 Sol、reasoning effort high。
