# 贡献指南

欢迎通过 Issue 描述问题或建议，通过 Pull Request 提交改动。开始前请先阅读 [README](README.md)、[领域术语](CONTEXT.md)和相关 [ADR](docs/adr/)。目前项目未添加开源许可证。

## 本地开发

1. Fork 或克隆仓库，从最新主分支创建一个主题分支。
2. 使用 JDK 17 和仓库内的 Maven Wrapper；页面为原生 HTML/CSS/JavaScript，没有独立 npm 构建步骤。
3. 按[本机配置指南](docs/入门与使用/local-secrets.md)准备服务和本地 YAML。真实配置、密码、管理令牌及访客密钥不得提交。
4. 一次改动聚焦一个问题，遵循相邻代码的风格，避免无关的全项目格式化。

## 验证改动

Windows（PowerShell 7）：

```powershell
pwsh -NoProfile -File ops/tests/run.ps1 unit
```

Linux：

```sh
sh ops/tests/run.sh unit
```

以上需要 Python 3.10+、Node.js 18+ 和 JDK 17。涉及数据库、缓存或消息行为时，还应安装 Docker 和 Compose，运行隔离设施的完整回归：

```powershell
pwsh -NoProfile -File ops/ci/run.ps1
```

Linux 对应 `sh ops/ci/run.sh`。此入口创建隔离测试设施，不要用个人数据库或消息队列代替。详见[测试说明](docs/测试与验证/testing.md)和 [CI 说明](docs/测试与验证/ci.md)。

## 提交 Pull Request

- 说明具体问题、修改后的行为和验证结果；无法执行的检查请明确注明。
- 修改接口、配置或统计口径时同步更新对应文档；架构决策记录放在 `docs/adr/`。
- 页面改动可附截图；日志和截图先脱敏，不包含真实 URL 查询串、令牌、Cookie 或连接密码。
- 提交前运行 `git diff --check`，检查 `git status --short`，确认没有生成产物或本地配置。
- 提交标题简洁描述改动，可使用 `feat:`、`fix:`、`docs:`、`test:`、`chore:` 前缀。

GitHub Issue 是外部反馈入口；代理执行任务继续使用 [.scratch 本地任务规范](docs/agents/issue-tracker.md)，并关联来源。无需将历史任务文件搬到 GitHub。

## 反馈与交流

普通问题请提供可复现步骤、运行环境和预期结果。安全问题按 [SECURITY.md](SECURITY.md) 处理。讨论聚焦具体行为与证据，尊重参与者，不发布他人隐私或凭据。
