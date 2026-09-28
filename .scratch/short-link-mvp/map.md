# 短链接 MVP 工作地图

## 已作决定

- [Issue 02：支持有效时长与过期判断](issues/02-expiring-links.md)：完成可选有效分钟数、UTC 毫秒精度到期时间和访问时过期判断；通过真实 MySQL 的 HTTP 集成测试。提交：`dfc8be4`、`0010ebc`；[PR #1](https://github.com/yjhaes/Link/pull/1)。
- [Issue 03：处理禁用与重新启用](issues/03-disabled-link-state.md)：完成数据库禁用/重新启用、`403 LINK_DISABLED`、过期优先判断与原短码恢复；通过真实 MySQL 的 HTTP 集成测试。提交：`7a14166`、`621380a`。
- [Issue 04：严格校验原始 URL 与参数错误](issues/04-url-validation.md)：补齐原始 URL 边界、原文保存与跳转、统一参数错误响应的 HTTP 集成测试；完整测试集 16 项通过，Standards Review 与 Spec Review 无最终发现。
