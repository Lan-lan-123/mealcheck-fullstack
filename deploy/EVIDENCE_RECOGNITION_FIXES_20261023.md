# 引用、信息不足与空餐盘处理完善

日期：2026-10-23，Asia/Shanghai。

## 最终行为

1. 引用采用现有来源/段落编号绑定，后端提取连续原文。新增真实供应商失败回放：便利店题中使用 `...` 拼接的引文被剔除，两个有效结论保留，最终内容标记为部分回答。使用段落编号可以绑定多个原文片段，不必生成伪连续引文。连续引用本身不保证结论受支持，生产语义复核仍需通过。
2. 证据复核新增 `informationStatus` 和 `missingInformation`：`SUFFICIENT` 表示所求信息足够；`MISSING_INPUT` 表示缺少用户配方、份量或标签等输入，但可给出有依据的解释；`MISSING_KNOWLEDGE` 表示核心解释缺少来源依据，需要在原有预算内补检。前两者不得请求补检。字段格式、缺项列表和补检标记矛盾时不放行未经完整复核的结论；旧版无这两个字段的审核记录保留兼容。缺失输入不会被补成零或测量值。
3. “无法从照片确定油量”的有依据解释可以保留，不等于实际油量已知。新增生产编排回归确认只执行原问题的一次固定检索和生成/复核，不为缺失的私人事实发起补检。原简化测试中的 `answerability=ANSWERABLE` 不作为生产分类依据，原始失败记录保持原样。
4. 视觉提示明确输出 `sceneType=FOOD|EMPTY_TABLEWARE|NON_FOOD|UNCLEAR`。清晰且明确标为无食物空餐具的场景才规范为非食物；不会仅因 `foods=[]` 推断空餐盘。模糊状态优先于场景分类，仍要求重新拍摄。旧版无 `sceneType` 的有效响应保持兼容。
5. 清晰结果中有食物标记却没有食物，或非食物标记却携带食物，返回 `RECOGNITION_INCONSISTENT`（HTTP 502）。任务错误码及前端显示保留这一原因，提示使用原图稍后重试；矛盾结果在进入评分和非食物上传惩罚之前被拦截。没有增加自动模型重试。

`informationStatus` 是助手内部生成/复核结果元数据，现有对外 `AssistantResponse` 字段没有变化；用户仍通过有依据的解释与部分回答说明了解信息边界。`sceneType` 为供应商解析协议字段，不增加数据库列。新错误码需要同步部署前后端。

## 样本与测试边界

`backend/src/test/resources/evaluation/no_embedding_captured_regressions.json` 保留三条实际输出及来源文本，附原始结果 SHA；这些是先前直接调用的简化提示词输出，不伪装为生产提示词输出或独立人工金标。

覆盖：拼接引文剔除与有效内容保留、多段原文绑定、合理未知解释、复核分类矛盾、历史复核兼容、生产补检次数、空餐盘、模糊优先、相互矛盾的识图字段和前端错误提示。回归使用模型替身，不联网，不调用 embedding。

上述首轮仅完成本地回放，当时没有重跑真实供应商质量测试或重启业务容器。后续真实复测及部署见下文；回放结果本身不代表线上命中率。

## 最终验证

- 后端受影响路径验收：81项通过、失败0、错误0、跳过0。日志：`logs/backend/evidence-recognition-acceptance-20261023-150101-257574.log`；XML位于同名任务时间目录 `logs/backend/evidence-recognition-acceptance-20261023-150101/`。
- 前端错误提示及任务解析：3项通过。日志：`logs/frontend/evidence-recognition-frontend-20261023-145630-299672.log`。
- 前端类型检查通过：`logs/frontend/evidence-recognition-typecheck-20261023-145831-675464.log`。
- 改动文件空白检查通过；根目录四份历史日志大小不变，未新增根目录日志。首次编译中测试文件的错误导入已修正；保留失败日志，最终通过结果来自后续完整运行。

## 后续生产代码复测与部署（同日 20:35）

在用户明确允许发送固定项目材料后，新增 `NoEmbeddingProductionAcceptanceTest`。它使用生产提示词、编排、HTTP 传输、引用/语义校验和识图解析；知识读取由固定片段替代，不连接向量客户端或业务数据库。只允许现有百炼 Chat Completions 地址和 `qwen3.8-flash`，单轮最多10次请求，异常停止，不自动重试。它不是经过真实 HTTP 业务入口的完整 RAG 检索测试。

固定题目与原文来自正式测试资源 `evaluation/no_embedding_captured_regressions.json`，与原先批准的输入逐字核对相同；不再依赖未提交的历史日志输入。每次运行使用新目录，保存脱敏请求、供应商原始响应、请求 SHA、调用账本、最终回答和通过/失败数，不复用其他轮次的问答结果。

真实复测发现并修复了以下额外问题：

- 非流式助手生成和证据复核对当前受控 Qwen 型号启用原生 `json_object` 输出，避免实际出现的缺少 JSON 结尾。已有显式格式设置保留，文本流式输出保持原协议，未将此策略扩展到其他供应商。
- 状态含义明确为“所问实际事实是否齐全”。有依据地回答“无法确定”可以回答问题，同时仍为 `MISSING_INPUT`；一般搭配方法无需个人测量值时为 `SUFFICIENT`。新接口复核必须包含状态和缺项列表，漏填不能走历史记录兼容分支；历史回放仍可接受旧字段结构。
- 可选补检参数错误及预算耗尽后的额外请求不再让已取得的原问证据整体丢失。错误参数不执行，仍在原有单次补检预算内处理。
- 先按模型实际提交的查询去重，再追加检索焦点词。重复原问不会因关键词扩展而伪装成新查询；重复请求返回 `SKIPPED`，关闭可选补检窗口，继续使用已有证据。真正缺少来源时的补检仍保留。

最终验收证据按运行阶段记录：

| 范围 | 结果 | 证据目录或日志 |
| --- | --- | --- |
| 最终后端离线回归 | 86项通过，失败/错误/跳过均0 | `logs/backend/evidence-dedup-regression-20261023-203022-532927.log`；XML目录 `logs/backend/evidence-dedup-regression-20261023-203022/` |
| 最终两道生产问答 | 2/2通过，5次真实模型请求，embedding 0 | `logs/backend/no-embedding-dedup-live-20261023-203151/` |
| 正常餐食、空餐盘、暗图 | 3/3通过；两图各1次识图请求，暗图本地拒绝、0请求 | `logs/backend/no-embedding-contract-live-20261023-202647/` 中对应结果及第07、08次请求；该批另含尚未修复去重的问答，整批当时失败，不能称整批通过 |
| 最终后端部署 | 镜像构建、启动完成 | `logs/deploy/evidence-dedup-deploy-20261023-203227-856489.log` |
| 部署后的配置与页面 | 7项检查通过，桌面/手机无浏览器错误、手机布局正常，临时账户已删除，供应商请求0 | `logs/deploy/ai-settings-live-2026-10-23T12-33-25-040Z/` |
| 最终运行状态 | 5个容器 healthy，健康接口 UP，数据库迁移V45，知识片段1351、单一向量签名，只有既有1项成功重建任务 | `logs/deploy/evidence-final-runtime-20261023-203406/`；任务表核验以 `database-summary-corrected.txt` 为准，首次表名拼写错误的输出保留 |

最终便利店题：`SUFFICIENT`、缺项列表为空，原文引用有效。用油题：`MISSING_INPUT`，列出配方/用量记录及实际份量缺项，`partial=true`，保留“不知道”解释且不编造油量；实际固定检索仅1次，引用有效。正常餐食返回食物清单，清晰空餐盘返回 `foodImage=false`、空清单及空餐具原因，暗图返回 `IMAGE_UNCLEAR`。

本轮续作的三次供应商复测分别调用7、8、5次，均无向量调用；前两轮暴露漏字段和重复检索问题，不能合并成一次全通过结果。更早的 JSON/参数失败记录也保留在原目录，没有修改预期类别或删除失败记录。

当前部署文字/识图均为 `qwen3.8-flash`，重排启用 `qwen3.7-text-rerank`，索引版本 `v8-longform-200-boundary-v2`。部署时通过进程环境显式设置 `MEALCHECK_KNOWLEDGE_AUTO_INDEX=false`，未重建向量；该临时覆盖没有写入 `.env`，后续重新创建容器时仍需明确选择索引开关。开发/生产 Compose 已补齐开关透传及当前来源版本默认值。

前端错误提示3项测试及类型检查沿用前文记录；本轮后续没有再改前端业务代码。部署会正常应用已有数据库迁移；页面验收只新增并删除自己的临时账户，没有修改现有用户记录或管理员模型配置。

## 复跑方式与剩余边界

默认单元测试不会联网；真实复测必须显式开启 `mealcheck.noEmbeddingLive=true`，指定新建且为空的 `logs/` 输出目录。以下示例只跑两道问答；去掉 `mealcheck.noEmbeddingTextOnly` 可再运行两张已批准合成图和本地暗图。使用现有 `.env` 连接配置，运行者须具有发送这些固定材料的授权。

```powershell
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fffffff'
$out = "E:/mealcheck-fullstack/logs/backend/no-embedding-live-$stamp"
$reports = "E:/mealcheck-fullstack/logs/backend/no-embedding-reports-$stamp"
New-Item -ItemType Directory -Path $out | Out-Null
python tools/run_logged.py --group backend --name no-embedding-live --cwd backend -- mvn `
  '-Dtest=NoEmbeddingProductionAcceptanceTest' '-Dmealcheck.noEmbeddingLive=true' `
  '-Dmealcheck.noEmbeddingTextOnly=true' '-Dmealcheck.noEmbeddingMaxRequests=8' `
  "-Dmealcheck.noEmbeddingOutput=$out" "-Dmealcheck.testReportsDirectory=$reports" test
```

部署配置及桌面/手机页面可单独运行 `python tools/run_logged.py --group deploy --name ai-settings-configuration -- node deploy/scripts/verify_admin_ai_settings.mjs --configuration-only`；该模式不保存模型设置、不做连接测试、不发起助手或餐食分析请求。

这些样本证明本次具体缺陷已有可复现的回归和实际响应证据，不能据此估计总体准确率。仍待后续验证：真实查询向量→召回→重排→回答的完整链路、独立留出题集，以及知识库内容的独立复核。本次固定片段试验没有覆盖完整召回链路或重新测试重排接口。
