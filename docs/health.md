# 分组健康与本地管理端口

Actuator 使用当前 Spring Boot 3.5.16 的 starter，没有升级技术栈或新增监控服务。基础运行的管理服务绑定127.0.0.1:8081；Compose容器内管理绑定0.0.0.0:8081并且**仅向宿主127.0.0.1发布**。应用8080仍负责业务与两个无详情探针。

| 入口 | 主端口8080 | 管理端口8081 | 含义 |
| --- | --- | --- | --- |
| `/livez` | status唯一字段 | 无此路由 | 自身LivenessState，不访问DB/Redis/MQ |
| `/readyz` | status唯一字段 | 无此路由 | ReadinessState + 显式核心MySQL dataSource |
| `/actuator/health/liveness` | 404 | status唯一字段 | 与主/livez同组 |
| `/actuator/health/readiness` | 404 | status唯一字段 | 与主/readyz同组 |
| `/actuator/health/dependencies` | 404 | 固定类别详情 | Redis、MQ被动连接观察、独立统计池、统计意图/实际 |
| `/actuator/health` | 404 | 安全固定状态详情 | 聚合所有组成员，可能因可选依赖DOWN而503 |
| `/actuator/info` | 404 | 安全构建信息 | 仅artifact/group/name/version，无环境、git、主机/系统、启动时间或秘密 |
| `/actuator/metrics`及单项 | 404 | 基础度量 | 既有HTTP次数/耗时与独立池，业务自定义观察由08补全 |

所有健康异常仅返回`unavailable`等固定类别；不加入Throwable、异常类/消息、SQL、连接地址、账号/秘密或访客数据。DB自动聚合健康、默认Redis/Rabbit/disk等指示器全部关闭，以免将统计池错误纳入核心或泄露默认详情。显式限定核心`dataSource`、统计`statsDataSource`是装配选择，健康响应不展示这些bean名/数据库元数据。

管理端口仅允许health/info/metrics read-only端点，默认端点access none且max-permitted read-only；关闭JMX暴露和发现链接。env/configprops/heapdump/loggers/shutdown/mappings等不创建/不暴露。这里没有Spring Security，也不会继承业务 `X-Internal-Token` 鉴权；管理端口不要求该头，这是明确的本机网络边界。不要把业务令牌当作Actuator保护。另行改变监听地址/宿主发布或公网部署时需要重新设计访问边界。

## 故障状态与运行意图

- Redis或MQ不可用：main/livez和core/readyz仍UP；依赖组展示DOWN。Redis故障时核心就绪不保证创建/管理可用，它们仍按限流fail-close独立503；跳转fail-open仍受实际回源并发保护。MQ故障仍保持best-effort统计，不能从核心UP推断PV已记录。
- 核心MySQL不可用：readiness503，liveness仍UP。恢复连接后探针重新核验；healthcheck不负责重启应用、恢复数据或提供高可用。
- 独立统计池失败：statisticsDatabase DOWN、dependencies503，核心组不受影响；即使同一个MySQL，两个池仍分别观察，不能把统计故障聚合为核心池故障。
- statistics详情包含collectionIntent/collectionActual、handoffActual、publisherActual，以及consumerIntent/consumerActual。配置意图和实际状态分列：关闭采集不关闭历史消费，配置消费者disabled和实际stopped不会被探针改成enabled/running；配置enabled但人工停止时显示enabled/stopped，不自动覆盖人工暂停。publisherActual的not-recovering并不保证broker接收或MySQL保存。
- mqDependency是已有发布/消费连接的**被动**onCreate/onClose及isOpen观察，分别报告connected/unavailable；任一已有连接仍开放时MQ组件UP。它不建立探针连接、不启停消费者，不确认policy/route/ready/unacked或端到端消费。连接断开及恢复按现有生命周期/心跳可延迟可见；这些broker数据仍通过受限Management/实际冒烟观察。

`ReadinessState.REFUSING_TRAFFIC`（包括启动未完成/关闭）使核心readiness不可服务；外部依赖不改变自身liveness。探针只观察，没有控制消费意图的操作入口。

## Compose探针与验证

镜像将仅用JRE标准库的 `CoreReadinessProbe` 单独编译到 `/app/probe`，使用短连接/读取等待，读取容器内**主8080的/readyz**并要求200和精确`{"status":"UP"}`。不依赖镜像里不存在的curl，不从独立8081存活反推业务端口存活。该辅助JVM最大堆32MiB，运行时额外开销包含在应用容器预算内；探针预算不是业务HTTP总时限。

`ops/compose/smoke.ps1` / `ops/compose/smoke.sh` 使用独立秘密、随机localhost业务/管理/MQ Management端口和唯一临时卷，实跑以下矩阵：健康精确无详情、危险端点404/无旧令牌依赖、安全info/基础HTTP池指标、暂停消费意图不被探针修改、Redis/MQ实时故障和再次启动核心仍UP、MySQL实时故障readiness503/livenessUP/容器unhealthy，恢复后readinessUP。报告仍在target/compose-smoke；不保存展开配置或容器环境。

真实Spring双HTTP服务器测试 `HealthManagementHttpTest` 使用受控JDBC/Redis/AMQP公共边界补验仅统计池故障、原始异常canary、配置enabled/人工stop的差异和AvailabilityChangeEvent关闭意图。已有MQ启动/关闭和HTTP限流装配回归继续运行。测试详情见07验收记录。

HTTP基础指标使用Spring MVC模板route或固定未知/拒绝类别，不用原始URL/IP/短码等标签；JDBC指标name=dataSource与stats（Boot自动去除statsDataSource的后缀），Hikari分别观察核心与visit-statistics池。没有自动取得broker队列ready/unacked，也没有本阶段新增的全部业务指标。这里没有Prometheus/Grafana/动态日志操作。

API依据：[Spring Boot 3.5.16 Actuator端点与健康分组](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html)、[管理服务端口](https://docs.spring.io/spring-boot/3.5/reference/actuator/monitoring.html)。
