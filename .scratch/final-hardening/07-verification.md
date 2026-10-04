# 07：分组健康和安全管理端口验收

日期2026-10-04（Asia/Shanghai）。实现分支 `codex/hardening-07` 基于集成 `f83c4be7b7787fd294100f92f92d2f79858a0cbb`，交付前同步纯任务索引更新 `ac0e549`。仍用当前实现模型，没有额外代理/后续任务范围。

## 结果

1. 添加当前Boot3.5.16的Actuator starter与只含artifact/group/name/version的构建info，没有技术栈升级。默认端点access none、最高权限read-only，仅health/info/metrics；JMX与发现关闭，env/configprops/heapdump/loggers/shutdown/mappings/threaddump等均404。
2. 独立管理默认127.0.0.1:8081；Compose容器内0.0.0.0:8081，只向宿主localhost发布。主8080所有Actuator入口404，仅 `/livez` 和 `/readyz`；两者精确status唯一字段，管理同组一致。管理入口无旧X-Internal-Token依赖，网络边界明确。
3. liveness仅ApplicationAvailability自身；readiness是readinessState和明确qualified核心dataSource。默认聚合DB/Redis/Rabbit等健康关闭；独立statisticsDatabase不进入核心，所有异常仅固定unavailable，不输出SQL/地址/原始异常/秘密/bean名。
4. dependencies分列Redis、MQ被动发布/消费连接状态、统计DB、collection/consumer配置意图与实际、handoff/发布恢复状态。沿用既有snapshot字段，追加started/accepting布尔观测；健康不createMQ连接、不start/stop消费者，不覆盖人工暂停。MQ连接观察不证明route/policy/ready/unacked或数据库记账。
5. 既有HTTP/独立JDBC/Hikari基础指标可查询，HTTP标签含模板不含实际短码；jdbc池固定name=dataSource和stats（Boot去除statsDataSource后缀）。业务自定义观察由08继续，不宣称broker全部指标自动获取。

## 真实HTTP红绿和生命周期

新 `HealthManagementHttpTest` 使用真实Spring双HTTP服务器、随机端口，受控JDBC/Redis/AMQP公共边界。红阶段实际 `/livez` 为404，绿阶段验证探针/危险入口/无旧令牌保护、安全info、仅统计池失败及Redis/MQ不可用时core仍UP、核心SQL失败ready503/livezUP、异常canary不在健康、配置enabled但人工停止的实际状态分列、重复探针不启动消费者或建连接、readiness拒流/接受生命周期。结束真实关闭双服务器。

最终针对性命令：`mvnw -B -Dtest=HealthManagementHttpTest,CreateRateLimitEmbeddedHttpTest,VisitPublisherFailureTest,VisitMqRuntimeTest,VisitMqShutdownTest -Dtest.reportsDirectory=target/health-compat-final test`。**9项通过，0失败/错误/跳过**。既有纯MVC嵌入夹具仅在自身排除生产HealthEndpoint装配并使用随机管理端口，不关闭生产健康组成员校验；独立健康夹具验证完整装配。报告 `target/health-compat-final` 和 `target/health-compat-final.log`。

## 真实全栈Compose故障矩阵

运行 `pwsh -NoProfile -File ops/compose/smoke.ps1`，唯一隔离项目 `link-smoke-27d4be3b0d99`；独立秘密、随机localhost主/管理/MQ Management端口、新卷。**64项部署断言全部通过**，见 [安全结构化摘要](07-health-summary.json)，raw redacted reports在 `target/compose-smoke/27d4be3b0d99`，父代理复制保留到主工作树target，不提交大型原始日志/展开环境/秘密。

- 实际新卷构建、DB/MQ项目账号和policy/route/消费者、创建201/302、异步PV/UV、暂停消费积压、普通整体stop/up保留映射/日志/积压/身份、受控空Redis重建全部保持06行为。
- 主与management精确无详情探针、危险端点/主Actuator404、管理info无令牌访问、安全固定构建字段、实际HTTP与两池指标全部核验；暂停配置后的intent=disabled/actual=stopped，重复健康探针仍0消费者。
- **运行中**停止Redis/MQ，依赖组DOWN503，但主/livez、/readyz及management核心组UP；再实际重建app时仍启动/302。停止全部旧应用写入者后恢复空Redis/ broker并重启，不绕过受控恢复边界。
- **实际停止MySQL**，主和managementreadiness503、self-onlyliveness200；等待真实JRE探针把容器标为unhealthy，恢复DB后readiness200。Docker应用探针读取主8080/readyz，不用独立8081存活替代业务端口；辅助JVM堆32MiB，仅JRE标准库，无curl假定。
- 故障健康详情不含任何独立秘密、jdbc/redis/amqp地址、SELECT或exception等原始内容。退出只删除本次唯一项目/临时卷/临时秘密。

首次Compose红项为06解析器仅接受application/json，实际Actuator使用标准vendor+json导致被误判bytes；修正解析但保留精确status-only断言，容器JRE探针在首轮已经Healthy。下一红项为验收错误预期statsDataSource完整bean标签，经本地Boot3.5.16官方源码核对固定实际标签stats后通过；未放宽两池区分。

资源末次轻载单点：app252.3MiB/768MiB、MySQL431.8MiB/1GiB、MQ127.6MiB/1GiB、Redis4.953MiB/256MiB。不是性能/峰值/最低机器要求。Windows宿主和真实Linux容器已验证；Linux入口已提供，原生Linux宿主完整运行尚未验证。网络/端点/故障含义与被动MQ观察局限见 [健康文档](../../docs/health.md) 和 [Compose运行](../../docs/compose.md)。
