# 自动正确性回归

GitHub Actions `.github/workflows/correctness.yml` 在 push、pull_request 和手动触发时运行，只申请 contents:read，checkout 不保留访问凭据。现有完整工作流包含外部基础设施验收；无设施回归单独见 [测试说明](testing.md)，不能将两者视为相同执行范围。

## 安全报告

报告保留在 `target/ci/<run>/reports`，只上传本轮白名单产物，不上传秘密、原始环境配置或整个 target。必测报告缺失、零项、失败、错误或跳过均不通过。普通成功或失败均上传报告，保存 7 日；强杀或作业取消可能阻止清理。

## 固定 Actions 的来源

2026-10-04查验GitHub官方release及git tag API，工作流使用完整提交SHA；更新需重新核验，不使用漂移major tag：

| Action | Release | SHA |
| --- | --- | --- |
| checkout | [v7.0.1](https://github.com/actions/checkout/releases/tag/v7.0.1) | `3d3c42e5aac5ba805825da76410c181273ba90b1` |
| setup-java | [v6.0.1](https://github.com/actions/setup-java/releases/tag/v6.0.1) | `de7274f081f381c8f8158605e0321c36c376e2e6` |
| setup-node | [v7.0.0](https://github.com/actions/setup-node/releases/tag/v7.0.0) | `820762786026740c76f36085b0efc47a31fe5020` |
| setup-python | [v7.0.0](https://github.com/actions/setup-python/releases/tag/v7.0.0) | `5fda3b95a4ea91299a34e894583c3862153e4b97` |
| upload-artifact | [v7.0.1](https://github.com/actions/upload-artifact/releases/tag/v7.0.1) | `043fb46d1a93c77aae656e7c1c64a875d1fc6a0a` |

upload-artifact隐藏文件默认排除的官方说明见[README](https://github.com/actions/upload-artifact#uploading-hidden-files)，本仓库另外使用显式白名单收集，不能仅靠隐藏属性保护秘密。

## 证据边界

工作流已配置并静态核验，本次文档维护没有触发远程运行。
