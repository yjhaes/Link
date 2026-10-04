Status: claimed
Type: task
Blocked by: 04, 05, 07

# 08：请求与异步结果的安全日志和最小指标

## Parent

[阶段 8 实现规格](../spec.md)

## What to build

运维查看者能定位限流拒绝、回源压力和异步统计失败，查询有限标签指标与安全日志，而敏感访问信息不会进入普通观测输出。

## Blocked by

- [04：跳转限流与实际回源并发保护](04-redirect-rate-limiting-and-load-admission.md)
- [05：鉴权后的管理写入与查询限流](05-management-rate-limiting.md)
- [07：分组健康检查与安全管理端口](07-health-and-management-exposure.md)

## 故事覆盖

38～42，以及跨核心/统计的隐私边界。

## Acceptance criteria

- [ ] 服务端requestId、安全操作/结果类别、必要耗时输出可用；重要已提交协调未确认可按短码定位，不承诺完整分布式追踪。
- [ ] 限流获准/拒绝/依赖失败、实际回源在途/拒绝、缓存结果/失败、HTTP/池以及已有MQ终局/统计丢弃观察具有真实含义。
- [ ] 只使用模板路由、请求组和固定类别标签，不使用IP/短码/URL/访客标识/eventId/requestId/任意请求路径。
- [ ] 复用现有snapshot；broker ready/unacked继续通过受限Management/观察入口核验，累计均速、保存延迟、HTTP延迟不混淆。
- [ ] 成功跳转不逐条INFO，限流/回源高频拒绝固定分组汇总或有界采样；降级恢复、生命周期、MQ/清理安全失败仍可观察。
- [ ] 保留已有安全依赖编码，补业务/Redis/JDBC/MQ错误canary，禁止秘密/原始URL/查询串/请求体/Cookie/访客摘要/原始IP/完整Referer或UA/payload/原始依赖异常泄露。
- [ ] 通过真实请求→故障/拒绝→安全日志和指标查询完成独立验收，不把指标类存在当作功能完成。

## Comments

- 2026-10-04：用户确认 12 项拆分的粒度与依赖后发布。依据已接受的阶段 8 规格；本次仅发布任务，未认领或实施。执行时遵循任务生命周期，验收全部满足后在 Answer 中记录证据再标为 resolved。
