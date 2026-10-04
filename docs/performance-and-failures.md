# 有限性能观察与故障演示

2026-10-04 在 Windows 本机完成 47 项真实断言；唯一正式运行 `09e444c16b63`，冻结源码 `c7c8395adfbd2bfe0d6eef22915ba96c5e816aa3`，启动时工作树干净。包含已验收的分组限流、回源保护、健康/指标和 OpenAPI。完整安全摘要见 [JSON 证据](evidence/finite-observation-2026-10-04.json)，原始分类样本、Docker/CLI 输出保存在该运行的 `target/observations/09e444c16b63/`。文档提交及后续纯 CI/展示整理不是这次测量 SHA，不将旧试跑或失败运行混为正式结果。

## 重复入口与隔离

需要 Python 3.10+、Docker 和 Compose 2.24.4+，首次构建需联网。Windows 使用 PowerShell 7：

```powershell
pwsh -NoProfile -File ops/observe/run.ps1 -Samples 240 -Rate 8
```

Linux 入口：

```sh
sh ops/observe/run.sh --samples 240 --rate 8
```

入口复用 [秘密初始化](../ops/init-local-secrets.ps1)、[四服务 Compose](../compose.yml) 与既有 RabbitMQ bootstrap/policy。每次独立随机项目、镜像 tag、localhost 动态端口、MySQL/RabbitMQ 卷及五项秘密；不连接个人设施。最终停止并只删除自己的容器/卷，删除临时秘密；失败明确非零退出，报告保留已分类结果和失败原因，设施缺失不跳过。Linux wrapper 的 shell 语法已核验；完整链本次是在 Windows + Linux 容器执行，未声称独立原生 Linux 宿主实跑。

新脚本只有报告与测试设施职责，不增加生产观测接口或监控组件。无设施检查为 `python -m unittest discover -s ops/observe -p 'test_*.py'`：2 项通过，分别验证业务成功与拒绝吞吐分离、非法 CLI 样本在准备设施前失败。开发阶段修正了压力填充与 SQL 统计口径；最终 47 项结果来自重新冻结后的完整新运行。

## 环境与口径

- 宿主 Windows 11 10.0.22621、Intel i5-12450H、12 逻辑 CPU、物理内存 16,944,914,432 bytes；Docker Desktop Linux/WSL2 内核 6.18.40.1、12 CPU、引擎内存 8,209,801,216 bytes。Python 3.14.0、Docker Server 29.8.1、Compose 5.5.1。运行时 Temurin 17.0.14+7；MySQL 8.4.4、Redis 7.4.2、RabbitMQ 3.13.7。基础镜像与实际应用 image digest/平台在 JSON 中逐项保留。
- 默认容器预算 app768MiB/Java 堆384MiB、MySQL/RabbitMQ 各1GiB、Redis256MiB；Redis maxmemory128MiB/noeviction、不持久化。核心池max8、统计池max4、实际跳转加载max4。没有更改这些生产起点；这次运行不能证明最低硬件要求或容量。
- 固定默认创建容量3/每6秒补1，跳转容量60/每100ms补1；管理写/查询容量5/每秒补1。全部 HTTP 来自同一宿主，经实际 Docker 连接对端共享跳转桶；不利用 Cookie、短码或伪造转发头绕额度。
- 直接在独立 MySQL 播种256条永久、启用的映射用于采样；这不测试 HTTP 发号性能。另用真实创建入口演示发号、保存、限流和状态。健康 hit 使用一个预热短码；distinct-miss 使用240个已经存在、从未缓存的不同短码，全部预期302，没有把不存在码404算成成功吞吐。
- 每个路径240次 HEAD，最多4个客户端工作线程，计划到达速率8次/秒、预热1次、每次新建 loopback HTTP 连接，耗时从实际工作线程发起到读完响应计算。HEAD 不产生访问事件；异步 PV/UV 用独立 GET 演示验证。线程池可能排队，因此不能将此工具当开放环饱和压测器。
- 通过唯一临时 MySQL 的 `general_log=ON/log_output=TABLE` 统计项目用户的映射 SELECT `Query/Execute`；排除 `Prepare`、root 观察 COUNT、SELECT1 健康查询和 visit_log 统计。只输出数量，不导出 arguments/原 SQL/参数。日志开销在四场景一致启用，仅临时卷保留并随清理删除，不修改常规 Compose 数据库设置。早期 digest 汇总与请求数出现一次不一致的试跑已失败，未使用其数字。
- 在途/核心和统计连接是每100ms读取既有指标的采样最大值；不是 SQL 次数。Docker CPU/内存是前/中/后三个点样本，不是全程峰值或均值。指标请求、general_log、Docker 采样都有成本；测量阶段仅轻量代码整理，没有并行 Maven/Docker 回归。顺序运行、JIT/缓存预热和宿主调度未随机化或配对，不从 MQ-off 更快推断优化收益。

## 本次分布与资源

最近秩 p50/p95，单位 ms；只描述本次各240个成功样本的经验分布，不计算或宣称稳定 p99。

| 路径 | 302/429/503 | 成功p50/p95/max ms | 成功数/测量秒 | 实际映射SELECT | 采样加载在途最大 |
| --- | --- | --- | --- | --- | --- |
| healthy-hit | 240/0/0 | 7.78/21.30/33.98 | 8.03 | 0 | 0 |
| healthy-distinct-miss | 240/0/0 | 8.41/24.90/33.96 | 8.03 | 240 | 1 |
| mq-off | 240/0/0 | 4.96/15.49/28.43 | 8.03 | 0 | 0 |
| redis-off | 240/0/0 | 405.98/421.77/1230.35 | 7.98 | 240 | 1 |

8次/秒是人为输入，不是应用最大吞吐。Redis 停机的成功响应有实测等待，不能把单条200ms Redis 预算当作 HTTP 总截止；创建拒绝的实测耗时为 206.13ms，错误码 `RATE_LIMIT_UNAVAILABLE`。

| 路径 | app中点CPU | app中点内存/预算 | 核心/统计连接采样最大 |
| --- | --- | --- | --- |
| healthy-hit | 12.77% | 455MiB / 768MiB | 0/0 |
| healthy-distinct-miss | 10.85% | 459.5MiB / 768MiB | 1/0 |
| mq-off | 6.26% | 465.9MiB / 768MiB | 0/0 |
| redis-off | 17.22% | 481.1MiB / 768MiB | 1/0 |

MySQL/Redis/RabbitMQ 的三时点数据也保留在 JSON；停机组件不会假造零用量。低采样在途值可能漏掉短查询，真正 max4 证明使用下面的控制交叠。

独立120次、16线程的缓存 HEAD 短突发：61个302、59个429、0个503，约0.120秒结束。429 保持 no-store、Retry-After 和无 Cookie。此短段消耗初始桶，不能据瞬时成功数/秒宣传持续吞吐，更不能把120个快速响应全部算业务成功。

## 故障边界与演示顺序

同一入口按下列步骤自动执行，不依赖“容器存在”判定业务正常：

1. 新初始化/构建/实际就绪后匿名 POST 返回201，GET 返回302、Location/no-store/Cookie；同 Cookie 再访问一次，管理统计最终查询到 **已记录 PV2/UV1**。
2. 无管理头写状态返回401；正确头禁用后 HEAD403，重新启用恢复302；连续创建出现429/Retry-After。主 livez/readyz 只给UP，管理依赖组健康。HEAD 性能样本不计 PV。
3. 健康 hit/不同码 miss 采样；缓存短突发分别统计成功与拒绝。真实停止 RabbitMQ 后 GET 仍302、核心 ready仍UP；MQ-off HEAD采样不证明故障统计完整入账。
4. 实停 Redis 后创建返回业务前503 `RATE_LIMIT_UNAVAILABLE`；livez/readyz 仍UP，依赖组503。成功 HEAD 的实际查询数和等待另列，Redis fail-open 继续受真实回源准入约束。
5. 由独立 MySQL 写锁冻结四个不同映射读取，SQL metadata/threads 独立观察到4个真实阻塞SELECT、应用实际加载在途4；第五GET得到 `REDIRECT_LOAD_BUSY`503，无Cookie，已接受访问事件计数 **3→3**。释放锁后四个HEAD全部302，实际Query/Execute增量恰4，拒绝第五请求没有SQL，所有许可释放，后继请求成功。第五整个HTTP实测205.90ms包含前置Redis失败等待；“立即无排队”指加载准入，不指整个HTTP零等待。
6. 停止全部应用 writer，删除仅本项目的非持久Redis容器，空 Redis 验证后启动应用；MySQL映射保留，原128MiB配置恢复，正常HEAD302。
7. 在未修改的 **128MiB/noeviction** 下，专用 `evidence:pressure:` key 的逐条独立1MiB SET在第101次明确OOM后停止，不重试失败写、不删版本/桶、不默许淘汰。大 SET 的 OOM **不表示微小Lua写必定失败**。为稳定注入“小写同样被拒绝”，仅将这一临时项目 maxmemory 从134,217,728收紧到 **131,395,704 bytes**（测得used_memory **132,444,280 bytes** 减1MiB）。该步骤单列为故障注入，非生产起点修正。此时实际创建503且发号表/映射数都不变，跳转仍302。最小独立Redis预验也用原限流Lua确认OOM且新桶不存在。
8. 再停止全部writer并重建空Redis，恢复 **128MiB**；映射302、全部依赖健康恢复，然后只清理本项目容器/卷/秘密。没有自动DLQ重放，也不承诺统计补全或跨故障持久配额。

## 可用结论与限制

本次证据支持缓存 hit 降低实际映射查询、不同码 miss/Redis 故障需要回源，令牌桶和 max4 控制的是不同阶段；可展示 Lua 原子性、明确的429/业务前503、失败放行后的并发边界及受控恢复。它不证明生产SLA、万级QPS、端到端恰好一次、稳定尾延迟或不丢统计。

历史异步版本只有并发1、30次预热+300次顺序GET的旧小样本；新路径包含限流等额外成本，本次是 HEAD、固定到达率、有观察开销的独立新测量。**不复用旧“55%均值下降”作为新增限流版本结论**，也不将本次瞬时突发数率写成简历容量承诺。需要稳定尾延迟或真实容量结论时，应另行设计重复/随机化测量和更足的样本。
