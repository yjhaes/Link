# 06：全栈 Compose 验收

日期：2026-10-04（Asia/Shanghai）。最终在 `codex/hardening-06` 合入 04/05 集成 tip `e6d433a38f9ddab4db471bf5ac61ee30c57cd549` 后实跑，未关闭限流/回源保护。Windows PowerShell 7 / Python 3.14.0 / Docker Engine 29.8.1 / Compose 5.5.1，Docker Linux 容器。

## 实际入口与结果

`pwsh -NoProfile -File ops/compose/smoke.ps1`（共享 Python 驱动）。隔离项目 `link-smoke-da66e6d163c5`，全新 MySQL/RabbitMQ 命名卷、独立五秘密、Docker 分配 localhost 随机端口，与个人设施完全分离。34 项部署断言全部通过、无跳过，见 [结构化安全摘要](06-compose-summary.json)。新增 Windows/Linux 入口，原生 Linux 宿主尚未完整实跑；Rabbit 容器 Linux bootstrap 已由真实冷启动和重复启动执行。

- 实际多阶段镜像构建成功；Maven/JDK 17、JRE 17、MySQL 8.4.4、Redis 7.4.2、RabbitMQ 3.13.7-management 均固定实跑标签及 manifest 摘要。应用配置 UID/GID 10001，Dockerfile 仅 COPY POM 和 src/main，上下文排除嵌套实际秘密、target/个人缓存/日志。
- 新 DB 项目账号/schema 可以实际创建 201，GET 正常 302/Location/no-store，发出采集 Cookie。实际队列、direct exchange 绑定及一个 live consumer 可见，异步 PV1/UV1；实际 broker 两个 policy 与既有权威 JSON 逐项相同。
- 关闭消费者意图后重建 app，新访问留在 durable ready backlog，旧 PV1 保持。整体 stop/up 保留映射、日志与 ready backlog、重复 policy 初始化不删除积压；重新启用消费者后 PV2/UV1，证明此前同 Cookie 身份与 HMAC 跨普通重启稳定，秘密文件摘要不变。
- 实际四服务、MySQL/RabbitMQ 命名卷、应用非 root、所有内存上限与 localhost 发布核验；DB/Redis/AMQP 默认均不发布。Redis 实际 maxmemory134217728/noeviction/save空/appendonly no。
- 停止所有应用写入者后实际移除并重建非持久 Redis，确认 DBSIZE0 后重启 app，映射302、异步 PV3/UV1、按需恢复缓存。额外停止 Redis/MQ 后**重新创建** app，已有页面正常启动、同映射仍302，证明 MySQL 是唯一设施启动硬依赖；随后停止全部旧写入者，空 Redis/ broker 恢复后完整服务再次启动。
- 不新增临时健康 API；当前用 MySQL/Redis/broker 自有检查、既有页面与业务 HTTP/MQ 实际状态验收；完整 Actuator 健康/管理端口由任务07增加。

## 资源观察与局限

末次完整恢复后的单次 Docker stats：app228.8MiB/768MiB，MySQL447.7MiB/1GiB，RabbitMQ137.7MiB/1GiB，Redis5.418MiB/256MiB。Java 最大堆384MiB；这些是演示资源起点和单次轻负载观察，未据此调整预算，不证明最低机器要求/峰值/性能/SLA；Docker引擎、堆外、构建与并行测试另需余量。

脚本统计轮询每1秒，保留默认管理查询5/1s，默认跳转60/100ms、创建3/6s；没有盲重试POST503。缺Docker/设施/构建失败/必需断言失败均非零退出；报告不保存展开Compose配置/inspect环境。raw redacted reports 位于 `target/compose-smoke/da66e6d163c5`，由父代理复制保存到主工作树 target；不提交大型原始日志。

初始红项来自实际配置和冷启动边界：Compose YAML引号错误、broker尚未建立节点时CLI过早退出、root CLI与官方降权入口竞态创建root-owned Erlang cookie。逐项修复后冷启动成功；Management统计字段首次可能缺失，验收采用有界观察等待。没有自动清空或删队列修复声明冲突。

普通启动、账号/密钥轮换与明确删除卷的数据重置，以及Redis受控重建边界见 [运行文档](../../docs/compose.md)。已有卷中的DB/MQ账号不会随环境变量自动更改，普通启动不轮换秘密。测试退出只删除本次唯一隔离项目的卷和临时秘密。
