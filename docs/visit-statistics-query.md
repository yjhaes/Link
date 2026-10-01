# 访问统计查询

内部接口：`GET /api/internal/links/{code}/stats`。隐式 HEAD 使用同一管理令牌及校验规则。
请求头为 `X-Internal-Token`，部署使用 HTTPS。未配置令牌返回 404；缺失、错误、重复令牌返回 401，先于参数解析或数据库读取。所有结果均 `Cache-Control: no-store`。

`from`、`to` 同时缺省时查询最近 7 个上海自然日，包含今天；显式日期为严格 `YYYY-MM-DD`，两端包含。
只传一端、重复、非法、逆序、未来和超出包含今天的最近 30 日返回 400，不截断范围。
日期转换为 UTC 左闭右开时间范围；例如上海 2026-10-01 为 `[2026-09-30T16:00:00Z, 2026-10-01T16:00:00Z)`。
窗口在每次请求开始校验时固定，不依赖超窗日志的物理清理。

合法但不存在或非法短码返回 404 `LINK_NOT_FOUND`。已有禁用或过期映射可以查询历史。
映射存在性、汇总、趋势及版本全部使用统计池，在一个短只读 REPEATABLE READ 事务中普通一致性读取，不锁映射。
每实例查询准入为 1，无容量立即 503 `STATS_BUSY`；明确查询超时为 503 `STATS_QUERY_TIMEOUT`；其他数据库错误为 500 `INTERNAL_ERROR`。
无重试，错误不返回伪造零值。阶段超时不保证 HTTP 总墙钟截止时间。

响应包含 `shortCode/from/to/timeZone/pv/uv/uvBasis/collectionPolicy/collectionEnabled/identityVersions/generatedAt/daily`。
`daily` 每项为 `date/pv/uv/isOngoing`，有效范围内无日志日期补零，今天标为进行中。
范围 UV 对整个范围的 `(visitor_key_version, visitor_hash)` 去重，不能相加日 UV。短码大小写不同，或不同短码指向同一原始 URL，均独立计数。

`timeZone=Asia/Shanghai`、`uvBasis=anonymous-cookie`、`collectionPolicy=best-effort`。
数字仅表示已记录访问，零值不证明没有实际访问或采集完整。匿名 Cookie UV 不等于真实人数。
关闭采集仍读取历史，`collectionEnabled=false`；不补采停采前后或故障期间的访问。
`identityVersions` 为本范围已记录版本的有序列表；多个版本表示身份拆分边界，同一访客跨版本可以被计为多个 UV，无法自动合并。
`generatedAt` 为读取成功后生成响应的 UTC 时间，不声称精确数据库提交截止。

任务 04 只提供汇总及日趋势；分页明细和调度清理由后续任务实现。
