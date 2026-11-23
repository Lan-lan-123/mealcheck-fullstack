# 图片预算、低置信度核对与周报审核优化（2026-10-15）

三项工作已完成：修复当前 Qwen3.8 图片预算预估、完善低置信度逐项核对、补齐周报审核证据上下文，并完成固定样本的 off/low 真实模型比较。建议继续默认关闭思考，保留运维配置，暂不增加普通用户开关。本轮没有部署到已有业务栈。

## 已完成的修复

### 图片预算

原逻辑按整个请求字符串长度估算输入，把 Base64 图片当成大量文本。现在共享传输层将图像与其余文本分开估算：对于已验证的 Qwen3.8 Max/Flash 系列，按请求对应的图像 token 上限预留，移除估算文本中的图片 URL/Base64；不解码或远程下载图片，也不修改实际发送的图像。

依据供应商的图像计算规则，默认上限为 2,560 图像 token，高分辨率及自定义像素上限最高对应 16,384 token，并计入图像边界 token。见[阿里云图像理解文档](https://www.alibabacloud.com/help/en/model-studio/vision/)。这是保守的准入估算，最终仍优先按响应 usage 结算；其他模型保留原估算方式。

4,000,000 字符 Base64 回归样本不再预留约 48 元输入费用，总预留低于 0.2 元，图片原文完整传输。真实端到端重新使用 **2 元预算**，未关闭预算或扩大限额，图片上传、识别、建议和结算均正常。真实测试初阶段所有请求预留在约 0.090～0.146 元范围，结束后无 RESERVED/SENT 残留。

实现：[AiRequestTokenEstimate.java](E:/mealcheck-fullstack/backend/src/main/java/com/example/mealcheck/service/AiRequestTokenEstimate.java)、[共享传输](E:/mealcheck-fullstack/backend/src/main/java/com/example/mealcheck/service/AiHttpTransport.java)。

### 低置信度核对

纠正窗口现在展示授权餐食照片、模型自报置信度及识别线索。低于 80% 的已计分食物需逐项确认或移除，才可保存；修改名称、分类后需要重新确认。未计分候选显示原因，用户明确确认后才能加入。保存只提交人工核对后的名称与分类，不把前端确认状态或模型置信度直接作为新识别结果提交。

原有 50% 计分门槛保持不变；新增确认门槛作用于纠正界面，不代表自报置信度已经经过概率校准。原始识别、修改历史和服务端重新评分仍保留。已验证手机 390px 宽度下的照片、核对、编辑后再确认及保存流程。

实现：[MealCorrectionModal.jsx](E:/mealcheck-fullstack/frontend/src/components/MealCorrectionModal.jsx)、[手机截图](E:/mealcheck-fullstack/loadtests/results/optimization-20261015-150851/low-confidence-confirmation.png)。

### 周报审核

生成与审核现在收到完全相同的检索片段摘要，包含编号、标题与内容，每次最多 5 条、每条正文最多 500 字符。审核不再从尚未写回的 agentTrace 推断是否完成检索；无证据时明确提供空列表。原有安全检查和拒绝危险建议的规则保留。

扩大样本还发现审核会混淆撰稿者与审核者的身份，并将条件性场景建议误当作用户事实。最终提示词已明确角色边界、条件句语义和实质性否决标准；面向用户的报告要求自然表达，不展示内部 RAG 编号或 Agent 流程。

初轮 8 份周报中有 2 份被退回修订，含上述误判；补充规则后对相同 4 套统计重新运行两种模式，**最终 8/8 份均首稿通过**。这是单次样本结果，不能保证生产重写率为零。

实现：[共享证据格式](E:/mealcheck-fullstack/backend/src/main/java/com/example/mealcheck/service/weeklyagent/WeeklyEvidenceContext.java)、[审核 Agent](E:/mealcheck-fullstack/backend/src/main/java/com/example/mealcheck/service/weeklyagent/WeeklySafetyReviewAgent.java)、[编排中的证据传递](E:/mealcheck-fullstack/backend/src/main/java/com/example/mealcheck/service/weeklyagent/WeeklyReportMultiAgentOrchestrator.java)。

## 相同样本的模式对照

模型为 qwen3.8-max，off 关闭思考，low 的思考预算为 512，总输出上限为 2,048。每个样本两种模式各执行一次，并交替 AB/BA 顺序。图片源文件校验 SHA-256，周报的基础统计、画像和证据保持一致。

- 图片：8 张既有合成图，6 张餐食、2 张非食物，涵盖普通、弱光、遮挡及逆光场景。餐食场景包括识别后生成短建议，非食物只有识别。建议输入随识别结果变化，属于同一图片的整体流程比较。
- 周报：均衡、蔬菜记录偏少、仅两餐记录、高油标记四套固定合成统计，各运行 off/low。最终周报测试在端到端结束后串行执行，没有本任务其他并行付费调用。
- 图片对照部分时间与一轮端到端并行，耗时可能受共享资源影响；所有结果都是小样本观测，不能作为生产平均值、P95 或普遍质量结论。

| 任务及模式 | 样本数 | 平均耗时/样本 | 同组 token 合计 | 同组估算费用 |
| --- | ---: | ---: | ---: | ---: |
| 图片识别及短建议 off | 8 | 6.94 s | 21,393 | 0.306372 元 |
| 图片识别及短建议 low | 8 | 14.67 s | 23,542 | 0.378792 元 |
| 最终周报 off | 4 | 17.59 s | 15,776 | 0.255528 元 |
| 最终周报 low | 4 | 50.54 s | 21,719 | 0.462564 元 |

单独计算识别请求（不含短建议），off 平均 4.80 秒、low 平均 10.44 秒。整体图片组 low 的 token 多约 10%、估算费用高约 24%；周报组 low 的 token 多约 38%、估算费用高约 81%。

### 准确性代理指标与建议质量

两种模式均与合成预标注的食物/非食物判断相符 8/8，六张餐食的类别集合匹配均为 6/6。预标注未经独立人工完整标注，这些是固定样本回归指标，**不代表真实照片准确率为 100%**。名称细节如煎/烤鸡胸肉、四季豆/豆角仍有差异，不能仅凭这些称呼推断某模式更准。

建议文本隐藏模式标签后，由本助手按事实一致、记录范围、可执行性及安全/证据一致性检查，再解码标签。这不是独立人工评测，本助手也参与了实现。

- 餐食建议：两种模式各有 5/6 条与输入完全一致；off 一条把豆腐餐概括为「有肉」，low 一条称缺少明确蛋白质来源，与输入评分将 dairy 计入蛋白质来源的规则不符。各有一组在措辞上较优，另四组接近；可执行性及无极端承诺均为 6/6。
- 最终周报：主体统计两种模式均为 4/4 一致，建议均具体，未见虚构来源或极端承诺。low 一条将水果记录「每周3次」作为提高到5次以上的起点，未充分限定为已记录频次，仍需改善范围表达。
- 两种模式都还有输出细节问题。思考更久未显示稳定的质量收益，也不能替代用户核对或后续独立评测。

结论：维持 off 默认配置；暂不开放普通用户思考强度选择。若以后扩大评测，应补充独立标注的真实照片、多次重复配对和实际用户反馈，再判断是否提供有明确内容差异的分析选项。

## 验证与新增用量

- 最终后端构建：**317 项测试通过，0 失败、0 错误、0 跳过**，覆盖预算、原文传输、共享证据、角色及条件句审核边界。
- 前端：**68 项测试、类型检查和构建通过**。
- 浏览器回归：**6 项通过**；另完成手机低置信度场景与截图核对。
- 最终真实端到端：**10/10 通过**，2 元预算下完成注册登录、队列、真实 AI、数据库、图片、刷新恢复、助手流式、周报及跨账号隔离。最终周报首稿通过；测试容器及临时卷已清理，已有业务栈未修改。

| 本次执行部分 | 真实调用 | token | 估算费用 |
| --- | ---: | ---: | ---: |
| 首轮图片与周报对照 | 56 | 90,706 | 1.571832 元 |
| 最终提示词的周报对照 | 24 | 37,495 | 0.718092 元 |
| 首阶段真实端到端，含中断后复测 | 15 | 28,019 | 0.415452 元 |
| 最终版本真实端到端 | 9 | 16,584 | 0.244416 元 |
| **本次新增合计** | **104** | **172,804** | **2.949792 元** |

输入合计 136,298、输出合计 36,506 token。全部返回已知用量，没有超时或输出截断；端到端数据库全局预算累计 attempts=24、spent=0.659868、reserved=0，与两阶段实际 usage 估算一致。组件比较使用预算/存储替身，真实请求的 usage 全部保留。

按输入 12 元/百万、输出 36 元/百万估算，未应用缓存折扣或免费额度，以供应商账单为准。以上与此前各轮费用分开，包含本次全部中间验证，不只统计最终成功轮次。首阶段曾因测试目录缺少黑图文件而中断，先前四次模型调用也计入；原记录保留。

## 证据

- [汇总、质量指标及费用账本](E:/mealcheck-fullstack/loadtests/results/optimization-20261015-150851/comparison-summary.json)
- [首轮原始结果](E:/mealcheck-fullstack/loadtests/results/optimization-20261015-150851/comparison-results.json)、[最终周报原始结果](E:/mealcheck-fullstack/loadtests/results/optimization-20261015-150851/comparison-weekly-final.json)
- [餐食建议质量记录](E:/mealcheck-fullstack/loadtests/results/optimization-20261015-150851/image-quality-review.json)、[周报质量记录](E:/mealcheck-fullstack/loadtests/results/optimization-20261015-150851/weekly-quality-review.json)
- [最终浏览器结果](E:/mealcheck-fullstack/loadtests/results/optimization-20261015-150851/browser-results.json)、[数据库对账](E:/mealcheck-fullstack/loadtests/results/optimization-20261015-150851/database-audit.json)
- [后端测试](E:/mealcheck-fullstack/loadtests/results/optimization-20261015-150851/backend-tests-final.log)、[前端测试](E:/mealcheck-fullstack/loadtests/results/optimization-20261015-150851/frontend-tests.log)、[浏览器回归](E:/mealcheck-fullstack/loadtests/results/optimization-20261015-150851/browser-regression.log)
- [最终运行产物核对](E:/mealcheck-fullstack/loadtests/results/optimization-20261015-150851/runtime-verification.json)、[审计与清理](E:/mealcheck-fullstack/loadtests/results/optimization-20261015-150851/audit.json)

原始证据位于本地 Git 忽略目录，保存的文本已检查不含供应商密钥和模型思考正文。未执行公网部署、真实照片总体准确率验证或生产负载测试。
