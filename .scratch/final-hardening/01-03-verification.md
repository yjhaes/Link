# 01～03 实施与集成验收

日期：2026-10-04（Asia/Shanghai）。用户本次仅授权 01、02、03；04～12 未开始。集成分支为 `codex/final-hardening-01-03`，实现基点为 `b8aae4a99aed9b43268df67ee850dc823b674dc5`，完成审查修复的代码集成提交为 `aced838b463d57395e7412b168b59a53300cfdd1`。父规格仍未整体完成。

## 交付

- 01：管理令牌/HMAC 无提交默认秘密，采集默认关闭、消费者默认开启；独立秘密初始化、重复保留、构建排除和运行说明。
- 02：Windows pwsh 与 Linux sh/Python 隔离测试入口，自动供给 MySQL/Redis/RabbitMQ、项目账号/vhost/policy，失败/错误/跳过与缺设施明确失败；真实创建→302→异步 PV/UV 查询。
- 03：匿名创建 Redis TIME/Lua 令牌桶、请求体解析前准入、429/Retry-After/no-store、业务前独立 503、页面提示，以及原子竞争、TTL、脚本恢复、断连/丢响应不重放验证。

实际使用入口见 [秘密初始化](../../docs/入门与使用/local-secrets.md)、[隔离测试](../../docs/测试与验证/testing.md)、[创建限流](../../docs/架构与原理/create-rate-limiting.md)。

## 集成回归：修复前完整版本

在代码集成提交 `02616a9f74fb021e3b1b31fa4e0af4af6251b724` 上执行：

```powershell
pwsh -NoProfile -File ops/tests/run.ps1 all
```

运行标识 `df4a472cb21a`。

| 分组 | tests | failures | errors | skipped | 退出码 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Node 页面契约 | 1 | 0 | 0 | 0 | 0 |
| 无设施 Java/MVC | 118 | 0 | 0 | 0 | 0 |
| 主真实集成 | 142 | 0 | 0 | 0 | 0 |
| 消费集成 | 13 | 0 | 0 | 0 | 0 |
| 生命周期集成 | 9 | 0 | 0 | 0 | 0 |

Java 共 282 项，全组核对缺失/意外测试类集合均为空。原始安全日志、XML 和 summary 位于 `target/regression/df4a472cb21a/`。

根工作树另执行 `pwsh -NoProfile -File ops/test-local-secrets.ps1`，独立值、无秘密输出、重复稳定验收通过。任务 01 的 POSIX 初始化在 Git Bash/OpenSSL 验证；本轮没有原生 Linux 宿主的完整设施运行，不将入口交付等同于该宿主实测。

## 审查及修复后针对性验收

Standards Review 与 Spec Review 两个子代理均明确使用 GPT-6.1 Sol / high。首次 Standards 审查没有硬性规范违反，提出配置重复解析的 P3 改进；Spec 审查发现两个 P2：Redis URL 数据库被属性默认值覆盖、默认 multipart 解析早于 MVC 准入。

单一实现代理通过提交 `826068beb49e5b5c4775d59d8c719a1b76be959c` 修复全部发现：URL 分支保留 URL 数据库，JSON 应用关闭 multipart 预解析，配置构造绑定时缓存毫秒值。新增真实 Redis URL `/1` 与属性 DB 0/2 冲突回归及真实嵌入式 HTTP oversized multipart 拒绝回归，均先红后绿。

修复后的精确验收命令：

```powershell
mvn -q '-Dtest=CreateRateLimitEmbeddedHttpTest,CreateRateLimitConfigurationTest,CreateRateLimitApiTest,CreateRateLimitRedisIntegrationTest,InternalManagementApiTest,InternalManagementDisabledApiTest,SafeDefaultsConfigurationTest' '-Dtest.reportsDirectory=target/review-fixes-final' test
node src/test/js/create-rate-limit-page.test.mjs
```

独立目录 `target/review-fixes-final/` 的 30 项 Java 测试全部通过，failures/errors/skipped 均为 0；页面契约另通过 1 项。报告已复制到集成工作树，保留后再归档实现工作树。两名原审查代理只读复核修复，Standards 硬性规范/代码异味及 Spec 剩余发现均为 0。

282 项完整回归属于修复前版本，30 项针对性验收属于修复后版本；未宣称最终提交再次运行全部回归。

01～03 已 resolved；04～12 仍 ready-for-agent，须等待用户后续授权。没有新增吞吐、生产 SLA 或全栈完成声明。
