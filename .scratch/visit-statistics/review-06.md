# 任务 06 代码审查

- 固定基点：`013d2eadaa390dd1e36e7d05854f3f91f44b609b`（本轮实施开始时的 HEAD）。
- 差异：`git diff 013d2eadaa390dd1e36e7d05854f3f91f44b609b...HEAD`。
- 实现提交：`561707e`；修复提交：`9e2d32b`。
- Standards Review 与 Spec Review 两个独立子代理均显式使用 GPT-6.1 Sol、reasoning effort high；实施使用当前模型。

## Standards

首次审查：没有文档标准硬性违规；1 项低优先级判断意见，清理与查询重复表达30日窗口，存在 Duplicated Code / Shotgun Surgery 风险。

已通过 `StatsDateRange.earliestRetainedDate()` 统一窗口规则。复审完整差异后剩余 **0 项**；统计池归属、许可释放、独立提交、调度销毁和停采维护符合 ADR-0006。

## Spec

首次审查和复审均无确定的规格遗漏、错误实现或范围扩张，剩余 **0 项**。

两位审查者均提示大积压下全量 COUNT 超时可能阻碍追赶。已改为沿清理索引有界读取1001行，并以 `backlogLowerBound` 明确表示计数下界；文档与真实 MySQL 验证同步更新。

## 验证说明

专项6项通过，覆盖受控时钟/调度/预算/容量，以及真实 MySQL 的窗口、独立提交、重启、锁失败、并发实例、受保护停采历史和核心数据保护。

首次完整回归212项中1项失败，原因是在清理后仅剩1行的表上断言优化器必须选择清理索引。已将执行计划验证移到有代表性的1101行积压、更新统计信息后；该场景真实 MySQL 选择 `idx_visit_cleanup`。最终完整回归结果记入任务 Answer。

最终发现：Standards 0 项；Spec 0 项；两个轴均无剩余问题。
