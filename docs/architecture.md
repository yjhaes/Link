# 短链接 MVP 架构设计

本设计服务于已确认的第一阶段规格，当前仓库尚无应用代码。目标是在单模块 Java 17 / Spring Boot / MyBatis-Plus / MySQL / Maven 项目中，让创建与跳转的职责、数据约束和测试入口清楚且可复现。本阶段不引入用户系统、额外基础设施或前端。

项目根目录将包含 Maven 构建描述、README 和 `.gitignore`；构建版本与依赖版本明确固定，忽略构建产物、IDE 文件及本地敏感配置。

## 代码组织

遵循 Maven 的 `src/main/java`、`src/main/resources`、`src/test/java`、`src/test/resources` 目录约定。Spring Boot 启动类放在根包，业务类位于其子包。根包名称在实现时确定，下列类名为职责示意。

```text
src/main/java/<根包>/
  LinkApplication
  shortlink/
    api/                 创建和跳转控制器；请求与响应 DTO
    service/             创建、过期、禁用判定；短码生成；URL 校验
    persistence/         MyBatis-Plus Mapper 与映射记录
  common/
    error/               统一错误响应与异常处理
src/main/resources/
  application.yml        非敏感配置及外部配置入口
  schema.sql             第一版映射表定义
src/test/java/<根包>/
  shortlink/             HTTP、业务规则与真实 MySQL 测试
src/test/resources/
  application-test.yml   测试专用配置
```

只有一个短链接业务包。按 `api → service → persistence` 的调用方向组织：控制器负责 HTTP 契约，服务负责业务规则，Mapper 负责数据库操作。请求/响应 DTO 不直接用作数据库映射记录。没有必要为唯一一张表增加独立的领域映射层、通用仓储框架或多模块工程。

## 两条端到端路径

- 创建：`POST /api/links` 校验输入，服务读取一次 UTC 当前时刻，计算可选到期时间，由短码生成器提出候选，经 Mapper 插入 MySQL。主键冲突时仅重试短码，最多三次；成功返回 `201`、完整短链接及到期时间。
- 访问：`GET /s/{code}` 校验短码并按主键查询，依次判断不存在、过期和禁用；有效且启用时返回 `302` 和原始 URL 的 `Location`。服务端不请求目标网站。

数据库只保存已确认的五个字段；短码直接作主键，过期或禁用记录不自动删除。初始建表定义纳入版本控制，让新环境和真实 MySQL 测试可以复现同一约束。数据库连接信息通过外部配置提供，不在仓库中保存密码。

## 完成标准与测试切入点

每张实现任务都应交付可从接口观察的行为，并包含相应测试；编译与受影响测试通过后才算完成。以 HTTP 接口贯通业务和数据库为主要测试入口，使用真实 MySQL 验证主键、空到期时间、UTC 读写及短码精确比较。对时间边界和短码碰撞这类难以稳定触发的情形，使用可控制的时钟和候选值做少量业务单元测试。

README 应说明项目用途、两个接口、数据库结构的初始化方式和测试方式。真实 MySQL 测试需要可重复准备与清理测试数据，不依赖开发者机器上遗留的记录。第一阶段的目录与职责保持紧凑；如果后续实际出现第二个业务领域，再评估是否需要调整模块边界。

## 与任务拆分的关系

第 01 张任务从零建立 Maven/Spring Boot 骨架和完整的“创建永久短链接 → MySQL 持久化 → 访问 302”路径。后续任务围绕有效时长、禁用状态、严格 URL 校验和短码碰撞，分别补齐对应端到端行为及测试。不单独开一张只创建目录或只写测试的横向任务。

## 依据

- [Maven 标准目录布局](https://maven.apache.org/guides/introduction/introduction-to-the-standard-directory-layout)
- [Spring Boot 代码组织建议](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html)
