# Short Link

一个用于 Java 后端实习项目的短链接服务。当前实现覆盖匿名创建永久短链接、MySQL 持久化和 302 跳转。

## 技术栈

- Java 17
- Spring Boot 3.5.16
- MyBatis-Plus 3.5.17
- MySQL
- Maven 3.9.16（通过 Maven Wrapper 固定）

## 初始化数据库

在本地 MySQL 中创建数据库：

```sql
CREATE DATABASE short_link CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE DATABASE short_link_test CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
```

应用启动时会执行版本控制中的 `schema.sql`。表使用短码作主键，映射记录中的到期时间允许为空。

通过环境变量提供连接信息；默认开发地址为 `localhost:3306/short_link`，用户名默认为 `root`，密码默认为空。需要密码时设置 `DB_PASSWORD`，不要把真实凭据提交到仓库。

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

访问未过期的短码 `GET /s/{code}` 后，系统返回 `302` 和原始 URL 的 `Location`；到期时或之后访问返回 `410 LINK_EXPIRED`。格式错误或不存在的短码返回 `404 LINK_NOT_FOUND`。失败和跳转响应均带 `Cache-Control: no-store`。

## 测试

测试连接真实 MySQL。确保测试数据库可用，并按需设置测试连接变量：

```powershell
$env:MYSQL_TEST_URL = 'jdbc:mysql://localhost:3306/short_link_test?serverTimezone=UTC'
$env:MYSQL_TEST_USERNAME = 'root'
$env:MYSQL_TEST_PASSWORD = '<本地测试数据库密码>'
.\mvnw.cmd test
```

集成测试通过 HTTP 接口验证永久与限时创建、MySQL 持久化、有效期边界、302 跳转和错误响应。
