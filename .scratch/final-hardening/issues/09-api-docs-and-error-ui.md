Status: ready-for-agent
Type: task
Blocked by: 04, 05

# 09：业务OpenAPI与页面错误契约

## Parent

[阶段 8 实现规格](../spec.md)

## What to build

API使用者能在本地Swagger UI查看并调用真实业务，准确区分429、业务前503和已提交协调未确认，页面也给出一致提示。

## Blocked by

- [04：跳转限流与实际回源并发保护](04-redirect-rate-limiting-and-load-admission.md)
- [05：鉴权后的管理写入与查询限流](05-management-rate-limiting.md)

## 故事覆盖

9、43～44，以及现有API/统计契约展示。

## Acceptance criteria

- [ ] 添加兼容现有Boot主版本的springdoc OpenAPI与本地Swagger UI，不为文档升级技术栈。
- [ ] 覆盖创建/跳转/HEAD/状态/统计/明细、参数与管理头、Location/no-store/Retry-After、201/302及完整错误契约。
- [ ] 正确区分429、限流不可用、回源繁忙、原有已提交协调未确认及统计忙/超时；页面按错误码解释，不误报已保存。
- [ ] 明确创建无请求幂等键、受控协调恢复、过期/禁用顺序、重复状态409、统计30日窗口/范围UV/异步可见性与HEAD不计数。
- [ ] UI不预填或持久保存真实令牌，只展示业务API，不混入Actuator。
- [ ] 真实OpenAPI契约、HEAD和错误页面验收通过，补最小API使用说明，不在本任务编写最终简历或夸大能力。

## Comments

- 2026-10-04：用户确认 12 项拆分的粒度与依赖后发布。依据已接受的阶段 8 规格；本次仅发布任务，未认领或实施。执行时遵循任务生命周期，验收全部满足后在 Answer 中记录证据再标为 resolved。
