# Outbox 窗口发布与统一任务轮询（2026-10-14）

## Outbox

`MealAnalysisOutboxStore.claimBatch` 在短事务中按窗口查询事件 ID，使用 `FOR UPDATE OF o SKIP LOCKED` 跳过其他发布者持有的行。一次 JDBC 批量更新为各事件分别生成发布 token、递增尝试次数、设置 30 秒领取租约，然后提交。等待 RabbitMQ 确认时不持有数据库事务。

`MealAnalysisOutboxPublisher` 默认每窗口 8 条：先发送本窗口的持久化消息，再逐条处理确认结果。每窗口共享 5 秒确认截止时间，避免多条未确认消息串行累积等待。窗口全部处理结束后才领取下一窗口，每次运行最多领取 20 条；同一发布器的重叠调用会跳过。没有新增发送线程池。

- ACK 且没有 returned message 时才回写；NACK、无法路由、发送异常、确认超时或数据库回写异常保留租约恢复。
- 某一条失败不影响本窗口其他已确认消息回写；发生失败后不再领取下一窗口。
- 发送中断时，已发送部分继续处理确认，尚未发送的已领取行等待租约恢复。
- 回写同时验证事件 ID、发布 token 和未过期的领取时间。被删除、已被新尝试接管或租约已过期的行计为 `fenced`。
- 超时后没有异步数据库回写，迟到 ACK 不会覆盖新的 token。消息可能因未确认而重复投递，消费者继续使用原有任务幂等、执行 token 与租约保护。
- 正常已发布的 PENDING 积压不重复发布；过期 PROCESSING 的恢复与显式空队列重放规则保持原有行为。

配置已接入 `application.yml`、开发/生产 Compose 与开发/生产环境变量示例：

| 环境变量 | 默认 | 有效范围 |
| --- | --- | --- |
| `MEALCHECK_ANALYSIS_OUTBOX_WINDOW_SIZE` | 8 | 1–20，超界限制到边界 |
| `MEALCHECK_ANALYSIS_OUTBOX_CONFIRM_TIMEOUT_MS` | 5000 | 100–10000 毫秒，保持短于 30 秒领取租约 |

## 图片与周报共用轮询

新建 `frontend/src/utils/taskPolling.js`，两个任务保留各自终态判断与操作提示，共用等待、退避、可见性、取消、超时与网络错误处理。

- 默认从 3 秒开始，图片任务的服务端 `retryAfterSeconds` 优先作为最低等待时间。状态不变时按 1.5 倍退避，普通前台间隔最多 10 秒，并增加最多 249 毫秒随机偏移；服务端更长的等待提示仍得到遵守。状态推进或断网后恢复时重置退避。
- 浏览器隐藏时至少 30 秒查询一次；隐藏事件会取消此前的快计时器。恢复可见时立即查询；如果已有查询未结束，则在它结束后立即再查，避免重叠请求。
- 网络异常、408、429、5xx 最多连续失败 5 次；状态读取成功后清除中断提示。HTTP 429 的 Retry-After 得到遵守。鉴权、不存在/过期任务等其他 4xx、无效接口契约、业务终态立即停止。
- 周报也显示“连接暂时中断，正在重新查询”，恢复后清除提示。自动重连始终查询原任务 ID/key，不重新提交图片或新建周报。
- 保留次数上限（图片 420、周报 200），增加总等待截止时间（图片 21 分钟、周报 10 分钟），包括后台时间、网络请求与退避。达到上限给出查询已有任务的操作提示，不取消后端任务。
- 取消/超时会中止当前计时器与查询，丢弃忽略 AbortSignal 的迟到结果；结束时清理可见性、取消监听和截止时间计时器。

## 验证

- 后端完整单元/HTTP 回归 **296 项**通过；Outbox 发布专项 **8 项**覆盖窗口、乱序 ACK、部分 NACK、发送失败、缺失/迟到 ACK、运行上限和重叠调用。
- PostgreSQL/RabbitMQ 专项 **9 项**通过，新增跨窗口发布 12 条持久化消息、并发批量领取跳过锁、批量回滚，并扩展过期 token 的确认保护。
- 完整后端数据库集成回归 **115 项**通过，无失败、错误或跳过。
- 前端严格类型检查、**67 项**单元测试、生产构建通过；新增统一轮询专项 **15 项**。
- 完整浏览器回归最终 **101 项**通过，包含图片/周报的可见性切换与周报断网恢复。首轮 100 项通过，新增周报断网提示用例在实际时间等待下失败；随后任务进度专项 8 项通过，并将新断网用例改为受控时钟，最终完整回归全部通过。首轮日志保留。
- `git diff --check` 通过。
- 后端生产包构建、开发与生产 Compose 的 `config --quiet` 校验通过。

日志：`backend/target/outbox-polling-unit.log`、`outbox-polling-rabbit-integration.log`、`outbox-polling-integration-complete.log`、`outbox-polling-package.log`；根目录日志归档至 `logs/root/outbox-polling-typecheck-final.log`、`logs/root/outbox-polling-frontend-unit.log`、`logs/root/outbox-polling-build.log`、`logs/root/outbox-polling-e2e.log`、`logs/root/outbox-polling-progress-e2e.log`、`logs/root/outbox-polling-e2e-final.log`。

本轮验证功能、并发边界与恢复行为，没有测定生产吞吐提升倍数，尚未部署业务环境。为启动隔离测试，已核对停止状态及仅包含零字节套接字/空目录的 Docker 运行目录，将它们保留到同父目录时间后缀备份，再后台启动 Docker；未重置 Docker 或删除秘密数据、镜像、容器。
