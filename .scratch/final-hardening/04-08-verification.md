# 04～08 集成验证记录

本轮授权仅包含 04～08；09～12 尚未实施，父规格未整体关闭。

集成分支：`codex/final-hardening-04-08`。固定审查基点：`4cdef745321e65325419493811a5482a60bd67bc`（01～03 已完成版本）。实现使用当前模型；完成实现后的 Standards Review 与 Spec Review 按用户要求使用 GPT-6.1 Sol / high。

## 已完成切片

- [04 跳转限流和实际回源保护](04-verification.md)：85 项针对性验收通过。
- [05 管理写入和查询限流](05-verification.md)：20 项重点验收及该切片无设施回归通过。
- [06 可复现全栈 Compose](06-verification.md)：34 项真实全栈部署断言通过。
- [07 分组健康和管理端口](07-verification.md)：64 项真实 Compose 断言及 9 项针对性兼容测试通过。
- 08 正在实施，完整验收及双轴审查结果将在合并后追加。

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

运行环境为 Windows PowerShell 7、Docker Linux 容器；原生 Linux 宿主的完整入口尚未实跑。容器资源观察仅为轻负载单点，不能推出峰值、最低机器要求或生产 SLA。
