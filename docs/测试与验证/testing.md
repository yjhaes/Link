# 测试入口

无设施 Java、MVC、真实 HTTP 和页面回归需要 JDK 17、Python 3.10+、Node.js 18+。Windows 使用 PowerShell 7；首次下载 Maven Wrapper 和依赖需要网络。

| 测试层 | Windows | Linux |
| --- | --- | --- |
| 无设施回归 | `pwsh -NoProfile -File ops/tests/run.ps1 unit` | `sh ops/tests/run.sh unit` |
| 页面契约 | `node --test src/test/js/*.test.mjs` | 同左 |

入口核对实际执行的测试类集合，缺报告、零测试、失败、错误或跳过均返回非零。无设施层不证明真实数据库、缓存或消息系统的行为。

真实设施验收须使用隔离的 MySQL、Redis 和 RabbitMQ，使用独立账号、数据库和 vhost，避免更改个人数据或清空已有队列。此页仅列无设施运行方式，不将已有完整回归脚本描述成无需外部测试基础设施。

限流测试使用专门额度，不能用高额度业务回归代替限流验收。

报告保存在被 Git 忽略的 `target/regression/<运行标识>/`，包含各组 JUnit XML/TXT 与安全摘要。不要输出环境变量或真实连接秘密。缺少设施与未执行场景应明确记录，不能称为成功或以历史结果代替本轮测试。

历史源码版本、实际数量与证据边界见 [正式验证](verification.md)，工作流及报告规则见 [CI说明](ci.md)。
