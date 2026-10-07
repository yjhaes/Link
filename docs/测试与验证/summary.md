# 测试证据运行报告

- 状态：`sampled`
- 运行标识：`20261006-210259-c11b6774`
- 源码提交：`af4876d872598c0de15151e0181182f3df9077d0`
- 工作树干净：`False`
- 环境：Windows-11-10.0.22621-SP0
- JVM 数量与端口：见 `processes*.json`、[`benchmark-processes.jsonl`](benchmark-processes.jsonl) 和 [`capacity-processes.jsonl`](capacity-processes.jsonl)

## 负载样本

| 配置 | 方法 | JVM | 工作负载 | 到达率/s | 阶段/轮次 | 实际发起/s | 302 | 429 | 503 | SQL 映射查询 | 成功 p50/p95/max ms | 调度延迟 p95 ms |
| --- | --- | ---: | --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- | ---: |
| capacity | HEAD | 1 | hit | 160 | head-hit-1jvm-explore-g0 | 160.0 | 9600 | 0 | 0 | 0 | 6.4138/6.7978/38.2586 | 0.24159999884432182 |
| capacity | HEAD | 1 | hit | 320 | head-hit-1jvm-explore-g0 | 320.0 | 19200 | 0 | 0 | 0 | 8.868/78.5942/244.9147 | 66.48259999928996 |
| capacity | HEAD | 1 | hit | 320 | head-hit-1jvm-explore-g1 | 320.0 | 19200 | 0 | 0 | 0 | 9.9394/80.4394/367.2028 | 53.921599999739556 |
| capacity | HEAD | 1 | hit | 320 | head-hit-1jvm-explore-g2 | 320.0 | 19198 | 0 | 0 | 0 | 12.7419/69.6059/377.3416 | 3.95570000182488 |
| capacity | HEAD | 1 | hit | 640 | head-hit-1jvm-explore-g0 | 511.3 | 30934 | 0 | 0 | 4 | 349.7698/974.4378/1939.2901 | 768.6213999986649 |
| capacity | HEAD | 1 | hit | 640 | head-hit-1jvm-explore-g1 | 560.617 | 32896 | 0 | 222 | 63 | 494.7969/1704.1991/5966.3505 | 1536.3534000025538 |
| capacity | HEAD | 1 | hit | 320 | head-hit-1jvm-formal-r1-g0 | 319.992 | 38381 | 0 | 0 | 7 | 12.9713/443.705/1939.888 | 119.63049999758368 |
| capacity | HEAD | 1 | hit | 160 | head-hit-1jvm-formal-r1-g0 | 160.0 | 19199 | 0 | 0 | 1 | 6.4621/55.7408/370.9042 | 67.4250000010943 |
| capacity | HEAD | 1 | hit | 80 | head-hit-1jvm-formal-r1-g0 | 80.0 | 9600 | 0 | 0 | 0 | 5.0724/14.19/141.3434 | 7.719200002611615 |
| capacity | HEAD | 1 | hit | 80 | head-hit-1jvm-formal-r2-g0 | 80.0 | 9600 | 0 | 0 | 0 | 8.4998/30.9634/171.83 | 4.715700000815559 |
| capacity | HEAD | 1 | hit | 80 | head-hit-1jvm-formal-r3-g0 | 80.0 | 9600 | 0 | 0 | 0 | 5.4423/10.4489/80.5012 | 1.0819999988598283 |
| capacity | HEAD | 1 | hit | 80 | head-hit-1jvm-sustain-10m-g0 | 80.0 | 48000 | 0 | 0 | 0 | 5.5523/11.3895/132.2535 | 0.5541999998968095 |
| capacity | HEAD | 2 | hit | 160 | head-hit-2jvm-explore-g0 | 160.0 | 9600 | 0 | 0 | 0 | 6.6222/28.6882/169.3033 | 14.361800000187941 |
| capacity | HEAD | 2 | hit | 160 | head-hit-2jvm-explore-g1 | 160.0 | 9600 | 0 | 0 | 0 | 6.4858/51.4165/202.8311 | 84.95849999962957 |
| capacity | HEAD | 2 | hit | 160 | head-hit-2jvm-explore-g2 | 160.0 | 9600 | 0 | 0 | 0 | 6.4981/43.7785/219.8446 | 75.46709999951418 |
| capacity | HEAD | 2 | hit | 160 | head-hit-2jvm-explore-g3 | 159.867 | 9600 | 0 | 0 | 0 | 6.478/36.3147/193.3312 | 19.607999998697778 |
| capacity | HEAD | 2 | hit | 160 | head-hit-2jvm-explore-g4 | 160.0 | 9600 | 0 | 0 | 0 | 6.4612/31.7572/194.7944 | 16.569400002481416 |
| capacity | HEAD | 2 | hit | 160 | head-hit-2jvm-explore-g5 | 160.0 | 9600 | 0 | 0 | 0 | 6.4536/39.3581/200.4014 | 58.47409999842057 |
| capacity | HEAD | 2 | hit | 160 | head-hit-2jvm-explore-g6 | 160.0 | 9600 | 0 | 0 | 0 | 6.4623/49.1916/204.0648 | 69.21869999860064 |
| capacity | HEAD | 1 | distinct-miss | 160 | head-distinct-miss-1jvm-explore-g0 | 160.0 | 9100 | 0 | 500 | 9039 | 7.3545/45.7595/201.8748 | 82.43260000017472 |
| capacity | HEAD | 1 | distinct-miss | 160 | head-distinct-miss-1jvm-explore-g1 | 160.0 | 9163 | 0 | 437 | 9107 | 7.0473/43.8041/200.4641 | 72.68430000112858 |
| capacity | HEAD | 1 | distinct-miss | 160 | head-distinct-miss-1jvm-explore-g2 | 160.0 | 9152 | 0 | 448 | 9103 | 7.3812/44.3215/213.1341 | 68.93349999882048 |
| capacity | HEAD | 1 | distinct-miss | 160 | head-distinct-miss-1jvm-explore-g3 | 159.867 | 9211 | 0 | 389 | 9141 | 7.2727/46.2412/194.9632 | 59.41959999836399 |
| capacity | HEAD | 1 | distinct-miss | 160 | head-distinct-miss-1jvm-explore-g4 | 160.0 | 9215 | 0 | 385 | 9135 | 7.3469/40.9626/256.7521 | 51.320999999006744 |
| capacity | HEAD | 1 | distinct-miss | 160 | head-distinct-miss-1jvm-explore-g5 | 160.0 | 9258 | 0 | 342 | 9184 | 7.3197/40.4973/188.3877 | 51.38299999816809 |
| capacity | HEAD | 1 | distinct-miss | 160 | head-distinct-miss-1jvm-explore-g6 | 160.0 | 9241 | 0 | 359 | 9176 | 7.2629/39.1767/184.5662 | 54.19650000112597 |
| capacity | HEAD | 2 | distinct-miss | 160 | head-distinct-miss-2jvm-explore-g0 | 160.0 | 9404 | 0 | 196 | 9350 | 8.3438/43.7251/189.0021 | 50.46970000330475 |
| capacity | HEAD | 2 | distinct-miss | 160 | head-distinct-miss-2jvm-explore-g1 | 160.0 | 9397 | 0 | 203 | 9342 | 6.9149/46.9891/187.987 | 81.73299999907613 |
| capacity | HEAD | 2 | distinct-miss | 160 | head-distinct-miss-2jvm-explore-g2 | 160.0 | 9354 | 0 | 246 | 9269 | 6.8737/46.9698/193.9812 | 86.74539999992703 |
| capacity | HEAD | 2 | distinct-miss | 160 | head-distinct-miss-2jvm-explore-g3 | 159.933 | 9401 | 0 | 195 | 9307 | 6.9281/48.5264/194.7548 | 83.56450000064797 |
| capacity | HEAD | 2 | distinct-miss | 160 | head-distinct-miss-2jvm-explore-g4 | 160.0 | 9440 | 0 | 160 | 9338 | 7.4241/47.0375/225.4889 | 64.02589999925112 |
| capacity | HEAD | 2 | distinct-miss | 160 | head-distinct-miss-2jvm-explore-g5 | 160.0 | 9457 | 0 | 143 | 9382 | 7.5432/46.1249/210.003 | 61.37939999825903 |
| capacity | HEAD | 2 | distinct-miss | 160 | head-distinct-miss-2jvm-explore-g6 | 160.0 | 9438 | 0 | 162 | 9347 | 7.6698/44.9009/213.0313 | 48.31899999771849 |
| capacity | GET | 1 | hit | 160 | get-hit-1jvm-explore-g0 | 160.0 | 9600 | 0 | 0 | 0 | 6.5265/38.6429/201.5364 | 44.2908000004536 |
| capacity | GET | 1 | hit | 160 | get-hit-1jvm-explore-g1 | 160.0 | 9600 | 0 | 0 | 0 | 6.5112/39.083/200.3683 | 53.40029999933904 |
| capacity | GET | 1 | hit | 160 | get-hit-1jvm-explore-g2 | 160.0 | 9600 | 0 | 0 | 0 | 6.5133/45.1867/270.6612 | 65.9176999979536 |
| capacity | GET | 1 | hit | 160 | get-hit-1jvm-explore-g3 | 160.0 | 9600 | 0 | 0 | 0 | 6.5204/47.7284/208.8565 | 47.534400000586174 |
| capacity | GET | 1 | hit | 160 | get-hit-1jvm-explore-g4 | 160.0 | 9599 | 0 | 0 | 0 | 6.5215/43.6893/219.6967 | 39.23589999976684 |
| capacity | GET | 1 | hit | 160 | get-hit-1jvm-explore-g5 | 160.0 | 9600 | 0 | 0 | 0 | 6.5126/44.4831/204.1028 | 39.89710000314517 |
| capacity | GET | 1 | hit | 160 | get-hit-1jvm-explore-g6 | 160.0 | 9600 | 0 | 0 | 0 | 6.5158/47.3715/219.0123 | 44.55439999946975 |
| capacity | GET | 2 | hit | 160 | get-hit-2jvm-explore-g0 | 160.0 | 9600 | 0 | 0 | 0 | 6.6585/54.3784/335.7285 | 53.924599997117184 |
| capacity | GET | 2 | hit | 160 | get-hit-2jvm-explore-g1 | 160.0 | 9600 | 0 | 0 | 0 | 6.515/46.6121/194.3286 | 62.09579999995185 |
| capacity | GET | 2 | hit | 160 | get-hit-2jvm-explore-g2 | 160.0 | 9600 | 0 | 0 | 0 | 6.5212/50.0422/214.9176 | 59.39090000174474 |
| capacity | GET | 2 | hit | 160 | get-hit-2jvm-explore-g3 | 160.0 | 9600 | 0 | 0 | 0 | 6.4779/6.8845/32.2552 | 0.1768000001902692 |
| capacity | GET | 2 | hit | 320 | get-hit-2jvm-explore-g0 | 320.0 | 19199 | 0 | 0 | 0 | 15.6272/151.4623/528.542 | 81.4477000021725 |
| capacity | GET | 2 | hit | 160 | get-hit-2jvm-formal-r1-g0 | 160.0 | 19200 | 0 | 0 | 0 | 6.5088/43.2714/325.3977 | 48.33210000288091 |
| capacity | GET | 2 | hit | 80 | get-hit-2jvm-formal-r1-g0 | 80.0 | 9600 | 0 | 0 | 0 | 4.492/10.4573/135.5867 | 0.225800002226606 |
| capacity | GET | 2 | hit | 80 | get-hit-2jvm-formal-r2-g0 | 80.0 | 9600 | 0 | 0 | 0 | 4.3913/5.9552/76.559 | 0.09830000271904282 |
| capacity | GET | 2 | hit | 80 | get-hit-2jvm-formal-r3-g0 | 80.0 | 9600 | 0 | 0 | 0 | 4.5503/8.9711/75.4232 | 0.21580000247922726 |
| capacity | GET | 2 | hit | 80 | get-hit-2jvm-sustain-10m-g0 | 80.0 | 48000 | 0 | 0 | 0 | 4.5014/9.4322/157.687 | 0.1899999988381751 |
| capacity | GET | 1 | distinct-miss | 160 | get-distinct-miss-1jvm-explore-g0 | 160.0 | 9421 | 0 | 179 | 9395 | 8.5511/20.2222/100.4606 | 1.9141000011586584 |
| capacity | GET | 1 | distinct-miss | 80 | get-distinct-miss-1jvm-backoff-g0 | 80.0 | 4696 | 0 | 104 | 4686 | 6.5881/17.5259/150.3588 | 37.87860000011278 |
| capacity | GET | 1 | distinct-miss | 80 | get-distinct-miss-1jvm-backoff-g1 | 80.0 | 4799 | 0 | 1 | 4793 | 6.3593/10.3694/57.8571 | 0.15640000128769316 |
| capacity | GET | 1 | distinct-miss | 120 | get-distinct-miss-1jvm-bisect-g0 | 119.933 | 7132 | 0 | 68 | 7117 | 8.5534/15.7327/84.2869 | 0.41383333154954016 |
| capacity | GET | 1 | distinct-miss | 100 | get-distinct-miss-1jvm-bisect-g0 | 100.0 | 5876 | 0 | 124 | 5857 | 6.233/20.7681/107.3289 | 11.277400000835769 |
| capacity | GET | 1 | distinct-miss | 100 | get-distinct-miss-1jvm-bisect-g1 | 100.0 | 5927 | 0 | 73 | 5916 | 6.5666/16.1045/104.621 | 2.4088999998639338 |
| capacity | GET | 1 | distinct-miss | 90 | get-distinct-miss-1jvm-bisect-g0 | 90.0 | 5374 | 0 | 26 | 5363 | 6.6095/15.9888/92.4238 | 0.7978111098054796 |
| capacity | GET | 1 | distinct-miss | 85 | get-distinct-miss-1jvm-bisect-g0 | 85.0 | 5090 | 0 | 10 | 5083 | 6.1564/12.1427/86.9375 | 0.16751176008256152 |
| capacity | GET | 1 | distinct-miss | 85 | get-distinct-miss-1jvm-upper-r1-g0 | 85.0 | 10199 | 0 | 1 | 10182 | 6.1595/10.7249/71.1253 | 0.12481176599976607 |
| capacity | GET | 1 | distinct-miss | 85 | get-distinct-miss-1jvm-upper-r2-g0 | 85.0 | 10199 | 0 | 1 | 10189 | 6.0434/8.9614/60.8228 | 0.1051941180776339 |
| capacity | GET | 1 | distinct-miss | 85 | get-distinct-miss-1jvm-upper-r3-g0 | 85.0 | 10200 | 0 | 0 | 10187 | 6.4646/11.9885/66.6387 | 0.1354882333544083 |
| capacity | GET | 1 | distinct-miss | 80 | get-distinct-miss-1jvm-formal-r1-g0 | 80.0 | 9600 | 0 | 0 | 9593 | 6.5004/10.345/59.7505 | 0.130899999930989 |
| capacity | GET | 1 | distinct-miss | 80 | get-distinct-miss-1jvm-formal-r2-g0 | 80.0 | 9600 | 0 | 0 | 9594 | 6.2927/9.6663/63.2117 | 0.11010000162059441 |
| capacity | GET | 1 | distinct-miss | 80 | get-distinct-miss-1jvm-formal-r3-g0 | 80.0 | 9600 | 0 | 0 | 9592 | 6.3841/10.2159/63.0328 | 0.13649999891640618 |
| capacity | GET | 1 | distinct-miss | 80 | get-distinct-miss-1jvm-sustain-10m-g0 | 80.0 | 47993 | 0 | 7 | 47940 | 6.3464/12.2328/81.7483 | 0.15079999866429716 |
| capacity | GET | 2 | distinct-miss | 160 | get-distinct-miss-2jvm-explore-g0 | 160.0 | 9558 | 0 | 42 | 9531 | 9.3675/22.8307/101.1581 | 0.48919999971985817 |
| capacity | GET | 2 | distinct-miss | 80 | get-distinct-miss-2jvm-backoff-g0 | 80.0 | 4777 | 0 | 23 | 4764 | 6.3142/21.1748/138.6212 | 13.148900001397124 |
| capacity | GET | 2 | distinct-miss | 80 | get-distinct-miss-2jvm-backoff-g1 | 80.0 | 4800 | 0 | 0 | 4793 | 6.4825/11.4206/62.9746 | 0.13480000052368268 |
| capacity | GET | 2 | distinct-miss | 120 | get-distinct-miss-2jvm-bisect-g0 | 120.0 | 7192 | 0 | 8 | 7172 | 8.5752/17.0083/84.0985 | 0.31239999952958897 |
| capacity | GET | 2 | distinct-miss | 100 | get-distinct-miss-2jvm-bisect-g0 | 100.0 | 6000 | 0 | 0 | 6000 | 7.1093/10.3429/20.5515 | 0.11499999891384505 |
| capacity | GET | 2 | distinct-miss | 110 | get-distinct-miss-2jvm-bisect-g0 | 110.0 | 6600 | 0 | 0 | 6600 | 7.1287/9.5319/18.6231 | 0.10942727385554463 |
| capacity | GET | 2 | distinct-miss | 110 | get-distinct-miss-2jvm-upper-r1-g0 | 110.0 | 13171 | 0 | 29 | 13156 | 9.3358/10.8005/119.8037 | 0.16377272550016642 |
| capacity | GET | 2 | distinct-miss | 110 | get-distinct-miss-2jvm-upper-r2-g0 | 110.0 | 13147 | 0 | 53 | 13108 | 9.3244/22.3129/114.9302 | 0.6498000002466142 |
| capacity | GET | 2 | distinct-miss | 110 | get-distinct-miss-2jvm-upper-r3-g0 | 110.0 | 13139 | 0 | 61 | 13098 | 9.3356/21.4378/137.5606 | 1.861318181909155 |
| capacity | GET | 2 | distinct-miss | 100 | get-distinct-miss-2jvm-formal-r1-g0 | 100.0 | 11956 | 0 | 44 | 11918 | 7.6404/22.3774/124.8018 | 0.3260000012232922 |
| capacity | GET | 2 | distinct-miss | 100 | get-distinct-miss-2jvm-formal-r2-g0 | 100.0 | 11987 | 0 | 13 | 11959 | 7.6991/20.1353/101.4819 | 0.3938999980164226 |
| capacity | GET | 2 | distinct-miss | 100 | get-distinct-miss-2jvm-formal-r3-g0 | 100.0 | 11996 | 0 | 4 | 11991 | 6.3738/10.3555/97.9838 | 0.12690000221482478 |
| capacity | GET | 2 | distinct-miss | 80 | get-distinct-miss-2jvm-formal-r1-g0 | 80.0 | 9600 | 0 | 0 | 9597 | 6.319/11.5196/71.6992 | 0.15640000128769316 |
| capacity | GET | 2 | distinct-miss | 80 | get-distinct-miss-2jvm-formal-r2-g0 | 80.0 | 9600 | 0 | 0 | 9592 | 6.2423/11.6343/75.2063 | 0.1274999995075632 |
| capacity | GET | 2 | distinct-miss | 80 | get-distinct-miss-2jvm-formal-r3-g0 | 80.0 | 9600 | 0 | 0 | 9590 | 6.3975/10.1035/63.6085 | 0.16279999908874743 |
| capacity | GET | 2 | distinct-miss | 80 | get-distinct-miss-2jvm-sustain-10m-g0 | 80.0 | 47998 | 0 | 2 | 47955 | 6.3837/12.8058/112.9906 | 0.14080000255489722 |

## 最大持续吞吐量

容量门槛：每个样本成功率至少 99.9%、成功响应 p95 不超过 200ms；GET 还需访问事件全部发布、消费、写库，且队列斜率不超过 0.1 条/秒（高于 100/s 时按目标的 0.1% 计算）。

| 方法 | 工作负载 | JVM | 最高已验证请求/s | 成功吞吐/s | 发布事件/s | 消费事件/s | 写库事件/s | 队列斜率/s | 相邻上界请求/s | 结果 | 平台 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| HEAD | hit | 1 | 80 | 80.0 | — | — | — | — | — | verified-lower-bound | False |
| HEAD | hit | 2 | — | — | — | — | — | — | — | inconclusive | — |
| HEAD | distinct-miss | 1 | — | — | — | — | — | — | — | inconclusive | — |
| HEAD | distinct-miss | 2 | — | — | — | — | — | — | — | inconclusive | — |
| GET | hit | 1 | — | — | — | — | — | — | — | inconclusive | — |
| GET | hit | 2 | 80 | 80.0 | 80.0 | 80.0 | 80.0 | -0.005 | — | verified-lower-bound | False |
| GET | distinct-miss | 1 | 80 | 80.0 | 80.0 | 80.0 | 80.0 | 0.008 | — | verified-lower-bound | False |
| GET | distinct-miss | 2 | 80 | 80.0 | 80.0 | 80.0 | 80.0 | -0.007 | — | verified-lower-bound | False |

探索样本从 160/s 起步并翻倍；首档未达标时逐半回退，再在边界内二分至速率间距缩至下界的 10% 以内。正式候选需三轮全通过并完成十分钟持续验证；确认最大值还要求相邻上界三轮都在未受发生器限制时失败，且边界不超过下界的 10%。若重复结果不一致、边界过宽或发生器先受限，只报告已验证下界或未能确定。

边界复核（2026-10-07）：本轮没有确认出最大值。GET 不同短码回源单 JVM 的85 req/s上界三轮结果不一致（一轮MQ发布/落库失败、两轮通过），所以只报告80 req/s已验证下界。双 JVM的110 req/s上界虽三轮都失败，但与80 req/s下界相差37.5%，且100 req/s正式重复结果不一致；按10%边界标准同样只报告80 req/s已验证下界。HEAD 缓存命中单 JVM和GET命中双 JVM也验证到80 req/s；HEAD缓存命中双 JVM、两组HEAD回源及GET命中单 JVM受发生器调度限制，未得到有效容量下界。逐组记录见 `capacity-results.json`，修正规则与变更见 `capacity-reporting-correction.json`。

## 文件

- `manifest.json`：源码、运行时、资源边界和脱敏配置。
- `scenarios.jsonl`、`samples.jsonl`、`preflight.jsonl`：断言与每次样本汇总。
- `requests-*.csv.gz`：逐请求状态、到达偏差、响应延迟及目标 JVM。
- `resources-*.jsonl`：负载发生器和 JVM CPU/内存、连接池、回源在途量及 GET 消息队列/发布/消费/写库指标。
- `capacity-results.json`、`capacity-probes.jsonl`：每组最大持续吞吐、搜索边界与探索记录。
- `logs/`：容器命令摘要及脱敏 JVM 日志。
