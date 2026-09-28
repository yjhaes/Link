# Triage Labels

| 技能中的角色 | 本项目状态值 | 含义 |
|---|---|---|
| needs-triage | needs-triage | 待评估 |
| needs-info | needs-info | 待补充信息 |
| ready-for-agent | ready-for-agent | 可交给 AI 执行 |
| ready-for-human | ready-for-human | 需要人工处理 |
| wontfix | wontfix | 决定不处理 |

技能要求应用某个角色时，使用对应状态值。
本地任务文件使用 `Status:` 行记录状态。

这些值只用于任务进入执行阶段前的分流。代理认领和完成任务后，分别使用 `claimed` 和 `resolved`；完整生命周期见 `issue-tracker.md`。
