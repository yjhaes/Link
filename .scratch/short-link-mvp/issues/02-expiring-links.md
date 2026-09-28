Status: resolved
Type: task
Blocked by: 01 — 创建并访问永久短链接

# 02: 支持有效时长与过期判断

## What to build

匿名创建者可按分钟设置短链接有效时长，访问者在到期前仍可跳转，到达期限时收到明确的过期结果；永久有效链接保持原有行为。

## Acceptance criteria

- [x] 创建请求接受可选整数 `validMinutes`；省略或显式为 `null` 均表示永久有效，填写时仅接受 1 至 5,256,000 分钟。
- [x] 创建时读取一次当前时刻，按 UTC 和统一精度保存创建及到期时间；成功响应的 `expiresAt` 是带 `Z` 的 UTC 时间或 `null`。
- [x] 到期前访问返回 `302`；当前时刻恰好到达或超过到期时刻返回 `410 LINK_EXPIRED`，并包含 `Cache-Control: no-store`；过期由访问时判断，不要求后台任务更新状态。
- [x] 0、负数、非整数和超出上限的有效时长返回 `400 INVALID_REQUEST`，错误正文保持统一 JSON 形状。
- [x] 使用可控制的时间测试到期边界，并通过 HTTP 与真实 MySQL 验证期限保存、返回及跳转结果；受影响测试通过。

## Comments

## Answer

- 增加可选 `validMinutes`，接受 1 至 5,256,000 的 JSON 整数；省略或 `null` 创建永久链接。
- 创建时单次读取可注入 UTC 时钟，按毫秒精度保存创建与到期时间；访问时到达或超过期限返回 `410 LINK_EXPIRED` 和 `Cache-Control: no-store`。
- 补充可控时钟、HTTP 与真实 MySQL 集成覆盖，并更新 README。
- `mvnw verify` 通过，9 项测试成功；提交为 `dfc8be4`、`0010ebc`。
- Pull request: [#1 支持有效时长与过期判断](https://github.com/yjhaes/Link/pull/1)。
