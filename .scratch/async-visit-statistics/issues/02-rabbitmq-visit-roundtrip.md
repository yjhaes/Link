Status: ready-for-agent
Type: task
Blocked by: 01

# 02: 正常GET通过RabbitMQ异步形成访问日志

**What to build:** 有效GET作出跳转决定后冻结并立即交接访问事件，返回302；后台通过最小RabbitMQ拓扑发布和消费，最终形成MySQL访问日志并可通过现有接口查询PV/UV。

**依赖说明：** 01 — Consumer需要明确的持久化成功/失败反馈。

## Acceptance criteria

- [ ] 沿用现有逐请求决定和Cookie/HMAC/元数据最小化边界；HTTP不调用MQ或统计SQL，不依赖响应发送完成回调，满缓冲不CallerRuns。
- [ ] 落实UTF-8 JSON的10字段协议、类型/版本/长度及16KiB消息体约束；不传原始标识、秘密、请求、映射或缓存实体。
- [ ] 配置当前Boot相容AMQP依赖、单节点durable direct Exchange/classic业务Queue、精确业务key、持久消息，以及发布/消费分离连接；不拆微服务。
- [ ] 基础本地缓冲256/worker1、未确认32、活跃channel16及获取等待200ms、Consumer并发1/prefetch10/batchSize1等限制随闭环建立，不先上线无界临时路径。
- [ ] 声明和监听器在核心启动后后台运行，基础提交/发送错误由统计侧兜底；没有同步写库回退或Producer事件自动补发。深度故障恢复由03完善。
- [ ] Consumer同步调用01的持久化边界，使用AUTO；已保存或合法重复才成功确认，不将任务交接给另一个异步线程后提前返回。
- [ ] 真实RabbitMQ/MySQL证明GET最终一事件一日志，重复客户端GET独立事件、同Cookie UV复用；broker确认不被当作已落库。
- [ ] cache hit/miss、共享加载等待者各自统计；HEAD/拒绝/管理请求及关闭采集无新事件Cookie。已有HTTP/身份/查询回归改为必要的有界最终等待并通过。

## Comments

- 2026-10-02：用户已确认8张任务的粒度、交付范围和直接阻塞关系。本轮仅发布任务，尚未认领或开始实施；父规格内容和状态保持不变。

