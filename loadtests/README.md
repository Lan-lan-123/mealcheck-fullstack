# MealCheck k6 入门

当前提供以下脚本。查询与图片分析的耗时需要分别解读。

| 脚本 | 用途 | 是否提交 AI 任务 |
| --- | --- | --- |
| `read.js` | 餐食列表查询，JWT 鉴权、数据库分页查询 | 否 |
| `read-multi.js` | 多账号、非空餐食列表查询，分别记录业务请求数与耗时 | 否 |
| `mixed.js` | 图片幂等重放、状态查询、周报提交、列表查询的混合流程 | 是 |
| `analysis-capacity.js` | 持续新增图片任务或突发提交，轮询到完成并记录端到端耗时 | 是 |
| `capacity-mixed.js` | 同时运行餐食列表查询和固定到达率图片分析 | 是 |
| `query-capacity.js` | 多账号 90 天趋势及两页长会话历史，核对非空数据与分页边界 | 否 |

下面先介绍 `read.js`，后半部分提供完整图片分析测试步骤。JWT 是实际测试账号登录后获得的令牌；k6 虚拟用户本身不会自动成为注册账号。

## 隔离容量与故障验收（2026-10-04）

`capacity_acceptance.py` 使用 `deploy/compose.acceptance.yml` 的独立项目 `mealcheck-capacity-acceptance`，无需生产环境文件。后端、指标端和模型替身仅绑定本机的 18081、19091、19082 端口。执行前检查 Compose 项目标签和专用模型密钥/地址，拒绝向其他环境写数据或注入故障。

需要 Java 17、Maven、Docker Compose、PATH 中的 k6，以及带 Pillow 的 Python。先在 `backend` 执行 `mvn verify -Pintegration` 生成当前 jar，再回项目根目录执行：

```powershell
python -m pip install Pillow
docker compose -p mealcheck-capacity-acceptance -f deploy/compose.acceptance.yml up -d --wait --wait-timeout 90
python loadtests/capacity_acceptance.py --output loadtests/results/local-acceptance-001 --fault-checks
docker compose -p mealcheck-capacity-acceptance -f deploy/compose.acceptance.yml down --volumes
```

最后一条命令仅清理这个专用验收项目及其临时数据，测试失败后也应执行。账号在隔离数据库中保留至环境清理，便于失败排查；每次使用新的输出目录，重复已有账号目录会被拒绝。`--fault-checks` 会顺序停止、重启专用 Redis 和 PostgreSQL，并验证故障期间返回 `503 / AUTH_DEPENDENCY_UNAVAILABLE`，恢复后同一 JWT 可访问。它不会操作生产服务。

默认 5 个账号，每人 10,000 条餐食、2,000 条聊天消息；餐食包含长建议和真实形状的食物/风险 JSON。生成约 7.36 MiB 的 4096×4096 JPEG，后端限制为 2 CPU、512 MiB 堆和 768 MiB 容器内存。可通过 `--accounts`（1–20）、`--meals-per-user`（1–100,000）、`--messages-per-user`（80–100,000）、`--read-duration` 调整；默认查询持续 30 秒。

验收标准：

- 查询默认 5 VU，趋势 P95 < 1 秒、消息分页 P95 < 500 ms、HTTP 失败率 < 1%，业务检查全部通过；历史两页每页 40 条且无重复，趋势记录数不少于种子数据。
- 图片各阶段默认提交 5 个独立任务。正常与慢模型阶段最多 2 VU，全部任务完成；慢模型每次请求延迟 1 秒。429 恢复阶段前两次请求返回 429 和 `Retry-After: 1`，后续恢复正常，必须记录至少 2 次物理 HTTP 重试且全部任务成功。
- 解码饱和阶段 5 VU 并发，允许最多 80% 明确的提交 429，至少一个任务成功；已接受任务不能执行失败、超时或状态不确定。该允许拒绝率只适用于主动制造的饱和用例。
- 非零 k6 退出码或任何用例 `passed=false` 使整体验收失败。阈值是回归检查标准，不是已证明的系统最大容量。

`acceptance.json` 汇总各阶段结果；各阶段 JSON、日志、Prometheus 快照和 `query-plans.txt` 保留原始证据。报告包括 CPU/容器内存与 JVM 堆采样、队列 P95 桶上界、数据库连接池获取时间、HTTP 重试及估算费用增量。SQL 计划直接读取当前 Repository 的三项原生查询，同时保存完整实体读取的参考计划；语句顺序执行且不重置缓存，不能把这些耗时解释成严格的优化前后倍数对比。数据库任务状态是整个隔离库的累计快照，可能包含之前阶段失败或准入拒绝的记录。

`accounts.json` 含 JWT，只供本轮使用，禁止作为共享证据。结果目录被 Git 忽略；CI 仅上传验收摘要、指标和 SQL 计划，不上传账号文件。安全边界和直方图计算可用 `python -m unittest discover -s loadtests -p test_capacity_acceptance.py -v` 独立验证。

2026-10-04 本地验收：50,000 条餐食、10,000 条消息下，趋势 P95 897.76 ms，分页 P95 94.98 ms；正常大图 P95 3.22 秒，慢模型 4.83 秒。前两次 429 后恢复，5 个任务全部成功；饱和阶段 2 张成功、3 张受控拒绝，两项依赖故障恢复通过。冷查询最大 3.47 秒，突发时容器内存采样最高约 741.5 MiB，接近 768 MiB 上限，需要进一步观察持续负载和内存余量。第一次使用持续周期性 429 的替身时出现 1 个有限重试后的任务失败，原始报告保留；后续单独验证了短暂 429 后恢复，未增加生产重试上限。

这是短时本地合成数据及模型替身验收，不验证供应商吞吐、识别质量、长时间稳定性或生产部署资源是否充足。

## 自动执行本地分档压测并生成 Markdown 报告

容量测试环境已启动且健康、k6 可用、合成样本 `tools/datasets/synthetic-meals-100/images/synth-001.png` 存在时，可在项目根目录运行：

```powershell
python .\loadtests\run-local-benchmark.py
python .\loadtests\write-benchmark-report.py .\loadtests\results\<本轮目录>
```

第一条命令会打印本轮证据目录，创建 20 个独立账号并准备每账号 1 条记录，执行 10/30/50 VU 的各 60 秒查询、5/10/20 张每分钟的各 60 秒图片到达测试，以及 20 张突发测试。图片阶段使用每次调用延迟 5 秒的本地模拟模型，并采集容器资源、RabbitMQ 和 Outbox 积压。每档等待任务排空后才继续；阈值失败会保留结果并继续下一档。

自动本地脚本明确使用 `http://127.0.0.1:18080`，与容量容器发布端口的 IPv4 loopback 绑定一致；浏览器仍可按环境配置使用 `localhost:18080`。

脚本结束会清理本轮账号及数据并恢复原模拟模型设置；清理或恢复失败会记录在 `manifest.json`。原始 JSON、日志及采样保存在被 Git 忽略的本轮目录；Markdown 保存到 `loadtests/reports/`。这是短时本地初步测试，不能据此宣称真实模型或线上最大容量。

## 第二轮优化复测：长时间对照与混合负载

在健康的 `mealcheck-capacity` 模拟模型环境中执行：

```powershell
python .\loadtests\run-capacity-optimization.py
python .\loadtests\write-optimization-report.py .\loadtests\results\<本轮目录>
```

脚本创建 40 个独立账号，经 API 各生成一条餐食，再仅扩充这些账号至每人 1,000 条合成记录。先用并发 2 复测 20 张突发、5 张 10 MiB 字节边界图片和 10/30/50 VU 查询，再比较并发 2 的 20 张/分钟与并发 4 的 5/10/20 张/分钟，各持续 10 分钟。最后执行并发 4 的突发及 50 VU 查询与 20 张/分钟上传同时运行的 10 分钟测试。预计约一小时，排空积压会延长执行时间。

持续与混合场景使用每次调用延迟 5 秒的模型替身；入口和字节边界场景使用 0.05 秒。混合场景轮换两张合成 PNG 和一张 16×16 JPEG 协议样本，不代表真实用户照片分布。10 MiB 样本通过在合法 PNG 尾部补零生成，仅验证输入字节上限。

每阶段保留 k6 汇总、HTTP 时间序列、日志及容器/队列采样。失败阈值不会中断后续对照；执行错误或积压无法排空会停止新增压力。HTTP 429 明确计入拒绝，不能算作业务成功。结束后仅清理本轮账号及关联数据、恢复模拟模型参数，并保留容量环境消费者并发 4。账号文件含凭据，不应分享。

前端默认 `/tmp` 为 256 MiB、内存上限为 512 MiB，可用 `FRONTEND_REQUEST_TMPFS_SIZE` 和 `FRONTEND_MEMORY_LIMIT` 调整。这里的旧对照脚本会显式设置消费者并发 2 / 4；当前应用、普通、生产和容量 Compose 默认均为 8 个消费者、32 个未完成任务名额。长测试所需的 180 分钟 JWT 仅配置在容量环境中。脚本不会调用真实供应商模型；真实模型需要另外限定配额、预算和测试范围。

单独运行多样本上传时，`IMAGES_FILE` 为 JSON 数组，每项包含绝对路径 `path` 和 MIME `type`；未设置时仍使用 `IMAGE_FILE`。查询可通过 `MIN_RECORDS` 设置每账号最低记录数（默认 1）。

## 第三轮突发优化与并发 2 / 4 对照

在同一个健康的 `mealcheck-capacity` 模型替身环境中执行：

```powershell
python .\loadtests\run-burst-comparison.py
python .\loadtests\collect-burst-health.py .\loadtests\results\<本轮目录>
python .\loadtests\write-burst-comparison-report.py .\loadtests\results\<本轮目录>
```

脚本先验证 20 张合成图片同时上传，再验证 20 张 10 MiB 字节边界图片、50 张主动过载和 10 张 4096×4096 JPEG。每种消费者并发均重复两次慢模型下的 20 张突发，再执行每分钟 20 张、持续十分钟的上传，以及 50 VU 查询与每分钟 20 张上传同时运行两分钟的混合场景。两组使用同一个后端镜像，仅切换消费者并发；每阶段排空后再继续。入口 20 张验收失败会停止对照并清理，其他阈值失败保留原始结果，执行错误或无法排空会停止新增压力。预计约 45 分钟，积压会延长时间。

解码并发仍为 2，等待位置默认 20，最多等待 5 秒。`MEALCHECK_UPLOAD_MAX_CONCURRENT_DECODES`、`MEALCHECK_UPLOAD_MAX_PENDING_DECODES` 和 `MEALCHECK_UPLOAD_DECODE_WAIT_MILLIS` 可调整这些限制；解码等待与消费者并发是不同参数。Nginx 在接收请求体前限制最多 20 个同时进行的 multipart 请求，普通查询不占名额，配合 256 MiB 临时缓冲；修改缓冲大小时需同时核对这个上限。

后端 multipart 临时文件写入独立的 `mealcheck_upload_tmp` 磁盘卷，目录由 `MEALCHECK_UPLOAD_TMP_DIR` 指定。默认应用预算 `MEALCHECK_UPLOAD_TEMPORARY_MAX_BYTES=1073741824`（1 GiB），解析前最多预留 22 个请求的空间，并检查现有文件及磁盘剩余容量。这是单实例应用准入预算，不是文件系统硬配额。启动清理仅处理专用目录中超过一小时的 `upload_*.tmp`；持久图片仍使用原上传卷。

后端 JVM 默认堆占容器内存的 60%，可通过 `BACKEND_JAVA_TOOL_OPTIONS` 覆盖；容器内存还需容纳线程、元空间、图像处理和缓冲，不能把容器限额都分给 Java 堆。`python loadtests/run-burst-comparison.py --memory-check` 可补跑两档的入口、大图、字节边界和一次突发，并复测并发 4 的十分钟持续与两分钟混合负载；此模式不重复并发 2 的十分钟阶段。健康收集后，可将其证据目录通过 `--memory-evidence` 传给报告生成器，与原完整对照分别列出。

若需要排查本机建连异常，可用 `python loadtests/run-burst-comparison.py --continuous-only`：使用明确的 `127.0.0.1` 地址，在消费者并发 4、60% JVM 堆占比下重新准备独立账号与数据，仅运行预热、十分钟持续上传和两分钟混合负载。其结果通过 `--confirmation-evidence` 附加到报告；既有失败记录不得剔除或并入新成功率。该对照不能单独证明 localhost/DNS 是连接故障的根因。

入口繁忙返回 `429 / UPLOAD_BUSY` 和 `Retry-After`；网页保留相同图片与幂等键，最多自动重试两次。普通账号配额限流和提交结果不确定不自动重交。k6 不自动重试，严格记录拒绝；主动过载、大分辨率饱和场景可允许最多 80% 明确拒绝，但通过 `REQUIRE_ACCEPTED_SUCCESS=true` 要求已接收任务无后台失败，且不得超时、不确定或丢弃迭代。其他场景继续使用默认 99% 成功率和 P95 小于 60 秒目标。

使用 40 个新账号、每账号 1,000 条合成记录。字节边界图片仅在合法 PNG 尾部补零，大分辨率图片由合成样本放大，均不代表真实用户照片分布。旧对照脚本结束只清理本轮账号及关联数据、恢复原模型替身配置，并保留容量环境消费者并发 4；需重新应用当前默认配置恢复至 8。当前生产默认也为 8。本地替身结果不能证明真实模型配额或线上最大容量。结果目录中的账号凭据禁止分享。

## 准备

1. 启动 MealCheck 后端及其 PostgreSQL、Redis 依赖。
2. 登录测试账号，准备餐食记录；空账号只能测到空数据查询。
3. 在已登录的 MealCheck 网页打开 F12 开发者工具，在 Console 中执行：

   ```javascript
   localStorage.getItem('mealcheck_token')
   ```

   将返回的 JWT（不包含外层引号）设置到下面的 `TOKEN` 环境变量。账号及令牌只需准备一次，不在每次循环里重新登录。

4. 在新的 PowerShell 窗口运行 `k6 version`，确认 k6 可用。安装后旧终端可能尚未加载新的 PATH。

## 运行

在项目根目录执行：

```powershell
Set-Location E:\mealcheck-fullstack
$env:BASE_URL = 'http://localhost:8080'
$env:TOKEN = '替换为测试账号的JWT'
k6 run .\loadtests\read.js
```

默认先使用 1 个虚拟用户运行 10 秒，确认服务地址、鉴权和接口均正确。

确认请求返回 200 后，逐步提高负载，每次结束后再运行下一档：

```powershell
k6 run --vus 10 --duration 1m .\loadtests\read.js
k6 run --vus 50 --duration 1m .\loadtests\read.js
```

`--vus` 是虚拟用户数量，`--duration` 是测试时长。每个虚拟用户每次请求后等待 1 秒，因此虚拟用户数量不等于实际 QPS。

保存控制台输出：

```powershell
k6 run --vus 10 --duration 1m .\loadtests\read.js 2>&1 |
  Tee-Object -FilePath .\loadtests\read-result.log
```

## 结果与排错

- `http_reqs`：请求总数及实际每秒请求数。
- `http_req_duration`：请求耗时，重点看 `p(95)`。
- `http_req_failed`：HTTP 请求失败比例。
- `checks`：状态码和 JSON 响应检查结果。
- 401：令牌缺失、失效或账号不可用，重新登录取得 JWT。
- 404：检查服务地址与端口是否指向 MealCheck API。
- 连接失败：检查服务是否启动、端口是否正确。
- 阈值失败：检查是否为接口错误或延迟超标；阈值不代表已经测得的系统容量。

脚本将失败率小于 1%、P95 小于 500ms 作为初始目标。k6 阈值失败时返回非零退出码。应结合测试机器配置和业务要求调整目标。

这份入门脚本使用同一账号。正式容量测试需要多个账号、具有代表性的数据量，以及线程池、数据库连接池、CPU、内存等服务端指标。本脚本不验证图片分析、助手或周报生成的处理能力。

官方参考：[运行 k6](https://grafana.com/docs/k6/latest/get-started/running-k6/)、[阈值](https://grafana.com/docs/k6/latest/using-k6/thresholds/)。

## 图片分析容量测试

`analysis-capacity.js` 每次迭代使用新的幂等键，上传一张图片，再查询该任务直到 `SUCCEEDED`、`FAILED`、`RETRYABLE` 或达到等待上限。成功响应还必须包含已保存的 `recordId`。它不会自动重试失败任务，避免掩盖错误或重复消耗模型调用。

默认使用 `constant-arrival-rate`，每分钟开始 5 次上传，持续 5 分钟。系统变慢时仍按指定速率开始迭代，直到可用虚拟用户不足，此时 `dropped_iterations` 会使测试失败。一次迭代包括上传和等待结果，所以需要为等待中的任务准备足够多的 VU。k6 的 `MAX_VUS` 与后端的 `MEALCHECK_ANALYSIS_CONCURRENCY` 是不同参数。

### 1. 启动隔离的模拟模型环境

下面使用专用 Compose 项目和公开的测试环境配置，数据卷与正常开发环境分开。模型请求发往本地替身。不要使用生产环境文件执行这一组命令。

在项目根目录执行：

```powershell
Set-Location E:\mealcheck-fullstack
$env:MEALCHECK_ANALYSIS_CONCURRENCY = '8'
$env:MODEL_STUB_DELAY_SECONDS = '5'
$env:MODEL_STUB_ERROR_EVERY_N = '0'

docker compose -p mealcheck-capacity --env-file deploy/smoke.env -f docker-compose.prod.yml -f deploy/compose.smoke.yml -f deploy/compose.capacity.yml up --build -d
docker compose -p mealcheck-capacity --env-file deploy/smoke.env -f docker-compose.prod.yml -f deploy/compose.smoke.yml -f deploy/compose.capacity.yml ps
```

等待后端与前端就绪后，通过 `http://localhost:18080` 访问这个专用测试环境。`MODEL_STUB_DELAY_SECONDS=5` 是**每次模型调用**等待 5 秒。常规图片任务包含图片识别和建议生成两次调用，因此总处理时间约 10 秒加上查询、写库等开销，不能把这个参数当成整条任务耗时。

容量测试覆盖配置使用 `capacity` Spring profile，并加载 `application-prod.yml` 保持部署参数一致；本地浏览器的 CORS 允许来源为 `localhost` 和 `127.0.0.1`，端口取自 `MEALCHECK_FRONTEND_PORT`。生产模式的 HTTPS 来源校验仍保留在 `prod` profile 中。

模拟模型还支持 `MODEL_STUB_ERROR_EVERY_N=5` 与 `MODEL_STUB_ERROR_STATUS=429`：每第 5 次调用返回 429。计数包含识别、建议和重试调用；它模拟故障，不等同于供应商真实 RPM/TPM 配额。调整后重新执行同一条 Compose `up -d` 命令应用配置。

### 2. 批量准备测试账号与图片

```powershell
New-Item -ItemType Directory -Force .\loadtests\results | Out-Null
python .\deploy\scripts\capacity_accounts.py --base-url http://localhost:18080 --output .\loadtests\results\accounts.json --count 20
```

此工具会注册 20 个测试账号，并保存 JWT 和清理账号需要的密码。`loadtests/results/` 已被 Git 忽略。已有文件不会被覆盖；中途失败后可以加 `--resume --count 20` 继续创建。

将一张实际餐食 JPEG 放到 `E:\mealcheck-fullstack\loadtests\results\meal.jpg`。图片仍经过后端真实的文件校验，不能用任意文本代替。正式测试需要有代表性的大小、分辨率和餐食类型；这个入门命令会重复上传同一张图片，每次都会产生独立任务。

也可自行准备账号文件，格式为 `[{"token":"账号A的JWT"},{"token":"账号B的JWT"}]`。脚本启动时检查每个账号能否查询餐食列表，过期 JWT 会在上传前阻止测试。账号按新任务编号轮流使用，而非按 VU 固定绑定，减轻少数空闲 VU 重复使用同一账号造成的限流偏差。

### 3. 运行持续上传测试

```powershell
$env:BASE_URL = 'http://localhost:18080'
$env:ACCOUNTS_FILE = 'E:/mealcheck-fullstack/loadtests/results/accounts.json'
$env:IMAGE_FILE = 'E:/mealcheck-fullstack/loadtests/results/meal.jpg'
$env:MODE = 'arrival'
$env:RATE_PER_MINUTE = '5'
$env:DURATION = '5m'
$env:MAX_VUS = '20'
$env:PREALLOCATED_VUS = '10'
$env:TASK_TIMEOUT_SECONDS = '180'
$env:E2E_P95_MS = '60000'
$env:RESULT_FILE = 'loadtests/results/analysis-c2-r5.json'
k6 run .\loadtests\analysis-capacity.js
$LASTEXITCODE
```

每轮自动生成共享的运行标识，保证不同 VU、不同迭代和不同轮次的任务键不同。也可设置 8 到 48 位字母、数字、下划线或短横线组成的 `RUN_ID`，但不同轮次必须更换。

依次测试每分钟 5、10、20 张，每档开始前修改 `RATE_PER_MINUTE` 和 `RESULT_FILE`。停止新增请求后，脚本会继续等待正在进行的任务，最长约为 `TASK_TIMEOUT_SECONDS` 加上少量网络与调度余量。等待上限包含上传时间。

达到超时或观察失败后，任务仍可能留在数据库或队列中继续执行；先确认积压已经清空、失败任务已处理，再开始下一轮。k6 没有取消这些后台任务。

### 4. 比较消费者并发与突发流量

修改 `$env:MEALCHECK_ANALYSIS_CONCURRENCY = '4'` 或 `'8'`，再次执行上述 Compose `up -d` 命令更新后端，然后重复同一组速率。只修改 k6 环境变量不能更改已运行的后端。

模拟短时间提交 20 张图片：

```powershell
$env:MODE = 'burst'
$env:BURST_TASKS = '20'
$env:MAX_VUS = '20'
$env:RESULT_FILE = 'loadtests/results/analysis-burst20.json'
k6 run .\loadtests\analysis-capacity.js
```

当 `MAX_VUS >= BURST_TASKS` 时，任务在测试开始后尽快提交。若 VU 更少，后续任务需等待前面迭代完成，不再代表全部任务同时提交。

后端保留真实的每账号上传限流。账号过少时 `analysis_submit_429` 可能来自用户限流，并不能证明 RabbitMQ 或消费者容量不足。模拟模型环境放宽的是注册和验证码限流，图片上传限流保持原有配置。

### 5. 解读结果

终端和 JSON 的 `capacity` 部分输出以下指标，JSON 的 `k6` 部分保留完整原始汇总：

| 字段 | 含义 |
| --- | --- |
| `attempts` / `accepted` | 实际开始上传 / 收到有效任务响应的数量 |
| `succeeded` / `failed` / `rejected` | 成功 / 后台终态失败或可重试 / 上传被拒绝 |
| `timedOut` | 等待上限内没有观察到终态 |
| `unresolved` / `unknownSubmissions` | 无法确认结果 / 上传超时、5xx 等造成提交结果不确定；后者是前者的子集 |
| `unfinished` | 已开始但没有记录结束分类，可能被 k6 强制中断 |
| `droppedIterations` | 没能开始的计划迭代，通常需增加压测端 VU 或检查持续积压 |
| `submit429` / `pollErrors` | 上传限流数量 / 查询过程出现的错误数量 |
| `uploadBusy` / `acceptedUploadMs` | 明确入口繁忙数量 / 有效接收上传响应的耗时分布 |
| `successFraction` | 成功数除以实际上传次数，包含拒绝和超时的影响 |
| `successfulE2eMs` | 成功任务从上传开始到观察到结果的耗时分布，包括 P95 与 P99 |
| `passed` / `failedThresholds` | 本轮是否通过以及失败阈值 |

`attempts` 应等于 `succeeded + failed + rejected + timedOut + unresolved + unfinished`。`accepted` 是提交时确认收到有效响应的数量，无法确认提交的请求可能已经在服务器入队，不能简单视为未创建任务。

默认目标：成功率至少 99%、至少 1 个成功样本、成功任务 P95 小于 60 秒、无超时、无观察结果不确定、无中断和丢弃迭代。这些是起始测试目标，调整到产品实际允许的等待时间。没有成功样本时不会将空耗时分布当作测试通过。

只看成功任务 P95 会漏掉失败和超时，因此必须同时看数量与 `passed`。k6 正常结束且阈值失败会返回非零退出码；手工终止、设置外部 `--duration`/`--vus` 覆盖或强制关闭进程可能破坏测试与汇总，不应作为有效容量证据。自动化还应检查 JSON 中的 `capacity.passed`。

端到端耗时含轮询延迟，不能精确分解排队时间。当前接口不暴露任务创建/开始时间，需同时观察 RabbitMQ Ready/Unacked、数据库待处理任务/Outbox、后端 CPU/内存/连接池及任务处理时长。RabbitMQ Ready 为零也可能仍有 Outbox 未发布的任务。

### 可配置参数

| 参数 | 默认值 | 作用 |
| --- | --- | --- |
| `MODE` | `arrival` | `arrival` 持续上传，`burst` 固定数量突发 |
| `RATE_PER_MINUTE` / `DURATION` | `5` / `5m` | 持续上传速率和新增任务时长；时长支持 `s`、`m`、`h` |
| `BURST_TASKS` | `20` | 突发模式任务总数 |
| `MAX_VUS` / `PREALLOCATED_VUS` | `20` / `10`（不超过 MAX_VUS） | 最大/预分配虚拟用户 |
| `TASK_TIMEOUT_SECONDS` / `HTTP_TIMEOUT_SECONDS` | `180` / `15` | 整条任务观察上限/单次 HTTP 超时 |
| `POLL_SECONDS` | `3` | 查询间隔，低间隔会增加数据库读压力 |
| `E2E_P95_MS` / `MAX_FAILURE_RATE` / `MIN_COMPLETED` | `60000` / `0.01` / `1` | 耗时目标/最大失败比例/最低成功样本数 |
| `REQUIRE_ACCEPTED_SUCCESS` | `false` | `true` 时，已接收任务有后台失败也会使验收失败，适用于允许入口拒绝的饱和用例 |
| `IMAGE_TYPE` / `GOAL` | 按扩展名推断 / `current` | 图片 MIME/分析目标 |
| `RESULT_FILE` | `loadtests/results/analysis-capacity.json` | JSON 结果路径，父目录需已存在 |

### 真实模型与清理

模拟环境用于测基础设施、排队和故障处理，无法证明模型供应商的实际吞吐量。真实模型阶段换用实际测试后端的 `BASE_URL` 和对应账号 JWT，并控制测试时长、图片数和调用预算。不要把专用测试密码或放宽注册限流的配置用于正式环境。

全部后台任务结束后，可以删除工具创建的测试账号及其关联数据：

```powershell
python .\deploy\scripts\capacity_accounts.py --base-url http://localhost:18080 --output .\loadtests\results\accounts.json --cleanup
docker compose -p mealcheck-capacity --env-file deploy/smoke.env -f docker-compose.prod.yml -f deploy/compose.smoke.yml -f deploy/compose.capacity.yml down
```

`down` 保留专用数据卷；上面的账号清理会删除这些测试账号的数据。手工准备且没有密码字段的 JWT 文件不能使用该清理工具。

## 混合流程与脚本验证

### 内存、消费者与恢复复测

`run-capacity-next.py` 在专用 `mealcheck-capacity` / 私有模型桩环境运行，并拒绝真实模型地址。默认 JVM 堆上限 45%、解码等待 8 秒；可通过 `--jvm`、`--decode-wait-ms` 显式覆盖。`--fresh` 重建后端，用于比较同样的冷启动条件。每次运行保存参数、镜像、k6 汇总、JVM/cgroup 采样和专用账号清理核对。

```powershell
python .\loadtests\run-capacity-next.py --mode memory --label memory-repeat --consumers 4 --fresh
python .\loadtests\run-capacity-next.py --mode sweep --label c4-c6 --consumers 4 6 --rates 20 30 40 --duration 900s --fresh
python .\loadtests\run-capacity-next.py --mode mixed-ai --label ai-smoke --consumers 4 --rates 5 --duration 120s --weekly-tasks 2 --assistant-rate 2 --fresh
python .\loadtests\run-capacity-next.py --mode mixed-ai --label c8-mixed-30m --consumers 8 --rates 30 --duration 1800s --weekly-tasks 40 --assistant-rate 12 --fresh
python .\loadtests\run-capacity-next.py --mode soak --label c8-soak-1h --consumers 8 --rates 30 --duration 3600s --fresh
python .\loadtests\run-capacity-faults.py
python .\loadtests\run-capacity-lease-smoke.py
```

持续模式先对每个消费者配置执行三轮 20 任务突发，再逐档加压；一档阈值失败后停止升高该配置的速率。持续观察默认预分配/最大 160 VU，避免 40 次/分钟、180 秒观察期限先耗尽压测客户端；可用 `--max-vus` 修改，或用 `--skip-bursts` 只复核持续档位。`mixed-ai` 使用 80 个专用账号，各 1000 条记录，其中 40 个上传、40 个生成周报，避免上传同账号餐食使周报正常失效（`STALE`）干扰生成容量。助手和 50 VU 查询同时竞争服务资源。`soak` 为查询＋上传，不增加助手或周报场景。

故障脚本在该隔离环境注入执行中后端重启、RabbitMQ 中断、模型 429 与超时；核对物理模型调用、重复记录、重放及数据库中的死信归档标记。它会创建并删除自身专用账号，不清空数据库或 RabbitMQ 队列。测试账号凭据文件在成功清理后删除，失败时应先排障再清理。

`run-capacity-lease-smoke.py` 要求默认 90 秒执行租约，让一次正常图片任务的两次模型调用各延迟 50 秒，检查超过租约长度后仍只执行一次、物理调用两次，且有有效续租记录；只清理自己创建的一个账号。

`summarize-capacity-memory.py <运行目录>` 汇总内存/GC；`summarize-capacity-run.py <运行目录>` 汇总验收、CPU、队列及每个容器的内核内存峰值；`summarize-capacity-stability.py <运行目录>` 按十分钟区间汇总工作集、匿名内存、文件缓存、GC 后存活数据和累计暂停时间。内核峰值累计到容器销毁，比较时按容器 ID 区分；数据库 `pending` 同时包含执行中任务，不能单独将非零值视为积压。最终结论以 Markdown 报告和各阶段 JSON 为准。

本轮记录：[2026-10-07 优化报告](E:/mealcheck-fullstack/loadtests/reports/k6-optimization-20261007.md)。

本轮 4/6/8 比较、30 分钟混合负载和独立 1 小时稳定性验收后，`compose.capacity.yml` 的图片消费者默认值为 8。2026-10-09 起，普通 Compose、生产 Compose、应用与环境变量示例也统一为 8 个消费者、32 个未完成任务名额；模型桩、测试凭据及测试专用预算仍属于隔离测试配置。显式环境变量优先于默认值，复测不同并发时务必核对已运行后端配置。8 是当前模型桩、资源和负载下的选择，真实供应商仍需按预算验证，不能据此认定继续增加消费者一定提高吞吐量。

`mixed.js` 默认继续使用每 VU 一个固定任务键，检查幂等重放。它只查询一次任务状态，不能测完整图片耗时。`NEW_TASKS=true` 会让每次迭代创建新图片任务，但没有固定上传速率控制，容易触发每账号限流；容量评估应使用 `analysis-capacity.js`。

运行本地脚本验证：

```powershell
python .\loadtests\verify_analysis_capacity.py
```

验证器实际执行 k6，通过只绑定 `127.0.0.1` 的临时 HTTP 接口检查任务键唯一性、账号轮换、成功、失败、可重试、上传限流、提交不确定、响应缺字段、超时、临时轮询错误恢复、丢弃迭代和鉴权失败。此验证不连接真实后端、RabbitMQ 或模型，因此不提供项目容量结论。

官方参考：[固定到达率](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/)、[上传文件](https://grafana.com/docs/k6/latest/examples/data-uploads/)、[自定义汇总](https://grafana.com/docs/k6/latest/results-output/end-of-test/custom-summary/)。
