Status: claimed
Type: task
Blocked by: 02

# 03: 发布故障和背压不阻塞短链跳转

**What to build:** MQ启动/运行失败、路由错误、网络背压和确认未知时，访问者仍完成跳转，维护者能够区分结果，发布缓冲、关联状态及恢复资源始终有界。

**依赖说明：** 02 — 需要实际请求交接和RabbitMQ发布路径。

## Acceptance criteria

- [x] 落实correlated confirms、mandatory/returns；区分发送尝试、broker接受/路由和落库，return后随后ACK不算路由成功。
- [x] 发送异常/nack/return/确认观察5秒未知按受控类别处理；不自动重投，不同步MySQL回退，不假称超时必定未送达。
- [x] 待发年龄按单调时间判断，超过5秒不再开始发送；满缓冲、未确认或channel限制下跳转立即继续，恢复只处理后续仍符合预算的事件。
- [x] 独立观察机制不依赖被卡住sender；仅回收/重建publisher子连接，最多一个恢复动作，不reset消费者主连接或无限创建替代线程/连接。
- [x] 超时、return、关闭产生的framework nack及迟到回调竞争时，每个发布尝试只结算/释放一次，框架关联资源也回收。
- [ ] 验证MQ从启动不可用到恢复、运行断连、缺binding/错误Exchange和受控背压；静态非法配置与声明/认证问题按既定边界处理，不删除已有队列。
- [x] 用闩锁阻住真正发送，证明302在解除前返回且请求线程不执行MQ建连/发布；少量真实故障核验框架资源释放及仅发布连接恢复，不把mock异常或5秒观察当总发送截止。
- [x] 该片提供自身发布结果观测和受控日志，秘密/消息体不进入异常输出；不新增监控平台或公开故障注入接口。

## Comments

- 2026-10-02：用户已确认8张任务的粒度、交付范围和直接阻塞关系。本轮仅发布任务，尚未认领或开始实施；父规格内容和状态保持不变。



## Answer

发布部分实现完成：correlation采用每尝试独立ID（payload事件ID不变）；单调5秒待发及confirm观察，最多32关联状态；return结算后仍保留框架跟踪直到ACK/超时，迟到ACK/nack不覆写结果或重复释放。独立observer和单cleanup发布恢复，gate仅在reset实际返回且旧sender退出后打开；不自动重投。publisher CCF与consumer CCF物理分离，恢复只调用publisher。Spring销毁登记为外部管理，避免DisposableBean同步reset，框架executor为2 daemon线程、32任务有界队列。

2026-10-02：Maven `VisitPublisherFailureTest,VisitPublisherBrokerTest,AsyncVisitRoundtripTest` 全部8测试通过（0失败/错误）。覆盖真实RabbitMQ路由、return+ACK、错误exchange/nack、2轮TCP黑洞confirm unknown和后续新事件、独立consumer连接身份保持；受控sender/cleanup闩锁、迟到ACK/nack、return无ACK回收、重复event独立correlation；真实HTTP在send阻塞/满缓冲时仍302，真实MySQL原口径最终落库。

边界：TCP黑洞证明确认丢失与连接清理，不证明内核阻塞写被中断，也不承诺整个publish的5秒硬截止。受控闩锁证明observer不依赖sender以及cleanup卡住时不创建替代worker。整体MQ启动不可用→listener恢复和认证/声明冲突验收由任务06集成，当前保留claimed及对应未勾选项，不能把代码实现当这项已验收。
