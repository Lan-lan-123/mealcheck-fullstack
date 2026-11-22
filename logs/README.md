# 本地日志

散落的开发、测试、构建日志统一保存在此目录。以下路径均相对于项目根目录。

| 目录 | 用途 |
| --- | --- |
| `logs/root/` | 原项目根目录的测试、构建及后端启动日志 |
| `logs/frontend/` | 原 `frontend/` 下直接存放的日志 |
| `logs/backend/` | 后端新一轮构建、测试和启动输出 |
| `logs/deploy/` | 原 `deploy/` 下直接存放的日志 |
| `logs/loadtests/` | 原 `loadtests/` 下直接存放的日志 |
| `logs/crash/` | Java 的 `hs_err_pid*.log` 故障记录 |

2.0.0起，统一发布候选证据写入`logs/deploy/release-baseline/<时间>-<提交>/`；常规CI使用`logs/deploy/ci-<时间>-<运行编号>-<重试编号>/`。相关工作流归档这些新路径，历史`deploy/release-results/`、`deploy/recovery-results/`证据保留原位。

2026-10-18 归档时保留了原文件名和内容。`archive-manifest.json` 记录原路径、新路径、文件大小及 SHA-256，供本地核对。日志和归档清单不提交 Git；本说明保留在 Git 中。整个 `logs/` 不进入 Docker 构建上下文。

## 后续日志输出

所有新生成的本地日志应写入 `logs/`，项目级规则见根目录 `AGENTS.md`。统一命令入口会自动创建目录、在文件名中加入时间，保存标准输出和错误输出，并返回原命令的退出码。例如，从项目根目录执行：

```powershell
python tools/run_logged.py --group frontend --name build --cwd frontend -- npm run build
python tools/run_logged.py --group backend --name unit -- mvn -B -ntp -f backend/pom.xml test
```

入口默认在项目根目录运行命令；`--cwd` 相对于项目根目录，便于从任意工作目录调用。终端显示日志位置和退出码，命令详细输出保存在对应日志里；PowerShell 中可以检查 `$LASTEXITCODE`。此入口收集终端输出，不能自动改写子程序自行打开的文件位置；工具有 `--output` 等参数时，应同时设置为 `logs/<分类>/<批次>/`。

手动重定向输出时，同样先创建 `logs/` 下的目标目录，使用任务名和时间命名。仅有 `.gitignore` 不会阻止文件生成；直接指定根目录文件名仍然会在根目录写出日志。

仅移动现有崩溃日志不会改变 JVM 后续输出位置；如需固定故障日志位置，可在启动 JVM 时设置 `-XX:ErrorFile=<日志目录的绝对路径>/hs_err_pid%p.log`，并预先创建目录。

## 历史成套结果

`backend/target/`、`frontend/test-results/`、`frontend/playwright-report/`、`loadtests/results/`、`loadtests/mcp-results/`、`deploy/recovery-results/`、`deploy/release-results/`、`tools/.runtime/` 中的历史成套证据暂时保留原位，以免报告引用和签名校验失效。新任务的可配置输出目录统一选用 `logs/<分类>/<批次>/`，不要单独拆走成套证据里的日志。框架自动生成的 XML、HTML 等报告不属于 `.log` 终端日志。
