# 模型配置调整（2026-10-23）

- 文字生成模型：`qwen3.8-flash`，用于助手、饮食建议及周报。
- 图像识别模型：`qwen3.8-flash`，通过 `MEALCHECK_AI_VISION_MODEL` 单独指定模型名称，仍使用当前 AI 连接的地址和密钥。该变量为空时沿用当前文字模型。用户因 `qwen3.7-flash` 额度不足，要求将文字和识图统一切换到 3.8 Flash，替代本日早先的文字/识图模型选择。
- 重排模型：`qwen3.7-text-rerank`。本地 `.env` 已启用，使用现有北京百炼密钥及原生重排接口。密钥没有写入源码或示例配置。
- 文档向量模型和维度保持原配置；此次切换不需要重新生成文档向量。

参考：[文字与识图模型能力](https://help.aliyun.com/zh/model-studio/qwen3-8-flash)、[原生重排协议](https://help.aliyun.com/en/model-studio/text-rerank-api)。

## 接口适配

`KnowledgeRerankerService` 对 `/services/rerank/text-rerank/text-rerank` 路径使用 `input.query`、`input.documents` 和 `parameters.top_n`，解析 `output.results`。其他重排地址保留原有扁平请求协议。现有排序融合、过滤及失败回退机制保持不变。

开发和生产 Compose、应用配置及环境示例已同步。生产 Compose 补齐重排环境变量透传。应用默认仍要求显式启用重排并提供地址、模型和密钥。

## 验证与生效

本日早先选择的文字模型和重排模型的两个最小真实请求均 HTTP 200。该结果不代表当前 Flash 配置已完成验证。历史安全摘要及终端输出位于 `logs/backend/model-switch-*`，不保存密钥。

后续统一切换到 `qwen3.8-flash` 后，文字与识图各一次最小真实请求均 HTTP 200，响应模型均为 `qwen3.8-flash`。识图使用不含用户数据的合成红色图片，成功返回 JSON 并识别为 red；两次请求均关闭思考并限制输出。实际 total token 分别为 29、114。该验证仅确认接口及基础图文能力，不代表餐食识别准确率或问答质量验收。证据：`logs/backend/flash-switch-live-probe-20261023-140759-989741.log` 及同目录 `flash-switch-probe-*.json`。本地配置与开发/生产 Compose 默认值核对通过，YAML 解析通过；此次仅调整配置和文档，沿用上一轮代码回归结果。

本地回归 21 项通过，覆盖新原生重排协议、原有重排协议、检索与过滤、识图和文字模型路由，以及现有请求策略。开发/生产 Compose 和应用配置的 YAML 解析通过。最终日志：`logs/backend/model-switch-final-20261023-135953-585263.log`；沙箱内首次测试停滞后已终止，最终成功结果来自后续完整运行。

最初切换配置时 Docker 引擎未运行，当时尚未重建业务后端或核验管理员覆盖。后续已恢复 Docker、重建前后端并完成运行核验，详见下文。需要重新创建后端且保持不触发自动索引时：

```powershell
$env:MEALCHECK_KNOWLEDGE_AUTO_INDEX = 'false'
try { docker compose up -d --build backend }
finally { Remove-Item Env:MEALCHECK_KNOWLEDGE_AUTO_INDEX }
```

管理员保存的 AI 连接优先于部署环境。如果 AI 模型配置页面显示来源为 ADMIN，应将已保存模型改为 `qwen3.8-flash`，或恢复部署配置；保留适合现有密钥的接口地址。识图专用模型使用同一连接，切换到其他供应商时也须确认该供应商支持它。

未知模型费用不应套用旧模型的单价。真实端到端检索、回答和引用质量另行验收。

## 当日后续部署核验

截至20:35，本地业务容器均健康，文字与识图实际配置均为 `qwen3.8-flash`，重排已启用且型号为 `qwen3.7-text-rerank`。管理页显示模型来源为部署配置，未发现管理员旧模型覆盖。桌面与手机配置页7项验收通过，临时测试账户清理完成；该页面核验没有发起供应商调用。

已在生产代码中完成两道固定问答、正常餐食、空餐盘和暗图的分阶段验收，未调用向量模型。后端受影响路径86项回归通过；具体通过结果、失败阶段保留记录及完整检索仍待验收的边界见 [证据与识图完善记录](EVIDENCE_RECOGNITION_FIXES_20261023.md)。
