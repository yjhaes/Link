# 任务 04 实现与审查

用户明确要求实现阶段使用当前模型；Standards Review 和 Spec Review 两个独立子代理使用 GPT-6.1 Sol，reasoning effort high。两个子代理首次审查和复审均保持此设置。

固定基线 `5e3002bc43e442693aec76203183502914a857bb`，首次实现提交 `ce38f4f`，审查修正提交 `f853129`。
最终审查命令 `git diff 5e3002bc43e442693aec76203183502914a857bb...f853129`。
规格为任务 04、本功能 spec 及 ADR-0006；标准来源包含 AGENTS、领域文档规则和 docs/architecture.md。附带规格中的旧“仅发布规格”说明为历史阶段背景，本次执行授权来自用户的 implement 请求。

## Standards

首次两项 P2 文档规则偏差：

- HTTP 日期参数解析和响应类型进入 stats 包，违反 docs/architecture.md 的职责划分。
- 查询没有内部超时计数，遗漏 ADR-0006 的查询超时观测。

两项均修复：HTTP 解析及 DTO 移入 api，stats 保留日期窗口与查询结果；VisitQueryObservations 提供不带请求/身份标签的进程内计数，真实语句和 socket 超时测试核验计数及恢复。

复审结论：未发现新增文档规则违反或值得单独修改的判断性异味，剩余发现 0 项。

## Spec

首次一项 P2：Connector/J 将明确 socket 超时包装为 SQLState 08S01 的 CommunicationsException，原分类未检查原因链，可能误报 500；不符合 spec 明确查询超时返回 503 STATS_QUERY_TIMEOUT 的规则。

已修复：检查明确 SocketTimeoutException/SQLTimeoutException 原因链，保留普通断连 500。真实 MySQL HTTP 路径验证 socket 超时返回 503、计数增加及后续恢复；池耗尽测试验证获取超时及许可恢复。

复审结论：无任务 04 的明确遗漏、范围扩张或新的可执行问题，剩余发现 0 项。分页与清理仍由任务 05、06 承接。

## 验收

首个 HTTP 测试先确认未实现接口返回 404，随后实现并通过。定向测试覆盖日期拒绝、零值、版本与跨日去重、UTC 边界、状态历史、真实 HEAD、查询准入与错误、只读 RR 快照及普通映射读取不加锁。
受控汇总读取之后并发删除旧记录、插入新版本记录并更新映射，当前响应保持原快照，后续请求观察新值。
审查修正后完整 Maven 套件 199 项通过，0 失败、0 错误、0 跳过，包含真实 MySQL 和 Redis 缓存/状态协调回归。
日志 `target/task04-final-full.log`（本地生成，不纳入 Git）；本轮临时 MySQL 容器已清理。

最终 Standards 0 项、Spec 0 项，两轴均无剩余最严重问题。
