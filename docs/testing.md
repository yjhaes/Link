# 可复现测试入口

测试依赖仅用于本轮隔离回归，不连接演示或个人数据库。无设施测试不需要 Docker；真实集成需要 Docker Engine、Compose v2，以及允许 Testcontainers 访问 Docker 的环境。两种入口均要求 JDK 17、Python 3.10+，无设施/完整回归还需 Node.js 18+（执行存在的页面 Node 测试），首次运行 Maven Wrapper 及容器镜像需要网络。Windows 使用 PowerShell 7；Linux 不需要安装 PowerShell。

| 测试层 | Windows（仓库根目录） | Linux（仓库根目录） | 设施 |
| --- | --- | --- | --- |
| 无设施单元 / MVC | `pwsh -NoProfile -File ops/tests/run.ps1 unit` | `sh ops/tests/run.sh unit` | 无 |
| 全部真实集成 | `pwsh -NoProfile -File ops/tests/run.ps1 integration` | `sh ops/tests/run.sh integration` | 自动准备独立 MySQL / Redis / RabbitMQ，原 Redis 回归继续使用 Testcontainers |
| 两层完整回归 | `pwsh -NoProfile -File ops/tests/run.ps1 all` | `sh ops/tests/run.sh all` | 同上 |

入口按现有顶层 `*Test.java` 分类：带 `@SpringBootTest` / `@Testcontainers` 的类及 `VisitPublisherBrokerTest` 进入真实集成，其余进入无设施层。报告核对实际执行的类集合，缺报告、零测试、失败、错误或跳过均返回非零。新增必测类须沿用此文件命名；未来采用其他设施注解时同步分类。直接运行 `mvn test` 不自动准备遗留设施，完整验证请用上述入口。

真实集成入口每次使用新的 `link-tests-<随机标识>` Compose project、Docker 网络、空数据库与随机 localhost 发布端口。Compose 不挂载宿主数据库目录或已有命名卷；MySQL 数据库固定叫 `short_link_test`，但位于本次独立容器。RabbitMQ 的 `/`、`link-consumer-test`、`link-lifecycle-test` 均位于本次独立 broker，不是个人 broker 的 vhost。Consumer 和生命周期故障回归分组执行，避免互相更改 policy / 清空队列。MySQL / RabbitMQ 使用 `linktest` 项目账号及本轮内存生成的随机密码，Rabbit 账号的 administrator 标签仅供隔离测试核验 policy 和积压。初始化应用现有 `ops/visit-consumer-policies.json`，测试使用真实 MQ 及数据库，不把启动设施当成业务通过。

入口清除继承的应用连接/秘密变量，再注入本次动态地址与测试凭据。`application-test.yml` 显式关闭默认采集、配置独立的公开测试 HMAC 和管理令牌；采集回归自行显式开启。测试秘密只用于隔离测试，禁止用于部署。创建额度测试配置为高容量 / 快补充以保留原业务回归，限流自己的行为由专门测试配置覆盖。

入口先确认 Docker / Compose 可用，再等待设施健康、准备账号/vhost/policy。任何缺设施、配置或初始化错误都会失败，不使用 disabledWithoutDocker，不把必测项跳过当成功。除现有创建、跳转、缓存、状态、管理、消息幂等与故障、统计查询/清理和生命周期回归外，`AsyncVisitRoundtripTest.createdLinkRedirectsAndBecomesVisibleThroughStatsApi` 从 POST 创建开始，验证真实 302，再经管理 HTTP 查询轮询到异步 PV=1 / UV=1。

每轮输出只显示报告目录、隔离 project 和测试计数。`target/regression/<运行标识>/` 保留 Maven/设施安全日志、各分组 JUnit XML/TXT 和 `summary.json`，计数包含 tests/failures/errors/skipped。生成的 DB/MQ 密码从日志和 XML 中替换掉，不运行 `docker compose config` 或输出环境变量；`target/` 已被 Git 忽略。报告是当前运行证据，不使用历史 263 项通过作为本轮证明。

正常结束、测试失败和可处理的中断均在 finally 执行本次 project 的 `down --volumes --remove-orphans`；不会删除其他 project 或个人数据。Testcontainers 的容器沿用其自己的生命周期 / Ryuk 清理。直接强制杀死进程或 Docker daemon 不可用可能阻止清理，此时使用输出的 project 名，只处理带该 project 标签的容器/网络。不要使用全局 `docker system prune`。

若清理需要重试，给 Compose 只用于解析的临时占位值（原随机密码不会保存到磁盘）再运行带原 project 名的 down：

Windows：

```powershell
$env:MYSQL_TEST_PASSWORD = 'cleanup-placeholder'
$env:MYSQL_ROOT_PASSWORD = 'cleanup-placeholder'
$env:RABBITMQ_PASSWORD = 'cleanup-placeholder'
docker compose --project-name link-tests-<本轮标识> --file ops/tests/compose.yml down --volumes --remove-orphans
```

Linux：

```sh
MYSQL_TEST_PASSWORD=cleanup-placeholder MYSQL_ROOT_PASSWORD=cleanup-placeholder RABBITMQ_PASSWORD=cleanup-placeholder \
  docker compose --project-name link-tests-<本轮标识> --file ops/tests/compose.yml down --volumes --remove-orphans
```

## 完整正确性入口与 CI

`pwsh -NoProfile -File ops/ci/run.ps1`（Windows）或 `sh ops/ci/run.sh`（Linux）与 GitHub Actions 使用相同 Python 入口：先验证 JDK17 / Node / Python / Docker / Compose，执行无设施 Python 公共边界检查，再运行上述 `all`，最后从新隔离 project 构建真实最终镜像并执行完整 Compose 冒烟。任何层失败或必测项跳过均使入口非零退出，性能数字不作为门槛。

最终镜像验收包括初始化/重复启动/持久卷/policy/消费、Redis重建和受控恢复、MQ隔离、真实健康/安全日志和低基数指标，以及生成OpenAPI/Swagger UI、HEAD和各错误契约；原核心/缓存/MQ/统计与限流/回源故障由全部JUnit回归覆盖。Compose单独入口为 `pwsh -NoProfile -File ops/compose/smoke.ps1` 或 `sh ops/compose/smoke.sh`，采用独立随机应用镜像tag，避免覆盖演示或测量镜像。

`target/ci/<运行标识>/reports/` 只收集本轮白名单安全文字证据；CI无论成功或普通失败均上传该目录，不上传整个target、秘密文件、raw inspect/expanded config或容器镜像。清理与取消边界、固定Actions来源及本轮实际执行范围见 [CI说明](ci.md)。已配置工作流与已经获得GitHub托管运行报告分别记录，不把本地等价验证声称为远程成功。