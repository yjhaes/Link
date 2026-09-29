# Short Link

一个用于 Java 后端实习项目的短链接服务。当前实现覆盖匿名创建永久与限时短链接、MySQL 持久化、302 跳转，以及到期和禁用状态判断。

## 技术栈

- Java 17
- Spring Boot 3.5.16
- MyBatis-Plus 3.5.17
- MySQL
- Redis，用于缓存已访问的永久短链接；Redis 不可用时跳转会回退到 MySQL
- Maven 3.9.16（通过 Maven Wrapper 固定）

## 初始化数据库

在本地 MySQL 中创建数据库：

```sql
CREATE DATABASE short_link CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE DATABASE short_link_test CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
```

应用启动时会执行版本控制中的 `schema.sql`。表使用短码作主键，映射记录中的到期时间允许为空。

通过环境变量提供连接信息；默认开发地址为 `localhost:3306/short_link`，用户名默认为 `root`，密码默认为空。需要密码时设置 `DB_PASSWORD`，不要把真实凭据提交到仓库。

跳转缓存默认连接 `localhost:6379`，可通过 `REDIS_HOST` 和 `REDIS_PORT` 配置 Redis 地址。缓存 TTL 默认 5 分钟，可通过 `SHORT_LINK_REDIRECT_CACHE_TTL` 调整。

## 网页

启动应用后访问 `http://localhost:8080/`，可在页面中输入原始网址，选择永久有效或按分钟设置期限，生成后查看短码和到期时间，并复制或打开短链接。页面由 Spring Boot 直接提供，无需额外的前端构建步骤。若应用对外使用其他地址，请设置 `SHORT_LINK_BASE_URL`，使生成的短链接指向可访问的服务地址。

## 运行

PowerShell：

```powershell
$env:DB_URL = 'jdbc:mysql://localhost:3306/short_link?serverTimezone=UTC'
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = '<本地数据库密码>'
.\mvnw.cmd spring-boot:run
```

## 接口

创建永久短链接，省略 `validMinutes` 或将它设为 `null` 即可：

```http
POST /api/links
Content-Type: application/json

{"originalUrl":"https://example.com/article?id=17#summary"}
```

按分钟设置有效时长时，`validMinutes` 必须是 1 至 5,256,000 的整数：

```http
POST /api/links
Content-Type: application/json

{"originalUrl":"https://example.com/article?id=17#summary","validMinutes":60}
```

成功返回 `201 Created`，JSON 中包含 `shortCode`、`shortUrl` 和 `expiresAt`；永久链接的 `expiresAt` 为 `null`。响应 `Location` 指向新短链接。

访问未过期且启用的短码 `GET /s/{code}` 后，系统返回 `302` 和原始 URL 的 `Location`；到期时或之后访问返回 `410 LINK_EXPIRED`；未过期但已禁用时返回 `403 LINK_DISABLED`。过期判断优先于禁用状态。格式错误或不存在的短码返回 `404 LINK_NOT_FOUND`。失败和跳转响应均带 `Cache-Control: no-store`。

维护者可直接在 MySQL 中禁用或重新启用映射，不提供对应 API：

```sql
UPDATE short_link SET enabled = FALSE WHERE short_code = 'abc12345';
UPDATE short_link SET enabled = TRUE WHERE short_code = 'abc12345';
```

禁用不会删除映射或释放短码；重新启用已过期的映射仍返回 `410 LINK_EXPIRED`。

## 测试

`ShortLinkApiTest` 连接真实 MySQL。确保测试数据库可用，并按需设置测试连接变量：

```powershell
$env:MYSQL_TEST_URL = 'jdbc:mysql://localhost:3306/short_link_test?serverTimezone=UTC'
$env:MYSQL_TEST_USERNAME = 'root'
$env:MYSQL_TEST_PASSWORD = '<本地测试数据库密码>'
.\mvnw.cmd test
```

`RedisRedirectIntegrationTest` 使用 Docker/Testcontainers 启动 MySQL 8.4 与 Redis 7.2，验证缓存值、TTL、重复跳转和大小写隔离。完整测试集需要 Docker 可用。其他集成测试通过 HTTP 接口及真实 MySQL 验证永久与限时创建、期限持久化、有效期边界、302 跳转、禁用与重新启用状态和错误响应。
