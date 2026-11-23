# 对话选餐真实模型验证：2026-10-24

本轮使用 `.env` 中配置的真实 `qwen3.8-flash`，四组业务场景全部通过。成功一轮共发生 8 次真实模型 HTTP 请求，响应模型名均为 `qwen3.8-flash`。

| 场景 | 输入 | 实测结果 | 本轮用时 |
|---|---|---|---|
| 首次推荐 | 晚餐、在家、25 元、30 分钟 | 模型调用 `recommend_next_meal`，实际搭配库返回 3 套方案 | 5.49 秒 |
| 连续追问 | 改成外卖，预算仍为 25 元 | 重新调用工具，`scene=TAKEOUT`，预算与 30 分钟条件保留 | 5.91 秒 |
| 缺少条件 | 推荐下一餐搭配 | 未调用推荐工具，询问餐次、场景、预算和时间 | 5.22 秒 |
| 无匹配方案 | 晚餐、在家、1 元、5 分钟 | 按原参数调用工具，返回无匹配方案，未提高预算或编造方案 | 3.80 秒 |

上述用时是一次验证的观察值，不是性能基准。

## 实测发现与修复

1. 模型曾把上轮方案直接改写成“已重新查询外卖”，而没有发起新调用。后端现在根据当前及连续用户输入检查条件完整性；条件完整且合法时，发送 `tool_choice=required`，要求模型生成新的推荐调用及参数。查询成功后恢复自动模式。缺少条件时继续使用自动模式，允许追问；未查询的回答不能冒充推荐结果。
2. 模型曾自行认定 1 元不可行，建议用户先改预算。1 元和 5 分钟是接口允许的下限；现在由真实搭配库判断是否有结果，模型不能替代查询或擅自放宽条件。
3. 模型曾返回未转义引号的损坏 JSON。选餐结果现在会校验和规范化；格式不合法时，根据本轮真实工具结果构建展示内容，不把损坏 JSON 直接交给用户，也不额外编造食物、费用和时间。搭配库 ID 不作为知识引用返回。

这些修复均有针对性的回归测试。普通回归默认跳过真实模型测试，防止 CI 意外调用付费接口。

最终普通后端回归共 755 项：751 项通过、4 项跳过、0 失败、0 错误；真实模型入口另行启用后通过。本次后端 JAR 已重新构建并通过 ZIP 完整性检查。普通回归报告位于 `logs/backend/function-calling-final-unit-20261024-1958/reports/`。

## 验证范围

- 真实执行：模型 HTTP 请求、项目模型适配器、Function Calling 循环、参数校验、推荐工具和搭配库筛选。
- 隔离夹具：空餐食数据、默认饮食偏好、均衡目标、数据库配额和并发许可；没有创建或修改现有业务账号。
- 本轮未完成：真实网页 SSE 和数据库持久化的应用联调。本机 Docker Desktop 因 `dockerInference` 运行时文件无法访问而不能启动；没有重置 Docker 数据，也没有更改模型运行器设置。上一轮独立的浏览器与数据库回归不能替代本轮真实应用联调。

模型仍负责理解用户参数及生成工具请求；后端负责白名单、必须查询的业务规则、执行与结果校验。这里不声称模型拥有不受约束的自主执行权限。

## 本地证据

- 成功结果：`logs/backend/function-calling-real-model-20261024-1952/real-model-results.json`
- 成功测试报告：同目录 `reports/`
- 成功运行日志：`logs/backend/function-calling-real-model-complete-20261024-195011-125222.log`
- 失败轮次证据分别保留在 `logs/backend/function-calling-real-model-20261024-1933/`、`logs/backend/function-calling-real-model-20261024-1940/` 和 `logs/backend/function-calling-real-model-20261024-1947/`，没有覆盖历史证据。

证据包含模型名、响应 ID、Token 用量、实际调用参数、工具返回摘要和最终回答，不包含 API 密钥、请求头或模型推理字段。

完整应用联调可在 Docker 恢复、服务使用最新构建后，运行 `deploy/scripts/verify_function_calling_live.py`。脚本说明见 [对话选餐演示指南](FUNCTION_CALLING_MEAL_PLANNING.md)。

## 重复运行真实模型循环验证

在项目根目录的 PowerShell 中执行以下命令。它会产生少量真实模型请求，普通测试不需要设置这两个环境变量。

```powershell
$fcBatch = Join-Path (Get-Location) ('logs/backend/function-calling-real-model-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$env:MEALCHECK_LIVE_MODEL_VERIFY = 'true'
$env:MEALCHECK_LIVE_MODEL_OUTPUT = $fcBatch
python tools/run_logged.py --group backend --name function-calling-real-model --cwd backend -- mvn -q '-Dtest=MealPlanningRealModelTest' "-Dmealcheck.testReportsDirectory=$fcBatch/reports" test
Remove-Item Env:\MEALCHECK_LIVE_MODEL_VERIFY
Remove-Item Env:\MEALCHECK_LIVE_MODEL_OUTPUT
```
