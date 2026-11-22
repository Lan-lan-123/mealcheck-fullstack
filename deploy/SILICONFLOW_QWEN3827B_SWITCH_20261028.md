# Qwen3.8-27B 迁移到 SiliconFlow

按用户要求，将原阿里云百炼文本、证据复核、餐食建议、周报及图片识别连接切换到 SiliconFlow。

| 用途 | 模型 | 平台 |
| --- | --- | --- |
| 文本生成、证据复核、建议和周报 | `Qwen/Qwen3.8-27B` | SiliconFlow |
| 图片识别 | `Qwen/Qwen3.8-27B` | SiliconFlow |
| 向量检索 | `BAAI/bge-m3`，1024 维 | SiliconFlow，保持原配置 |
| 重排 | `BAAI/bge-reranker-v2-m3` | SiliconFlow，保持原配置 |

聊天 Base URL 为 `https://api.siliconflow.cn/v1`。本地忽略的 `.env` 复用原有 SiliconFlow 重排凭据，不将百炼密钥发送至新平台。凭据不写入源码、报告或终端输出。

原管理员连接为百炼、版本 10，优先级高于部署环境。确认没有在途餐食任务后，已保存原配置的加密备份，并通过版本校验恢复为部署配置，版本变为 11。当前文本和识图共享新的连接地址及密钥。没有修改向量配置、知识片段或重建索引。

## 请求协议

针对 `api.siliconflow.cn` 上精确模型名 `Qwen/Qwen3.8-27B` 使用 SiliconFlow 的 `max_tokens`，不沿用百炼的 `max_completion_tokens`。保留现有输出上限及各任务默认关闭思考的设置；识图、复核等非流式结构化任务继续使用 JSON 输出约束。普通文本建议与流式文本不强制 JSON。

可选 low 模式下遵守平台 thinking_budget 至少 128 的范围；小输出窗口无法给思考和最终答案分别留出空间时关闭思考。其他供应商及未核实模型不会因名称相似而自动套用这组请求字段。

同步更新开发/生产 Compose 模型默认值、应用默认值和环境示例。历史百炼专用实测脚本及报告保留原有模型约束，不自动重定向其真实测试授权或预算。

## 验证与部署

- 使用当前 SiliconFlow 凭据查询一次 `/v1/models`，HTTP 200，目录包含精确目标模型名；没有发送项目内容或图片，没有推理调用。
- 本地请求策略、HTTP 传输和识图路由回归 30 项全部通过，日志及 XML 位于 `logs/deploy/siliconflow-qwen3827b-20261028/tests/` 和 `logs/backend/siliconflow-policy-*.log`。
- 原有 1351 条知识片段、1 个向量签名及 2 条历史重建任务保持不变。
- 最终容器、数据库覆盖、健康和零推理检查见同批 `deployed-verification.json`。已保留旧后端镜像回退标签 `mealcheck-local-backend:pre-siliconflow-20261028`，不新增数据库迁移。
- 此次 Docker Desktop 的 desktop-linux context 无法连接；使用临时 `DOCKER_CONTEXT=default` 接入包含原项目容器和数据卷的 Linux 引擎，没有修改用户全局 Docker context。

目录查询只确认凭据与模型目录，不证明剩余额度、真实文本输出、图片识别效果或全链路性能。原测试仍保持停止；后续真实推理测试需另行安排。

参考：[SiliconFlow 模型目录](https://www.siliconflow.cn/models)、[聊天接口参数](https://docs.siliconflow.cn/docs/api/chat-completions-post)。
