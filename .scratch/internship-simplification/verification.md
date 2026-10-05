# 本机启动与文档简化验证

验证日期：2026-10-05。本记录对应本轮未提交工作树中的模板和文档改动，不冒用既有设施验收结论。

## 已执行

- 模板使用真实 SnakeYAML 解析，16 个叶子配置键均存在于现有基础 application.yml。
- 在 target/local-startup-probe/ 隔离目录中使用模板生成仅含测试占位值的 local YAML，通过真实 ConfigDataApplicationContextInitializer 分别验证默认配置和 local profile：默认管理令牌为空且采集关闭；local profile 读取本地数据库密码、管理令牌和采集开关。未刷新业务容器、未连接外部设施。
- 四组现有回归：SafeDefaultsConfigurationTest、StatsConfigurationTest、RedirectResourceConfigurationTest、RedirectCachePropertiesTest。共 13 项，失败 0、错误 0、跳过 0，BUILD SUCCESS。报告位于被忽略的 target/surefire-reports/。
- Git 忽略检查：config/application-local.yml 被忽略，config/application-local.example.yml 不被忽略。
- 对受版本控制 Markdown 和本轮新增讨论文件检查本地链接及锚点；最终结果见下方。
- 只读交叉核查账号权限、初始化职责、IDEA 参数、MQ 策略和关键业务边界，未发现需修正的实质问题。

回归命令（使用已有依赖缓存；执行进程不继承个人连接配置）：

```powershell
mvn -o '-Dmaven.repo.local=C:\Users\86198\.m2\repository' '-Dtest=SafeDefaultsConfigurationTest,StatsConfigurationTest,RedirectResourceConfigurationTest,RedirectCachePropertiesTest' test
git diff --check
```

最初 Maven 使用错误的默认缓存位置，随后沙箱内编译关闭依赖 JAR 时失败；指定已有缓存并获自动审批后，在沙箱外重新执行成功。没有修改系统 Maven 配置或安装依赖。

## 未执行

- 没有读取或生成真实个人密码文件，没有初始化本机数据库账号、MQ 账号/vhost/policy 或更改 Windows 服务。
- 没有在真实 IDEA 中操作运行配置，也没有执行本机三设施的完整创建→跳转→统计演示。
- 没有重新执行全量设施测试或性能/故障注入；本轮只变更模板、Git 忽略和文档。

## 首次简化的静态检查

最终检查覆盖 129 个 Markdown 文件、365 个本地链接和 5 个锚点，缺失链接/锚点为 0。Git diff --check 通过，差异范围仅为已授权的模板、忽略规则、文档和本轮讨论记录。

## 缓存面试材料跟进

2026-10-05 按用户反馈补充缓存优化提纲。逐项核对 RedisRedirectCache、RedirectService、回源配置和 HTTP 跳转限流；核查 TTL 端点、同码加载合并、负缓存复用、旧回填拒绝及多实例合并边界的测试方法确实存在。全仓库 Markdown（含新增文件）本地链接/锚点及 Git diff --check 通过。仅文档补充，未重新运行这些业务测试或真实设施验收。

## 中文目录分类跟进

2026-10-05 将根目录 22 篇文档分入六个中文目录，逐个校验移动前后 SHA256 相同。已有 adr/agents/images 路径和文件位置保留；只修复 ADR 中必要的入站链接。模板注释、首页、历史任务和文档内部引用同步迁移，外部历史版本 URL 保持不变。

静态检查覆盖全部受版本控制且仍存在的 Markdown 和全部新文档，并核查迁移清单中的原路径已消失、目标路径存在。链接/锚点及 Git diff --check 通过。未修改业务源码、pom、AGENTS、agents、images 或设施状态，未重新执行业务测试。

## 面试材料丰富跟进

2026-10-05 完成 8 份面试材料、63 条连续编号问答和 12 题模拟面试。逐项核对当前编码、创建、行锁状态维护、统计查询、MQ 消费/发布、匿名身份及令牌桶源码，官方资料用于框架原理说明，不作为本项目性能证据。

面试专题中的 41 处源码/测试引用均存在；问答编号连续、标题无重复、面试文档无行尾空白，全仓库 Markdown 本地链接/锚点及 Git diff --check 通过。本轮没有改动业务源码、pom 或运行环境，未重新执行 Java 回归、真实设施或性能验证。入口见 [面试导航](../../docs/面试准备/README.md)。
