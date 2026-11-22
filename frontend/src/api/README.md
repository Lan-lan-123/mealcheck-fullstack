# API 模块约定

页面继续从 `client.js` 导入公开接口。该文件保留用户业务接口，并显式转出管理员、助手及会话接口，避免页面依赖底层通信细节。

- `transport.js`：统一 HTTP 请求、超时、Cookie 会话、共享 CSRF 初始化和错误类型。会话状态只有这一份，普通请求与流式请求共同使用。
- `assistant.js`：同步问答及 SSE 通信、事件验证、取消和兼容回退。
- `admin.js`：管理员业务接口。
- `query.js`：查询参数序列化。
- `validation.js`：服务响应的运行时验证；`contracts.d.ts` 保存对外类型契约。

运行 `npm run typecheck` 会执行现有前端检查和 `tsconfig.api.json` 的严格 JavaScript 检查。API 目录下新增的 `.js` 文件自动纳入检查，不需要依靠手工添加 `@ts-check`。`typecheck/apiContracts.ts` 用不可执行的编译期用例约束公开调用方式。

新增接口时补齐参数及返回类型，并在 `validation.js` 为需要使用的数据提供运行时验证。底层动态请求入口仍兼容尚未收敛的旧接口；声明类型不能代替验证外部 JSON。新增通信逻辑应复用传输层，避免复制会话或 CSRF 状态。
