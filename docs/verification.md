# 正式验证入口

本页汇总最终工程验收与有限观察；证据JSON随仓库提交，个人工作目录和临时记录不是唯一证明。运行原始JUnit/安全日志由脚本保存在被忽略的target目录，CI仅上传本轮白名单产物。

## 当前完整正确性验收

2026-10-04，源码 `c4b47a3f31710fec5d99ac78ae4586c17e402716`（sourceDirty=false），Windows11 / PowerShell7 / Corretto17.0.17 / Node24.19.0 / Python3.14.0，Docker29.8.1 / Compose5.5.1。通过本地等价CI入口执行，详见 [提交的安全摘要](evidence/final-ci-2026-10-04.json)。

| 层 | 实际数量 | 失败 / 错误 / 跳过 |
| --- | ---: | --- |
| Java无设施/真实HTTP | 146 | 0 / 0 / 0 |
| Java真实设施主回归 | 149 | 0 / 0 / 0 |
| Java消费者 | 13 | 0 / 0 / 0 |
| Java生命周期 | 9 | 0 / 0 / 0 |
| Node页面 | 2 | 0 / 0 / 0 |
| Python公共CLI/报告 | 4 | 0 / 0 / 0 |
| 最终镜像Compose断言 | 136 | 全部通过 |

Java合计317，缺失/额外测试类为空。Compose覆盖冷初始化、独立秘密、201/302/PV/UV、卷和积压重启、实际policy/consumer、Redis受控重建、MQ/Redis故障仍核心启动跳转、MySQL故障readiness、端口隔离、预算、non-root、OpenAPI、HEAD、低基数指标和Tomcat原始解析异常隐私。不是历史263或314项的转述。

[CI配置与取消/清理限制](ci.md) · [重新执行分层回归](testing.md) · [重新执行完整Compose](compose.md)。GitHub Actions工作流已配置并通过actionlint，但本次未push/触发托管run；原生Linux宿主完整入口未实跑。Windows运行Linux容器不替代这两项证明。

## 有限性能与故障观察

源码 `c7c8395adfbd2bfe0d6eef22915ba96c5e816aa3`，run `09e444c16b63`，47项行为检查通过。每种健康命中、不同码miss、MQ停机、Redis停机各240个HEAD样本，最大并发4，计划每秒8请求；实际映射SQL分别0 / 240 / 0 / 240（表格原profile顺序见JSON）。HEAD不采集统计。MySQL general_log各组均启用，观察本身有开销；资源采样可能遗漏峰值，p50/p95不代表稳定p99或最大吞吐。

[完整条件与结果](performance-and-failures.md) · [提交的实际观察JSON](evidence/finite-observation-2026-10-04.json)。max4在途保护、OOM/noeviction和故障等待另作受控注入；临时缩紧Redis容量不是修改默认资源预算。旧并发1、30预热/300顺序GET及55%均值变化仅属于历史版本，不作为新版本结论。

## 历史与版本边界

01～08的历史验收保留在版本控制的 [.scratch记录](../.scratch/final-hardening/04-08-verification.md)：314 Java+2 Node及100 Compose属于49d34d7源码，25541f0审查修复后仅9项针对性补验。当前上面的317+2+4和136断言已包含这些修复后的生产源码，不能混淆两次结果。ADR记录设计选择，不是执行成功证明。

12只改文档/截图；截图来自635ffef集成源码、仅文档未提交修改（sourceDirty=true）重新构建的冷启动本地隔离项目。应用源码与完整CI的c4b47a3一致。其真实HTTP/页面、初始化、资源清理与链接检查见 [展示验收](evidence/readme-demo-2026-10-04.json)。不为纯说明改动重复声称运行全部Java。
