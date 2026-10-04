Status: resolved
Type: task
Blocked by: 02, 08, 09

# 10：CI自动回归与全栈最终冒烟

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

- [x] 基础GitHub Actions自动准备设施，运行无设施测试、真实设施回归和完整Compose冒烟；提供等价本地入口。
- [x] 验证原核心/缓存/MQ/统计与新增全部限流、回源、秘密、健康、日志、API契约；缺设施明确失败，不静默跳过必测项。
- [x] 干净初始化、持久卷重复启动、实际policy/消费者、Redis重建/受控恢复及MQ故障隔离可验证。
- [x] 测试隔离不碰个人数据，报告列环境/版本、结果及失败/错误/跳过，不打印秘密。
- [x] 产出本轮完整验证证据，不将历史263项通过当本次结果；不追求机械100%覆盖率。
- [x] CI以正确性为门槛，不将不稳定性能数字作为强制通过条件，不在本任务部署公网或发布镜像到外部账号。

## Comments

- 2026-10-04：用户确认 12 项拆分的粒度与依赖后发布。依据已接受的阶段 8 规格；本次仅发布任务，未认领或实施。执行时遵循任务生命周期，验收全部满足后在 Answer 中记录证据再标为 resolved。

## Answer

- 2026-10-04：10完成。新增固定完整SHA的基础GitHubActions与Windows/Linux等价正确性入口，全部设施自动隔离准备，缺设施/缺类/零测试/失败/错误/跳过不成功；performance数据不作为CI门槛，无远程部署或发布。
- 冻结源c4b47a3的实际完整统一入口exit0：Python4、Node2、Java317全部通过，失败/错误/跳过0及Java类集合完整；重新构建最终镜像136项Compose验收通过，含09契约/UI/HEAD及08审查修复后pre-filter Socket隐私验收。
- 两个project的容器/卷/网络、临时秘密与随机app镜像tag均已实查清理；81文件安全artifact仅白名单本轮证据，未混入env/raw inspect/config/镜像。当前仅本地等价实跑，未远程GitHub托管run、未原生Linux宿主全量；详见[10验收](../10-verification.md)及[紧凑JSON](../10-ci-summary.json)。
