# 上传突发优化及消费者并发 2 / 4 对照（20261004-195236）

北京时间：2026-10-04T19:52:36.770585+08:00 至 2026-10-04T20:39:18.733441+08:00。

## 结论

本轮建议容量环境采用消费者并发 **4**：两轮 20 张突发、十分钟持续上传和混合阶段全部达标。

最终容量环境保持消费者并发 4、解码并发 2、等待位置 20、等待上限 5 秒、JVM 堆占比 60%。明确 IPv4 的最终十分钟复测 201/201 成功，P95 11.35 秒。中间补测的两次建连超时完整保留在后文，不并入成功轮次。

大图补测内存峰值仍约 965 MiB，1 GiB 容器余量有限；并发 4 的吞吐建议不等于已验证生产内存和真实模型配额。

- 并发 2，20 张/分钟持续十分钟：201 次上传，接收 201，观察到成功 84，超时 117，成功率 41.79%，成功任务 P95 169.21 秒，待处理采样峰值 81。
- 并发 2 的混合阶段：50 VU 查询与每分钟 20 张上传同时运行两分钟，查询 5950 次，成功率 100.00%，查询 P95 15.56 ms。
- 并发 4，20 张/分钟持续十分钟：201 次上传，接收 201，观察到成功 201，超时 0，成功率 100.00%，成功任务 P95 11.24 秒，待处理采样峰值 4。
- 并发 4 的混合阶段：50 VU 查询与每分钟 20 张上传同时运行两分钟，查询 5800 次，成功率 100.00%，查询 P95 53.60 ms。

## 已完成的实现

- 图片解码并发维持 2，增加最多 20 个等待位置、公平的限时等待，本轮等待上限 5000 ms；超时、异常和线程中断均释放占用，中断状态保留。
- Spring multipart 临时文件改用独立磁盘目录 `/app/upload-temp`，与持久图片目录分开。解析前最多预留 22 个上传请求，每个按最大请求 12 MiB 加 64 KiB 余量预留，应用临时空间预算 1 GiB，并检查磁盘可用空间。预算是单实例的应用准入限制，不是文件系统配额；现有文件和预留保守叠加。
- 正常请求结束由 multipart 解析器清理临时文件；启动时仅清理专用目录中超过一小时的 `upload_*.tmp`，不清理持久餐食图片。
- Nginx 在接收请求体之前限制同时处理最多 20 个 multipart 请求，读取接口不占名额；20×12 MiB=240 MiB，适配默认 256 MiB 缓冲。调整缓冲容量时需同时核对该上限。
- 入口繁忙返回 `429 / UPLOAD_BUSY` 和 `Retry-After: 1`；普通用户配额仍是原限流错误。解码阶段的拒绝独立记录为 `UPLOAD_BUSY`，不再归类为普通执行失败。
- 前端只对明确的 `429 / UPLOAD_BUSY` 自动重试最多两次，遵循 Retry-After、加入随机延迟并保留原图片、目标和幂等键；取消等待会停止后续提交。网络不确定结果、5xx、鉴权错误和普通配额限流不自动重交。

限流键和空键行为依据 [Nginx 官方文档](https://nginx.org/en/docs/http/ngx_http_limit_conn_module.html)；multipart 落盘目录及阈值依据 [Spring Boot 3.3 文档](https://docs.spring.io/spring-boot/3.3/api/java/org/springframework/boot/autoconfigure/web/servlet/MultipartProperties.html)。

## 测试设置与证据范围

- 40 个本轮独立账号，每账号 1,000 条由真实 API 种子复制的合成餐食记录，共 40,000 条；每阶段结束等待后台任务排空。
- 两组使用同一后端镜像、1.5 CPU、1 GiB 容器内存；解码 2、等待 20、限时 5 秒保持一致，只切换消费者并发 2 / 4。
- 入口、边界、过载、大分辨率用例使用每次调用延迟 0.05 秒的模型替身。消费者对照使用每次调用延迟 5 秒的模型替身；常规任务通常包含两次调用。每组先预热。
- 标准样本约 2.5 MB、1254×1254 合成 PNG。10 MiB 边界样本通过尾部补零构造，验证字节边界；4096×4096 JPEG 由合成原图放大，验证解码工作量。混合阶段轮换两张 PNG 与大分辨率 JPEG。
- k6 不自动重试上传，拒绝与超时均计入结果。两分钟混合场景用于比较并行读写，长期稳定性由十分钟持续上传场景补充，不能把两分钟混合结果称为长期混合容量。
- 真实模型未执行：本机没有真实模型凭据和配额配置。压测端与服务端共用本机，结果不代表真实供应商容量、识别质量或线上最大吞吐。

## 回归验证

- 后端单元测试 213 项，失败 0，错误 0，跳过 1；数据库与 RabbitMQ 指定集成测试 16 项通过。5 秒参数调整后补跑 42 项上传、安全与任务相关回归。
- 前端 19 项测试通过，生产构建成功；实际执行 k6 的脚本验证器 9 项通过，包括过载允许拒绝时仍不得掩盖已接收任务失败。
- 后端完整验证命令：`mvn -q -Pintegration "-Dit.test=MealAnalysisDatabaseIntegrationTest,MealAnalysisRabbitDatabaseIntegrationTest" verify`；前端：`npm test`、`npm run build`；k6 验证器：`python loadtests/verify_analysis_capacity.py`。

## 各阶段结果

| 场景 | 尝试/接收/成功 | 429（入口繁忙） | 后台失败/超时/不确定/中断 | 接收上传 P95 ms | 成功 E2E P95 s | 丢弃迭代 | 验收 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| c2-entry-20 | 20/20/20 | 0（0） | 0/0/0/0 | 4034.65 | 5.46 | 0 | 通过 |
| c2-boundary-20 | 20/20/20 | 0（0） | 0/0/0/0 | 6425.20 | 8.44 | 0 | 通过 |
| c2-overload-50 | 50/20/20 | 30（30） | 0/0/0/0 | 3271.50 | 4.29 | 0 | 通过 |
| c2-large-10 | 10/10/10 | 0（0） | 0/0/0/0 | 5720.50 | 6.73 | 0 | 通过 |
| c2-burst-20-r1 | 20/20/20 | 0（0） | 0/0/0/0 | 2594.50 | 102.25 | 0 | 未通过 |
| c2-burst-20-r2 | 20/20/20 | 0（0） | 0/0/0/0 | 2496.20 | 102.06 | 0 | 未通过 |
| c2-arrival-20-long | 201/201/84 | 0（0） | 0/117/0/0 | 178.00 | 169.21 | 0 | 未通过 |
| c2-mixed-20 | 40/40/40 | 0（0） | 0/0/0/0 | 760.75 | 84.87 | 0 | 未通过 |
| c4-entry-20 | 20/20/20 | 0（0） | 0/0/0/0 | 4585.20 | 5.60 | 0 | 通过 |
| c4-boundary-20 | 20/20/20 | 0（0） | 0/0/0/0 | 6378.80 | 7.39 | 0 | 通过 |
| c4-overload-50 | 50/20/20 | 30（30） | 0/0/0/0 | 4163.25 | 5.18 | 0 | 通过 |
| c4-large-10 | 10/10/10 | 0（0） | 0/0/0/0 | 5912.90 | 6.92 | 0 | 通过 |
| c4-burst-20-r1 | 20/20/20 | 0（0） | 0/0/0/0 | 3468.90 | 52.86 | 0 | 通过 |
| c4-burst-20-r2 | 20/20/20 | 0（0） | 0/0/0/0 | 2779.00 | 53.13 | 0 | 通过 |
| c4-arrival-20-long | 201/201/201 | 0（0） | 0/0/0/0 | 172.00 | 11.24 | 0 | 通过 |
| c4-mixed-20 | 41/41/41 | 0（0） | 0/0/0/0 | 1158.00 | 12.27 | 0 | 通过 |

正常场景验收：成功率 ≥99%、成功任务 P95 <60 秒、无超时、不确定结果、中断或丢弃迭代。`overload-50` 与 `large-10` 是明确的饱和测试，允许最多 80% 拒绝，已接收任务仍须成功且不能超时；它们通过不表示全部请求被接收。接收上传 P95 仅包含有效提交响应，成功 E2E P95 排除失败样本，必须结合全部数量判断。

| 场景 | 上传 HTTP 状态分布 | 待处理采样峰值 | 排空后待处理/未发布 Outbox |
| --- | --- | ---: | --- |
| c2-entry-20 | `{"202": 20}` | 0 | 0/0 |
| c2-boundary-20 | `{"202": 20}` | - | 0/0 |
| c2-overload-50 | `{"429": 30, "202": 20}` | - | 0/0 |
| c2-large-10 | `{"202": 10}` | 10 | 0/0 |
| c2-burst-20-r1 | `{"202": 20}` | 20 | 0/0 |
| c2-burst-20-r2 | `{"202": 20}` | 20 | 0/0 |
| c2-arrival-20-long | `{"202": 201}` | 81 | 0/0 |
| c2-mixed-20 | `{"202": 40}` | 17 | 0/0 |
| c4-entry-20 | `{"202": 20}` | - | 0/0 |
| c4-boundary-20 | `{"202": 20}` | 0 | 0/0 |
| c4-overload-50 | `{"429": 30, "202": 20}` | - | 0/0 |
| c4-large-10 | `{"202": 10}` | - | 0/0 |
| c4-burst-20-r1 | `{"202": 20}` | 16 | 0/0 |
| c4-burst-20-r2 | `{"202": 20}` | 20 | 0/0 |
| c4-arrival-20-long | `{"202": 201}` | 4 | 0/0 |
| c4-mixed-20 | `{"202": 41}` | 4 | 0/0 |

超时表示 k6 观察上限已到，后台可能最终完成；数据库累计 FAILED 也可能包含入口拒绝，不能混作已接收任务的执行失败。采样峰值包含该阶段的排空等待，短暂峰值可能漏采。

失败阈值：

- `c2-burst-20-r1`：`analysis_e2e_ms: p(95)<60000`。
- `c2-burst-20-r2`：`analysis_e2e_ms: p(95)<60000`。
- `c2-arrival-20-long`：`analysis_timed_out: count==0；analysis_e2e_ms: p(95)<60000；analysis_success_rate: rate>=0.99`。
- `c2-mixed-20`：`analysis_e2e_ms: p(95)<60000`。

## 资源采样

| 并发 | 后端 CPU 峰值 | 后端内存峰值 MiB | 前端内存峰值 MiB |
| ---: | ---: | ---: | ---: |
| 2 | 140.87% | 965.10 | 36.30 |
| 4 | 152.35% | 1023.00 | 219.50 |

共 332 次采样，其中 3 次包含错误；每次采样后等待 5 秒，每三次读取队列。CPU 100% 表示一核，不是 CPU 配额使用率。准备、切换与清理阶段未计入上表。

初次对照 JVM 参数：`-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError`。延迟达标不等于内存余量充足；最终默认 JVM 堆占比为 60%，为线程、元空间、图像处理和其他容器内存预留空间。补测结果单独列出。

## 收尾、复现与资料

- 清理：Only the newly created 40 test accounts and their associated data were deleted。
- 模型恢复：True。
- 完成后容量环境保留消费者并发 4；生产默认并发仍为 2，真实模型需结合供应商限额验证。

```powershell
python loadtests/run-burst-comparison.py
python loadtests/collect-burst-health.py loadtests/results/20261004-195236
python loadtests/write-burst-comparison-report.py loadtests/results/20261004-195236
```

- [本轮参数、镜像、验证与清理记录](../results/20261004-195236/manifest.json)
- [容器与队列采样](../results/20261004-195236/resources.jsonl)
- [仅扩充本轮账号的 SQL](../results/20261004-195236/seed-records.sql)
- [实测脚本及配置快照](../results/20261004-195236/scripts)
- [上轮优化报告](k6-optimization-20261004-173543.md)；新一轮修改了后端实现，旧数据仅作背景，本轮并发对照以同一新镜像为准。
- [3 秒等待校准的失败记录](k6-burst-comparison-20261004-193440.md)：20 张中 19 张接收成功，1 张受控拒绝；随后调整为 5 秒，未删除或混入该轮结果。
- `seed`：[汇总](../results/20261004-195236/seed.json)、[日志](../results/20261004-195236/seed.log)、[时间序列](../results/20261004-195236/seed.timeseries.jsonl)，退出码 0。
- `c2-warmup`：[汇总](../results/20261004-195236/c2-warmup.json)、[日志](../results/20261004-195236/c2-warmup.log)、[时间序列](../results/20261004-195236/c2-warmup.timeseries.jsonl)，退出码 0。
- `c2-entry-20`：[汇总](../results/20261004-195236/c2-entry-20.json)、[日志](../results/20261004-195236/c2-entry-20.log)、[时间序列](../results/20261004-195236/c2-entry-20.timeseries.jsonl)，退出码 0。
- `c2-boundary-20`：[汇总](../results/20261004-195236/c2-boundary-20.json)、[日志](../results/20261004-195236/c2-boundary-20.log)、[时间序列](../results/20261004-195236/c2-boundary-20.timeseries.jsonl)，退出码 0。
- `c2-overload-50`：[汇总](../results/20261004-195236/c2-overload-50.json)、[日志](../results/20261004-195236/c2-overload-50.log)、[时间序列](../results/20261004-195236/c2-overload-50.timeseries.jsonl)，退出码 0。
- `c2-large-10`：[汇总](../results/20261004-195236/c2-large-10.json)、[日志](../results/20261004-195236/c2-large-10.log)、[时间序列](../results/20261004-195236/c2-large-10.timeseries.jsonl)，退出码 0。
- `c2-burst-20-r1`：[汇总](../results/20261004-195236/c2-burst-20-r1.json)、[日志](../results/20261004-195236/c2-burst-20-r1.log)、[时间序列](../results/20261004-195236/c2-burst-20-r1.timeseries.jsonl)，退出码 99。
- `c2-burst-20-r2`：[汇总](../results/20261004-195236/c2-burst-20-r2.json)、[日志](../results/20261004-195236/c2-burst-20-r2.log)、[时间序列](../results/20261004-195236/c2-burst-20-r2.timeseries.jsonl)，退出码 99。
- `c2-arrival-20-long`：[汇总](../results/20261004-195236/c2-arrival-20-long.json)、[日志](../results/20261004-195236/c2-arrival-20-long.log)、[时间序列](../results/20261004-195236/c2-arrival-20-long.timeseries.jsonl)，退出码 99。
- `c2-mixed-20`：[汇总](../results/20261004-195236/c2-mixed-20.json)、[日志](../results/20261004-195236/c2-mixed-20.log)、[时间序列](../results/20261004-195236/c2-mixed-20.timeseries.jsonl)，退出码 99。
- `c4-warmup`：[汇总](../results/20261004-195236/c4-warmup.json)、[日志](../results/20261004-195236/c4-warmup.log)、[时间序列](../results/20261004-195236/c4-warmup.timeseries.jsonl)，退出码 0。
- `c4-entry-20`：[汇总](../results/20261004-195236/c4-entry-20.json)、[日志](../results/20261004-195236/c4-entry-20.log)、[时间序列](../results/20261004-195236/c4-entry-20.timeseries.jsonl)，退出码 0。
- `c4-boundary-20`：[汇总](../results/20261004-195236/c4-boundary-20.json)、[日志](../results/20261004-195236/c4-boundary-20.log)、[时间序列](../results/20261004-195236/c4-boundary-20.timeseries.jsonl)，退出码 0。
- `c4-overload-50`：[汇总](../results/20261004-195236/c4-overload-50.json)、[日志](../results/20261004-195236/c4-overload-50.log)、[时间序列](../results/20261004-195236/c4-overload-50.timeseries.jsonl)，退出码 0。
- `c4-large-10`：[汇总](../results/20261004-195236/c4-large-10.json)、[日志](../results/20261004-195236/c4-large-10.log)、[时间序列](../results/20261004-195236/c4-large-10.timeseries.jsonl)，退出码 0。
- `c4-burst-20-r1`：[汇总](../results/20261004-195236/c4-burst-20-r1.json)、[日志](../results/20261004-195236/c4-burst-20-r1.log)、[时间序列](../results/20261004-195236/c4-burst-20-r1.timeseries.jsonl)，退出码 0。
- `c4-burst-20-r2`：[汇总](../results/20261004-195236/c4-burst-20-r2.json)、[日志](../results/20261004-195236/c4-burst-20-r2.log)、[时间序列](../results/20261004-195236/c4-burst-20-r2.timeseries.jsonl)，退出码 0。
- `c4-arrival-20-long`：[汇总](../results/20261004-195236/c4-arrival-20-long.json)、[日志](../results/20261004-195236/c4-arrival-20-long.log)、[时间序列](../results/20261004-195236/c4-arrival-20-long.timeseries.jsonl)，退出码 0。
- `c4-mixed-20`：[汇总](../results/20261004-195236/c4-mixed-20.json)、[日志](../results/20261004-195236/c4-mixed-20.log)、[时间序列](../results/20261004-195236/c4-mixed-20.timeseries.jsonl)，退出码 0。
- [最终健康与清理验证](../results/20261004-195236/final-health.json)。
- [上传临时存储验证](../results/20261004-195236/storage.json)。

最终验证：六个容器均健康，无 OOM、无重启；本轮账号、餐食与任务均清理完成，数据库待处理和未发布 Outbox 为 0，RabbitMQ Ready/Unacked 为 0。
上传临时目录最终文件数 0、总字节 0，使用独立磁盘卷。

## 60% JVM 堆占比的内存补测

北京时间：2026-10-04T20:44:34.560169+08:00 至 2026-10-04T21:08:06.446520+08:00。保持 1.5 CPU、1 GiB 容器和相同业务镜像；两档均验证入口、字节边界、过载、大分辨率与一次慢模型突发。并发 4 再运行十分钟持续上传及两分钟混合负载。并发 2 没有重复十分钟持续上传，原对照未删除或合并。

| 场景 | 尝试/接收/成功 | 429 | 后台失败/超时/不确定/中断 | 上传 P95 ms | 成功 E2E P95 s | 验收 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| c2-entry-20 | 20/20/20 | 0 | 0/0/0/0 | 3605.30 | 4.61 | 通过 |
| c2-boundary-20 | 20/20/20 | 0 | 0/0/0/0 | 5959.65 | 7.15 | 通过 |
| c2-overload-50 | 50/20/20 | 30 | 0/0/0/0 | 3466.20 | 4.66 | 通过 |
| c2-large-10 | 10/10/10 | 0 | 0/0/0/0 | 5682.60 | 6.69 | 通过 |
| c2-burst-20-r1 | 20/20/20 | 0 | 0/0/0/0 | 2599.70 | 102.19 | 未通过 |
| c4-entry-20 | 20/20/20 | 0 | 0/0/0/0 | 4922.65 | 6.08 | 通过 |
| c4-boundary-20 | 20/20/20 | 0 | 0/0/0/0 | 6799.95 | 7.81 | 通过 |
| c4-overload-50 | 50/20/20 | 30 | 0/0/0/0 | 3677.10 | 4.69 | 通过 |
| c4-large-10 | 10/10/10 | 0 | 0/0/0/0 | 5299.65 | 6.31 | 通过 |
| c4-burst-20-r1 | 20/20/20 | 0 | 0/0/0/0 | 2495.60 | 51.82 | 通过 |
| c4-arrival-20-long | 201/199/199 | 0 | 0/0/2/0 | 181.00 | 11.24 | 未通过 |
| c4-mixed-20 | 41/41/41 | 0 | 0/0/0/0 | 815.00 | 11.88 | 通过 |

补测仍有未达标阶段：`c4-arrival-20-long`。其中持续上传 201 次尝试、199 次确认成功、2 次客户端 TCP 建连超时（HTTP 状态 0、Windows errno 10060）计为结果不确定，不从结果中剔除。它不是 HTTP 500；尚不能据此确定连接故障根因。

| 并发 | 后端内存采样峰值 MiB | 1 GiB 限额的采样余量 MiB |
| ---: | ---: | ---: |
| 2 | 954.50 | 69.50 |
| 4 | 965.30 | 58.70 |

降低堆占比后，大图补测中的容器内存余量仍偏小；生产应结合真实图片分布评估更大的容器内存预算或进一步减少解码分配。本轮没有验证更大的内存规格，不能把无 OOM 解释为 1 GiB 已有充分余量。

内存补测清理验证：六个容器健康、无 OOM 与重启；本轮账号数据、队列、Outbox 和 multipart 临时文件均已清空。峰值来自间隔采样，不能当作更大图片分布或更长运行的硬上界。

- [补测参数、运行配置与清理](../results/20261004-204434/manifest.json)
- [补测资源采样](../results/20261004-204434/resources.jsonl)
- [补测最终健康](../results/20261004-204434/final-health.json)
- [补测临时存储](../results/20261004-204434/storage.json)
- 复现：`python loadtests/run-burst-comparison.py --memory-check`，再执行本轮的健康收集；生成报告时增加 `--memory-evidence <补测目录>`。
- `seed`：[汇总](../results/20261004-204434/seed.json)、[日志](../results/20261004-204434/seed.log)、[时间序列](../results/20261004-204434/seed.timeseries.jsonl)。
- `c2-warmup`：[汇总](../results/20261004-204434/c2-warmup.json)、[日志](../results/20261004-204434/c2-warmup.log)、[时间序列](../results/20261004-204434/c2-warmup.timeseries.jsonl)。
- `c2-entry-20`：[汇总](../results/20261004-204434/c2-entry-20.json)、[日志](../results/20261004-204434/c2-entry-20.log)、[时间序列](../results/20261004-204434/c2-entry-20.timeseries.jsonl)。
- `c2-boundary-20`：[汇总](../results/20261004-204434/c2-boundary-20.json)、[日志](../results/20261004-204434/c2-boundary-20.log)、[时间序列](../results/20261004-204434/c2-boundary-20.timeseries.jsonl)。
- `c2-overload-50`：[汇总](../results/20261004-204434/c2-overload-50.json)、[日志](../results/20261004-204434/c2-overload-50.log)、[时间序列](../results/20261004-204434/c2-overload-50.timeseries.jsonl)。
- `c2-large-10`：[汇总](../results/20261004-204434/c2-large-10.json)、[日志](../results/20261004-204434/c2-large-10.log)、[时间序列](../results/20261004-204434/c2-large-10.timeseries.jsonl)。
- `c2-burst-20-r1`：[汇总](../results/20261004-204434/c2-burst-20-r1.json)、[日志](../results/20261004-204434/c2-burst-20-r1.log)、[时间序列](../results/20261004-204434/c2-burst-20-r1.timeseries.jsonl)。
- `c4-warmup`：[汇总](../results/20261004-204434/c4-warmup.json)、[日志](../results/20261004-204434/c4-warmup.log)、[时间序列](../results/20261004-204434/c4-warmup.timeseries.jsonl)。
- `c4-entry-20`：[汇总](../results/20261004-204434/c4-entry-20.json)、[日志](../results/20261004-204434/c4-entry-20.log)、[时间序列](../results/20261004-204434/c4-entry-20.timeseries.jsonl)。
- `c4-boundary-20`：[汇总](../results/20261004-204434/c4-boundary-20.json)、[日志](../results/20261004-204434/c4-boundary-20.log)、[时间序列](../results/20261004-204434/c4-boundary-20.timeseries.jsonl)。
- `c4-overload-50`：[汇总](../results/20261004-204434/c4-overload-50.json)、[日志](../results/20261004-204434/c4-overload-50.log)、[时间序列](../results/20261004-204434/c4-overload-50.timeseries.jsonl)。
- `c4-large-10`：[汇总](../results/20261004-204434/c4-large-10.json)、[日志](../results/20261004-204434/c4-large-10.log)、[时间序列](../results/20261004-204434/c4-large-10.timeseries.jsonl)。
- `c4-burst-20-r1`：[汇总](../results/20261004-204434/c4-burst-20-r1.json)、[日志](../results/20261004-204434/c4-burst-20-r1.log)、[时间序列](../results/20261004-204434/c4-burst-20-r1.timeseries.jsonl)。
- `c4-arrival-20-long`：[汇总](../results/20261004-204434/c4-arrival-20-long.json)、[日志](../results/20261004-204434/c4-arrival-20-long.log)、[时间序列](../results/20261004-204434/c4-arrival-20-long.timeseries.jsonl)。
- `c4-mixed-20`：[汇总](../results/20261004-204434/c4-mixed-20.json)、[日志](../results/20261004-204434/c4-mixed-20.log)、[时间序列](../results/20261004-204434/c4-mixed-20.timeseries.jsonl)。
- [两次建连超时的时间序列证据](../results/20261004-204434/connection-diagnostics.json)。

## 明确 IPv4 地址的持续与混合复测

北京时间：2026-10-04T21:09:06.681143+08:00 至 2026-10-04T21:23:34.239859+08:00。使用 `http://127.0.0.1:18080`，保持相同业务镜像、消费者并发 4、1.5 CPU、1 GiB 容器及 60% JVM 堆占比；新建独立的 40 个账号和 40,000 条记录。未重复入口和大图阶段，也未覆盖或合并前一轮失败数据。

| 场景 | 尝试/接收/成功 | 超时/结果不确定/中断 | 成功 E2E P95 s | 验收 |
| --- | ---: | ---: | ---: | --- |
| c4-arrival-20-long | 201/201/201 | 0/0/0 | 11.35 | 通过 |
| c4-mixed-20 | 41/41/41 | 0/0/0 | 12.06 | 通过 |

混合查询 5950 次，成功率 100.00%，查询 P95 27.31 ms。

IPv4 复测通过，原有两次建连超时仍作为失败证据保留；一次复测不能证明 localhost/DNS 就是根因。最终六个容器健康，本轮数据和临时文件清理完成，队列及 Outbox 为 0。

- [IPv4 复测参数及清理](../results/20261004-210906/manifest.json)
- [IPv4 复测资源采样](../results/20261004-210906/resources.jsonl)
- [IPv4 复测最终健康](../results/20261004-210906/final-health.json)
- [IPv4 复测临时存储](../results/20261004-210906/storage.json)
- 复现：`python loadtests/run-burst-comparison.py --continuous-only`，执行健康收集后生成报告时增加 `--confirmation-evidence <IPv4 复测目录>`。
- `seed`：[汇总](../results/20261004-210906/seed.json)、[日志](../results/20261004-210906/seed.log)、[时间序列](../results/20261004-210906/seed.timeseries.jsonl)。
- `c4-warmup`：[汇总](../results/20261004-210906/c4-warmup.json)、[日志](../results/20261004-210906/c4-warmup.log)、[时间序列](../results/20261004-210906/c4-warmup.timeseries.jsonl)。
- `c4-arrival-20-long`：[汇总](../results/20261004-210906/c4-arrival-20-long.json)、[日志](../results/20261004-210906/c4-arrival-20-long.log)、[时间序列](../results/20261004-210906/c4-arrival-20-long.timeseries.jsonl)。
- `c4-mixed-20`：[汇总](../results/20261004-210906/c4-mixed-20.json)、[日志](../results/20261004-210906/c4-mixed-20.log)、[时间序列](../results/20261004-210906/c4-mixed-20.timeseries.jsonl)。

账号文件含 JWT 与清理密码，禁止分享。原始证据在 Git 忽略目录，分享 Markdown 时需要另行保存证据。
