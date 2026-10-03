# 架构优化实施验证

实施起点：`d0b7edc1ce9cfd8d2e819fbe0d35d825f9ae5ebc`（方案的评估起点 e9dd03d 之后仅新增规格与方案）。当前用户调用 implement 授权实施；规格中的“本次不实施”描述此前发布阶段。

## 基线

2026-10-03，既有隔离容器 link-async-test-mysql / redis / rabbit，MySQL 13306、Redis 16379、RabbitMQ 15672、管理端口 15673；Testcontainers 另启独立 MySQL/Redis。

- 首次受限运行在编译阶段因依赖文件访问失败，没有执行测试。
- 重跑完整 Maven suite：259 项通过，失败 0、错误 0、跳过 0，耗时 4 分 16 秒。基线测试使用原始编译产物，源码的纯格式整理另行提交。
- 原始日志存放忽略目录 `.tools/`，不提交可能带框架或驱动原文的日志。

## 已执行切片

- 可读性：独立提交 f7c635e；生产 Java 使用四空格格式及显式 import，移除 Attempt 未使用的事件参数；没有改变状态转换。import 展开时的 EventListener 名称冲突已纠正，随后 runtime red 编译已通过生产源码阶段。
- 生命周期新增测试的 red：未实现 VisitMqRuntime 和新构造器时测试编译失败；接缝沿用规格确认的真实 Spring context-close 与消息 adapter。
- 生命周期 green：VisitMqRuntimeTest、VisitMqShutdownTest、VisitPublisherFailureTest 共 7 项通过；原包内真实设施启动/暂停/恢复/未 ACK 重投回归另 7 项通过，无失败/错误/跳过。
- 入口收敛与双池配置：首次未 clean 的重命名回归有 29 项上下文错误，原因是 target 中旧 MySqlVisitRecorder.class 与新实现同时扫描；清理构建后 76 项通过，失败/错误/跳过均 0。核心独立装配测试先因 CoreDataSourceConfiguration 未实现编译失败，迁出原 Bean 后通过。
- messaging、persistence、query、retention、collection、config、api.error、api.management 逐组搬迁各自 clean test-compile 通过；api.stats 搬迁后定向测试 95 项执行中 92 项通过、3 项数据库连接错误，原因是该次命令未设 MYSQL_TEST_URL 而使用默认 3306。完整重跑已设置隔离变量，不弱化或跳过这些测试。
- 前端 node --check 通过；本地浏览器使用仅驻留 .tools 的 HTTP 响应 fixture，验证永久/限时结果、分钟输入显示、提交 disabled/恢复、复制成功反馈、部分完成短码提示、URL 和分钟范围错误及输入清错。fixture 只证明 UI 行为，真实后端由完整 suite 验证。函数声明仅重排和分组注释，样式只增加分组注释。
- [生产类型和依赖清单](structure.md)：74 个现有类型各有唯一实际目标，新增两类后 76 个；无包循环、无生产 wildcard import、原有辅助类型没有新增 public。消息/查询/清理无相互实现引用，数据与缓存不依赖 HTTP。

## 可复现命令

在 pwsh 中使用隔离 MYSQL_TEST_URL（13306）、REDIS_PORT=16379、RABBIT_TEST_PORT=15672、RABBIT_MANAGEMENT_URL（15673），凭据沿用既有测试配置，不输出。

```powershell
mvn '-Dmaven.repo.local=.tools/maven-repository' clean test
node --check src/main/resources/static/app.js
git diff --check
```

重命名及搬包验证使用 clean，防止旧 class 留在扫描路径。原始日志只保留忽略目录 .tools；失败执行与最终验收不混计为独立测试。

## 验收边界

静态检查不能证明设施健康、性能或消息完整性。纯 JVM、真实 Spring 和真实设施结果分别记录；只有全部条件满足才将任务 resolved。实现模型沿用当前模型，最终双轴审查使用独立 GPT-6.1 Sol / high 子代理。
