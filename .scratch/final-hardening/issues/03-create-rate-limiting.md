Status: resolved
Type: task
Blocked by: None

## Parent

[阶段 8 实现规格](../spec.md)

## What to build

匿名创建者正常创建仍得到201，过快请求得到429和等待提示，Redis限流无法确认时在业务开始前收到独立503；页面不会把它误认为已保存。

## Blocked by

None（无前置依赖；执行需用户另行授权）。

## 故事覆盖

5～9、25～28、30，以及公共限流判定能力。

## Acceptance criteria

- [x] 交付一个内聚限流判定模块及真实Redis/Lua实现，HTTP只得到获准/拒绝/不可用和等待提示，不额外增加泛化框架。
- [x] 创建按连接对端IP，容量3、每6秒补充1，可配置并启动校验；忽略伪造Forwarded/X-Forwarded-For。
- [x] 在请求体解析、发号、插入之前准入；获准后业务/参数失败不退令牌，明确拒绝不扣成负数。
- [x] 429使用RATE_LIMIT_EXCEEDED、向上取整Retry-After和no-store；限流不可用独立503无发号/写库副作用，与已有已提交协调未确认503区分。
- [x] 页面正确解释新增429和业务前503，仍展示已有部分完成短码，不增加页面新业务。
- [x] Lua短且有界，Redis TIME、桶容量/补充/TTL、命名空间隔离、EVALSHA/NOSCRIPT恢复及超时不盲目重扣符合规格。
- [x] MVC拒绝无副作用、真实Redis最后令牌竞争、独立应用上下文共享桶、补充/TTL、脚本重载及执行后丢响应验收通过。
- [x] 使用现有真实设施测试方式即可独立完成，不将任务02视为必须先落地的生产功能依赖。

## Comments

- 2026-10-04：用户确认 12 项拆分的粒度与依赖后发布。依据已接受的阶段 8 规格；本次仅发布任务，未认领或实施。执行时遵循任务生命周期，验收全部满足后在 Answer 中记录证据再标为 resolved。

## Answer

- 2026-10-04：实现匿名创建准入和内聚 RateLimiter / RedisRateLimiter，容量3、6秒补充1，配置启动校验；Redis TIME / 有界 Lua / 独立命名空间 / 完整补满时间TTL / EVALSHA 与明确NOSCRIPT重载。获准后不退令牌，损坏状态或未来时钟不放行。
- MVC在请求体解析前判断连接对端，伪造转发头不能换桶；新增429 RATE_LIMIT_EXCEEDED + Retry-After向上取整 + no-store，以及独立业务前503 RATE_LIMIT_UNAVAILABLE。拒绝无发号、插入、缓存协调和Cookie副作用；正常永久/限时创建仍201。页面通过实际提交入口区分429、未开始503和已提交协调未确认短码。
- 独立Lettuce客户端每次准入新建/关闭连接，禁止自动重连、拒绝离线命令并约束队列，应用关闭销毁客户端。真正执行后丢弃TCP响应测试证明业务前503且只有一次扣减，不盲目重试或重放。代价是每次连接成本，未宣称性能收益或HTTP总截止；见 docs/create-rate-limiting.md。
- 页面 node src/test/js/create-rate-limit-page.test.mjs 红绿通过（Node24.19.0）；未启动跳转/管理限流、回源保护或后续任务。既有缓存HTTP故障测试显式允许判定边界，避免缓存协调验收被新准入拦截；完整合并回归交由集成入口记录。

### 审查修复验收（2026-10-04）

- 单一修复提交处理全部审查发现：Redis URL数据库优先于独立database配置；JSON-only应用基础配置关闭multipart解析，避免真实DispatcherServlet在准入之前解析超大multipart；不可变RateLimitProperties在构造绑定时保存已校验毫秒值，保持既有访问API并拒绝显式空配置。
- 红测真实复现：URL `/1` 配置与独立数据库0冲突导致观察客户端错误获准；超1KB限制的4KB multipart在429判定前进入解析返回500。修复后分别验证URL数据库1优先于独立0/2、同桶已消耗及默认数据库隔离；真实嵌入式HTTP对超大multipart返回429/503、Retry-After/no-store正确、无发号或写入，获准后的错误JSON仍400。
- 最终相关验收30项（HTTP4、配置2、真实Redis9、嵌入式HTTP1、既有鉴权8+2、安全默认4），0失败/0错误/0跳过。报告为 `target/review-fixes-final/`（独立目录，无历史报告），命令：`mvn -q '-Dtest=CreateRateLimitEmbeddedHttpTest,CreateRateLimitConfigurationTest,CreateRateLimitApiTest,CreateRateLimitRedisIntegrationTest,InternalManagementApiTest,InternalManagementDisabledApiTest,SafeDefaultsConfigurationTest' '-Dtest.reportsDirectory=target/review-fixes-final' test`。
- 282项完整Java回归为父级修复前版本的证据，本次不将其宣称为修复后完整回归；修复后运行以上有针对性的30项及页面契约。生产改动仅限上述三项，未扩展后续任务。
