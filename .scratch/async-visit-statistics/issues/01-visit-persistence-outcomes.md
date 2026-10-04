Status: resolved
Type: task
Blocked by: None

# 01: 明确统计持久化结果，保持跳转降级

**What to build:** 先让统计持久化能够明确反馈已保存、合法事件重复或失败/提交不确定，同时保持现有访问者在同步统计失败时正常跳转。后续Consumer不再把吞异常后的正常返回当作ACK依据。

**依赖说明：** 无前置依赖，可首先开始。

## Acceptance criteria

- [x] 在既有统计用例边界提供明确的持久化反馈或可分类失败，不直接复用吞异常的void返回证明消费成功；保留HTTP侧的尽力降级适配。
- [x] 只有事件唯一键重复被视为合法重复，其他约束和SQL错误传播正确类别；确认保存后的资源关闭失败保留SAVED。
- [x] 保留原eventId和单条日志插入，不新增表、统计双写、数据库事务依赖或对外API。
- [x] 真实MySQL验证保存、重复、繁忙、执行前失败、提交确认丢失及资源释放；重放同事件至多一行。
- [x] 从HTTP验证正常302/Location/no-store、Cookie及失败降级不变；核心数据源、状态事务、查询和清理归属保持。
- [x] 使用现有统计提交/存储接缝，不为下一阶段建立通用仓储或大量透传接口；不引入RabbitMQ。

## Comments

- 2026-10-02：用户已确认8张任务的粒度、交付范围和直接阻塞关系。本轮仅发布任务，尚未认领或开始实施；父规格内容和状态保持不变。

## Answer

- 实现 `VisitPersistence.persist` 的 SAVED / DUPLICATE 确认边界，失败通过 `VisitPersistenceException.Failure` 明确区分 BUSY / TRANSIENT / PERMANENT / UNCERTAIN。异常只保留固定类别，不保留驱动文本或 cause。
- HTTP 继续使用原 `VisitRecorder.record` 尽力降级适配；统计准入、专用连接池、单行插入、eventId、查询和清理归属保持。确认执行成功后资源关闭失败仍返回 SAVED，仅 uq_visit_event 的真实唯一键重复返回 DUPLICATE。
- TDD 首个测试先因缺少确认边界编译失败；真实 MySQL JDBC 准备失败测试暴露 S1009 不应归暂时错误，随后修正为仅已知暂时故障可重试。
- 在隔离 MySQL 8.4（localhost:13306）执行七类针对性回归，共 51 项全部通过：VisitStatisticsApiTest、VisitTimeoutTest、VisitStatsQueryApiTest、VisitLogsApiTest、VisitCleanupLifecycleTest、VisitCollectionFailureTest、VisitWriteObservationTest。另加真实其他唯一约束测试 1 项通过，合计 52 次测试执行。
- 覆盖保存/重放至多一行、合法与其他唯一约束、SQL 错误、连接阶段失败、准入繁忙、保存后确认丢失、确认后关闭失败、许可/连接释放、HTTP 302/Location/no-store/Cookie 及核心事务/查询/清理回归。
- 实现提交 70fdd03，已合并当时 integration tip d5e0e05。未修改外部 Desktop 任务文件。
