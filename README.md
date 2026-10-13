# MealCheck Fullstack

## 当前部署：RAG 知识库 100 条，数据库 V31（2026-10-13）

源码知识库更新为 `v3-curated-100`：保留原 26 个主题，补充 74 个独立条目，覆盖主食与蛋白质、蔬菜水果、饮品与标签、外食预算、食品安全和记录复盘。每条附公开资料来源，校园实践示例为应用建议，不是个体诊断或营养处方。

文档首行 `<!-- mealcheck:independent-topics -->` 保留条目边界，不合并短主题，也不在主题之间追加无关重叠内容；单个长主题仍按最大 Token 限制切分。默认配置下 100 个主题对应 100 个系统知识片段，管理员手动条目另计。来源内容同步会替换旧的系统片段，保留 `source=admin` 条目。

本轮已更新当前本地 Docker 后端。实际数据库为 `diet_guides.md` 来源的 100 条、100 个独立标题和内容哈希，分块 166–212 Token；旧系统 19 条已通过来源同步替换。原有 3 个账户、34 条餐食保留，临时验收账户已清理，五个容器健康。

- 后端完整回归：272 项单元 / HTTP 测试、99 项数据库集成测试通过，无失败、无跳过。日志 `backend/target/rag100-backend-verification.log`。
- 运行中验收：19 项检查通过，包括普通用户权限、管理员总数、五页完整分页、唯一标题及内容、来源版本和四项新增知识检索。结果 `deploy/release-results/rag100-20261013/live-smoke-final/verification.json`。
- 升级前联合备份已隔离恢复通过：44 张表、34 个图片引用、35 个文件、1 个排队图片。证据 `deploy/recovery-results/rag100-20261013/pre-index-restored/verification.json`。
- 当前后端镜像保留为 `mealcheck-local-backend:20261013-rag100`（`sha256:8e19dd1d035dc7b65ff9b1f9e2f7b9406f3bc91595a13a3935db694a47267f8c`）；原镜像另保留为 `mealcheck-local-backend:pre-rag100-20261013`。镜像内知识正文哈希与源码一致。
- 沿用当前 `docker-compose.yml` 和 `mealcheck-fullstack` 项目；仅更新后端，前端及数据卷保留。当前来源版本无 `.env` 覆盖，使用新默认值。
- 本地检索使用开发用 Hash 向量，无真实嵌入或重排密钥。真实模型回答质量、费用与正式发布基线未在本轮验收。

本地无真实嵌入 / 重排密钥时，可重新验证（使用新的输出目录）：

```powershell
python deploy/scripts/verify_rag_knowledge.py --output deploy/release-results/your-rag-verification
```

以下为扩展前的历史部署证据。

## 历史部署：七项代码优化，数据库 V31（2026-10-13）

已完成本轮七项优化，并更新到**本地 Docker**。访问 [http://127.0.0.1:5173](http://127.0.0.1:5173)。继续使用 `mealcheck-fullstack` 项目和原持久卷，保留 3 个账户、34 条餐食、34 个图片引用及 35 个上传文件；五个容器均健康，仅绑定本机回环地址。以下 V28 / V27 部署记录为历史证据。

### 优化结果

1. 数据成功变更后统一通知目标、计划、趋势和当前周报，使相关缓存立即失效；失败请求保持原缓存。周报任务完成也刷新历史列表。
2. 目标与计划返回分页结果及总数，前端支持翻页。目标修改直接按本人 ID 查询，超过原 100 项限制的旧目标可编辑；计划超过 500 项仍能完整查询。
3. 手动计划保存后清空表单并支持连续创建；同一建议保存后的防重复按钮保持。
4. 助手期限包含排队时间，排队阶段立即提供请求 ID，支持取消。取消排队任务会释放队列容量；运行任务清理结束后再允许同一用户提交。
5. 覆盖率和目标进度复用一次按日期、餐次聚合的结果，使用前缀计数计算历史达标天数，查询次数不随目标数量增加。
6. 餐食变化按受影响日期使相关周报过期，保留历史原文。计划、行为目标及备注的修改保留正文，并实时更新当前进度；增加历史周报分页与只读快照入口。当前报告不再使用旧周期快照。
7. 后端拆分目标、计划、统计服务与查询 Repository；独立 DTO、明确接口类型、构造器注入和输入验证，控制器不直接操作 SQL。

详细接口、统计、缓存、任务状态和限制见 [代码优化说明](deploy/CODE_OPTIMIZATIONS.md)。份量及营养估算仍可选、默认关闭，明确提示不准确，不作为目标达标依据。**真实 AI 助手测试继续暂缓**；任务与流式测试使用本地模型桩，生产验收只调用无密钥的本地规则回复。

### 实际部署版本

- 后端：`mealcheck-local-backend:20261013-journal-optimized-v31-verified`，内容 ID `sha256:2def3b697b9d9c4cd24dc6d109d7c3116dd83b5325b73c2aa70b19aa66d3a13d`。
- 前端：`mealcheck-local-frontend:20261013-journal-optimized-v31-verified`，内容 ID `sha256:94cd2cf2e3aa06cee491a9449437beebd7226087611999921cb9515bda1c6b08`。
- Flyway 从 V28 升级至 V31。V29 为本轮历史周报过期字段；工作区既有 V30 失败任务引用与 V31 查询索引迁移一并保留并验证，V31 的 `.sql.conf` 配置非事务并发索引创建。
- `.env` 的两个本地镜像引用已更新。镜像来自未提交工作区，没有创建正式发布提交或推送；实际容器、数据库、源码和打包迁移由证据清单核验。
- Docker Desktop 启动时再次出现孤立套接字。停止进程并核对路径后，仅重命名保存运行目录及真实套接字目录，再启动引擎；未重置 Docker、删除数据卷或改动其他项目。保存目录后缀为 `.stale-20261013-1424`、`1427`、`1429`，最终恢复使用 `1429`；Codex 的目录虚拟化路径也已核实。

### 验证结果

- 后端完整回归：270 项单元 / HTTP 测试与 99 项 PostgreSQL 集成测试全部通过，无失败、无跳过，日志 `backend/target/optimization-final-verification.log`。随后针对提交时请求 ID、报告周期与可空历史时间兼容修复，相关单元测试及全部 12 项餐食业务集成测试再次通过，日志 `optimization-final-boundaries.log`、`optimization-null-history-verification.log`。Surefire 统计排除旧的集成测试 XML，集成结果以 Failsafe 为准。
- 前端最终回归：30 项单元测试、生产镜像构建和 67 项浏览器测试全部通过，日志 `frontend/test-optimization-unit-final.log`、`frontend/test-optimization-browser-verified.log`、`deploy/optimization-frontend-verified-build.log`。
- 运行中验收：51 项检查通过，包括真实 API、生产 Nginx、目标 106 项 / 计划 602 项的分页、旧目标按 ID 编辑、局部过期及历史原文保留。手机页面另有 9 项业务检查和 8 项营养界面检查，验证连续创建两条计划、分页总数更新、返回已缓存周报时执行进度刷新，以及历史快照弹窗。
- 所有运行中验收使用新注册的临时账户。结束后账户、合成餐食和图片已清理，原账户与餐食数量保持。初次验收暴露的历史生成时间为空错误已修复，并增加集成回归；初次 Node 单元测试中的导入扩展名问题也已修复。早期失败日志保留，最终通过证据以 `live-smoke-final-06` 为准。
- 升级前和部署后联合备份均已在隔离容器恢复验证：44 张表、34 个图片引用、35 个文件，源数据未修改。部署后演练耗时 24.36 秒。

| 证据路径（Git 忽略目录） | 内容 |
| --- | --- |
| `deploy/recovery-results/optimization-20261013/pre-deployment` | 升级前 V28 数据库与图片联合备份 |
| `deploy/recovery-results/optimization-20261013/pre-deployment-restored/verification.json` | 升级前隔离恢复通过 |
| `deploy/release-results/optimization-20261013/live-smoke-final-06/verification.json` | 最终 51 项部署验收及清理结果 |
| `deploy/release-results/optimization-20261013/live-smoke-final-06/journal-browser/` | 9 项真实手机页面验收及截图 |
| `deploy/recovery-results/optimization-20261013/post-deployment` | 最终 V31 数据库与图片联合备份 |
| `deploy/recovery-results/optimization-20261013/post-deployment-restored/verification.json` | V31 隔离恢复通过 |
| `deploy/release-results/optimization-20261013/evidence/manifest.json` | 实际镜像、健康、端口、数据库版本、打包迁移及源码哈希 |

### 复现本轮部署

在项目根目录执行 PowerShell。备份、恢复和验收输出目录使用新名称，避免覆盖证据；`.env` 的密钥不写入文档。

```powershell
python deploy/scripts/recovery.py backup --project mealcheck-fullstack --env-file .env --compose docker-compose.yml --compose deploy/compose.local.yml --output deploy/recovery-results/your-upgrade/pre-deployment
python deploy/scripts/recovery.py drill --backup deploy/recovery-results/your-upgrade/pre-deployment --output deploy/recovery-results/your-upgrade/pre-deployment-restored

docker build -t mealcheck-local-backend:20261013-journal-optimized-v31-verified -f backend/Dockerfile backend
docker build -t mealcheck-local-frontend:20261013-journal-optimized-v31-verified -f frontend/Dockerfile frontend
# 确认 .env 中两个本地镜像引用为上述标签。
docker compose -p mealcheck-fullstack --env-file .env -f docker-compose.yml -f deploy/compose.local.yml up -d --no-build --wait --wait-timeout 180 backend frontend

# 浏览器验收需要已安装 frontend 依赖和本机 Chromium / Chrome。
$env:PLAYWRIGHT_CHROMIUM_EXECUTABLE='C:\Program Files\Google\Chrome\Application\chrome.exe'
python deploy/scripts/verify_local_deployment.py --output deploy/release-results/your-upgrade/live-smoke --browser
```

Flyway 在后端启动时自动迁移；遇到失败先检查日志，不删除迁移历史。外部客户端的目标 / 计划 GET 响应须由数组改为解析分页 `content`，详见接口说明。需要回退数据时，使用升级前数据库和图片联合备份；旧镜像配合新库不视为完整回滚。助手任务注册仍限单个后端实例，多实例需另行设计路由或共享状态。

## 历史部署：V28 餐食记录、行为目标与行动计划（2026-10-11）

已完成本轮五项功能并更新到**本地 Docker**。访问 [http://127.0.0.1:5173](http://127.0.0.1:5173)。保留 `mealcheck-fullstack` 项目、原有持久卷、3 个账户和 34 条餐食；五个容器均为 healthy，仅绑定本机回环地址。以下 V27 记录保留为历史证据。

### 新增功能

1. 餐食记录增加实际用餐时间、早餐 / 午餐 / 晚餐 / 加餐、手动记录、补记与修改。趋势、筛选与周报按实际用餐日期统计，创建时间独立保留；历史及图片记录在未确认前暂沿用创建时间。
2. 行为目标支持每周记录天数和每日不同餐次数，显示进度、剩余数量与每日历史达标天数。同一餐次多条记录只计一次；未指定餐次不计每日目标。营养估算保持默认关闭，提示不准确，不参与达标判断。
3. 餐食建议、完整助手回答与周报建议可保存为计划，支持完成打卡、跳过、重新打开及调整内容 / 日期 / 备注，并保留建议来源和报告周期。
4. 趋势和周报增加记录覆盖率、目标进度、计划执行汇总，区分“未记录，情况未知”与主动确认的“未吃”，提示样本不足和实际统计范围。
5. 助手增加 SSE 流式回复、取消等待、回答评价、问题类型和请求 ID。未完成回答在历史中标为取消或异常，不作为完整回答保存为计划。

入口、统计定义、接口、并发控制和迁移说明见 [V28 功能说明](deploy/MEAL_JOURNAL.md)。**真实 AI 助手测试按要求暂缓**；已使用本地模型桩验证流式、取消及超时，当前本地服务没有真实 AI 密钥，使用本地规则建议。

### 本轮运行版本与复现部署

- 后端：`mealcheck-local-backend:20261011-v28-final`，内容 ID `sha256:62808da716852f76713fb0a86a0b5023e7a0bb57165546d6a87a38d2361a6433`。
- 前端：`mealcheck-local-frontend:20261011-v28-final`，内容 ID `sha256:9928019125435b7d09cc906e67551924faba1d0cd7199bfded1043fba5de7bd6`。
- Flyway 已从 V27 升级到 V28，保留既有餐食和历史周报。V27 镜像与升级前联合备份保留。镜像来自包含未提交修改的工作区，没有提交或推送为正式发布。
- 本轮再次遇到 Docker Desktop 4.73.0 的孤立 Windows 套接字启动问题。停止 Desktop 后检查运行目录，仅将含已知不可访问套接字的 `Docker/run` 与 `docker-secrets-engine` 重命名保存为 `.stale-20261011-2128`，随后恢复引擎；没有重置 Docker 或删除数据卷。

在项目根目录执行以下 PowerShell 命令；`.env` 中的本地镜像引用已更新为上述标签，密钥不写入 README：

```powershell
# 升级前先备份，输出目录使用新的名称，避免覆盖已有证据。
python deploy/scripts/recovery.py backup --project mealcheck-fullstack --env-file .env --compose docker-compose.yml --compose deploy/compose.local.yml --output deploy/recovery-results/your-upgrade/pre-deployment
python deploy/scripts/recovery.py drill --backup deploy/recovery-results/your-upgrade/pre-deployment --output deploy/recovery-results/your-upgrade/pre-deployment-restored

# 为新改动选择新的镜像标签；不要覆盖需要保留的旧版本。
docker build -t mealcheck-local-backend:your-new-version -f backend/Dockerfile backend
docker build -t mealcheck-local-frontend:your-new-version -f frontend/Dockerfile frontend
# 更新 .env 中 MEALCHECK_LOCAL_BACKEND_IMAGE / MEALCHECK_LOCAL_FRONTEND_IMAGE 后启动。
docker compose -p mealcheck-fullstack --env-file .env -f docker-compose.yml -f deploy/compose.local.yml up -d --no-build --wait --wait-timeout 180 backend frontend
```

Flyway 在后端启动时自动迁移，Nginx 已关闭 API 响应缓冲，以便 SSE 内容即时到达。V28 数据结构增加字段与四张业务表，旧镜像直接配合新库不应视为完整回滚；需要回退数据时使用升级前数据库和图片联合备份。

### 验证与恢复证据

- 后端：完整后端回归为 262 项单元 / HTTP 测试 + 87 项隔离 PostgreSQL 集成测试，全部通过。收尾修复取消回答的记忆游标后，再次运行全部 263 项单元 / HTTP 测试和 8 项新增业务集成测试，全部通过，无失败、无跳过；累计验证 **350 项不同测试**。日志 `backend/target/journal-final-verification.log` 与 `backend/target/journal-memory-final-verification.log`；统计时排除 surefire 中旧运行遗留的集成测试 XML，集成测试结果以 failsafe 为准。
- 前端：30 项单元测试、生产构建、61 项浏览器测试通过。之后针对反馈按需加载与计划筛选的最后优化，新增功能及助手历史 6 项相关浏览器测试再次通过。日志 `frontend/test-journal-{unit-final,build-final,browser-final,refinement}.log`。
- 运行中联合验收：**42 项检查通过**，包含真实 API、生产 Nginx 和手机页面。营养界面另有 8 项检查，新增记录 / 目标 / 计划 / 助手页面另有 5 项检查。使用新注册的临时账户和合成数据，结束后账户、记录和图片已清理，原有 3 个账户、34 条餐食数量保持。

| 本轮证据路径（Git 忽略目录） | 用途 |
| --- | --- |
| `deploy/recovery-results/deployment-20261011-v28/pre-deployment` | V27 数据库与图片联合备份 |
| `deploy/recovery-results/deployment-20261011-v28/pre-deployment-restored/verification.json` | 升级前隔离恢复通过：40 张表、34 个图片引用、35 个文件，源数据未修改 |
| `deploy/release-results/deployment-20261011-v28/live-smoke-final-02/verification.json` | 42 项部署验收与清理结果 |
| `deploy/release-results/deployment-20261011-v28/live-smoke-final-02/journal-browser/` | 生产手机页面验证及截图 |
| `deploy/recovery-results/deployment-20261011-v28/post-deployment` | V28 部署后联合备份 |
| `deploy/recovery-results/deployment-20261011-v28/post-deployment-restored/verification.json` | V28 隔离恢复通过：44 张表、34 个图片引用、35 个文件，源数据未修改 |
| `deploy/release-results/deployment-20261011-v28/evidence/manifest.json` | 实际镜像、健康、端口、数据库版本、打包迁移与源码哈希证据 |

备份与验证目录包含私人数据，保持 Git 忽略状态。可重复运行 `verify_local_deployment.py --browser` 验证，但应使用新的输出目录；脚本在调用本地助手前确认真实 AI 密钥为空，避免产生模型费用。

## 历史部署：V27 本地 Docker 部署与功能验收（2026-10-11）

本轮部署目标是 **本机 Docker**，沿用 `mealcheck-fullstack` 项目与原有持久卷。前端入口为
[http://127.0.0.1:5173](http://127.0.0.1:5173)，后端为 `http://127.0.0.1:8080`。
2026-10-10 完成 V26 部署后曾暂停；2026-10-11 已继续完成 V27 升级与新增功能修复。前端、后端、PostgreSQL、Redis、RabbitMQ 五个容器均为 `healthy`，原有 3 个账户、34 条餐食保留。

### 已部署内容与版本

- 本轮镜像包含当前工作区的多轮修改：执行期限及重试、AI 预算与并发准入、图片存储抽象及维护任务、周报批量调度、前端异步任务进度，以及默认关闭的可选份量和营养粗略估算。
- 营养功能需要用户填写份量与每 100 克参考值，明确提示“不准确，仅供参考”；数据不完整时不显示整餐总量，不参与结构评分和周报判断。详见 [功能说明](deploy/OPTIONAL_NUTRITION.md)。
- 实际运行后端：`mealcheck-local-backend:20261010-v27`，内容 ID：`sha256:33a76cce80f5b0dc8cecf0518b5b29c320c5fd6f6c29213bd57f489adb3e8b83`。
- 实际运行前端：`mealcheck-local-frontend:20261011-v27-nutrition-fix`，内容 ID：`sha256:20b52f49a94f0b2108cc4c53339322e8df9033a1dd3938fdebce99017a38a81d`。
- V26 镜像继续保留为 `mealcheck-local-backend:20261010-v26` 和 `mealcheck-local-frontend:20261010-v26`，没有覆盖其内容。
- 本次构建使用包含未提交及新增文件的工作区，Git 基础提交为 `20f68e758003c853953e6dda5993c25446a81cd2`。没有提交或推送代码、镜像，不能将其视为已完成 [正式发布基线验收](deploy/RELEASE_BASELINE.md) 的版本。

### 数据库与图片恢复

原数据库为 V22，保存 3 个账户、34 条餐食。两次部署中 Flyway 已成功应用 V23–V27：全站 AI 准入、持久化助手评测任务、图片引用与周报调度、可选营养快照，以及遗留重复外键修复。原有账户和餐食数量保留。

部署前发现旧上传卷为空，首次备份因此失败并保留失败记录。随后从 `backend/uploads` 找回原始图片，经数据库引用及文件哈希校验后恢复到原上传卷，并修复容器运行用户的目录权限。
隔离恢复演练验证了 35 张表、34 个图片引用、35 个文件，源数据库未被演练修改。

备份与验收证据保存在 Git 忽略目录，包含私人数据，不应提交仓库：

| 路径 | 状态与用途 |
| --- | --- |
| `deploy/recovery-results/deployment-20261010-v26/pre-deployment` | 首次失败记录，不可作为完整备份使用 |
| `deploy/recovery-results/deployment-20261010-v26/pre-deployment-local-files` | V22 数据库与找回的本地原图联合备份 |
| `deploy/recovery-results/deployment-20261010-v26/pre-deployment-restored/verification.json` | 上述备份的隔离恢复演练通过记录 |
| `deploy/recovery-results/deployment-20261010-v26/pre-deployment-volume-verified` | 图片恢复入卷后重新完成的联合备份 |
| `deploy/recovery-results/deployment-20261010-v27/pre-v27` | V27 应用前的 V26 联合备份；备份完成后已自动恢复后端运行 |
| `deploy/recovery-results/deployment-20261011-v27/pre-v27-restored/verification.json` | V26 备份的隔离恢复演练通过：40 张表、34 个图片引用、35 个文件 |
| `deploy/recovery-results/deployment-20261011-v27/post-deployment` | 最终 V27 数据库与上传卷联合备份 |
| `deploy/recovery-results/deployment-20261011-v27/post-deployment-restored/verification.json` | V27 备份的隔离恢复演练通过：40 张表、34 个图片引用、35 个文件，未修改原库 |

旧镜像另保留为 `mealcheck-local-backend:pre-20261010` 和 `mealcheck-local-frontend:pre-20261010`。
旧应用与升级后数据库的兼容性尚未验收；保留镜像不代表直接切换即可安全回退。

### 构建、启动与维护命令

本地使用 [deploy/compose.local.yml](deploy/compose.local.yml) 覆盖端口为 `127.0.0.1`，需要支持 `!override` 的 Compose 2.24.4+。
本轮使用 Compose 5.1.3；数据库、缓存及消息队列使用开发配置，适用于本机环境。
`.env` 已在本地创建并被 Git 忽略，业务时区为 `Asia/Shanghai`，时间存储口径为 `UTC`，当前镜像变量指向上述 V27 后端与修复后的前端标签。

以下是本轮执行过的部署方式，在仓库根目录运行：

```powershell
# 仅在准备新版本时构建；先将 .env 镜像变量改为新的独立标签
docker compose --env-file .env -p mealcheck-fullstack -f docker-compose.yml -f deploy/compose.local.yml --profile fullstack build backend frontend

# 后端先启动，等待依赖健康与自动迁移完成
docker compose --env-file .env -p mealcheck-fullstack -f docker-compose.yml -f deploy/compose.local.yml --profile fullstack up -d --no-build --wait --wait-timeout 120 backend

# 启动前端
docker compose --env-file .env -p mealcheck-fullstack -f docker-compose.yml -f deploy/compose.local.yml --profile fullstack up -d --no-build --wait --wait-timeout 60 frontend

# 查看状态和后端日志
docker compose --env-file .env -p mealcheck-fullstack -f docker-compose.yml -f deploy/compose.local.yml --profile fullstack ps
docker compose --env-file .env -p mealcheck-fullstack -f docker-compose.yml -f deploy/compose.local.yml logs --tail 100 backend
```

升级时先备份，再构建并记录新的独立镜像标签、核对内容 ID 后启动；不要覆盖已验收标签。
保持项目名一致以复用原卷，勿用 `down -v` 删除持久化数据。

### 新增功能检查、优化与修复

1. **旧数据库注销失败**：实际旧库在 V6 删除策略约束旁保留了 Hibernate 创建的 `NO ACTION` 外键，会阻止账户注销。V27 只移除与有效 V6 约束具有相同表、列和引用列的重复限制约束，保留级联、审计置空和其他外键，不改动业务行。回归测试先升级到 V5 并添加遗留约束，再升到 V26，真实复现约束触发顺序；验证升级前失败、V27 后正常删除、营养快照级联及审计保留。当前真实账户注销接口已通过。
2. **空餐食仍可开启估算**：已禁用没有食物项时的开启入口，并提示先纠正识别结果，避免提交后才报错。已开启的失效设置仍能关闭清理。
3. **不完整数据定位**：保存不完整资料时新增“已具备完整估算资料：X / Y 项食物”，帮助用户补全资料；未知值继续保留为空，整餐总量仍不显示。
4. **真实页面验收**：新增 [移动端验收脚本](deploy/scripts/verify_local_browser.mjs)，通过真实前端容器与真实 API 验证默认关闭、不准确提示、保存、重开读取、关闭清理和空食物入口；不拦截或模拟 API。页面截图只包含本轮临时账户的合成数据。

### 最终验收结果与证据

| 检查 | 最终结果 | 证据 |
| --- | --- | --- |
| 后端完整 `mvn -o verify -Pintegration` | 259 项单元/HTTP、79 项集成测试通过，0 失败/跳过 | `backend/target/deployment-20261011-final-verification.log` |
| 前端单元与生产构建 | 30 项测试通过，构建成功 | `frontend/test-deployment-20261011-unit-final.log`、`frontend/test-deployment-20261011-build-final.log` |
| 前端浏览器回归 | 57 项通过，包括空餐食新用例、异步进度与预算提示 | `frontend/test-deployment-20261011-browser-final.log` |
| 实际部署联合验收 | 25 项检查通过，包含真实移动端流程 | `deploy/release-results/deployment-20261011-v27/live-smoke-final-02/verification.json` |
| V26 与 V27 备份恢复 | 均通过，数据库与图片一致，演练未修改原数据 | 上表中的 `pre-v27-restored`、`post-deployment-restored` |
| 源码、运行镜像与证据绑定 | 工作区文件 SHA-256、运行镜像内容 ID、实际 JAR 内迁移文件比对 | `deploy/release-results/deployment-20261011-v27/evidence/manifest.json` 及 `manifest.sha256` |

真实验收覆盖 Nginx 页面与资源、Redis 验证码、注册与认证、数据库/缓存/上传目录健康、预算查询、管理接口隔离、图片读取、默认关闭、仅份量保存不生成总量、负数拒绝、来源校验、完整换算、显式零值、版本冲突、真实纠错后估算失效及账户注销清理。临时数据会自动清理；合成食物与营养值不代表真实模型质量或真实食品数据。

前序失败证据继续保留：旧库回归测试的初版必填标题遗漏及约束顺序不准确均已修正；本轮 Docker 未启动时的集成失败、受限运行导致的浏览器失败也没有被覆盖。以上最终记录才是本次完成部署的通过依据。

### 复验与备份命令

需要 Python 3.11+、Node/npm、已安装前端依赖和 Docker。每次使用新的输出目录，脚本拒绝覆盖既有验收与备份目录。

```powershell
# 联合验收：真实 HTTP + 390px 移动端页面，仅创建并清理临时账户/餐食
$env:PLAYWRIGHT_CHROMIUM_EXECUTABLE='C:\Program Files\Google\Chrome\Application\chrome.exe'
python deploy/scripts/verify_local_deployment.py --browser --output deploy/release-results/my-next-check

# 一致备份：短暂停止后端写入，完成后自动恢复；应同时停止外部写入者
python deploy/scripts/recovery.py backup --project mealcheck-fullstack --env-file .env --compose docker-compose.yml --compose deploy/compose.local.yml --output deploy/recovery-results/my-next-backup

# 隔离演练：恢复到新的无公开端口容器，核对表数量、图片引用和文件哈希
python deploy/scripts/recovery.py drill --backup deploy/recovery-results/my-next-backup --output deploy/recovery-results/my-next-restore
```

### 本机启动问题与当前限制

本轮 Docker Desktop 4.73.0 因两处遗留的 AF_UNIX 通信 socket 无法访问而启动失败，与 [Docker 官方仓库问题记录](https://github.com/docker/for-win/issues/15064) 中的错误一致。停止报错的 Desktop 后，仅将两处只含损坏 socket 的目录重命名留存，重新启动后引擎恢复；未重置 Docker 或删除数据卷。
留存目录为 `%LOCALAPPDATA%/docker-secrets-engine.stale-20261011-2009` 和 `%LOCALAPPDATA%/Docker/run.stale-20261011-2011`。遇到类似错误应先确认日志与目录内容，不要直接套用删除或恢复出厂设置操作。

- 当前未配置真实模型密钥和地址，**真实图片 AI 识别不可用**；真实模型质量、调用费用尚未验收。启用前在本地 `.env` 填写 `MEALCHECK_AI_API_KEY` 和 `MEALCHECK_AI_BASE_URL` 后重建后端容器，并单独验收。没有部署远程服务器、域名或 HTTPS。

发布候选版本的源码、镜像及验收证据绑定流程见 [发布基线](deploy/RELEASE_BASELINE.md)。

最近多轮改进已汇总到下方「近期改进」与「后端优化」，文档更新于 **2026-10-04**。内容对应当前工作区实现；本地验收、生产待验收事项和运行步骤分别见 [优化验收](deploy/OPTIMIZATION_VERIFICATION.md) 与 [运行说明](deploy/OPERATIONS_AND_EVALUATION.md)。

MealCheck is a meal image analysis system. Users can upload meal photos, get food recognition results, receive nutrition scores, and view RAG-based dietary suggestions. In addition, users can interact with an AI assistant powered by LangChain4j and RAG to receive personalized dietary advice. Administrators can manage users, meal records, and the RAG knowledge base.

## Tech Stack

- Backend: Spring Boot
- Frontend: React + Vite
- Database: PostgreSQL
- Query optimization: [字段裁剪、游标分页、联表索引与监控](deploy/QUERY_OPTIMIZATION.md)
- Vector Search: pgvector
- Cache & Session: Redis
- Image analysis messaging: RabbitMQ + PostgreSQL transactional outbox
- Authentication: JWT
- AI Vision: Qwen VL API
- AI Assistant: LangChain4j + RAG + optional Function Calling
- Weekly Report Multi-Agent: LangGraph4j orchestration + LangChain4j agents
- Knowledge Base: Markdown + pgvector Retrieval
- Agent Workflow: Tool orchestration, conversation memory, proactive suggestions
- Agent Skills: independently registered and dynamically selected diet skills
- MCP: authenticated Streamable HTTP tools for external AI clients
- Deployment: Docker Compose

## Features

- User registration, login, and JWT authentication
- Meal image upload and food recognition
- Meal structure scoring and risk tag analysis
- Optional per-meal portion entry and rough nutrition estimates (disabled by default). Users supply weights and per-100g label/reference data; incomplete inputs never produce a whole-meal total. Estimates explicitly warn about inaccuracy and are kept separate from structure scores, trends, and reports. See [可选营养估算说明](deploy/OPTIONAL_NUTRITION.md).
- RAG-based nutrition advice
- AI Assistant: LangChain4j + RAG
  - Provides personalized diet Q&A based on user meal history and nutrition knowledge
  - Supports conversation context, RAG references, and structured responses
  - Generates proactive suggestions based on recent diet trends
  - Dynamically selects a registered skill for weekly reports, trend analysis, fat loss, muscle gain, fried food, barbecue, vegetables, sweet drinks, or balanced diet advice
  - Uses layered memory with full PostgreSQL history, conversation-scoped Redis working memory, rolling summaries, structured state, and token-budgeted relevant history
  - Optionally lets the model select read-only local tools through LangChain4j Function Calling, with bounded rounds, an allow-list, a request deadline, and deterministic fallback
- MCP server
  - Exposes read-only dietary knowledge, Skill routing, recent meal, and weekly report tools at `/mcp`
  - Reuses the existing JWT authentication and records per-tool request count, outcome, and latency
- Meal history and weekly reports
  - Uses a LangGraph4j workflow for generated reports: trend Agent and RAG evidence run in parallel, then a diet-plan Agent produces the draft and a safety-review Agent approves or requests one revision.
  - Keeps database/RAG access as deterministic tool nodes instead of treating every service as an Agent.
  - Falls back to the original rule-based report when AI is unavailable, the graph fails, or the final review still rejects the draft.
  - Returns `generationMode`, `reviewStatus`, and `agentTrace` metadata with each generated report for debugging and demonstrations.
- Admin dashboard
  - User management
  - Global meal record pagination, filtering, deletion, and visual analytics
  - RAG knowledge creation, editing, deletion, search, rebuild, and hit statistics
  - AI assistant usage statistics, question trends, RAG reference ranking, and session analytics

## 近期改进（截至 2026-10-02）

### 用户端与管理端工作台

- 用户端按「饮食分析、饮食记录、饮食趋势、我的周报、饮食目标」拆分页面；管理端按业务管理、智能服务、质量评测和系统管理分组导航。
- 两端共用工作台布局，支持桌面侧栏折叠、移动端抽屉导航、键盘焦点循环、Esc 关闭和焦点返回。
- 分析结果、餐食记录和完整指标通过详情抽屉展开；趋势、周报拥有独立页面。长建议、完整食物列表和周报正文仍可查看。
- 管理端将助手评测拆为当前结果、评测历史、运行对比和样本候选库，将监控及助手统计按类别切换，减少单页信息堆叠。
- 切换助手会话、筛选条件或管理页面时，取消或忽略过期请求；统一保留 HTTP 状态、错误码、请求 ID 和 `Retry-After`，登录失效时返回登录页。

### 图片分析异步化与用户纠错

- 图片分析已接入 RabbitMQ：任务与 Outbox 在同一事务入库，发布确认和不可路由检查保障补发，消费者默认每实例并发 8 条、prefetch 1，全站最多 32 个未完成任务；结果事务提交后确认消息。支持死信队列、执行租约恢复及重复投递隔离，部署与配置见 [RabbitMQ 运行说明](deploy/RABBITMQ.md)。
- `POST /api/meals/analysis-requests` 接收图片、目标和 `Idempotency-Key`，新任务返回 `202 / PENDING`；`GET /api/meals/analysis-requests/{key}` 查询当前用户的进度和结果。兼容接口 `/api/meals/analyze` 保留同步客户端支持。
- 图片经解码、尺寸校验和归一化后进入持久队列，独立 worker 领取任务；租约过期可恢复，尝试令牌隔离旧任务，成功后清除排队图片。
- 同一请求标识绑定图片与目标，重复提交复用请求；更换内容返回冲突。成功结果与餐食、画像更新在同一事务发布，避免重复餐食记录。崩溃恢复仍可能再次调用模型，不承诺供应商只计费一次。
- 前端自动查询任务，断网后有限重试；刷新页面保留未确认的请求标识并继续查询。失败后使用原图重试，无需因响应丢失而另建分析。
- 用户可纠正食物名称、分类和漏识别项。纠错保存原始识别及修订记录，校验记录归属与版本，并重新计算评分和画像。
- 评分仅使用置信度达标、去重后的结构化食物分类；场景描述和备注不直接影响分数。历史记录不会因规则升级被自动重算。

### 周报一致性与 Agent 执行追踪

- 周报读取与生成分离：查询只读快照或规则摘要，显式提交后台任务才触发生成；页面刷新不会重复调用 AI。
- 新增、删除或纠正餐食后，旧周报及相关任务失效；失效任务标为 `STALE`，同日可提交新任务。快照发布与任务成功共享事务，并校验尝试编号，阻止旧尝试覆盖新结果。
- 周报详情按服务端 `periodStart` / `periodEnd` 独立分页加载餐食，不受历史列表的分页和筛选条件影响；食物项数量与包含该类食物的餐次数分开统计。
- 管理端新增「Agent 协调」，保存周报运行及节点记录，可查看步骤状态、耗时、审查修订和降级情况；配套心跳、超时恢复与运行积压指标。

### 助手与识别质量评测

- 助手联合评测包含 **200 条合成用例**，提供快速 10 题、标准 50 题和完整 200 题模式，覆盖意图路由、工具调用和回答质量。评测复用生产 Function Calling 循环与工具定义，用户、餐食、目标和会话使用隔离的合成数据。
- 工具名称、参数、状态、耗时、轮次与预期工具顺序均可追踪；缺少必需工具、调用禁用工具或违反轨迹约束会导致用例失败。
- 支持独立裁判模型、质量维度与硬门禁、Skill 命中率及 Macro-F1。同模型自评最多得到 `NEEDS_REVIEW`，不会直接判为 `PASSED`。
- 评测运行、逐题结果、人工复核与脱敏失败样本候选持久化；运行对比记录数据集版本及 SHA-256 指纹，模式或数据集不同会提示不可直接比较。候选样本审核通过后仍需整理和代码评审才能进入正式评测集。
- 管理端「识别纠错评测」支持脱敏审核、独立标注期望评分、固定回归集导出、历史运行和失败案例比较。历史快照不依赖原餐食继续存在。
- 新增独立图片识别评测工具，校验图片授权声明、人工标注声明及 SHA-256，输出 precision/recall、分类准确率、逐题错误、P95、成本和质量门槛结果。**评分回归、合成助手评测和独立图片识别评测分别衡量不同能力，不能互相代替。**

详细评测口径见 [助手评测说明](backend/src/test/resources/evaluation/README.md)、[纠错与可靠性说明](deploy/RELIABILITY.md) 和 [视觉评测运行说明](deploy/OPERATIONS_AND_EVALUATION.md)。当前没有基于独立真实图片标注集的识别准确率结论。

### 运行、安全与发布验证

- 增加生产 Compose、生产配置校验、非 root 容器、健康检查及 Nginx 反向代理配置；前端使用 Docker DNS 动态解析后端地址，支持后端容器重建后的访问。
- JWT 校验不可变用户 ID、用户名、有效期与账户状态；删除账户后的旧令牌不能登录同名新账户。普通用户可通过密码校验注销账户，并清理关联数据、图片及缓存。
- 密码统一限制为 72 个 UTF-8 字节；同名并发注册返回 409，AI 预算和超时保留对应状态，错误响应及共享健康状态使用安全摘要。详细约定见 [密码与错误处理说明](backend/ERROR_CONTRACT.md)。
- 增加分析失败率、耗时、队列积压、Token 与估算成本指标，以及 15 条告警规则；提供 Prometheus、Alertmanager 和 webhook 通知配置。
- 提供数据库与图片一致备份、rclone crypt 加密复制、内容校验和隔离恢复演练，以及每日备份、每周恢复的 systemd 配置。
- 增加失败图片、失败请求、失效周报任务和审计日志的保留策略；保留成功请求的幂等标识，避免清理后重复入库。
- CI 已配置后端单元/HTTP 与真实 PostgreSQL 集成测试、前端测试与构建、Playwright 浏览器验收、告警校验、生产配置冒烟和恢复演练。

**已有验收范围：** [2026-10-01 优化验收记录](deploy/OPTIMIZATION_VERIFICATION.md)记录了 163 个后端单元/HTTP 测试、19 个 PostgreSQL 集成测试、12 个前端测试、7 条浏览器验收及 4 个 Python 测试通过。这是该轮历史结果，后续工作台拆页另有浏览器用例，当前测试数量应以重新执行结果为准。混合压测使用模型替身和小数据量，不能代表真实供应商容量。生产通知、异地存储、定时任务启用和正式发布仍需按运行说明验收。

## Earlier Version Highlights

Compared with the previous project version, [MealCheck-agent2](https://github.com/Lan-lan-123/MealCheck-agent2), this version mainly improves the AI Assistant module and adds more detailed user-side and admin-side capabilities:

- Enhanced AI Assistant
  - Improved the LangChain4j + RAG based diet assistant with structured responses, reference display, conversation context, and personalized suggestions.
  - Added assistant usage statistics, RAG reference tracking, session analytics, and proactive diet advice.

- Improved user-side features
  - Added richer diet trend analysis, user goal/profile management, weekly report generation, and clearer upload feedback.
  - Added abnormal upload handling for non-food images, including user warnings and temporary upload restrictions.

- Enhanced admin dashboard
  - Added more detailed user, meal record, RAG knowledge base, assistant usage, abnormal upload, and system operation statistics.
  - Improved pagination, filtering, deletion confirmation, visual analytics, RAG evaluation, and AI observability panels.

## 后端优化（2026-10-04）

### AI 总超时、统一重试与每日预算

图片分析和 AI 周报以异步任务为主流程，同步兼容接口保留。默认后端 AI 操作 120 秒、前端容器代理 150 秒、宿主机代理 165 秒、浏览器长请求 180 秒；普通查询、任务提交和轮询仍为 75 秒。自定义提高后端超时时需同步调整其他层，详见 [超时与部署说明](deploy/TIMEOUTS.md)。

- 视觉识别、饮食建议、普通助手、Function Calling、周报 Agent、Embedding、Reranker 和评测裁判共用 `AiHttpTransport`。LangChain4j 通过 HTTP 适配器复用这一入口，关闭 SDK 内部重试，避免叠加重试。Token 与估算成本指标也在此统一采集。
- 单个外部调用连同所有重试、退避默认最多 60 秒；单餐分析、助手对话和周报生成共享默认 120 秒的操作截止时间。Function Calling 保留更短的 30 秒上限。每次 HTTP 请求按剩余时间设置超时，执行器排队不会获得额外时间，取消后不启动新尝试。上下文通过有界执行器和周报图节点显式传递。
- 重试采用指数退避加随机抖动，支持供应商 `Retry-After` 的秒数和 HTTP 日期。供应商要求的等待超过剩余时间时直接结束操作。普通 4xx 不重试，429、5xx 和网络故障可在预算内重试。
- PostgreSQL 在每次外部尝试前原子预留调用次数和估算费用，多实例并发共享额度。每用户默认每天 100 次**外部尝试**，重试、函数调用轮次和查询向量都分别计数；这不是 100 次提问或图片上传。后台索引和管理端联合评测使用独立的系统额度，默认每天 5,000 次；联合评测逐题重置操作超时。
- 返回可信 usage 且配置了匹配模型、接口的输入/输出价格时，按 Token 用量结算；其他供应商、缺失 usage 或结果不确定的失败使用预留额保守记账。未发送的预留会释放；进程中断后，恢复任务回收超过 10 分钟的预留，已标记发送的尝试仍保留调用次数和估算费用。账本保留 30 天，注销用户时级联清理。
- 费用单位与配置的每百万 Token 价格一致，默认不启用金额上限。金额预算属于**估算额度**：输入按请求长度保守预估，图片的 Base64 数据可能导致预留偏高；供应商实际计费、隐藏 Token 或缺失 usage 也可能与估算不同。不能将它当作供应商账单的硬上限。
- `GET /api/profile/ai-budget` 只返回当前登录用户的当日调用次数、剩余额度、已结算费用、预留费用和重置时间。预算耗尽返回 `429 / AI_BUDGET_EXCEEDED` 与 `Retry-After`；预算存储故障拒绝新模型调用，返回 `503 / AI_BUDGET_UNAVAILABLE`。直接处理接口超时返回 `504 / AI_DEADLINE_EXCEEDED`；已有助手、周报和重排降级路径仍可返回本地结果。

常用配置（已加入两个环境示例与 Compose）：

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `MEALCHECK_REMOTE_TOTAL_TIMEOUT_SECONDS` | `60` | 一次外部调用含重试的总上限 |
| `MEALCHECK_AI_OPERATION_TIMEOUT_SECONDS` | `120` | 一次用户 AI 操作的共享上限 |
| `MEALCHECK_AI_MAX_OUTPUT_TOKENS` | `2048` | 聊天模型输出上限 |
| `MEALCHECK_AI_GLOBAL_DAILY_CALLS` | `100000` | 所有用户与系统任务共享的每日外部尝试上限 |
| `MEALCHECK_AI_BUDGET_ENABLED` | `true` | 启用持久化预算 |
| `MEALCHECK_AI_DAILY_CALLS` | `100` | 每用户每日外部尝试上限 |
| `MEALCHECK_AI_SYSTEM_DAILY_CALLS` | `5000` | 后台与联合评测的每日尝试上限 |
| `MEALCHECK_AI_DAILY_COST` | `0` | 每用户每日估算金额上限，0 表示不限制金额 |
| `MEALCHECK_AI_SYSTEM_DAILY_COST` | `0` | 系统每日估算金额上限，0 表示不限制金额 |
| `MEALCHECK_AI_RESERVATION_COST` | `0.02` | 每次尝试的最低预留额；未知成本按预留额结算 |
| `MEALCHECK_AI_BUDGET_ZONE` | `Asia/Shanghai` | 每日额度重置时区 |

价格仍通过 `MEALCHECK_AI_INPUT_COST_PER_MILLION` / `MEALCHECK_AI_OUTPUT_COST_PER_MILLION` 配置。两项总超时允许 1–300 秒，输出上限允许 1–32,768 Token；无效预算配置会阻止启动。部署时执行 Flyway **V21**，创建预算账本及查询索引；关闭预算只跳过账本限制，仍保留超时、重试和输出上限。

### 长会话游标分页

- `GET /api/assistant/conversations/{id}?limit=40` 默认只返回最近 40 条消息，`limit` 范围为 1–100。消息在页内按时间、ID 升序展示，响应新增 `hasMore` 和 `nextBefore`。
- 有历史页时，将服务端返回的 `nextBefore` 作为下一次请求的 `before` 参数。游标绑定会话，以 `(createdAt,id)` 为边界；相同时间的消息以及翻页期间新增的消息不会造成重复或漏页。每次最多读取 `limit+1` 条，不加载完整历史或统计全量页数。游标不能跨会话复用，所有查询仍验证当前用户归属。
- 聊天页面增加「加载更早的消息」，向前追加历史后保留滚动位置；新建或切换会话会取消旧历史请求并忽略迟到响应。模型记忆仍使用独立的受限窗口与摘要，不受页面加载历史数量影响。
- 原详情地址保留，但 `messages` 现在表示**当前页**；使用该接口的其他客户端需要按分页字段读取更早的消息。

### 趋势查询使用聚合与字段投影

- 餐次数、平均分、高低分计数、首末评分和每日均分由 PostgreSQL 聚合；食物与风险统计只读取两项 JSON 字段，不加载完整 `MealRecord`、长建议、原始识别结果或用户实体。
- 保留 7–90 天范围、评分阈值、四舍五入和现有响应字段。三项查询在同一个只读数据库快照中执行，避免并发新增餐食导致计数与图表不一致。按日统计将底层 UTC 时间转回服务端时区，保持原实体读取的日期口径；相同时间的首末评分用 ID 稳定排序。筛选始终限制当前用户。
- V21 增加用户/时间/ID 与会话/时间/ID 索引。目前使用实时查询，没有增加每日汇总表。集成测试会在 2,000 条含长建议的隔离数据上保存 `EXPLAIN (ANALYZE,BUFFERS)` 对比到 `backend/target/trend-query-plans.txt`；小样本的 SQL 耗时差异不构成线上性能结论，当前主要收益是减少返回数据和 Java 实体加载。上线后再根据真实慢查询和负载决定是否汇总。

### 助手长期记忆迁移与可恢复重建

- Flyway **V22** 管理长期记忆结构。记忆原文与不同版本的向量分开保存；升级已有 `vector` / `halfvec` 表时保留原文、记录 ID 和旧向量。应用启动不再执行动态 DDL、清空记忆或同步调用模型。
- Embedding 模型、维度或存储精度变化后，后台创建持久化重建任务，每批处理 5 条。任务使用 3 分钟租约和执行令牌，过期后可由其他实例接管；已完成且原文哈希仍匹配的向量继续复用。累计 5 次执行失败后暂停，管理员可修复配置或依赖后重试。
- 全部当前记忆准备完成后，在数据库事务中原子切换版本。重建期间、版本不匹配或查询 Embedding 失败时，助手回退到当前用户最近的有限条原文；新增记忆先保存原文，向量调用失败也不会丢失文本。
- 管理员接口：`POST /api/admin/assistant-memory/rebuilds` 发起或复用任务，`GET /api/admin/assistant-memory/rebuilds/{id}` 查看进度，`POST /api/admin/assistant-memory/rebuilds/{id}/retry` 重试。响应只包含任务信息，不返回用户记忆正文。多实例应使用一致的模型、维度与压缩配置；不同目标版本的重建不能同时启动。
- PostgreSQL 集成测试覆盖旧 `halfvec(3)` 表实际升级、维度/模型/精度变更、断点恢复、过期执行令牌、新增记忆阻止提前切换、切换回滚及用户隔离。

### AI 额度提示、管理监控与告警

- 上传页与助手页显示剩余外部调用次数、重置时间，并在操作后更新，支持手动刷新。启用金额上限时同时显示已用与预留的估算费用。页面明确提示一次操作可能有多次调用，重试也消耗额度；最终准入仍由后端原子预留决定。
- 异步图片任务失败响应增加允许公开的 `errorCode`：额度耗尽提示等待重置，预算存储故障提示稍后重试。内部异常不直接传给用户；额度错误不会清除登录会话。
- 管理端 AI 监控新增预算卡片，`GET /api/admin/ai-budget` 返回预算启用状态、恢复监控健康度、未结算/超时预留、最老预留秒数及拒绝/延迟结算/存储故障计数。预留统计来自共享数据库；累计计数属于当前实例，跨实例应在 Prometheus 汇总。
- 新增 5 条预算告警：拒绝量异常、结算延迟、超时预留积压、恢复监控失效、预算存储不可用。关闭预算时监控健康告警不误报；规则文件共 25 条并通过 `promtool` 校验。规则阈值应按实际调用量调整，通知接收端仍按部署监控配置接入。

### 大数据量、图片资源与故障验收

- 新增独立的 [容量验收环境](deploy/compose.acceptance.yml) 和 [验收脚本](loadtests/capacity_acceptance.py)，只使用固定的本地隔离 Compose 项目、专用账号及模型替身；执行数据写入和依赖中断前验证项目标签与替身配置。完整命令见 [压测说明](loadtests/README.md#隔离容量与故障验收2026-10-04)。CI 已接入脚本、清理与证据归档，远程 CI 结果需以实际运行记录为准。
- 默认准备 5 个账号、50,000 条含长建议的餐食、10,000 条聊天消息与 4096×4096 图片。验证趋势查询、连续两页历史、正常上传、慢模型、429 后恢复、解码并发饱和，以及 Redis/PostgreSQL 中断后使用原 JWT 恢复访问。
- 采集 k6 成功/失败/拒绝/超时、CPU、容器内存、JVM 堆、连接池获取时间、队列等待直方图、HTTP 重试和估算费用；另保存实际趋势 SQL 的 `EXPLAIN (ANALYZE,BUFFERS)`。连接池获取时间不是 SQL 执行耗时；队列 P95 是直方图桶上界，资源峰值来自采样。
- 本地短时结果（2 CPU、512 MiB JVM 堆、768 MiB 容器）：趋势 P95 **898 ms**，历史分页 P95 **95 ms**；约 7.36 MiB 图片正常分析 P95 **3.22 秒**，每次模型请求延迟 1 秒时 **4.83 秒**。临时前两次模型请求返回 429 后，记录 2 次重试，5 个任务全部成功；5 张并发上传时 2 张成功、3 张被入口明确拒绝，已接受任务没有执行失败、超时或不确定结果。两项依赖故障均返回 503，恢复后原 JWT 可继续使用。
- 验收也暴露了边界：查询冷启动最大耗时约 **3.47 秒**，突发阶段容器内存采样达到约 **741.5 MiB / 768 MiB**。最初持续周期性 429 的替身使 1 个任务在有限重试后失败，原始证据保留；这与暂时故障后恢复的用例分别记录。短时合成数据和模型替身不能证明线上最大容量、持续稳定性或真实识别效果，正式资源配置仍需要持续负载验证。

### 调度、代理与任务可靠性

本轮同时保留并完善了此前六项优化，详情见 [后端可靠性与资源隔离](deploy/BACKEND_OPTIMIZATIONS.md)：

- 后台调度器与助手计时线程分离；耗时维护任务使用有界线程池，同类未完成任务合并。
- 两层 Nginx 清除并重建转发头，仅信任明确的代理地址，提供真实代理链与伪造头回归测试。
- 图片解码前执行入口限流和幂等检查；重复请求跳过解码，默认最多并发解码两张图片。
- 周报领取数量匹配线程池容量；调度拒绝归还重试次数、延后执行并轮换令牌，阻止旧尝试更新任务。
- JWT 无效返回 401；Redis、数据库认证故障返回 503 并记录指标，避免前端清除用户会话。
- 知识库固定结构通过 Flyway V20 管理；向量重建分批保存进度、支持恢复与管理员重试，全部完成后原子切换。

验证方式：后端运行 `mvn verify -Pintegration`（需要 Docker）；前端运行 `npm test`、`npm run build` 与 `npx playwright test e2e/assistant-history.spec.js`。真实 HTTP 测试覆盖总超时、Retry-After、SDK 共用传输层和预算拒绝；PostgreSQL 测试覆盖并发额度、结算幂等、崩溃恢复、游标边界和趋势日期口径。

最新本地验证：**204 个后端单元/HTTP 测试、16 个前端测试及生产构建通过**。本轮完整 PostgreSQL 集成验证通过 49 项；最后补充 HTTP 指标与权限回归后，另重跑记忆/RabbitMQ 相关 11 项通过。浏览器既有 39 项通过，新增额度验收在修正测试拦截数据后单独重跑通过，共覆盖 40 项。隔离容量与依赖恢复用例通过，25 条告警规则校验通过；Python 脚本另有安全边界和真实 k6 替身契约验证。以上均为本地结果。

## Project Structure

```text
mealcheck-fullstack/
  backend/              Spring Boot backend
  frontend/             React + Vite frontend
    e2e/                Playwright browser acceptance tests
    test/               Request consistency and async polling tests
  deploy/               Production, monitoring, backup and verification guides
  tools/                Vision evaluation, comparison and operations checks
  loadtests/            k6 load tests and mixed workload scenarios
  .github/workflows/    CI build, tests, smoke checks and restore drills
  docker-compose.yml    PostgreSQL + Redis + optional full-stack services
  docker-compose.prod.yml    Production application stack
  docker-compose.mirror.yml  Optional Maven mirror for Docker builds
  .env.example          Environment variable example
  .env.production.example   Production environment example
```

## Quick Start

Prerequisites: JDK 17, Maven, Node.js 22 (as used in CI), and Docker Compose for PostgreSQL/Redis/RabbitMQ and integration tests. Configure the AI provider before testing image recognition; no API key is bundled.

Use [.env.example](.env.example) as the configuration reference. Docker Compose reads `.env` values referenced by its service definitions. A backend launched with Maven needs the variables in its process environment or IDE run configuration; it does not automatically load the root `.env` file. Production configuration uses [.env.production.example](.env.production.example).

### 1. Start only infrastructure services for local development

```bash
docker compose up -d
```

This starts only:

- PostgreSQL + pgvector
- Redis
- RabbitMQ (AMQP on `127.0.0.1:5672`; development management UI on `http://localhost:15672`)

### 2. Start the backend

```bash
cd backend
mvn spring-boot:run
```

Default backend URL:

```text
http://localhost:8080
```

### 3. Start the frontend

```bash
cd frontend
npm ci
npm run dev
```

Default frontend URL:

```text
http://localhost:5173
```

### Optional: start the whole stack with Docker

```bash
docker compose --profile fullstack up --build -d
```

This starts PostgreSQL, Redis, RabbitMQ, backend, and frontend together.

If Docker Hub times out while fetching the frontend's Nginx image, fetch the
same image tag through the DaoCloud proxy and add the local tag expected by the
Dockerfile:

```bash
docker pull m.daocloud.io/docker.io/nginxinc/nginx-unprivileged:1.27-alpine
docker tag m.daocloud.io/docker.io/nginxinc/nginx-unprivileged:1.27-alpine nginxinc/nginx-unprivileged:1.27-alpine
```

If Maven Central also fails with a TLS handshake error during the backend build,
use the optional build-only Aliyun Maven mirror override:

```bash
docker compose -f docker-compose.yml -f docker-compose.mirror.yml --profile fullstack up --build -d
```

The regular Docker build still defaults to Maven Central. BuildKit caches Maven
dependencies between builds. Neither workaround changes global Docker proxy or
Maven settings. Verify startup with `docker compose --profile fullstack ps`, then
open `http://localhost:5173`; the backend API is exposed at
`http://localhost:8080`. Registry proxy syntax is documented by
[DaoCloud](https://github.com/DaoCloud/public-image-mirror), and the Maven mirror
is documented by [Aliyun](https://developer.aliyun.com/mirror/maven).

For an ECS production deployment, use `docker-compose.prod.yml` and follow
[`deploy/PRODUCTION.md`](deploy/PRODUCTION.md). The production stack binds the
web container to loopback only and expects the host Nginx or an Alibaba Cloud
load balancer to terminate HTTPS.

## Environment Variables

The following is a subset of the available settings. See [.env.example](.env.example) for assistant evaluation judge, worker and concurrency settings, [application.yml](backend/src/main/resources/application.yml) for analysis queue and retention defaults, and [deploy/operations.env.example](deploy/operations.env.example) for monitoring and encrypted backup configuration. When adding a setting to a Compose deployment, also ensure it is passed into the backend container.

```text
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/mealcheck
SPRING_DATASOURCE_USERNAME=mealcheck
SPRING_DATASOURCE_PASSWORD=mealcheck123
MEALCHECK_AI_API_KEY=your_api_key
MEALCHECK_AI_BASE_URL=https://example.com/compatible-mode/v1/chat/completions
MEALCHECK_AI_MODEL=qwen3-vl
MEALCHECK_KNOWLEDGE_EMBEDDING_API_KEY=your_embedding_api_key
MEALCHECK_KNOWLEDGE_EMBEDDING_BASE_URL=https://example.com/compatible-mode/v1
MEALCHECK_KNOWLEDGE_EMBEDDING_MODEL=text-embedding-v4
MEALCHECK_KNOWLEDGE_EMBEDDING_DIM=512
MEALCHECK_KNOWLEDGE_VECTOR_STORAGE=halfvec
MEALCHECK_KNOWLEDGE_RERANK_ENABLED=false
MEALCHECK_KNOWLEDGE_RERANK_URL=https://api.jina.ai/v1/rerank
MEALCHECK_KNOWLEDGE_RERANK_API_KEY=your_rerank_api_key
MEALCHECK_KNOWLEDGE_RERANK_MODEL=jina-reranker-v2-base-multilingual
MEALCHECK_KNOWLEDGE_RERANK_CANDIDATE_MULTIPLIER=4
MEALCHECK_KNOWLEDGE_RERANK_MODEL_WEIGHT=0.75
MEALCHECK_KNOWLEDGE_RERANKER_MIN_SCORE=0.20
MEALCHECK_KNOWLEDGE_RRF_K=60
MEALCHECK_KNOWLEDGE_RESULT_FILTER_ENABLED=true
MEALCHECK_KNOWLEDGE_RESULT_MIN_SCORE=0.35
MEALCHECK_KNOWLEDGE_RESULT_RELATIVE_SCORE=0.60
MEALCHECK_KNOWLEDGE_RESULT_DEDUP_SIMILARITY=0.88
MEALCHECK_KNOWLEDGE_RESULT_CANDIDATE_MULTIPLIER=4
MEALCHECK_KNOWLEDGE_CHUNK_MIN_TOKENS=300
MEALCHECK_KNOWLEDGE_CHUNK_MAX_TOKENS=500
MEALCHECK_KNOWLEDGE_CHUNK_OVERLAP_TOKENS=50
MEALCHECK_KNOWLEDGE_SPLITTER_STRATEGY=recursive-markdown
MEALCHECK_KNOWLEDGE_SOURCE_VERSION=v3-curated-100
MEALCHECK_MCP_ENABLED=true
MEALCHECK_MCP_ENDPOINT=/mcp
MEALCHECK_ASSISTANT_FUNCTION_CALLING_ENABLED=false
MEALCHECK_ASSISTANT_FUNCTION_CALLING_MAX_TOOL_ROUNDS=3
MEALCHECK_ASSISTANT_FUNCTION_CALLING_MAX_TOOL_CALLS=6
MEALCHECK_ASSISTANT_FUNCTION_CALLING_TOTAL_TIMEOUT_SECONDS=30
MEALCHECK_ASSISTANT_MEMORY_HISTORY_TOKEN_BUDGET=1200
MEALCHECK_ASSISTANT_MEMORY_RECENT_MESSAGES=6
MEALCHECK_ASSISTANT_MEMORY_SUMMARY_MAX_TOKENS=500
MEALCHECK_ASSISTANT_MEMORY_RELEVANT_MESSAGES=4
```

## Notes

### Reliability, corrections and release verification

See [`deploy/RELIABILITY.md`](deploy/RELIABILITY.md) for meal-analysis idempotency and result recovery, user recognition corrections, reviewed scoring regression datasets, browser acceptance tests, production smoke checks, and consistent database/image backup-and-restore drills. Migration V16 invalidates old weekly reports after meal changes; repeated generation creates a fresh job when the previous report is stale.

### Report generation and verification

- `GET /api/reports/weekly?days=7` reads a stored report or a rule-based summary; it never calls an AI model.
- `POST /api/reports/weekly/jobs?days=7` submits a durable job and returns `202` with its ID and status (or `200` for a terminal cached job). `GET /api/reports/weekly/jobs/{id}` reads the authenticated user's job and completed report.
- Submissions reuse the same non-stale user/date/day-count job, including failed jobs. Meal changes invalidate the old job as `STALE`, allowing a fresh job for the same period. New jobs have a configurable token bucket (default capacity 3, refill 1 per hour). The dashboard submits and polls these jobs; refreshing a page never launches AI generation.
- `categoryTotals` counts food items; `categoryMealCounts` counts meals containing each category. Report periods use inclusive calendar dates; retries retain the original job creation date as the period end.
- Snapshot publication and successful job completion share one transaction, fenced by the attempt number. Migration V15 gives every new snapshot a unique job ID. Legacy snapshots remain readable.
- Run `mvn test` for backend unit/HTTP tests and `npm test` in `frontend` for report polling tests. Run `mvn verify -Pintegration` with Docker running for mandatory PostgreSQL migration, transaction, and concurrency tests. CI runs this full profile and fails when Docker is unavailable; database tests are not silently skipped.
- JWT authentication requires an existing immutable user ID, matching username, valid expiration, and an active account. Tokens for deleted accounts cannot authenticate a new account with the same username.
- Scoring uses confident, deduplicated food entries and structured categories. Scene summaries and notes cannot directly change the score. These rule changes apply to new analyses; historical meal scores are not automatically rewritten.

- Image recognition requires a configured AI provider; missing configuration returns an explicit error without saving a fabricated recognition result.
- The default RAG source is `backend/src/main/resources/knowledge/diet_guides.md`.
- Configure an OpenAI-compatible embedding endpoint for semantic retrieval. Without it, the system remains available through a local 512-dimensional hash embedding fallback.
- Existing fixed-dimension knowledge vectors are migrated to the configured dimension at startup and regenerated with the active embedding provider.
- Knowledge embeddings use pgvector `halfvec` storage by default. For 512 dimensions this reduces the raw vector payload from 2,056 bytes to 1,032 bytes (about 49.8%); set `MEALCHECK_KNOWLEDGE_VECTOR_STORAGE=vector` to keep 32-bit vectors.
- A same-dimension migration from `vector` to `halfvec` casts existing values without calling the embedding API again. A dimension change still regenerates embeddings to preserve correctness.
- Each knowledge vector stores an embedding signature. Switching between the hash fallback and a semantic model automatically refreshes stale vectors instead of mixing embedding spaces.
- The bundled guide contains **100 independently curated topics** (`v3-curated-100`), each with a public reference and practical campus examples. Its leading `<!-- mealcheck:independent-topics -->` marker preserves heading boundaries: short topics are neither merged nor prefixed with unrelated topics. At default settings the guide produces exactly 100 database chunks, each title plus body embedded as one vector. Long individual topics still split at the configured maximum with internal overlap.
- For ordinary Markdown without the marker, `recursive-markdown` still uses heading, paragraph, line, sentence and token-window boundaries, approximately 300–500 tokens and 50-token overlap; `legacy-window` remains available. Curated short topics may be under 300 tokens.
- Admin counts database chunks rather than evaluation questions: the separate assistant evaluation set has 200 cases (50 base + 150 expansion). Rebuilding the backend and synchronizing the knowledge source updates the 100 system entries while preserving `source=admin` entries. A configured source version in `.env` overrides the new default; set `MEALCHECK_KNOWLEDGE_SOURCE_VERSION=v3-curated-100` to label this corpus correctly.
- References provide general food principles; campus menus and recordkeeping examples are editorial applications, not medical prescriptions or measured nutrition data. Real model answer quality remains a separate verification task.
- Each chunk stores its source, category, source version, token count, update timestamps, stable chunk key, and SHA-256 content hash. Reindexing only regenerates vectors for new or changed chunks; unchanged chunks keep their existing vectors and hit statistics.
- RAG entries manually added by administrators are stored in the database and preserved separately.
- Hybrid retrieval independently ranks pgvector, title pg_trgm, and content pg_trgm candidates, then combines their ranks with weighted Reciprocal Rank Fusion (`k=60`) plus a small category prior. The normalized fusion score remains compatible with confidence metrics.
- Optional second-stage reranking expands Top-K weighted-RRF candidates by four and calls a query-document relevance model. Rough rank and reranker rank are fused with Weighted RRF; rough score/rank, raw reranker score/rank, and final score are retained separately. A timeout or provider failure automatically keeps the rough ranking.
- The 58-case admin RAG benchmark contains 48 answerable and 10 no-answer questions. It reports Hit@3, Hit@5, MRR, nDCG@5, category accuracy, no-answer accuracy, false-positive rate, layered scores, per-stage latency, and an automatic regression result.
- When reranking is active, candidates below `MEALCHECK_KNOWLEDGE_RERANKER_MIN_SCORE` are rejected before context injection; this raw-score threshold must be calibrated against the no-answer benchmark for the selected provider.
- Final RAG results use a configurable absolute score floor plus a relative-to-Top1 floor, then remove near-duplicate chunks with lexical Jaccard similarity before context injection.
- Assistant history remains intact in PostgreSQL. Redis keys include both username and conversation ID, preventing working-memory leakage between separate conversations.
- Normal assistant requests load a bounded recent PostgreSQL window and incrementally summarize at most 100 pending older messages, avoiding a full-history scan as conversations grow.
- Prompt memory is selected by token budget rather than a fixed message count: the most recent messages are retained, relevant older messages are selected, and evicted history is compressed into a rolling summary. Follow-up questions are expanded with the active conversation topic before RAG retrieval.
- Explicit user goals, preferences, allergies, and restrictions are stored as deduplicated long-term memory vectors. Recall combines semantic similarity, 90-day recency decay, and a same-conversation boost before facts enter the prompt.
- Recent meals, current goal, weekly report, RAG, and long-term memory are fetched concurrently with independent timeouts and graceful per-source fallback. DB, RAG, and background writes use separate fixed-size bounded executors; a request-wide deadline actively cancels timed-out tasks. Proactive advice reuses the already loaded meal records.
- Function Calling is disabled by default. When enabled, the model can select only four read-only tools: dietary knowledge search, recent meals, latest weekly report, and current goal. Model HTTP calls, DB tools, and RAG tools run in separate bounded pools; tool rounds, total tool calls, arguments, result sizes, and the end-to-end deadline are bounded. Any failure returns to the existing deterministic orchestration path.
- RAG and admin-stat caches use jittered TTLs and single-flight refresh. Empty RAG results receive a shorter TTL, while a stale copy lets concurrent followers avoid stampeding the database or model provider during refresh.
- Weekly report generation reuses the durable database job/worker boundary. LangGraph4j manages the internal parallel branches and review loop, while LangChain4j performs the three role-specific model calls. The review loop is bounded by `MEALCHECK_WEEKLY_REPORT_MAX_REVIEW_REVISIONS`, and each Agent call has an independent `MEALCHECK_WEEKLY_REPORT_AGENT_TIMEOUT_SECONDS` timeout.
- Remote AI, embedding, and reranker calls use configurable exponential-backoff retry, circuit breaking, and semaphore concurrency isolation in addition to HTTP timeouts.
- Flyway owns the versioned relational schema and query indexes; Hibernate uses `ddl-auto=validate` instead of mutating production tables at startup. Runtime-configured pgvector/halfvec columns remain managed by their vector services.

## Observability

Spring Boot Actuator exposes health and application metrics:

```text
GET /actuator/health
GET /actuator/metrics
GET /actuator/prometheus
```

`health` is public, while `metrics` and `prometheus` require authentication. Responses include an `X-Request-ID`, and the same request ID is included in backend logs for correlation.

The production profile exposes only health and Prometheus endpoints. The monitoring Compose overlay uses an internal management port (`9091`) without a host mapping; see [operations instructions](deploy/OPERATIONS_AND_EVALUATION.md) for network access and alert setup.

Custom metrics include:

- `mealcheck.knowledge.embedding.*`: embedding request count and latency by provider/outcome
- `mealcheck.knowledge.index.*`: incremental indexing duration, runs, and inserted/updated/unchanged/deleted chunk counts
- `mealcheck.rag.search.*`: RAG cache hit/miss, latency, result count, and top score
- `mealcheck.rag.filter.*`: candidate/output counts, threshold removals, duplicate removals, effective threshold, and empty-result rate
- `mealcheck.rag.score.*`: rough Top1, raw reranker Top1, final Top1, and whether reranking was applied
- `mealcheck.rate.limit.requests`: Redis token-bucket decisions by API scope and outcome
- `mealcheck.executor.*`: executor pool/queue saturation, rejection count, queue wait, and task duration
- `mealcheck.weekly.report.jobs`: durable weekly-report job success, failure, rejection, and stale-result count
- `mealcheck.weekly.report.coordination.*`: workflow and node outcomes, duration, review revisions, recoveries, active runs, and oldest active age
- `mealcheck.analysis.*`: pending analysis tasks, oldest active age, and expired leases
- `mealcheck.ai.tokens`, `mealcheck.ai.estimated.cost`, `mealcheck.ai.usage.missing`, `mealcheck.ai.cost.unpriced`: model usage, estimated cost, and missing usage/pricing signals
- `mealcheck.rag.rerank.*`: reranker request count, outcome, latency, and candidate count
- `mealcheck.ai.call.*`: VLM/assistant API request count and latency by operation/outcome
- `mealcheck.weekly.report.generated`: weekly report success/failure count
- `mealcheck.mcp.tool.*`: MCP tool request count and latency by tool/outcome
- `mealcheck.assistant.memory.*`: selected message/token counts, summary updates, and follow-up rewrite counts
- `mealcheck.assistant.tool.*`: latency and outcome for each parallel assistant data source and long-term memory write

## MCP Tools

The MCP Streamable HTTP endpoint is `POST /mcp`. Send the same `Authorization: Bearer <JWT>` header used by the REST API. User-specific tools derive the user identity from that JWT; no `userId` argument is accepted.

- `search_diet_knowledge`: hybrid dietary knowledge retrieval
- `route_assistant_skill`: select a registered assistant Skill
- `list_assistant_skills`: list available Skills
- `get_my_recent_meals`: get the authenticated user's recent meals
- `get_my_weekly_report`: get the authenticated user's latest weekly report

For a real initialize/list/call demonstration with a disposable account, see
[`deploy/MCP_DEMO.md`](deploy/MCP_DEMO.md). The Python client handles MCP sessions,
prints a summary, and can save a JWT-free request/response transcript.

## Function Calling and MCP

Set `MEALCHECK_ASSISTANT_FUNCTION_CALLING_ENABLED=true` to enable the learning/demo Function Calling path. The flow is:

```text
assistant request -> model receives tool schemas -> model returns tool_calls
-> MealCheck validates the allow-list and arguments -> local business service executes
-> tool result is appended to the conversation -> model returns strict JSON
```

The in-process assistant deliberately does not make an HTTP request to its own `/mcp` endpoint. Function Calling and the MCP server reuse the same underlying MealCheck services: Function Calling is the model-to-tool interaction inside this application, while MCP is the standardized boundary for external AI clients. This keeps the monolith efficient while still making the tools available to external MCP hosts.

## Verification and Operations Guides

Run the backend checks with Docker available:

```bash
cd backend
mvn verify -Pintegration
```

Run the frontend checks from `frontend`:

```bash
npm ci
npm test
npm run build
npx playwright install chromium
npm run test:e2e
```

Run the operations/evaluation contract tests from the repository root:

```bash
python -m unittest discover -s tools -p test_operations.py -v
```

Browser tests use deterministic API fixtures. Real PostgreSQL tests validate persistence and concurrency separately; neither establishes real model recognition accuracy. The CI definition is [.github/workflows/ci.yml](.github/workflows/ci.yml).

| Guide | Contents |
| --- | --- |
| [Production deployment](deploy/PRODUCTION.md) | Production environment, HTTPS edge and startup |
| [Backend optimizations](deploy/BACKEND_OPTIMIZATIONS.md) | Scheduler isolation, trusted proxy headers, upload admission, weekly dispatch, auth dependency handling, resumable vector builds |
| [RabbitMQ image analysis](deploy/RABBITMQ.md) | Outbox, consumer concurrency, acknowledgements, dead letters and recovery |
| [Reliability](deploy/RELIABILITY.md) | Idempotency, corrections, smoke checks and migration rollback constraints |
| [Operations and evaluation](deploy/OPERATIONS_AND_EVALUATION.md) | V17 async queue, alerts, encrypted backup, retention and visual evaluation |
| [Assistant evaluation](backend/src/test/resources/evaluation/README.md) | Dataset, judge, tool traces, manual review and comparison rules |
| [Reliability verification](deploy/VERIFICATION.md) / [Optimization verification](deploy/OPTIMIZATION_VERIFICATION.md) | Dated local acceptance results and remaining production checks |
| [Load testing](loadtests/README.md) | k6 scenarios and interpretation of results |
| [MCP demonstration](deploy/MCP_DEMO.md) | Authenticated initialize/list/call example |

图片分析容量测试使用 `loadtests/analysis-capacity.js`，支持固定新增任务速率、突发提交、独立幂等键、轮询到任务结束及端到端 P95/P99；结果同时核对失败、拒绝、超时、未完成和丢弃迭代。测试说明包含模拟模型、批量账号准备和并发 2/4/8 的对比步骤。模拟测试不代表真实模型供应商容量。

Flyway migrations now include V7–V18 for evaluation history, coordination tracking, snapshot fencing, corrections, durable analysis and the RabbitMQ outbox. V16 changes the weekly-job unique index; V17 adds the async queue and evaluation snapshots; V18 adds transactional dispatch and backfills unfinished image tasks. Before rolling back, stop new submissions, handle pending tasks, and follow the compatible-version or consistent-backup restore procedure in the guides.

## Pictures

最新后端代码完善及 V25 迁移说明见 [后端代码完善（2026-10-10）](deploy/BACKEND_IMPROVEMENTS.md)，包含任务重试、图片存储、分批清理、交互 AI 名额预留、周报断点续跑和全站额度监控。

The images below show earlier interfaces. The current workspace navigation and page separation are described in the recent improvements above; these images are not acceptance evidence for the latest UI.

### user 

![user](pictures/user1.png)
![user](pictures/user2.png)
![user](pictures/user3.png)

### admin

![admin](pictures/admin1.png)
![admin](pictures/admin2.png)
![admin](pictures/admin3.png)
![admin](pictures/admin4.png)
![admin](pictures/admin5.png)
![admin](pictures/admin6.png)

### assistant

![demo](pictures/assistant1.png)
![demo](pictures/assistant2.png)
