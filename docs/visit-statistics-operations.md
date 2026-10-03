# 异步访问统计：停采、暂停消费与关闭

采集和消费是两个独立的部署配置。新采集默认关闭；消费者默认开启，即使停采也处理队列中的历史消息。修改后重启应用生效，没有动态管理 HTTP 接口。

| 环境变量 | 对应属性 | 效果 |
| --- | --- | --- |
| `SHORT_LINK_STATS_ENABLED` | `short-link.stats.enabled` | `false` 不产生新事件或统计 Cookie；历史查询、消费和日志清理继续 |
| `SHORT_LINK_STATS_CONSUMER_ENABLED` | `short-link.stats.rabbit.consumer-enabled` | `false` 不启动消费者；发布、队列保存及历史查询、清理保持独立 |

用 PowerShell 7 从仓库目录运行。以下命令假设已构建 `target/short-link-0.0.1-SNAPSHOT.jar`，保留当前部署的数据库、Redis、RabbitMQ、HMAC 和管理令牌配置。前台进程用 `Ctrl+C` 发起正常关闭，然后在同一终端执行对应启动命令；服务部署应把相同变量写入其启动环境，再用现有服务管理器重启。

暂停消费（故障期间仍然采集是否可接受，由维护者根据积压预算决定）：

```powershell
# pwsh -NoProfile
$env:SHORT_LINK_STATS_CONSUMER_ENABLED = 'false'
java -jar target/short-link-0.0.1-SNAPSHOT.jar
```

关闭新采集，同时继续处理历史消息：

```powershell
$env:SHORT_LINK_STATS_ENABLED = 'false'
$env:SHORT_LINK_STATS_CONSUMER_ENABLED = 'true'
java -jar target/short-link-0.0.1-SNAPSHOT.jar
```

恢复消费（保留当前采集选择）：

```powershell
$env:SHORT_LINK_STATS_CONSUMER_ENABLED = 'true'
java -jar target/short-link-0.0.1-SNAPSHOT.jar
```

持续 DB 故障时先观察受控消费失败类别、处理速率、业务 ready/unacked 和 DLQ 数量，核验统计池与数据库，再暂停消费并修复故障。暂停需要正常关闭并重启；当前及预取事件并非瞬间撤回，暂停生效前仍可能重试耗尽、死信、TTL 或容量淘汰。业务队列和 DLQ 也有驻留与容量预算，不是可靠补偿存储。恢复后可先核验少量历史记录写入和积压回落；人工重放保持原 ID/发生时间且遵守窗口，不自动回流 DLQ。

后台声明和消费者启动发生在核心 `ApplicationReadyEvent` 后的单一后台 worker。启动网络失败按固定间隔重试；配置暂停始终不启动 listener，发布恢复只重建发布连接，不覆盖暂停。监听器已启动后的连接故障由其恢复逻辑处理；手动停止的监听器不因发布恢复被启动。认证、声明属性冲突应修正配置或拓扑，不能删除重建已有积压队列。

关闭首先停止交接、清空有限本地缓冲、终止观察与启动重试，并将所有尚未终结的发布结果标记 unknown。发布清理和消费关闭各有单一 daemon owner；Spring 的 CCF lifecycle stop 不再同步重置连接，重复 DisposableBean destroy 由显式 owner 接管。消费停止等待起点 1 秒，`forceStop` 避免继续排空预取；未 ACK 的物理断连消息由 broker 重投，同 ID 通过现有唯一键去重。普通完成处理的事件仍可 ACK。

Recorder 的共同等待预算为 2 秒，Spring 每个关闭阶段等待起点也是 2 秒；它们不是整个进程或所有销毁方法的硬截止。卡在 TCP/JDBC 或依赖关闭锁的单一 worker 可能需要依赖解除后才退出；不会因超时创建替代 worker、连接工厂或恢复任务。进程内未发事件允许丢失，unknown 不能改称未送达，也不重发或同步回退 MySQL。正常解除依赖后线程和连接释放；异常阶段不能承诺所有后台线程立即消失。

测试 profile 默认暂停消费者，避免保留的查询测试上下文或不同 Clock 消费其他测试的事件。真正的 MQ HTTP 测试显式开启消费并关闭其上下文；集成测试须使用独立数据库/vhost。测试默认暂停不影响生产默认开启。

## 积压观察与资源起点

以下配置是可解释的起点，不是已经验证的吞吐、HTTP p99、消费延迟或 SLA。运行期也要合并所有应用实例的资源预算。

| 资源 | 当前配置和约束 |
| --- | --- |
| 本地待发 | 256 个事件、1 个 sender；满立即跳过，单调待发年龄超过 5 秒不开始发送 |
| 关联尝试 | 最多 32 个未确认，5 秒观察期；单终局释放，unknown 不证明未送达 |
| 发布 channel | 活跃最多 16 个，checkout 等待 200ms；不是仅设置 channel 缓存大小 |
| 发布后台 | sender/startup/observer/cleanup 各 1 个；恢复最多 1 个，未退出不创建替代 worker |
| 发布框架 executor | 2 线程、32 个待执行任务、AbortPolicy；不用 CallerRuns |
| 消费后台 | listener 1 线程、任务队列 1；框架 executor 4 线程、任务队列 64、AbortPolicy |
| Consumer | 同步处理、并发 1、prefetch 10、batchSize 1、AUTO；DB 终局后才正常返回 |
| 统计 DB | 池最多 4，写入准入 2、查询 1、清理 1；核心池独立，全实例仍共享 MySQL |
| 业务 Queue | ready 10000 条或 16MiB body，先到限制生效；24 小时驻留 TTL、reject-publish |
| DLQ | ready 1000 条或 4MiB body，24 小时独立驻留 TTL、drop-head，无消费者/自动回流 |

队列 ready/body 配额不包含所有 unacked、headers 和存储开销。prefetch 的 10 个 unacked 不是另一份本地无界缓冲，也不受 ready 条数上限保护。业务满触发 publisher nack，reject-publish 不送 DLQ；不要改为 reject-publish-dlx。业务过期可经 DLX 进入 DLQ，DLQ 再计自己的 24 小时，可能合计接近 48 小时；TTL 不保证到点立即物理删除，不替代 unacked 处理与业务日期窗口。

主队列 DLX/key、容量和 TTL 保存在同一条 `shortlink-visit-consumer` policy。RabbitMQ 对每个队列选择一条最高优先级普通 policy；新增重叠容量 policy 可能遮蔽 DLX。声明仅固定 durable/classic 身份；修改 quota 用受控 policy，不删除含积压队列。只读观察和 policy 设置都需要管理端最小权限，不把管理凭据写入配置/命令历史。

在 `pwsh -NoProfile` 中读取凭据并显式设置策略（这些脚本不删除队列/消息）：

```powershell
$credential = Get-Credential
./ops/set-visit-consumer-policies.ps1 -ManagementUrl 'https://broker-management.example' -VirtualHost 'your-vhost' -Credential $credential
./ops/get-visit-broker-observation.ps1 -ManagementUrl 'https://broker-management.example' -VirtualHost 'your-vhost' -Credential $credential
```

观察脚本只读取两条固定队列及 broker nodes，输出 ready、unacked、consumers、brokerAckPerSecond 和 memoryAlarm/diskAlarm。management 的数据有采样延迟；缺失速率为 null，不能当 0。资源告警必须与网络/DB/消费情况合看；不要在生产人为制造资源告警。脚本错误只保留固定类别，不显示管理 API response 或凭据。没有新增监控平台或公开运维 HTTP 接口。

应用内观测保留在 bean 的只读 `snapshot()`，可在已有内部调试/诊断环境读取，不为读取新增公网接口：

- `AsyncVisitRecorder`：`eventOutcomes` 是本地接受/满/过期/限流/编码失败/停机损失；`publishOutcomes` 包括实际 send 尝试、confirm accepted、return、nack、unknown、send-failed 和恢复类别。`outcomes` 保留原兼容视图，不能与两个分层 map 再相加。`pending/unconfirmed/recovering` 描述本进程发布资源；`publishDurationNanos` 是关联尝试终局观察累计耗时，包含未知观察期，不是网络发送总截止。
- `VisitConsumer`：`deliveries` 是收到消息的处理轮次，重投会增加；`persistenceAttempts` 是每轮实际调用 DB 用例的次数，重试不会当新访问。固定终局有 SAVED/DUPLICATE/EXPIRED/INVALID/PERMANENT/EXHAUSTED/INTERRUPTED；ATTEMPT_FAILED 单独计算失败尝试。`processingNanos` 包含同步重试等待，`inFlight` 是当前同步处理数；`completedPerSecond` 是自此 bean 创建以来的已终结消息轮次均速，包含失败/拒绝，不冒充成功保存速率。
- `VisitWriteObservations`：DB 保存/重复/准入丢弃/失败/不确定、固定错误类别、尝试累计耗时及 Hikari active/idle/total/waiting。与 Consumer 尝试和 Publisher ACK 分开解释。未初始化池时池数为 0，并非 DB 健康证明。

所有 snapshot 是并发下近似的本进程累计观察，重启会清零；比较两次快照差值/采样间隔，才能观察短时处理变化。固定类别不以短码、IP、访客或消息体建无限标签。累计处理耗时除以 completed 只给均值，不是分位数。`processedEventDelayMillis/Count` 只包含已保存或已确认重复的事件，从冻结 occurredAt 到终局的时间差；重复也可能非常迟到，负时差压为 0。`localOldestQueuedAgeNanos` 只看本地 pending 队首单调年龄，已由 sender 取走的在途事件不包含其中；空队列为 0。二者都不是 broker 精确最旧事件年龄、完整消费水位或 generatedAt 的业务含义。

积压演练与处置顺序：先核验 listener/consumer 数、ready/unacked、消费轮次速率和已处理延迟，再核验 DB 失败/耗时/池等待和 broker 资源告警。持续 DB 失败会有限重试耗尽并继续把新消息送往 DLQ，单轮三次不会自动止损。按前述配置暂停消费，必要时关闭新采集，修复 DB 后恢复消费并核验少量历史写入/积压回落；DLQ 不自动回流，停采期间不补采。提高并发前必须核验所有实例的统计池和总 DB 容量；不以无限增加 buffer/prefetch 掩盖持续输入大于处理能力。

隔离验收使用缩小的条数/body/TTL，真实确认满队列 nack 而未入 DLQ、主 TTL 过期死信、DLQ 自己的 TTL/drop-head，以及阻塞持久化时 consumers=1/unacked=10/ready=3。持续真实 DB 锁故障显示两次消费轮次各三次尝试、两次耗尽，人工停止后待处理消息留队，解除故障/恢复后写入；故障和暂停时短链仍 302，失败样本仍留 DLQ。生产运维采用配置修改后正常重启；测试直接控制内部 listener 只是隔离接缝，不是新增运维 API。

## 资源所有权

应用 MQ ready/close/destroy 统一归 `stats.messaging.VisitMqRuntime`；受信任上下文应通过它执行终止，不再调用 recorder.close 终止消费。AsyncVisitRecorder 只负责发布和发布恢复；消费关闭、CCF 非网络 stop 与销毁保护保留在 messaging adapter。核心 dataSource 归 configuration，statsDataSource 归 stats.config；采集、消费、查询和清理开关与本说明一致。
