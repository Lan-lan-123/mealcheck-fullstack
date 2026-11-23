# 2.0.0 Linux源码归档验证与Vite补丁（2026-10-30）

本轮补验了此前未完整运行的Linux测试路径，并在全新依赖安装时发现npm审计告警。后端源码从Git归档重新编译；前端补丁升级后重新安装、测试和构建。没有推送、提交实际项目或部署业务服务。

## 发现和修改

- 前端原先固定`vite@8.3.1`，npm报告1个高危依赖节点，关联多条公告。其中Windows开发工具链问题见[GHSA-587h-7wmj-h5pm](https://github.com/advisories/GHSA-587h-7wmj-h5pm)，该公告将Vite 8.3.0～8.3.2列为受影响版本。
- 按npm建议，将唯一直接依赖变更固定为`vite@8.3.5`，同步锁文件；该补丁属于现有8.3系列，[官方发布记录](https://github.com/vitejs/vite/releases/tag/v8.3.5)可查。没有执行`npm audit fix --force`。锁文件同时更新Vite所需的Rolldown、PostCSS等传递依赖，保留全部平台条目。
- 修复后Linux的结构化`npm audit`结果为0条已报告漏洞；这表示当次审计数据库未命中，不能替代完整安全审查。
- 首次浏览器回归使用10个工作进程，同时存在Linux安装/测试任务，165项通过、2项超时。两个相关文件在2个工作进程下8项均通过。将`CI=true`时的浏览器并发固定为2，避免随本机CPU数量扩大；测试断言、30秒用例上限及零重试策略保持不变。最终全量结果保存在本轮证据中。

## 验证范围

| 项目 | 结果 |
| --- | --- |
| 本轮开始时的Git归档与工作区 | 1390文件按换行规范核对一致 |
| Linux后端重新编译及默认测试 | 868项：860通过、8按条件跳过，零失败/错误 |
| 补丁后Linux前端 | 全新`npm ci`、类型检查、102项单元测试、生产构建通过 |
| 补丁后npm审计 | 0条已报告漏洞 |
| 受控并发浏览器专项 | 8项通过 |
| 补丁后完整浏览器回归 | 167项全部通过，零重试 |

后端使用Maven 3.9.9/Temurin 17构建阶段，从归档重新编译应用与测试，复用Maven下载缓存，没有复用本机`target`。前端使用Node 22 Alpine容器和全新依赖安装，没有复用本机`node_modules`。补丁后前端输入为原归档加已更新的两个包配置文件，后端输入保持原归档；本轮未再次运行数据库集成或重新构建生产镜像。Linux容器和本机Chrome浏览器分别覆盖相应环节，不等同GitHub Ubuntu runner对最终提交的整轮运行。

## 本地证据

- 归档输入、后端XML、修复前后npm审计JSON、汇总和哈希：`logs/deploy/ci-linux-archive-20261030-191545/`。
- 后端：`logs/backend/ci-linux-archive-unit-20261030-191658-155780.log`。
- 升级：`logs/frontend/vite-security-patch-install-20261030-192043-841957.log`。
- 补丁后Linux前端：`logs/frontend/ci-linux-vite-patched-20261030-192134-767707.log`。
- 首次浏览器：`logs/frontend/vite-security-browser-20261030-192134-445980.log`；专项：`logs/frontend/vite-security-targeted-20261030-192600-244169.log`；最终全量：`logs/frontend/vite-security-browser-final-20261030-192725-714342.log`。
- 浏览器最终报告及失败记录分别保留在`logs/frontend/vite-security-browser-final-20261030/`与`logs/frontend/vite-security-browser-20261030/`，未覆盖首次失败证据。
