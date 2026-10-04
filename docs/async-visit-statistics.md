---
status: accepted
accepted_date: 2026-10-02
---

# 阶段 6：RabbitMQ 异步访问统计设计

2026-10-02通过`/grill-with-docs`完成五轮讨论，用户已确认Q1～Q15及完整设计，随后授权实施。本文保留设计理由和能力分级；可以实现与只需要理解项不是当前能力。

## 是否值得引入

本阶段选择引入最小RabbitMQ方案，理由是隔离统计落库等待、缓冲短期访问尖峰，并形成可解释的发布/消费失败处理实践。没有生产压测或故障资料证明当前系统必须使用MQ，不能把学习项目描述为已经解决了实际百万流量瓶颈。

实施前同步统计已具备统计专用池、写准入、分阶段超时、失败仍302和eventId唯一键，不能称为完全没有故障隔离。实施前的实际架构成本是：被准入的请求仍在HTTP线程等待JDBC；写容量满时直接漏记；统计与核心仍共用MySQL硬件。Redis命中免去映射查询，不会免去实施前同步统计写入。

RabbitMQ使HTTP不用等待数据库日志写入，短期输入超过消费速率时可以在broker缓冲。它不会提高MySQL持续写入能力，也不会自动隔离发布网络阻塞；长期输入大于消费能力，积压仍会增长。新增成本包括一个broker、异步可见延迟、重复投递、故障处理和维护。

保留同步方案的运行成本最低，只有进程内异步也能移除请求内JDBC等待；后者的待处理事件完全依赖应用进程，不能替代独立broker缓冲。项目选择RabbitMQ是当前明确的学习与职责解耦目标，并接受这些成本，不引入复杂消息基础设施。

## 业务契约与边界

- 访问事件仍发生于每个GET通过最终逐请求有效性检查、作出正常跳转决定之时；不是证明响应已发送或目标网站已打开。HEAD、拒绝结果、创建及查询不计访问。
- 缓存命中、未命中和共享数据库加载的等待者都逐请求产生自己的事件。不得把Producer放进缓存加载/回填或共享任务内部。
- 跳转优先，统计为best-effort。采集失败、缓冲满、发布失败或不确定、发布前进程退出及队列容量/TTL淘汰，都允许漏记，不将正常302改成错误。
- 不把异常或未知提交描述为一定没有执行，不承诺端到端不丢、恰好一次或无限期去重。
- MySQL访问日志继续是统计权威。查询只统计已落库事件；正常以数秒可见作为起始目标，故障/积压没有固定延迟保证。
- 保持单短码维度、匿名Cookie UV、上海统计日、最近30个统计日查询窗口、管理令牌与日志最小化。范围UV对整个范围去重，不能相加每日UV。

## 同步与异步职责

| 请求内同步完成 | 后台完成 |
| --- | --- |
| 短码解析、映射解析、最终状态/过期校验、302目标地址 | JSON编码和RabbitMQ网络发布 |
| 判断采集开关及请求方法 | broker确认/return/发送结果观测 |
| 匿名Cookie识别/生成、响应Cookie构建 | 消费消息校验与持久化 |
| HMAC、元数据最小化、独立eventId及发生时间/统计日冻结 | 有限消费重试、死信路由及资源恢复 |
| 尝试立即交给有界缓冲，无容量则跳过 | 既有日志清理继续独立运行 |

核心`RedirectService`保持现有决定边界，不知道Exchange、Queue、ACK或重试。Web入口沿用`VisitCollection`提取HTTP数据，后续在统计提交边界替换同步写法；消息适配、消费和持久化职责集中在`stats`功能，不增加通用消息平台或大量透传接口。

流程为：逐请求正常跳转决定→同步冻结事件→尝试本地交接→返回302；已交接事件由后台Producer→RabbitMQ→统计Consumer→MySQL。实际发送可以早于或晚于HTTP响应返回，不依赖响应发送完成回调。

HTTP不等待可用缓冲、建连、借channel、发送、confirm或消费；不使用CallerRuns等让请求线程接手发送的拒绝策略。Java采集、摘要和短临界区仍有成本，此处不承诺零等待或严格HTTP总墙钟截止。

已独立构建的合法新Cookie可以随302返回，即使事件未能交接或发布；不因为统计落库失败重建共享访客身份。采集关闭则不生成新事件或统计Cookie。

## VisitEvent消息契约

采用明确的UTF-8 JSON协议与预期DTO，不使用Java原生序列化，也不以Java类名消息头替代协议版本。

| 字段 | 类型与约束 | 意义 |
| --- | --- | --- |
| schemaVersion | 必填整数，第一版1 | 消息格式版本 |
| eventId | 必填UUID文本 | 一次事件身份；内部重试/重投/重放保持不变 |
| shortCode | 必填，现有4～8位字母数字规则、大小写敏感 | 访问的短码 |
| occurredAt | 必填ISO-8601 UTC时间，毫秒精度 | 从最终决定时刻冻结，不在后台重读钟替换 |
| statDate | 必填YYYY-MM-DD | occurredAt对应的Asia/Shanghai自然日 |
| visitorHash | 必填规范Base64，解码32字节 | 按短码隔离的HMAC摘要 |
| visitorKeyVersion | 必填整数，1～65535 | 摘要密钥版本，与schemaVersion不同 |
| peerIpNetwork | 可空，沿用最多49字符及IPv4 /24、IPv6 /48脱敏规则 | 对端网段，不接受代理头作为可信身份 |
| userAgent | 可空，控制字符清理、最多512个Unicode字符 | 最小化UA |
| refererHost | 可空，合法规范化host、最多253字符 | 不包含路径、参数或用户信息 |

单消息UTF-8 JSON体上限16KiB，元数据缺失用null。Producer完成最小化；Consumer仍校验schema、必填、格式、长度、摘要长度及日期一致性，只解析预期事件类型。采集最小化并不使访客摘要和网段成为完全无须保护的数据。

消息不包含原始URL、原始Cookie、完整IP、完整Referer、整组请求头、管理令牌、HMAC/连接秘密、Servlet请求或响应、映射实体、缓存状态或版本、数据库自增日志ID、预计算PV/UV。消费者无需原始Cookie/密钥来重新识别访客，也不应重新判断历史跳转是否有效。

客户端再次GET是新的访问事件，生成新eventId；同一事件的内部处理重试沿用原ID及原数据。数据库自增ID在落库时产生，只用于已有明细排序，不能代替事件身份。

一个Spring Boot应用及一个RabbitMQ节点；逻辑上独立统计监听器，不拆统计微服务。Producer与Consumer分离连接，消费者继续使用统计专用数据库池；同进程/主机/MySQL仍有共享资源边界。

| 实体 | 名称 | 配置 |
| --- | --- | --- |
| 业务Exchange | shortlink.visit.x | direct、durable |
| 业务Queue | shortlink.visit.stats.q | classic、durable、非独占、非自动删除 |
| 业务Binding | visit.occurred.v1 | 精确binding key，只绑定上述业务队列 |
| 死信Exchange | shortlink.visit.dlx | direct、durable |
| 死信Queue | shortlink.visit.stats.dlq | classic、durable、非独占、非自动删除 |
| 死信Binding | visit.failed.v1 | 业务队列的dead-letter-routing-key |

业务队列的DLX及dead-letter-routing-key指向上述死信路由。DLQ不设置回业务队列的DLX，不运行自动回流消费者。direct的点号没有topic通配意义，也不天然代表单消费者；同一队列的多个监听器是竞争消费。[RabbitMQ Exchange说明](https://www.rabbitmq.com/docs/exchanges)

消息设置持久化；durable元数据与持久消息降低broker正常重启时丢失风险，但classic在RabbitMQ4.x不复制。单节点、磁盘永久故障及broker故障仍可丢数据，不构成高可用承诺。[Queue说明](https://www.rabbitmq.com/docs/queues)、[Classic说明](https://www.rabbitmq.com/docs/classic-queues)

容量、TTL和DLX等适合变化的属性优先用受控policy管理，类型等固定身份由声明明确。不假定同名队列可以用不同参数重声明以动态修改配置；不匹配时观测并维护修复，不自动删除包含积压的队列。选择policy不增加新的业务队列或服务。

## Producer成功、失败和恢复

采用correlated publisher confirms、mandatory和returns。一次发布的状态不能只由`convertAndSend`正常返回判断，必须区分发送尝试、broker接受/路由与数据库保存这三个边界。

| 观察结果 | 处理 |
| --- | --- |
| 本地交接失败/无容量 | 跳过事件，记录本地丢弃；保持302 |
| 超过本地待发年龄 | 不再开始发送，记录丢弃 |
| JSON编码失败/消息超限 | 记录受控失败，不发送，不抛向HTTP |
| 发送异常 | 记录失败或结果不确定；发送阶段不能一概断言未送达 |
| return | 无可匹配队列等路由失败，不因随后confirm ACK当作成功 |
| nack | broker未承担此发布责任；不自动重投 |
| confirm ACK且无return | 此次发布得到broker确认，不表示Consumer/MySQL成功 |
| 观察期限内没有可靠确认 | 标记结果不确定，不能断言消息未送达 |

publisher confirm与consumer ACK覆盖不同边界。未路由消息也可能得到confirm ACK；mandatory/returns才帮助识别无法路由。持久confirm需满足相应持久条件，但没有立即确认的硬保证。[RabbitMQ确认说明](https://www.rabbitmq.com/docs/confirms)、[Spring AMQP3.2发布说明](https://docs.spring.io/spring-amqp/reference/3.2/amqp/template.html)

不在HTTP等待或重试；失败/不确定事件不自动补发，也不同步回退MySQL。同步回退会把数据库等待重新带回核心路径，且未知投递与回退可能同时成功。恢复连接只为处理后续仍符合预算的事件，不构成历史补采。

本地缓冲、未确认关联状态和channel均设限。关联状态在每个发布尝试上只终结一次，超时、return、连接关闭产生的framework nack及迟到回调不能重复计数或重复释放许可。回调轻量、无业务阻塞，不打印消息体或秘密。

确认观察/恢复任务独立于可能阻塞的sender。超时不能只删自己的map而留下框架pending confirm及channel；故障恢复仅回收/重建publisher子连接，不reset消费主连接，并接受该发布连接其他在途消息结果可能不确定。恢复动作最多一个，关闭未完成时不无限创建替代worker、线程或连接，暂停发送并继续按有界缓冲降级。关闭行为需要真实背压/故障测试验证，不把future超时当成取消publish。

建连、握手、channel取得、心跳和confirm观察是不同阶段，分别设置有限预算并核验实际效果；这里不虚构普通端到端publishTimeout。[RabbitMQ资源告警说明](https://www.rabbitmq.com/docs/alarms)、[AMQP3.2连接管理](https://docs.spring.io/spring-amqp/reference/3.2/amqp/connections.html)

## Consumer持久化和ACK

使用同步listener与Spring AMQP AUTO、batchSize=1。AUTO由容器在listener正常返回后ACK；NONE对应RabbitMQ无须等待处理完成的autoAck，本阶段不用NONE。MANUAL不是业务成功后确认的唯一实现方式。[AMQP3.2.12容器配置](https://raw.githubusercontent.com/spring-projects/spring-amqp/v3.2.12/src/reference/antora/modules/ROOT/pages/amqp/containerAttributes.adoc)

`stats.persistence.MySqlVisitPersistence.persist` 只承担保存确认：明确返回已保存/仅 eventId 重复，或传播繁忙、失败及提交不确定。旧同步 record 入口已移除；HTTP 使用 VisitRecorder 本地异步交接，消费持久化失败不能静默正常返回。

| 消费结果 | 处理 |
| --- | --- |
| 已确认保存 | 成功返回，容器ACK |
| 仅uq_visit_event重复 | 已处理，成功返回，日志/PV不增加 |
| 合法但已经超出保留窗口 | 不写库，记expired，成功返回并ACK丢弃 |
| 暂时性持久化错误或提交不确定 | 抛出可分类错误，有限重试；耗尽后拒绝 |
| 格式/schema/永久错误 | 拒绝、不requeue，走死信路由 |

只有事件唯一键重复可按已处理确认，其他约束或SQL错误不能吞成重复。保留当前“确认保存之后资源关闭失败不抹掉SAVED”的语义。listener不得先提交另一个异步落库任务就正常返回。

数据库提交后、MQ ACK送达前进程退出会造成重投；复用既有eventId唯一键使同事件日志至多一条，查询PV不重复。当前只追加访问日志，不增加计数表或Redis统计双写；若未来维护汇总，去重及汇总变更须在同一数据库事务内完成，另行设计。本文不承诺消息只投递一次。

## 分类重试、DLQ和人工暂停

暂时性连接/取连接超时、断连、锁等待、死锁、写准入繁忙及提交不确定，每轮最多执行3次（首次＋2次重试）；重试前分别等待200ms、500ms。同eventId、同冻结数据，只占消费线程；这是间隔，不是消费总耗时最多700ms。

格式、转换、未知schema、字段校验、非事件重复的永久约束及永久SQL错误不重试。分类须检查异常cause，不能把所有异常交给默认retry-all。耗尽必须拒绝且不requeue；不能让recoverer吞错误、导致AUTO误ACK成功，也不能依赖默认普通异常无限requeue。[AMQP3.2.12重试说明](https://raw.githubusercontent.com/spring-projects/spring-amqp/v3.2.12/src/reference/antora/modules/ROOT/pages/amqp/resilience-recovering-from-errors-and-broker-failures.adoc)

stateless上限只覆盖当前执行轮次，未ACK的消息在崩溃或断连后可以开始新一轮；classic没有quorum式跨投递delivery-limit。x-death记录死信，不是普通requeue或本地重试计数。[确认与重投说明](https://www.rabbitmq.com/docs/confirms)、[死信说明](https://www.rabbitmq.com/docs/dlx)

DLQ保管最终拒绝及主队列TTL到期的消息供排查，不自动消费回流。普通classic DLX转移本身不保证不丢，目标不可用时也可能损失；容量/TTL淘汰同样是可接受丢失边界。它不是持久补偿平台。[RabbitMQ DLX可靠性边界](https://www.rabbitmq.com/docs/dlx)

人工处理先修正故障，限制选取范围，检查事件仍在30日窗口；重放保留eventId和冻结业务数据，不为重新入账修改时间/统计日。不会在本阶段编写自动重放工具。若使用人工读取后重新发布，确认新路由被broker接受后再移除原DLQ记录；未知确认不能声称完成，可能重复仍由唯一键保护。

系统性 DB 故障时，有限重试不会自动止损。当前已实现独立消费者启用配置，人工暂停、恢复和必要时停采的步骤及预取/淘汰边界见 [统计运维](visit-statistics-operations.md)。后台恢复尊重人工暂停意图；不新增自动断路器、分布式协调或公开运维 HTTP 接口。

## 容量、TTL和积压

下表是可配置起点，不是已压测的吞吐或SLA。

| 位置 | 起始预算与策略 |
| --- | --- |
| 本地待发 | 256个事件，1个发布worker；满则跳过 |
| 本地年龄 | 交接时使用单调时间；等待超过5秒，不再开始发送 |
| 未确认发布 | 最多32个关联状态，确认观察5秒 |
| 发布channel | 最多16个，获取等待200ms；明确限制活跃channel，不仅设置缓存大小 |
| Consumer | concurrency=1、prefetch=10、batchSize=1 |
| 统计池 | 沿用最多4连接及写2/查询1/清理1配额；当前消费并发1 |
| 业务Queue | ready最多10000条或16MiB消息体，先到者生效；reject-publish |
| 业务message TTL | 队列驻留24小时 |
| DLQ | ready最多1000条或4MiB消息体，先到者生效；drop-head |
| DLQ message TTL | 在DLQ中驻留24小时，无DLX自动回流 |
| 单消息体 | UTF-8 JSON最多16KiB |

业务reject-publish导致新发布被拒绝，confirm可报告nack；不会因为配置DLX就把拒收的新消息送到DLQ，本方案不用reject-publish-dlx。容量只统计ready，unacked不计入；bytes只计消息体，不包含headers/属性和存储开销，不能当broker总内存/磁盘硬上限。[队列容量说明](https://www.rabbitmq.com/docs/maxlength)

MQ的24小时是传输缓冲预算，MySQL的30个统计日是查询和日志清理窗口。主队列到期后经DLX进入DLQ，再受DLQ自身24小时约束，可能合计接近48小时；TTL也不保证精确到时物理删除或清理已经unacked的事件，消费窗口检查仍需要独立执行。[TTL说明](https://www.rabbitmq.com/docs/ttl)

积压先看consumer是否在运行、DB错误/耗时、资源告警、ready/unacked及消费速率。有限提高并发前核验统计池和所有实例的总DB负载，不无限增加缓冲/prefetch。持续输入高于处理能力时，需要提高真实处理能力、减少采集或接受淘汰；队列不会消除这个差值。

已处理事件的发生至落库延迟、本地最老待发年龄可辅助观察；不宣称它们是精确的broker最旧事件年龄或完整消费水位。未确认状态只观测发布边界，不能据此声称统计完整。

## 迟到、乱序和保留窗口

有效事件按原occurredAt/statDate落库，不按消费时间分桶；消费者不因映射现在已过期/禁用而拒绝过去合法发生的访问。统计日必须与发生时间的上海日期一致。

每次真正持久化尝试前检查当前上海日及其前29日窗口，重试跨午夜须重新检查；已超窗的合法事件记expired并ACK丢弃，不重新插入清理后的旧日志。未来统计日不属于当前窗口，按不合法时间数据拒绝进入死信路由，不用消费时间改正事件；先排查生产者时钟和消息来源。消息刚入队或DLQ并不能使业务时间变新，人工重放同样适用。执行/提交等待跨午夜可能留下物理超窗日志，由既有尽力清理追赶；查询始终严格限制窗口，不承诺任何瞬间都不存在物理超窗行。

清理会删除事件唯一记录，所以不承诺无限期幂等。保留窗口内的同一事件由唯一键保护，窗口外不再接收入库；未来若要求长期重放/历史补采，须重新设计去重和保留，不在本阶段新增永久去重表。

## 启动、开关及停机

MQ声明/监听器启动在核心已启动后后台执行，MQ不可用时核心仍能启动和跳转；后台间隔恢复连接。仅把missingQueuesFatal设false不证明核心启动从不等MQ，不能直接照搬默认auto-startup行为。静态非法配置仍应失败；认证/队列属性不匹配要观测并人工修复，不自动删除重建。

新事件采集开关保持原义：关闭时不生成统计事件/Cookie，不影响已在MQ中的事件消费、历史查询或日志清理。消费者暂停是独立运维控制。不开启自动同步统计回退或停采补采。

停机停止接收新的本地交接，终止后台恢复任务并有界释放发布资源，不无限等待MQ排空。未能发送的进程内事件允许丢失；未确认仍不能声称未送达。Consumer关闭连接后未ACK消息可重投，已提交未ACK仍靠eventId去重。优雅停机只提供有限等待，不改变强制退出或依赖故障时的丢失契约；最终验收须覆盖此边界。Boot每个生命周期phase的等待预算不等于整个进程或任意销毁方法的统一截止。[Boot3.5优雅停机说明](https://docs.spring.io/spring-boot/3.5/reference/web/graceful-shutdown.html)

## 观测与查询说明

保留已有统计写入/查询/清理观测，增加分层的交接、发布、消费观测：本地接受/满/过期、当前排队与未确认数、confirm/return/nack/unknown、保存/重复/expired/失败/重试耗尽、消费速率及处理延迟。broker侧看ready/unacked、死信积压及资源告警。指标区分事件、发布尝试和持久化尝试，避免把一次重试计算成一次新访问。

回调竞态下一个发布尝试只结算一次。使用固定错误类别，事件ID可用于受控日志关联；短码/IP/访客不作为无限增长指标标签。不打印消息体、摘要、原始请求头、密钥/令牌、连接密码或驱动原文/堆栈。Spring默认ErrorHandler/recoverer日志须显式核验和控制，不能因业务代码没打印就认定没有泄露。[AMQP3.2.12异常处理说明](https://raw.githubusercontent.com/spring-projects/spring-amqp/v3.2.12/src/reference/antora/modules/ROOT/pages/amqp/exception-handling.adoc)

现有查询API、令牌、范围UV、趋势和明细游标规则继续使用MySQL日志。collectionPolicy仍为best-effort；collectionEnabled只是配置采集状态，不代表MQ健康或数据完整。generatedAt只是响应生成时间，不是消费水位；同一查询快照内部一致，不表示此前全部访问已经消费。

## 测试方案（Q14已确认）

以HTTP可见行为、真实RabbitMQ及MySQL持久化为主要边界，复用现有可控Clock、闩锁、用例替身和真实MySQL/Redis回归。异步结果用有界最终等待，避免长sleep和测试私有实现。既有测试需从立即落库断言改成符合异步契约的最终断言，但不放宽身份、有效性、隐私及窗口规则。本次不编写/执行测试。

| 验收场景 | 主要证据 |
| --- | --- |
| 正常GET、缓存hit/miss和共享加载 | 每个请求独立事件；最终日志数量正确、Cookie UV复用，302/Location/no-store保持 |
| HEAD/拒绝/采集关闭 | 不产生新事件或统计Cookie；关闭后仍消费已入MQ事件、可查历史且清理继续 |
| HTTP与发布线程隔离 | 用闩锁阻住实际发送，HTTP仍返回；观测MQ建连/发送和统计SQL不在请求线程，满缓冲立即跳过而非CallerRuns；核心Redis或MySQL查链仍按原协议 |
| 启动/运行期MQ不可用 | 核心启动和跳转继续；后台恢复后新事件可消费，不伪造故障历史补采 |
| 真实路由和broker确认 | 正确binding成功；缺binding产生return；不存在Exchange及断连结果受控；confirm ACK不当作已落库 |
| 发布背压、未知确认及回调竞态 | 真实broker/TCP故障结合受控回调，验证有界资源、观察超时、单次结算和仅重建发布连接；最多一个恢复动作，不无限增加线程/连接，不影响消费连接 |
| 消费失败与ACK顺序 | DB失败不能正常返回后误ACK；先提交后断开消费连接，再投递同事件仍只有一行；其他约束不是成功重复 |
| 分类重试与DLQ | 暂时失败后恢复、每轮3次、永久/转换/schema零重试、耗尽不requeue且进入DLQ；无自动回流 |
| 时间及保留 | 上海跨午夜、冻结时间、乱序、映射后续禁用/过期仍计历史；超窗/清理后重放不写库，未来统计日拒绝，重试跨午夜复查 |
| 容量/prefetch/TTL | 缩小测试配额并最终观测ready/unacked；业务满产生nack而不进DLQ；主TTL死信、DLQ淘汰、消息体超限及许可恢复 |
| 查询、隐私和原业务回归 | 范围UV/一致快照/游标/令牌/30日窗口/日志清理及Redis协议保持；JSON和各层错误日志无禁传字段 |
| 暂停与有界关停 | 人工暂停不被后台恢复撤销；停止非立即撤回已预取消息；不无限等待MQ，本地未发允许漏记，消费未ACK重投后去重，无后台线程或许可泄漏 |

少量真实故障验收用于证明背压、ACK/重投、容量/TTL和阶段超时；mock只证明错误分支，不能证明真正耗时上限。故障注入限隔离测试环境，不在生产制造资源告警。用同等环境和负载对比原同步基线构建与异步版本的HTTP耗时分布、丢弃率和处理延迟；不为比较保留生产双路径，不把低负载结果宣传为固定p99/SLA。

## 可靠性机制分级（Q15已确认）

分级针对本项目已经选定的方案，不宣称所有RabbitMQ应用都有相同强制清单。

| 级别 | 范围 |
| --- | --- |
| 必须实现 | 逐请求冻结/隐私最小化/消息校验；有界交接和发布恢复、无HTTP统计网络等待；独立发布/消费连接；最小durable拓扑与持久消息；confirm/returns及受控观测；消费结果反馈与AUTO ACK；复用eventId唯一键；分类有限重试与单DLQ；容量/TTL/窗口过期处理；后台启动/有界关停；基础故障观测和人工暂停消费步骤；核心和统计回归 |
| 可以实现 | 受控选取和确认的DLQ重放工具；测量后批量落库或有限并发优化；持续DB故障的自动暂停/探测恢复；接入现有指标系统的更完整耗时分布。均不纳入当前验收，变更确认/批量语义时重新设计 |
| 只需要理解 | outbox/本地消息表；MQ事务及分布式事务；quorum集群与更可靠死信；多级延迟重试/长期补偿；跨重启累计投递限制与永久去重；MANUAL ACK的正确使用；端到端恰好一次的边界 |

当前不做outbox是明确取舍：访问不是计费账本，允许漏记，且不希望为了可靠产生消息在核心路径新增一笔DB事务。若以后要求每次访问可靠入账，仅加confirm/DLQ不足，须重新讨论可靠产生、持久补偿和代价，不能沿用当前承诺。

## Java后端面试重点（Q15已确认）

1. 对比实施前后代码说明：已有池/准入/超时为何仍等待JDBC，MQ收益如何通过故障测试与同条件对比验证；不虚构规模。
2. 区分跳转决定、broker确认、数据库保存，以及不能证明目标网站已打开；为什么异步confirm不保证publish非阻塞。
3. 为什么Cookie/HMAC/时间冻结同步、网络和落库异步；缓存命中/共享加载为什么仍逐请求计数，为什么不等响应发送回调。
4. confirm与return、AUTO与NONE/MANUAL分别解决什么；为何吞异常会误ACK，ACK必须跟在数据库处理成功之后。
5. 数据库提交与ACK间隙为什么重复；eventId内部稳定、客户端重新GET新ID，唯一键如何保护当前只追加日志的PV。
6. 有限重试不等于永久3次，x-death不等于重投计数；DLQ为什么不用自动循环回放，数据库整体故障为何要暂停消费。
7. ready/unacked/prefetch及消费速率差如何造成积压；为何有界缓冲、TTL和拒收优先保护核心，5秒confirm观察为何不是publish截止。
8. 冻结时间、迟到/乱序、30日窗口及清理后去重边界；范围UV不能加每日UV，query快照不等于消息全部处理完。
9. 摘要/元数据最小化和schema/密钥版本区别；为什么不把Cookie、秘密或整个映射实体放进消息。
10. 为什么当前不用outbox、集群和复杂补偿；当业务变成可靠记账时哪些前提必须重新决定。

面试可以概括为：在跳转优先、统计允许漏记的业务契约下，把数据库写入移到独立消费路径，限制交接、发布和积压资源，明确确认/ACK及重复处理边界，并用真实故障与持久化结果验证，而不是仅加一个发送调用。

## 文档关系与完成边界

方案决策见[ADR-0007](adr/0007-rabbitmq-visit-statistics.md)，逐轮确认见[讨论地图](../.scratch/async-visit-statistics/map.md)。沿用[ADR-0006](adr/0006-synchronous-visit-statistics.md)的业务口径，以当前异步实现替代其中的同步采集边界；当前实现事实见[访问采集说明](visit-collection.md)与[查询说明](visit-statistics-query.md)。不把历史ADR的设计日期当当前代码状态。

2026-10-03 已确认[阶段 7：消费者幂等](consumer-idempotency.md)。保留本阶段的事件唯一键、同步消费与 AUTO 确认、有限重试和窗口规则，不增加 Redis 幂等双写或额外显式写事务；阶段 7 两类新增异常测试仍为待实现计划，不计入本阶段历史验收结果。

已核验并修正查询说明末句原先“任务06调度清理仍未实现”的陈旧表述；实际已有清理调度和生命周期测试，异步化继承该能力。本文没有把文档编写当作实现，也未运行任何实现测试。用户最后一轮确认了测试及分级，全部设计分支已收束；本次工作止于已接受的设计，不创建实施任务或开始编码。

## 当前资源所有权与代码导航

`stats.messaging.VisitMqRuntime` 统一应用 ready/close/destroy 与后台拓扑、消费启动终止；`AsyncVisitRecorder` 只封装发布状态和发布连接恢复。框架关闭 adapter 与销毁保护仍在 messaging 内，所有 MQ 等待共用有限预算。核心池归 `configuration.CoreDataSourceConfiguration`，统计池归 `stats.config`。其他统计能力、HTTP 分组和同包测试导航见 [实际架构](architecture.md)；本设计的历史资源值和 best-effort 边界保持。
