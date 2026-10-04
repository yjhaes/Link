# 自动正确性回归

GitHub Actions `.github/workflows/correctness.yml` 在 push、pull_request 和手动 workflow_dispatch 运行 Ubuntu24.04 作业；只申请 contents:read，checkout不保留访问凭据，不部署公网或发布镜像。JDK17、Node24、Python3.13和runner的Docker/Compose是必需环境。脚本预检缺Dockerdaemon或Compose返回失败，必测JUnit缺类/零项/失败/错误/跳过不通过。

本地等价入口：Windows `pwsh -NoProfile -File ops/ci/run.ps1`；Linux `sh ops/ci/run.sh`。仅检查环境可加 `--preflight`。统一入口串行运行无设施Python检查、`ops/tests/run.py all`（页面/Java无设施及真实隔离设施），最后执行`ops/compose/smoke.py`重新构建本轮独立应用镜像，核验最终HTTP/健康/观测/文档及持久化恢复。有限性能观察另行显式执行，不自动混入CI，不作为正确性门槛。

## 安全报告和失败清理

报告保留在`target/ci/<run>/reports`。只从本次启动产生的regression和compose-smoke子目录收集确定命名的summary、JUnitXML、已处理日志和低基数指标JSON，不把历史target一起上传。收集CLI对普通中断残留.env中的密码/令牌/HMAC再替换；隐藏文件、env、ports.yml、jar/image、raw inspect和expanded config没有上传资格，符号链接不收集。原始容器inspect只在smoke进程内存读取，正常及业务失败后的finally删除秘密文件。workflow在普通成功/失败后always上传显式路径，找不到报告返回错误，保存7日。

回归和Compose子脚本在finally只执行各自随机project的down --volumes --remove-orphans，不碰演示卷或个人数据；测试端口动态localhost，应用镜像为本次随机tag。入口不使用全局prune。命令运行期间不设置额外子进程强制超时以免绕过其finally；GitHub作业75分钟超时、强杀进程、runner取消/断电或Dockerdaemon不可用仍可能阻止清理。此时只按已输出project标签清理本次资源；托管临时runner随后销毁，个人Docker宿主必须按[测试说明](testing.md)/[Compose说明](compose.md)核验对应project，不能全局删除。

## 固定 Actions 的来源

2026-10-04查验GitHub官方release及git tag API，工作流使用完整提交SHA；更新需重新核验，不使用漂移major tag：

| Action | Release | SHA |
| --- | --- | --- |
| checkout | [v7.0.1](https://github.com/actions/checkout/releases/tag/v7.0.1) | `3d3c42e5aac5ba805825da76410c181273ba90b1` |
| setup-java | [v6.0.1](https://github.com/actions/setup-java/releases/tag/v6.0.1) | `de7274f081f381c8f8158605e0321c36c376e2e6` |
| setup-node | [v7.0.0](https://github.com/actions/setup-node/releases/tag/v7.0.0) | `820762786026740c76f36085b0efc47a31fe5020` |
| setup-python | [v7.0.0](https://github.com/actions/setup-python/releases/tag/v7.0.0) | `5fda3b95a4ea91299a34e894583c3862153e4b97` |
| upload-artifact | [v7.0.1](https://github.com/actions/upload-artifact/releases/tag/v7.0.1) | `043fb46d1a93c77aae656e7c1c64a875d1fc6a0a` |

upload-artifact隐藏文件默认排除的官方说明见[README](https://github.com/actions/upload-artifact#uploading-hidden-files)，本仓库另外使用显式白名单收集，不能仅靠隐藏属性保护秘密。现代Action运行时要求当前GitHub hosted runner；自托管老runner需按各官方README核验最低版本。

## 本轮证据边界

本任务不执行远程push/触发GitHub Actions，因此新增工作流是已配置状态，不宣称已有GitHub托管成功run。实际本地等价入口版本、测试数、失败/错误/跳过、最终Compose项目与Linux入口核验范围在[10验收](../.scratch/final-hardening/10-verification.md)记录；执行命令并不代替成功报告。本地Windows Docker Linux容器结果不等于原生Linux宿主运行证据。
