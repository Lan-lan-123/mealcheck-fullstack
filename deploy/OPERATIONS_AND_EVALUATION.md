# 异步分析、监控备份与评测运行说明

## 当前实现与实际验证

2026-10-02 起图片分析默认通过 RabbitMQ + Outbox 分发，每实例默认并发 2 条。新的部署、确认、死信和恢复机制见 [RabbitMQ 运行说明](RABBITMQ.md)；下文单线程描述及验收数字对应此前 V17 数据库 Worker。

新分析接口提交后返回 `202 / PENDING`，数据库保存经过解码、大小校验和归一化的图片。后台独立线程领取任务，成功时在同一事务中保存餐食、画像和结果，并清除排队图片。每实例同时处理一条分析；多个实例用账户锁和尝试令牌协调。进程中断后租约过期可自动重新领取，最多恢复三次。模型报错后显示失败，由用户使用原图重试；供应商内部调用已有有限重试，避免再增加一层自动重试造成重复计费。

前端自动查询 `PENDING / PROCESSING`，断网时有限重试查询；刷新会继续跟踪原标识。超过查询时间上限保留标识，稍后可继续查询。租约判断使用数据库时间，避免应用与数据库时区不同造成误判。兼容 `/api/meals/analyze` 仍支持旧同步客户端。

本轮本地验收：

- 真实 PostgreSQL 验证队列持久化、重复提交额度复用、恢复尝试隔离、结果事务，以及评测历史在标签修改和原餐食删除后仍可读取。
- 7 条浏览器验收涵盖移动端自动查询、窗口宽度、Tab 焦点循环、Esc 返回、审核表单、评测失败案例及管理页旧响应隔离。
- 5 个独立账号进行 20 秒混合负载：364 次请求，检查全部通过，HTTP 错误率 0；提交 P95 260ms、列表 P95 145ms。模型由 50ms 延迟的确定替身提供，不能当作真实模型容量结论。
- 分页执行计划使用 `idx_meal_records_user_created_id`。测试数据仅 5 条餐食；大规模筛选、画像重建和实际供应商瓶颈仍应使用代表性数据验证。
- Prometheus 实际采集了 5 次成功分析和模型成本计数。模型单价为测试值，不能作为业务成本；备份重启后的进程计数器会重置，告警使用 `rate/increase`。
- 15 条 Prometheus 告警通过 `promtool check rules`。
- V17 完整迁移库恢复验证 21 张表、1 条图片引用、1 张排队图片；实际应用库恢复验证 24 张表、5 条图片引用。
- 使用真实 rclone crypt 完成加密复制、读回内容校验及重新下载恢复。存储目标是本地独立测试目录；尚未验证生产云权限、跨地域网络或正式通知接收端。

## 监控与告警通知

生产后端通过内部 `9091` 端口提供 Prometheus 指标，不映射到宿主机。公网应用端口仍需鉴权才能访问指标。Compose 监控组件只绑定宿主机 loopback 地址。

将 `deploy/operations.env.example` 的实际配置放入私有 `.env.operations`，不要提交 webhook 或云凭据。配置企业微信使用 `MEALCHECK_ALERT_PROVIDER=wecom`，钉钉普通文本 webhook 使用 `dingtalk`，其他接收端使用 `generic`。需要额外签名的机器人应通过已有受信通知网关接入。通知转发失败返回非 2xx，Alertmanager 重试；服务启动时拒绝空地址和非 HTTPS 地址。

```bash
docker compose --env-file .env.production --env-file .env.operations -f docker-compose.prod.yml -f deploy/compose.monitoring.yml up --build -d
```

内部流程为 `Prometheus → Alertmanager → notification → webhook`。通知只包含告警标题、级别和说明，不包含账号、图片或模型响应。测试通知后必须同时确认触发与恢复通知实际到达，并在正式数据上调整失败率、P95、积压时长和成本预算阈值。

为 `MEALCHECK_AI_INPUT_COST_PER_MILLION`、`MEALCHECK_AI_OUTPUT_COST_PER_MILLION` 配置同一币种的实际合同单价。仅默认模型及对应供应商地址使用该价格；其他模型、缺少 usage 的响应或单价未配置时视为成本未知。指标为成功响应的用量估算，供应商可能对重试或失败请求计费，最终以供应商账单为准。缺失用量或价格会触发单独告警。

本机 Docker Hub 对部分新镜像拉取超时，完整 Alertmanager/Pushgateway 容器启动未验收；通知协议已通过本地真实 HTTP 接收端测试，备份指标已通过测试接收端验证。生产环境仍需拉取镜像、启动全栈并验证实际通知与 Pushgateway 抓取。

## 每日异地备份、每周恢复

部署主机安装 Python 3.11+、Docker Compose、rclone。配置 rclone `crypt` remote，其下游必须是受限的异地私有存储；配置文件应仅运维账号可读，并将加密恢复所需配置保存到独立的受限位置。

`.env.operations` 中 `MEALCHECK_BACKUP_REMOTE` 指向专属 `cryptremote:mealcheck-backups` 子目录。脚本拒绝普通未加密 remote。每日备份暂停单实例后端写入，保存数据库、已发布图片和数据库内的排队图片，恢复后端，再上传并读取远端内容进行校验。外部数据库写入者必须先停止。多实例、托管数据库或对象存储部署需要相应适配，不能直接使用单实例暂停方案。

以下手动命令要求配置已注入当前进程环境；systemd service 会通过 `EnvironmentFile` 自动读取 `.env.operations`。单价用同一币种配置，金额应在运维看板注明币种。

```bash
python deploy/scripts/operations_backup.py --mode backup --output /var/lib/mealcheck-backups
python deploy/scripts/operations_backup.py --mode restore --output /var/lib/mealcheck-restore-drills
```

每周恢复从 remote 下载最新备份，在新的隔离数据库容器中恢复，核验全部表行数、图片哈希、引用和排队图片校验值；不覆盖源库。成功/失败与最近成功时间推送到 loopback Pushgateway。即使进程在发布指标前中断，缺少最近成功时间的告警仍能发现遗漏。

Linux 部署目录默认 `/opt/mealcheck-fullstack`。修改 `deploy/systemd/*.service` 中路径后，复制四个 unit 到 `/etc/systemd/system/`，执行：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now mealcheck-backup.timer mealcheck-restore.timer
sudo systemctl start mealcheck-backup.service
sudo systemctl start mealcheck-restore.service
sudo systemctl list-timers 'mealcheck-*'
```

每日 02:00 UTC 备份，每周日 04:00 UTC 恢复，漏执行会补跑。首次手动备份、恢复与告警验收全部通过后，才视为已落地。当前只提供部署 unit，未在用户机器或生产服务器安装、启用定时任务。备份与恢复输出为新的私有目录；云侧和本地备份保留期应按恢复窗口设置生命周期，脚本不会自动删除远端备份。

## 识别质量评测

提供引擎和空模板 `tools/vision-dataset.template.json`，不把模型输出或纠错评分案例冒充独立图片标注集。需要获授权的真实图片，人工独立标注，并保持测试集与提示词调试集分离。

另提供 [100 张合成图片回归样本](../tools/datasets/synthetic-meals-100/README.md)。合成样本必须显式使用 `--allow-synthetic`，报告标记为 `synthetic-regression-only`，不能设置发布质量阈值，也不能与真实照片报告混合比较。初始标签来自生成场景，画廊提供独立人工审核入口。

数据集目录内每个案例格式：

```json
{
  "id": "public-case-001",
  "image": "images/meal-001.jpg",
  "sha256": "实际图片SHA256",
  "foodImage": true,
  "foods": [{"name": "米饭", "category": "staple", "aliases": ["白米饭"]}]
}
```

顶层须包含版本、`independentlyAnnotated: true`、`authorizedForEvaluation: true` 和非空 `cases`。空集、重复 ID、路径越界和图片哈希改变都拒绝评测。建议覆盖不同菜品、遮挡、光照和非餐食图片，先形成明确的标注规范与人工复核样本。

管理员令牌通过私有环境变量 `MEALCHECK_EVALUATION_TOKEN` 配置。以下命令实际调用供应商，会产生费用；该接口不创建个人餐食记录。

```bash
python tools/vision_evaluation.py --dataset /private/vision/dataset.json --run --base-url https://预发布域名 --output /private/vision/run-001.json
python tools/compare_vision_evaluations.py /private/vision/run-001.json /private/vision/run-002.json --output /private/vision/comparison.json
```

也可使用 `--predictions /private/predictions.json` 离线评分，预测以案例 ID 为键。按精确名称及人工声明别名做一对一匹配，输出 precision/recall、漏识别/多识别、匹配项分类准确率、食物图片判断准确率、P95、成本及逐题分类错误。未知成本为 null，不报告为免费；失败调用计入错误和漏识别。可用 `--min-recall`、`--min-precision`、`--min-category-accuracy` 设置发布阈值；质量未达标返回 2，调用失败返回 1。

报告使用新文件写入，保存数据集指纹、版本、模型和运行 ID。数据集不同的运行不能直接比较质量变化。没有真实图片和独立标签，本次不提供准确率结论。

## 评分回归历史与数据保留

管理端“识别纠错评测”使用名称/分类表单审核，独立填写期望分数；“运行并保存评测”在数据库保存脱敏数据集快照与逐题结果。可查看失败案例、导出固定回归集、比较历史运行。同一指纹可比较规则变化；输入或标签不同会明确提示不可直接比较通过率。

每天清理失败任务图片（7 天）、失败请求账本（90 天）、失效周报任务（30 天）和管理员审计（180 天）。相应 `MEALCHECK_RETENTION_*_DAYS` 可调整，设为 0 禁用某项；整体可用 `MEALCHECK_RETENTION_ENABLED=false` 禁用。餐食、正在排队/处理的任务、成功幂等标识和固定评测历史不按这些策略删除；餐食已删除时清除结果缓存，保留不含结果的幂等标识。生产保留期须符合业务删除和审计需求。

V17 新增队列字段与历史评测表。沿用 V16 的回退约束；旧版本无法消费异步任务。回退前停止新提交、完成或妥善迁移待处理任务，并按一致备份方案恢复兼容版本。

## 重跑与验收证据

```bash
cd backend && mvn verify -Pintegration
cd ../frontend && npm test && npm run build && npm run test:e2e
cd ..
python -m unittest discover -s tools -p test_operations.py -v
python deploy/scripts/recovery.py self-test --migrations backend/src/main/resources/db/migration --output deploy/recovery-results/new-v17-check
```

混合压测见 `loadtests/mixed.js` 与 `deploy/compose.capacity.yml`。模型替身和放宽注册限流只属于专用测试配置，不能合并到生产项目。测试用账号/JWT、备份和运行时均被 Git 忽略。

实际证据：`loadtests/results/mixed-v17.json`、`deploy/recovery-results/v17-self-test/restored/verification.json`、`deploy/recovery-results/rclone-fixture/verification.json`、`deploy/recovery-results/v17-metrics-historical.json`。以上是本轮本地结果，未执行生产发布。
