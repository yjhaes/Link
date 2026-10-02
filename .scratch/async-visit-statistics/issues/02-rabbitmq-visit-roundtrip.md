Status: resolved
Type: task
Blocked by: 01

# 02: 正常GET通过RabbitMQ异步形成访问日志

**What to build:** 有效GET作出跳转决定后冻结并立即交接访问事件，返回302；后台通过最小RabbitMQ拓扑发布和消费，最终形成MySQL访问日志并可通过现有接口查询PV/UV。

**依赖说明：** 01 — Consumer需要明确的持久化成功/失败反馈。

## Acceptance criteria

- [x] 沿用现有逐请求决定和Cookie/HMAC/元数据最小化边界；HTTP不调用MQ或统计SQL，不依赖响应发送完成回调，满缓冲不CallerRuns。
- [x] 落实UTF-8 JSON的10字段协议、类型/版本/长度及16KiB消息体约束；不传原始标识、秘密、请求、映射或缓存实体。
- [x] 配置当前Boot相容AMQP依赖、单节点durable direct Exchange/classic业务Queue、精确业务key、持久消息，以及发布/消费分离连接；不拆微服务。
- [x] 基础本地缓冲256/worker1、未确认32、活跃channel16及获取等待200ms、Consumer并发1/prefetch10/batchSize1等限制随闭环建立，不先上线无界临时路径。
- [x] 声明和监听器在核心启动后后台运行，基础提交/发送错误由统计侧兜底；没有同步写库回退或Producer事件自动补发。深度故障恢复由03完善。
- [x] Consumer同步调用01的持久化边界，使用AUTO；已保存或合法重复才成功确认，不将任务交接给另一个异步线程后提前返回。
- [x] 真实RabbitMQ/MySQL证明GET最终一事件一日志，重复客户端GET独立事件、同Cookie UV复用；broker确认不被当作已落库。
- [x] cache hit/miss、共享加载等待者各自统计；HEAD/拒绝/管理请求及关闭采集无新事件Cookie。已有HTTP/身份/查询回归改为必要的有界最终等待并通过。

## Comments

- 2026-10-02：用户已确认8张任务的粒度、交付范围和直接阻塞关系。本轮仅发布任务，尚未认领或开始实施；父规格内容和状态保持不变。



## Answer

- 2026-10-02：使用当前模型实现。VisitCollection 保持逐请求冻结和 Cookie/身份/元数据规则，Primary VisitRecorder 改为有界 offer；后台单 worker 发布 UTF-8 JSON，独立消费连接的同步 AUTO listener 调用 VisitPersistence。
- 严格协议拒绝未知字段/schema、错误类型、非规范 UUID/Base64/UTC毫秒、日期不一致、完整 IP/非规范 host/控制字符/超长元数据及超过16KiB/非UTF-8消息。发布持久消息，durable direct/classic 精确绑定，缓冲256、未确认32、channel16/200ms、消费1/prefetch10/batch1。声明及启动只在 ApplicationReady 后的独立 daemon 执行。
- TDD：协议和真实闭环测试先编译失败后通过，补充 UTF-16 接受漏洞的失败测试后严格解码通过。真实 RabbitMQ/MySQL 测试证明两次 GET 两个事件且 Cookie UV 复用、元数据最小化；闩锁阻住 MQ send 时包括超过256个交接的 HTTP 仍完成302和Cookie，DB不提前写入。
- VisitStatisticsApiTest 18项通过（命中/未命中/共享加载逐请求、身份、跨日、拒绝及持久化反馈）；VisitStatsQueryApiTest 12、VisitLogsApiTest 7、VisitTimeoutTest 6通过；AsyncVisitRoundtripTest 2、VisitMessageCodecTest 2通过。直接持久化故障测试留在同步用例边界，HTTP观察使用有界最终等待。
- 此票为正常闭环与基础资源边界；03完善独立确认观察及发布连接回收/恢复，04完善分类重试/DLQ，05完善消费窗口，06完善暂停及停机，07完善观测/队列配额，08执行整体故障/性能验证。未把基础 future 观察声称为框架关联资源已完全回收，未将确认称为落库，也未承诺未测试的吞吐或硬截止。
