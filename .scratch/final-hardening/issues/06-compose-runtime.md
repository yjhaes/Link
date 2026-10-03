Status: ready-for-agent
Type: task
Blocked by: 01

# 06：全栈Compose运行、持久卷和资源边界

## Parent

[阶段 8 实现规格](../spec.md)

## What to build

使用者完成一次秘密初始化后即可运行一个应用和MySQL/Redis/RabbitMQ，完成真实创建、跳转及异步统计，重复启动保存持久数据和积压。

## Blocked by

- [01：安全默认配置与独立秘密初始化](01-safe-defaults-and-secrets.md)

## 故事覆盖

1～4、29、31～36，以及部署资源预算。

## Acceptance criteria

- [ ] 四个常驻服务、内部网络服务名连接、单应用进程内发布/消费；多阶段固定兼容镜像构建、非root应用，镜像上下文不含实际秘密/个人缓存。
- [ ] MySQL/RabbitMQ命名卷，Redis演示无持久化；应用/管理/MQ Management仅向localhost发布，MySQL/Redis/AMQP默认不发布，调试另用override/profile。
- [ ] 初始化实际创建项目DB账号、MQ账号/vhost和既有policy，路由/声明顺序可核验，不依赖人工粘贴命令。
- [ ] 只以核心MySQL健康作为应用启动硬依赖，MQ后台启动/恢复协议保持；全栈演示必须核验全部组件，不把容器running当ready。
- [ ] Redis maxmemory128MiB/noeviction/容器256MiB，app容器768MiB/堆384MiB、MySQL/RabbitMQ各1GiB可配置，记录实测修正，不承诺最低配置或HTTP总耗时。
- [ ] 当前应用能力能在新环境完成创建/跳转/异步统计冒烟；普通重启保留映射/日志/积压和身份密钥，不删除队列恢复声明冲突。
- [ ] Redis重建/恢复按既有协议说明，账号/秘密变化与已有卷的关系明确，普通启动、轮换、明确数据重置可区分。
- [ ] 仅使用此时已有的启动/设施健康和HTTP业务冒烟；完整Actuator分组由任务07交付，不能新增临时健康API代替它。

## Comments

- 2026-10-04：用户确认 12 项拆分的粒度与依赖后发布。依据已接受的阶段 8 规格；本次仅发布任务，未认领或实施。执行时遵循任务生命周期，验收全部满足后在 Answer 中记录证据再标为 resolved。
