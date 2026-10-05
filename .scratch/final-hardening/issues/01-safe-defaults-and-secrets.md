Status: resolved
Type: task
Blocked by: None

## Parent

[阶段 8 实现规格](../spec.md)

## What to build

使用者第一次运行时不会意外开放管理入口或采集访问，能够生成稳定、独立的本地秘密；显式配置后原有创建、管理和统计能够正常使用。

## Blocked by

None（无前置依赖；执行需用户另行授权）。

## 故事覆盖

21、32～35，以及运行秘密安全边界。

## Acceptance criteria

- [x] 管理令牌无默认值；未配置时管理入口保持404，缺失/错误/重复头保持401且不进入业务。
- [x] 基础采集默认关闭，启用采集必须提供有效独立HMAC；消费者默认开启，停采不等于停止历史消费。
- [x] 提供一次本地初始化生成管理令牌、HMAC及后续DB/MQ凭据，真实配置被Git忽略，示例仅占位。
- [x] 普通重复初始化/重启不擅自替换现有秘密，UV身份密钥保持稳定；轮换/数据重置另有明确说明。
- [x] 输出、日志、构建素材不出现秘密；公开创建/跳转不增加管理令牌要求。
- [x] 无配置、非法配置、显式开启以及正常业务的配置/MVC验收通过，同步默认行为说明。

## Comments

- 2026-10-04：用户确认 12 项拆分的粒度与依赖后发布。依据已接受的阶段 8 规格；本次仅发布任务，未认领或实施。执行时遵循任务生命周期，验收全部满足后在 Answer 中记录证据再标为 resolved。
## Answer

- 基础 YAML 管理/HMAC 默认为空，采集默认 false、消费者 true。DB/MQ 使用项目账号名与空默认密码；程序化 Rabbit 属性也去除了 guest 回退。显式启用采集须满足已有有效 HMAC/版本校验，新增启动校验拒绝与管理令牌完全相同的 HMAC。统计配置诊断字符串隐藏秘密。
- 文档 `docs/入门与使用/local-secrets.md` 与 README/ops README 同步默认值、显式导入环境变量、项目账号供给、稳定 UV、显式轮换/密钥版本和数据重置边界。
- TDD 证据：真实 packaged YAML 装配测试初次因已提交默认管理秘密失败；启用采集复用管理秘密测试因未拒绝失败；HMAC 诊断输出测试因 record 默认 toString 泄露测试 canary 失败；程序化 Rabbit 配置测试因 guest 默认失败。各切片修复后通过。
- 验收：`mvn -q '-Dtest=SafeDefaultsConfigurationTest,StatsConfigurationTest,InternalManagementConfigurationTest,InternalManagementApiTest,InternalManagementDisabledApiTest,VisitCollectionFailureTest,VisitMessageCodecTest,VisitMqRuntimeTest,VisitPublisherFailureTest,VisitMqShutdownTest' test`：32 项、0 failures、0 errors、0 skipped。覆盖缺省/非法/显式开启配置、管理 404/401 先于业务、公开业务不要求令牌、统计失败隔离及消费生命周期相关回归。
