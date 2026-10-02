Status: claimed
Type: task
Blocked by: 04

# 05: 管理者安全分页查看访问明细

**What to build:** 内部管理者在与聚合相同的令牌、日期窗口及查询资源规则下，分页查看访问发生时间、脱敏对端网段、限长 UA 和来源 host。游标不泄露访客摘要，不被当作权限或固定快照凭证。

**依赖说明：** 04 — 管理者查询日期范围 PV/UV 与日趋势

## Acceptance criteria

- [ ] 交付 GET /api/internal/links/{code}/visits，复用04的鉴权、日期窗口、查询容量、超时和错误规则；GET/HEAD与全部错误响应均受保护并no-store。
- [ ] 响应表达短码及最终范围、items、nextCursor、hasMore；只展示 occurredAt、peerIpNetwork、userAgent、refererHost，无 Cookie、visitor_hash、密钥、完整 IP/Referer 或目标URL副本。
- [ ] limit 默认20、范围1至100，非整数、重复及超限400；采用 occurred_at/id 倒序与 limit+1，不用大OFFSET，不返回总页数。
- [ ] 游标包含末尾排序位置并绑定短码、日期范围和格式版本，严格校验结构；续页范围一致，不能因游标存在绕过管理令牌。
- [ ] 相同毫秒日志稳定排序，原游标行删除后按位置继续；新写/迟到/清理会改变跨页可见性，不提供固定快照导出保证。
- [ ] 窗口移动使原范围超窗时400，非法或跨短码/范围游标400，不维持已过期历史访问。
- [ ] 用HTTP与真实MySQL验证大小写、同时间多行、页大小、hasMore、缺失元数据、游标原行删除及并发新写/清理，核对响应最小化。
- [ ] 聚合、普通跳转与现有状态行为保持，不增加页面、导出或公开诊断入口。

## Comments

- 2026-10-01：用户已确认六张任务的粒度、交付范围和阻塞关系。本轮仅发布任务，尚未认领或实施。
