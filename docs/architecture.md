# 短链接服务代码组织

项目使用单模块 Java 17 / Spring Boot / Maven 结构。当前只有短链接这一项业务功能，因此业务包直接位于应用根包 `com.example.shortlink` 下；`LinkApplication` 留在根包，以便 Spring Boot 扫描其子包。

## 目录

```text
src/main/java/com/example/shortlink/
  LinkApplication.java
  api/                 HTTP 控制器、请求与响应、参数反序列化、错误响应
  service/             创建和跳转流程、短码编码、所需接口与结果类型
    error/             业务失败类型，由 api 映射为 HTTP 响应
  persistence/         MyBatis-Plus 映射与 MySQL 发号实现
  cache/               Redis 跳转缓存实现
src/main/resources/
  application.yml      数据库、Redis 和服务地址配置
  schema.sql           MySQL 表结构
  static/              由 Spring Boot 提供的单页界面
src/test/java/com/example/shortlink/
  ShortLinkApiTest.java
  RedisRedirectIntegrationTest.java
  service/             创建、跳转及短码编码测试
  cache/               Redis 缓存行为测试
src/test/resources/
  application-test.yml
```

## 模块与依赖

`ShortLinkService` 是创建和跳转的主模块；调用方只需了解 `create`、`findOriginalUrl` 的输入、结果和错误类型。它负责 URL 校验、有效期与禁用判定、短码主键冲突处理和缓存回退。`api` 将 HTTP 请求交给该模块，并把结果或错误转换为响应；请求 DTO 不作为数据库记录使用。

`ShortLinkService` 通过构造函数接收 `ShortCodeIdIssuer`、`RedirectCache` 和 `Clock`。这两个接口放在使用它们的 `service` 包：`persistence/MySqlShortCodeIdIssuer` 与 `cache/RedisRedirectCache` 分别提供生产实现，测试可在相同接缝上替换它们。`ShortLinkMapper` 和 `ShortLinkEntity` 仍由 MyBatis-Plus 管理；当前只有一张映射表，不增加透传的仓储接口。

`service/error` 中的异常表达创建或跳转失败的原因。`api/ApiExceptionHandler` 把普通错误映射成 `ApiError`；已提交创建的缓存协调未确认则使用带短码的 `CreateCacheCoordinationError`，业务流程无需知道 HTTP 状态码。短码编码规则由 `PermutedShortCodeEncoder` 封装，创建流程只调用 `encode`。

创建时先由 MySQL 分配 ID，再编码并插入映射；发号与映射分别提交。Spring 管理的创建入口挂起调用者事务，让 MyBatis 的非事务插入在返回时已提交。随后轮换 Redis 版本并清除旧结果，确认成功才返回创建完成；协调异常保留已提交短码并报告部分完成。受信任维护代码可调用 `recoverCacheCoordination` 按原短码仅重试缓存协调，不重新写库。

跳转时先读取带版本的 Redis 条目：缓存 miss 或有限期占位都会继续查询 MySQL，可跳转或不存在结果命中时直接返回相应业务结果；MySQL 成功回源后的正值及不存在结果只按读取时的版本条件回填。Redis 不可用时回源 MySQL，但没有拿到版本就跳过回填。不存在结果有独立 TTL 上限和向下抖动，数据库查询错误不写成不存在。具体规则和一致性范围见 [ADR-0002](adr/0002-permuted-auto-id-base62.md)、[ADR-0003](adr/0003-redis-cache-aside-for-redirects.md) 与 [ADR-0004](adr/0004-negative-cache-for-redirects.md)。

测试目录与生产包对应。HTTP 和 MySQL/Redis 集成测试位于测试根包，覆盖接口可见行为；`service` 与 `cache` 测试验证可控制的时间边界、失败路径和缓存写入条件。运行方式见 [README](../README.md)。
