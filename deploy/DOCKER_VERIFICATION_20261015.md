# Docker 诊断及恢复验收（2026-10-15）

当前 Docker 已恢复可用。本轮首次在宿主机权限下检查时，引擎已运行；本轮没有执行修复性重启、升级或重置，不能把已经发生的恢复归因于本轮操作。真实容器验收确认，此前阻塞数据库和消息队列测试的问题当前已解除。

## 原故障及当前状态

北京时间 13:05～13:10 的启动日志反复记录：初始化 Inference manager 时，无法移除 `%LOCALAPPDATA%\Docker\run\dockerInference`，错误为 `The file cannot be accessed by the system`，随后 Docker backend 取消启动并退出。这是 Docker Desktop 的运行时套接字访问故障；仅凭现有记录，无法确定该文件为何不可访问，没有证据表明业务项目的 Compose 配置导致启动失败。

本轮看到 `com.docker.backend` 进程启动于 **14:27:31**，当前 `desktop-linux` 上下文的 Docker Engine **29.4.3** 能正常响应。恢复发生在本轮宿主机检查之前，具体恢复操作并非本轮执行，也未从日志确认。

另外，在 Codex 受限命令环境中，访问用户 Docker 配置、contexts 和 WSL 会报 Access is denied；获得宿主机执行权限后，同机 Docker 正常。后续应区分执行权限不足与引擎启动失败，避免只凭受限命令结果判定 Docker 已坏。

## 验收结果

- `docker version`、`docker info`、显式 `docker --context desktop-linux version` 均正常。
- `docker compose -f docker-compose.yml -f deploy/compose.local.yml config --quiet` 通过，本地 Compose 配置有效，未打印解析后的秘密配置。
- 执行 `mvn -B -f backend/pom.xml -Pintegration failsafe:integration-test failsafe:verify`，使用上一轮刚构建的当前代码及测试类。
- **115 项集成测试通过，0 失败、0 错误、0 跳过，BUILD SUCCESS，耗时 1 分 53 秒。**
- PostgreSQL pgvector 与 RabbitMQ 临时容器真实启动；Flyway 执行 32 个迁移至 V32。覆盖餐食任务、事务、消息重投、预算、历史记录与周报等流程。
- 测试结束后没有残留 Testcontainers 容器，Docker 运行容器数恢复为 0。

本轮没有启动或修改已有业务栈，没有重置 Docker、删除业务卷或镜像；使用隔离数据库及模型替身，**没有新增付费模型调用**。这补齐了基础设施集成验收，仍不等同于当前版本部署后的前端登录、上传端到端测试。

## 周报思考模式由谁控制

当前是服务端全局配置，默认 off。普通用户没有页面开关，周报请求也没有逐次选择参数。部署维护者可以调整：

```dotenv
MEALCHECK_AI_WEEKLY_THINKING_MODE=off
# 若要低力度思考，将上面的 off 改成 low
MEALCHECK_AI_THINKING_BUDGET_TOKENS=512
```

修改后需要让后端以新环境启动。Compose 环境变量变更需要重新创建后端容器，单纯 restart 不会重新读取新的环境。此设置统一作用于周报趋势、计划和审核步骤；当前供应商思考字段仅对代码识别的 Qwen3.8 Max/Flash 系列注入。另有 provider 模式，用于保留供应商请求默认策略。本轮未修改现有模式。

配置位置：[application.yml](E:/mealcheck-fullstack/backend/src/main/resources/application.yml:135)、[AiChatRequestPolicy.java](E:/mealcheck-fullstack/backend/src/main/java/com/example/mealcheck/service/AiChatRequestPolicy.java:78)、[Compose](E:/mealcheck-fullstack/docker-compose.yml:136)。

## 本地证据

- [验收摘要](E:/mealcheck-fullstack/loadtests/results/docker-verification-20261015/summary.json)
- [完整集成测试日志](E:/mealcheck-fullstack/loadtests/results/docker-verification-20261015/integration.log)
- [Failsafe 汇总](E:/mealcheck-fullstack/loadtests/results/docker-verification-20261015/failsafe-summary.xml)
- [旧启动故障摘录](E:/mealcheck-fullstack/loadtests/results/docker-verification-20261015/previous-startup-error.log)
- [最终引擎状态](E:/mealcheck-fullstack/loadtests/results/docker-verification-20261015/final-engine-state.txt)

运行证据保存在本地忽略目录。未来若再现同类错误，应先检查宿主机权限及启动日志，再依据实际状态处理；当前无需中断健康引擎或清理业务数据。

后续已在本地隔离 Docker 环境完成真实浏览器、数据库、队列及真实模型的关键端到端流程验收，最终 10 个场景全部通过，见 [完整端到端验收](E:/mealcheck-fullstack/deploy/FULL_E2E_20261015.md)。
