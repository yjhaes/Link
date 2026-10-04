# 09 验收记录

2026-10-04（Asia/Shanghai）。基于 `58250091239f1088b87c4782e2ed719d681bcf09` 的独立工作树/分支 `codex/hardening-09`，实现提交 `340b37d`；已同步当时集成分支，同步后 `7e54292c7338fc820c210c5ce112b5a9c8af757e`。本票不执行后续最终展示整理。

固定 Spring Boot 3.5.16 / Java 17，新增 springdoc 2.8.17，官方兼容表与release链接见 [API说明](../../docs/api.md)。通过生成文档而非手写静态spec覆盖5条真实业务路径、8个方法操作（含3个隐式HEAD）、DTO引用、管理API-key头、日期/分页约束、201/302和400/401/403/404/409/410/429/500及各类503。错误与HEAD的Location/no-store/Retry-After、已提交协调oneOf结构准确区分；不改变业务算法/存储/事务。

## 红绿与证据

- 真实HTTP第一片 `BusinessOpenApiHttpTest` 在未加文档时 `/v3/api-docs` 返回404而预期200，明确红；增加兼容依赖/配置/契约后绿。
- 页面回源忙提示先缺失而失败，再增加固定 `REDIRECT_LOAD_BUSY` 提示后绿；不将繁忙/业务前503解释为已保存。
- `ops/tests/run.ps1 unit`：**146项Java、2项Node页面通过**，failed/error/skipped均0，missing/unexpected class空。目录 `target/regression/c24dcfb77107`。该次包含新增2项HTTP首轮验收；其后只补同一HTTP测试的schema引用、管理端口及已提交503断言，生产代码未再改变。
- 补验 `./mvnw.cmd --batch-mode '-Dtest=BusinessOpenApiHttpTest' '-Dtest.reportsDirectory=target/ticket09/http-final' test`：**2项通过**，失败/错误/跳过0。最终测试源随实现提交；原始报告 `target/ticket09/http-final`，运行日志 `target/ticket09/http-final.log`。页面最终 `node --test --test-reporter=tap src/test/js/*.test.mjs`：2项通过，报告 `target/ticket09/pages.log`。
- `git diff --check`通过。

真实嵌入Tomcat HTTP公开边界验证：仅业务文档，无Actuator路径；所有schema引用可解析；Swagger index和实际初始化JS 200，未预填测试令牌、未调用preauthorize；Swagger实际配置 persistAuthorization=false，管理端口无业务文档内容。HEAD302 Location/no-store、无体/无统计Cookie和事件；跳转/两个查询HEAD429保留向上取整Retry-After=2/no-store，无体/无Cookie。创建业务前503不含shortCode且不发号；控制持久化确认及缓存故障后HTTP503明确CREATE_CACHE_COORDINATION_UNCONFIRMED并保留shortCode，不泄露异常。既有页面创建/管理提示保持429、业务前503、已提交503、统计繁忙/超时差异。

最小使用契约、限流/异步窗口/无幂等/受控恢复入口边界见 `docs/api.md`；未增加恢复HTTP端点，也未混入Actuator。
