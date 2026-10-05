Status: resolved
Type: task
Blocked by: None

## Parent

[阶段 8 实现规格](../spec.md)

## What to build

维护者能在干净环境用明确入口运行无设施测试和既有真实设施回归，不需要私人MySQL或RabbitMQ，且看到真实失败和安全报告。

## Blocked by

None（无前置依赖；执行需用户另行授权）。

## 故事覆盖

45、47，以及既有测试可复现性。

## Acceptance criteria

- [x] 无设施单元/MVC与真实设施集成有明确入口和设施要求，提供Windows pwsh与Linux运行方式。
- [x] 数据库、端口、vhost与演示/个人环境隔离，失败定位及设施清理明确，不删除个人数据。
- [x] 现有创建/跳转、缓存、管理、MQ幂等/故障、统计查询/清理和生命周期回归能够完整运行；至少确认一个真实创建→跳转→异步统计往返。
- [x] 启停和报告不打印秘密；测试结果记录失败/错误/跳过，历史263项通过不作为本任务运行证据。
- [x] 不为统一形式重写全部测试，不在本任务修改生产默认配置；测试配置显式、与任务01解耦。

## Comments

- 2026-10-04：用户确认 12 项拆分的粒度与依赖后发布。依据已接受的阶段 8 规格；本次仅发布任务，未认领或实施。执行时遵循任务生命周期，验收全部满足后在 Answer 中记录证据再标为 resolved。

## Answer

- 2026-10-04：在独立分支实现 `ops/tests/run.py`，Windows `run.ps1`、Linux原生 `run.sh` 两个入口；unit/integration/all明确分层。README入口与 `docs/测试与验证/testing.md` 记录依赖、隔离、失败定位和清理。
- test profile显式关闭默认采集，配公开测试管理/HMAC及Rabbit凭据；真实采集类自行显式开启。直接Rabbit测试工厂使用本轮项目账号。测试入口清除继承的应用连接/秘密及JVM启动覆盖，随机密码从保存的日志/XML脱敏。
- 新增 `AsyncVisitRoundtripTest.createdLinkRedirectsAndBecomesVisibleThroughStatsApi` 从公共POST创建→GET302→管理HTTP异步stats PV=1/UV=1，真实MySQL/Redis/RabbitMQ运行；RED为隔离不可连接地址的1error，GREEN为真实设施的3项往返/缓冲/身份测试。
- 合并任务01后，正式Windows `pwsh` wrapper `all` 本轮报告 `target/regression/e906bf1f3203/`：unit112，integration-main134，integration-consumer13，integration-lifecycle9，共268项，failures/errors/skipped均0。额外最新版unit报告 `afacfed07f32/`：112项，0/0/0。覆盖现有创建/跳转、缓存、管理、MQ幂等/故障、统计查询/清理与生命周期；不是历史263项证明。
- unit/all在后续任务03的Node页面测试文件存在时纳入Node回归，缺Node/失败/跳过明确fail；当前该文件尚未合并，后续集成验收需验证。Linux提供原生shell入口与共享Python逻辑，但本轮只有Windows宿主，未声称Linux真实设施已实跑。任务03/其余后续能力的验收不属于本条现有回归证明。

完整本轮记录见 [02验证记录](../02-verification.md)。
