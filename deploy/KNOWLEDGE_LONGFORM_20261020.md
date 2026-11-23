# 200 主题的分档长文扩写

本文记录历史 v6 扩写阶段。此后经过 v7 去重中间版，当前已替换为 v8、1,249 片；
1,318 片是 v6 的历史结果，不是当前状态。
当前内容与全部长度目标验收见 [v8 长文验收说明](KNOWLEDGE_LONGFORM_V8_20261021.md)。

## v6 阶段结果（已由 v7 替代）

2026-10-20 用户要求改由助手编写后，已为全部 200 个主题编写分主题案例材料，
并以这些材料重写新增正文，包括先前保留的 52 篇草稿；本轮新增 Qwen、Embedding 和其他供应商请求均为 **0**。
原始主题、顺序、正文与参考来源保留。`diet_guides.md` 已更新为 `v6-longform-200`，
默认实际切分为 **1,318 条**，片段估算 token 均在 300–500 内，主题内部按默认配置重叠，主题之间不混合。

| 分档 | 主题数 | 允许切片数 | 本版实际切片数范围 | 本档片段总数 |
| --- | --- | --- | --- | --- |
| SHORT | 40 | 2–3 | 2–3 | 84 |
| MEDIUM | 60 | 4–6 | 4–5 | 244 |
| LONG | 60 | 7–10 | 8–10 | 491 |
| EXTENDED | 40 | 12–20 | 12–14 | 499 |

长度分档与原文锚点均通过生产切分器校验。已补充整包与每份换算、标签单位、总糖包含关系、
覆盖率分母、称量对象、过敏接触与保存过程等具体说明；计算例子的数字明确为假设，不作为真实商品营养值。
能量单位换算另核对了 [NIST 计量资料](https://www.nist.gov/glossary-term/26261)，来源同时记入主题元数据。

正文采用五类讲解框架（选餐、标签、记录、食品安全、过敏）组织主题专属案例，通用方法会复用；
这不是 200 篇经独立作者审核的营养研究，也不能把完整重复段落为 0 解释为没有语义相似内容。
`knowledge_longform_case_notes.txt`、`knowledge_longform_topic_details.json` 与 `knowledge_case_prose.py`
保存可核查的编写材料和组织方法；先前通用说明较多的预览批次只保留在日志目录，不作为当前知识源。

200 道 RAG 问题及其意图保持，原有引文迁移到实际新片段哈希，180 道有知识证据的题完成锚点迁移。
数据集升为 `rag-200-v3+rag-tools-v2`，工具规则和链路评分公式不变。
新段落尚未全部独立补标相关性，标注继续为 `PENDING_HUMAN_REVIEW`，覆盖范围明确是原有引文；
已导出包含 1,318 个实际片段和 200 份空白评审的独立复核包，不宣称已完成独立评审或真实检索质量提升。

验收证据位于 `logs/backend/knowledge-longform-authored-20261020-release/`：
实际切分清单、主题元数据、原文备份、迁移结果、审查报告、复核包及离线测试报告一并保留。
本轮后端回归 **54 项通过**，生成工具本地测试 **14 项通过**。
资源文件与版本默认值已经更新，**没有启动后端、同步运行中数据库或重建向量索引**。
实际运行配置可覆盖默认版本；本次未通过服务重启触发任何同步或付费模型请求。

## 先前供应商批次的暂停记录

先前 Qwen 批次累计发送 53 次请求（含失败或中断），供应商报告 249,701 token，
含缺失用量预留的保守计数为 340,468 token。HTTP 403 后停止，具体供应商原因仍未知。
该批次的原始请求、响应与 52 篇草稿继续保留；本轮编写没有重试或新增这些请求。
`logs/backend/knowledge-longform-generation-hold.json` 保持暂停，只有用户明确授权恢复供应商调用才可处理该记录。

## 分档方案

用户指定按主题数分配，而非按最终向量总量分配：

| 主题比例 | 主题数 | 每主题实际切片范围 |
| --- | --- | --- |
| 20% | 40 | 2–3 |
| 30% | 60 | 4–6 |
| 30% | 60 | 7–10 |
| 20% | 40 | 12–20 |

整体范围为 1,220–1,880 个片段。仍保留 200 个主题的标题、顺序、原文与公开来源；新增内容以操作步骤、
条件对照、校园假设案例和记录边界为主。不得靠复制段落凑长度，也不将长文数量当成知识正确率。

验收使用实际 `MarkdownKnowledgeChunker` 默认配置：递归 Markdown，300–500 个估算 token，50 token 重叠。
这是项目内的文本长度估算，与模型供应商计费 token 分别统计。独立主题之间继续隔离，不相互添加重叠。

## 准备与生成

`prepare_knowledge_longform.py` 冻结原文、200 主题计划和提示，并利用本地后端 jar 已有依赖编译
`KnowledgeCorpusTool.java`。桥接器直接调用当前源码的生产切分器，无需启动 Spring、数据库或供应商模型。
默认输出在新的 `logs/backend/knowledge-longform-时间戳/`，其中 `plan.md` 可逐项核对主题分档。

```powershell
python tools/run_logged.py --group backend --name knowledge-longform-prepare -- python tools/evaluation/prepare_knowledge_longform.py
python tools/run_logged.py --group backend --name knowledge-longform-dry -- python tools/evaluation/generate_knowledge_longform.py logs/backend/NEW-BATCH
```

本次用户明确允许当前已配置的 `qwen3.7-flash` 扩写，最多 200 次请求，本地预算上限 3,000,000 token。
`--run` 才会读取所需的配置并请求现有 DashScope HTTPS 地址。只发送公开知识原文与相关原则，不发送用户餐食或个人数据。
密钥不写入日志、提示快照或结果。源码文件、生产数据库与向量设置不会由生成脚本更改。

```powershell
python tools/run_logged.py --group backend --name knowledge-longform-generate -- python tools/evaluation/generate_knowledge_longform.py logs/backend/NEW-BATCH --run --token-budget 3000000 --max-calls 200
```

每次调用前保存预留量，成功后保留供应商 usage。缺失用量或中断请求继续保守占用额度；
正文与思考明细和 total 不一致时，采用较大计数。预算不足、HTTP 错误和连接错误均停止请求，
错误不会被自动重试，恢复不会抹掉此前用量。已有草稿也不会因不合格而被悄悄重新付费生成。
`--review-every` 控制生成检查点；原始响应、实际请求和每次本地修订保留供检查。

## 本地编辑、切分与评测迁移

`preview_knowledge_longform.py` 可以将本地编辑的 `preview-编号-supplement.md` 与原文组合，
用真实切分器验证范围、片段长度、重复块及原评测引文是否仍能落在具体片段中。
`adopt_knowledge_preview.py` 采用经过检查的编辑稿时，先归档旧草稿，保留调用累计，且不能解除供应商错误状态。
`revalidate_knowledge_drafts.py` 不联网，可在修正规则后重新校验已有响应；修复重复 JSON 字段造成的正文丢失时，不新增模型调用。

```powershell
python tools/run_logged.py --group backend --name knowledge-longform-audit -- python tools/evaluation/audit_knowledge_longform.py logs/backend/NEW-BATCH
```

审查分别报告：主题和片段数量、各档达标数、原文证据锚点、重复正文、疑似自创标准量或规则、调用数与保守 token 统计。
词语与结构检查不能代替事实审核；已发现的无依据营养值、未知当零、虚构功能和不必要核对等问题应先修订。
AI 扩写与助手编辑稿均不得标记为独立人工审核完成。

长文改变物理片段指纹，必须按实际切分清单迁移 `rag_eval_cases.json`，不能用整篇标题代替片段证据。
`migrate_rag_longform_labels.py` 保留 200 道问题和原有引文，只把引文重新绑定到确实包含它们的新片段。
无法完整落入片段的引文会停止迁移；不会把同主题所有新增片段自动标成正确答案。
这属于原证据锚点迁移，新段落的相关性标注仍需补充复核。

只有完整候选文本、长度分布与证据迁移通过后，才更新知识资源、版本与相关测试。
本文记录工作方式与验收边界；实际完成情况以批次审查和测试报告为准。
知识文件更新和运行中数据库重建分别记录，未执行重建时不能宣称线上已出现新片段。
