# Issue tracker: Local Markdown

任务和规格以 Markdown 文件存放在 `.scratch/` 中。

## 文件约定

- 每个功能使用 `.scratch/<feature-slug>/`。
- 规格文件为 `spec.md`。
- 每张任务单独保存为 `issues/<NN>-<slug>.md`，从 `01` 编号。
- 状态写在文件顶部的 `Status:` 行。
- 评论和讨论追加到文件底部的 `## Comments` 下。

## 状态生命周期

- 分流阶段使用 `needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human` 或 `wontfix`；含义见 `triage-labels.md`。
- 代理开始执行任务时，将状态更新为 `claimed`。
- 验收条件全部满足并在 `## Answer` 下记录结果后，将状态更新为 `resolved`。
- `ready-for-human` 表示任务需要人工处理，不能用于表示等待人工复核的已完成任务。

## 技能操作

- “发布到任务跟踪系统”：按上述约定创建文件及所需目录。
- “获取任务”：读取用户指定路径或编号对应的文件。

## Wayfinder 操作

- 工作地图：`.scratch/<effort>/map.md`，包含笔记、已作决定和待探索问题。
- 子任务：`issues/NN-<slug>.md`，正文记录问题。
- `Type:` 取值为 research、prototype、grilling 或 task。
- 使用 `Blocked by: NN, NN` 记录依赖；依赖全部 resolved 后可执行。
- 从开放、未阻塞、未认领的任务中，优先选择编号最小的任务。
- 按“状态生命周期”更新任务状态。
- 完成后在地图的已作决定部分追加摘要和链接。
