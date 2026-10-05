# 04～08 集成验证记录

本轮授权仅包含 04～08；09～12 尚未实施，父规格未整体关闭。

集成分支：`codex/final-hardening-04-08`。固定审查基点：`4cdef745321e65325419493811a5482a60bd67bc`（01～03 已完成版本）。实现使用当前模型；完成实现后的 Standards Review 与 Spec Review 按用户要求使用 GPT-6.1 Sol / high。

## 已完成切片

- [04 跳转限流和实际回源保护](04-verification.md)：85 项针对性验收通过。
- [05 管理写入和查询限流](05-verification.md)：20 项重点验收及该切片无设施回归通过。

## 04～07 集成回归（08 之前）

源码版本：`ed1803ef156f4c02beaa5b50b5a51140fcbe5eb4`。入口：`pwsh -NoProfile -File ops/tests/run.ps1 all`。隔离项目 `link-tests-9fa5b61d62a3` 已清理。

| 测试层 | 数量 | 失败/错误/跳过 |
| --- | ---: | --- |
| Java 无设施 | 137 | 0/0/0 |
| Java 真实设施主回归 | 149 | 0/0/0 |
| Java 消费者 | 13 | 0/0/0 |
| Java 生命周期 | 9 | 0/0/0 |
| 页面 Node | 2 | 0/0/0 |

Java 合计 308，页面合计 2；每个 Java 分组的 missingClasses 和 unexpectedClasses 均为空。安全原始报告保留在主工作树 `target/regression/9fa5b61d62a3`。这些结果属于 08 合并之前的版本，不能作为 08 最终版本已通过的证据。

容器资源观察仅为轻负载单点，不能推出峰值、最低机器要求或生产 SLA。

## 04～08 最终源码验收

最终生产及测试源码为 `49d34d7004d04facd76a0f617771700c887565a8`；集成合并版本为 `25376127bb8446ac13ce24eca074618fd6ed2440`，差异仅合并与纯证据文件，无后续生产/测试修改。完整入口由主代理在08工作树执行，报告已安全复制到主工作树。

| 测试层 | 数量 | 失败/错误/跳过 |
| --- | ---: | --- |
| Java 无设施 | 143 | 0/0/0 |
| Java 真实设施主回归 | 149 | 0/0/0 |
| Java 消费者 | 13 | 0/0/0 |
| Java 生命周期 | 9 | 0/0/0 |
| 页面 Node | 2 | 0/0/0 |

Java合计314与页面2均通过，所有Java分组missingClasses及unexpectedClasses为空。最终隔离项目 `link-tests-c7f76b81cf37` 已清理，报告 `target/regression/c7f76b81cf37`。28项重点测试报告 `target/ticket08-final-focused`；详细版本及语义边界见各切片证据。

## 双轴审查

固定基点 `4cdef745321e65325419493811a5482a60bd67bc` 到集成 `25376127bb8446ac13ce24eca074618fd6ed2440`；Standards Review和Spec Review均显式指定GPT-6.1 Sol / high，独立并行执行。Standards发现1项旧运维日志语义文档回归；Spec发现1项P1 Tomcat请求解析INFO异常日志泄露。统一修复提交为25541f0，两个原审查代理focused复核后均为0项剩余问题。
### 审查修复后的验收版本

修复提交 `25541f0` 更新控制台HTTP框架携Throwable事件保护、真实原始Socket隐私验收及采样文档。Socket测试修复前确实失败，暴露原始查询canary；修复后9项针对性测试全部通过（失败/错误/跳过0），包括5项真实HTTP流程、编码器、请求上下文清理和双端口健康。报告保留在 `target/review-fixes-green` 与 `target/review-fixes-green.log`。

随后仅整理文档及文件尾多余空行，无生产或测试语义改动。两个复核代理仍为GPT-6.1 Sol / high，Standards剩余0、Spec剩余0。

04～08均resolved，09～12未开始；父规格仍保留整体未完成状态。五个临时工作树归档前，必要安全原始报告已复制保留在主工作树target，归档不影响集成分支源码和提交。
