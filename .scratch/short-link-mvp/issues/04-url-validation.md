Status: ready-for-agent
Type: task
Blocked by: 01 — 创建并访问永久短链接

# 04: 严格校验原始 URL 与参数错误

## What to build

匿名创建者提交合法 URI 时，其目标地址按原文保存；提交不符合第一阶段边界的地址或错误 JSON 时，收到一致且可理解的参数错误。

## Acceptance criteria

- [ ] 接受长度不超过 4,096 字符、带主机的绝对 HTTP 或 HTTPS URI，包括 `localhost`、内网目标以及合法的路径、查询参数和片段；保存并在跳转 `Location` 中使用原文。
- [ ] 拒绝缺失或空原始 URL、超长输入、首尾或内部空白、控制字符、无效编码、不合法端口、缺少主机、非 HTTP/HTTPS 协议、用户名或密码，以及直接输入的非 ASCII 字符；不自动改写输入。
- [ ] 非法 URL、缺少字段和格式错误的 JSON 返回 `400 INVALID_REQUEST`；错误正文包含稳定的 `code` 和可读 `message`，失败响应包含 `Cache-Control: no-store`。
- [ ] 校验只检查 URI 格式，不查询 DNS、不探测目标网站，也不在服务端跟随目标跳转。
- [ ] 通过 HTTP 测试覆盖有效目标、保留查询参数和片段、长度边界及主要错误类别；受影响测试通过。

## Comments
