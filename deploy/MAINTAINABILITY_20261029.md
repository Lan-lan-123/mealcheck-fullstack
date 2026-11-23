# 回答编排与 API 边界维护性优化（2026-10-29）

## 改动

- 从 `LangChainDietAssistantService` 提取 `AssistantResponseMapper`，集中处理模型响应解析、流式引用元数据、引用筛选、回答状态、偏好保护、趋势整理和会话编号组装。主服务从 941 行降至 799 行，继续负责请求路由、工具调用和持久化时序。解析所需的交付状态由调用者显式传入，映射器不再自行读取线程上下文。
- 前端保留 `client.js` 的既有公开接口，抽出 `transport.js`、`assistant.js`、`admin.js` 和 `query.js`。主入口从 659 行降至 281 行；61 个管理员接口移入独立模块。普通请求与 SSE 仍使用同一份会话版本和共享 CSRF 状态，避免跨模块出现失效状态或重复初始化。
- 新增 `tsconfig.api.json`，对整个 API JavaScript 目录启用 `checkJs: true` 和继承的严格配置。`npm run typecheck` 自动执行原检查及 API 检查，新增 API 文件无需手工选择是否检查。补齐入口参数、登录/注册/计划/反馈等输入契约、错误类型和共享状态类型，新增编译期反例验证。
- 严格检查发现知识片段编辑提交可能带空 ID，新增提交前保护。空 SSE 响应体和空 CSRF 返回值统一报响应验证错误。

业务模型、提示词、复核规则、超时预算、并发设置、数据库结构及部署配置均未调整。前端其他页面仍按原有渐进策略检查；底层动态请求保留旧接口兼容，一些管理员负载与响应尚未收敛为完整 DTO 类型，不能据本次检查通过宣称全部接口均已完整建模。后续约定见 `frontend/src/api/README.md`。

## 验证

| 检查 | 结果 |
| --- | --- |
| 后端默认全量测试 | 863 项：855 通过、8 跳过、0 失败/错误 |
| 前端单元测试 | 96 项全部通过 |
| 前端类型检查 | 原前端检查、整个 API 目录严格检查、编译期契约反例全部通过 |
| 前端生产构建 | 通过 |
| 浏览器专项 | 11 项全部通过：Cookie 登录、CSRF、助手历史及 API 异常恢复 |

新增后端回归覆盖 PARTIAL/DEGRADED 在回答组装与会话绑定后保留、异常模型输出不获得引用、流式引用元数据不外泄。原先通过反射调用主服务私有解析方法的两组测试改为直接调用映射器。新增前端用例验证普通请求与 SSE 共享 CSRF 初始化，取消其中一个请求不会取消另一个。

后端测试未启用 `integration` profile，未重新执行真实数据库集成或付费模型验证；浏览器专项使用测试拦截数据。本次没有部署，也不据此声称 AI 语义质量或真实时延改善。

## 本地证据

- 后端最终全量：`logs/backend/maintenance-full-verified-20261029-213649-104819.log`
- 后端 XML 报告：`logs/backend/maintenance-full-verified-20261029/reports/`
- 前端单元：`logs/frontend/maintenance-unit-final-20261029-213440-679398.log`
- 前端类型：`logs/frontend/maintenance-types-final-20261029-213442-064424.log`
- 前端构建：`logs/frontend/maintenance-build-final-20261029-213622-606996.log`
- 浏览器：`logs/frontend/maintenance-browser-verified-20261029-213712-893138.log`
- 浏览器结果目录：`logs/frontend/maintenance-browser-verified-20261029/results/`

沙箱内测试曾停滞，相关会话已中止。最终后端全量与浏览器检查在沙箱外完成；后端用本地已安装的 Byte Buddy agent 启动参数避免 Mockito 动态附加，不修改项目依赖或构建配置。所有新增日志集中在 `logs/`；根目录四份历史 `.log` 文件名和大小保持不变。
