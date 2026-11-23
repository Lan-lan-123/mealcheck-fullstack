# RAG 任务恢复与评测（2026-10-18）

本次按用户选择只完成代码与本地测试，不调用真实向量模型或聊天模型，不向外部服务发送知识、题目或用户数据。

## 管理页面

位置：管理员 → RAG 知识库 → 向量工作台。

- 活跃任务可“终止任务”。终止会释放任务位置、撤销工作线程的发布权限；原有向量继续生效。已经发出的远程请求无法撤回，但返回结果不能发布。
- 模型、接口、维度、存储配置变化，或者语义接口配置不可用时，定时维护和状态刷新会检测不兼容任务并标记“配置已失效”。局部任务还要求与当前生效向量空间一致。
- “重试”使用原配置和仍有效的暂存进度；配置不匹配会拒绝重试并提示重新提交。
- “重新提交”使用当前下拉框方式。局部任务保留仍存在的选择 ID，使用当前生效方式；换模型或维度需要整库重建。已删除的知识不会重新生成；若选择已全部删除，需要重新选择。
- “任务历史与恢复”分页展示方式、范围、进度、时间、失败说明；“查看任务记录”展示最近 50 条事件。

鉴权、请求配置和非法响应等错误立即结束任务；限流、超时、网络、服务临时异常、数据库错误会延迟重试，最多自动尝试 5 次（正常分批推进不占用失败次数）。失败后保留有效暂存数据。鉴权失败不等同于额度耗尽；429 也不能直接判定为余额不足。持久化错误说明采用固定文案，不保存接口原始错误正文或密钥。

数据库迁移 V40 增加 CANCELLED、INVALIDATED 状态、失败摘要、结束时间和事件表。应用更新后由 Flyway 自动执行。本次仅测试隔离数据库，没有修改运行中的业务库或重新生成正式知识向量。

## 评测入口和边界

管理页原有“运行 RAG 评测集”检查检索，不调用最终回答服务；语义模式仍会产生向量请求。页面已将“最终拒答准确率”更正为“无答案检索准确率”，避免把检索空结果误当成最终回答拒答。

新增 `RagLiveQualityDatabaseIntegrationTest`，默认不执行，只有显式启用才运行。它在独立 pgvector 测试库中写入 200 条知识、执行现有 158 道检索题（148 道有答案、10 道无答案），再执行生产回答提示词和解析流程。使用虚拟空白用户，Redis、用户历史和持久化使用测试替身，不使用真实用户饮食记录。

两种执行模式：

- `MEALCHECK_RUN_OFFLINE_RAG=true`：不读取 `.env`，不访问模型接口。向量和聊天响应在进程内生成。只验证完整链路，所有输出标记 OFFLINE_FIXTURE / qualityMeasurement=false。
- `MEALCHECK_RUN_LIVE_RAG=true`：需要另行明确授权向已配置服务发送数据并支付调用费用。读取项目 `.env` 的聊天、向量连接配置，在隔离库中调用真实服务；它不是线上动态管理员配置的自动快照。本次没有启用此模式。

离线模式优先于真实模式；常规单元测试和普通集成测试默认跳过该评测类。每次输出目录需要没有已有 results.json，防止覆盖证据。

从仓库根目录运行离线验证：

```powershell
$env:MEALCHECK_RUN_OFFLINE_RAG='true'
$env:MEALCHECK_RUN_LIVE_RAG='false'
$env:MEALCHECK_RAG_OUTPUT='E:\mealcheck-fullstack\deploy\release-results\rag-offline-NEW'
mvn -f backend/pom.xml verify -Pintegration '-Dtest=RagBenchmarkServiceTest' '-Dit.test=RagLiveQualityDatabaseIntegrationTest'
python tools/evaluation/report_rag_quality.py "$env:MEALCHECK_RAG_OUTPUT\results.json"
```

需要可用的本地 Docker。报告脚本只读取已保存 JSON，不调用任何服务。它生成 audit.json、report.md 和 review.csv，检查覆盖、执行错误、引用来源是否存在、有答案题是否缺引用、疑似误拒答等。空引用不会被当作“引用通过”。标题匹配和拒答关键词是筛选疑点的代理指标，不能判定引用是否支持最终结论；人工复核表预留引用支持度、实际拒答和备注列。

该次维护实现阶段未运行真实模型。后续在用户另行授权后完成了随机 10 题真实测试，见 [随机 10 题报告](RAG_RANDOM10_REAL_20261018.md)；158 题全量真实评测仍未执行，不能将离线结果当作真实效果结论。

## 本地验证结果

- 64 项相关后端单元测试通过。
- 16 项隔离 PostgreSQL 集成测试通过；包括运行中更换接口、取消/失效后工作线程不可发布、重试次数、历史记录和原向量保留。
- 158 / 158 道离线题完整执行，回答传输和解析均经过生产链路，执行错误 0；引用来源核验兼容界面摘要格式。
- 8 项本地模拟接口浏览器测试通过；包含终止、重试、重新提交、历史记录和原有逐条状态功能。
- 前端类型检查和生产构建通过；3 项报告审查脚本测试通过。

后端日志：`logs/root/knowledge-recovery-final.log`；浏览器日志：`logs/root/knowledge-recovery-browser.log`。
离线证据：`deploy/release-results/rag-offline-recovery-final-20261018/` 下的 results.json、audit.json、report.md、review.csv。该目录是本地测试产物，不代表真实效果报告。
