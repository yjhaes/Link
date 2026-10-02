Status: resolved
Type: task
Blocked by: 06

# 07: 积压可观测且队列容量与TTL生效

**What to build:** 维护者能够从固定类别观测区分本地丢弃、发布未知和消费积压，核验broker容量/TTL/prefetch约束，并用已具备的停采/暂停控制保护核心服务。

**依赖说明：** 06 — 需要完整的恢复及人工控制，形成观察积压→采取措施→恢复处理的可演示闭环；03/04为传递依赖，不重复列出。

## Acceptance criteria

- [x] 沿用各片基础观测，形成事件/发布尝试/持久化尝试分层快照，覆盖本地接受/满/过期、关联状态、confirm/return/nack/unknown、保存/重复/expired/失败/耗尽和耗时。
- [x] 可观察ready/unacked、消费速率、处理延迟及broker资源告警；不把已处理事件延迟或本地年龄伪装成精确broker最旧事件年龄或消费水位。
- [x] 业务Queue ready最多10000条或16MiB、TTL24小时、reject-publish；真实缩小配额验证满时nack且不进入DLQ，不误用reject-publish-dlx。
- [x] 真实缩小TTL/容量验证主队列过期死信、DLQ自己的驻留期限和drop-head，明确ready/body配额不覆盖全部unacked或存储开销，两个TTL不是总24小时。
- [x] 核验Consumer并发/prefetch、统计池及本地/channel/关联状态预算；积压时不无限增缓冲，有限并发优化前核验全实例DB资源。
- [x] 演练持续DB故障的观测、人工暂停、必要时停采及修复后恢复，短链继续正常跳转；没有自动历史补采或无限补偿。
- [x] 指标固定类别、不含无限短码/IP/访客标签，业务/驱动/框架错误均不泄露消息体、摘要或秘密；不新增监控平台或公开运维接口。
- [x] 更新资源和维护说明，所有起点仍标明不是已验证吞吐/SLA；本片不依赖迟到统计业务的实现，05和本片可独立验收。

## Comments

- 2026-10-02：用户已确认8张任务的粒度、交付范围和直接阻塞关系。本轮仅发布任务，尚未认领或开始实施；父规格内容和状态保持不变。
## Answer

2026-10-02：已完成07，同步最新集成 tip eba7afc 后提交；没有执行08全量回归/性能比较或最终双轴审查。

- 保留发布单终局/有界关联与发布专属恢复，补事件/发布尝试分层 map、关联终局累计耗时、本地最老 pending 单调年龄；兼容原 outcomes。Consumer 增固定轮次终局与尝试失败分类、实际 persist 次数、in-flight、同步处理耗时、已完成轮次均速和已保存/重复事件冻结发生至处理延迟；作为 bean 供内部诊断读取。DB 层继续用 VisitWriteObservations；无新公开 HTTP、无限标签或监控平台。
- 在原主 policy 同时增加10000条/16MiB/24h/reject-publish并保留 DLX/key；原 DLQ 1000条/4MiB/24h/drop-head维持。用已有设置脚本显式应用隔离 vhost；新只读 ops/get-visit-broker-observation.ps1 输出固定队列 ready/unacked/consumers/ack rate和memory/disk告警，真实管理端执行成功，无法访问时只输出固定故障文本。
- TDD：Consumer 新观测接缝测试在缺 snapshot/Category 时红；local snapshot 缺分层 map/年龄时红；真实主policy预期10000而得到0时红。逐片实现后绿；真实缩小配额/TTL的broker验证不靠mock。
- 最终针对性合跑 `VisitConsumerTest,VisitPublisherFailureTest,VisitBacklogIntegrationTest,VisitCollectionLifecycleTest`：21项，0失败/错误/跳过（8+5+5+3）。随后只提高处理延迟断言为固定正差1123ms，重跑 Consumer 8项全通过；没有行为变化。3项既有06生命周期回归用于验证新 Consumer bean接线，保留Clock窗口检查与受控CCF/Listener关闭、依赖日志保护。
- 5项新真实RabbitMQ/MySQL测试：同一主policy所有参数生效；缩小条数和body两种上限均nack且DLQ空；主队列150ms过期死信reason=expired，DLQ1500ms独立驻留并drop-head保留最后消息；持久化闩锁时实际consumers=1/unacked=10/ready=3，放行后13行；真实DB锁持续故障2轮/6次尝试/2次耗尽和DLQ，人工停止后消息留队，故障/停采仍302无统计Cookie，解除故障恢复后保存且失败样本不自动回流。
- 隔离资源：short_link_lifecycle_test / link-lifecycle-test，AMQP15672、management15673、MySQL13306、Redis16379；每例停止consumer、清理测试消息/数据并恢复标准policy。未生产制造资源告警。
- [运维说明](../../../docs/visit-statistics-operations.md)记录所有本地/channel/关联/线程/统计池预算及多实例核验；ready/body不含全部unacked/存储开销、两个独立TTL可能近48h、本地年龄/处理延迟不是broker最旧年龄或水位。所有起点不是已验证吞吐/SLA。无历史补采、自动DLQ回流或无限补偿。
- 原始Maven日志移动到忽略目录 `.tools/07-verification/`，不提交；永久验收证据为本Answer和测试源码。仅本片完成，08测量/全量/最终审查仍待执行。
