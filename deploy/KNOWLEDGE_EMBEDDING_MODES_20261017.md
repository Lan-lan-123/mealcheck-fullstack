# 管理员选择知识库向量方式

管理员在「RAG 知识库维护」的「向量生成方式」中选择本地哈希向量或语义向量模型，再点击「重建系统 RAG 知识库」。两种方式复用 Markdown 主题切分器。默认语义模型为 `qwen3.7-text-embedding-flash`，默认维度为 512。

## 配置与部署

部署新版前后端，由 Flyway 应用 V37。多实例须统一代码、知识源版本及向量接口、模型、维度和存储配置。

单独配置向量服务时设置 `MEALCHECK_KNOWLEDGE_EMBEDDING_API_KEY`、`MEALCHECK_KNOWLEDGE_EMBEDDING_BASE_URL`、`MEALCHECK_KNOWLEDGE_EMBEDDING_MODEL`。模型必须支持配置的维度。Flash 支持 256、512、768、1024。

专用接口地址和密钥都为空时，可复用已有阿里云百炼聊天接口和密钥；只认可 DashScope 和阿里云 MAAS 主机。不会将聊天密钥发送到另外指定的向量接口，也不会复用其他聊天供应商的凭据。浏览器只接收模型名称和配置可用状态，不接收密钥。选择本地模式时，生成本地向量不调用外部模型。

选择结果保存在数据库，重启后保留。未选择过的数据库保留原有显式 Embedding 配置的行为；只有聊天配置时，初始模式仍为本地，需要管理员选择语义模式后重建。

## 后台流程

`GET /api/admin/knowledge/indexing` 读取当前方式和最新任务；`POST` 请求体为 `{"mode":"LOCAL"}` 或 `{"mode":"SEMANTIC"}`，返回 202 和任务信息。两个接口均限制管理员访问。

任务先使用当前生效的方式同步系统知识源，再每批处理五条数据库知识（包含管理员新增的片段），将目标向量暂存。全部准备好后，在同一个数据库事务内替换向量、索引和生效方式。准备阶段检索继续使用旧方式。并发写入会检查向量签名，防止切换后写回旧向量。

同一时间只允许一项重建。页面轮询显示进度，刷新后可继续查看。接口失败会保留暂存进度并重试，失败达到上限后停止；管理员用同一目标重新提交可恢复原任务。语义模型失败不会静默改用本地哈希。

重建包含明确的系统知识源同步：该来源的人工编辑可能被随部署携带的文件替换；`source=admin` 的独立知识保留。模型准备失败不会切换生效方式，但此前成功的知识源同步可能已经更新系统正文。

## 本次验证

- 真实百炼请求：`qwen3.7-text-embedding-flash` 成功返回 512 维向量，测试文本用量 22 token。证据：根目录 `knowledge-mode-live-embedding.json`。未执行整库真实模型重建，也未进行 108 题检索质量评测。
- 完整后端单元/HTTP 回归：381 项通过；最后补充的模式重试和向量返回值校验相关回归：15 项通过。
- 前端单元：76 项通过；浏览器回归：12 项通过，含模式切换、失败状态、刷新恢复、管理员页面隔离和手机宽度检查。
- 前端类型检查、生产构建、改动空白检查通过。桌面与手机版式已检查。
- PostgreSQL 集成验收未通过环境启动：Docker 引擎不可用，Testcontainers 在创建数据库前失败。V37 及新增数据库测试已编译；待 Docker 可用后执行以下命令补验。

```powershell
mvn -f backend/pom.xml --batch-mode --no-transfer-progress verify -Pintegration "-Dtest=KnowledgeEmbeddingServiceTest,KnowledgeMaintenanceServiceTest,KnowledgeIndexingControllerTest" "-Dit.test=KnowledgeRebuildDatabaseIntegrationTest,KnowledgeSourceSyncDatabaseIntegrationTest,KnowledgeEmbeddingModeDatabaseIntegrationTest"
```

新增数据库测试覆盖模式与向量一并激活、未同步源时拒绝激活、失败回滚、显式恢复暂存任务、拒绝旧方式的并发写入，以及真实切分/入库链路中保留管理员知识并来回切换两种方式。模型响应用隔离替身，不调用真实模型。
