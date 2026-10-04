Status: resolved
Type: task
Blocked by: 04

# 05: 迟到事件遵守冻结时间和30日窗口

**What to build:** 内部管理者查询异步落库的历史访问时，跨日、乱序和映射后续状态变化不改统计口径；超过保留窗口的重放不能重新建立旧日志或PV。

**依赖说明：** 04 — 需要已明确的拒绝/DLQ路径，并验收持久化重试跨午夜的窗口复查。

## Acceptance criteria

- [x] 校验statDate与occurredAt的上海日期一致，按冻结时间落库；不按处理时间重分桶，不因映射当前禁用/过期拒绝过去合法事件。
- [x] 每次真正持久化尝试前检查当前上海日及其前29日；重试跨午夜重新判断，合法超窗事件记expired并ACK丢弃。
- [x] 未来统计日/日期不一致走无效数据拒绝和死信，不为使事件入账修改发生时间；清理后旧事件重放不重新插入。
- [x] 真实消费/数据库结果验证跨午夜、乱序、清理后重放和重试跨窗口边界；不承诺执行等待跨午夜时所有物理超窗行已删除。
- [x] 现有聚合/趋势/身份版本、范围UV、明细排序/游标、权限及30日查询窗口回归通过；查询失败不伪造零值。
- [x] 不新增API、消费水位或完整性承诺；generatedAt/collectionEnabled/一致快照说明保持准确，消息TTL不代替业务保留判断。

## Comments

- 2026-10-02：用户已确认8张任务的粒度、交付范围和直接阻塞关系。本轮仅发布任务，尚未认领或开始实施；父规格内容和状态保持不变。

## Answer

- 消费侧使用现有 Clock，实际持久化首次及每次重试前重新计算上海当天及前29日。返回独立的 SAVED / DUPLICATE / EXPIRED 消费终局；expiredCount 记录合法超窗成功丢弃，不借用数据库保存结果。
- 未来统计日直接拒绝；codec 原有发生时间/上海日期一致性校验保持。冻结 eventId、发生时间、身份摘要不变，不查询当前映射状态。
- TDD：先添加超窗终局测试，编译失败证明缺失 Clock / EXPIRED / 消费反馈；实现后 VisitConsumerTest 6项通过。
- 真实 RabbitMQ + MySQL：VisitConsumerIntegrationTest 11项通过，包括跨午夜/乱序、禁用及过期映射、未来日/日期不一致 DLQ、实际清理后重放、提交不确定重试跨上海午夜窗口，以及原重试/唯一键/ACK断连重投。
- 回归通过：VisitStatsQueryApiTest 12项、VisitLogsApiTest 7项、InternalManagementApiTest 8项、VisitCleanupLifecycleTest 3项、VisitLogCleanupTest 2项；本次回归连同消费集成共43项，0失败/错误。独立DB short_link_consumer_test，vhost link-consumer-test；query-only上下文以命令配置禁用自动消费，真正消费测试手动启动独立 listener。06负责将测试默认暂停和真实MQ类显式启用/关闭上下文固化。
- 未新增 API、表或消费水位。执行期间跨午夜仍由原清理追赶，不声称瞬时物理删除全部超窗行。
