# Docker 自动启动与桌面启动差异排查

2026-10-18，Asia/Shanghai。排查期间没有运行中的容器，实验使用正常退出，没有强制结束进程、重置 Docker、修改现有套接字、驱动或数据卷。

## 已证实的故障链路

工具直接启动的 Docker 在清理 Windows AF_UNIX 套接字文件时失败：`engine.sock` 或 `dockerInference` 的 remove 返回 `The file cannot be accessed by the system`，随后后端取消启动。真正由已存在的 Explorer 桌面窗口启动同一个程序则成功。

最小复现进一步将问题定位到执行上下文相关的文件访问差异，而非 Docker 配置或供应商额度：

| 实验 | 结果 |
| --- | --- |
| 工具在项目目录创建、关闭、清理一次性 Unix 套接字 | 成功 |
| 工具在用户 LocalAppData/Temp 创建、关闭、清理一次性 Unix 套接字 | 成功 |
| 工具在用户 LocalAppData/Docker/run 创建、关闭、清理独立随机命名的 Unix 套接字 | 创建成功，删除返回系统无法访问此文件 |
| Explorer 启动的进程删除上一步完全相同的测试文件 | 成功 |

测试文件名为 `codex-diagnostic-42178d0496ee459dae906a8dccd2c53c.sock`，已清理。桌面清理证据：`logs/deploy/docker-desktop-socket-cleanup.json`。这不是根据“Docker 报错”猜测残留文件损坏：同一文件在桌面上下文可以正常删除。

## 已排除和未证实的机制

- 工具与 Explorer 使用相同 Windows 用户、登录会话 AuthenticationId、管理员权限；不是简单的换用户或缺管理员权限。
- 保持工具创建进程，仅完整替换为 Explorer 环境变量，仍失败；环境变量不是充分原因。
- 切换到 Explorer 的工作目录 `C:/Windows/system32`，仍失败。
- 单实例、正常退出后启动仍能复现；不是重复启动的充分解释。
- 工具作业标志为 `0x2800`，桌面探针为 `0x1800`。但只用允许的后台分离标志后仍失败，因此不能把 `KILL_ON_JOB_CLOSE` 当成已证实的直接原因。
- 系统存在文件系统过滤驱动。上述最小复现支持工具进程链的文件访问过滤/隔离与该目录中 Unix 套接字处理存在兼容问题，但没有内核调用栈证据，不能指认某个具体驱动或声称知道其内部实现。

## 恢复及后续启动方式

通过现有 Explorer 桌面窗口的 `Document.Application.ShellExecute` 请求启动，确认 Docker 主启动器的父进程确实为 Explorer。仅 `New-Object Shell.Application` 后直接调用 `ShellExecute` 仍可能在工具进程内启动，实验中依然失败，不等同于桌面入口。

21:14:47 Secrets Engine ready，21:14:50 Linux/WSL 引擎进入 running，随后 `docker info` 正常返回引擎版本 29.4.3。Docker 已恢复。项目五个容器维持排查前的 exited 状态。

之后启动 Docker Desktop 应使用真实桌面入口，避免工具直接创建这个常驻后台程序。Docker 运行后可以继续使用已授权的 Docker CLI 管理项目。

本次更正：此前将现象描述为“Docker 损坏/只能修复临时文件才能启动”证据不足；已验证的是特定工具执行上下文无法完成 Docker 运行目录中套接字文件的清理。

## 2026-10-21 再次验证

RAG 固定小集验证时，直接启动再次在 `dockerInference` 套接字 remove 处失败；正常停止后，通过已存在 Explorer 的桌面窗口入口恢复，引擎返回 29.4.3。没有打开资源管理器文件夹窗口时，`Windows()` 普通枚举可能为空，可取得现有桌面窗口：

```powershell
$shell = New-Object -ComObject Shell.Application
$handle = 0
$desktop = $shell.Windows().FindWindowSW([ref]0, [ref]0, 8, [ref]$handle, 1)
$desktop.Document.Application.ShellExecute(
  'C:\Program Files\Docker\Docker\Docker Desktop.exe',
  '', 'C:\Program Files\Docker\Docker', 'open', 0)
```

本轮确认 Docker 主启动器父进程确为原 Explorer。随后仅启动原 `mealcheck-postgres`，沿用原数据卷完成索引同步；没有修改套接字、Docker 配置或数据卷。
