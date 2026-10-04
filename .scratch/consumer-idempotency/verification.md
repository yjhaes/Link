# 阶段 7：消费者幂等异常验收

实施起点：`dab10695c9e057a30debe5afd7cd438795abdbf8`。用户调用 implement 并指定任务 01，授权实施此前待实现的两类测试。实现使用当前模型；Standards Review 和 Spec Review 均使用 GPT-6.1 Sol / high。

## 本次环境与执行

2026-10-04，Java 17、Maven 3.9.16，复用隔离测试设施 MySQL 8.4（13306）、Redis 7.2（16379）、RabbitMQ 3.13-management（AMQP 15672、管理 15673）。MySQL 使用 short_link_test 数据库，消费验收使用 link-consumer-test vhost，其他生命周期测试使用自己的隔离 vhost。

- 新增两类用例初次运行：2 项通过，失败、错误、跳过均 0。
- 相关回归首次运行：71 项中 70 项通过、1 项失败。VisitStatsQueryApiTest 要求默认关闭采集，本次未设置 SHORT_LINK_STATS_ENABLED，采用生产默认开启；没有生产代码缺陷证据。
- 设置 SHORT_LINK_STATS_ENABLED=false 后重跑相关回归：71 项通过，失败、错误、跳过均 0。显式开启采集的测试配置仍生效。

相关回归范围：VisitConsumerIntegrationTest、VisitStatisticsApiTest、VisitStatsQueryApiTest、VisitLogsApiTest、VisitCollectionLifecycleTest、AsyncVisitRoundtripTest、VisitConsumerTest、VisitLogCleanupTest、VisitTimeoutTest。覆盖既有 ACK 间隙重投、非事件约束错误、保存后清理异常、独立新访问、超窗重放、重试跨午夜、查询及明细。

原始日志保存在忽略目录 `.tools/consumer-idempotency/`；上述数量为每轮实际执行数，不将重跑相加为独立用例数，也不复用阶段 6 历史通过记录。

## 新增证据与注入边界

`VisitConsumerIntegrationTest.concurrentFirstInsertsOfSameEventSaveOnceAndConfirmDuplicate`：通过既有 VisitPersistence.persist 入口，两个线程处理尚未保存的同一事件。测试 JDBC 代理在绑定指定 eventId、执行真实 INSERT 前使用双参与者 CyclicBarrier 同步放行；真实 MySQL 返回一个 SAVED、一个 DUPLICATE，隔离短码仅一行日志。屏障 5 秒、future 10 秒、线程终止 10 秒上限；检查写准入和连接释放。不改变生产 semaphore 或唯一键。

`VisitConsumerIntegrationTest.committedInsertWithLostReplyRetriesOriginalEventAndAcknowledgesSingleVisit`：既有协议经真实 RabbitMQ listener 调用真实持久化。测试代理先执行真实自动提交 INSERT，用另一连接看到一行，再仅对指定事件的首次成功响应抛 SQLState 08S01；不是 SQL 执行前异常或纯 mock。持久化暴露一次 UNCERTAIN，消费沿用原 eventId、发生时间、统计日、短码、访客摘要和密钥版本，第二次得到 DUPLICATE。消费 SAVED=0、DUPLICATE=1、ATTEMPT_FAILED=1；真实首次提交不冒充首次确认保存。

审查后增强 ACK 证据：发布前记录业务队列 ACK 累计基线，等待其增长，并要求 ready/unacked 为零、DLQ messages 为零持续 6 秒（总等待 25 秒）；覆盖管理接口默认采样延迟，避免投递前旧零样本假阳性。管理 HTTP 连接与请求均限时 5 秒。

既有管理 stats/visits API 使用隔离映射、固定访客、对应有效日期，确认 PV=1、UV=1、明细一条且无下一页。listener 停止后复原故障代理、清理隔离映射和日志、清空测试队列；生产实现未改。

## 复现

在 pwsh 设置 MYSQL_TEST_URL 为隔离测试数据库、REDIS_PORT=16379、RABBIT_TEST_PORT=15672、SHORT_LINK_STATS_ENABLED=false；管理 URL 默认 15673，凭据沿用测试配置。运行：

```powershell
mvn '-Dtest=VisitConsumerIntegrationTest,VisitStatisticsApiTest,VisitStatsQueryApiTest,VisitLogsApiTest,VisitCollectionLifecycleTest,AsyncVisitRoundtripTest,VisitConsumerTest,VisitLogCleanupTest,VisitTimeoutTest' test
mvn test
git diff --check
```

保留 best-effort、窗口内同事件唯一键防重与每轮最多三次重试；不承诺永久去重、端到端不丢或严格 Exactly Once。不进入阶段 8。
