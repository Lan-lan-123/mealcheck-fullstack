# 图片分析 RabbitMQ 运行说明

图片分析默认通过 RabbitMQ 分发，周报仍使用原有数据库任务队列。前端提交和查询接口保持兼容。

## 消息流程

1. `POST /api/meals/analysis-requests` 校验图片和幂等标识，在同一 PostgreSQL 事务中保存 `meal_analysis_requests` 与 `meal_analysis_outbox`，返回 `202 / PENDING`。
2. Outbox 发布器每秒领取最多 20 条到期事件。领取使用短事务、`FOR UPDATE SKIP LOCKED` 和 30 秒发布租约；等待 Broker 确认时不持有数据库事务。
3. 发布器发送持久消息，消息正文仅为 36 字符的随机事件 UUID，不含图片、用户名、JWT 或模型输出。消息发到 `mealcheck.analysis` exchange，再路由到 `mealcheck.analysis.jobs` quorum queue。
4. 发布必须同时满足 publisher confirm ACK 与未发生 mandatory return 才标为已发送。发送失败、NACK、无法路由或确认超时，30 秒租约到期后重试。允许重复投递。
5. 消费者默认每实例并行执行 8 个任务，每消费者 prefetch 为 1。根据事件 UUID 在数据库查找请求，再用账户锁、任务状态与执行令牌领取。实际模型调用不放在数据库事务中。
6. 餐食、画像与任务成功状态提交后，监听方法才返回。Spring `AcknowledgeMode.AUTO` 在方法成功返回后发送 ACK；它不是 RabbitMQ 的“投递即确认”模式。
7. 成功后删除对应 Outbox；失败时保留事件关联供死信排查，直到用户重试、删除或请求保留期清理。发布器不会发送 `FAILED` 任务。重复、已完成、已删除或已被手动重试替换的事件直接忽略。前端继续通过原有 GET 接口查询任务状态。

Flyway **V18** 新增 Outbox 表，并为 V17 遗留的排队/处理中图片任务补建事件。手动重试会生成新的事件 UUID，旧消息不能领取新一轮提交。

## 启动与配置

开发基础服务：

```bash
docker compose up -d
```

包括 PostgreSQL、Redis 和 RabbitMQ。开发 AMQP 地址是 `localhost:5672`，管理界面为 `http://localhost:15672`，均只将 RabbitMQ 端口绑定到宿主机 loopback。开发账号默认为 `mealcheck` / `mealcheck-rabbit-dev`，通过 `.env` 可覆盖。

全部服务：

```bash
docker compose --profile fullstack up --build -d
docker compose --profile fullstack ps
```

本地 Maven 启动需把配置注入进程环境。主要配置如下：

| 配置 | 默认值 | 用途 |
| --- | --- | --- |
| `SPRING_RABBITMQ_HOST` / `SPRING_RABBITMQ_PORT` | `localhost` / `5672` | Broker 地址；Compose 内使用 `rabbitmq` |
| `SPRING_RABBITMQ_USERNAME` / `SPRING_RABBITMQ_PASSWORD` | 开发账号 | 生产必须改为独立凭据 |
| `SPRING_RABBITMQ_VIRTUAL_HOST` | `/` | 应用连接的 vhost；使用其他 vhost 时需预先创建并授权 |
| `MEALCHECK_ANALYSIS_DISPATCH` | `rabbitmq` | `database` 可切回旧数据库轮询 Worker |
| `MEALCHECK_ANALYSIS_CONCURRENCY` | `8` | 每实例 RabbitMQ 消费者并发数，建议用正整数 |
| `MEALCHECK_ANALYSIS_WORKER_ENABLED` | `true` | 设为 `false` 可关闭该实例消费，仍允许 API 入队和 Outbox 发布 |
| `MEALCHECK_ANALYSIS_OUTBOX_POLL_MS` | `1000` | Outbox 发布间隔（毫秒） |

改变并发前需考虑模型供应商并发/速率配额、数据库连接池、内存和整体成本。配置 prefetch 不能代替消费者并发配置。旧同步 `/api/meals/analyze` 和管理员视觉评测入口仍直接调用模型，不经过此队列。

生产使用 `.env.production.example` 与 `docker-compose.prod.yml`，必须配置 `SPRING_RABBITMQ_PASSWORD`。生产 Broker 和管理端口不映射到宿主机。RabbitMQ 使用独立持久卷和固定 hostname；更改 hostname 可能使 Broker 使用不同数据目录。当前 Compose 是单节点 Broker，quorum 队列只有单副本，不能当作多节点高可用部署。

## 失败处理与恢复

- **模型或业务失败**：供应商调用已有有限重试；分析最终失败后，将数据库任务标为 `FAILED` 并拒绝消息。消息进入 `mealcheck.analysis.dead.jobs` 死信队列，不自动无限重试模型。用户使用原图、原请求标识重试会创建新的派发事件。
- **数据库等基础设施异常**：监听容器最多尝试 3 次（间隔 1 秒、2 秒）；异常耗尽后进入死信队列。若错误发生在领取后、无法记录失败，等待执行租约过期后自动恢复；若发生在领取前，需排障后重放。
- **进程中断**：RabbitMQ 未确认消息可重新投递；数据库仍在有效执行租约内时，重复消息会被忽略。20 分钟租约过期后 Outbox 会重新投递任务，总领取尝试最多 3 次，之后标记失败。
- **发送成功但发布器崩溃**：发布租约到期后可能重复发送；消费者的数据库领取和结果发布检查确保重复消息不重复发布餐食。
- **已确认但尚未消费**：消息留在持久队列等待消费者，不按固定时间重复发送，避免积压时重复消息不断增长。已领取任务的租约过期恢复仍会自动触发。
- **Broker 数据丢失或数据库恢复到新 Broker**：必须先停止全部任务写入者与消费者，再执行下方重放 SQL，随后重启服务。Outbox 随数据库备份保存，已结束任务的死信消息不会从数据库重建。
- **重复计费边界**：在调用模型后、保存结果前崩溃，恢复任务可能再次调用供应商；结果幂等不代表模型调用只计费一次。

仅在确认需要恢复 Broker 消息、且所有处理实例已停止时，对应用数据库执行：

```sql
BEGIN;
UPDATE meal_analysis_outbox o
SET published_at=NULL, publish_token=NULL, next_publish_at=CURRENT_TIMESTAMP
FROM meal_analysis_requests r
WHERE o.user_id=r.user_id AND o.request_key=r.request_key
  AND r.status IN ('PENDING','PROCESSING') AND r.input_image IS NOT NULL;
UPDATE meal_analysis_requests
SET lease_until=CURRENT_TIMESTAMP
WHERE status='PROCESSING' AND input_image IS NOT NULL;
COMMIT;
```

重放保留任务的尝试计数，不会无限重试已达到领取上限的任务。仅重启 Broker 且原持久卷完好时不需要执行该 SQL。正常业务错误进入 `FAILED` 后仍由用户通过原图重试；数据库故障耗尽监听器重试且发生在领取前的任务，可能在死信队列中保留已确认发布的事件，排障后需按上述流程重放。

迁移时，同一环境的实例应统一 `MEALCHECK_ANALYSIS_DISPATCH`，避免混用新旧分发策略。切换前停止任务处理实例，再以统一配置启动。`database` 模式下 Outbox 仍随任务创建，便于稍后切回 RabbitMQ；RabbitMQ 健康检查可按需通过 `MANAGEMENT_HEALTH_RABBIT_ENABLED=false` 关闭。

生产的数据库与图片一致备份脚本仍适用于 Compose 单后端实例。若独立部署多个 API/消费者进程，备份前必须暂停全部写入者。队列消息不能替代数据库和图片备份。

## 监控与排障

- 原有 `mealcheck.analysis.pending`、`mealcheck.analysis.oldest.active.seconds`、`mealcheck.analysis.expired.leases`、`mealcheck.analysis.jobs`、`mealcheck.analysis.duration` 继续使用。
- 新增 `mealcheck.analysis.outbox{outcome=confirmed|retry}` 与 `mealcheck.analysis.deliveries{outcome=ignored}`，分别观察发布确认/重试与重复投递。
- Broker 管理界面可以查看 ready、unacked、consumer 数及死信队列积压。死信只包含事件 UUID，可与失败任务的 Outbox 关联排查；成功、手动重试或保留期清理后旧关联消失。不要把死信队列批量重投作为模型失败的自动重试策略。
- 若所有任务停在 `PENDING`：检查 RabbitMQ 健康、连接凭据、发布重试指标、队列绑定及消费者是否启用。
- 若任务长期处于 `PROCESSING`：检查消费者进程和供应商超时；租约过期后观察恢复情况。

## 验证

```bash
cd backend
mvn test
mvn verify -Pintegration
```

`MealAnalysisRabbitDatabaseIntegrationTest` 使用真实 PostgreSQL 和 RabbitMQ 测试容器，覆盖并发消费、重复消息、不可路由补发、消息丢失恢复、租约恢复与旧尝试隔离、死信与手动重试、事务回滚、用户删除，以及发布器租约隔离。模型使用确定替身，不产生供应商调用费用。Docker 不可用时集成测试失败，不会静默跳过。

### 2026-10-02 本地验收

- 最终执行 `mvn -B -ntp verify -Pintegration` 成功：169 个单元/HTTP 测试、25 个容器集成测试，失败、错误、跳过均为 0。
- 其中 6 个 RabbitMQ + PostgreSQL 集成测试实际验证双消费者并行、重复消息只发布一次餐食、不可路由消息不标记已发送、已确认待处理任务不重复入队、恢复空 Broker 后的显式重放、旧执行令牌隔离、死信关联与手动重试、事务回滚及账户删除。
- 开发 Compose 和生产冒烟 Compose 配置解析通过；本次没有发布生产环境，也没有调用真实模型供应商。
- 本地日志位于 `backend/target/rabbit-final-verification.log`，标准报告位于 `backend/target/surefire-reports/` 和 `backend/target/failsafe-reports/`，这些生成文件不提交 Git。
