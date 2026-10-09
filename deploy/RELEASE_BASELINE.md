# 可追溯的发布候选基线

基线由一个 Git 提交、该提交的源码归档、数据库迁移校验值、固定镜像以及本轮验收证据组成。
这是工程发布候选验收；真实模型质量、真实供应商费用、完整一小时全功能负载和生产发布分别验收。

## 创建候选版本

先审查并提交业务源码、测试、依赖锁文件、配置示例及部署工具。真实密钥、上传文件、数据库备份和学习用 Word 文档不属于本轮候选源码。
脚本要求已跟踪文件没有未提交改动，通过 `git archive HEAD` 导出源码。未跟踪文件不会混入构建或验收。

在仓库根目录运行（Python 3.11+、Java 17、Maven、Node/npm、Docker Compose、k6、Pillow）：

```powershell
$env:PLAYWRIGHT_CHROMIUM_EXECUTABLE='C:/Program Files/Google/Chrome/Application/chrome.exe'
python deploy/scripts/release_baseline.py --version baseline-20261009-rc1
```

Linux CI 使用 `.github/workflows/release-baseline.yml` 的手动工作流，安装 Chromium 和 Nginx，并要求代理链验收通过。
既有自动 CI 保留。工作流没有发布、推送镜像或部署生产的权限。

## 验收内容

- 从同一个提交的干净源码归档运行后端单元/HTTP 与 PostgreSQL/RabbitMQ 集成测试。
- 周报协调监控测试分别在 UTC 与 Asia/Shanghai 时区执行；保留完整后端报告后再执行单项测试，避免覆盖完整结果。
- 数据库回归覆盖 UTC、上海和洛杉矶 JVM 时区下的原生 SQL 时间参数、ORM 时间存取与任务到期领取。LocalDateTime 已由 AppTime 明确存储口径，不额外设置 Hibernate JDBC Calendar 时区。
- `npm ci`、前端测试、生产构建、浏览器验收，以及运维、容量和模型桩的 Python 合约测试。
- 用该归档构建一次后端与前端镜像，写入提交和版本 OCI 标签，保存镜像内容 ID 与镜像归档。
- 容量/依赖故障测试和生产配置冒烟共用该后端镜像；生产配置冒烟使用该前端镜像。逐一核对运行容器的实际镜像内容 ID，禁止验收时重新构建。
- 完整迁移库恢复、运行栈数据库与图片联合恢复、备份后 HTTP 冒烟、告警规则检查。
- 验证代理链：本机缺少 Nginx 时在隔离 Python/Alpine 容器安装 Nginx 并执行；Linux 候选 CI 使用预装 Nginx，强制执行。

所有容器操作仅限新建隔离项目。发现容量验收同名项目已存在时拒绝操作。
隔离项目在 finally 中清理其专用卷；原项目、原数据库和上传文件不受影响。
模型请求仅向本地替身发出，生产配置冒烟禁用模型密钥。

## 证据与镜像保留

证据写入 Git 忽略的 `deploy/release-results/<时间>-<提交>/`：

| 文件 | 内容 |
| --- | --- |
| `manifest.json` 与 `.sha256` | 提交、Git tree、源码归档哈希、迁移/配置/依赖哈希、镜像身份、逐项检查与限制 |
| `source.zip` | 本轮提交的干净源码 |
| `images.tar` | 本轮已验收镜像，可用 `docker image load -i` 恢复 |
| `logs/`、`reports/` | 命令结果、完整测试报告、浏览器证据 |
| `capacity/acceptance.json` | 隔离容量与依赖恢复验收 |
| `stack-restored/verification.json` | 本轮实际运行镜像对应数据库的联合恢复证明 |
| `full-schema/restored/verification.json` | 完整迁移库与图片引用的恢复证明 |

失败、中断会记录 FAILED，不得把部分通过改写成整轮通过。日志与报告按 SHA-256 绑定；清单不包含生产密钥。
本地镜像 `imageId` 是内容寻址标识；未推送到仓库的镜像不虚构 registry digest。推送后另记仓库返回的 RepoDigest。
候选镜像标签以提交 SHA 命名；脚本拒绝覆盖已有同名候选镜像。重复验收可使用保留镜像进行专门验收，或创建新的候选提交并运行整轮流程。

## 发布与回退边界

仅在本轮要求的检查全部通过后创建本地候选标签。正式发布时使用本轮保留的同一个镜像内容，核对提交标签及镜像 digest，保存环境配置版本。
生产迁移前先执行数据库与上传文件一致备份，并证明该备份可恢复。
回退旧镜像的迁移兼容性需单独验证；本轮验证可恢复性，不承诺任意旧应用都兼容新数据库。
生产启用、异地备份、告警通知、真实模型质量和配额/费用验收应各有证据。
