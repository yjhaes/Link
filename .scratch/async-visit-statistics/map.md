# 阶段 6：RabbitMQ 异步访问统计设计讨论

本文件记录 `/grill-with-docs` 访谈的事实、设计树与已确认选择。2026-10-02完成五轮讨论，用户确认Q1～Q15；随后调用to-spec发布规格，并调用to-tickets、确认8张任务拆分。设计和任务发布阶段未修改生产代码、SQL、配置或测试；后续显式授权实施及验收记录见下文。

## 已有约定

- 使用根目录 `CONTEXT.md` 的 single-context 术语及 `docs/adr/` 决策布局。
- [ADR-0006](../../docs/adr/0006-synchronous-visit-statistics.md) 接受同步 best-effort 采集、跳转优先、故障允许漏记；本轮如改变这一契约必须明确记录。
- 访问事件发生于 GET 通过最终逐请求有效性检查并作出正常跳转决定，不证明响应已发送或原始 URL 已打开。HEAD 和跳转拒绝结果不计入。
- MySQL 访问日志是统计权威；范围 UV 按匿名访客摘要及其版本在整个范围去重。统计日为 Asia/Shanghai，查询窗口为含当天的最近 30 个统计日。
- 不因讨论 RabbitMQ 自动改变口径、Cookie、查询权限、映射状态或 Redis 缓存协议。

## 已核验事实

- `ShortLinkController.redirect` 在逐请求 `decide` 后调用 `VisitCollection.collect`，该调用返回后才完成 302 响应构建。
- 当前写入在请求线程中执行。统计专用池最多 4 连接，写入准入为 2；繁忙直接丢弃，失败仍正常跳转，已有分阶段超时及观测。
- 剩余同步成本是已准入请求等待 JDBC、占用 HTTP 线程及共享 MySQL 资源。独立连接池不消除这些成本；现阶段没有证据证明出现生产延迟或吞吐瓶颈。
- 已有 `VisitRecorder` 接缝及冻结的 `VisitEvent`：事件 ID、短码、发生时间、统计日、访客摘要与版本、脱敏网段、清理后的 UA、Referer host。
- `MySqlVisitRecorder.record` 返回 `void` 并捕获失败；直接把它接入监听器会使监听器无法区分保存成功、重复、繁忙、失败和结果不确定，存在错误 ACK 风险。
- 事件唯一键已实现数据库去重。在线清理同时删除去重记录；异步设计必须限制晚到和重放的窗口，防止旧事件在清理后再次入库。
- 当前基线 HEAD 为 `323fb63`；Java 17、Spring Boot 3.5.16，目前无 RabbitMQ/AMQP 依赖或配置。此次没有运行实现测试，测试信息来自只读核验。
- 既有 HTTP/MySQL 测试覆盖统计容量、实际 JDBC 超时、确认丢失、逐请求事件、跨午夜冻结、唯一键重放、关闭采集后继续查询/清理；引入 MQ 后应复用这些回归约束。
- 当前查询一致性快照不等于消息全部消费完成；`generatedAt` 不是消费水位，`collectionEnabled` 不是 MQ 健康状态。
- `docs/visit-statistics-query.md` 原末句“任务 06 的调度清理仍未实现”已陈旧：当前 `VisitCleanupSchedule` 和 `VisitCleanupLifecycleTest` 已存在。完成设计时已修正这句说明，没有修改实现或历史ADR。

事实来源：[采集说明](../../docs/visit-collection.md)、[查询说明](../../docs/visit-statistics-query.md)、当前代码；正式方案需区分既有能力与拟新增能力。

关键代码位置：`ShortLinkController.java:64`、`RedirectService.java:137`、`VisitCollection.java:36`、`MySqlVisitRecorder.java:30`、`VisitRecorder.java:3`、`StatsDataSourceConfiguration.java:23`、`VisitLogCleanup.java:55`。当前统计仅追加日志，不维护额外计数；消费者必须保留既有唯一键去重，不能因阶段 7 将专门讨论幂等，就在阶段 6 假设消息不会重复。

## 设计树

| 分支 | 决策内容 | 前置条件 |
| --- | --- | --- |
| 引入价值 | 为学习及解耦引入，还是以测量结果作为引入门槛；对比保留同步和进程内异步 | Q1 已确认 |
| 故障契约 | 是否延续跳转优先及允许漏记；哪些丢失窗口可接受 | Q2 已确认 |
| 查询时效 | 是否接受异步可见延迟与故障期间没有固定追赶期限 | Q3 已确认 |
| 同步与异步边界 | 核心校验、Cookie、最小化及冻结；发布和日志持久化如何隔离 | Q4 已确认 |
| 产生和发布时点 | 每次有效 GET 产生独立事件，何时交给 Producer；不放入共享缓存加载 | Q4 已确认 |
| 消息契约 | 继承已有事件和隐私边界；必选、可空和禁止字段、编码和 schema 版本 | Q6/Q9/Q13 已确认 |
| Producer 失败 | 发送异常、confirm/return、未确认有界管理、MQ 不可用 | Q7/Q13 已确认 |
| Consumer ACK | 保存结果反馈、ACK、重复及未知提交 | Q8 已确认 |
| 启动和采集开关 | 后台声明/启动、MQ不可用核心仍启动、关闭采集继续消费历史 | Q10 已确认 |
| Consumer 重试 | 错误分类、有限重试、耗尽和数据库大面积故障处理 | Q11已确认 |
| 死信与重放 | 最终拒绝消息的去向；单一DLQ、保留和人工处理；禁止循环重放 | Q12已确认 |
| 积压与资源 | 有界缓冲、prefetch、并发、队列容量、年龄和超窗丢弃、告警及降级 | Q13已确认 |
| 测试 | 口径回归、真实 broker 路由和确认、DB 提交/ACK 间崩溃、积压及启动故障 | Q14已确认 |
| 面试及可靠性分级 | 必须实现、可以实现、只需要理解；端到端可靠性边界及取舍 | Q15已确认 |

## 第一轮已确认

用户回复“全部按推荐”，确认如下。

- Q1：以有限规模的学习项目及隔离统计写入作为引入 RabbitMQ 的理由，选择最小实现；不宣称已由生产故障或压测证明必须引入。后续实施通过故障测试及延迟对比评估收益。
- Q2：延续跳转优先、允许故障漏记，包括发布失败、本地缓冲满及发布前进程退出；不承诺每次访问都可靠入账。后续可靠性机制围绕此契约分级。
- Q3：查询读取已经消费落库的日志，正常运行以数秒可见为初始目标；积压或故障期间无固定延迟保证。保留既有上海统计日和最近 30 日窗口，不承诺每次 302 后立即读到新增事件。

## 第二轮已确认

用户回复“全部按推荐”，确认如下。

- Q4：同步保留核心跳转判定、匿名 Cookie 识别/响应、HMAC、元数据最小化及事件 ID/发生时间/统计日冻结。在作出跳转决定后、返回 302 前，只进行立即成功或失败的本地有界交接；实际网络发布及数据库写入在后台执行。HTTP 不等待连接、发送、confirm 或消费，不使用调用线程执行的拒绝策略，不依赖响应发送完成回调采集。
- Q5：只增加单节点 RabbitMQ，不拆微服务；当前 Spring Boot 应用内独立统计监听器使用统计连接池落库。业务拓扑为一个 durable direct exchange `shortlink.visit.x`、一个 durable classic queue `shortlink.visit.stats.q`、精确 routing key `visit.occurred.v1`。非独占、非自动删除；消息持久化；发布和消费分离连接。DLQ 是否增加留到消费失败策略讨论。
- Q6：明确 UTF-8 JSON 消息契约，继承当前九个事件字段并增加 `schemaVersion=1`；UUID 为文本、UTC 毫秒时间为 ISO-8601、统计日为 YYYY-MM-DD、32 字节访客摘要使用规范 Base64。网段、UA、Referer host 可空，其余必填。不传原始 URL、原始 Cookie/完整 IP/完整 Referer、密钥令牌、请求对象/整组头、映射实体/缓存状态、数据库自增 ID、预计算 PV/UV；重试沿用同一个事件 ID 及冻结数据。

## 第二轮补充事实

- direct 为 binding key 精确匹配，一个 key 本身可以绑定多个队列；当前设计只绑定一个业务统计队列。队列中的多个消费者是竞争消费。
- durable 队列与持久消息分别约束队列元数据和消息恢复；classic 在 RabbitMQ 4.x 不复制，不能把单节点方案描述为高可用。
- Spring AMQP 3.2 支持独立 publisher connection，默认未启用。无需为了分离连接手工重建全部 Boot 自动配置，后续实施需明确定制并验证。
- 明确 JSON 转换与消息 DTO，不能直接依赖默认 SimpleMessageConverter 或以 Java 类名消息头充当 schemaVersion；消费者只解析预期类型。
- 同应用 listener 属于职责及线程隔离，仍共享进程、主机和 MySQL。非阻塞交接指不等待缓冲容量、网络或确认，不承诺 Java 调用零等待或严格 HTTP 墙钟上限。
- Boot 3.5 默认 simple listener 的 autoStartup=true、missingQueuesFatal=true；只依赖默认配置可能增加启动连接等待或启动失败风险。需要区分“不因MQ失败而取消核心启动”和“不在核心启动线程等待MQ”。
- Spring AUTO仅在同步listener正常返回后确认；NONE才是RabbitMQ autoAck。默认错误处理器/重试recoverer可能打印消息和异常原文，后续实施必须沿用项目受控错误日志约束。

## 第三轮已确认

用户回复“全部按推荐”，确认如下。

- Q7：开启 correlated publisher confirms 和 mandatory/returns。发送异常、nack、return、确认超时分别观测；发送调用返回不视为 broker 已接受，confirm ACK 也不视为 MySQL 落库，confirm ACK 加 return 不能视为路由成功。确认超时记为结果不确定，不能断言未送达。不在 HTTP 等确认，不为失败事件自动重投，不同步回退 MySQL；恢复连接仅用于接收后续事件，不构成历史补采。缓冲和未确认事件都有限，具体预算在容量轮确定；回调不阻塞、不抛向 HTTP、不输出消息体或秘密。
- Q8：使用 Spring AMQP AUTO（容器在同步 listener 正常返回后确认），不用 NONE。消费者持久化边界明确返回已确认保存/仅eventId重复，或传播繁忙、失败及未知提交；不直接使用吞异常的 void recorder。只有保存确认或合法重复才走处理成功路径；执行前失败和提交不确定不能普通返回后被误ACK。确认保存后的资源关闭失败保留已保存结果。数据库提交与MQ ACK之间崩溃仍可能重复，因此复用数据库eventId唯一键。MANUAL可作为理解或后续练习，不是当前必要复杂度。
- Q9：校验schemaVersion、必填字段/格式、摘要长度、现有元数据长度与最小化约束，以及statDate与occurredAt的上海日期一致性。有效迟到/乱序消息按冻结时间落库，不因消费时映射已禁用或过期而改变历史访问口径。已经早于当前30日窗口的合法事件不写库，明确记expired并ACK丢弃，不送回业务队列；不能让清理后重放重新创建旧日志。人工重放同样遵守该规则；不承诺无限期幂等。恶意/损坏/未知schema消息的拒绝和DLQ策略下一轮确定。
- Q10：MQ不可用不成为新增的核心启动硬依赖；声明与listener启动在应用启动后由后台执行，失败时核心仍能启动/跳转，后台有间隔地恢复连接。静态非法配置仍按配置错误处理，同名队列属性冲突需维护者修复，不自动删除重建。采集开关只控制新事件及新Cookie产生，关闭后仍消费已进入MQ的事件，历史查询和日志清理继续；不提供自动切回同步统计或停采期间补采。

## 第三轮补充事实

- 本轮不修改AUTO的语义：listener同步完成数据库处理；不在listener里交接另一异步任务后提前返回。拟使用单条处理和batchSize=1，避免引入批量确认与部分失败语义。
- 普通listener异常默认可能requeue；必须显式有限重试和耗尽拒绝，不靠默认配置声称已有重试上限。
- stateless重试仅限制当前执行轮次，maxAttempts包含首次调用。崩溃/连接关闭后未ACK消息可重投并开启新一轮；classic没有跨投递的delivery-limit，x-death也不是普通requeue计数。
- 重试分类应检查异常cause；格式/转换/自定义schema校验不能因默认retry-all被错误反复重试。默认recoverer可能吞掉或打印Message，必须明确耗尽后的broker拒绝及受控日志。
- 有限重试不阻止数据库整体故障时主队列逐条搬向失败去向，需要明确持续故障时的暂停消费和恢复运维步骤。

## 第四轮已确认

用户回复“全部按推荐”，确认如下。

- Q11：暂时性数据库错误（取连接/连接超时、断连、锁等待、死锁、写准入繁忙、提交不确定）每个执行轮次最多3次，包含首次；两次重试前分别等待200ms/500ms，仅占消费线程，同一eventId和冻结数据。格式/转换/schema/永久约束或SQL错误直接拒绝，不重试。耗尽后拒绝且不requeue；无法据此声称事件跨重启生命周期只处理3次。不设计多级延迟队列或自动无限重试。持续数据库故障时告警并由维护者暂停独立消费者、恢复数据库后再启动，自动故障断路/探测恢复可作为后续优化。
- Q12：为最终拒绝的格式/永久错误及重试耗尽消息增加一个durable direct DLX `shortlink.visit.dlx`，精确key `visit.failed.v1` 绑定durable classic DLQ `shortlink.visit.stats.dlq`，无自动消费者或回主队列的DLX。DLQ用于受限排查；修复后按需人工重放原eventId/冻结数据，仍须符合30日窗口。DLQ最多1000条或4MiB消息体、TTL24小时、drop-head保留较新样本；容量/TTL可能丢弃，普通classic死信转移本身也可能丢失，不能作为可靠保管保证。无无限保留或自动回放基础设施。
- Q13：可配置的容量起点：本地待发缓冲256个事件、发布worker=1、待发最长5秒；未确认最多32个、确认观察期限5秒；发布channel最多16个、获取等待200ms；消费concurrency=1/prefetch=10/batchSize=1，沿用统计池最多4连接及原查询/清理配额。业务队列最多10000条或16MiB消息体、overflow=reject-publish、message TTL=24小时；单消息16KiB。满时拒绝新事件并观测，TTL是队列缓冲预算而非MySQL的30日查询窗口。建连/channel获取/握手/心跳与confirm期限分别约束，不虚构总publishTimeout；超时处理需回收框架关联状态及故障发布连接，避免仅清自己的map而留下资源。观察/恢复机制必须独立于可能阻塞的sender，且只重建publisher子连接，不reset主连接影响消费；恢复不自动重投失败事件。所有数字为起点，不是压测证明的吞吐或SLA。
- Q13积压处理建议：监测ready/unacked、消费速率、已处理事件的发生至落库延迟、本地最老待发年龄、本地满/过期待发、confirm未知、失败/重试/死信及broker资源告警；不得把已消费事件延迟当作broker精确最旧事件年龄或完整消费水位。积压先查consumer和数据库，再有限增并发（需核验全实例DB预算），不无限增缓冲；依故障程度暂停消费或停止新采集，核心跳转继续。观测不以短码/访客/IP作标签，不打印消息体/密钥/驱动原文，不新增监控平台或公开运维HTTP接口。

## 第四轮补充事实

- Queue的max-length/max-length-bytes只计ready消息，unacked不计入；字节限制只计body，不含header、属性和存储开销，不能视为broker总内存/磁盘硬上限。
- reject-publish在confirm启用时返回nack，不因设置DLX而自动把新拒收消息送入DLQ；本方案不使用reject-publish-dlx。
- TTL按每个队列驻留时间计；主队列过期经DLX进入DLQ后再按DLQ自己的TTL计时，所以两个24小时不能描述为总24小时保留。TTL也不保证到时立刻释放物理存储。
- 5秒confirm观察期限不是publish调用总截止；basicPublish受背压阻塞时，该sender线程无法自己执行超时恢复。观察任务必须独立，关闭/重建连接行为需要真实故障验收。
- CachingConnectionFactory主实例reset会连带影响publisher子连接与消费者；恢复只针对独立publisher连接，不把发送故障传播成消费重启。
- 本地待发年龄以交接时的单调时间判断，超过5秒就不再开始发送；不能声称卡住的实体会在第5秒物理消失。确认超时、关闭产生的framework nack与迟到回调可能竞争，每个尝试只能结算一次并只释放一次许可。
- 每次持久化重试都重新检查当前窗口，不能复用跨午夜的过时准入；执行/提交等待跨午夜时可能留下物理超窗日志，由既有清理追赶，查询仍严格按当前30日窗口限制。
- 3次重试的200ms/500ms是间隔，另有实际DB及调度耗时，不等于整次消费最多700ms。人工暂停前仍可能持续死信并淘汰样本，这属于已接受的best-effort边界。

## 最后一轮已确认

用户再次回复“全部按推荐”，确认Q14/Q15。完整技术方案、测试与实施分级见 [异步访问统计设计](../../docs/async-visit-statistics.md)，已标记accepted；设计树所有分支已确认，不开启实现或创建实施任务。

- Q14：以HTTP响应、真实RabbitMQ路由/确认与MySQL日志为主要验收边界，复用已有口径/隐私/查询/清理/Redis回归；验证HTTP不等待发布、满缓冲与MQ启动/运行故障仍跳转、confirm/return/unknown竞态及仅重建发布连接、消费失败不误ACK、有限重试和DLQ、DB提交后ACK前崩溃的去重、迟到/跨日/超窗重放、容量/TTL/prefetch、关闭采集继续消费以及有界关停。异步结果用可控时钟/闩锁和有界最终等待，不依赖长sleep或实现私有方法；少量真实故障证明背压和超时阶段，其余故障可用用例边界替身。做同条件的同步基线/异步版本延迟和漏记/处理延迟对比，不为比较而保留生产双路径，不宣称固定SLA。本次只确认测试设计，不编写/执行测试。
- Q15：已确认方案的事件冻结与最小化、有界交接/发布恢复、最小durable拓扑/持久消息、confirm与returns、消费结果反馈/AUTO ACK、既有唯一键、有限分类重试/单DLQ、容量TTL/过期处理、后台启动/有界关停、基础观测和人工暂停运维为必须实现；受控重放工具、批量落库、测量后的有限并发优化、自动暂停/探测恢复和更完整指标接入为可以实现且不纳入当前验收；outbox/本地消息表、MQ/分布式事务、quorum集群、多级延迟重试、跨重启累计投递限制/永久去重和端到端恰好一次边界只需要理解。面试以实际问题和取舍、三个成功边界、AUTO/NONE、提交与ACK间隙、eventId与客户端新访问、背压/积压、冻结时间/隐私和为何不做outbox为重点。停止在设计文档，不写代码，不创建实施任务。

## 官方资料核验

- [RabbitMQ confirms](https://www.rabbitmq.com/docs/confirms)：publisher confirm 与 consumer ACK 覆盖不同边界；confirm 不表示数据库落库。异步 confirm 不代表 publish 调用本身必然不阻塞。
- [RabbitMQ alarms](https://www.rabbitmq.com/docs/alarms)：资源告警会阻塞发布连接，应隔离发布对跳转线程的影响。
- [Spring AMQP ACK 模式](https://docs.spring.io/spring-amqp/docs/current/api/org/springframework/amqp/core/AcknowledgeMode.html)：AUTO 是容器按监听器正常返回或异常处理 ACK/NACK，区别于 RabbitMQ autoAck；MANUAL 由应用发送 ACK/NACK。
- [RabbitMQ DLX](https://www.rabbitmq.com/docs/dlx)：死信不是无限重试服务，默认转移不等于保证不丢；其可靠性边界须如实说明。
- [RabbitMQ queue limits](https://www.rabbitmq.com/docs/maxlength)：可以限制队列长度/字节并明确溢出策略。
- [Spring AMQP 恢复与重试](https://docs.spring.io/spring-amqp/reference/amqp/resilience-recovering-from-errors-and-broker-failures.html)：消费者重试和连接恢复是不同机制，不能用无限立即 requeue 代替错误处理。
- [RabbitMQ Exchanges](https://www.rabbitmq.com/docs/exchanges)、[Queues](https://www.rabbitmq.com/docs/queues)、[Classic Queues](https://www.rabbitmq.com/docs/classic-queues)：精确路由、队列和消息持久性、classic非复制边界。
- [3.2.12 容器配置文档源](https://raw.githubusercontent.com/spring-projects/spring-amqp/v3.2.12/src/reference/antora/modules/ROOT/pages/amqp/containerAttributes.adoc)、[异常处理文档源](https://raw.githubusercontent.com/spring-projects/spring-amqp/v3.2.12/src/reference/antora/modules/ROOT/pages/amqp/exception-handling.adoc)：AUTO/NONE、autoStartup、missingQueuesFatal与转换异常在listener前发生的边界。

## 已确认决策

- Q1～Q15已确认，见五轮记录，没有未定设计分支。
- [ADR-0007](../../docs/adr/0007-rabbitmq-visit-statistics.md)及[完整设计](../../docs/async-visit-statistics.md)已接受；根CONTEXT.md补充“已记录访问事件”定义，区分事件发生和成为统计依据。
- ADR-0006的统计业务口径继续有效，后续实施以ADR-0007替换请求内同步采集边界。当前代码仍同步，本次仅完成设计，无实施任务或代码改动。
- 用户随后显式调用`to-spec`，已将已确认设计合成为[完整实施规格](spec.md)，按本地任务跟踪约定标记`Status: ready-for-agent`。只发布规格，不拆子任务、不认领或执行实施；测试接缝沿用Q14已确认的HTTP/真实MQ/MySQL及现有统计提交边界。

- 用户随后调用`to-tickets`并回复“确认”，已按[任务索引](ticket-plan.md)发布8张独立任务，初始状态均为ready-for-agent。依赖为01无、02←01、03←02、04←02、05←04、06←03/04、07←06、08←05/07；当前可执行前沿只有01。父规格内容和状态保持不变，没有认领或开始实施。

- 2026-10-02：用户显式调用 implement-spec，开始已确认八张任务的实施；历史的仅发布/不实施范围不再限制本轮授权。集成分支为 codex/async-visit-statistics，固定实现基线为 8a3d7eb。
- 已完成并合入 [01 持久化结果](issues/01-visit-persistence-outcomes.md)、[02 异步闭环](issues/02-rabbitmq-visit-roundtrip.md)、[03 发布故障](issues/03-publish-failure-and-backpressure.md)。验收证据和可复现同步基线见 [实施验证](verification.md)。
- 实施阶段沿用当前模型；最终 Standards Review 和 Spec Review 明确使用 GPT-6.1 Sol / high。

## 已作决定：任务 04 实施结果

- [04](issues/04-consumer-retry-and-dead-letter.md) 已完成：同步消费仅明确保存/事件重复 ACK；受控暂时 cause 本轮三次与 200/500 ms；永久/格式失败或耗尽直接拒绝且不 requeue；单 durable classic DLQ、精确死信路由及 policy 运维文件。
- 真实 MySQL/RabbitMQ 验证提交后消费连接在 ACK 前强制中断，重新投递由原 event 唯一键维持单行；其他唯一键及 CHECK 错误不误吞。独立资源为 short_link_consumer_test / link-consumer-test。
- 共享日志用 console 安全编码保留每条依赖事件与 WARN/ERROR、logger、线程和时间，替换原始 JDBC/AMQP 文本与异常为固定类别；明确包含核心池，未关闭日志。新故障/金丝雀/池隔离矩阵 14 项全部通过，合并发布恢复集成 tip e813f06 后重跑仍全部通过。
- 后续 06 将容量/TTL扩展到同一主队列 policy，防止较高优先级普通 policy 覆盖 DLX；05 为每次 persist 加窗口检查；07 复核运维和 logging 范围。

- 2026-10-02：已合入 [04 消费重试与死信](issues/04-consumer-retry-and-dead-letter.md)、[05 迟到事件与窗口](issues/05-late-visits-and-retention-window.md)、[06 独立采集/消费生命周期](issues/06-collection-and-consumer-lifecycle.md)。06 合并提交 4a3f4c5，针对性10项测试通过。
- 用户最新要求为06完成后暂停；已停止执行，07、08及最终双轴审查留待续接。[续接记录](resume.md)保存测试环境、已有证据和GPT-6.1 Sol/high审查要求。

- 2026-10-02：用户回复继续，恢复07及后续实施/整体验收/最终审查；已启动此前保留的隔离测试设施。

- 2026-10-02：[07 积压观测与资源预算](issues/07-backlog-observability-and-budgets.md)已完成；分层事件/发布/DB尝试和消费延迟速率、固定投影broker观察、同一主policy完整配额/TTL及真实缩小broker/持续DB故障暂停恢复验证。21项针对性测试通过，正值延迟断言再验证8项通过。预算/运维解释保留best-effort、独立TTL和非水位边界；08全量回归/同环境测量与双轴审查尚未执行。

- 2026-10-02：07、08及最终审查修正全部合入。完整回归两轮各259项通过；GPT-6.1 Sol/high独立Standards与Spec审查分别为2项P3维护性建议、0项发现，建议已全部修正并以51项针对性回归验证。代码合并9b33501，父规格和八张任务全部resolved。详见[验证](verification.md)与[审查](code-review.md)。
