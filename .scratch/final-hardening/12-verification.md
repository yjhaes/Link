# 12：首页与正式展示验收

2026-10-04，应用源码来源 `635ffef140e8232c8b2fbdc93775b18a288d2cc0`；执行时仅文档/截图修改未提交，sourceDirty=true。生产源码与最终完整CI source `c4b47a3f31710fec5d99ac78ae4586c17e402716` 相同；本票不重复运行 Java 全量，不将历史结果声明为新提交验收。

- README 已精简为实际能力、技术栈/公开截图、单应用架构、初始化/启动、最短演示、API/测试入口、隐私/故障局限及停止线。
- HEAD302无body、Location/no-store、两次GET同Cookie异步PV2/UV1、启禁用、实际默认创建429/Retry-After、公开Swagger八操作和两端健康均已实跑。临时override只调隔离端口与短链接base URL；未访问个人服务或外部目标地址。
- 使用独立无用户profile的headless Edge上下文重拍公开空态首页与无auth Swagger，逐张view_image检查清晰度/内容，无令牌、Cookie或访客数据。截图2763是本次隔离端口，正常README为8080。
