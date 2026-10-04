Status: resolved
Type: task
Blocked by: 03, 04

# 06: 分离停采与暂停消费，并有界启停

**What to build:** 维护者可分别停止新采集和暂停消费；停采后历史消息、查询和清理继续，暂停不被自动连接恢复覆盖，MQ或DB故障时应用仍可有界启动/关闭。

**依赖说明：** 03, 04 — 需要可控的发布恢复和消费失败/重试生命周期，才能验收暂停意图与故障关停。

## Acceptance criteria

- [x] 采集开关只控制新事件/统计Cookie；关闭后消费已有消息、历史查询和清理继续，恢复后不补采停采期间访问。
- [x] 提供独立消费者启用配置及修改后重启的运维步骤，不新增公开管理HTTP接口或自动断路器。
- [x] 后台MQ恢复区分故障待恢复与人工暂停，人工暂停后不擅自重新启动listener；停止不是瞬间撤回当前或预取消息。
- [x] MQ声明/启动不在核心启动线程等待网络，MQ不可用不取消核心启动；认证/声明冲突观测并提示维护，不删除重建积压队列。
- [x] 停机停止新交接、终止恢复任务，有界关闭发布/消费资源，不无限drain或无限生成替代资源；未发允许漏记，未ACK重投去重，未知确认不改称未送达。
- [x] 验证停采继续历史处理、暂停后连接恢复不自启、修复DB后恢复消费，以及启动和关停时MQ阻塞场景；关闭后没有后台任务/许可泄漏。
- [x] 运维说明能够执行消费者暂停/恢复和持续DB故障处理，清楚说明暂停之前仍可能死信/淘汰，生命周期阶段预算不等于进程总截止。

## Comments

- 2026-10-02：用户已确认8张任务的粒度、交付范围和直接阻塞关系。本轮仅发布任务，尚未认领或开始实施；父规格内容和状态保持不变。

## Answer

- 独立 `SHORT_LINK_STATS_CONSUMER_ENABLED` / `short-link.stats.rabbit.consumer-enabled` 默认开启消费；停采只影响新事件与 Cookie。修改后重启的暂停、修复及恢复步骤见 `docs/visit-statistics-operations.md`，没有新增 HTTP 管理接口。
- 保留核心 ready 后后台声明/启动与重试，配置暂停不被 startup 或发布恢复覆盖。声明错误受控观测且不删除队列。
- 发现 Spring CCF `SmartLifecycle.stop()` 会同步调用 `resetConnection()`；新增受控 CCF lifecycle stop，避免框架绕过显式异步关闭 owner。consumer listener terminal close、factory destroy 由单一 daemon worker 执行；重复 DisposableBean destroy 被标记为外部管理。发布仍由单一 cleanup worker 独占清理，最后异步 destroy；不会在 observer/HTTP/caller 同步 reset。
- Recorder 关闭原子停止 admission，标记本地丢失及未确认 unknown，回收每个许可一次，停止启动重试/观察并使用共同 2 秒等待预算。consumer worker、framework executor 与 listener executor 有限；Spring 每阶段预算 2 秒不代表整个进程硬截止。
- 真实隔离依赖：MySQL `short_link_lifecycle_test`、RabbitMQ vhost `link-lifecycle-test`。最终 `VisitCollectionLifecycleTest` 3/3 通过：停采继续历史消费/鉴权查询/清理；已停止 listener 在消费连接 reset 后保持暂停再恢复历史；真实提交后保持 pre-ACK 闩锁，terminal close 关闭实际 delivery channel，broker ready=1，再启动消费者同 eventId DUPLICATE 且 MySQL 仅一行。
- 最近合并 05 Clock 后的其他 focused 测试 7/7 已通过：`AsyncVisitStartupRecoveryTest` 1、`VisitPausedRecoveryTest` 1、`VisitMqShutdownTest` 1、`VisitPublisherFailureTest` 4。shutdown 测试在真实 Spring context close 时控制 native reset 闩锁，验证关闭完成时阻塞依赖仍未释放、交接/未确认资源已停止；不宣称该闩锁证明 TCP write 可取消。
- 新 unACK 测试首次失败源自不稳定的 pre-ACK gap/启动时序；最终明确等初始 consumer 活跃、使用实际 ChannelAware delivery channel，并让 SAVED 后闩锁忽略关闭 interrupt 直到 channel 已关闭及真实 broker requeue，未放松 ready=1 或去重断言。破坏性 listener 生命周期测试各自关闭 context。
- test profile 默认暂停消费，真正 HTTP/MQ 用例显式开启并关闭 context，避免不同 Clock 的缓存测试上下文消费其他事件。生产默认仍开启。继承框架日志使用新增子类名，精确纳入现有安全 dependency encoder。
- 验证边界：未运行 08 全量回归、整体性能测量或最终两轴 review；本票验证阶段预算和真实 unACK 重投，不承诺卡在任意 JDBC/TCP/依赖销毁锁的所有线程立即退出，也不承诺 whole-process deadline。持续 DB 故障分类重试/修复由 04 真实 MySQL 锁测试覆盖，本票另验证暂停后的恢复消费。
