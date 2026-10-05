Status: resolved

# 本地启动与文档简化方案

## 已确认目标

用于 Java 后端实习求职，保留现有技术栈、业务行为和架构保证。继续使用本机 MySQL、Redis、RabbitMQ，首次手工准备环境，日常使用 IntelliJ IDEA 点击运行。用户希望将本地连接信息和秘密直接写入 YAML，不使用环境变量作为日常配置入口。

## 已确认的完整方案

### 本地 YAML 与 IDEA

1. 提供 config/application-local.example.yml，用户复制为 config/application-local.yml，在文件中填写项目数据库、Redis、RabbitMQ 连接信息，以及管理令牌、访客 HMAC、采集开关。
2. 真实本机文件加入 .gitignore；模板只含占位符与必要注释。真实配置位于源码资源目录之外，默认 Maven 构建不将其打入应用 JAR。
3. IDEA 导入 Maven 项目、选择 JDK 17，运行 LinkApplication.main。工作目录设为项目根目录，Program arguments 一次填写 --spring.profiles.active=local；适用时也可在 Spring Boot 运行配置的 Active profiles 中填写 local。
4. 使用 Spring Boot 已有配置加载机制，从项目根的 config/ 加载 application-local.yml。无需 dotenv、配置加载代码、额外插件或新的启动脚本。
5. src/main/resources/application.yml 继续保留现有安全默认值和其他入口的兼容配置；本地 local 文件仅在显式启用 local profile 时生效，避免普通测试自动读入个人配置。

### 首次手工准备

- MySQL：给出创建项目库、项目专用账号和项目库权限的手工步骤；应用负责表初始化，无需手工逐表导入。
- Redis：给出本地地址和端口检查方式；不要求预建 key。
- RabbitMQ：给出管理页面中创建项目 vhost/账号/权限的步骤，并说明如何用现有 policy 脚本设置必要队列策略；应用负责 exchange/queue/binding。
- 管理令牌与访客 HMAC 使用独立随机值，保持现有校验规则；提供一次生成并填入 YAML 的简短步骤。DB_ROOT_PASSWORD 不纳入本地应用配置，因为它不是现有 MySQL 管理员密码，应用也不读取它。
- 不安装、重置或自动启动/停止本机设施，不改变已有账号密码、数据或积压。

### 文档修改

- README.md：以“首次准备→IDEA 点运行→最短功能演示”为主线，保留技术栈和实际功能；详细故障处理指向专门说明。
- docs/入门与使用/local-secrets.md：集中首次准备、本地 YAML 模板、IDEA 配置和最常见启动问题。原 dotenv 入口保留为简短兼容说明，不作为首页默认路径。
- docs/README.md：按运行演示、面试原理、故障排查组织导航，历史设计和测试证据保留但不成为启动前置阅读。
- ops/README.md：明确 policy 设置是首次准备，积压观察和死信排查按需使用；现有脚本行为保留。
- docs/历史与维护/document-maintenance.md：记录本轮文档及本地配置入口变更。

## 范围

不变更业务 Java 代码、src/main/resources/application.yml 的安全默认值、数据库结构、缓存一致性、MQ 行为、资源隔离或限流。此次选择容易逆转，无需新增 ADR 或领域术语。

## 验证

- 检查模板配置键与当前 application.yml 和配置绑定一致；普通测试不启用 local profile。
- 用 Git ignore 检查确认真实本机 YAML 不会提交，模板可提交。
- 检查 Markdown 本地链接与配置/命令的事实一致性。
- 必要时运行现有 SafeDefaultsConfigurationTest，验证默认管理和采集仍关闭；不为文档复制新增镜像测试。
- 不将静态检查称为真实 IDEA 启动验证；缺少对应设施或个人配置时，明确记录未执行完整启动演示。

## 依据

- [讨论地图](map.md)
- [Spring Boot 3.5 外部配置](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html)：外部 config/ 默认目录与 profile 专用配置。
- [IntelliJ IDEA Spring Boot 运行配置](https://www.jetbrains.com/help/idea/run-debug-configuration-spring-boot.html)。

## 共识状态

用户于 2026-10-05 确认完整方案并授权实施。配置模板、Git 忽略规则及文档调整已完成，验证结果见 [verification.md](verification.md)。未运行真实 IDEA 与本机三设施的完整演示。
