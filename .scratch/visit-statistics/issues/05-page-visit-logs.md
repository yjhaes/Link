Status: resolved
Type: task
Blocked by: 04

# 05: 管理者安全分页查看访问明细

**What to build:** 内部管理者在与聚合相同的令牌、日期窗口及查询资源规则下，分页查看访问发生时间、脱敏对端网段、限长 UA 和来源 host。游标不泄露访客摘要，不被当作权限或固定快照凭证。

**依赖说明：** 04 — 管理者查询日期范围 PV/UV 与日趋势

## Acceptance criteria

- [x] 交付 GET /api/internal/links/{code}/visits，复用04的鉴权、日期窗口、查询容量、超时和错误规则；GET/HEAD与全部错误响应均受保护并no-store。
- [x] 响应表达短码及最终范围、items、nextCursor、hasMore；只展示 occurredAt、peerIpNetwork、userAgent、refererHost，无 Cookie、visitor_hash、密钥、完整 IP/Referer 或目标URL副本。
- [x] limit 默认20、范围1至100，非整数、重复及超限400；采用 occurred_at/id 倒序与 limit+1，不用大OFFSET，不返回总页数。
- [x] 游标包含末尾排序位置并绑定短码、日期范围和格式版本，严格校验结构；续页范围一致，不能因游标存在绕过管理令牌。
- [x] 相同毫秒日志稳定排序，原游标行删除后按位置继续；新写/迟到/清理会改变跨页可见性，不提供固定快照导出保证。
- [x] 窗口移动使原范围超窗时400，非法或跨短码/范围游标400，不维持已过期历史访问。
- [x] 用HTTP与真实MySQL验证大小写、同时间多行、页大小、hasMore、缺失元数据、游标原行删除及并发新写/清理，核对响应最小化。
- [x] 聚合、普通跳转与现有状态行为保持，不增加页面、导出或公开诊断入口。

## Comments

- 2026-10-01：用户已确认六张任务的粒度、交付范围和阻塞关系。本轮仅发布任务，尚未认领或实施。

- 2026-10-02：依赖 04 已 resolved；本轮认领并实现，实施使用当前模型，Standards/Spec 两项独立审查明确使用 GPT-6.1 Sol / high。

## Answer

- 交付 GET /api/internal/links/{code}/visits 及隐式 HEAD，先令牌鉴权、所有响应 no-store；复用上海日期范围、统计池、共享容量 1 和繁忙/超时/数据库错误规则。
- 明细仅返回 UTC occurredAt、脱敏网段、已清理限长 UA 及来源 host；缺失元数据为 null。没有访客摘要、Cookie、密钥、完整 IP/Referer 或目标 URL。
- 默认 20、最大 100，严格校验参数；数据库按 occurred_at/id 倒序取 limit+1，无 OFFSET 或总页数。真实 MySQL EXPLAIN 使用 idx_visit_page 倒序扫描，无 filesort。
- 规范版本 1 游标绑定短码、最终日期范围和末尾排序位置，严格结构校验；与鉴权分离，原游标行删除后仍按位置继续。窗口移动超窗返回 400。
- 续页应传首页返回的 from/to；跨页不提供固定快照，新写、迟到及清理可改变后续数据。API 编解码与数据库位置分离，见 [查询说明](../../../docs/入门与使用/visit-statistics-query.md)。
- 首个 HTTP/真实 MySQL 测试先因未实现接口返回 404 失败，实现后通过。验证大小写、同毫秒排序、页大小、hasMore、空元数据、最小化、游标删除、并发新写/清理及超窗，真实 SQL 超时验证覆盖两类查询。
- 最终完整 Maven 回归 206 项通过，0 失败、0 错误、0 跳过；含真实 MySQL 8.4、Redis 与原有状态/跳转回归。日志 target/task05-final-full.log 为本地生成，不提交。
- 两项 GPT-6.1 Sol / high 审查：Standards 首次 1 项可选建议已修复，Spec 首次 0 项；最终两轴剩余问题均为 0，见 [审查记录（Git 历史）](https://github.com/yjhaes/Link/blob/49d0e53ce0314a4e628bf2491d140433e218db7a/.scratch/visit-statistics/review-05.md)。本轮未增加任务 06 调度清理。
