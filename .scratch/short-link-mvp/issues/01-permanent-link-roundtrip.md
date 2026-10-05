Status: resolved
Type: task
Blocked by: None (can start immediately)

## What to build

匿名创建者提交一个合法的原始 URL 后，获得可直接访问的完整短链接；访问者打开该短链接时获得原始 URL 的 302 跳转。这是从零建立项目骨架、数据库映射和两个公开接口的第一条完整路径。

## Acceptance criteria

- [ ] 建立单模块 Java 17、Spring Boot、MyBatis-Plus、MySQL、Maven 项目；按短链接业务聚合 API、业务规则和持久化职责，并提供可复现的初始表定义、非敏感配置入口及基本 README。
- [ ] 映射表以 8 位短码为区分大小写的主键，保存原始 URL、UTC 创建时刻、可空到期时刻和启用状态；初始映射永久有效且启用。
- [ ] `POST /api/links` 在未填写有效时长时返回 `201`，包含 `shortCode`、完整 `shortUrl`、空 `expiresAt`，响应 `Location` 指向短链接；同一原始 URL 可分别创建映射。
- [ ] 短码由 `SecureRandom` 生成，固定为 8 位小写字母或数字；`GET /s/{code}` 对有效映射返回 `302` 和原始 URL 的 `Location`，不主动访问目标网站。
- [ ] 格式错误或不存在的短码返回 `404 LINK_NOT_FOUND`；跳转与失败响应包含 `Cache-Control: no-store`，错误正文为含 `code`、`message` 的 JSON。
- [ ] 至少有贯通创建、MySQL 持久化和跳转的 HTTP 测试；项目可编译，受影响测试通过。

## Comments

- 开始执行：采用 Maven Wrapper 固定 Maven 版本；先覆盖已确认的 HTTP 创建/跳转 seam 与真实 MySQL 持久化路径。

## Answer

- 从零建立 Java 17、Spring Boot、MyBatis-Plus、MySQL、Maven 项目骨架，加入 Maven Wrapper、非敏感配置入口、可复现建表脚本和 README。
- 实现永久短链接创建、SecureRandom 短码、MySQL 主键映射、302 跳转、短码错误 404、普通未注册路由 404 和统一 JSON 错误处理。
- 使用临时隔离的 MySQL 实例执行 HTTP/MySQL 测试；`mvnw verify` 通过，4 项测试成功。
- Standards 与 Spec 两轴代码评审通过。
- 实现提交：`7febb43`、`c1baa5e`、`9a006da`。
