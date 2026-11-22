# 前端严格检查与 API 响应校验（2026-10-14）

本轮完成用户工作台、饮食助手和可选营养表单的渐进类型检查及关键响应运行时校验。沿用 JavaScript/JSX、JSDoc 和现有 TypeScript 检查工具，没有增加运行时依赖。

## 静态检查范围

新增 `@ts-check` 的模块：

- `UserWorkspacePage.jsx`、`AssistantCard.jsx`、`MealNutritionPanel.jsx`、`MealRecordLoader.jsx`。
- `useUserWorkspaceData.js`、`useGoalDraft.js`、`useWeeklyReportJob.js`。
- `mealNutrition.js` 和新增 `api/validation.js`。

`contracts.d.ts` 补充工作台筛选、营养响应与保存版本、助手回复与会话、流式事件、DOM/ref 状态和共享组件回调契约。关键 API 导出函数使用明确的输入、输出类型。共享详情、记录、纠错等组件补充 props 类型，使已检查页面的调用边界可检查。

`typecheck/contracts.tsx` 增加负向检查：字符串营养记录 ID、错误的助手错误回调、缺失的保存版本、错误访问会话消息，以及未区分旧餐食列表数组/分页结构都会被类型检查阻止。

全局继续使用 `strict=true`、`checkJs=false`，通过模块的 `@ts-check` 渐进接入。现有 CI 和发布候选验证已经执行 `npm run typecheck`，本轮沿用这些入口；尚未声明所有前端模块都已严格检查。

## 运行时边界

成功响应先经 `api/validation.js` 校验和规范化，再传给调用方。覆盖：

| 接口/数据 | 检查重点 |
| --- | --- |
| 餐食分析提交、恢复、轮询、纠错 | 请求 key、状态枚举、成功结果、评分、食物数组与内嵌字段、记录 ID |
| 摘要列表、完整列表、详情、手动记录、元数据保存 | 分页结构、计数、评分、数组、详情/写入 ID 与请求路径一致；完整列表根据查询参数区分数组和分页 |
| 当前饮食目标、趋势、最新周报、周报生成任务 | 目标枚举、统计数值、趋势条目、报告期间字段、周报餐数、任务 ID/状态、成功报告 |
| 营养读取、保存 | 记录 ID、两种版本号、开关、状态、食物索引、份量范围、每 100 克数据范围、未知值和合计结构 |
| 助手回复、会话列表、历史、主动建议 | 字符串、数组元素、角色/风险/投递状态枚举、消息 ID、分页游标、引用字段 |
| 助手 SSE 与 JSON 回退 | 已识别事件在回调前校验，完整回复使用同一回复契约，损坏 JSON 使用统一错误；未来未知事件不进入 UI |

必须的身份、状态、关键数值及营养版本号不会用类型强制转换掩盖异常。仅可选展示字段允许缺省：如风险标签/建议列表规范化为空数组，可空文本或未知营养数据保留 `null`；显式 `0` 原样保留。已有餐食的可选展示字段及历史 revision/metadataVersion 缺省行为保留兼容默认值。报告期间目前校验字符串类型，不代替后端日期和业务规则验证。

`ESTIMATED` 需要每项食物具备完整份量、每 100 克数据与合法合计；`INCOMPLETE` 不得带整餐合计；`DISABLED`/`STALE` 不得带已清除或失效的营养数值。不验证营养来源真实性，也不代替后端计算、权限或并发控制。

响应不合格时抛出 `ResponseValidationError`，代码为 `INVALID_API_RESPONSE`，页面显示“服务返回的数据格式异常，请重新加载后重试。”错误仅带定位路径，不携带原始响应值。异常数据不会发布为成功的饮食变更，也不会进入营养表单或完成的助手回答。分析轮询遇到格式异常立即停止，正常网络重试、超时、取消及登录失效逻辑保持现有规则。流式校验失败会结束读取并尝试取消已有助手请求。

营养草稿序列化也会拒绝 NaN/Infinity，避免 JSON 将非法数值转换成 `null` 后被误认为用户留空。

## 验证

- `npm run typecheck`、49 项前端单元测试、`npm run build` 通过。
- 5 项新增浏览器测试通过：异常摘要重试、异常详情重试、营养版本缺失阻止编辑及重新加载、异常保存不显示成功/合计、助手异常历史/流式消息及恢复。
- 完整浏览器回归 93 项通过，覆盖原有上传、恢复、纠错、营养、目标、周报、助手、管理、导航、移动端与数据隔离流程。
- 首轮完整浏览器回归暴露 5 个模拟响应缺失后端字段的问题，已补齐创建时间、分析 key、营养完整项计数等测试数据；后续校验提交 key 一致性时又发现额度测试使用固定标识，已改成回传真实提交标识。没有降低这些关键字段的校验标准。
- 日志（路径相对于项目根目录）：`logs/root/frontend-contracts-typecheck-final.log`、`logs/root/frontend-contracts-unit-final.log`、`logs/root/frontend-contracts-build-final.log`、`logs/root/frontend-contracts-api-e2e.log`、`logs/root/frontend-contracts-e2e-final-pass.log`；前两轮完整浏览器回归日志 `logs/root/frontend-contracts-e2e.log`、`logs/root/frontend-contracts-e2e-final.log` 保留了首次发现问题的记录。

修改位于当前工作区，本轮未更新运行中的业务容器或发布环境。
