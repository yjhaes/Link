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

`.env.local` 被 Git 与 Docker 构建上下文忽略，`.env.example` 只有占位符。不要复制真实文件到文档、工单、日志或镜像，也不要将它作为构建参数。Windows 文件采用所在目录权限，请将工作目录放在仅当前用户可访问的位置；Linux 初始化使用 `umask 077`。此任务只生成配置，不实际创建数据库、broker 用户或 vhost；已有 MySQL/RabbitMQ 必须按这些值独立供给。完整 Compose 部署由后续任务提供。

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

## 重启、轮换与数据重置

普通重启保留原文件及 MySQL/RabbitMQ 数据。不要为重启删除配置文件或卷；重跑初始化不会修复已有数据库/broker 账号与文件不同步的问题。

轮换属于显式维护操作：先暂停采集/应用并备份当前配置，再单独生成新凭据；数据库和 broker 密码须同步实际账号。管理令牌轮换会使旧调用者立即失效。HMAC 轮换必须同时更新 `SHORT_LINK_VISITOR_KEY_VERSION`，并让所有实例一致；它会改变匿名访客摘要，使跨轮换统计窗口的同一访客可能被算成多个 UV，因此不应在普通重启时轮换。保留旧版本日志不等于能合并新旧访客身份。

数据重置是另一个操作，会删除持久化映射、访问日志或队列积压；初始化与秘密轮换均不执行数据重置。后续部署文档将单独提供明确删除卷的流程。

## 配置验收

```powershell
pwsh -NoProfile -File ./ops/test-local-secrets.ps1
mvn '-Dtest=SafeDefaultsConfigurationTest,StatsConfigurationTest,InternalManagementConfigurationTest,InternalManagementApiTest,InternalManagementDisabledApiTest,VisitCollectionFailureTest,VisitMessageCodecTest' test
```

这些验收不需要外部设施，覆盖真实基础配置装配、非法与显式开启配置、管理 MVC 拒绝无业务访问、公开业务无令牌要求及初始化稳定性。Linux 初始化还需在有 OpenSSL 的 Linux 环境运行同等生成/重复初始化核验。
