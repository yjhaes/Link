Status: ready-for-agent

# 阶段 3：Redis 跳转缓存

## Problem Statement

当前每次访问短链接都按短码查询 MySQL。访问者会重复访问同一映射，而映射的原始 URL 和过期时间在创建后不修改；希望在不改变现有跳转和错误语义的前提下减少重复数据库读取。

## Solution

以已接受的 [阶段 3 Redis ADR](../../docs/adr/0003-redis-cache-aside-for-redirects.md) 为准。跳转采用 Cache Aside：按需缓存仍可跳转的映射；Redis 未命中或故障时查 MySQL；应用每次命中仍判断业务过期时间。缓存 TTL 最多 5 分钟，限时链接不超过剩余有效期。MySQL 是权威数据源。

## Delivery

1. [01 — 永久短链接通过 Redis 加速跳转](issues/01-permanent-link-redis-redirect.md)：打通首次 miss、回填、后续 hit 和 Redis 故障降级。
2. [02 — 限时链接保持精确过期语义](issues/02-expiring-link-redis-redirect.md)：扩展到限时映射并保留到期边界与错误响应。
3. [03 — 维护者禁用或重新启用时清除缓存](issues/03-maintenance-cache-invalidation.md)：现有直接改库维护流程在数据库提交后显式删缓存。

02 和 03 都只依赖 01，可分别执行。本阶段不增加公开维护接口；未来应用内状态变更也应在数据库提交后删除对应缓存。

## Verification

服务层测试须证明 hit 不查 MySQL、miss 查 MySQL 并仅回填有效映射、Redis 故障时降级。少量集成测试用 Docker/Testcontainers 中的真实 MySQL 和 Redis 验证 Key、Value、TTL、失效与 HTTP 响应。测试应区分缓存命中与仅仅两次都返回相同的 `302`。

## Comments

- 根据用户逐轮确认的 ADR 与三张纵向任务拆分发布；目前只发布任务，不开始实现。
