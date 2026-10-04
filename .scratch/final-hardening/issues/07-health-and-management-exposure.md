Status: resolved
Type: task
Blocked by: 06

# 07：分组健康检查与安全管理端口

## Parent

[阶段 8 实现规格](../spec.md)

## What to build

运维查看者能从本机判断应用存活、核心就绪和依赖/统计状态，MQ或Redis故障不会被误报为整个服务死亡。

## Blocked by

- [06：全栈Compose运行、持久卷和资源边界](06-compose-runtime.md)

## 故事覆盖

36～37，以及健康暴露与运行意图。

## Acceptance criteria

- [x] 添加最小Actuator；liveness只判断自身，core readiness为应用就绪与核心MySQL，统计池不错误聚合为核心失败。
- [x] Redis/MQ/统计意图与实际状态分别以安全固定类别展示，不启动消费者、不覆盖人工暂停，不使其成为核心硬依赖。
- [x] 管理端口仅health/info/metrics，info安全；主端口给无详情存活/就绪探针，验证主HTTP入口。
- [x] env/configprops/heapdump/动态日志修改不开启，健康详情不含连接地址/SQL/秘密/原始异常，验证没有误依赖旧管理鉴权保护Actuator。
- [x] 接入已有HTTP/池基础指标，业务自定义观察由任务08补全，不提前声称全部broker指标已自动取得。
- [x] Redis/MQ停机、核心MySQL故障、统计池异常/消费者暂停、启动关闭的真实分组验收通过；Compose探针使用正确分组，不使用聚合失败阻断全部核心。

## Comments

- 2026-10-04：用户确认 12 项拆分的粒度与依赖后发布。依据已接受的阶段 8 规格；本次仅发布任务，未认领或实施。执行时遵循任务生命周期，验收全部满足后在 Answer 中记录证据再标为 resolved。

## Answer

2026-10-04：完成Boot3.5.16最小Actuator、self-only liveness/qualified核心DB+readiness、独立依赖/统计安全状态、无控制副作用的被动MQ观察、独立localhost管理端口和仅主端口无详情探针。仅health/info/metrics read-only，禁用危险端点/JMX/发现，info只安全构建字段；已有HTTP/独立池基础指标接入，业务自定义由08补全。真实Spring双端口与已有生命周期兼容9项通过；真实全栈Compose64项断言通过，含运行中Redis/MQ停机核心UP、实际MySQL停机ready503/livenessUP/JRE容器unhealthy及恢复、统计池异常与暂停意图不被健康修改。证据 [07验收](../07-verification.md)，说明 [健康文档](../../../docs/health.md)。Linux入口已提供，原生Linux宿主完整运行尚未验证；真实Linux容器探针/bootstrap已执行。
