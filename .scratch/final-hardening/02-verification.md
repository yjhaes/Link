# 02：可复现既有回归验证

日期：2026-10-04（Asia/Shanghai）。实现分支 `codex/hardening-02` 从 integration `b8aae4a` 开始，随后同步任务01集成提交 `0c3b5aa`，未使用历史263项作为当前证明。

| 运行 | 入口 / 内容 | tests | failures | errors | skipped | 退出 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| RED | AsyncVisitRoundtrip新增HTTP往返，独立不可连接MySQL地址 | 1 | 0 | 1 | 0 | 1 |
| e906bf1f3203 | Windows `ops/tests/run.ps1 all` / unit | 112 | 0 | 0 | 0 | 0 |
| 同轮 | integration-main | 134 | 0 | 0 | 0 | 0 |
| 同轮 | integration-consumer | 13 | 0 | 0 | 0 | 0 |
| 同轮 | integration-lifecycle | 9 | 0 | 0 | 0 | 0 |
| afacfed07f32 | 最新入口unit补验 | 112 | 0 | 0 | 0 | 0 |

完整本轮Java 268项通过，覆盖全部发现的既有顶层测试类及新增HTTP往返；零失败、错误、跳过。新增往返在真实数据库与broker中从POST创建开始，检查302 Location，随后以管理HTTP查询最终PV1/UV1；没有将设施健康视为业务通过。

首轮测试记录没有藏匿失败：无设施入口最初受全局采集/vhost环境注入污染，修正为仅集成阶段注入连接设置；初次真实main因test profile开启采集导致2条既有默认关闭断言失败，修正profile默认false，采集类保持自己的显式true。未弱化既有断言，后续上述完整运行全绿。

原始安全报告在各工作树 `target/regression/<运行标识>/`，不提交生成日志/XML。每组summary记录tests/failures/errors/skipped及退出码；入口核验实际执行类集合，missing report / 零测试 / 跳过均失败。生成密码由内存产生，并从保存输出中脱敏。没有将私人连接配置带入进程。

e906bf1f3203的finally清理日志记录三容器及本project网络已删除，实际按project label查询无剩余容器。清理没有删除个人容器或数据。强制终止/daemon不可用的手工限定清理见 `docs/测试与验证/testing.md`。

Linux交付 `sh ops/tests/run.sh <suite>`，使用标准库Python共享实现，不需要PowerShell；本轮宿主没有Linux真实运行环境，未声称Linux全设施验收。Node页面测试入口在任务03文件合并后执行，尚待集成分支复验。全栈演示/CI/后续限流和回源验收由后续任务负责。

后续集成更新：任务03合并后，集成工作树运行同一 `all` 入口，Node页面测试1项和Java282项均通过。原始报告已保留到集成工作树，审查修复后的30项针对性验收另列，见 [01～03 集成验证](01-03-verification.md)；此处268项记录仍指本任务原始运行。
