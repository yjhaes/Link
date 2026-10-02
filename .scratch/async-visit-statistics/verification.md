# 异步访问统计实施验证

## 固定基线

- 实施起点：`8a3d7ebfcf34fd02378cf30ebb7b08bb0f222294`。
- 集成分支：`codex/async-visit-statistics`。
- 实施模型沿用当前模型；最终 Standards Review 与 Spec Review 使用 GPT-6.1 Sol / high。
- 原规格的“本次不实施”指此前规格发布阶段。本次用户显式调用 implement-spec，授权按已发布的八张任务实施。

## 同步基线验证

2026-10-02，在隔离 MySQL 8.4 上运行 VisitStatisticsApiTest、VisitStatsQueryApiTest、VisitLogsApiTest：32 项通过、无失败和跳过。

实施前同步可执行包保存在本机 `.tools/async-baseline/short-link.jar`，原始测试日志、测量 JSON 同目录保存，不提交包含框架原文的日志。

## 对比方法与同步样本

本机 Java 17，MySQL 8.4 单节点容器，Redis 7.2 单节点容器；HTTP loopback；统计开启，同一匿名 Cookie，正常有效映射，30 次预热后 300 次顺序 GET（并发 1），HttpClient 关闭自动跳转，核验 302 和目标地址。测量包含客户端请求与响应开销，缓存命中。通过独立 `short_link_benchmark` 数据库避免回归测试干扰。脚本：[measure-http.ps1](measure-http.ps1)。

| 版本 | 平均/ms | p50/ms | p95/ms | p99/ms | 测量总耗时/ms | 查询时可见 PV/UV |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 同步基线 | 7.386 | 7.267 | 8.929 | 9.945 | 2244.951 | 330/1（含预热） |

此结果仅为一个小样本开发环境观察，不代表生产吞吐、稳定 p99、尖峰能力或 SLA。异步版本应使用相同环境与脚本，并报告事件最终可见情况、丢弃及处理延迟；尚未获得异步结果，不能宣称性能收益。

## 已合入切片

- 01：明确同步持久化结果和安全失败类别；相关真实 MySQL / HTTP / 查询 / 清理共 52 次测试执行通过。提交 `70fdd03`，集成合并 `6f08548`。完整验收见 [任务 01](issues/01-visit-persistence-outcomes.md)。

## 最终验收

待所有实现切片、整体测试和双轴审查完成后记录。未验证的条件必须明确保留，不能用单元替身结果冒充真实网络故障或 broker 验收。
