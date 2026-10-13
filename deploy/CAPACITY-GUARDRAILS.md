# 图片接单与 AI 共用预算

所有后端实例必须部署同一版本，并使用相同限制、数据库和预算时区。数据库事务协调接单、全站日预算、滚动 60 秒调用准入和并发名额；单独增加后端实例不会自动扩大这些上限。

## 配置

| 参数 | 生产默认 | 容量测试 | 含义 |
| --- | ---: | ---: | --- |
| `MEALCHECK_ANALYSIS_CONCURRENCY` | 8 | 8 | 每个后端实例的图片消费者 |
| `MEALCHECK_ANALYSIS_MAX_OUTSTANDING` | 32 | 32 | 全站未完成图片任务，含上传预处理、执行中、过期待接管和未发布 Outbox |
| `MEALCHECK_ANALYSIS_MAX_OUTSTANDING_PER_USER` | 2 | 2 | 单个用户未完成图片任务 |
| `MEALCHECK_AI_GLOBAL_MAX_CONCURRENT_CALLS` | 10 | 10 | 图片、助手、周报及其他 AI transport 共用的客户端尝试名额 |
| `MEALCHECK_AI_GLOBAL_MAX_STARTS_PER_MINUTE` | 120 | 120 | 滚动 60 秒共用调用准入次数，包括重试；未发出的准入保守计入 |
| `MEALCHECK_AI_GLOBAL_DAILY_CALLS` | 100000 | 100000 | 用户调用与系统调用合计的全站日尝试预算 |
| `MEALCHECK_AI_GLOBAL_DAILY_COST` | 0 | 0 | 共用费用准入预算；0 表示不限制金额，真实测试前必须明确金额与模型价格 |

JVM 默认 45%，同时解码 2 张，最多等待 20 个解码请求，等待 8 秒。不要把解码等待名额当作后台任务积压上限。

2026-10-10 起，全站日调用额度统一提高为 100000 次。个人日额度仍为 100 次，后台系统日额度仍为 5000 次；容量环境的个人/系统测试额度保留原值。全站日额度变化不扩大并发名额、滚动分钟准入或图片任务容量。已有环境中的 `MEALCHECK_AI_GLOBAL_DAILY_CALLS` 显式值仍优先，需要更新并重建后端容器才能应用。

本次日额度调整通过 7 项预算/并发单元测试及 12 项 PostgreSQL 预算/查询集成测试；普通 fullstack、生产及容量环境 Compose 解析均确认全站日额度为 100000。该验证覆盖预算一致性，不代表真实模型已达到每天 10 万次吞吐；既有部署未重建。

2026-10-09 起，应用、普通 Compose、生产 Compose 和容量环境统一采用 8 个消费者、32 个未完成任务名额。普通与生产后端容器资源均默认 1.5 CPU / 1 GiB、JVM 堆比例 45%；图片解码保持 2 / 20 / 8000 毫秒，每用户最多 2 个未完成任务，全站 AI 同时调用名额仍为 10。

8/32 配对下，模型每张总处理约 10 秒时，最后一批约在 40 秒处理完。该估算不包括真实服务限流、抖动和额外工作，不能作为线上延迟保证。已完成的容量验收使用模型桩；真实供应商的配额与响应时间需要另外验证。测试模型地址、测试凭据、注册/验证码限流放宽和测试日预算继续仅用于隔离容量环境。

已有显式环境变量优先于新默认值；若 `.env` 或 `.env.production` 中仍写着 2/8，需要分别更新 `MEALCHECK_ANALYSIS_CONCURRENCY=8`、`MEALCHECK_ANALYSIS_MAX_OUTSTANDING=32`，然后通过对应环境的 Compose `up -d` 重建后端。仅修改文件或执行 `docker start` 不会更新已有容器的环境变量。

2026-10-09 默认配置统一验收：普通、生产和容量 Compose 解析结果均为 8 / 32、1.5 CPU / 1 GiB、45% JVM 堆；普通与生产日预算仍为 5000。`mvn verify -Pintegration` 通过 238 项单元/HTTP 测试及 66 项 PostgreSQL 集成测试。在独立临时项目中启动新 JAR，未显式设置消费者数量，后端健康为 UP，RabbitMQ 的 `mealcheck.analysis.jobs` 实际注册 8 个消费者。临时项目已清理，既有部署未重建。

## 行为与恢复

- 接单在账号锁和全站事务锁内检查名额，然后写入领取记录；多实例并发不能同时超额。Outbox 与任务按同一任务计数，不叠加计算。
- 返回 `429 ANALYSIS_BUSY` 或 `429 ANALYSIS_USER_BUSY`，并带 `Retry-After: 3`。拒绝不会新建领取、图片 payload 或 Outbox。原请求已接收或已成功时，幂等重放仍返回原状态。
- 不带幂等键的旧同步分析接口也领取同一任务名额，处理结束释放，保留原响应结构和限流头；不能通过旧接口绕过每用户上限。
- 前端仅对明确的上传繁忙/全站繁忙最多重试两次，保留原图、目标和请求键；用户积压上限不自动重试。
- 异步上传预处理占位 60 秒；到期且未写入图片 payload 的占位不再占容量，也不能迟到入队。同步分析领取仍为 20 分钟。
- 后台执行租约 90 秒，每 10 秒续租。过期后台任务继续计入积压，等待重新接管。已过期或被替换的执行不能保存结果或标记失败。
- AI 名额按每次 transport 尝试获取，在超时预算内有界等待；失败重试也共用限额和日预算。数据库不可用时不绕过限制。
- 进程崩溃留下的 AI 名额在“该次剩余截止时间＋30 秒”后到期。客户端结束或名额到期不证明供应商已取消请求；费用可能重复，供应商实际活动请求数也可能暂时高于客户端名额。
- 全站费用保留独立预算记录，删除用户不会抹掉已经发出的调用或遗失待结算的全站占位。未发出请求释放预算，已发出但用量未知的请求保守结算，十分钟后的未结算记录由恢复任务处理。
- 固定重放错误仍报冲突；成功结果删除后保持原来的失效语义。接单上限不改变业务幂等规则。

## 本地验收命令

仅使用 `mealcheck-capacity` 和 `capacity-test-only` 模型桩，故障脚本会校验项目和端点。不要并行运行这些命令。

```powershell
$env:MEALCHECK_CAPACITY_BACKEND_IMAGE='mealcheck-capacity-backend:guardrails-20261008-final'
python loadtests/run-capacity-admission-faults.py
python loadtests/run-capacity-faults.py
python loadtests/run-capacity-next.py --mode mixed-ai --label guardrails-smoke --consumers 8 --rates 30 --duration 120s --weekly-rate 1 --assistant-rate 12 --fresh
python loadtests/run-capacity-next.py --mode mixed-ai --label guardrails-full-mixed-1h --consumers 8 --rates 30 --duration 3600s --weekly-rate 1 --assistant-rate 12 --fresh
```

该固定镜像 ID 为 `sha256:aba465be32a1105519a9ac1c935a43d7b59eb80cf8097784a812c2faa88e0232`。若源码继续变化，应另建镜像并记录版本，不复用这个标签覆盖已验收版本。

周报使用独立账号，持续到达，每次使用未生成过报告的账号；不能把缓存报告作为新的模型生成。各组保留参数、镜像、原始结果、连续 JVM/cgroup 样本和专用账号清理证明。网络错误保持失败，不吞掉首次连接异常。

## 真实模型验收待办

按用户指示暂不发起真实模型测试。准备好本地密钥后，先确认供应商/模型/端点、RPM/TPM/并发额度、输入输出价格和本轮费用/调用次数上限；密钥只保存在本地配置，不写入报告或聊天。

先做有限次数的功能与延迟冒烟，再按额度逐档增长。当前滚动准入、并发和日费用预算不等于 TPM 限制，也不等于供应商账单硬限。需要按供应商真实口径核对；未知 token 用量或价格不能报告为已验证费用。

## 部署与回退

此前容量验收只更新了容量环境。2026-10-09 已统一应用、普通和生产配置文件的默认值，既有容器仍需按各自部署流程重建才能应用新值。生产部署需先备份数据库及上传文件，再通过既有生产部署流程部署同一镜像到全部实例，禁止混用缺少共用限制的旧版本。

迁移 `V23` 是新增索引、字段和表，没有删除业务数据；不要为回退执行 `down --volumes` 或删除这些表。

容量环境上一轮镜像为 `sha256:43d8129473b52aa246fc162fa4218dcf852c974ac925129438436a92e6dbf07d`。必要时设置 `MEALCHECK_CAPACITY_BACKEND_IMAGE` 为保留镜像，并通过 `up -d --no-build --force-recreate` 更新后端；旧镜像没有本轮完整保护，回退后停止压测并恢复保守流量。回退镜像后再次核对 Flyway 校验、健康状态、待处理任务和 Outbox，不能仅凭容器启动判断回退成功。
