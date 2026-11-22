# Cookie 认证加固（2026-10-26）

浏览器使用 HttpOnly Cookie 保存 JWT，PostgreSQL 保存退出撤销记录。Redis 继续承担验证码、登录限流等职责，令牌撤销检查不依赖 Redis。

## 登录与恢复

登录、注册响应 JSON 只包含 username/displayName/role，不返回 JWT。生产 Cookie 为 `__Host-mealcheck_session`，设置 `HttpOnly; Secure; SameSite=Lax; Path=/`，不设置 Domain；生产配置强制 Secure。HTTP 本地开发使用 `mealcheck_session`。生产浏览器应通过 HTTPS 入口访问，TLS 可终止于可信反向代理。

前端启动通过 `GET /api/auth/me` 恢复身份。401 显示登录页，503 显示重试入口，不把依赖故障当成退出。身份只在内存保存，启动、登录和退出清理旧 localStorage 的 mealcheck_token/mealcheck_user。图片、普通 API、流式请求统一带 Cookie，不再组装 Authorization Header。

有效期沿用部署配置：生产默认 60 分钟，开发默认 10080 分钟。HttpOnly 限制脚本读取凭证，不能阻止已经执行的恶意脚本借页面发起操作；CSP、内容渲染和依赖安全措施继续有效。

## CSRF

- 首次写操作前调用 `GET /api/auth/csrf`，取得 Spring Security 的掩码 token 与 headerName=X-XSRF-TOKEN，并由后端写入 HttpOnly CSRF Cookie。
- 所有 POST、PUT、DELETE 等写操作提交 X-XSRF-TOKEN，包括登录、注册、退出、图片上传、流式对话和管理接口。permitAll 不代表豁免 CSRF。
- 使用 CookieCsrfTokenRepository 和默认 XOR 请求处理。生产 CSRF Cookie 名为 `__Host-mealcheck_csrf`，设置 Secure、HttpOnly、SameSite=Lax、Path=/，不设置 Domain。
- 登录成功、退出成功清除旧 CSRF Cookie，前端使内存 token 失效，下一次写操作重新获取。CSRF token 与认证 JWT 分开。
- 校验失败返回 `403 / CSRF_INVALID`。前端清除缓存的 CSRF token，不自动重放写请求；用户下次明确操作时重新获取。

CORS 只允许明确来源，允许凭证和 X-XSRF-TOKEN，拒绝非受信 Origin。前后端优先使用同源 /api 代理；SameSite=Lax 不适用于任意跨站部署。

## 持久化退出和故障处理

Flyway V50 新增 `auth_token_revocations(token_id, expires_at, revoked_at)`，只保存已验证 JWT 的随机 jti，不保存完整 JWT。

退出先提交数据库撤销事务，再清除认证和 CSRF Cookie，返回 204。重复退出幂等。写入或提交失败返回 `503 / AUTH_DEPENDENCY_UNAVAILABLE`，不清除认证 Cookie、不提示退出成功。提交结果不确定时，重试不会撤销其他设备凭证。

每次鉴权验证签名、有效期、认证代次、jti、用户 ID/用户名、账号状态和 token_version，并在数据库检查撤销记录。数据库不可用返回 503，不放行。不缓存“未撤销”结果；独立实例共享同一 PostgreSQL，撤销提交后新请求可以观察到。已通过鉴权并开始的请求及后台任务不会因退出被强制取消。

Redis 清空或暂时不可用不会复活退出凭证，已有 Cookie 的认证和退出仍可工作。验证码和登录限流仍依赖 Redis，故障时登录入口可能按既有限流策略拒绝请求。

撤销记录按 jti 主键查询，每小时分批清理最多 1000 条已过期超过一小时的记录。清理失败只延后维护，不降低鉴权要求。

V47 的 enabled/token_version、封禁解封与审计机制继续有效。解封不恢复旧凭证，用户必须重新登录。

## 认证代次与数据库恢复

新 JWT 必须包含 auth_gen，并与 `MEALCHECK_AUTH_GENERATION` 一致，默认 cookie-v1。此前缺少该字段的 JWT 全部失效。所有后端副本必须使用相同代次。

旧数据库备份可能缺少后续撤销与封禁信息。实际恢复时必须：

1. 停止入口流量及全部写入/认证实例，完成数据库与图片的一致恢复。
2. 将恢复环境的 MEALCHECK_AUTH_GENERATION 改为新的唯一值，例如 restore-20261026-01；不要恢复旧代次。
3. 使用同一新代次启动全部实例，确认原凭证返回 401、新登录正常，再恢复入口。

不要为使登录失效而随意轮换 JWT 签名密钥。AI 配置和向量配置加密密钥可能回退从 JWT 密钥派生，轮换前须单独验证密文迁移。认证代次切换不修改密钥或既有密文。

## 升级和工具

先应用 V50，统一升级前后端和所有实例，要求用户重新登录。旧实例不执行新撤销与代次规则，不能混跑。保留签名密钥、独立 AI 配置加密密钥及数据卷。

新签发 JWT 仍允许非浏览器工具通过显式 Bearer Header 提交，写操作同样要求 CSRF；旧代次不恢复。Python 验收入口通过 tools/cookie_http.py 获取 CSRF，并从 Set-Cookie 提取工具凭证，仅在私有 CLI 适配层补回旧账号文件需要的 token 字段，不改变服务端 JSON。Node 工具通过 deploy/scripts/cookie-browser.mjs 向浏览器上下文安装 HttpOnly Cookie，不向页面注入 JWT。k6 写操作使用 loadtests/auth-headers.js。工具账号文件仍是敏感凭证，不提交 Git。

本轮只使用临时 PostgreSQL、Redis、模型替身或模拟 API，Redis FLUSHDB 和 pause/unpause 仅针对 Testcontainers 创建的实例。所有新增终端日志和配套报告位于根目录 logs/。

可通过 `-Dmealcheck.buildDirectory=<绝对独立目录>` 隔离编译，避免其他构建更新 target。默认路径仍为 backend/target；通过 mealcheck.testReportsDirectory 将报告保存到 logs/。

## 验证记录

- 后端完整单元/HTTP：786 项，781 项通过、5 项真实模型条件测试跳过，无失败或错误。日志：`logs/backend/auth-cookie-isolated-verification-20261026-134004-758475.log`；报告：`logs/backend/auth-cookie-20261026/isolated-reports/`。
- 相关 PostgreSQL 集成 17 项通过：认证与真实 Redis 故障 4 项、餐食/流式 HTTP 等 12 项、完整运行时装配 1 项。认证专项在增加独立认证过滤器实例的 HTTP 检查后再次 4/4 通过，确认一个实例退出、另一个实例随后拒绝原凭证且接受另一独立登录。后者使用独立过滤器和服务对象共享真实数据库，不是两个完整 JVM 的容量验收。补验日志：`logs/backend/auth-cookie-replica-final-20261026-134738-526181.log`；报告：`logs/backend/auth-cookie-20261026/replica-final/`。
- 前端 92 项单元测试、类型检查和生产构建通过。日志分别为 `logs/frontend/auth-cookie-unit-expanded-20261026-133004-479217.log`、`auth-cookie-typecheck-final-20261026-133440-524713.log`、`auth-cookie-production-build-20261026-134411-682041.log`。
- Python CLI 适配 4 项、Node CLI/浏览器 HttpOnly 传递、隔离集群命名空间保护 1 项及 k6 回环契约 11 项通过。日志位于 `logs/deploy/auth-cookie-cli-final-20261026-133444-604604.log`、`auth-cookie-node-cli-20261026-134328-714030.log`、`auth-cookie-cluster-safety-20261026-134930-740122.log` 和 `logs/loadtests/auth-cookie-capacity-contract-20261026-134004-852401.log`。
- 完整浏览器回归 **151/151 通过**，覆盖 HttpOnly 凭证不可读、清理旧存储、刷新恢复、退出故障保留登录、503 重试入口，以及既有上传、流式对话、预算和管理页面。完整结果见 `logs/frontend/auth-cookie-browser-complete-20261026-134934-083986.log`，配套输出位于 `logs/frontend/auth-cookie-20261026/complete/`。

原始失败记录保留：测试辅助 csrf() 曾替换真实 Cookie 仓库，已隔离该用例；初轮集成用例的空 Cookie 参数和导入冲突已修复；共享 target 在其他构建期间被更新导致的类缺失通过独立编译目录解决。浏览器测试的模拟登录夹具已改为 /auth/me 与 CSRF 协议，并修复导航竞争、缺失偏好/向量配置响应及未等待初始目标加载的测试时序，未通过放宽应用鉴权来使测试通过。

本轮未部署业务环境、未切换运行中用户会话，也未调用真实模型。部署时需要应用 V50 并统一升级实例；数据库恢复时必须按上文切换认证代次。根目录既有四份 2026-10-18 日志未改动，本轮新增日志统一位于 logs/。
