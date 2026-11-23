# 管理员 AI 连接配置

入口：系统管理 → 系统监控 → AI 调用 → AI 模型配置。

可编辑模型名称、Base URL、API Key。当前部署应使用 `qwen3.7-flash`；2026-10-18 排查时 `.env` 与运行容器仍为 `qwen3.8-max`，后者返回 `AllocationQuota.FreeTierOnly`，同 Key 调用 Flash 返回 HTTP 200。该错误不能解释为所有模型的额度都用尽。

保存后后续识图、饮食建议、助手（包括流式回复）、周报使用新连接；已经开始的请求保持其调用时的连接快照。配置保存到 PostgreSQL（V39），重启保留，其他实例每约 2 秒同步。管理员配置优先于环境变量；“恢复部署配置”清除覆盖，恢复该实例的环境变量设置。各副本应使用相同部署配置。

API Key 只写不读，留空保留现有密钥，变更服务来源（协议、主机或端口）时必须同时提供新密钥，避免把旧供应商密钥发送给另一服务。数据库保存 AES-GCM 密文；审计日志只记录操作类型。可设置稳定的 `MEALCHECK_AI_SETTINGS_ENCRYPTION_KEY`，为空时从 JWT secret 派生。备份恢复和所有副本必须保留相同的加密密钥；更换派生所用密钥前应恢复部署配置，再重新保存 AI 配置。

“测试已保存配置”只验证短文本调用，受用户和全站预算约束并消耗少量供应商额度；测试结果不会返回供应商响应内容或密钥。图像能力须另外上传餐食图验证。知识库的独立向量模型与评测的独立裁判模型继续使用它们各自的配置，未单独配置的凭据回退遵循原有逻辑。

旧模型的环境单价不自动套用于管理员新选的模型。精确的 endpoint/model 价格目录优先，其余未知费用仍按既有预算预留估算。

接口（仅 ADMIN）：

- `GET /api/admin/ai-settings`：有效模型、地址、密钥配置状态、来源、版本、更新时间。
- `PUT /api/admin/ai-settings`：`model`、`baseUrl`、`apiKey`、`version`。
- `POST /api/admin/ai-settings/test`：`version`。
- `POST /api/admin/ai-settings/reset`：`version`。

旧版本提交返回 409，刷新配置后再编辑提交。Base URL 支持 HTTP(S) 的 OpenAI 兼容地址，不接受内嵌账号、查询参数或 URL fragment。

## 验证结果

- 后端完整单元及 HTTP 回归：405 项通过；最终请求策略回归另有 6 项通过。
- 临时 PostgreSQL 集成验证：加密保存、重新加载、跨实例同步、密钥留空保留、版本冲突、地址校验、审计失败回滚和恢复部署配置全部通过。
- 浏览器回归：配置管理 2 项和 AI 调用/额度 3 项通过；前端构建与类型检查通过。
- 已更新本地前后端。运行中的 `qwen3.7-flash` 通过真实连接测试、异步图片识别和助手回复；数据库餐食记录中的实际模型名也已核对。此验收只验证链路可用，不代表识别准确率评估。
- 临时验收账号与其记录、图片已通过应用删除流程清理，配置来源恢复为部署配置，模型保持 Flash。

本地验收脚本：`node deploy/scripts/verify_admin_ai_settings.mjs`。该脚本会临时创建并清理专用账号、发送少量真实模型请求。证据保存在 `logs/deploy/ai-settings-live-summary.json` 与 `logs/deploy/ai-settings-live.png`。

### 20:48 再次复查

以上为此前已完成的真实验收。再次复查时 Docker 引擎已停止；启动 Docker Desktop 报 `initializing Inference manager`，无法访问本机 `AppData/Local/Docker/run/dockerInference` 运行时套接字，随后退出。尝试将该临时文件改名备份也被 Windows 拒绝，因此没有修改该文件。当前无法再次确认容器健康，需先恢复 Docker Desktop，再在项目目录执行 `docker compose up -d`。此启动故障发生在模型调用前，和供应商额度无关。

21:15 更新：通过对照实验确认工具直接创建的进程无法清理 Docker 运行目录的 Unix 套接字，而桌面上下文可以。已通过真正的 Explorer 桌面入口恢复 Docker 引擎；项目容器仍维持停止状态。详见 [启动差异排查](DOCKER_TOOL_STARTUP_DIAGNOSIS_20261018.md)。
