# Redis 故障与受控恢复

MySQL 是短链接映射的权威数据源。即时可见仅适用于所有实例遵守版本协议、共享同一 MySQL 主写库及同一 Redis 主实例，并且已确认的协调更新没有丢失的运行过程。自动故障切换、恢复旧快照或丢失确认写入可能同时回滚业务结果和版本；即使 JSON 完全合法，应用也无法识别这种回滚。未经清理不能宣称保持即时可见。

## 临时不可达

Redis 连接及命令超时继续为 200ms。读取异常、损坏或未知结构、非法时间和未知业务类型按 miss／降级处理，访问者仍按 MySQL 返回 302、404、403 或 410；数据库异常仍是 500。版本无法确认时独立查库，不加入旧共享任务，不无条件回填。已取得版本后的条件回填失败也不会改变 MySQL 结果。超时不代表命令一定没有执行。

创建的 MySQL 提交成功而缓存协调未确认时返回带短码的 503；维护协调异常同样不能报告完成。保留短码，Redis 恢复后调用内部 `recoverCacheCoordination(shortCode)`，只重试协调，不重复创建或改写旧的启用状态。日志按短码记录读取、回填及协调错误。

若确认只是网络中断、同一 Redis 主实例的已确认更新未丢失，可恢复连接并重试未确认协调。若不能确认这一前提，按下面的完整清理流程处理。

## 重启、切换及旧数据恢复

1. 暂停创建和 SQL 状态维护，记录所有协调未确认的短码。撤下所有缓存启用实例的流量，等待在途请求结束并停止实例，包括内部维护进程。若无法排空，终止这些进程，确保其不会重连后继续旧任务；停止旧 Redis 的外部访问。仅滚动重启一个实例或删除一个 Key 不足以完成这一步。
2. 可启动读取降级实例，设置 `SHORT_LINK_REDIRECT_CACHE_ENABLED=false`（配置项 `short-link.redirect-cache.enabled`）。该启动配置停用所有缓存读写及协调，跳转独立查 MySQL；它不是动态开关，需要重启生效。不要放行创建流量：若误调用创建，MySQL 仍可能提交，返回的是协调未确认的 503。内部协调入口也会异常，不能报告维护完成。
3. 完成 Redis 重启、实例切换或备份恢复，确认应用将连接的主实例、端口和逻辑数据库。阻止旧实例、后台恢复过程或其他写入者再次写入这个命名空间；先完成恢复再清理，不能清理后再载入旧备份。
4. 在这个 Redis 数据库中清理 **全部** `shortlink:redirect:*`，包括 v1 旧格式、v2 业务结果及其版本占位。下列 PowerShell 7 示例要求本机已有 `redis-cli`；认证通过维护环境的 `REDISCLI_AUTH` 提供，TLS 环境在参数中添加 `--tls`。不要对共享数据库执行 `FLUSHDB`。

   ```powershell
   # 替换为已核对的目标主实例和数据库；脚本中途异常时保持缓存停用。
   $redisArgs = @('-h', '127.0.0.1', '-p', '6379', '-n', '0')
   $cursor = '0'
   do {
       $reply = @(& redis-cli @redisArgs --raw SCAN $cursor MATCH 'shortlink:redirect:*' COUNT 100)
       if ($LASTEXITCODE -ne 0 -or $reply.Count -lt 1 -or $reply[0] -notmatch '^\d+$') {
           throw 'Redis SCAN 未确认成功；保持缓存停用。'
       }
       $cursor = $reply[0]
       foreach ($key in ($reply | Select-Object -Skip 1)) {
           if (-not $key.StartsWith('shortlink:redirect:')) { throw '缓存命名空间不匹配。' }
           $removed = & redis-cli @redisArgs --raw UNLINK $key
           if ($LASTEXITCODE -ne 0 -or $removed -notmatch '^[01]$') {
               throw 'Redis UNLINK 未确认成功；保持缓存停用。'
           }
       }
   } while ($cursor -ne '0')
   $remaining = @(& redis-cli @redisArgs --scan --pattern 'shortlink:redirect:*')
   if ($LASTEXITCODE -ne 0 -or $remaining.Count -ne 0) {
       throw '仍有缓存或检查失败；保持缓存停用，检查旧写入者后重试。'
   }
   ```

   SCAN 可重复返回 Key，UNLINK 重复执行安全；在没有写入者的前提下完整遍历并再次确认空命名空间。清理失败可重跑。此流程不修改 MySQL 或其他 Redis 命名空间，不清理单个业务状态来代替整体版本清理。
5. 清理确认后，在仍暂停变更流量的前提下启动缓存启用实例（开关恢复 `true`），所有实例使用相同 v2 协议及目标 Redis。停止读取降级实例，再恢复正常跳转流量，首次访问按需回源并建立新版本和结果；不预热。抽查已启用、已禁用、已过期及不存在短码，核对 302/403/410/404、Location 和 no-store；用受控查询计数确认后续命中不再查库，核对结果及占位都有正的有限 PTTL。
6. 对记录中的未确认短码逐一执行内部协调恢复，正常返回后才确认这些操作完成；异常时继续保留未确认状态。最后恢复创建及维护流量。清理前后的在途请求不属于连续运行的即时可见保证，新的保证从清理完成并确认协调后的运行过程开始。

## 演示与压力边界

运行 `mvn '-Dtest=RedisRedirectIntegrationTest' test`（需要 Docker）可复现真实 Redis `CLIENT PAUSE WRITE` 造成的命令超时、四类 MySQL 结果、读取回填超时、旧共享任务隔离及创建 503。测试用 `CLIENT UNPAUSE` 恢复，并给暂停设置有限时长；只在隔离测试容器中使用，不对生产执行故障注入。

恢复演示保存旧 404 的整个 v2 条目，创建并完成协调后恢复旧结果及旧版本，同时放入 v1 数据；停用缓存的独立应用实例仍读取当前 MySQL。所有调用结束后按命名空间清理，确认旧版本不能回填，首次访问重新加载、后续命中不查库，其他命名空间保留。有限的四个新无效短码仍分别回源，结果和无业务含义的版本占位都有有限 TTL。

TTL 抖动仅分散部分集中到期。实例内合并仅减少正常同码同版本交叠 miss，每实例允许各查一次；未知版本及等待超时允许额外回源。Redis 整体不可用时 MySQL 可能承受全部请求，200ms 不是 HTTP 总耗时上限。正常命中的热点仍直接访问 Redis。持续更换合法无效短码仍会产生查询和缓存，有限 TTL 不等于严格内存上限、抗攻击能力、目标 QPS 或任意故障下全局只查一次。

本任务不实现 Bloom Filter、跨实例互斥回源、长期本地缓存、热点拆分、预热、后台刷新、专门限流／熔断、Sentinel／Cluster 或新的监控平台。常规业务和并发验收保留在各前置任务；本任务交付受控故障降级及清理恢复行为。

故障注入采用 WRITE 模式，因为它也会阻塞 EVAL/EVALSHA，同时允许解除暂停；见 [Redis CLIENT PAUSE 文档](https://redis.io/docs/latest/commands/client-pause/)。
