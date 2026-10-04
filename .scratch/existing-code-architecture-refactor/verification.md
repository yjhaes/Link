# 现有短链接职责重构验收

执行日期：2026-10-01（Asia/Shanghai）。审查基点：`c1a47bb10532a4831bf42df31b253c8e46048d08`。

## 实现结果

- `ShortLinkCreationService` 集中创建校验、发号编码、冲突重试、提交后协调及内部恢复；`RedirectService` 集中缓存回退、判定、版本回填、实例内合并和等待处理。HTTP 直接调用对应完整用例，旧 façade 已删除。
- `MySqlShortLinkWriter` 集中插入确认及 MySQL 1062／PRIMARY 分类，创建用例仅处理分类后的主键冲突，最多插入两次；其他失败原样传播。
- `RedirectCacheProperties` 集中已有开关、四类 TTL 和等待预算，保留配置键、默认值及六个环境变量。显式空值、非法值、非正值、亚毫秒 TTL 和转换溢出启动失败；正的纳秒等待预算有效。
- 已核对生产调用、README 与维护说明；无实际用途的 `deleteIfVersion` 已移除，坏值按原值条件删除仍封装在 Redis adapter 内。
- README 和架构说明已同步；数据库默认密码文档改为现有配置的 `123456`，没有改变连接配置。

## 测试证据

使用 Java 17 / Maven 3.9.16；工作树内 `.tools/maven-repository` 作为 Maven 缓存。

| 阶段／套件 | 测试数 | 结果 |
| --- | ---: | --- |
| 原四组行为基线 | 48 | 全部通过 |
| 拆分后四组回归 | 48 | 全部通过 |
| 配置绑定专项 | 4 | 全部通过 |
| RedirectCachePropertiesTest | 4 | 全部通过 |
| RedisRedirectCacheTest | 6 | 全部通过 |
| RedirectLoadCoalescingTest | 14 | 全部通过 |
| ShortLinkUseCasesTest | 26 | 全部通过 |
| PermutedShortCodeEncoderTest | 3 | 全部通过 |
| ShortLinkApiTest | 26 | 真实本地 MySQL，全部通过 |
| 最终完整测试集 | 115 | 0 失败、0 错误、0 跳过 |

完整测试命令：

```powershell
mvn '-Dmaven.repo.local=C:\Users\86198\.codex\worktrees\b54d\Link\.tools\maven-repository' test
```

完整运行耗时 40.871 秒，完成时间 2026-10-01 14:29:50 +08:00。本地日志位于 `.tools/baseline-tests.log`、`.tools/refactor-tests.log`、`.tools/config-tests.log`、`.tools/full-tests.log`（忽略提交）。没有未运行的必需环境或测试。

真实 Spring 代理场景在调用者事务挂起期间、缓存协调前独立读取数据库，确认映射与发号已经提交；调用者回滚不撤销创建。HTTP 协调闩锁场景确认 Redis 返回前创建响应没有完成。保留已有 HTTP、503 恢复、真实 Redis 超时、版本隔离、多实例、TTL 和并发验收。

配置专项测试先发现空值被默认绑定掩盖，再通过显式时长文本解析修复；最终验证空值不能悄悄回退到默认值。

## 结构与范围检查

生产 Java import 包图检查无循环：根应用 → cache；api → service／service.error；service → cache／persistence／shortcode／service.error；persistence → shortcode；cache、shortcode 和 service.error 无反向依赖。`git diff --check` 通过。

没有修改 HTTP 协议、短码参数、MySQL schema、Redis v2 格式、提交与恢复规则；没有引入统计、消息、限流、outbox、数据库迁移或 Maven 多模块。

## Code Review

Standards Review 与 Spec Review 使用用户指定的 GPT-6.1 Sol，reasoning effort 为 high，并行独立审查。审查范围为实施前基点至本次实现提交。

### Standards

未发现文档规范硬性违反，也未发现值得报告的新增代码异味。实际错误分类使保存 adapter 具有职责深度；现有 Mapper／实体耦合符合仓库标准，无需另建通用仓储。

### Spec

未发现缺失或部分实现、范围扩张或错误实现。新增真实代理和 HTTP 协调等待验收覆盖了提交与完成顺序。

Standards：0 项发现，无最严重问题；Spec：0 项发现，无最严重问题。
