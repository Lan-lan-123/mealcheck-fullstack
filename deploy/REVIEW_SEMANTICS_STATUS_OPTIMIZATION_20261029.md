# 否定语义、回答状态与复核输入优化（2026-10-29）

后续更新：Docker恢复后已完成最终版部署、V53及固定10题+1次重复验收，见[本轮真实复测结果](REVIEW_DIRECTED10_RESULTS_20261029.md)。以下“待完成”段落保留为当时的工作记录。

## 已完成的代码

1. 否定语义：补充“不能把/将……当成/视为……保证”等有明确范围的否定结构。标点、转折及新增谓语终止该范围，双重否定不享受豁免；保留原有肯定事实、程度、因果及适用条件检查。实际误拦截句“不能把低因当成可以随便多喝的保证”回放已通过，肯定保证、双重否定、转折及并列新增保证仍被拦截。
2. 回答状态：新增PARTIAL及answerReasonCodes，与COMPLETE、DEGRADED分别表示回答完整性；deliveryStatus仍表示传输/交付结果。删减结论、可选建议预算省略、缺输入/知识和没有可用证据的回答不再显示为完整。成功修正后的回答不携带历史错误导致的PARTIAL；正常范围外拒答仍可完整交付。模型超时等服务失败继续使用DEGRADED/failureCode。
3. 历史与页面：V53增加outcome_json，保存状态、原因及服务错误，重新打开会话可恢复；旧消息保持原有兼容行为，不对历史文本猜测回填。新字段不接受客户端历史伪造。页面标明部分回答，隐藏其“加入计划”按钮；部分/降级回答不自动写入长期记忆或后续请求历史。评测脚本分别统计传输完成、回答状态及原因，状态本身不等同语义准确率。
4. 复核输入：绑定引文通过quoteIds引用共享quoteTexts，同一原文不在多个绑定和上下文中重复传输；保留完整claim、逐分句文本、原问题和相邻适用条件。邻段仅提供条件边界，不能扩充可用证据；原有子串引用不会扩大成整段引用。移除模型不必使用的字符位置，压缩重复说明，四项checks、证据ID、输入/知识独立判断、未知保留及来源边界均保留。复核输出协议、3条/6分句/3来源上限、模型及超时/并发参数不变。

## 已完成的验证

- 最终后端全量850项：843通过、7跳过、0失败/错误。
- 隔离PostgreSQL迁移与历史持久化测试1项通过，包含V53、PARTIAL原因恢复及账号隔离。
- 前端单元测试95项、类型检查通过；浏览器专项1项通过，覆盖SSE结果、历史重新加载和隐藏加入计划按钮。
- Python runner/统计契约13项通过。新增--small --questions-only模式，限制为固定10题，不发起任何图片请求，支持按剩余授权降低调用上限。
- 同一捕获候选的服务层回放中，复核请求3509→3040字符，减少469字符（约13.4%）。候选、问题、检索材料相同，仍只生成1次、复核1次、检索1次。输入变短不能证明真实耗时同比下降。

关键证据：

- `logs/backend/review-final-protocol-20261029/reports/`
- `logs/backend/review-final-protocol-regression-20261029-170040-550717.log`
- `logs/backend/review-optimization-db-20261029/reports/`
- `logs/backend/review-outcome-db-20261029-163946-384769.log`
- `logs/frontend/review-outcome-unit-20261029-163443-127025.log`
- `logs/frontend/review-outcome-typecheck-20261029-163446-063602.log`
- `logs/frontend/review-outcome-browser-verified-20261029-165002-771399.log`
- `logs/frontend/review-outcome-browser-verified-20261029/`
- `logs/backend/review-outcome-runner-contract-20261029-165130-774887.log`
- `logs/backend/optional-budget-regression-final-20261029-155723-824608.log`（3509字符基线）

## 部署与真实测试待完成

初版镜像构建和compose up曾成功，但最终版镜像构建期间Docker引擎停止。复制app.jar阶段停滞的构建已中止，后续构建明确返回“Docker Desktop is unable to start”。再次启动Docker Desktop后，状态仍为stopped。最终源码版本尚未完成部署验证，不能认定初版容器已经包含全部最终改动。当前也不能确认服务可用及生产迁移状态；隔离数据库验证不代替生产部署验证。

- 停滞构建日志：`logs/deploy/review-final-backend-build-20261029-165636-671790.log`
- 最终构建失败日志：`logs/deploy/review-final-protocol-build-20261029-170231-831205.log`

本轮真实模型调用0次；原500次授权累计434，剩余66次，未重新设置额度。Docker恢复后应先确认无活动任务、重新构建最终后端并应用V53，检查前后端健康，再执行固定10题问答专项。首批最多64次模型调用，保留2次授权余量，不发图片，不重建知识库，临时账号用完删除；持续服务失败或权限/额度错误时停止。是否追加同题重复，须根据首批实际消耗和剩余预算另行核算，不能直接叠加调用上限。

首批执行命令（在项目根目录，DOCKER_CONTEXT=default）：

```powershell
python tools/run_logged.py --group backend --name review-directed10 -- python deploy/scripts/run_qwen3827b_fullchain.py --provider siliconflow --small --questions-only --attempt-cap 64 --execute
```

逐题检查原问题回应、引用支持、未知保留及拒答，同时统计COMPLETE/PARTIAL/DEGRADED、各阶段耗时、实际HTTP调用和修正轮数。比较耗时必须说明证据/候选是否相同；不能把此次单题历史数据与新10题直接作为速度提升比例。
