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
