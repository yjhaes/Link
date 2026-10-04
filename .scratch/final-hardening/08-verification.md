# 08：安全日志与最小指标验收

日期：2026-10-04（Asia/Shanghai）。生产及测试冻结版本为 `49d34d7004d04facd76a0f617771700c887565a8`，已包含最终框架日志保护与异常退出计数修复；后续只整理证据。

## 真实行为与结果

- 28 项针对性测试通过：真实双端口请求和指标查询、四组限流结果、获准后400不退额度、4个实际SQL在途及第5个无Cookie的503、查询结束释放、缓存失败及同实例恢复、服务端requestId与MDC异常清理、已提交协调短码定位、任意路径/method标签、未知清理观察、业务/Redis/JDBC/MQ错误canary及高频驱动/HTTP框架日志有界性。
- 最终冻结源码重新构建后，真实 Compose 项目 `link-smoke-cfda2b0c28b6` 的100项断言通过、failure=null，项目已清理。包括真实请求→429/缓存故障/Redis停机→安全日志及管理指标查询，MQ confirm与数据库保存分别观察，HEAD不增加访问事件或逐条成功INFO、缓存同实例恢复、生命周期、原有持久化/人工暂停及健康故障矩阵。见 [安全结构化摘要](08-observability-summary.json)。首轮100断言的旧镜像结果未替代最终源码验收。
- 同一冻结源码完整回归：Java314（无设施143、真实设施主回归149、消费者13、生命周期9）及Node页面2项全部通过。失败/错误/跳过全0，Java所有分组missingClasses与unexpectedClasses均为空。入口为 `pwsh -NoProfile -File ops/tests/run.ps1 all`，隔离项目 `link-tests-c7f76b81cf37` 已清理。

安全原始报告已复制到主工作树：`target/ticket08-final-focused`、`target/regression/c7f76b81cf37`、`target/compose-smoke/cfda2b0c28b6`。临时秘密已删除；不提交大型原始日志。

## 语义与限制

指标标签仅使用模板路由、固定请求组及类别，自动HTTP观察也收敛到固定标签；requestId/短码/IP/任意method与路径都不作为标签。复用现有MQ/统计/清理snapshot，broker confirm不是数据库保存，累计均速不是窗口吞吐，处理延迟不是HTTP延迟，未知清理观察不是零。broker ready/unacked仍由受限Management核验。

安全业务日志与明确依赖/HTTP框架编码边界采用有界采样，保留降级恢复、生命周期、固定失败类别及必要协调短码；这不是全局自动脱敏或完整分布式追踪。单位、标签与保护范围见 [观测说明](../../docs/observability.md)。Windows PowerShell7与Docker Linux容器实跑，原生Linux宿主完整入口未实跑；资源轻载观察不构成性能或生产SLA。
