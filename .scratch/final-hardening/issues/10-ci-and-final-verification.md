Status: resolved
Type: task
Blocked by: 02, 08, 09

## Parent

[阶段 8 实现规格](../spec.md)

## What to build

新提交在CI自动准备隔离环境并完成全部必需检查，评审者能查看真实成功/失败报告，完整新版本能从干净环境运行。

## Blocked by

- [02：自动准备隔离设施并复现现有回归](02-reproducible-integration-tests.md)
- [08：请求与异步结果的安全日志和最小指标](08-safe-logs-and-metrics.md)
- [09：业务OpenAPI与页面错误契约](09-api-docs-and-error-ui.md)

## 故事覆盖

45～47，以及完整最终版本验收。

## Acceptance criteria

- [x] 验证原核心/缓存/MQ/统计与新增全部限流、回源、秘密、健康、日志、API契约；缺设施明确失败，不静默跳过必测项。
- [x] 测试隔离不碰个人数据，报告列环境/版本、结果及失败/错误/跳过，不打印秘密。
- [x] 产出本轮完整验证证据，不将历史263项通过当本次结果；不追求机械100%覆盖率。

## Comments

- 2026-10-04：用户确认 12 项拆分的粒度与依赖后发布。依据已接受的阶段 8 规格；本次仅发布任务，未认领或实施。执行时遵循任务生命周期，验收全部满足后在 Answer 中记录证据再标为 resolved。
