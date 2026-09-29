Status: ready-for-agent
Type: task
Blocked by: 01 — 永久短链接通过 Redis 加速跳转

# 02: 限时链接保持精确过期语义

## What to build

访问者重复打开未过期、已启用的限时短链接时可以命中 Redis；在业务过期时刻及之后仍得到现有的 `410`，不会因缓存尚未清理而继续跳转。

## Acceptance criteria

- [ ] 仅对未过期且已启用的限时映射回填 Redis；Value 包含原始 URL 和过期时刻，TTL 取可配置的 5 分钟上限与剩余有效期中较短者，无法得到正的毫秒 TTL 时不写缓存。
- [ ] 缓存命中时由应用再次检查过期时刻：到期前保持 `302`，到期当刻及之后返回 `410 LINK_EXPIRED`，并尽力删除已过期的 Key；业务有效性不只依赖 Redis TTL。
- [ ] 不存在、已过期、已禁用和格式错误的短码不产生缓存条目；同时已过期且已禁用时仍优先返回 `410`，响应保持原有的禁止 HTTP 缓存语义。
- [ ] 可控时钟测试覆盖到期前、到期当刻与之后；真实 MySQL 和 Redis 的 Testcontainers 测试核对实际 TTL、序列化和重复请求的数据库读取行为。

## Comments
