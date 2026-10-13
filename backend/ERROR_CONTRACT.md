# 密码与错误处理约定

注册密码至少 10 个字符，最多 72 个 UTF-8 字节。登录和注销账户同样拒绝超过 72 字节的密码；中文等多字节字符按实际 UTF-8 字节数计算。编码器独立检查此限制，防止绕过 DTO 校验后发生 BCrypt 截断。既有合规 BCrypt 哈希继续兼容。

历史上若使用超过 72 字节的密码，旧 BCrypt 哈希无法证明第 72 字节之后的内容，不能据此恢复完整密码。这类账户需要通过可信身份核验后重设密码，不能通过截取旧密码绕过新限制。

| 场景 | HTTP | 业务码 |
| --- | --- | --- |
| 密码等字段校验失败 | 400 | VALIDATION_FAILED |
| JSON 格式错误、参数类型错误、缺少参数或上传部分 | 400 | BAD_REQUEST |
| 用户名已存在，包括并发注册竞争 | 409 | AUTH_USERNAME_CONFLICT |
| 超过上传限制 | 413 | UPLOAD_TOO_LARGE |
| AI 预算耗尽 | 429 | AI_BUDGET_EXCEEDED（附 Retry-After） |
| AI 预算存储不可用 | 503 | AI_BUDGET_UNAVAILABLE |
| AI 调用超时 | 504 | AI_DEADLINE_EXCEEDED |
| 上游 AI 失败或模型返回结果无效 | 502 | AI_CALL_FAILED |

注册以 PostgreSQL 的 `users_username_key` 唯一约束为最终依据，事务内主动 flush，把同名注册冲突转换为 409；其他数据库约束错误继续按服务器错误处理。

共享健康状态仅保留操作、成功/失败、时间和耗时等信息，失败摘要固定为“最近调用失败”。AI 错误响应和餐食降级建议不输出上游响应正文、模型原始内容或底层异常消息；本次调整的 AI 失败日志保留操作及异常类型供排查。

回归测试包含 UTF-8 边界及 BCrypt 同前缀不同后缀、真实视觉服务的错误传播、HTTP 参数校验、错误内容泄露检查，以及两个并发事务竞争同一用户名的 PostgreSQL 集成测试。

在 backend 目录运行 `mvn verify -Pintegration`（需要 Docker）执行完整后端验收。

2026-10-09 本轮验收：238 项单元及 HTTP 测试、66 项 PostgreSQL 集成测试全部通过，无失败或跳过。打包产物内 Spring Security config/web/core/crypto 均为 6.3.10。本次为已识别问题的定向修复，不代表整个依赖树均已更新或完成全面漏洞审计。
