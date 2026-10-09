# MealCheck 本轮可靠性改进

本轮实际验证结果与边界见 [验收记录](VERIFICATION.md)。
后续异步任务、监控备份和评测历史见 [运行说明](OPERATIONS_AND_EVALUATION.md)。

## 已实现的行为

- 周报详情按服务端返回的 `periodStart` / `periodEnd` 独立分页查询，不受历史列表的翻页、目标、评分筛选影响。
- 切换助手会话、新建会话、组件卸载会取消或忽略旧请求。历史列表也拒绝迟到的旧查询结果。
- 前端统一保留 HTTP 状态、错误码、请求 ID 和 `Retry-After`；401 回到登录页，分析超时可找回已保存的结果。
- 每次图片提交生成一个请求标识。网络失败后的重试复用标识；刷新页面会查询未确认的请求结果。
- 用户可纠正食物名称、分类和漏识别项；每次修改保留前后结果和原因，重新计算评分及完整画像。
- 新增、删除、纠正餐食都会让旧周报失效。旧任务保留为 `STALE`，同日可创建新任务，旧 Agent 尝试不能再发布快照。
- 管理端“识别纠错评测”可审核脱敏样本、人工标注期望评分、执行回归并导出固定数据集。
- 生产前端增加独立入口网络，保持宿主机 loopback 绑定；后端健康检查验证 HTTP 健康状态。
- AI API Key 不再使用源码内的默认值，需要通过 `MEALCHECK_AI_API_KEY` 配置。

## 图片分析接口与重试

```http
POST /api/meals/analysis-requests
Authorization: Bearer <JWT>
Idempotency-Key: <16 到 80 位字母、数字、下划线或短横线>
Content-Type: multipart/form-data

image=<图片>
goal=current
```

返回 `{ key, status, result, retryAfterSeconds }`。成功为 `200 / SUCCEEDED`；处理中为 `202 / PROCESSING`，带 `Retry-After: 5`。

`GET /api/meals/analysis-requests/{key}` 查询当前用户的请求。`FAILED` 可用原图片重试；超过 20 分钟的处理租约显示为 `RETRYABLE`。成功后的餐食被删除时返回 410。

相同标识只能用于相同图片字节和目标参数。改变图片或目标返回 409。`current` 的实际目标在首次认领时固定，后续重试不会因用户更改目标而变化。成功结果及餐食、图片、画像在同一事务中保存。跨实例并发由数据库唯一键及账户锁协调；重试使用新的尝试令牌，过期尝试不能入库。

这保证一条请求最多发布一条餐食记录。进程在调用模型后崩溃、尚未保存结果时，恢复尝试可能再次调用供应商。供应商是否支持请求幂等需另行接入，不能承诺跨崩溃只计费一次。

兼容接口 `POST /api/meals/analyze` 也支持 `Idempotency-Key`。成功时保持旧的分析结果结构，处理中返回任务结构；不带标识的旧客户端保留原行为，迁移客户端时应补上标识。

前端在 `sessionStorage` 保存尚未确认的标识，不保存图片。刷新后若结果已完成，可以直接恢复；失败或租约过期时需重新选择原图。用户明确选择“另建一次分析”才会放弃原请求标识。

## 纠错与评测闭环

`POST /api/meals/{id}/corrections` 接受 `expectedRevision`、`foods: [{name, category}]` 和 `reason`。服务端验证归属和版本，版本不匹配返回 409。原始模型结果保存在 `original_foods_json`；修订记录保存在 `meal_corrections`。纠错后使用本地规则建议，数据库事务内不调用模型。

管理员通过 `GET /api/admin/meal-corrections?page=0` 查看候选样本，使用 `PUT /api/admin/meal-corrections/{id}/review` 提交单独的脱敏食物列表、期望分数、状态和脱敏确认。个人账户、图片路径、自由文本原因不会进入导出数据集。

`GET /api/admin/meal-corrections/evaluation` 返回数据集指纹、评分规则版本和逐题期望/实际分数。没有审核通过的样本时不能判定回归通过。指纹只包含人工确认的输入及标签，不包含本次评分输出。

将管理端导出的固定回归集保存到发布流程中，然后运行：

```bash
cd backend
mvn test -Dtest=MealCorrectionRegressionTest -Dmealcheck.regression.dataset=/absolute/path/meal-correction-regression.json
```

未提供外部数据集时运行项目内的五个人工规则基准案例。该评分回归集验证纠错与评分，不代替视觉模型准确率评测；真实视觉模型的独立图片标注集和供应商质量验证仍需要实际业务样本。

## 自动验证

```bash
cd backend
mvn verify -Pintegration
cd ../frontend
npm ci
npm test
npm run build
npx playwright install chromium
npm run test:e2e
```

后端使用真实临时 PostgreSQL 验证迁移、并发幂等、恢复租约、已删除记录、纠错事务、画像和周报失效。HTTP 测试覆盖 401 / 403、输入校验、脱敏确认和处理中响应。浏览器验收使用确定的 API 替身，覆盖真实页面交互；不把 API 替身测试当作真实模型或数据库的端到端测试。

CI 还会启动独立的生产配置栈，验证网页、注册、鉴权、餐食/周报查询、账号注销及联合备份恢复。冒烟不调用 AI。执行独立测试栈：

```bash
docker compose -p mealcheck-reliability-smoke --env-file deploy/smoke.env -f docker-compose.prod.yml -f deploy/compose.smoke.yml up --build -d --wait --wait-timeout 180
python deploy/scripts/smoke.py --base-url http://127.0.0.1:18080
```

`deploy/smoke.env` 只含公开测试值，不能用于真实部署。测试完成后仅清理该专属项目：

```bash
docker compose -p mealcheck-reliability-smoke --env-file deploy/smoke.env -f docker-compose.prod.yml -f deploy/compose.smoke.yml down --volumes
```

## 一致备份与恢复演练

适用于 Compose 内的单实例应用、PostgreSQL 和本地上传卷。先停止其他外部数据库写入者。备份脚本会暂停后端写入，保存数据库与图片，再恢复后端先前的运行状态；写入停顿长度取决于数据量。

```bash
python deploy/scripts/recovery.py backup --env-file .env.production --output /private/backups/mealcheck/new-backup
python deploy/scripts/recovery.py drill --backup /private/backups/mealcheck/new-backup --output /private/drills/mealcheck/new-drill
```

每次输出必须使用全新的目录。脚本验证归档与图片 SHA-256、全部公开表行数以及每条数据库图片引用。演练在新的无网络端口 PostgreSQL 容器内执行 `pg_restore --exit-on-error`，恢复图片到新目录，结束时删除临时容器和其匿名卷，保留验证报告。源服务及源数据不会被演练覆盖。

独立功能自检：

```bash
python deploy/scripts/recovery.py self-test --migrations backend/src/main/resources/db/migration --output deploy/recovery-results/new-self-test
```

备份包含私人数据，应加密后上传到私有异地存储。RDS、OSS、多应用实例及外部写入者需要相应的一致性备份方案。Redis 缓存不作为餐食数据恢复来源。恢复演练成功说明备份可还原，不表示已经完成正式环境切换；正式切换需检查迁移、配置、外部连接和应用功能。

新增 Flyway V16 扩展记录和纠错表，并调整周报任务的唯一索引。上线后旧版本二进制的周报 INSERT 与新索引不兼容，因此不能直接回退到旧镜像；需要先在维护窗口恢复迁移前的一致备份，或准备兼容 V16 的回退版本。
