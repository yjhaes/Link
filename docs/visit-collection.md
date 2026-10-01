# 同步访问采集

采集默认关闭。设置 `SHORT_LINK_STATS_ENABLED=true` 时必须同时提供
`SHORT_LINK_VISITOR_HMAC_KEY`（至少 32 个安全随机字节的标准 Base64）及
`SHORT_LINK_VISITOR_KEY_VERSION`（1..65535）。密钥与管理令牌独立，不提供生产默认秘密。
所有实例必须使用相同密钥、版本和编码规则；紧急更换密钥必须使用新版本，跨版本 UV 可能增加。

只有正常 GET 跳转决定参与采集。发生时间来自最终逐请求检查，以 UTC 毫秒保存，
按 Asia/Shanghai 计算统计日。采集是同步 best-effort：没有写入容量立即跳过，
超时、失败或提交确认丢失后仍返回原 302，不重试；日志只能证明服务端作出了跳转决定。

`sl_visitor` 保存 128 位随机值，固定 30 天且不续期，host-only、Path=/s、HttpOnly、
SameSite=Lax；HTTPS 服务地址或 HTTPS 请求设置 Secure。拒绝 Cookie、清除 Cookie、
不同设备及首次并发请求都会增加统计身份，UV 不代表真实人数。不使用 IP 作为 UV。
部署时应向访客明确统计 Cookie 的用途和保存期。

事件只包含按短码隔离的 HMAC 摘要和脱敏字段。对端 IPv4 保留 /24，IPv6 保留 /48，
忽略 Forwarded/X-Forwarded-For 身份声明；代理部署时对端可能是代理。
不要启用自动信任外部代理头的 Servlet 转发处理。
UA 清理控制字符并限 512 个 Unicode 字符，Referer 只保留规范化 host。
访问日志及备份应仅向受信任维护人员开放；在线日志删除不代表备份或 binlog 同时删除。

核心主 DataSource 继续负责 MyBatis、JdbcTemplate、发号、状态事务及 schema.sql 初始化。
统计池只用于独立自动提交，不加入核心事务；最多 4 个连接、minimumIdle=0、
initializationFailTimeout=-1，写入立即准入容量 2。统计池不做启动网络预检，
核心原有数据库初始化依赖仍存在。schema.sql 只追加日志表，不清空现有映射或发号数据。

`short-link.stats` 可覆盖以下超时起点：

| 属性 | 默认 |
| --- | --- |
| connection-timeout-ms | 1000 |
| validation-timeout-ms | 500 |
| connect-timeout-ms | 500 |
| socket-timeout-ms | 1000 |
| statement-timeout-seconds | 1 |
| lock-timeout-seconds | 1 |

超时分别约束取连接、验证、建连、socket、语句及统计会话行锁等待，
不构成整个 HTTP 调用的墙钟截止时间，也不能证明失败写入没有执行。

## 内部故障观测

`VisitWriteObservations.snapshot()` 提供进程内累计观测，无公开 HTTP 入口。
`attempted` 统计到达记录器的事件尝试；`outcomes` 固定为 SAVED（收到自动提交写入确认）、
DUPLICATE（仅事件唯一键重复）、DROPPED（准入繁忙）、FAILED（执行前失败或数据库明确拒绝）、
UNCERTAIN（执行阶段未收到可靠确认）。一次尝试只进入一个结果。
确认后关闭资源失败保留 SAVED，并增加 CLEANUP 类别，避免把已确认保存改成不确定。
SQL 超时、取消、断连及未知执行错误不能证明日志未保存，不生成新事件重试。

`categories` 是 CONNECTION、TIMEOUT、CONSTRAINT、DATABASE、UNEXPECTED、COLLECTION、CLEANUP
的固定累计计数；其他唯一键违反计入 CONSTRAINT，不当作事件重复。
COLLECTION 表示随机值、摘要或采集处理失败而跳过事件；不会使用共享默认访客身份。
耗时为到达记录器至资源释放的累计纳秒（包含丢弃），配合 attempted 的区间差可计算平均值。
快照还包含当前 inFlight、统计池 active/idle/total/waiting。快照在并发写入时不是原子事务，
实例重启后归零；不含短码、IP、Cookie 或访客摘要标签。错误日志只输出事件 ID、受控类别和阶段，
不输出驱动异常原文或堆栈。采集处理失败仅输出固定类别。

Connector/J 的 DataSource Properties 使用字符串值，包括 connectTimeout、socketTimeout
和 forceConnectionTimeZoneToSession；整数/布尔对象不能替代字符串。
真实 MySQL 验证覆盖取连接耗尽、独立语句超时、socket 读超时、会话行锁等待、KILL 失效连接恢复，
测试专用 TCP 转发器丢弃已建立连接的回复，验证 500ms 校验预算并替换连接。
建连测试观察驱动向真实 Socket.connect 传入 500ms；它不模拟每种操作系统、DNS 或建连故障，
也不证明 HTTP 总墙钟上限。mock 故障仅验证分类与降级语义。
统计池占满期间核心创建、发号和状态提交仍使用核心池；两池共享 MySQL，不能据此宣称硬件完全隔离。
本任务不交付统计查询、管理令牌保护或日志清理调度；后续任务负责这些能力。
