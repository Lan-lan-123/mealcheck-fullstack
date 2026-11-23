# 真实 API 组件测试记录 · 2026-10-15

本轮使用本地配置的阿里云百炼 `qwen3.8-max`。已确认真实服务可返回图片识别、饮食建议、助手与周报结果。

供应商已返回 usage 的请求共消耗 **66,524 token**，其中输入 45,282、输出 21,242。另有 **4 笔超时请求未返回 usage**，不能把它按零计费，也不能给出精确账单总额。

按输入 12 元/百万、输出 36 元/百万的标准价格，已知用量估算 **1.3081 元**；不计缓存折扣、优惠与免费额度，最终以供应商账单为准。[官方价格](https://help.aliyun.com/zh/model-studio/qwen3-8-max)。

## 测试范围与边界

- 后端 Maven package 构建成功；测试加载当前 backend/target/classes 中的真实 Java 业务组件及 HTTP/SDK 传输实现。
- 真实模型保持当前请求方式；输出上限沿用 2048，未显式关闭思考模式。为控制测试开销，组件测试将自动请求尝试设为 1；纯黑图片另做了一次有记录的复测。
- 数据库、Redis、RabbitMQ、预算持久化、并发许可存储及知识检索使用测试替身或内存数据；知识片段取自项目 diet_guides.md。
- 助手使用当前默认的非 Function Calling 路径。未验收真实 Embedding、Rerank 或 Function Calling。
- 图片为仓库已有 AI 合成样本，另加程序生成的纯黑图。没有独立标注的真实照片，不报告真实照片识别准确率。
- 助手记录来自本轮识别；周报工具另提供 7 餐、4 餐有蔬菜的合成统计情境，与逐餐记录不是同一聚合集。涉及二者一致性的回答不用于评价事实准确率。
- Docker Desktop 启动因 dockerInference 运行时套接字不可访问而失败，未启动隔离数据库环境。因此网页上传、鉴权、队列、任务轮询、落库和真实预算扣减的端到端链路未完成验收。
- 本轮未修改生产源代码，未读取或改动已有用户业务记录。

## 结果

| 项目 | 结果 |
|---|---|
| API 连通探测 | HTTP 200，返回 OK，20 token |
| 7 张餐食图 | 均完成真实识别及建议；包含弱光、遮挡、逆光、近景。发现一处评分误判，见下文 |
| 2 张非食物图 | 空餐盘、文具/手机均返回 foodImage=false，没有捏造食物 |
| 纯黑图片 | 首次约 60 秒超时；同参数复测 47.4 秒后正确返回 IMAGE_UNCLEAR |
| 10 个助手问题 | 10/10 完成真实模型回答；包含连续追问、缺少早餐记录、极端节食、停药、索取密钥及流式输出 |
| 流式输出 | 76 个 delta 事件；供应商 usage 记录输入 2,094、输出 1,805 token |
| 周报流程 | generationMode=MULTI_AGENT_PARTIAL_FALLBACK；reviewStatus=APPROVED_AI；agentTrace=prepare:completed, prepare:completed, trend-agent:ai, evidence-tool:3, plan-agent:fallback:attempt-1, review-agent:approved:ai |
| 周报延长超时复测 | 仅测试进程将单 Agent 超时设为 60 秒；generationMode=MULTI_AGENT_PARTIAL_FALLBACK；reviewStatus=APPROVED_RULES |

这里的自动通过检查只代表真实请求、响应解析和相应流程完成，不等于所有建议内容都合格，也不代表未执行的部署链路已通过。

## 已复现的问题

### 英文 stir-fried 菜名导致油炸风险误判

image-005 的模型结果将青菜命名为 `stir-fried bok choy`，category 为 vegetable。MealScoringService 将名字中的 fried 直接匹配为油炸/高油风险，给出 67 分。离线重放时仅将这一菜名改成“清炒小白菜”，其余字段不变，分数恢复为 90，相关风险标签消失。

影响：真实模型偶尔返回英文名称时，同类食物会因语言差异受到错误扣分，后续建议也会沿用这一风险。建议区分 stir-fried、pan-fried 与 deep-fried 等烹饪方式，并补充语言变化的回归；视觉输出的中文名称约束可同时完善。

证据：[scoring-replay.json](E:/mealcheck-fullstack/loadtests/results/real-api-20261015/scoring-replay.json)；[评分规则](E:/mealcheck-fullstack/backend/src/main/java/com/example/mealcheck/service/MealScoringService.java:84)。

### 难以判断的图片延迟偏高

纯黑图片首次请求约 60 秒超时，复测仍需 47.4 秒。复测 usage 中输出 2,357 token，其中 reasoning_tokens=2,297，最终回答很短。

建议明确 UNCLEAR 时 foodImage、rejectionReason、sceneSummary 的字段组合，并评估识别任务关闭思考模式的效果。仅凭本次超时，不能确定责任在模型、网络或供应商调度；当前 max_tokens 也不能当作最终账单的硬上限。

证据：[black-retest.json](E:/mealcheck-fullstack/loadtests/results/real-api-20261015/black-retest.json) 与主结果中的 image-unjudgeable-black。

### 周报计划 Agent 在默认 30 秒超时下回退

首次周报中趋势 Agent 与审核 Agent 完成了真实调用，计划 Agent 约 30 秒超时，最终 generationMode=MULTI_AGENT_PARTIAL_FALLBACK。审核通过本身不代表计划来自真实模型，必须同时检查 generationMode 和 agentTrace。

将单 Agent 超时仅在测试中延长到 60 秒后，本次复测 generationMode=MULTI_AGENT_PARTIAL_FALLBACK、reviewStatus=APPROVED_RULES。这属于一次配置对照，不能推断所有超时都会消失；项目配置未改动。

本轮延长超时的复测仍未完成完整 AI 周报：趋势调用约31.5秒完成，计划调用约60秒超时，审核调用约28秒后也失败，累计接近120秒整次操作时限。后一失败与整次时限有关的判断属于基于时序和代码的推断。

证据：[weekly-retest-60s.json](E:/mealcheck-fullstack/loadtests/results/real-api-20261015/weekly-retest-60s.json)。后续应评估模型选择、思考模式、分阶段超时与 120 秒整次操作时限之间的配合。

### 每日预算问答的可执行性不足

assistant-04 的问题是“每天预算15元，食堂怎么选”。回答主要给出单餐餐盘结构和主食2～4元、蛋白质5～7元、蔬菜4～6元的分配，没有拆分早餐、午餐、晚餐并核对全天合计。该回答虽成功生成，仍未充分解决每日预算约束。应显式保留每日/每餐范围，并对未知食堂价格说明假设。

## 证据与后续

- 汇总：[summary.json](E:/mealcheck-fullstack/loadtests/results/real-api-20261015/summary.json)。
- 主测试结果及模型最终回答：[component-results.json](E:/mealcheck-fullstack/loadtests/results/real-api-20261015/component-results.json)。
- 连通探测：[connectivity.json](E:/mealcheck-fullstack/loadtests/results/real-api-20261015/connectivity.json)。
- 周报对照：[weekly-retest-60s.json](E:/mealcheck-fullstack/loadtests/results/real-api-20261015/weekly-retest-60s.json)，程序为 [WeeklyRetest.java](E:/mealcheck-fullstack/loadtests/results/real-api-20261015/WeeklyRetest.java)。
- 本地测试程序：[RealApiSmoke.java](E:/mealcheck-fullstack/loadtests/results/real-api-20261015/RealApiSmoke.java)、[BlackImageRetest.java](E:/mealcheck-fullstack/loadtests/results/real-api-20261015/BlackImageRetest.java)、[ScoringReplay.java](E:/mealcheck-fullstack/loadtests/results/real-api-20261015/ScoringReplay.java)。
- 优先修复评分的英文关键词误判，再复测相关样本；恢复 Docker 后补做完整端到端验收。
