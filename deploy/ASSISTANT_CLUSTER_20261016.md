# 助手跨实例任务协调

## 实现与接口

新增 V33 `assistant_tasks`。同步 `/api/assistant/ask`、兼容 `/api/chat` 和流式 `/api/assistant/stream` 共用有界执行池及数据库用户占位。部分唯一索引保证同一用户全站只有一个活动任务；本机仍保留快速拒绝与取消执行所需的引用。

- 实例 ID 每次进程启动随机生成；请求 ID 和执行 token 独立生成。
- 默认每实例 4 个工作线程、16 个等待位置。租约默认 15 秒，每个租约的三分之一时间更新一次；续租只覆盖仍在本机任务表中的请求。所有实例都能清理过期占位，不需要等待原实例重启。
- 排队时间包含在原有操作期限内。主动取消或超时后，正在运行的任务保持共享占位直到工作线程完成清理；排队任务取消立即移出本机队列并收尾。
- `DELETE /api/assistant/requests/{requestId}` 可发往任意实例。只允许请求所有者更新取消标记；执行实例通过心跳读取标记并中止本地工作。
- `GET /api/assistant/requests/{requestId}` 只返回本人请求 ID 和状态，越权与不存在均为 404。状态包括 QUEUED、RUNNING、CANCEL_REQUESTED、SUCCEEDED、FAILED、CANCELLED、TIMED_OUT、INTERRUPTED。
- 同步响应保留原有正文，并增加 `X-Assistant-Request-ID`；协调存储故障时拒绝提交，不回退到单机占位。同步被取消返回 409 / ASSISTANT_CANCELLED。

## 提交与故障边界

完整对话写入前在同一事务锁住任务行，验证实例、执行 token、状态、数据库租约和期限；发布标志与问题/回答一并提交或回滚。事务提交前再次验证本机执行期限与取消状态。取消先获得任务行时阻止完整写入；完整回答已经提交时不撤销历史结果。

发布事务按用户行、任务行的顺序加锁，在获取第一把用户锁之前设置 PostgreSQL 的事务内空闲超时为 3 秒；其他短协调事务也设置相同超时。避免进程被暂停时只依赖失效的客户端超时而长期持锁。模型请求和 SSE I/O 均不置于数据库事务内。

活跃实例失联后，过期请求被标为 INTERRUPTED 或 TIMED_OUT；已提交完整回答的过期收尾保留 SUCCEEDED。不会自动重问模型，也不把部分回答改为完整建议。旧实例恢复后无法续租已过期任务、覆盖新执行或提交完整旧回答。终态任务元数据默认分批保留 30 天；已保存的会话消息不随该清理删除。

SSE 连接仍由接入实例持有，本轮支持跨实例控制与结果写入隔离；进程重启不恢复原有流连接。取消与故障后的供应商请求是否继续执行仍由供应商决定，不保证只计费一次。图片存储共享、反向代理服务发现及其他模块的扩容需按各自部署方案配置，不能仅凭本项宣布整个应用已具备高可用性。

新增协调健康、最后成功时间、本机活动数和队列长度指标，并接入协调故障告警。

## 配置与升级

`MEALCHECK_ASSISTANT_DELIVERY_WORKERS=4`、`MEALCHECK_ASSISTANT_DELIVERY_QUEUE_CAPACITY=16`、`MEALCHECK_ASSISTANT_DELIVERY_LEASE_SECONDS=15` 已接入应用、普通与生产 Compose 和环境示例。租约有效范围 2–300 秒；工作线程和队列维持正数。

升级时所有服务实例必须使用包含 V33 的版本和同一个协调数据库。应先排空旧版助手请求再切换；旧版本不使用共享占位，滚动混跑不能保证全局互斥。迁移只新增表和索引，不重写历史会话。降级前先排空新版任务，数据库兼容性与完整回退按既有发布流程单独验证。

## 验证入口

```powershell
$env:DOCKER_HOST='npipe:////./pipe/dockerDesktopLinuxEngine'
$env:DOCKER_API_VERSION='1.44'
mvn -f backend/pom.xml --batch-mode --no-transfer-progress verify -Pintegration
python deploy/scripts/verify_assistant_cluster.py --output deploy/release-results/your-new-cluster-run
```

单元测试覆盖同步/流式本机占位、清理后释放、期限、取消、退出保护、协调不可用和续租失败。`AssistantClusterDatabaseIntegrationTest` 使用隔离 PostgreSQL 验证跨实例准入、权限、发布/取消并发、回滚、旧租约隔离、排队与清理占位及终态保留；还验证发布线程在第一把用户锁处停止推进时，数据库会自行释放锁，旧事务恢复后无法写入消息。

`verify_assistant_cluster.py` 新建随机且原先不存在的 Compose 项目，启动两个真实后端 JVM、PostgreSQL、Redis、RabbitMQ 和本地模型替身。后端和模型只加入内部网络，由固定目标的专用 TCP 网关提供主机回环端口。两端 JAR 均与固定副本 SHA-256 对账。故障操作只针对该项目，验证 kill、pause/unpause、远程取消和旧执行恢复；最终清理专用容器与卷，保留日志。它不会读取生产 .env 或使用真实模型密钥。相同检查已接入普通 CI。

## 最终验收结果

2026-10-16 完成，本项状态为 **VERIFIED**。源码、JAR、报告及日志的 SHA-256 固定在 [阶段 7 清单](release-results/assistant-stage7-20261016/manifest.json)。

| 检查 | 结果 |
| --- | --- |
| 完整后端单元/HTTP | 341 项通过 |
| PostgreSQL/RabbitMQ 集成 | 135 项通过，含助手跨实例数据库 13 项 |
| 两个真实后端 JVM | 16 项通过，最终运行 `dual-backend-04` |
| 模型替身与验收命名空间保护 | 4 + 1 项通过 |
| 第 6 项备份指标回归 | 2 项通过 |
| Prometheus | 32 条规则通过；生命周期预算、协调失败和心跳停滞告警触发/恢复通过 |
| 工作流与脚本 | actionlint、Python 编译及改动空白检查通过 |

最终构建 JAR：`c46d68b9fd33727aae5600f65322a6c04d6a0e6883185c38d01a40b029994917`。验收使用该 JAR 的冻结副本，在两端容器内逐一对账。没有调用真实模型，没有修改原业务数据库；专用容器和数据卷已清理。

保留首轮隔离网络无法直接发布主机端口的失败记录，修复为固定目标网关；保留第三轮验收脚本未等待同步取消清理终态的失败记录，已改为观察共享状态后推进。最终验收没有放宽全局互斥或提前释放正在清理的占位。

双实例冷启动还观察到知识索引初始化的重复键竞争，现有维护任务随后重试成功（见首轮及第三轮容器日志）。该模块的启动协调不属于本项助手任务改造范围，整体扩容上线前仍需单独处理。

第 6 项证据 `deploy/release-results/lifecycle-stage6-20261016/` 保持不变，归档时逐一核对原清单中的证据哈希和源码包哈希。本轮只完成源码与隔离验收，尚未部署生产环境。
