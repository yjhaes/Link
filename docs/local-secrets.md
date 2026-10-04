# 本地秘密与安全默认值

基础 `application.yml` 的管理令牌与访客 HMAC 均为空，访问采集默认关闭。管理接口未配置时返回 404；显式配置后缺失、错误或重复管理头仍返回 401，并在参数解析与业务之前拒绝。公开创建与跳转不要求管理令牌。

消费者默认开启，关闭采集只停止新访问事件与 Cookie，不停止历史消息消费。启用采集须配置至少 32 字节的 Base64 HMAC 与 1..65535 的密钥版本。HMAC 不得与管理令牌相同；管理令牌非空时至少 32 个 UTF-8 字节。

## 一次初始化

Windows PowerShell 7：

```powershell
pwsh -NoProfile -File ./ops/init-local-secrets.ps1
```

Linux（需要 OpenSSL）：

```sh
sh ./ops/init-local-secrets.sh
```

默认生成仓库根目录 `.env.local`，仅报告成功或保留现有文件，不输出任何秘密。管理令牌、访客 HMAC、项目数据库密码、数据库引导 root 密码及 RabbitMQ 项目密码各自使用独立 32 字节随机数。版本固定初始为 1。该文件显式开启演示采集及消费者；数据库和 MQ 用户均为 `short_link`，MQ vhost 为 `short_link`。

`.env.local` 被 Git 忽略，`.env.example` 只有占位符。不要复制真实文件到文档、工单或日志，也不要将它作为构建参数。Windows 文件采用所在目录权限，请将工作目录放在仅当前用户可访问的位置；Linux 初始化使用 `umask 077`。此任务只生成配置，不实际创建数据库、broker 用户或 vhost；已有 MySQL/RabbitMQ 必须按这些值独立供给。

普通再次初始化原样保留现有文件，不补填、不改写、不自动轮换；若原文件不完整，应先人工检查。初始化失败产生的文件须先确认内容是否完整，不能把保留提示当作配置校验。程序重启也不会生成新秘密。

## 本地应用使用

Spring Boot 不自动读取 dotenv 文件。已供给数据库与 MQ 后，在当前 PowerShell 进程导入配置：

```powershell
Get-Content .env.local | ForEach-Object {
    if ($_ -match '^([A-Z_]+)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
$env:DB_URL = 'jdbc:mysql://localhost:3306/short_link?serverTimezone=UTC'
.\mvnw.cmd spring-boot:run
```

不要在共享终端使用打印环境变量的命令。基础配置 DB/MQ 无默认密码，不再依赖 root/guest；必须供给项目账号。

## 配置验收

```powershell
pwsh -NoProfile -File ./ops/test-local-secrets.ps1
mvn '-Dtest=SafeDefaultsConfigurationTest,StatsConfigurationTest,InternalManagementConfigurationTest,InternalManagementApiTest,InternalManagementDisabledApiTest,VisitCollectionFailureTest,VisitMessageCodecTest' test
```

这些验收不需要外部设施，覆盖真实基础配置装配、非法与显式开启配置、管理 MVC 拒绝无业务访问、公开业务无令牌要求及初始化稳定性。Linux 初始化还需在有 OpenSSL 的 Linux 环境运行同等生成/重复初始化核验。
