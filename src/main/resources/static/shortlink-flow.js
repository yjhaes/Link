'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const escape = text => String(text).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const n = (title, subtitle, detail, source, result, code = '', type = '') => ({ title, subtitle, detail, source, result, code, type });
  const scenario = (label, caption, nodes) => ({label, caption, nodes});
  const src = {
    redirect: 'service/RedirectService.java', cache: 'cache/RedisRedirectCache.java', create: 'service/ShortLinkCreationService.java',
    state: 'service/ShortLinkStateService.java', collect: 'api/stats/VisitCollection.java', producer: 'stats/messaging/AsyncVisitRecorder.java',
    consumer: 'stats/messaging/VisitConsumer.java', persistence: 'stats/persistence/MySqlVisitPersistence.java', query: 'stats/query/MySqlVisitStatsQuery.java',
    api: 'api/ShortLinkController.java', rate: 'ratelimit/ · docs/入门与使用/api.md'
  };
  const format = n('校验短码', '4～8 位，区分大小写', '只接受 4～8 位 ASCII 字母或数字。非法短码直接返回 404，不读取 Redis 或 MySQL。合法短码继续经过跳转限流。', src.redirect, '非法格式 → 404 LINK_NOT_FOUND', '[A-Za-z0-9]{4,8}');
  const rate = n('令牌桶准入', 'Redis Lua 原子扣减', 'Lua 一次完成取时间、补充令牌、判断、扣减及设置 TTL。跳转按连接对端 IP 跨短码共享额度。无令牌返回 429；限流 Redis 故障则跳转放行，继续受实际回源并发保护。', src.rate, '获准后，业务失败不退令牌', 'GET / HEAD：容量 60，10 tokens/s');
  const finalCheck = n('逐请求最终检查', '重新检查业务到期时间', '缓存命中者、加载发起者和共享结果等待者都要单独检查 expiresAt。过期优先于禁用；共享查询不会共享整个 HTTP 响应，也不能共享访问事件。', src.redirect, '通过后冻结本请求的决定时刻', 'RedirectDecision(originalUrl, decidedAt)');
  const response = n('返回 302', 'Location + no-store', '控制器返回原始 URL 的 Location 和 Cache-Control: no-store。仅正常 GET 且采集开启时尝试采集访问事件；HEAD 维持跳转状态与响应头，但没有统计事件或 Cookie。', src.api, '302 不证明客户端打开了目标页面', 'HTTP 302 · Cache-Control: no-store');
  const dbPermit = n('实际 SQL 准入', '每实例默认 4 并发', '只有真正执行映射 SQL 才申请 Semaphore 许可。无许可立即拒绝，不新增排队；查询完成或失败后释放。缓存命中与共享任务等待者不占许可。', src.redirect, '无许可 → 503 REDIRECT_LOAD_BUSY', 'tryAcquire() → SQL → finally release()');
  const dbLoad = n('读取 MySQL', '权威映射与状态', '按短码主键查询。判定顺序为不存在、已过期、已禁用、可跳转。数据库异常不能缓存成“不存在”，实际 SQL 结束即释放准入许可。', src.redirect, '形成四类业务结果之一', 'NOT_FOUND / EXPIRED / DISABLED / REDIRECT');
  const fill = n('按版本回填', 'Lua 比较版本再写入', '只允许把结果写到读取时持有的缓存版本。版本已经轮换或不匹配时拒绝写入；缓存写失败也不抹掉 MySQL 已读结果。未取得可信版本时跳过回填。', src.cache, '原子校验与写入，阻止旧查询污染新缓存', 'storeIfVersion(code, generation, result)');
  const collect = n('冻结访问事件', '每个正常 GET 独立生成', '根据本次最终决定生成 UUID eventId、occurredAt 和上海统计日；识别匿名 Cookie，计算按短码隔离的 HMAC 访客摘要，并最小化网段、UA 与 Referer。事件发生在正常跳转决定时，不以响应发送成功为前提。', src.collect, '刷新 = 新事件；消息重投 = 原事件', 'eventId ≠ visitorHash');
  const buffer = n('有界本地交接', 'offer；默认容量 256', 'HTTP 线程只尝试立即交接，不等待 RabbitMQ 网络、发布确认或日志写库。队列满则跳过统计、记录丢弃，正常跳转继续。事件未持久化在本地，进程退出允许丢失。', src.producer, '队列满不把正常 302 改成错误', 'ArrayBlockingQueue.offer(event)');
  const publish = n('后台发布 MQ', 'confirm + mandatory/return', '独立后台发送 JSON 持久消息，使用 durable exchange/queue。默认最多 32 个未确认尝试。confirm ACK 且无 return 表示本次发布被 broker 接受，不代表 MySQL 已保存。失败或不确定的旧事件不自动补发。', src.producer, 'HTTP 不等待发送、confirm 或消费', 'confirm ACK ≠ consumer ACK ≠ DB COMMIT', 'async');
  const consume = n('消费并校验', '同步 listener / Spring AUTO', '校验 schema、事件字段、日期与保留窗口，不按映射的当前禁用状态改写历史访问。同步调用持久化；listener 正常返回后，容器才确认消息。合法超窗事件确认丢弃。', src.consumer, '不能先另起异步落库再返回成功', 'Spring AUTO ≠ RabbitMQ autoAck / NONE', 'async');
  const persist = n('幂等写入日志', 'eventId 唯一约束', '在统计专用池中独立保存。只有 eventId 唯一键重复才视为已处理；其他约束或 SQL 错误传播。访问日志是统计权威，没有再双写 Redis 计数或维护额外汇总表。', src.persistence, '同一 eventId 至多保留一条在线日志', 'uq_visit_event(eventId)', 'async');
  const ack = n('保存后确认', '正常返回 → 容器 ACK', '确认保存或仅 eventId 重复后，listener 返回，Spring 容器 ACK。若数据库已提交但 ACK 未送达，后续重投仍携带原 eventId，由 MySQL 唯一约束拦住重复入账。', src.consumer, '消息可能多投，PV 不重复增加', 'DB COMMIT → listener return → ACK', 'async');
  const auth = n('管理鉴权', '先鉴权，再限流和解析', '管理请求必须提供唯一且准确的 X-Internal-Token。未配置管理能力返回 404，令牌无效返回 401；鉴权发生在限流及请求体、短码、日期解析前。', 'api/management/ · docs/入门与使用/api.md', '令牌不放入 URL、Cookie 或 Redis Key', 'X-Internal-Token');
  const commit = n('提交状态事务', '行锁内检查并更新', 'SELECT FOR UPDATE 获取当前映射；拿到锁后才读取时间，避免锁等待跨过有效期。不存在 404、过期 410、重复目标 409。仅更新 enabled，独立事务确认提交后才进入 Redis 协调。', src.state, 'DB COMMIT 是缓存版本轮换的前置条件', 'SELECT FOR UPDATE → UPDATE → COMMIT');
  const rotate = n('轮换缓存版本', '新版本占位，清除旧结果', '数据库提交后，在 Redis 原子替换为新 generation 的有限 TTL 占位。后续读取当前 MySQL，而不是把本次操作的旧快照写回缓存。创建也执行同一协调协议，用来移除可能存在的旧“不存在”结果。', src.cache, '旧版本的迟到回填无法覆盖新版本', 'replaceVersion(shortCode)');

  const pages = {
    overview: {
      label:'链路总览', icon:'⌘', title:'一次短链接访问，如何流经整个系统？', description:'从短码创建到 302 跳转，再到异步入账。把正常路径、并发时序与失败边界放在同一张地图里。',
      flowTitle:'服务全景', flowSubtitle:'同一个应用内，跳转主流程与统计后台各自推进。',
      boundary:'缓存版本协议防止旧查询回填，不构成 MySQL 与 Redis 的分布式事务。统计可能漏记；请求合并和 SQL 并发上限均为单实例机制。',
      notes:[
        ['创建与状态变更：先确认数据库提交，再协调缓存', '独立发号表分配 ID，经过固定双射与 Base62 编码得到 4～8 位短码。首次插入映射即携带完整短码；创建与状态维护都在数据库提交后轮换缓存版本。协调未确认会返回含 shortCode 的专用 503。', 'create'],
        ['高并发跳转：缓存、请求合并、实际 SQL 准入各管一层', '缓存吸收重复读取；按“短码 + 缓存版本”的实例内共享任务减少同码重复 SQL；Semaphore 限制真正的 MySQL 回源。随机 TTL 缓解集中失效，负缓存区分不存在、过期和禁用。', 'redirect'],
        ['异步统计：本地交接、发布确认、数据库保存是三个边界', '正常 GET 逐请求冻结事件，HTTP 只做有界交接。后台经 RabbitMQ 消费到 MySQL，eventId 唯一键处理重复入账。确认发布不代表落库，统计也不保证端到端不丢。', 'stats'],
        ['查询：只统计已记录事件，跨日 UV 对整个范围去重', '最近 30 个上海统计日是允许查询的窗口，默认查询最近 7 日。汇总与趋势从同一只读快照读取；明细按发生时间与行 ID 倒序，游标绑定短码及日期范围。', 'query']
      ]
    },
    create: {
      label:'短链接创建', icon:'＋', title:'先拿到唯一 ID，再建立短码映射。', description:'发号、编码、保存与缓存协调分别承担职责；创建成功需要确认映射已提交及缓存已协调。', flowTitle:'创建链路', flowSubtitle:'POST /api/links · 相同原始 URL 可以创建多个独立映射。',
      boundary:'Base62 负责数字表示，唯一性来自自增 ID、固定双射和短码主键。混淆不是加密或授权；创建没有请求幂等键，未知结果不能盲目重 POST。',
      scenarios:[
        scenario('正常创建','不预热跳转结果；只轮换版本。',[
          n('创建限流与校验','按对端 IP，容量 3','在发号和写库前进行创建限流。默认每 6 秒补 1 个令牌。验证绝对 HTTP/HTTPS URL 与有效分钟；省略有效时长即永久。无额度 429，限流依赖失败 503。',src.create,'合法请求继续发号','POST /api/links'),
          n('MySQL 独立发号','AUTO_INCREMENT','向追加式独立发号表插入记录，取得唯一 ID。发号与映射分别提交；失败允许留下未使用 ID，不回收、不复用。', 'persistence/MySqlShortCodeIdIssuer.java','多个实例共享同一 MySQL 主写库','1 ≤ id ≤ 62^8'),
          n('固定双射 + Base62','最终短码为 4～8 位','对 ID 做固定有界双射后编码为 Base62，不足 4 位左补 0。采用不会让乘法溢出的运算，超过 ID 上限明确失败。规则固定后，不同有效 ID 对应不同短码。','shortcode/PermutedShortCodeEncoder.java','短码区分大小写，规则发布后保持稳定','v = (a × (id − 1) + b) mod 62^8'),
          n('保存最终映射','短码主键 / 提交确认','首次插入映射就带最终短码、原始 URL、过期时间与启用状态。只有短码 PRIMARY 冲突才重新发号并重试一次，总共最多两次映射插入；其他数据库错误不参与该重试。',src.create,'先确认映射提交，后执行缓存协调','MySqlShortLinkWriter.insertConfirmed(entity)'),
          rotate,
          n('返回新短链接','201 + shortCode / shortUrl','缓存协调确认后返回 201、shortCode、shortUrl、expiresAt；Location 指向新短链接。创建不直接生成访问事件。',src.api,'创建完成；真正访问时才采集','HTTP 201 Created')
        ]),
        scenario('映射已保存，缓存协调未确认','保留已提交的 shortCode；只恢复协调。',[
          n('映射提交成功','MySQL 已有短码','创建映射已经获得数据库提交确认。接下来的 Redis 失败不能撤销已提交记录。',src.create,'映射真实存在，不能声称创建未发生'),
          n('Redis 结果不确定','超时可能发生在执行后','replaceVersion 发生异常或响应丢失，Redis 可能已经执行，但应用无法确认完成。创建不执行与状态维护相同的三次同步重试。',src.cache,'返回专用部分完成错误','','risk'),
          n('503 携带短码','CREATE_CACHE_COORDINATION_UNCONFIRMED','向调用者返回已保存短码与专用错误码，不能重新 POST 来代替修复，重新 POST 可能创建另一条映射。',src.api,'保留 shortCode，交给受信任维护入口','DB committed / cache unconfirmed','risk'),
          n('独立恢复协调','不重新发号或写库','受信任维护代码按原短码确认映射存在，仅重试版本轮换。该入口不是公开 HTTP API，没有后台持久补偿任务。',src.create,'恢复针对同一映射；可安全重复协调','recoverCacheCoordination(shortCode)')
        ])
      ],
      notes:[['为什么采用独立发号表？','同一映射行的自增 ID 要等插入后才能拿到，无法在首次插入前计算短码。独立发号表让映射首次插入就有完整且非空的短码；两步间崩溃允许出现 ID 空洞。'],['短码碰撞是什么信号？','固定规则与有效 ID 范围内的映射应当唯一。短码主键冲突用于兜底编码、配置或迁移问题；即使重试成功，也应该排查首次冲突。不能把它解释成正常随机碰撞。'],['成功、失败与未知结果怎么区分？','201 确认映射提交和缓存协调。专用协调 503 确认数据库已提交；普通 500、连接断开或超时不能据此推断提交状态。']]
    },
    redirect: {
      label:'跳转与回源',icon:'↗',title:'让命中足够短，让回源有边界。',description:'先读缓存，未命中再合并加载；每个请求最终独立校验有效期，正常 GET 各自产生访问事件。',flowTitle:'跳转链路',flowSubtitle:'GET /s/{code} · HEAD 共用准入与校验，但不采集统计。',
      boundary:'合并发生在同一实例的“短码 + 缓存版本”范围内。默认等待共享加载 200ms，超时会重读缓存，仍未命中可独立回源；它不保证全局仅查一次，也不是整个 HTTP 请求的耗时上限。',
      scenarios:[
        scenario('缓存命中','不执行映射 SQL；每个请求独立做最终检查。',[format,rate,n('读取缓存结果','命中 REDIRECT 快照','Redis 返回带 generation 的缓存结果。命中不续期；有效缓存可避免映射 SQL，但仍要做业务到期判断，不能只依赖 Redis 的 TTL。',src.cache,'正常命中直接进入逐请求判定','shortlink:redirect:v2:{shortCode}'),finalCheck,collect,response]),
        scenario('缓存未命中 / 同码并发','加载 owner 二次读缓存；等待者共享结果。',[format,rate,n('按短码与版本合并','实例内 CompletableFuture','MISS 或 PLACEHOLDER 获得 generation 后，用 (shortCode, generation) 作为合并键。owner 再读一次缓存，避免刚完成的前一轮加载被重复执行；等待者复用正在进行的任务。',src.redirect,'等待者不占 SQL 许可','LoadKey(shortCode, generation)'),dbPermit,dbLoad,fill,finalCheck,collect,response]),
        scenario('负缓存 / 跳转拒绝','拒绝结果保持原因，不产生统计副作用。',[format,rate,n('命中拒绝结果','不存在 / 过期 / 禁用','不存在、已过期、已禁用使用不同 TTL。禁用快照仍带业务到期时间；返回前若发现已经过期，应优先返回 410。',src.cache,'默认 TTL 上限：30s / 300s / 15s','NOT_FOUND / EXPIRED / DISABLED'),n('返回明确业务原因','404 / 410 / 403','不存在返回 404 LINK_NOT_FOUND，已过期返回 410 LINK_EXPIRED，已禁用返回 403 LINK_DISABLED。拒绝不生成统计 Cookie 或访问事件。',src.redirect,'拒绝不计 PV / UV','Cache-Control: no-store','risk')]),
        scenario('Redis 不可用','限流阶段 fail-open；实际回源仍受保护。',[format,n('跳转限流降级','Redis 无法确认额度','限流故障允许跳转继续。故障不是额度耗尽，因此不能混成 429。缓存也不可用时，无法取得可信 generation，不能加入旧版本的加载任务。',src.rate,'继续受实际回源 SQL 并发上限约束'),dbPermit,dbLoad,n('跳过版本回填','没有可信缓存版本','独立读取 MySQL 后使用业务结果。没有可信版本就不回填缓存；数据库正常时仍可作出正常跳转决定。',src.redirect,'数据库异常不能缓存成“不存在”'),finalCheck,response])
      ],
      notes:[['四类缓存 TTL 如何设置？','正值和已过期默认上限各 300 秒，不存在 30 秒，已禁用 15 秒；实际 TTL 向下抖动 0%～10%。限时正值再取 min(抖动 TTL, 业务剩余有效时长)。缓存命中不续期。'],['为什么共享 SQL 不能共享统计事件？','多个 HTTP GET 可以复用同一份映射查询结果，但每个通过最终校验的请求都是独立访问。事件必须在共享任务外逐请求生成；否则高并发合并会少计 PV。'],['共享任务超时会怎样？','默认等待 200ms，超时重读一次缓存；仍无结果才独立查询，并继续申请 SQL 许可。不会取消其他请求正在等待的共享任务；任务成功或失败后从实例内映射移除。']]
    },
    consistency: {
      label:'状态与缓存一致性',icon:'⇄',title:'旧查询可以迟到，旧版本不能回填。',description:'先提交 MySQL，再轮换 Redis 版本；Lua 把版本比较与结果写入放在同一次原子操作里。',flowTitle:'状态变更与缓存协调',flowSubtitle:'PUT /api/links/{code}/enabled · 提交后轮换版本，阻断迟到回填。',
      boundary:'已经开始的请求仍可能使用旧快照作出跳转决定；版本协议保证的是缓存回填边界。即时可见依赖同一 Redis 主实例及已确认写入未丢失，不能把它描述为跨 MySQL / Redis 强一致。',
      scenarios:[
        scenario('正常启用 / 禁用','数据库只更新一次；过期和重复目标在事务内拒绝。',[auth,n('管理写限流','固定共享管理写桶','鉴权后共享容量 5、每秒补 1 个令牌。无额度 429；限流依赖不可用 503，并且不进入数据库业务。',src.rate,'先准入，再执行状态事务'),commit,rotate,n('返回状态变更完成','200 + enabled','版本轮换得到确认才返回 200。响应中的 enabled 是本次提交结果，其他后续状态操作仍可继续改变映射。',src.api,'重复目标不是幂等成功：返回 409','HTTP 200 · no-store')]),
        scenario('旧回源遇到并发禁用','旧结果不能在版本轮换后污染缓存。',[
          n('请求 A 持有 v1','读取旧的已启用映射','A 在缓存 miss 时取得 v1，随后从 MySQL 读到 enabled=true，但回填尚未发生。',src.redirect,'A 的查询结果归属于 v1','A: expectedGeneration = v1'),
          n('请求 B 提交禁用','数据库已变为 disabled','B 在行锁事务中完成检查与更新，提交 MySQL。A 手中的快照不会自动变成新状态。',src.state,'先 COMMIT，再操作缓存','B: COMMIT enabled = false'),
          n('请求 B 轮换到 v2','清除旧结果 / 新占位','Redis 原子替换缓存版本。此后新请求使用 v2 发起加载，从 MySQL 读取当前禁用状态。',src.cache,'旧 v1 回填资格失效','Redis: generation = v2'),
          n('请求 A 尝试回填','Lua 发现 v1 ≠ v2','Lua 原子比较当前版本与 A 预期版本，不一致即拒绝写入。不会先比较再由另一条命令写入，否则又会出现竞态。',src.cache,'拒绝污染新缓存；A 仍可能使用旧快照','storeIfVersion(v1) → false','risk'),
          n('后续请求读当前状态','回源禁用并缓存 v2 结果','新请求从 v2 占位回源到 MySQL 的已禁用状态，按 v2 条件回填负缓存并返回 403。',src.redirect,'本协议不撤销已经开始的旧请求','HTTP 403 LINK_DISABLED')
        ]),
        scenario('提交后协调重试耗尽','仅重试版本轮换，不再执行状态更新。',[commit,n('同步协调最多三次','首次 + 50ms / 100ms 后重试','每次只调用 replaceVersion。数据库状态已经提交，不再重复更新。延迟只是重试间隔，不构成整个请求的固定截止时间。',src.state,'三次仍未确认，报告部分完成','attempt 1 → +50ms → 2 → +100ms → 3'),n('专用 503 + 短码','LINK_STATE_CACHE_COORDINATION_UNCONFIRMED','数据库已提交，但缓存协调结果无法确认。重复 PUT 会因目标相同而得到 409，不能拿它当作修复入口。',src.api,'可能已经执行的 Redis 命令也属于“未确认”','','risk'),n('维护入口仅恢复协调','无后台持久补偿','按相同短码调用受信任维护方法，只轮换版本；不会把旧操作携带的 enabled 再写入 MySQL。进程退出或长期故障不保证自动恢复。',src.create,'recoverCacheCoordination 不是公开 HTTP API','recoverCacheCoordination(shortCode)')])
      ],
      notes:[['为什么仅删缓存不够？','A 读到旧映射，B 禁用并提交后删除缓存，A 再写回旧映射，就会让新请求继续命中旧状态。新版本占位和 Lua 条件回填专门阻断这个“迟到写入”。'],['为什么重试只轮换版本，不写状态快照？','若旧禁用操作的重试晚于一次新启用完成，再次写入旧 enabled=false 就会回退状态。轮换版本仅强制后续读当前 MySQL，不携带旧目标状态。'],['Redis 重启、切换或恢复旧快照后呢？','若确认过的版本写入丢失，原协议的前提已被破坏。按项目受控恢复文档隔离全部写入者，清理完整版本缓存命名空间，核验 MySQL 状态后恢复；不能仅删一个 Key 当作完成。']]
    },
    stats: {
      label:'异步访问统计',icon:'≋',title:'跳转先行，统计在有界后台链路完成。',description:'正常 GET 独立生成事件，经本地缓冲与 RabbitMQ 落入 MySQL。把发布确认、消费确认与幂等边界分开理解。',flowTitle:'异步统计链路',flowSubtitle:'采集在请求内冻结，网络发布与日志保存由后台完成。',
      boundary:'统计为 best-effort：缓冲满、发布失败、结果不确定、进程退出和队列淘汰都可能漏记。幂等约束防止同一事件重复入账，不代表端到端恰好一次或永不丢失。',
      scenarios:[
        scenario('正常采集与保存','从正常跳转决定开始；HTTP 只尝试本地交接。',[collect,buffer,publish,consume,persist,ack]),
        scenario('已提交，ACK 前断连后重投','复用同一 eventId，数据库唯一键决定是否入账。',[
          persist,n('ACK 前断连','MySQL 已提交，MQ 未确认','在日志提交后、消费 ACK 到达 broker 前发生断连或进程退出。broker 可能重新投递同一事件；不能通过更换 eventId 来消除冲突。',src.consumer,'重投保留 eventId 与冻结的发生时间','eventId = E1','risk'),
          n('同事件再次保存','仅 eventId 唯一键重复','重新尝试插入遇到 uq_visit_event，持久化明确返回已处理。不新增日志，不为重复消息增加 PV；其他 SQL 错误不能被吞为重复。',src.persistence,'E1 已保存 → 不再新增一行','uq_visit_event duplicate → DUPLICATE','async'),ack
        ]),
        scenario('本地缓冲满','本地交接失败，跳过该统计事件。',[collect,n('本地队列已满','offer 立即失败','队列容量有界且不等待空位。记录丢弃后继续原本的 302，不让 HTTP 线程接手发送，不新增无限等待队列。',src.producer,'丢失该统计事件；不进入 MQ 发布','local buffer full → skip event','risk'),response]),
        scenario('已交接，发布结果不确定','后台处理与 HTTP 响应独立推进。',[collect,buffer,publish,n('发布结果未知','超时不等于消息没送达','confirm 迟到、连接中断等可能让发布结果无法确认。只观测失败或不确定，不自动重发旧事件，不同步回退 MySQL；连接恢复用于后续事件。HTTP 可早于此刻就已返回 302。',src.producer,'不改变跳转；消息可能已经送达','unknown ≠ not delivered','risk')]),
        scenario('消费暂时失败 / 死信','每轮最多三次；最终拒绝不 requeue。',[consume,n('分类有限重试','首次 + 200ms / 500ms','暂时性连接、忙碌或提交不确定等失败最多三次尝试，每次重查最近 30 日窗口。保留原 eventId 与业务数据；格式、schema 和永久错误不走重试。',src.consumer,'三次是单轮上限，重投可开始新一轮','attempt 1 → +200ms → 2 → +500ms → 3','async'),n('拒绝并走死信路由','DLX → DLQ，不自动回流','耗尽或永久错误后抛出拒绝、不 requeue，按已配置 DLX 路由到 DLQ 供排查。普通 classic DLX 转移本身也不是无损承诺。',src.consumer,'先修故障，人工限量重放并保留 eventId','AmqpRejectAndDontRequeueException','risk')])
      ],
      notes:[['eventId 和匿名访客身份分别解决什么？','eventId 区分每次事件：刷新、客户端重试是新 GET，对应新事件；消费重试与消息重投沿用原 eventId。visitorHash 表示该短码范围内的匿名 Cookie 身份，用于 UV 去重。UV 不等于独立 IP 或真实自然人数。'],['AUTO ACK 的“自动”发生在什么时候？','Spring AMQP AUTO 在同步 listener 正常返回后 ACK。保存失败必须传播受控错误，不能先把落库扔到另一个线程就返回。RabbitMQ NONE / autoAck 在处理前就不再等待确认，与这里不同。'],['资源如何隔离？','本地缓冲默认 256、未确认尝试 32；统计专用 Hikari 池容量 4，写 / 查询 / 清理准入分别 2 / 1 / 1。主业务池和统计池共享同一 MySQL 硬件，池配额隔离不等于物理隔离。'],['为什么没有“双写计数表”这一步？','当前只追加访问日志，查询以已记录日志为依据。未来若增加计数汇总，需要让去重和汇总变更在同一数据库事务中完成，不能先去重再分开累加。']]
    },
    query: {
      label:'PV / UV 与明细查询',icon:'▥',title:'用同一份已记录日志，回答两个问题。',description:'统计范围内有多少次访问、多少匿名访客；需要追踪时，再通过绑定查询范围的游标读取明细。',flowTitle:'统计查询链路',flowSubtitle:'管理接口鉴权后读取统计专用池；失败不会伪造成零访问。',
      boundary:'最近 30 日是查询及在线清理窗口，默认查询最近 7 日。每日 UV 相加会高估跨日 UV；身份密钥版本切换也可能拆分访客身份。统计未记录不代表实际没有访问。',
      scenarios:[
        scenario('汇总与每日趋势','同一只读 REPEATABLE READ 快照内读取。',[auth,n('查询限流与日期校验','最近 30 个上海统计日','两统计接口共用固定管理查询桶，容量 5，每秒补 1。from/to 必须同时提供，且位于包含今天的最近 30 日窗口；不提供时默认最近 7 日。',src.query,'Asia/Shanghai 自然日，日期闭区间','from ≤ to ≤ today'),n('统计专用池与准入','池容量 4，查询准入 1','避免查询挤占核心映射池。查询繁忙或超时返回对应 503，不以 PV=0 / UV=0 伪装失败。',src.query,'STATS_BUSY / STATS_QUERY_TIMEOUT'),n('读取一致快照','映射、汇总、趋势、版本','同一只读 REPEATABLE READ 事务中读取映射、PV、范围 UV、逐日趋势与 identityVersions。只计算已保存日志，不查询 MQ 当作入账结果。',src.query,'异步积压期间结果可能滞后','PV = COUNT(*)'),n('返回范围与每日统计','跨日 UV 整体去重','范围 UV 对整个时间范围去重，不能对每日 UV 求和；返回身份版本用于解释密钥轮换带来的拆分。',src.query,'HEAD 无体；GET JSON + no-store','UV = COUNT(DISTINCT visitor identity)')]),
        scenario('游标分页访问明细','保持短码、日期范围与原始 nextCursor。',[auth,n('校验分页参数','limit 默认 20，范围 1～100','首屏无需 cursor；后续原样使用 nextCursor。游标绑定短码、from/to，不得改写或跨短码、跨日期范围复用。',src.query,'过滤范围变化后从首屏重新查询','GET /api/internal/links/{code}/visits'),n('按双字段倒序读取','occurredAt DESC，id DESC','发生时刻相同的记录再按日志行 ID 排序，使游标位置明确。跨页不是冻结整段浏览会话的全局快照，异步迟到记录可能进入已翻过的区域。',src.query,'每次查询有自己的读取快照','ORDER BY occurred_at DESC, id DESC'),n('返回记录与下一游标','最后一页 nextCursor = null','明细返回最小化元数据，不暴露原始完整 IP、完整 Referer 或访客摘要。保留原游标继续翻页，到 nextCursor=null 停止。',src.query,'避免深 OFFSET；不承诺跨页冻结所有迟到数据','{ items, nextCursor }')])
      ],
      notes:[['PV、UV 的准确口径','PV 是指定短码和时间范围内已记录的访问事件数。UV 对其中匿名身份去重。同一 Cookie 多次正常 GET：PV 增长，UV 通常不变；同事件重投：两者均不应增加。HEAD、跳转拒绝与创建请求不计入。'],['为什么趋势与汇总用同一快照？','异步消费在不断追加日志。分多次独立查询可能读到不同时间的数据，让汇总与趋势互相矛盾。同一只读快照能让这次响应中的多项结果保持同一读取视图。'],['日志何时清理？','包含当天在内的最近 30 个上海统计日为在线窗口。日志清理批次独立提交，带轮次预算，启动 / 每日 / 积压追赶独立调度；关闭新采集不停止历史消费、查询或清理。']]
    },
    protection: {
      label:'限流与故障保护',icon:'⊣',title:'额度不足与依赖失败，需要不同答案。',description:'令牌桶约束入口流量，SQL 并发许可约束实际回源；分别说明请求为何被拒绝，以及业务是否已经开始。',flowTitle:'请求准入与降级',flowSubtitle:'429 表示已确认额度不足，503 需要按错误码判断业务阶段。',
      boundary:'限流桶不是严格滚动窗口上限；后续业务失败不退令牌。Redis 重建或淘汰可能重置额度，Lua 原子性仅覆盖桶内操作。每实例 4 个跳转 SQL 不等于集群总上限，也不是独占保留的数据库连接。',
      scenarios:[
        scenario('额度已耗尽 → 429','拒绝发生在核心业务前，无统计副作用。',[n('识别请求组','创建 / 跳转 / 管理写 / 管理查','创建与跳转使用连接对端 IP；管理先鉴权再使用固定共享分组。忽略用户提供的 Forwarded / X-Forwarded-For，不按短码或令牌内容建桶。',src.rate,'管理鉴权优先；非法跳转短码提前 404'),n('令牌桶原子计算','各组使用各自容量与速率','Redis Lua 在一次执行中读取 TIME、补充令牌、判断是否获准、扣减并设置 TTL。默认参数可在下方展开查看；桶允许突发，不是严格滚动窗口上限。',src.rate,'后续业务失败不退令牌','Redis Lua token bucket'),n('Lua 确认无令牌','RATE_LIMIT_EXCEEDED','额度不足不扣成负数；计算等待预算，用向上取整秒的 Retry-After。等待结束也不保证下一次一定获得额度。',src.rate,'HTTP 429 + Retry-After + no-store','No Cookie / No VisitEvent','risk')]),
        scenario('创建 / 管理限流依赖异常','故障不是额度不足；不确定扣减不盲重试。',[n('Redis 失败或响应丢失','无法确认本次扣减','命令可能已经执行。普通超时不会无条件重新扣一次。创建、管理写与管理查询的限流依赖异常，按 fail-close 处理。',src.rate,'不能统一伪装成 429','','risk'),n('创建 / 管理 fail-close','503 RATE_LIMIT_UNAVAILABLE','业务尚未开始，不发号、不改映射、不读统计。该错误没有“已保存 shortCode”语义。',src.rate,'修复依赖后可按业务契约重新操作','before business execution','risk')]),
        scenario('跳转限流依赖异常','仅限流阶段放行，SQL 回源仍受保护。',[format,n('Redis 失败或响应丢失','无法确认跳转额度','跳转按 fail-open 策略继续处理。不能把无法确认令牌桶结果说成已确认超额，也不会盲目重复执行可能已经生效的扣减。',src.rate,'跳转限流故障继续主流程'),n('继续缓存 / 受限回源','缓存可用则优先命中','合法跳转继续原路径。若缓存也不能用，走受实例 SQL 许可保护的独立回源；并不放开数据库的并发上限。',src.redirect,'真正回源仍需 tryAcquire()','rate limiter open → SQL admission remains')]),
        scenario('实际回源繁忙 → 503','缓存命中和共享等待者不占实际 SQL 许可。',[n('映射需要实际 SQL','MISS、超时回源或缓存故障','缓存结果可用就不回源；同码同版本等待者先等待共享结果。只有走到实际 SQL 的请求才申请实例级许可。',src.redirect,'集中保护真实数据库查询'),dbPermit,n('许可已用尽','503 REDIRECT_LOAD_BUSY','默认已有 4 个实际映射查询在执行时，第 5 个实际查询立即被拒绝，不新增等待队列，也不会生成统计 Cookie 或事件。被拒绝请求没有取得许可；已有查询各自在完成或失败后释放自己的许可。',src.redirect,'与 429 额度拒绝、已提交协调 503 分开','tryAcquire() = false','risk')])
      ],
      notes:[['当前默认桶参数','创建：每对端 IP 容量 3 / 每 6 秒补 1；跳转 GET 与 HEAD：每对端 IP 跨码共享容量 60 / 每秒补 10；管理写和管理查分别固定共享容量 5 / 每秒补 1。两统计接口共用查询桶。'],['四种 503 不要混淆','RATE_LIMIT_UNAVAILABLE：业务前无法确认限流；REDIRECT_LOAD_BUSY：实际映射 SQL 容量繁忙；STATS_BUSY / STATS_QUERY_TIMEOUT：查询繁忙或超时；创建 / 状态协调专用 503：数据库已提交但 Redis 协调未确认，需要保留短码并独立恢复。'],['可观察性怎样辅助排障？','观测请求次数与耗时、限流拒绝、实际回源并发与拒绝、缓存状态、MQ 终局结果及积压、统计池状态。指标只用请求组和固定结果类别做标签，避免把 IP、短码、URL 或访客标识变成高基数标签。']]
    }
  };

  const overviewNodes = [
    n('客户端请求','GET /s/{code}','浏览器或 API 客户端访问完整短链接。短码保持大小写，最终目标 URL 来自服务端映射，不来自请求中的跳转参数。',src.api,'入口进入格式校验与限流','GET /s/pGeGLWSx'),
    rate,
    n('Redis 缓存','结果 + generation','先读版本缓存；命中则使用结果，MISS 或有限占位继续加载。缓存回填必须匹配读取时的版本。未命中路径在下方展示。',src.cache,'减少重复读取；MySQL 保持权威','REDIRECT / NOT_FOUND / EXPIRED / DISABLED'),
    response,
    n('合并与 SQL 准入','同码同版本共享加载','先在实例内按短码与版本共享加载；真正执行映射 SQL 再申请默认 4 个许可之一。不同实例可以各自加载。',src.redirect,'缓存可用时不进入这条支线','CompletableFuture + Semaphore'),
    dbLoad,fill,
    n('MySQL 访问日志','PV / UV 的查询依据','统计消费者写入的已记录事件是查询权威。异步落库后才对汇总、趋势及明细可见；无日志不证明无实际访问。',src.persistence,'读管理统计时使用独立统计池','eventId UNIQUE','async')
  ];
  const asyncNodes = [collect,buffer,publish,n('消费 → MySQL','校验、去重、提交后 ACK','同步消费校验事件并写入访问日志，eventId 唯一键处理重投。暂时错误每轮有限重试；耗尽或永久错误拒绝并走死信路由。',src.consumer,'保存后才确认消息；查询异步可见','eventId UNIQUE → COMMIT → ACK','async')];
  let current = 'overview', scenarioIndex = 0, step = 0, timer = null, overviewSelected = 2;
  let localEvents = new Map(), eventSerial = 0, lastEvent = null, raceStep = 0;
  const activeScenario = () => pages[current].scenarios?.[scenarioIndex];
  const nodeHtml = (node, index, selected, past = false, overview = false) => `<div class="node-wrap"><button type="button" class="node ${node.type} ${selected ? 'selected' : ''} ${past ? 'past' : ''}" data-node="${index}" aria-pressed="${selected}" aria-label="${escape(node.title)}：${escape(node.subtitle)}"><span class="node-kind">${overview ? (node.type === 'async' ? 'background' : 'request / data') : node.type === 'async' ? 'background' : node.type === 'risk' ? 'exception' : 'request'}${overview ? '' : `<span class="node-step">${String(index + 1).padStart(2,'0')}</span>`}</span><strong>${escape(node.title)}</strong><small>${escape(node.subtitle)}</small></button>${overview ? '<span class="connector" aria-hidden="true">→</span>' : ''}</div>`;
  function stop() { if (timer) clearInterval(timer); timer = null; $('play').textContent = '播放链路'; }
  function inspector(node, phase) {
    $('inspector').innerHTML = `<div><div class="inspector-heading"><span class="phase-label">${escape(phase)}</span><h3>${escape(node.title)}</h3></div><p>${escape(node.detail)}</p><div class="inspector-source">实现定位 · ${escape(node.source)}</div></div><div class="inspector-aside"><span class="aside-label">这一环节的关键结果</span><strong>${escape(node.result)}</strong>${node.code ? `<code>${escape(node.code)}</code>` : ''}</div>`;
    $('inspector').style.borderLeftColor = node.type === 'async' ? 'var(--teal)' : node.type === 'risk' ? 'var(--orange)' : 'var(--blue)';
  }
  function renderOverview() {
    const row = (label, sub, nodes, offset, async = false) => `<div class="lane ${async ? 'async' : ''}"><div class="lane-label ${async ? 'async' : ''}">${label}<small>${sub}</small></div><div class="nodes">${nodes.map((node,i) => nodeHtml(node, offset+i, offset+i === overviewSelected, false, true)).join('')}</div></div>`;
    $('diagram').innerHTML = row('跳转主线','缓存命中',overviewNodes.slice(0,4),0) + row('未命中支线','从缓存分流',overviewNodes.slice(4,7),4) + '<div class="return-note"><span class="return-line" aria-hidden="true"></span><span>查询结果回到主线，逐请求检查后作出跳转决定</span></div><div class="lane-separator">正常 GET 逐请求采集并尝试本地交接；302 不等待 MQ / 统计 SQL</div>' + row('统计分支','交接后异步',asyncNodes,8,true) + '<p class="diagram-caption">创建与状态维护也在同一应用中：MySQL 提交 → Redis 版本轮换。选择左侧链路查看完整时序。</p>';
    inspector(overviewSelected >= 8 ? asyncNodes[overviewSelected-8] : overviewNodes[overviewSelected], '全景节点');
  }
  function renderChain() {
    const nodes = activeScenario().nodes;
    $('diagram').innerHTML = `<div class="chain-grid">${nodes.map((node,i) => nodeHtml(node,i,i===step,i<step)).join('')}</div>`;
    inspector(nodes[step], `${step + 1} / ${nodes.length}`);
    $('step-count').textContent = `${String(step+1).padStart(2,'0')} / ${String(nodes.length).padStart(2,'0')}`;
    $('step-progress').style.width = `${(step+1)/nodes.length*100}%`;
    $('previous').disabled = step === 0;
    $('next').disabled = step === nodes.length-1;
    $('scenario-caption').textContent = activeScenario().caption;
  }
  function renderNotes() {
    $('notes').innerHTML = pages[current].notes.map(([title, body, jump]) => `<details><summary>${escape(title)}</summary><div class="note-body"><p>${escape(body)}</p>${jump ? `<button type="button" class="jump" data-jump="${jump}">查看这条链路 ↗</button>` : ''}</div></details>`).join('');
  }
  function render() {
    const page = pages[current];
    $('navigation').innerHTML = Object.entries(pages).map(([key,p]) => `<a href="#${key}" class="nav-item" ${key===current ? 'aria-current="page"' : ''}><span class="nav-icon" aria-hidden="true">${p.icon}</span>${p.label}</a>`).join('');
    $('crumb').textContent = page.label;
    $('page-title').textContent = page.title;
    $('page-description').textContent = page.description;
    $('flow-title').textContent = page.flowTitle;
    $('flow-subtitle').textContent = page.flowSubtitle;
    $('boundary-text').textContent = page.boundary;
    $('notes-title').textContent = current === 'overview' ? '把关键设计串起来' : '继续追问这一条链路';
    $('scenario-toolbar').hidden = current === 'overview';
    $('playback').hidden = current === 'overview';
    if (current === 'overview') renderOverview();
    else {
      $('scenario-select').innerHTML = page.scenarios.map((s,i) => `<option value="${i}">${escape(s.label)}</option>`).join('');
      $('scenario-select').value = String(scenarioIndex);
      renderChain();
    }
    renderNotes(); renderSandbox();
    document.title = `ShortLink · ${page.label}`;
  }
  function navigate(key) {
    if (!pages[key]) key = 'overview';
    stop(); current = key; scenarioIndex = 0; step = 0; render();
    window.scrollTo({top:0,behavior:'auto'});
  }
  $('diagram').addEventListener('click', event => {
    const target = event.target.closest('[data-node]'); if (!target) return;
    stop();
    if (current === 'overview') { overviewSelected = Number(target.dataset.node); renderOverview(); }
    else { step = Number(target.dataset.node); renderChain(); }
  });
  $('notes').addEventListener('click', event => { const target = event.target.closest('[data-jump]'); if (target) { location.hash = target.dataset.jump; $('main').scrollIntoView({behavior:'auto'}); } });
  $('scenario-select').addEventListener('change', () => { stop(); scenarioIndex = Number($('scenario-select').value); step = 0; renderChain(); });
  $('previous').addEventListener('click', () => { stop(); step = Math.max(0,step-1); renderChain(); });
  $('next').addEventListener('click', () => { stop(); step = Math.min(activeScenario().nodes.length-1,step+1); renderChain(); });
  $('reset').addEventListener('click', () => { stop(); step = 0; renderChain(); });
  $('play').addEventListener('click', () => {
    if (timer) { stop(); return; }
    if (step === activeScenario().nodes.length-1) { step=0; renderChain(); }
    $('play').textContent = '暂停';
    timer = setInterval(() => { step++; renderChain(); if (step === activeScenario().nodes.length-1) stop(); }, 2200);
  });
  window.addEventListener('hashchange', () => { if (location.hash !== '#main') navigate(location.hash.slice(1)); });
  document.addEventListener('visibilitychange', () => { if (document.hidden) stop(); });

  function renderSandbox() {
    $('sandbox').hidden = !['stats','query','consistency'].includes(current);
    if (['stats','query'].includes(current)) {
      $('sandbox').innerHTML = `<h3 id="sandbox-title">动手验证：事件幂等与访客去重</h3><p class="sandbox-intro">同一短码、同一统计日的本地示例。这里只模拟已成功写入日志的事件，不连接真实服务。</p><div class="sandbox-row"><button type="button" data-sim="a">访客 A 再访问一次</button><button type="button" data-sim="b">访客 B 再访问一次</button><button type="button" data-sim="replay" ${lastEvent ? '' : 'disabled'}>重投最后一个事件</button><button type="button" data-sim="reset">清空示例</button></div><div class="sim-results"><span>已记录 PV<b id="sim-pv">${localEvents.size}</b></span><span>匿名访客 UV<b id="sim-uv">${new Set([...localEvents.values()].map(e=>e.visitor)).size}</b></span><span>同事件重投不新增日志</span></div><div id="sim-log" class="sim-log" aria-live="polite">${lastEvent ? `最后事件 ${lastEvent.id} · visitor ${lastEvent.visitor}` : '先让一个访客访问，再试试重投同一事件。'}</div>`;
    } else if (current === 'consistency') {
      const events = [
        ['A 取得 v1，准备读取 MySQL', raceStep >= 1], ['A 读到 enabled=true，暂缓回填', raceStep >= 2], ['A 用 v1 回填 → Lua 拒绝', raceStep >= 5]
      ];
      $('sandbox').innerHTML = `<h3 id="sandbox-title">动手推演：旧查询与禁用操作交错</h3><p class="sandbox-intro">v1 / v2 是便于阅读的示例名称；实际 generation 为随机版本令牌。</p><div class="race-track"><div class="race-column"><strong>请求 A · 映射回源</strong>${events.map(([label,done],i)=>`<div class="race-event ${done ? i===2 ? 'blocked' : '' : 'pending'}">${escape(label)}</div>`).join('')}</div><div class="race-column"><strong>请求 B · 禁用映射</strong><div class="race-event ${raceStep>=3 ? 'new' : 'pending'}">B 提交 enabled=false</div><div class="race-event ${raceStep>=4 ? 'new' : 'pending'}">B 将 Redis 版本轮换到 v2</div></div></div><div class="race-state" aria-live="polite"><span>MySQL：<b>${raceStep>=3 ? 'disabled' : 'enabled'}</b></span><span>Redis generation：<b>${raceStep>=4 ? 'v2' : 'v1'}</b></span><span>旧回填：<b>${raceStep>=5 ? '已拒绝' : '尚未尝试'}</b></span></div><div class="race-controls"><button type="button" class="control-button primary" data-race="next" ${raceStep===5 ? 'disabled' : ''}>推进下一步（${raceStep} / 5）</button><button type="button" class="text-button" data-race="reset">重新推演</button></div><p class="sandbox-intro" style="margin-bottom:0">旧请求 A 仍可能使用已经读到的启用快照；这里演示的是阻止旧快照污染后续缓存。</p>`;
    }
  }
  $('sandbox').addEventListener('click', event => {
    const sim = event.target.closest('[data-sim]'); const race = event.target.closest('[data-race]');
    if (race) { raceStep = race.dataset.race === 'reset' ? 0 : Math.min(5,raceStep+1); renderSandbox(); }
    if (!sim) return;
    const action = sim.dataset.sim;
    if (action === 'reset') { localEvents = new Map(); eventSerial=0; lastEvent=null; renderSandbox(); return; }
    if (action === 'replay') {
      if (!lastEvent) return;
      renderSandbox(); $('sim-log').textContent = `${lastEvent.id} 已存在 → 唯一约束拦住重复写入；PV、UV 均不变。`; return;
    }
    const visitor = action === 'a' ? 'A' : 'B'; lastEvent={id:`E${++eventSerial}`,visitor}; localEvents.set(lastEvent.id,lastEvent);
    renderSandbox(); $('sim-log').textContent = `已写入 ${lastEvent.id} · visitor ${visitor} → 新事件计 1 PV；UV 按匿名访客去重。`;
  });

  const apiRows = [
    ['POST','/api/links','originalUrl；可选 validMinutes','201；新短码、短链接与过期时间'],
    ['GET / HEAD','/s/{code}','4～8 位字母数字短码','302 / 404 / 410 / 403；HEAD 不采集'],
    ['PUT','/api/links/{code}/enabled','管理头；JSON enabled','200；重复目标 409；过期 410'],
    ['GET / HEAD','/api/internal/links/{code}/stats','管理头；可选成对 from / to','200；范围 PV / UV、逐日趋势、身份版本'],
    ['GET / HEAD','/api/internal/links/{code}/visits','管理头；日期、limit、cursor','200；明细与 nextCursor']
  ];
  const references = [
    ['领域口径：访问事件、PV、UV 与统计窗口','CONTEXT.md'],
    ['当前架构：职责划分、资源边界、版本协议','docs/架构与原理/architecture.md'],
    ['API：响应码、默认限流参数、查询契约','docs/入门与使用/api.md'],
    ['异步统计：发布、消费、有限重试与失败边界','docs/架构与原理/async-visit-statistics.md'],
    ['短码：固定双射与 Base62 长度边界','docs/adr/0002-permuted-auto-id-base62.md'],
    ['缓存：差异化负缓存、版本与请求合并','docs/adr/0004-negative-cache-for-redirects.md'],
    ['状态：事务提交后有限重试','docs/adr/0005-enabled-state-api.md'],
    ['代码：创建、跳转与状态维护','src/main/java/com/example/shortlink/service/'],
    ['代码：逐请求采集、异步发布、消费及幂等保存','src/main/java/com/example/shortlink/api/stats/VisitCollection.java · stats/messaging/ · stats/persistence/']
  ];
  function openDialog(kind) {
    stop(); $('dialog-title').textContent = kind === 'api' ? '接口与返回边界' : '这张地图的阅读依据';
    $('dialog-content').innerHTML = kind === 'api'
      ? `<p>管理头为 X-Internal-Token。以下是静态契约索引，不会调用接口。</p><div class="table-wrap"><table><thead><tr><th>方法</th><th>路径</th><th>输入</th><th>主要结果</th></tr></thead><tbody>${apiRows.map(row=>`<tr>${row.map((cell,i)=>`<td>${i===1 ? `<code>${escape(cell)}</code>` : escape(cell)}</td>`).join('')}</tr>`).join('')}</tbody></table></div><p>429 为额度不足；503 按错误码区分限流依赖、实际 SQL 繁忙、统计查询和已提交协调未确认。所有错误均 no-store；HEAD 的成功与错误均无响应体。</p><p>内部 recoverCacheCoordination(shortCode) 是受信任应用上下文方法，没有公开 HTTP 路由。</p>`
      : `<p>按当前代码与领域文档梳理。历史 ADR 保留决策过程，当前行为优先对照实现和最新架构说明；页面没有运行生产压测，也不展示伪造监控数据。</p><ul class="reference-list">${references.map(([label,path])=>`<li>${escape(label)}<code>${escape(path)}</code></li>`).join('')}</ul><p>链路节点中的实现定位默认相对于 src/main/java/com/example/shortlink/；docs 路径相对于仓库根目录。</p>`;
    if (!$('reference-dialog').open) $('reference-dialog').showModal();
  }
  $('source-button').addEventListener('click',()=>openDialog('source'));
  $('api-button').addEventListener('click',()=>openDialog('api'));
  $('close-dialog').addEventListener('click',()=>$('reference-dialog').close());
  $('reference-dialog').addEventListener('click',event=>{ if(event.target === $('reference-dialog')) {const r=event.target.getBoundingClientRect(); if(event.clientX<r.left||event.clientX>r.right||event.clientY<r.top||event.clientY>r.bottom) event.target.close();} });
  function printCatalog() {
    document.querySelector('.print-catalog')?.remove();
    const catalog=document.createElement('section'); catalog.className='print-catalog';
    catalog.innerHTML='<h2>全部链路与异常场景</h2>'+Object.values(pages).filter(p=>p.scenarios).map(p=>`<article><h2>${escape(p.label)}</h2><p>${escape(p.description)}</p>${p.scenarios.map(s=>`<h3>${escape(s.label)}</h3><ol>${s.nodes.map(node=>`<li><strong>${escape(node.title)}：</strong>${escape(node.detail)}<br>关键结果：${escape(node.result)}</li>`).join('')}</ol>`).join('')}<p><strong>保证范围：</strong>${escape(p.boundary)}</p></article>`).join('');
    $('main').append(catalog);
  }
  window.addEventListener('beforeprint',()=>{stop();printCatalog();});
  $('print-button').addEventListener('click',()=>{stop();window.print();});
  navigate(location.hash.slice(1));
})();
