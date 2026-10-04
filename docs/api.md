# 本地业务 API

业务页面 `/`、管理页面 `/admin.html`，交互文档 `/swagger-ui/index.html`，真实 JSON 契约 `/v3/api-docs`（均应用端口，默认 `http://localhost:8080`）。Swagger UI 的 Authorize 手动输入本地 `X-Internal-Token`，没有预填值，`persistAuthorization=false`；仅当前页面内存保留，刷新/关闭后重新输入，不将秘密写入仓库、URL或浏览器存储。浏览器自身的密码管理/扩展不属于此应用控制。

Boot 保持 **3.5.16 / Java 17**；文档依赖固定 **springdoc-openapi-starter-webmvc-ui 2.8.17**。官方 [兼容矩阵](https://springdoc.org/v2/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot) 将 Boot 3.5.x 对应到 springdoc 2.8.x，补丁来源为 [2.8.17 release](https://github.com/springdoc/springdoc-openapi/releases/tag/v2.8.17)；实际双端口 HTTP 测试验证此组合。Actuator 不混入业务文档，独立管理端口只供本机 health/info/metrics，详见 [健康边界](health.md)。

## 请求与成功响应

| 方法/路径 | 输入 | 成功与响应头 |
| --- | --- | --- |
| `POST /api/links` | JSON `originalUrl`，可选 `validMinutes` | 201；`{shortCode,shortUrl,expiresAt}`，`Location` 为新短链接 |
| `GET /s/{code}` | 4～8位ASCII字母数字短码 | 302、原始URL `Location`、`Cache-Control: no-store`，无响应体；可有匿名统计Cookie |
| `HEAD /s/{code}` | 同GET短码/额度/有效性 | 同状态及Location/no-store，无体、无统计Cookie/事件 |
| `PUT /api/links/{code}/enabled` | 管理头；JSON布尔 `enabled` | 200、`{shortCode,enabled}`、no-store |
| `GET/HEAD /api/internal/links/{code}/stats` | 管理头；`from`/`to` | GET 200 PV/UV/每日趋势 JSON，HEAD无体；no-store |
| `GET/HEAD /api/internal/links/{code}/visits` | 管理头；`from`/`to`、`limit`、`cursor` | GET 200访问明细/分页 JSON，HEAD无体；no-store |

创建原始URL必须是绝对HTTP/HTTPS ASCII URI，最多4096字符，无首尾空白；有效分钟省略/null为永久，否则JSON整数1～5256000。无请求幂等键，相同URL每次成功创建可得到不同映射。POST连接断开或未知错误不能推断数据库未提交，勿盲重试。201确认MySQL提交与缓存协调，但未来维护仍可改变映射状态。

管理头必须且只能提供一个准确的 `X-Internal-Token`；基础配置无秘密时接口404 `RESOURCE_NOT_FOUND`，启用后缺失/错误/重复头401 `INTERNAL_UNAUTHORIZED`。鉴权先于限流、短码/日期/请求体解析。不要通过query/cookie传令牌。PUT过期优先，已过期410禁止任何状态修改；重复启用/禁用409，不是幂等成功，也不能替代协调恢复。

统计日期按Asia/Shanghai闭区间，包含当天的最近30统计日，默认最近7日。显式日期必须同时提供 `from` 和 `to`、各一次YYYY-MM-DD，`from≤to≤今天`且不早于窗口起点。UV对整个查询范围去重，不能相加每日UV；身份密钥版本切换可能拆分访客身份，响应包含 `identityVersions`。统计是best-effort异步可见，只覆盖已记录访问事件；HTTP GET作出跳转决定不保证已入账或已打开目标站点。HEAD及任何跳转拒绝不计PV/UV，停采仍可查询历史。明细 `limit` 默认20，整数1～100且只出现一次；将上次 `nextCursor` 原样用于同短码/日期范围，不能修改或复用于其他范围。无剩余页时nextCursor为null；明细不含原始完整IP/Referer/访客摘要，UA仅已有有界最小化值。

## 错误与重试

错误通常是JSON `{code,message}`；只有已提交协调未确认额外含 `shortCode`。所有错误no-store，HEAD**包括错误**无响应体，客户端不能靠HEAD读取错误码JSON。Swagger UI/browser可能自动跟随302，核对原始响应请使用不跟随跳转的HTTP客户端。

| HTTP / code | 含义与处理 |
| --- | --- |
| 400 `INVALID_REQUEST` | 请求体、URL、有效时长、日期/分页参数错误；修正输入 |
| 401 `INTERNAL_UNAUTHORIZED` | 管理头无效；重新核对令牌 |
| 404 `RESOURCE_NOT_FOUND` / `LINK_NOT_FOUND` | 管理未开启，或短码非法/不存在；非法跳转短码不访问Redis/MySQL |
| 403 `LINK_DISABLED` / 410 `LINK_EXPIRED` | 禁用/过期；跳转判定过期优先 |
| 409 `LINK_ALREADY_ENABLED` / `LINK_ALREADY_DISABLED` | 重复状态目标被拒绝；不能用于协调恢复 |
| 429 `RATE_LIMIT_EXCEEDED` | 额度不足；`Retry-After`向上取整秒，无Cookie/访问事件。等待不保证下一次有额度 |
| 503 `RATE_LIMIT_UNAVAILABLE` | 创建/已鉴权管理限流未能确认，业务尚未开始，不发号/写库/查询、不含已保存短码 |
| 503 `REDIRECT_LOAD_BUSY` | 每实例实际回源容量繁忙，立即拒绝，无新增等待队列、无统计副作用 |
| 503 `STATS_BUSY` / `STATS_QUERY_TIMEOUT` | 统计查询容量繁忙/超时，不代表已保存；稍后重新查询 |
| 503 `CREATE_CACHE_COORDINATION_UNCONFIRMED` | 创建已提交MySQL，缓存协调未确认；保留shortCode，联系维护者仅恢复协调，禁止用POST重试代替 |
| 503 `LINK_STATE_CACHE_COORDINATION_UNCONFIRMED` | 状态已提交MySQL，协调未确认；保留shortCode，不重复PUT代替恢复 |
| 500 `SHORT_CODE_GENERATION_FAILED` / `INTERNAL_ERROR` | 无法完成或确认正常业务；不从通用错误推断提交状态，也不盲重试创建 |

默认令牌桶：每连接对端IP创建容量3/每6秒补1，跳转GET/HEAD跨短码共用容量60/每秒补10；已鉴权固定共享管理写/查询各5/每秒补1，两统计接口共用查询桶。忽略用户Forwarded/X-Forwarded-For；桶允许突发，不是严格滚动窗口上限。获准后业务失败不退还令牌。Redis限流故障跳转fail-open，但实际MySQL回源仍受每实例4并发保护；创建/管理fail-close。

受控恢复见 [Redis恢复](redis-recovery.md)。内部 `recoverCacheCoordination(shortCode)` 是受信任维护代码入口，**不是公开HTTP API**，只重试协调、不新增映射或重复改状态。PUT已提交后的同步尝试最多3次、等待50/100ms，耗尽没有后台持续补偿。Redis超时可能已执行；主实例/快照丢失确认写入时先隔离全部写入者、清理完整版本缓存命名空间、核验MySQL状态再恢复，不删一个key或重试业务代替。

## 验收入口

无设施公开边界：`./mvnw.cmd --batch-mode '-Dtest=BusinessOpenApiHttpTest' test`（Linux `./mvnw`）；页面契约：`node --test src/test/js/*.test.mjs`。
