# 12：首页与正式展示验收

2026-10-04，应用源码来源 `635ffef140e8232c8b2fbdc93775b18a288d2cc0`；执行时仅文档/截图修改未提交，sourceDirty=true。生产源码与最终完整CI source `c4b47a3f31710fec5d99ac78ae4586c17e402716` 相同；本票不重复运行 Java 全量，不将历史结果声明为新提交验收。

- README 已精简为实际能力、技术栈/公开截图、单应用架构、初始化/启动、最短演示、API/测试入口、隐私/故障局限及停止线。
- 架构图显示 MySQL 权威、Redis 缓存/四组限流、实际SQL回源4并发、本地交接256/未确认32、发布/消费/访问日志、管理查询、DLQ、持久卷与localhost端口。architecture两张时序图说明Lua原子边界及已提交协调未确认。
- 正式证据由 docs/verification.md 汇总317 Java+2 Node+4 Python/136 Compose及带条件有限观察；docs/portfolio.md提供四条已验收简历素材，不承诺生产SLA、强一致故障切换或不漏统计。
- 从独立随机项目的新命名卷按README初始化/构建启动，实际26项HTTP/运行检查通过，另核验两条有界Rabbit policy、五个互异秘密和重复初始化保留。
- HEAD302无body、Location/no-store、两次GET同Cookie异步PV2/UV1、启禁用、实际默认创建429/Retry-After、公开Swagger八操作和两端健康均已实跑。临时override只调隔离端口与短链接base URL；未访问个人服务或外部目标地址。
- 使用独立无用户profile的headless Edge上下文重拍公开空态首页与无auth Swagger，逐张view_image检查清晰度/内容，无令牌、Cookie或访客数据。截图2763是本次隔离端口，正常README为8080。
- 旧退出演示和新项目各自的容器、卷、网络、精确临时镜像标签及秘密文件已清理，无全局prune。正式安全摘要记录项目/镜像/端口/结果和清理为零，无秘密值。

正式交付：[验证入口](../../docs/verification.md)、[README演示安全摘要](../../docs/evidence/readme-demo-2026-10-04.json)、[简历素材](../../docs/portfolio.md)、[架构](../../docs/architecture.md)。原始仅任务安全产物位于 ignored `target/ticket12-proof/`，不是公开证据唯一来源。Windows Linux容器证明不替代原生Linux宿主或GitHub托管run。
