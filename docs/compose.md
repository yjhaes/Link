# 全栈本地 Compose

本地单实例演示包含 app/MySQL/Redis/RabbitMQ 四个常驻服务。创建、跳转与管理用同一个应用；统计发布和消费也在该进程内。MySQL 是映射和已记录访问日志的权威；Redis 是非持久缓存/限流；RabbitMQ 命名卷保存队列及积压。单节点和持久卷不提供高可用或可靠统计记账。

## 首次初始化与运行

Docker Engine、Docker Compose 2.24.4+（隔离冒烟使用 `!override`；推荐当前 Compose）、构建联网与可用内存是前提。宿主无需 Maven/JDK：应用在固定版本 Maven/JDK 17 镜像构建，运行在固定补丁 JRE 17 镜像，使用 UID/GID 10001；基础构建/运行和三项设施镜像同时固定实测标签与 SHA-256 manifest 摘要。首次构建会下载镜像和 Maven 依赖。

Windows PowerShell 7：

```powershell
pwsh -NoProfile -File ops/init-local-secrets.ps1
docker compose --env-file .env.local up --build --detach --wait --wait-timeout 240
```

Linux（Shell/OpenSSL）：

```sh
sh ops/init-local-secrets.sh
docker compose --env-file .env.local up --build --detach --wait --wait-timeout 240
```

初始化生成独立管理令牌、访客 HMAC、DB 项目密码、DB 引导密码、MQ 项目密码；重复运行保留原文件。不打印秘密，不提交 `.env.local`。基础配置采集默认关闭，Compose 明确开启；消费者默认开启。不要把占位 `.env.example` 当真实配置。Dockerfile 只复制 POM 和 `src/main`，上下文同时排除嵌套秘密、个人缓存、日志和测试报告。不要运行或保存 `docker compose config`/完整 `docker inspect`：它们可展开环境秘密；校验配置使用 `config --quiet`。

新 MySQL 卷由官方镜像创建 `short_link` 数据库和 `.env.local` 项目账号，应用启动幂等建表。新 RabbitMQ 卷由官方镜像创建项目用户/vhost；固定 hostname 保持 broker 节点数据路径稳定。启动包装脚本等待 broker，重复应用 [既有队列 policy](../ops/visit-consumer-policies.json)，成功后才使 broker 健康；不删除队列，不清空积压。策略源码在 `ops/compose/rabbit-bootstrap.sh`，隔离冒烟逐项核对实际 broker 与权威 JSON。应用后台声明 durable direct exchange、classic 业务队列、DLX/DLQ 与绑定；声明冲突必须排查，不自动删除队列修复。

浏览器打开 `http://localhost:8080`，RabbitMQ Management 是 `http://localhost:15672`（项目账号秘密自行从本地配置查看）。容器内按 mysql/redis/rabbitmq 服务名连接。端口绑定支持容器网络；宿主仅发布 127.0.0.1 的应用8080、独立Actuator管理8081和MQ Management15672。应用容器真实JRE-only探针读取主8080的 `/readyz`，包含应用就绪与核心MySQL，Redis/MQ健康不会令核心探针失败；`up --wait` 等待该核心探针，完整业务和统计还需实际冒烟确认。详见 [分组健康与管理端口](health.md)。应用启动硬依赖仅 MySQL 健康，不等待 Redis/MQ；故障隔离不代表统计正常，完整演示仍检查队列、策略、消费者与异步 PV/UV。

默认 MySQL/Redis/AMQP 不向宿主发布。确需调试时显式增加 override：

```sh
docker compose --env-file .env.local -f compose.yml -f compose.debug.yml up --detach
```

调试端口也只发布 localhost；请先检查本机端口占用。可通过 `APP_PORT`、`MANAGEMENT_HTTP_PORT`、`RABBITMQ_MANAGEMENT_PORT`、`MYSQL_DEBUG_PORT`、`REDIS_DEBUG_PORT`、`AMQP_DEBUG_PORT` 调整。普通用户不要增加 debug 文件。

## 资源起点

| 服务 | 默认上限/约束 | 可调环境变量 |
| --- | --- | --- |
| app | 容器 768MiB，最大 Java 堆 384MiB | APP_MEMORY_LIMIT、APP_MAX_HEAP |
| MySQL | 容器 1GiB | MYSQL_MEMORY_LIMIT |
| Redis | 容器 256MiB，maxmemory 128MiB，noeviction | REDIS_MEMORY_LIMIT、REDIS_MAXMEMORY |
| RabbitMQ | 容器 1GiB；Erlang 调度器 2 | RABBITMQ_MEMORY_LIMIT |

核心池与实际回源、独立统计池仍按应用预算；broker 队列数量/字节/TTL 和 prefetch/local handoff 限制不由容器内存代替。Redis 内存满时拒绝相关写操作，走既定错误/降级，不通过淘汰桶重置额度。Redis `save ""`/`appendonly no`，演示不持久化。资源数字是演示起点，包含引擎、堆外、构建和并行测试需额外余量；不承诺最低机器要求、生产容量或 HTTP 总时限。实测观察见验收报告。

## 普通停止和再次启动

```sh
docker compose --env-file .env.local stop
docker compose --env-file .env.local up --detach --wait --wait-timeout 240
```

`stop` 与不带 `--volumes` 的 `down` 保留 MySQL/RabbitMQ 命名卷；同一项目名且 `.env.local` 不变时映射、已记录访问日志、broker 积压和身份密钥稳定。进程内未发布事件仍可能丢失。停止全部应用后重启非持久 Redis 等于空库重建；所有旧写入者已经停止，因此可在空 Redis 确认后重启应用重新回填。不要仅重启 Redis 后宣称连续运行即时可见。

## Redis 受控重建

暂停创建/状态维护，停止全部应用和内部维护进程，等待在途结束。对单实例独立 Compose 演示：

```sh
docker compose --env-file .env.local stop app
docker compose --env-file .env.local rm --stop --force redis
docker compose --env-file .env.local up --detach --wait redis
docker compose --env-file .env.local exec -T redis redis-cli DBSIZE
# 独立演示库必须为 0，确认没有其他写入者后：
docker compose --env-file .env.local up --detach app
```

没有开启持久化的 Redis 重建后为空，不导入旧快照，限流桶重置可接受。首次跳转从 MySQL 建立新版本缓存；核对状态和 no-store 后恢复流量。有多个实例、外部写入者或旧快照时必须按 [完整受控恢复](redis-recovery.md) 隔离所有写入者，并清理全部版本/缓存命名空间，不能清理一个 Key 代替。此前已提交但协调未确认的短码仍需逐一调用内部协调恢复入口；POST 重试不能代替恢复。

## 轮换与明确数据重置

普通启动保留秘密。改变 `.env.local` 不会自动修改已有 MySQL 用户密码或 RabbitMQ 用户/vhost：官方镜像的账号引导仅适用于空卷。若需要轮换，先停止相关流量/应用，使用现有认证在 MySQL `ALTER USER`、broker 的 `change_password` 等维护入口更新实际账号，再同步本地配置并重建应用，核验连接/消费；不要把秘密放进 shell 历史或可查看的命令行，不提供自动账号修复或自动删除卷。管理令牌轮换需更新配置并重建应用；访客 HMAC 轮换需同时提升 `SHORT_LINK_VISITOR_KEY_VERSION`，将导致新身份版本，跨版本 UV 不能视为同一身份。普通重启无需任何轮换。

仅在确认要永久删除这个项目全部映射、日志、broker 状态/积压时使用：

```sh
docker compose --env-file .env.local down --volumes
```

该命令是明确数据重置，不是故障恢复。已有秘密可以继续用于空卷初始化；如果也要换秘密，先备份所需信息并手动移走旧 `.env.local` 再初始化。固定项目名默认 `short-link`，自定义 `--project-name` 后需每次一致。

## 独立真实冒烟

Python 3.10+、Docker 和 PowerShell 7/Linux shell：

```powershell
pwsh -NoProfile -File ops/compose/smoke.ps1
```

```sh
sh ops/compose/smoke.sh
```

创建唯一 Compose 项目、独立秘密和 Docker 分配的随机 localhost 端口；真实多阶段构建、新建账号/schema/policy、HTTP 创建/302、异步 PV/UV、消费暂停积压、整体普通重启、积压恢复、身份稳定、实际资源/端口/卷、Redis 受控重建，以及Redis/MQ实时停机时核心探针保持UP、实际重新启动应用仍302、MySQL实时停机时核心探针503且liveness仍UP、真实容器unhealthy/恢复和管理端口安全边界。报告在 `target/compose-smoke/<run-id>`；失败明确非零退出，不把缺设施当跳过。结束只删除这个隔离项目的临时卷/秘密；不连接或删除个人设施。统计轮询 1 秒，保持真实限流，POST 503 不盲重试。成功后报告为安全摘要，不保存展开配置或容器环境。

来源：[官方 MySQL 镜像](https://hub.docker.com/_/mysql)、[RabbitMQ 3.13 配置](https://www.rabbitmq.com/docs/3.13/configure)、[Maven 官方镜像](https://hub.docker.com/_/maven/)。这些入口说明初始化与构建机制；实际部署兼容性以本仓库冒烟为准。
