# 同步访问统计工作地图

## 已作决定

- 2026-10-02：[任务 04](issues/04-query-visit-statistics.md) 已完成受令牌保护的 PV/UV 范围汇总与连续日趋势，固定上海日期窗口，统计池容量 1，汇总/趋势/版本使用同一只读 RR 快照；支持禁用与过期历史、明确繁忙/超时/数据库错误及超时计数。
- 两项 GPT-6.1 Sol / high 独立审查共发现 3 项并全部修复，最终 Standards/Spec 均 0 项；完整 199 项真实 MySQL/Redis 与其他回归通过。见 [任务 04 审查](review-04.md)。

- 2026-10-01：[任务 03](issues/03-visit-failure-and-capacity.md) 完成故障内部观测、容量恢复与真实数据库超时验收。事件结果区分确认保存、重复、繁忙丢弃、明确失败与不确定；确认后清理失败不撤销保存确认。
- Connector/J 的 DataSource Properties 必须使用字符串，真实测试证明旧整数 socketTimeout 未生效；已修正连接、socket 和会话时区属性。
- 两项代码审查使用 GPT-6.1 Sol / high，复核后 Standards/Spec 剩余发现均为 0；完整 185 项测试通过。见 [审查记录](review-03.md)。
- 阶段超时不是 HTTP 总墙钟截止时间，两池同用 MySQL 不构成硬件完全隔离；建连证据限于真实 Socket.connect 收到 500ms 参数。

## 待探索

- 分页与清理继续由任务 05、06 承接，任务 04 不扩大交付范围。
