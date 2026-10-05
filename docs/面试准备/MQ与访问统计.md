# MQ 与访问统计

返回 [面试导航](README.md)。先说明为什么异步、何时算记录成功，再解释确认机制和失败处理。

## 开场回答

> 访问统计是正常跳转后的附加工作。我把统计网络和 SQL 移出 HTTP 线程：请求生成访问事件并尝试放入有界本地队列，后台发布到 RabbitMQ，消费者同步保存 MySQL。eventId 唯一键处理同事件重投，统计查询基于已记录日志。核心跳转优先，统计 best-effort；本地交接、broker 确认和数据库保存是三个边界，不保证不漏记或端到端恰好一次。

## 必会回答

### Q1. 为什么引入 MQ？同步写统计不行吗？

同步写可以更简单，但获准写入的 HTTP 线程仍等待统计 JDBC；独立池只能隔离部分连接配额，不能把等待移出请求。当前通过 MQ 分离发布和消费工作，并学习消息确认、重投和积压边界。

代价是新设施、异步可见、失败分类与运维成本。不能仅因引入 MQ 就声称延迟下降多少或业务必须用它；当前没有生产性能证据证明 MQ 必不可少。

### Q2. 为什么不是直接在控制器里 RabbitTemplate.send？

直接发送仍可能等待连接、channel 和网络。当前 HTTP 只进行事件采集和有界本地交接，后台发布者承担统计网络调用。`record` 没有同步写库回退。

本地交接默认容量 256、未确认发布上限 32，均可配置；交接满、发布不确定、进程退出等允许漏记。内存队列不是可靠存储，不能把“offer 成功”当作数据库保存。

### Q3. 统计什么时候算成功？

| 边界 | 已经证明什么 | 尚未证明什么 |
| --- | --- | --- |
| 本地交接接受 | 事件暂时进入进程内队列 | 已到 broker、已保存、重启后仍存在 |
| broker 确认 | 对本次发布收到 broker 的确认信息 | 消费者业务成功、MySQL 已保存 |
| MySQL 确认保存或同 eventId 已存在 | 该事件已成为统计查询依据 | 所有发生过的访问都被记录 |

Publisher confirm 与 consumer ACK 面向不同链路，不能互相替代。[RabbitMQ 确认说明](https://www.rabbitmq.com/docs/confirms)。

### Q4. confirm、return、consumer ACK 有何区别？

| 机制 | 项目中的作用 |
| --- | --- |
| correlated confirm | 将 broker 的 ACK/NACK 与本次发布观察关联；超时可能意味着确认丢失 |
| mandatory + return | 观察未能路由到队列的返回结果；不能忽略 return 只看 ACK |
| consumer ACK | listener 完成处理后由容器确认消费；消息因此可从 broker 待确认集合中移除 |

发布观察的 correlation 标识与业务 `eventId` 职责不同：前者定位发布尝试，后者用于事件去重。

### Q5. 消费者为什么用 AUTO，而不是 MANUAL？

消费者同步保存数据库，成功、同 eventId 重复或合法超窗丢弃后正常返回；失败传播受控异常。Spring 容器的 `AUTO` 依据 listener 是否成功完成进行 ACK，当前不需要手动操作 delivery tag。

**Spring AUTO 不是 RabbitMQ 的无确认 auto-ack。** Spring 的 `NONE` 才对应 broker 不等待应用 ACK 的模式；也不能在 listener 内只提交另一个后台任务就提前返回。[Spring AMQP 容器说明](https://docs.spring.io/spring-amqp/reference/amqp/containerAttributes.html)。

### Q6. 重复消费是怎么发生的，怎么处理？

一种场景：MySQL 已保存事件，但 ACK 前连接断开；broker 后续重投。另一个场景是 SQL 响应丢失，调用方无法确认是否保存。

事件生成时已有 UUID `eventId`；消息重投保留身份。数据库 `uq_visit_event(event_id)` 唯一键兜底，只有明确命中这一唯一约束才视为重复成功，不把任何数据库约束错误吞掉。

不能先“SELECT 不存在，再 INSERT”来替代唯一键，两消费者可能同时查到不存在。数据库约束把判断和写入收敛到存储端。

### Q7. 浏览器刷新两次为何仍计两次 PV？

刷新产生两个符合统计口径的新 GET，每次生成新的事件身份。同一个事件重复投递仍保持同一个 eventId，不能重复入账。

匿名访客身份用于 UV，事件身份用于去重，是两种不同标识。不能把 eventId 当访客标识，也不能用访客摘要去重所有访问事件。

### Q8. 如何处理消费失败，为什么不一直 requeue？

暂时、繁忙和保存不确定的受控失败最多尝试三次，包含首次；两次等待分别为 200ms、500ms。永久错误不做同样重试，最终拒绝且不 requeue，按已有 DLX/DLQ 策略隔离。

无限重投会使坏消息或持续数据库故障反复占用消费能力。DLQ 用于排查，并不自动等于失败已修复或数据已补齐；当前没有自动回放。重试时继续检查保留窗口，合法超窗事件确认丢弃，未来日期拒绝。

## 访问统计口径

### Q9. 什么时候生成访问事件？

在正常 GET 通过短码及最终逐请求有效性校验、作出正常跳转决定后生成。缓存命中也逐请求采集，不能只在回源时计数。

HEAD、404/403/410、429 和回源准入拒绝不产生统计事件。事件不证明响应已发送成功，也不证明访问者打开了目标网站；已经发生但尚未记录的事件不进入当前 PV/UV。

### Q10. PV 和 UV 怎么计算？

PV 是指定短码与日期范围内的已记录事件数量。UV 对整个范围内的匿名访客版本与摘要去重；同一访客今天一次、明天一次，范围 PV=2、UV=1，两个每日 UV 相加会得到错误的 2。

代码使用 `COUNT(*)` 和 `COUNT(DISTINCT visitor_key_version, visitor_hash)`。密钥版本切换可能把同一浏览器分成不同统计身份，因此响应提供身份版本信息，不能描述成真实人数。

### Q11. 为什么 Cookie 配合 HMAC，而不是 IP 计 UV？

IP 可能被多个访问者共享，同一访问者也可能更换网络。当前匿名 Cookie 保存随机标识，服务器用独立 HMAC 密钥生成访客摘要，摘要范围包含用途、密钥版本和短码。

日志和 MQ 不传浏览器原始标识或密钥。Cookie 使用 HttpOnly、SameSite=Lax，Secure 按 HTTPS 配置/请求生效。不同浏览器、设备、隐身环境或清除 Cookie 会形成不同匿名身份，不是自然人认证。

HMAC 不是把匿名标识可逆加密，也不是反爬身份验证；不能靠可清除的 Cookie 防刷。

### Q12. 为什么固定上海统计日和保留窗口？

统计日按 Asia/Shanghai 的自然日划分，在线查询范围是包含当天的最近 30 个统计日，默认查询最近 7 日。发生时刻和统计日在生成事件时冻结，MQ 延迟不把昨天的访问改成今天。

队列消息驻留 TTL 与 30 日统计保留窗口是不同层面的规则；不能把消息 TTL 说成日志保留时间。历史查询、消费和日志清理也不因为停止新事件采集而全部停止。

## 进阶追问

### Q13. 为什么统计查询用同一数据库快照？

一次统计响应需要映射存在性、汇总、日趋势和身份版本等多次读取。若读取之间新日志不断写入，不同部分可能基于不同数据集。

当前在同一只读 REPEATABLE READ 事务内使用普通一致性读，保持一次查询内的快照。查询失败返回错误，不伪造零访问；连续两个 HTTP 查询仍可能看到不同结果。

### Q14. 双连接池真的隔离了吗？

核心池与统计池分开，统计池默认最大 4，写入/查询/清理准入分别 2/1/1。它们减少统计占满全部应用连接配额的风险，但仍共用同一 MySQL 和机器资源，不是独立数据库或硬件隔离。

并发准入、连接池容量、SQL 超时和 broker prefetch 限制的是不同阶段。放大其中一个参数未必提升吞吐，可能只是增加等待和积压。

### Q15. MQ 不可用时为什么应用还能启动和跳转？

核心先启动，MQ 拓扑声明和 listener 启动在后台进行；MQ 没有成为新增的核心启动硬依赖。故障时统计可能漏记，跳转仍按核心规则处理。

“恢复连接”不等于补回进程内已丢事件。正常关闭有等待预算，也不保证所有未发事件都可靠保存；详细生命周期属于进阶选读。

### Q16. 如果要求统计一条不丢，应该怎么改？

这是新需求。需要可靠交接、持久事件、重试/补偿、积压容量和恢复验证，再讨论 outbox 等方案。跳转当前没有必须写库的核心业务事务，若请求内持久化访问事件，就会重新引入延迟与数据库依赖，必须明确权衡。

不能只说“队列 durable、消息 persistent、打开 confirm 就万无一失”，更不能把当前 best-effort 说成端到端恰好一次。

## 代码与证据入口

- [逐请求采集](../../src/main/java/com/example/shortlink/api/stats/VisitCollection.java)、[匿名访客身份](../../src/main/java/com/example/shortlink/stats/collection/VisitIdentity.java)。
- [异步交接与发布](../../src/main/java/com/example/shortlink/stats/messaging/AsyncVisitRecorder.java)、[MQ 装配](../../src/main/java/com/example/shortlink/stats/messaging/VisitRabbitConfiguration.java)。
- [消费者](../../src/main/java/com/example/shortlink/stats/messaging/VisitConsumer.java)、[数据库保存](../../src/main/java/com/example/shortlink/stats/persistence/MySqlVisitPersistence.java)。
- [统计查询](../../src/main/java/com/example/shortlink/stats/query/MySqlVisitStatsQuery.java)、[消费者用例测试](../../src/test/java/com/example/shortlink/stats/messaging/VisitConsumerTest.java)。
- [消费者幂等设计](../架构与原理/consumer-idempotency.md)、[异步统计设计](../架构与原理/async-visit-statistics.md)、[真实设施与历史证据](../测试与验证/verification.md)。

本轮整理说明，不把代码入口或历史结果称为新版本的重跑证明。
