# 识别结果可解释性（2026-10-14）

本轮增加低置信度食物核对、未计分候选展示，以及按失败原因提供操作。保留原有评分门槛、幂等请求标识、非食物上传限制、预算和并发控制；不新增数据库迁移或模型调用。

## 结果说明与核对

- 模型置信度低于 **0.8** 的已识别食物标记“待核对”，显示名称、分类、置信度及模型提供的识别线索。页面明确说明置信度是模型自报数值，不是经校准的识别正确概率。
- 原有评分门槛仍为 **0.5**。低于该门槛的有效候选单独放在 `recognition.uncertainFoods`，展示“未计入当前评分”；这些候选不会自动加入评分、建议或营养表单。
- 核对入口打开现有纠错表单。未计分候选需要用户点击“确认并添加”才进入待保存食物；用户仍可修改、移除。保存使用已有 revision 检查，重新评分，并按现有规则使旧营养输入失效。
- 完整餐食详情从已保存的原始识别 JSON 恢复未计分候选，不依赖新增字段存储。记录经用户纠正后不再展示旧的未确认候选。新分析、结果详情、历史详情都支持核对。
- 缺失的历史置信度不会被前端当作 0 或人为生成概率。候选元数据经过运行时检查，未计分候选必须提供合法的低于 0.5 的数值。

## 失败原因及操作

| 稳定错误码 | 页面说明 | 操作 |
| --- | --- | --- |
| `NON_FOOD` | 未识别到餐食；判断可能有误 | 选择食物/饮品照片，或换角度拍摄 |
| `IMAGE_UNCLEAR` | 图片不清晰或细节不足，无法可靠识别 | 改善光线、稳定镜头、完整入镜后换图 |
| `MODEL_SERVICE_ERROR`，已有 AI 调用失败/超时码 | 模型服务暂时异常、超时或返回无效数据 | 稍后用原图重试，当前草稿复用同一请求 key |
| `NON_FOOD_LIMIT` | 连续上传非食物后受限 | 等待限制解除再上传餐食照片 |
| 额度/预算服务/上传繁忙码 | 沿用既有具体提示 | 等待额度或入口恢复 |
| 未知故障、传输中断、保存故障 | 暂未取得结果，原因不能确定 | 查询已有任务状态，避免盲目重复提交 |

模型提示词增加 `imageQuality: CLEAR | UNCLEAR`。无法判断的模糊、过暗或遮挡场景要求返回 `UNCLEAR`，不会按明确非食物处理。有效食物候选均不足评分门槛时，也返回“细节不足，无法可靠识别”；这不是确定的物理模糊诊断。旧模型响应未带 imageQuality 时保留兼容默认值。

格式损坏、缺失置信度、置信度类型错误/越界等模型响应异常归为模型服务问题，不靠异常文本推测失败种类。可辨认的非食物沿用原有上传限制计数；清晰度不足不会增加非食物次数。

失败原因通过 `RecognitionFailureException`、`TaskFailureClassifier` 和现有 `last_error` 持久化，再经状态接口白名单公开。同步 HTTP 错误和异步任务均使用稳定代码，刷新后仍可恢复说明。不会向用户公开原始模型错误、密钥或供应商返回体。

换图片或目标继续采用新 key；原图重试复用当前草稿 key；旧任务查询保留原标识。新增操作按钮仍受上传/恢复中的禁用控制。

## 验证记录

- 前端严格类型检查、**52 项单元测试**及生产构建通过。
- **98 项完整浏览器回归**通过，含 5 项新增用例：低置信度及候选确认、纠正后清除提示、非食物/清晰度失败刷新与换图、新图片的新 key、模型失败原图复用 key，以及移动端历史说明。
- 后端最终完整单元/HTTP 回归 **284 项**通过，无失败、错误或跳过；新增输入校验及状态读取的 **16 项针对性测试**也通过。
- 数据库集成测试新增候选恢复/纠正和错误原因持久化用例，已编译。执行 `MealAnalysisDatabaseIntegrationTest`、`MealAnalysisRabbitDatabaseIntegrationTest`、`TaskFailureManagementDatabaseIntegrationTest` 时，Docker 引擎不可用，三类测试均在容器初始化阶段失败。启动 Docker Desktop 后报告 Secrets Engine `engine.sock` 无法访问，因此本轮数据库集成结果尚未确认；没有连接业务数据库或重置 Docker 数据。

日志（路径相对于项目根目录）：`logs/root/recognition-explanation-typecheck.log`、`logs/root/recognition-explanation-unit.log`、`logs/root/recognition-explanation-build.log`、`logs/root/recognition-explanation-e2e-final.log`；后端 `target/recognition-explanation-unit-final.log`、`recognition-explanation-boundaries.log`、`recognition-explanation-unit-complete.log`、`recognition-explanation-integration.log`。

源码改动位于当前工作区，尚未更新业务运行容器或部署。阈值是本轮产品默认值，尚未用真实人工标注图片校准；图片清晰度与非食物判断仍依赖视觉模型，用户可换图或纠正结果。

后续验证：在同日的[并发代码优化](CONCURRENCY_CODE_OPTIMIZATIONS_20261014.md)中恢复 Docker 后，识别分类和候选恢复的数据库用例已经实际运行。发现分类测试循环重设 Mockito 异常时会触发旧 stub，修正测试后完整 112 项数据库集成回归通过，覆盖本节此前因容器启动失败而未完成的验证。原失败日志保留。
