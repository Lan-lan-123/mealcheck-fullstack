# MealCheck MCP 实际调用演示

这份演示用 Python 标准库作为 MCP 客户端，连接真实后端的 `/mcp`。不需要安装额外 Python 包，也不调用生成模型。客户端依次完成初始化、工具发现和三个只读工具调用。

## 一条命令复现

先确保项目已启动，再在 CMD 中执行：

```cmd
cd /d E:\mealcheck-fullstack
python deploy\scripts\mcp_demo.py --demo-account --output loadtests\mcp-results\demo.json
```

脚本创建唯一命名的临时账号，从注册响应取得 JWT，演示结束后使用账号注销接口删除该临时账号，并确认令牌不能再访问餐食接口。它不操作已有账号。

## 实际调用顺序

1. `initialize`：客户端发送名称与协议版本，服务器返回 `mealcheck-mcp-server` 和协商后的协议版本。
2. `notifications/initialized`：客户端确认初始化完成。
3. `tools/list`：获取工具名称、说明和输入 Schema。
4. `tools/call` → `route_assistant_skill`：判断问题对应的饮食 Skill。
5. `tools/call` → `get_my_recent_meals`：读取 JWT 对应账号的近期餐食。
6. `tools/call` → `get_my_weekly_report`：读取同一账号的周报。

HTTP 地址是 `http://localhost:8080/mcp`，每次请求携带 `Authorization: Bearer <JWT>`。脚本处理服务端返回的会话 ID，并接受普通 JSON 或 SSE 响应；输出记录不保存 JWT、密码或会话 ID。

例如，选择减脂 Skill 的实际请求为：

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "route_assistant_skill",
    "arguments": {
      "question": "我想减脂，晚餐怎么搭配？"
    }
  }
}
```

工具返回的 `structuredContent.skill` 为 `fat-loss`，同时返回描述、意图和提示指令。近期餐食工具返回 `{"meals": []}`，因为新建的临时账号没有餐食记录；周报的 `totalMeals` 相应为 0。

首次实际演示已成功发现以下五个工具：

```text
search_diet_knowledge
route_assistant_skill
list_assistant_skills
get_my_recent_meals
get_my_weekly_report
```

本脚本调用了其中三个；知识检索和 Skill 列表在这次演示中仅被发现。终端摘要中的 `passed: true` 表示这条调用链成功，`temporaryAccountDeleted: true` 表示临时账号已清理。

## 使用自己的账号读取真实记录

登录 MealCheck 网页，在 F12 Console 执行 `localStorage.getItem('mealcheck_token')`，把不带外层引号的值设置到本地 CMD 环境变量：

```cmd
set "MEALCHECK_MCP_TOKEN=替换为自己的JWT"
python deploy\scripts\mcp_demo.py --output loadtests\mcp-results\my-demo.json
set "MEALCHECK_MCP_TOKEN="
```

没有 `--demo-account` 时，脚本只读取该账号的数据，不创建或删除账号。`loadtests/mcp-results/` 已加入 Git 忽略规则。

这次使用脚本明确选择工具；接入外部 AI 应用后，可以由模型提出工具调用需求，再由该应用的 MCP 客户端执行。项目网页里的助手仍走 REST 与本地业务服务调用。
