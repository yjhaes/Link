# 09～12 集成记录

用户在2026-10-04（Asia/Shanghai）授权执行09、10、11、12；集成分支`codex/final-hardening-09-12`，固定审查基点`58250091239f1088b87c4782e2ed719d681bcf09`。实现使用当前模型，最终Standards Review和Spec Review按用户要求均显式GPT-6.1 Sol/high。

## 已合并与版本边界

- [09 API文档与页面错误契约](09-verification.md)：springdoc2.8.17/Boot3.5.16，146项Java+2项页面测试通过，其后增强2项真实HTTP测试通过。
- [11 有限测量与故障链](../../docs/performance-and-failures.md)：正式源码`c7c8395adfbd2bfe0d6eef22915ba96c5e816aa3`，run`09e444c16b63`、source_dirty=false，47项真实断言与2项Python验收通过。四场景各240个HEAD全部302，实际映射SELECT0/240/0/240。大SET OOM与临时紧缩阈值的小写故障分开，恢复默认128MiB；不复用旧55%下降、稳定p99或容量承诺。
- [10 CI与最终全栈验证](10-verification.md)：源码`c4b47a3f31710fec5d99ac78ae4586c17e402716`，CI run`a3b7acbf7e1e`整体exit0。Python4、Node2及Java317（146/149/13/9）全部0失败/错误/跳过，Java missing/unexpectedClasses空。最终镜像Compose136条实际断言通过，所有随机项目资源和临时秘密已清理。
- [12 首页与正式展示](12-verification.md)：26项实际冷启动HTTP/运行检查通过，8份UTF-8文档与53个本地链接无断链，两张真实公开截图目视检查，旧/新项目与秘密均清理。截图来源635ffef且sourceDirty=true，仅文档修改；应用源码与完整CI的c4b47a3一致。

本轮没有远程push或触发GitHub Actions；工作流已配置并通过官方actionlint静态检查，实际结果属于Windows PowerShell7本地等价入口和Docker Linux容器。原生Linux宿主全量未实跑，不冒称托管CI成功。

安全原始报告保存在主工作树target：`ticket09`、`regression/c24dcfb77107`、`observations/09e444c16b63`、`ci/a3b7acbf7e1e`、`regression/10be088bdf9a`、`compose-smoke/2f8ecc2ff4da`、`ticket10-static`。正式公共入口将由12的docs/verification.md承接；本地工作路径不是唯一证据。

## 双轴审查

审查目标为固定基点58250091239f1088b87c4782e2ed719d681bcf09到集成3d768cd4f62da2afa24b6a636bff74d7ae7b217f。Standards Review与Spec Review分别显式GPT-6.1 Sol/high，独立只读审查完成：Standards 0项，Spec 0项，无需审查修复。审查未重复昂贵回归；最后仅补任务关闭/证据元数据和准确包名，不改变生产或测试语义。

## 最终交付

01～12均resolved，父规格已关闭，完整交付位于codex/final-hardening-09-12。正式入口为 [README](../../README.md)、[验证证据](../../docs/verification.md)、[简历素材](../../docs/portfolio.md)。临时工作树归档前，必要安全原始报告已保留主工作树target；公开JSON/截图随Git提交。未远程推送或执行GitHub托管作业，未原生Linux全量，均保留准确限制。
