# 访问统计查询

内部接口：`GET /api/internal/links/{code}/stats`。隐式 HEAD 使用同一管理令牌及校验规则。
未配置令牌返回 404；缺失、错误、重复令牌返回 401，先于参数解析或数据库读取。所有结果均 `Cache-Control: no-store`。

`from`、`to` 同时缺省时查询最近 7 个上海自然日，包含今天；显式日期为严格 `YYYY-MM-DD`，两端包含。
只传一端、重复、非法、逆序、未来和超出包含今天的最近 30 日返回 400，不截断范围。
日期转换为 UTC 左闭右开时间范围；例如上海 2026-10-01 为 `[2026-09-30T16:00:00Z, 2026-10-01T16:00:00Z)`。
窗口在每次请求开始校验时固定，不依赖超窗日志的物理清理。

合法但不存在或非法短码返回 404 `LINK_NOT_FOUND`。已有禁用或过期映射可以查询历史。
映射存在性、汇总、趋势及版本全部使用统计池，在一个短只读 REPEATABLE READ 事务中普通一致性读取，不锁映射。
每实例查询准入为 1，无容量立即 503 `STATS_BUSY`；明确查询超时为 503 `STATS_QUERY_TIMEOUT`；其他数据库错误为 500 `INTERNAL_ERROR`。
无重试，错误不返回伪造零值。阶段超时不保证 HTTP 总墙钟截止时间。
明确的语句、统计池获取及 socket 读取超时按超时响应处理；一般断连仍为数据库错误。`VisitQueryObservations` 提供进程内查询超时计数，不包含请求或身份标签。

响应包含 `shortCode/from/to/timeZone/pv/uv/uvBasis/collectionPolicy/collectionEnabled/identityVersions/generatedAt/daily`。
`daily` 每项为 `date/pv/uv/isOngoing`，有效范围内无日志日期补零，今天标为进行中。
范围 UV 对整个范围的 `(visitor_key_version, visitor_hash)` 去重，不能相加日 UV。短码大小写不同，或不同短码指向同一原始 URL，均独立计数。

`timeZone=Asia/Shanghai`、`uvBasis=anonymous-cookie`、`collectionPolicy=best-effort`。
数字仅表示已记录访问，零值不证明没有实际访问或采集完整。匿名 Cookie UV 不等于真实人数。
关闭采集仍读取历史，`collectionEnabled=false`；不补采停采前后或故障期间的访问。
`identityVersions` 为本范围已记录版本的有序列表；多个版本表示身份拆分边界，同一访客跨版本可以被计为多个 UV，无法自动合并。
`generatedAt` 为读取成功后生成响应的 UTC 时间，不声称精确数据库提交截止。

访问明细：`GET /api/internal/links/{code}/visits`，GET/隐式 HEAD 复用上述令牌、日期窗口、统计池、容量及错误规则；与聚合共享每实例查询准入 1。

`limit` 默认 20，范围 1 至 100，只允许单个整数参数。按 `(occurred_at,id)` 倒序读取 `limit+1`，不使用 OFFSET、不计算总页数。
响应为 `shortCode/from/to/items/nextCursor/hasMore`；每项仅有 UTC `occurredAt`、脱敏 `peerIpNetwork`、已清理且最长 512 字符的 `userAgent` 和 `refererHost`，缺失元数据为 null。
无后续记录时 `nextCursor=null`、`hasMore=false`，无记录时 items 为空。

续页传 `cursor=nextCursor`，并传首页返回的 `from/to`（即使首页使用默认日期）；页大小可以改变。
游标为版本 1 的规范无填充 Base64URL，保存短码、日期范围和末尾时间/行 ID，不含访客摘要。格式、范围或短码不匹配返回 400；游标不签名，只控制授权后的排序位置，始终需要管理令牌。
原游标行删除后仍可按位置继续。每页为独立读取，跨页没有固定快照：新写或迟到记录、清理可能使后续数据改变；新记录排在游标之前时，继续向后翻页不会看到，返回首页可重新查询。
窗口移动使原范围超出最近 30 日时返回 400，不保留过期游标的历史查询能力。在线日志清理已实现；故障或积压时物理删除可能延迟，具体调度与维护边界见[访问采集说明](visit-collection.md)。

## 实现导航

HTTP 参数、游标编码和响应位于 `api.stats`，查询与观察位于 `stats.query`，共享日期规则仍为 `stats.StatsDateRange`。管理保护归 `api.management`，统一错误表示归 `api.error`；查询不依赖 HTTP 实现或消息启停。池归属和测试导航见 [架构说明](architecture.md)。
