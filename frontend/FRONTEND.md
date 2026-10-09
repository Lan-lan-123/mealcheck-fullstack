# 前端工作台

栏目使用 History API 导航。例如 `/user/records`、`/user/goals`、`/admin/users` 和 `/admin/agent-coordination` 可以直接访问、刷新和分享，支持浏览器前进与后退。普通用户访问管理员地址会回到用户概览，后端权限验证保持原有实现。Vite 开发服务和项目 Nginx 配置均支持回退到 `index.html`；其他部署方式也需要配置 SPA 回退。

用户目标草稿在同一工作台切换栏目、前进和后退时保留，不会被记录筛选请求覆盖。浏览器整页刷新后会重新加载已保存数据。

## 维护入口

- `src/hooks/useWorkspaceNavigation.jsx` 与 `src/utils/workspaceRoute.js`：页面地址、栏目白名单和角色默认页。
- `src/components/DialogProvider.jsx`：统一确认、输入和消息弹窗；支持取消、Escape、焦点恢复和排队。密码输入保留原始字符。
- `src/components/FilterDisclosure.jsx`：手机默认折叠、桌面默认展开的筛选容器；草稿保存在父组件。
- `src/components/MiniBars.jsx` 与 `src/utils/barScale.js`：真实比例柱条，区分零值与缺失值。
- `src/components/ScoreTrend.jsx`：固定 0–100 评分纵轴，缺失值断开连线，支持聚焦与悬停查看数值。
- `src/pages/admin/AssistantEvaluationPanel.jsx` 与 `src/hooks/useAssistantEvaluation.js`：评测、历史对比、人工复核和候选样本状态；离开栏目后停止读取与轮询，草稿保留。
- `src/pages/admin/AgentCoordinationPanel.jsx` 与 `src/hooks/useAgentCoordination.js`：协调列表、筛选和时间线；忽略过期请求，关闭详情或离开栏目后停止相应轮询。
- `src/styles/index.css`：唯一样式入口。按功能拆分样式，并通过 `tokens.css` 集中维护基础色彩与字体。加载顺序说明见 `src/styles/README.md`。

## 验证

在 `frontend` 目录执行 `npm test`、`npm run build` 和 `npm run test:e2e`。Windows 可通过环境变量 `PLAYWRIGHT_CHROMIUM_EXECUTABLE` 指定本机 Chrome，例如 `C:/Program Files/Google/Chrome/Application/chrome.exe`。

浏览器测试使用模拟 API，覆盖请求隔离、草稿保护、URL 导航、移动筛选、弹窗取消、精确柱条和轮询停止；不会实际删除用户数据或调用付费模型。
