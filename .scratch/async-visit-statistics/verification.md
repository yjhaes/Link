# 异步访问统计实施验证

## 固定基线

- 实施起点：`8a3d7ebfcf34fd02378cf30ebb7b08bb0f222294`。
- 集成分支：`codex/async-visit-statistics`。
- 实施模型沿用当前模型；最终 Standards Review 与 Spec Review 使用 GPT-6.1 Sol / high。
- 原规格的“本次不实施”指此前规格发布阶段。本次用户显式调用 implement-spec，授权按已发布的八张任务实施。

## 同步基线验证

2026-10-02，在隔离 MySQL 8.4 上运行 VisitStatisticsApiTest、VisitStatsQueryApiTest、VisitLogsApiTest：32 项通过、无失败和跳过。

实施前同步可执行包保存在本机 `.tools/async-baseline/short-link.jar`，原始测试日志、测量 JSON 同目录保存，不提交包含框架原文的日志。

## 对比方法与同步样本

本机 Java 17，MySQL 8.4 单节点容器，Redis 7.2 单节点容器；HTTP loopback；统计开启，同一匿名 Cookie，正常有效映射，30 次预热后 300 次顺序 GET（并发 1），HttpClient 关闭自动跳转，核验 302 和目标地址。测量包含客户端请求与响应开销，缓存命中。通过独立 `short_link_benchmark` 数据库避免回归测试干扰。脚本：[measure-http.ps1](measure-http.ps1)。

| 版本 | 平均/ms | p50/ms | p95/ms | p99/ms | 测量总耗时/ms | 查询时可见 PV/UV |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 同步基线 | 7.386 | 7.267 | 8.929 | 9.945 | 2244.951 | 330/1（含预热） |

此表保留实施前的小样本观察，单独不足以推出异步收益，也不代表生产吞吐、稳定 p99、尖峰能力或 SLA。最终新会话同条件比较和事件可见性、处理延迟见下方 08 记录。

## 已合入切片

- 01：明确同步持久化结果和安全失败类别；相关真实 MySQL / HTTP / 查询 / 清理共 52 次测试执行通过。提交 `70fdd03`，集成合并 `6f08548`。完整验收见 [任务 01](issues/01-visit-persistence-outcomes.md)。
- 02：JSON 协议、真实异步闭环、HTTP 隔离及核心回归共 99 项通过。见 [任务 02](issues/02-rabbitmq-visit-roundtrip.md)。
- 03：真实路由、return/nack、TCP 黑洞恢复、启动不可用后恢复及竞态共 9 项通过。见 [任务 03](issues/03-publish-failure-and-backpressure.md)。
- 04：分类重试/DLQ、提交至 ACK 间隙重投、其他约束拒绝、安全日志及池隔离共 14 项通过。见 [任务 04](issues/04-consumer-retry-and-dead-letter.md)。
- 05：消费测试 17 项及回归测试 43 项执行通过；两组包含重复执行的测试，不能相加为独立用例数。见 [任务 05](issues/05-late-visits-and-retention-window.md)。
- 06：生命周期与发布故障的 10 项针对性测试分两轮绿色；最终生命周期类 3 项，其他 7 项。见 [任务 06](issues/06-collection-and-consumer-lifecycle.md)。集成合并 `4a3f4c5`。
- 07：固定类别观测、真实队列容量/TTL/prefetch和持续数据库故障暂停恢复的 21 项针对性测试通过。见 [任务 07](issues/07-backlog-observability-and-budgets.md)。
- 08：两轮全量各 259 项通过，失败/错误/跳过均 0；完成同等条件同步/异步比较及独立延迟补充样本。见 [任务 08](issues/08-async-visit-verification.md)。

## 最终验收

01～08 实现与验收、独立双轴审查及两项维护性修正均已完成。下面保留各阶段的实际证据，最终审查与修正见末尾；测试及测量不扩大 best-effort 契约。

2026-10-02 曾按用户要求在 06 完成后暂停，随后收到“继续”并完成剩余工作。历史续接依据见 [resume.md](resume.md)。

## 08 整体验收与新会话比较

2026-10-02 用户已恢复任务，01～07 resolved。首轮完整 Maven suite **259 项通过，失败/错误/跳过均 0**，包含 RedisRedirectIntegrationTest 的真实 Testcontainers MySQL 8.4 / Redis 7.2（47 项），没有替换成 mock 或跳过 Docker。其余真实 MySQL、RabbitMQ 测试运行已保存实施工作树忽略目录（原始运行日志随后清理，永久依据为本记录与测试源码）。首次非异步上下文后台连接默认5672产生安全降级日志；统一 test profile 的 RABBIT_TEST_PORT 后最终全量再次 **259 项通过，失败/错误/跳过均0**（23:07，3分59秒），运行记录已归纳为本文件及任务08 Answer；无生产行为修复。

### 场景证据

| 合并验收场景 | 实际通过的测试边界 |
| --- | --- |
| 每请求 cache hit/shared loading、Cookie/独立ID/口径 | VisitStatisticsApiTest 18、AsyncVisitRoundtripTest 2；真实 HTTP、RabbitMQ、MySQL 最终结果与闩锁隔离 |
| 原创建/状态/鉴权、缓存协议 | ShortLinkApiTest 35、RedisRedirectIntegrationTest 47、RedirectLoadCoalescingTest 14、管理 API/configuration 回归 |
| 范围UV/日期/趋势/分页/查询错误、清理 | VisitStatsQueryApiTest 12、VisitLogsApiTest 7、清理及 timeout/observation 测试 |
| 启动不可用/运行发布失败/恢复/背压/竞态 | AsyncVisitStartupRecoveryTest、VisitPublisherBrokerTest、VisitPublisherFailureTest；真实 Rabbit/TCP blackout 与可控回调分别提供证据 |
| 提交至ACK间隙、分类重试、DLQ、时间窗口 | VisitConsumerIntegrationTest 11、VisitConsumerTest；真实独立消费连接重投、MySQL 去重和窗口检查 |
| 关采继续消费/查询/清理、人工暂停/恢复、有界关闭 | VisitCollectionLifecycleTest 3、VisitPausedRecoveryTest、VisitMqShutdownTest |
| ready/unacked、满nack无DLQ、TTL/DLQ淘汰、prefetch及DB持续故障暂停 | VisitBacklogIntegrationTest 5；真实 broker 缩小 policy，结束恢复标准 policy |
| 安全消息/日志、池隔离和超时 | VisitMessageCodecTest、SafeDependencyConsoleEncoderTest、VisitStatisticsApiTest、VisitTimeoutTest 6 |

以上测试计数为对应类独立用例数，首轮完整总数259，不将历史多轮执行相加。此前每张切片自身 Answer 的详细故障方法继续适用；mock 不冒充真实 broker 证据。

### 同环境公平 HTTP 样本

本轮先运行原 `8a3d7eb` 同步 jar，再运行当前异步 jar，均 Java17、本机loopback、同MySQL8.4/Redis7.2、独立 short_link_benchmark DB、同缓存命中且同匿名Cookie、30预热+300顺序GET、并发1、同 measure-http.ps1。异步使用新隔离 link-benchmark-08 vhost 和完整主/DLQ policy，开始前确认消费者已运行；没有关闭 MQ 或同步回退。脚本核验302/Location；测量分布只计300请求，PV含30预热。原日志/逐请求样本保存在被忽略 `.tools/verification`，历史7.386ms只作旧样本，不参与这次比较。

| 本轮 jar | 均值/ms | p50/ms | p95/ms | p99/ms | 总耗时/ms | 首次PV/UV | 最终PV/UV | 追加可见等待/ms | 窗口末缺失 |
| --- | ---: | ---: | ---: | ---: | ---: | --- | --- | ---: | ---: |
| 原同步 | 7.016 | 6.873 | 8.486 | 9.677 | 2128.171 | 330/1 | 330/1 | 0.629 | 0/330 |
| 当前异步 | 3.126 | 3.041 | 3.928 | 4.165 | 948.222 | 156/1 | 330/1 | 741.181 | 0/330 |

在这一小样本正常依赖环境观察到 HTTP 均值约降低55.4%，代价是异步可见。追加查询等待是请求循环结束后到全量可见的上界观察，**不是单事件处理延迟**。脚本有30秒可见等待预算；若预算末仍缺失，只能报告缺失而不能证明永久丢弃。本次两组最终记录完整，观察缺失率0%；不证明故障丢弃率、并发吞吐、稳定生产p99或 SLA。同机同DB仍共享硬件；同步先运行、单轮顺序样本也存在顺序/预热影响，不宣称通用倍数。

为获得现有内部观测，用同实现 classpath 启动独立应用上下文、同HTTP脚本和独立样本，未增加生产端点/生产测试模式：300请求均值3.273ms；330本地接受/330发布confirm/330 delivery/330 SAVED，满/expired/encoding/nack/return/unknown/send-failed/重试/重复均0，最终pending/unconfirmed/inFlight均0。现有 VisitConsumer.snapshot 的累计 processedEventDelayMillis=142467、count=330，事件冻结至保存平均 **431.718ms**；listener内平均处理耗时 **5.274ms**（1740502300ns/330），实际保存观察而非可见等待。该补充运行不同于上面 jar 测量，不能伪称同一组事件的延迟；只有累计平均，没有延迟分布。同步返回前已完成记录，其请求耗时含落库但未单独测event→commit延迟，不能把7.016ms当其精确处理延迟。

### 阶段边界

部署/开关/人工暂停/关停/policy/固定类别观察/人工死信及面试能力说明同步至 README、ops/README、采集说明和异步设计。只实施必须实现范围：无 outbox/事务消息、quorum、自动重放/补采、批量/扩容、永久去重或端到端恰好一次。所有统计仍来自MySQL已记录事件，业务口径、隐私、核心缓存协议保持；单节点、进程内丢失、classic死信丢失、确认不确定及物理保留延迟继续明确。本节记录08完成时的验收；后续GPT-6.1 Sol/high双轴审查与建议修正见末尾记录，测试和审查仍分别说明。

最终公平组在23:08全量回归结束后、无并行DB/MQ测试时重新运行（sync-final-measurement.json、async-final-measurement.json）。前一探索组曾与suite尾部时间重叠，故不用于最终收益比较；补充上下文延迟样本独立运行时无并行测试。

## 最终审查与修正

审查快照2ada127，Standards Review与Spec Review分别由独立GPT-6.1 Sol/high子代理完成：规范硬性违规0、两项P3判断性维护建议；规格0项发现。原报告分轴保存在 [code-review.md](code-review.md)。

单一实施子代理完成两项建议（私有发布类别枚举、私有boundedExecutor），公开snapshot标签、分层和资源预算保持。代码2fbf5fb、合并9b33501；修正后9个测试类共51项全部通过，无失败/错误/跳过。全量259项的两轮记录来自08构建；随后只作上述维护性重构，验证相关51项，未伪称再次全量或重新测量性能。累计差异检查通过。

父规格及八张任务全部resolved，集成分支codex/async-visit-statistics；辅助实施工作树归档，隔离测试容器停止并保留数据。开发环境测量、best-effort及非可靠入账边界继续有效。
