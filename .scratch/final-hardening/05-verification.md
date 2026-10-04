# 05 管理限流定向验收

日期：2026-10-04（Asia/Shanghai）。实现工作树 `hardening-05/Link`；共享限流模块来源于任务 04 的 `8e9fb3f3e9293d1a37a114abb3839352fde6c9b5`，本分支对应 cherry-pick 为 `74b0d7b`。

- TDD 首个红灯：授权但请求体损坏的状态 PUT，期望 429，原行为 400；增加鉴权之后的准入拦截器后通过。
- 页面红灯：429 管理操作只有通用失败提示；补齐等待秒数和业务未开始的 503 提示后通过，已提交协调未确认提示继续独立。
- 定向执行：`mvn -q -Dtest=ManagementRateLimitApiTest,ManagementRateLimitRedisIntegrationTest,InternalManagementApiTest,InternalManagementDisabledApiTest -Dtest.reportsDirectory=target/ticket05-final-focused test`：20 tests，0 failures/errors/skipped。
- `python ops/tests/run.py unit`：126 Java tests，0 failures/errors/skipped，无缺失或意外类；2 Node page tests，0 failures/errors/skipped。独立报告为 `target/regression/bab21265cebc`。
- 真实 Redis 为 Testcontainers `redis:7.4.2-alpine`，Docker Desktop 29.8.1。3 个管理集成测试覆盖跨码/GET/HEAD/两个查询共享、写/查询/公开组隔离、独立客户端共享、不带令牌的请求不扣额度、Key 不包含令牌及真实拒绝连接时的 503 与无业务副作用。
- 7 个新增 MVC 测试覆盖鉴权不调用准入、授权请求在参数/体解析前 429/503、HEAD 拒绝无体、无统计 Cookie、无业务写入/查询、获准后错误不退款，以及原有冲突/过期/已提交协调未确认和查询忙/超时契约。
- 无设施与真实 Redis 验收均已执行；完整隔离 MySQL/MQ 全量回归由父集成分支执行，本文件不声称已运行。

管理使用说明见 [管理请求限流](../../docs/management-rate-limiting.md)。完整父规格及 09～12 不在本票完成范围。
