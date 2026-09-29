Status: resolved
Type: task
Blocked by: None (can start immediately)

# 01: 永久短链接通过 Redis 加速跳转

## What to build

访问者首次打开已启用的永久短链接时，系统从 MySQL 取得原始 URL 并回填 Redis；之后重复访问可直接命中 Redis，仍获得相同的 `302` 跳转。Redis 故障只影响加速，不阻断 MySQL 正常时的跳转。

## Acceptance criteria

- [x] 创建永久映射后不预热缓存；首次访问按已接受的 ADR 校验短码、查 MySQL、确认映射可跳转，再写入区分短码大小写的 Key，Value 仅含原始 URL 与空的过期时间，使用单次写入设置可配置的 5 分钟 TTL。
- [x] 重复访问命中 Redis 时不再查询 MySQL；`302`、`Location` 和 `Cache-Control: no-store` 与现有行为相同，大小写不同的短码不会串用缓存。
- [x] Redis 读取、写入、超时或缓存内容无法解析时，仍按 MySQL 的结果响应；缓存操作有短且有界的超时，Redis 不可用时应用仍可提供跳转。
- [x] 格式错误、不存在或已禁用的映射保持原有错误响应且不回填缓存；限时映射在本任务中沿用现有 MySQL 路径。
- [x] 服务层测试能证明 hit 避免数据库读取、miss 回填及 Redis 故障降级；真实 MySQL 和 Redis 的 Testcontainers 测试核对缓存值、TTL、重复跳转与测试数据隔离。

## Answer

- 已提交 `94b1424`：永久短链接按需写入 Redis，缓存命中跳过 MySQL；Redis 故障或缓存损坏时回退至 MySQL。跳转状态码、响应头和现有错误语义保持不变。
- 验证：完整 Maven 测试集 45 项通过，包含 4 项真实 MySQL/Redis Testcontainers 测试、26 项 HTTP/MySQL 测试和 12 项服务层测试。
- Standards Review 与 Spec Review 均未发现问题；两个审查子代理使用 GPT-6 Sol、high。

## Comments
