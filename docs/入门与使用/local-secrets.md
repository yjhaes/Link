# 本机配置与 IDEA 启动

日常使用本机 MySQL、Redis、RabbitMQ，配置直接写 YAML，在 IDEA 中点击运行。首次按下面的步骤准备；以后只需启动三个服务，再运行 Java 应用。

## 1. 准备项目配置

在 IDEA 中以 Maven 项目打开仓库，Project SDK 选择 JDK 17，等待 Maven 依赖导入完成。本机需要已安装的 MySQL、Redis、RabbitMQ；RabbitMQ 的管理页面用于首次配置。默认端口如下，若本机不同，填写实际地址。

| 服务 | 默认地址 | 用途 |
| --- | --- | --- |
| MySQL | localhost:3306 | 映射与访问日志 |
| Redis | localhost:6379 | 缓存与限流 |
| RabbitMQ | localhost:5672 | 异步访问统计 |
| RabbitMQ 管理页面 | http://localhost:15672 | 首次配置账号、vhost 和策略 |

将 [config/application-local.example.yml](../../config/application-local.example.yml) 复制为同目录下的 `application-local.yml`。所有连接信息和密码直接填入这个文件，不需要设置环境变量。`<...>` 是待替换的占位符，不能原样启动。

真实文件已被 Git 忽略，模板可以提交。个人配置位于项目根目录的 `config/`，不放进 `src/main/resources`，因此不会被默认 Maven 资源打包收进 JAR。

在 PowerShell 7 中执行以下命令**两次**，将两个独立结果分别填入 `internal-token` 和 `visitor-key`，保留 YAML 中的引号：

```powershell
pwsh -NoProfile -Command "[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))"
```

前者是管理页面使用的令牌，后者用于匿名访客摘要，两者不能相同。填好后保持它们稳定，不需要每次启动重新生成；不要提交或分享真实文件。模板已开启访问采集和消费者，以便演示 PV/UV。

## 2. 首次准备 MySQL

用本机已有的管理员账号登录 MySQL（IDEA 数据库工具、Workbench 或 `mysql` 客户端均可）。在 SQL 编辑器中替换下列密码占位符，再执行：

```sql
CREATE DATABASE IF NOT EXISTS short_link
  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE USER IF NOT EXISTS 'short_link'@'localhost'
  IDENTIFIED BY '<your-project-mysql-password>';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX
  ON short_link.* TO 'short_link'@'localhost';
```

把项目账号密码填入 YAML 的 `spring.datasource.password`。这里只赋予项目库权限；应用启动会自动执行 [schema.sql](../../src/main/resources/schema.sql)，不用手工创建各张表。`CREATE`、`ALTER` 权限用于现有表初始化。

如果 `short_link` 账号已经存在，`IF NOT EXISTS` 不会重置它的密码；使用已有密码，或使用另一项目专用账号并同步修改 YAML。管理员密码用于准备数据库，不写入应用配置。[MySQL 账号与赋权说明](https://dev.mysql.com/doc/refman/8.4/en/creating-accounts.html)。

## 3. 首次准备 Redis 与 RabbitMQ

Redis 启动后，填写实际地址和端口即可，不用预先创建 key。如果本机 Redis 有认证，在 YAML 的 `spring.data.redis` 下补充 `password`，使用 ACL 用户时再补充 `username`。

用本机已有的 RabbitMQ 管理员登录 <http://localhost:15672/>：

1. 在 **Admin → Virtual Hosts** 中创建 `short_link`。
2. 在 **Admin → Users** 中创建项目用户 `short_link`，设置项目密码；该用户不需要管理员标签。已有用户使用原密码。
3. 打开项目用户，为 `short_link` vhost 设置权限：Configure、Write、Read 均填 `.*`。权限限定在这个项目 vhost。
4. 在 YAML 的 `short-link.stats.rabbit` 中填写相同的用户名、密码和 vhost。

如果管理页面打不开，先确认本机 RabbitMQ 已启动、管理插件 `rabbitmq_management` 已启用；Windows 可在 RabbitMQ Command Prompt 中执行 `rabbitmq-plugins enable rabbitmq_management`。插件、页面和权限说明见 [RabbitMQ 官方文档](https://www.rabbitmq.com/docs/management)。

最后，在项目根目录的 PowerShell 7 中设置一次队列策略，使用**可设置策略的管理账号**，不是刚创建的普通应用账号：

```powershell
pwsh -NoProfile
$credential = Get-Credential
./ops/set-visit-consumer-policies.ps1 -ManagementUrl 'http://localhost:15672' -VirtualHost 'short_link' -Credential $credential
```

该脚本安装现有的容量、TTL 和死信策略，不删除队列或消息。通常只需设置一次，创建新的 vhost 时再设置；本机地址不同时替换参数。应用启动后自动声明 exchange、queue 和 binding，不用手工创建。更详细的策略和排查说明见 [ops/README.md](../../ops/README.md)。

## 4. IDEA 一次配置，以后点击运行

1. 打开 `src/main/java/com/example/shortlink/LinkApplication.java`，使用 `main` 方法旁的运行按钮创建运行配置。
2. 在 **Run → Edit Configurations** 中编辑该配置：JDK 选择 17，**Working directory** 设为项目根目录（含 `pom.xml` 的目录，可填写 `$PROJECT_DIR$`）。
3. 在 **Program arguments** 中填入 `--spring.profiles.active=local`。若该字段未显示，从 **Modify options** 中启用。支持 Spring Boot 配置的 IDEA 也可以在 **Active profiles** 填 `local`，二选一即可。
4. 保持该运行配置的 **Environment variables** 为空，不设置秘密或连接信息。保存并点击运行。

Spring Boot 自动读取工作目录下的 `config/application-local.yml`，并覆盖基础文件中的对应配置。`local` profile 只在这个应用运行配置中启用；运行测试时不要把它添加到测试配置或全局 JVM 参数。基础配置仍保持管理令牌为空、采集关闭，普通测试不加载个人配置。[Spring Boot 配置加载说明](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html) · [IDEA 运行配置说明](https://www.jetbrains.com/help/idea/run-debug-configuration-spring-boot.html)。

启动后打开 <http://localhost:8080/>，按 [README 最短演示](../../README.md#最短演示)验证创建、跳转、统计和启禁用。日常停止 Java 应用使用 IDEA 的 Stop；三个本机服务可以继续运行。

## 常见启动问题

| 现象 | 先检查什么 |
| --- | --- |
| 数据库连接失败、Access denied 或 Unknown database | MySQL 是否启动；项目库是否创建；YAML 用户、密码及账号允许的连接来源是否匹配 |
| 管理接口 404，或提示未配置访客密钥 | `local` profile、工作目录和本地文件名是否正确；占位符是否已替换 |
| 提示令牌太短、HMAC 非法或与令牌相同 | 用上面的命令分别生成两个值；每个生成值保持完整 |
| 创建返回 `RATE_LIMIT_UNAVAILABLE` | Redis 地址、端口、认证及服务状态 |
| 跳转正常但统计一直不增加 | YAML 采集是否开启；MQ 账号/vhost/权限/策略是否准备；消费者是否运行 |
| 8080 或 8081 被占用 | 停止旧应用实例；若改端口，同步设置 `server.port`、`management.server.port` 与 `short-link.base-url` |

需要更多信息时查看 <http://localhost:8081/actuator/health/dependencies>；它用于定位依赖问题，不能代替真实统计验证。Redis 清理恢复、暂停消费和死信重放都是特定故障操作，不属于日常启动步骤，见 [故障排查导航](../README.md#故障排查按需阅读)。

## 已有 dotenv 方式（可选）

此前的 `.env.local` 与 `ops/init-local-secrets.ps1` / `.sh` 保留兼容。已经使用这条路径的用户可继续使用；新配置采用上述 YAML 方式，不需要同时维护两份秘密。

初始化器只生成配置，不创建数据库账号、MQ 用户或 vhost；再次运行保留原文件。它生成的 `DB_ROOT_PASSWORD` 是随机字符串，**不是本机已有的 MySQL 管理员密码**，应用也不读取它。Spring Boot 不自动加载 `.env.local`，旧方式仍须先在 PowerShell 7 中导入配置，再运行 Maven：

```powershell
Get-Content .env.local | ForEach-Object {
    if ($_ -match '^([A-Z_]+)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
mvn spring-boot:run
```

不要同时继承另一套连接环境变量：环境变量可覆盖 YAML 配置。旧初始化行为检查使用 `pwsh -NoProfile -File ops/test-local-secrets.ps1`，不属于日常启动操作。
