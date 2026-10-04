Status: resolved
Type: task
Blocked by: 01 — 创建并访问永久短链接

## What to build

匿名创建者遇到偶然的短码重复时，系统自行尝试新候选值；若无法创建或数据库发生其他错误，调用者获得明确而不泄露内部信息的失败响应。

## Acceptance criteria

- [x] 创建流程直接插入短码候选；仅短码主键冲突时再生成候选并最多重试三次，总计最多四次尝试。
- [x] 首次主键冲突而后续候选成功时，返回新短码对应的 `201`，数据库只产生本次成功的映射。
- [x] 四次候选都发生短码冲突时，返回 `500 SHORT_CODE_GENERATION_FAILED`，不产生新映射。
- [x] 其他数据库写入错误不按碰撞重试，返回不暴露 SQL 或内部异常文本的 `500 INTERNAL_ERROR`；失败响应包含 `Cache-Control: no-store`。
- [x] 使用可控制的候选短码测试重试次数及失败分支，并使用真实 MySQL 验证短码主键约束；受影响测试通过。

## Answer

- 创建时逐个直接插入最多四个短码候选；只有 MySQL 错误码 `1062` 且冲突键为 `PRIMARY` 时才重试。
- 后续候选插入成功时返回其短码和 `201`；四次主键冲突后返回 `SHORT_CODE_GENERATION_FAILED`，未创建额外映射。
- 其他数据库错误不重试，由统一处理器返回不含 SQL 或异常文本的 `INTERNAL_ERROR`，并设置 `Cache-Control: no-store`。
- 使用可控候选码覆盖重试成功、四次冲突耗尽和其他唯一约束错误；连接本机 MySQL 9.5 的隔离临时数据库运行 API 集成测试 19 项及 service 单元测试 3 项，共 22 项全部通过。
