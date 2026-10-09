# 大图内存优化结果与暂停交接

测试日期：2026-10-04（北京时间）。文档补齐日期：2026-10-05。

**状态：按用户要求暂停。已完成内存基线、图片处理优化和 50% 堆配置的三轮复测；45% 堆配置仅完成第一轮。消费者 4/6 对比、长时间混合负载及故障恢复测试尚未执行。** 本次补写文档没有启动新压测。

## 已完成结果

三组均使用隔离的 `mealcheck-capacity` 环境、消费者 4、后端 1.5 CPU / 1 GiB。大图为相同的 4096×4096 合成餐食 JPEG，大小 2,001,845 字节；每轮同时提交 10 张。模型使用本地桩，每次调用延迟 0.05 秒，不涉及真实模型费用。

| 配置 | 已完成大图轮数 | 成功/提交 | 各轮成功任务 E2E p95 | 容器内存采样峰值 |
| --- | ---: | ---: | --- | ---: |
| 原实现，堆上限 60% | 3 | 30/30 | 7.194 / 6.397 / 6.172 秒 | 1022.13 MiB |
| 优化实现，堆上限 50% | 3 | 30/30 | 7.119 / 6.164 / 6.924 秒 | 884.03 MiB |
| 优化实现，堆上限 45% | **1，未完成** | 10/10 | 7.216 秒 | 789.30 MiB，部分过程 |

已完成的大图阶段均无失败、拒绝、超时或未确认结果，k6 阈值检查通过。每组另有 40 次账号初始化分析，均通过，未计入上表的大图数量。

50% 配置的采样峰值比基线低 138.10 MiB，约 13.5%；相对 1 GiB 上限还剩约 140 MiB（13.7%）。它适合作为下一轮验证起点，**尚不能据此认定生产内存充足或配置最优**。

### 结论的适用范围

- 这里比较的是“代码优化＋堆配置”的组合，不能把全部内存下降归因于代码。基线还复用了已运行的 JVM，优化组重建了容器，预热状态不同。
- 内存数字取自定期采样的 cgroup `memory.current`，包含进程和容器文件缓存，不能当作堆使用量，也不是连续监测得到的绝对峰值。短暂峰值可能未被采到。
- 45% 组只完成一轮，不能直接与三轮结果判定优劣。需要重新完整测试，不能把未来新进程的两轮结果拼成同一次三轮测试。
- 本轮使用快速模型桩，只验证大图处理和短时内存表现。它没有复测每次模型调用 5 秒情况下的持续消费能力，更不代表真实模型服务的容量。
- 运行清单继承了 `recordsPerAccount: 1000`、持续速率等通用默认元数据，但本轮 `mode=memory` **没有执行 1000 条/用户的数据扩充或持续速率测试**。实际每组创建 40 个账号，各初始化一条记录，然后执行大图阶段；应以 `phases` 和对应 k6 结果为准。

## 内存诊断

基线大图负载使已提交堆内存扩展到约 595.5 MiB，非堆使用约 202–204 MiB；容器总内存接近 1 GiB。最后一次采样的 cgroup `memory.events` 显示 `max=94`、`oom=0`、`oom_kill=0`：已经触及内存限制并发生回收压力，尚未被 OOM 杀死。

三轮基线大图后的 GC 存活数据约为 193.70、181.44、138.75 MiB，没有逐轮增长。当前证据更支持大图分配、堆扩张及进程其他内存共同造成高占用；**短测试不能排除长期泄漏**。当时 JVM 使用 Serial GC，本轮没有切换 GC 算法。

50% 配置的已提交堆上限约 494.94 MiB，三轮后 GC 存活数据约 175.01 MiB。暂未进一步细分线程栈、原生分配等占用，也没有完成长时间 GC 对比。

## 已保存的改动与验证

1. [ImageUploadValidationService.java](E:/mealcheck-fullstack/backend/src/main/java/com/example/mealcheck/service/ImageUploadValidationService.java)：用有字节上限的输入流交给 ImageIO，减少整份压缩图片的内存复制；继续检查实际文件字节数，包括图片结束标记后的尾部内容。尺寸无需调整的 RGB/BGR 图像复用已有像素；需要新画布时使用三字节 BGR，并保留白底透明度处理和 JPEG 重新编码。
2. [ImageUploadValidationServiceTest.java](E:/mealcheck-fullstack/backend/src/test/java/com/example/mealcheck/service/ImageUploadValidationServiceTest.java)：增加伪报文件大小、超大尾部内容和颜色/透明度保持的验证。后端 Maven 单元测试结果为 **215 项、0 失败、0 错误、1 跳过**。本轮没有重跑全部数据库/RabbitMQ 集成测试。
3. [run-capacity-next.py](E:/mealcheck-fullstack/loadtests/run-capacity-next.py) 与 [summarize-capacity-memory.py](E:/mealcheck-fullstack/loadtests/summarize-capacity-memory.py)：保存 k6 阶段、镜像和资源配置、JVM/GC、cgroup、队列等证据；生成内存采样汇总。内存模式已运行，持续扫描和长时间模式尚待验证。
4. [capacity-ai-mixed.js](E:/mealcheck-fullstack/loadtests/capacity-ai-mixed.js) 和 [model_stub.py](E:/mealcheck-fullstack/deploy/scripts/model_stub.py)：已准备助手、周报混合负载及对应模型桩结构化响应、分类调用计数。**混合 AI 脚本及这些新响应路径尚未端到端验收**，下次先做短冒烟，不能直接视为已通过。
5. [compose.capacity.yml](E:/mealcheck-fullstack/deploy/compose.capacity.yml)：仅在隔离容量环境提高假模型测试额度，用户每日 1000 次、系统每日 50000 次，避免长测先碰到账单配额。本轮没有提高生产环境额度。

本轮优化镜像：`sha256:4322e041ecec2c20dee04270321277b039ae9e72ad06a9f3e1b3ad2a24402f2a`。

基线镜像：`sha256:2945faee0b2c434eb8bf4858594e1061ac7be26e46e22ea7343d7288a5275527`。

## 暂停与清理

2026-10-04 用户要求暂停后，核实压测调度器处于第一轮大图后的间隔、没有活动 k6 子进程，随后停止调度器，未再启动后续负载。45% 组的自动 `finally` 收尾没有运行，已手工补做账号清理和环境恢复。

当晚的收尾检查确认：

- 三组各自创建的 40 个专用账号已通过账号删除 API 清理；清理脚本同时删除了本地账号凭据文件。
- 数据库只读汇总结果：`capacityUsers=0`、`records=0`、`analysisTasks=0`、`pending=0`、`outbox=0`。没有使用全库删除命令。
- RabbitMQ 就绪消息、未确认消息均为 0；`/app/upload-temp` 没有遗留文件。
- 六个测试容器健康；后端恢复为消费者 **4**、`-XX:MaxRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError`；模型桩恢复为延迟 0.05 秒、无注入错误。

以上是 **2026-10-04 收尾时的状态**，不是 2026-10-05 重新检查的实时状态。恢复到 50% 只应用于当时的测试容器；Dockerfile 和生产 Compose 的默认值仍为 60%。未来重新创建容器时，应显式指定本轮需要验证的 JVM 参数，避免配置悄悄回到默认值。

## 下次继续的位置

1. 先确认工作区改动、隔离环境和假模型地址，核对镜像与实际 JVM 参数；不要直接重用已删除的账号凭据。
2. 完整重跑 45% 堆的三轮大图测试，并用相同冷启动/预热条件对比 50%。观察内存峰值、GC 暂停、上传耗时及失败率，再选定配置。
3. 在同一镜像、同一资源和选定堆配置下比较消费者 **4、6**：模型桩每次调用 5 秒；20/30/40 次每分钟，单档 15 分钟；20 任务突发各重复三次。某档已经不达标时停止继续加压，保留失败与排空证据。
4. 只有 6 带来明显收益且 CPU、内存及下游调用并发仍有余量时，再测 8。当前结果不足以判断 6 或 8 优于 4。
5. 先冒烟验证新增助手/周报响应及判定，再进行 50 VU 查询＋图片上传的 30 分钟混合负载；随后验证助手/周报竞争情况和选定配置的 1–2 小时稳定性。
6. 最后做后端执行中重启、模型 429/超时、RabbitMQ 短时不可用测试，检查恢复时长、丢任务、重复记录、幂等重放和实际模型调用次数。源码当前分析任务租约为 20 分钟，需特别验证崩溃后的等待时间，不能只看容器重新健康。

正常负载继续采用：成功率至少 99%、成功任务 E2E p95 小于 60 秒，无超时、未确认任务或丢弃迭代，队列不持续增长，并保留资源余量。容量超限时的主动拒绝单独统计。真实模型验证仍待实际服务配置及额度条件具备。

以下命令仅记录恢复入口，**本次补写时未执行**：

```powershell
E:\Anaconda\python.exe -X utf8 loadtests/run-capacity-next.py --mode memory --label optimized-heap45-repeat --consumers 4 --jvm '-XX:MaxRAMPercentage=45.0 -XX:+ExitOnOutOfMemoryError' --fresh
```

后续持续测试的 JVM 参数应在内存复测后确定，不直接沿用脚本默认值。

## 原始证据

| 测试组 | 运行清单 | JVM/cgroup 原始采样 | 内存汇总 |
| --- | --- | --- | --- |
| 基线 60% | [manifest](E:/mealcheck-fullstack/loadtests/results/20261004-221949/manifest.json) | [jvm.jsonl](E:/mealcheck-fullstack/loadtests/results/20261004-221949/jvm.jsonl) | [memory-summary](E:/mealcheck-fullstack/loadtests/results/20261004-221949/memory-summary.json) |
| 优化 50% | [manifest](E:/mealcheck-fullstack/loadtests/results/20261004-222559/manifest.json) | [jvm.jsonl](E:/mealcheck-fullstack/loadtests/results/20261004-222559/jvm.jsonl) | [memory-summary](E:/mealcheck-fullstack/loadtests/results/20261004-222559/memory-summary.json) |
| 优化 45%，部分完成 | [manifest](E:/mealcheck-fullstack/loadtests/results/20261004-223131/manifest.json) | [jvm.jsonl](E:/mealcheck-fullstack/loadtests/results/20261004-223131/jvm.jsonl) | [memory-summary](E:/mealcheck-fullstack/loadtests/results/20261004-223131/memory-summary.json) |

各目录还保留 `c4-large*.json`、k6 日志、时序数据和测试开始时的源码快照。50% 组的逐轮 k6 结果：[第一轮](E:/mealcheck-fullstack/loadtests/results/20261004-222559/c4-large1.json)、[第二轮](E:/mealcheck-fullstack/loadtests/results/20261004-222559/c4-large2.json)、[第三轮](E:/mealcheck-fullstack/loadtests/results/20261004-222559/c4-large3.json)。

上一轮消费者 2/4 的结果见 [突发与持续负载报告](E:/mealcheck-fullstack/loadtests/reports/k6-burst-comparison-20261004-195236.md)，不要将其误写为本轮消费者 4/6 的结果。
