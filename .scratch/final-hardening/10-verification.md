# 10：CI 与最终全栈验收

2026-10-04，用户授权09～12。10在独立分支`codex/hardening-10`实施，基于已完成09的集成版本，执行前再合并11及最新集成。测试源码冻结为 **`c4b47a3f31710fec5d99ac78ae4586c17e402716`**，统一入口报告确认`sourceDirty=false`；以下全部最终测试及镜像属于该版本，后续仅写验收证据和任务状态。

## 实际运行

入口：`pwsh -NoProfile -File ops/ci/run.ps1`，CI run `a3b7acbf7e1e`，整体exit0、failure=null。脚本按顺序执行公共Python正确性、全部Node/Java回归、重新构建最终应用镜像的Compose验收，不用性能数字作为门槛。安全紧凑摘要见[本轮JSON](10-ci-summary.json)。

| 层 | 数量 | 失败 / 错误 / 跳过 |
| --- | ---: | --- |
| Python CI 公共CLI边界 | 2 | 0 / 0 / 0 |
| Python 有限观察报告公共边界 | 2 | 0 / 0 / 0 |
| Node 页面契约 | 2 | 0 / 0 / 0 |
| Java 无设施 / 真实HTTP | 146 | 0 / 0 / 0 |
| Java 真实设施主回归 | 149 | 0 / 0 / 0 |
| Java 消费者回归 | 13 | 0 / 0 / 0 |
| Java 生命周期回归 | 9 | 0 / 0 / 0 |

Java合计317，每个分组missingClasses与unexpectedClasses均为空；不是历史263/314项的转述。回归run/project `10be088bdf9a` / `link-tests-10be088bdf9a`。原核心/缓存版本协议与加载合并、实际MQ/统计、全部限流/回源、秘密/健康/安全日志/API契约均在本轮既有及新增顶层测试类中实际执行，不因缺设施跳过。

最终Compose project `link-smoke-2f8ecc2ff4da` **136条实际断言通过**，failure=null。包括空数据初始化及独立秘密、201/302和异步PV/UV、普通持久卷重启/积压保留与同UV恢复、真实policy/路由/consumer、Redis停写后清空重建和受控恢复、Redis/MQ停机后核心启动跳转、MySQL故障readiness503/liveness200及恢复、端口/预算/non-root/危险Actuator隔离、真实低基数指标和安全日志。

本票在最终镜像另外查询实际OpenAPI的5条业务路径与8操作、HEAD无content、管理头、429响应头以及六类不同503；查询真实Swagger配置`persistAuthorization=false`及UI资源无预填授权，管理端口没有业务OpenAPI。实际HTTP补验HEAD302无Cookie/body，非法短码与管理401的HEAD无体，Redis实际停机后管理HEAD503无体，创建业务前503不含已提交短码。原始Socket发送Tomcat滤器之前非法请求目标得到400，最终控制台只保留`category=http`，不输出parser-query-canary或原始解析异常；此项覆盖08审查修复后的真实最终镜像。

## TDD 与自动化配置

公共报告收集CLI先失败（入口尚不存在），实现后验证仅白名单文本产物被复制，中断遗留.env密码被替换，env/inspect/config/ports/image不上传。公共CI预检先失败（入口尚不存在），实现后以真实不可达的Docker端点证明明确非零失败，不能称作测试通过或静默skip。最终统一入口实际包含这2项及11的2项便宜报告正确性检查。

GitHub Actions使用Ubuntu24.04、Java17/Node24/Python3.13；宿主Docker依据官方runner清单并再次真实预检。五个官方Actions以完整SHA固定，contents:read、checkout不保留凭据，正确性入口普通成功/失败均always上传明确安全目录。官方actionlint **v1.7.12** 实检`.github/workflows/correctness.yml` exit0、诊断为空。版本来源与取消/超时的清理限制见[CI说明](../../docs/ci.md)。

本次**未push或触发远程GitHub Actions**，工作流为已配置，本地等价入口为已真实通过；不声称已有GitHub托管成功run。原生Linux宿主完整入口未运行，Windows驱动Docker Linux容器的结果不替代该证明。Linux共享同一Python业务脚本，并通过仓库`.gitattributes`保持shell脚本LF，但不把脚本提供等同实际宿主运行。

## 环境、产物与隔离

Windows11，PowerShell7，Python3.14.0，Corretto JDK17.0.17，Node24.19.0，Docker29.8.1、Compose5.5.1。最终应用容器Temurin JRE17.0.14+7，MySQL8.4.4、Redis7.4.2、RabbitMQ3.13.7；实际镜像引用/摘要和runtime输出记录在JSON环境字段，不展开容器环境或秘密。

安全报告：`target/ci/a3b7acbf7e1e/reports/`实际收集**81个文件**，只绑定子进程明确宣布的本次随机report目录，不包含旧轮次或并行目标。实查无.env/ports.yml/raw inspect/expanded config/jar/image/executable。原始安全回归在`target/regression/10be088bdf9a`，最终Compose在`target/compose-smoke/2f8ecc2ff4da`，静态诊断与清理证据在`target/ticket10-static`；归档前由父复制到集成工作树target保留。

两个隔离project的容器、命名卷、网络经标签实查均为0，临时秘密文件与本轮随机app镜像tag也已删除。不删除个人demo镜像、数据卷或全局buildcache。流程缺设施/普通失败返回非零并执行子脚本finally；强杀、runner取消或Dockerdaemon不可用仍可能阻止finally，按docs只清理本轮project，不能用全局prune。
