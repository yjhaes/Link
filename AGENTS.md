# AGENTS.md

## 自然语言偏好

- 所有最终回复必须使用简体中文。

## Shell 偏好（用户明确要求，永久生效）

- 执行 PowerShell 相关命令时，优先使用 `pwsh`（PowerShell 7）。
- 运行 .ps1 脚本、PowerShell 命令、模块操作等，使用 `pwsh -NoProfile -Command ...`。
- 仅当任务明确要求 Windows PowerShell 5.1 或 cmd 行为时，才使用 `powershell` 或 `cmd`。

<!-- CODEGRAPH_START -->
## CodeGraph

In repositories indexed by CodeGraph (a `.codegraph/` directory exists at the repo root), reach for it BEFORE grep/find or reading files when you need to understand or locate code:

- **MCP tool** (when available): `codegraph_explore` answers most code questions in one call — the relevant symbols' verbatim source plus the call paths between them, including dynamic-dispatch hops grep can't follow. Name a file or symbol in the query to read its current line-numbered source. If it's listed but deferred, load it by name via tool search.
- **Shell** (always works): `codegraph explore "<symbol names or question>"` prints the same output.

If there is no `.codegraph/` directory, skip CodeGraph entirely — indexing is the user's decision.
<!-- CODEGRAPH_END -->

## Agent skills

### Issue tracker

创建、读取或更新任务时，使用本地 Markdown；先读取 `docs/agents/issue-tracker.md`。

### Triage labels

评估任务或更新状态时，使用默认的五个分流标签；先读取 `docs/agents/triage-labels.md`。

### Domain docs

探索代码或讨论领域概念时，使用 single-context 布局；先读取 `docs/agents/domain.md`。
