# 同步访问统计工作地图

## 已作决定

- 2026-10-01：[任务 03](issues/03-visit-failure-and-capacity.md) 完成故障内部观测、容量恢复与真实数据库超时验收。事件结果区分确认保存、重复、繁忙丢弃、明确失败与不确定；确认后清理失败不撤销保存确认。
- Connector/J 的 DataSource Properties 必须使用字符串，真实测试证明旧整数 socketTimeout 未生效；已修正连接、socket 和会话时区属性。
- 两项代码审查使用 GPT-6.1 Sol / high，复核后 Standards/Spec 剩余发现均为 0；完整 185 项测试通过。见 [审查记录](review-03.md)。
- 阶段超时不是 HTTP 总墙钟截止时间，两池同用 MySQL 不构成硬件完全隔离；建连证据限于真实 Socket.connect 收到 500ms 参数。

## 待探索

- 查询、分页与清理继续由任务 04、05、06 承接，任务 03 不扩大交付范围。
