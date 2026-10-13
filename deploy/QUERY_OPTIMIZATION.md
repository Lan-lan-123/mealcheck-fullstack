# 数据库查询优化（2026-10-13）

本轮减少管理端列表的实体加载、联表范围和趋势查询的重复扫描，并提供查询统计入口。它不构成生产并发容量验收；改动尚未部署到运行数据库。

## 实现与兼容性

- 管理端 `GET /api/admin/meals` 保留页码、总数、筛选和响应字段。查询只取列表需要的六项餐食字段，先选当前页，再关联用户；计数无用户名筛选时不联表。计数和列表在同一个只读、可重复读事务中执行。
- 排序明确为 `created_at DESC,id DESC`，相同时间的分页顺序稳定。结束日期保留下一业务日的排他边界，确保不会包含下一日零点记录。
- 新增 `GET /api/admin/meals/cursor`，保持管理员权限。参数支持 `size`、`username`、`from`、`to`、`minScore`、`maxScore`、`beforeTime`、`beforeId`。首屏不传游标；后续同时传回上一页的 `nextBeforeTime` 和 `nextBeforeId`，保持原筛选条件。时间原样使用返回的存储时间，不进行客户端时区换算。
- 游标响应包含 `content`、`hasMore`、`nextBeforeTime`、`nextBeforeId`、`size`；最多取 `size+1` 条，不查询总数，不使用 OFFSET。带偏移的游标时间转换到存储时区，无偏移的旧格式按存储时间解析。末页没有下一游标。现有管理界面仍用页码接口，已获得字段裁剪优化；切换游标接口需要调整交互与前端调用。
- 失败列表先选当前页，再关联分析请求、发件箱和周报。周报关联和人工重试通过 bigint 主键查找，不再把主键转成文本。
- 上传趋势将餐食和异常事件各按整个日期范围聚合一次，再关联日期序列，保留空日期、告警数量、去重封禁用户数以及业务时区边界。

## 迁移

V30 为 `task_failure_events` 增加生成列 `weekly_report_job_id`。仅接受正整数、无前导零、bigint 范围内的周报键；其他键生成 NULL。旧文本身份仍用于审计和幂等处理。该列不加任务外键，已清理任务的失败记录仍可查看。

**增加 STORED 生成列需要重写失败事件表并取得表锁。** 部署前检查该表大小、长事务和可用磁盘；在维护窗口执行并设置合适的迁移连接锁等待上限。对于非常大的失败表，应改用普通列、分批回填与兼容写入的分阶段迁移，不能直接套用此 DDL。

V31 为用户名/显示名的 `lower(...)` 模糊检索、全局餐食分页、异常上传日期、用户消息日期、失败列表时间及失败操作历史建立七个索引。

- `V31__query_access_indexes.sql.conf` 设置 `executeInTransaction=false`；应用设置 `spring.flyway.postgresql.transactional-lock=false`。直接运行 Flyway 的集成测试同样使用会话锁，避免并发建索引等待迁移自身的事务锁。
- 索引用 `CREATE INDEX CONCURRENTLY`，仍会增加 I/O、CPU 和磁盘占用，并可能等待旧事务。避免多个发布进程同时构建索引。
- 非事务迁移中断可能留下 INVALID 索引和部分已完成索引。脚本会拒绝静默接受本轮的无效索引。DBA 应检查 `pg_index`，仅清理确认无效的本轮索引，修复失败的 Flyway 历史后重试；不要直接删除有效索引或修改已执行脚本校验和。
- 本轮没有删除旧索引。需积累完整业务周期的索引使用数据后再评估合并，避免把低频但必要的索引误判为无用。

参考：[Flyway PostgreSQL 锁配置](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database)。

## 查询监控

提供可选的 `deploy/compose.query-monitoring.yml`。与实际部署的基础 Compose 文件一起使用，启用 `shared_preload_libraries=pg_stat_statements`。**启用需要 PostgreSQL 重启，本轮没有应用该配置。** 若已有其他预加载扩展，应合并配置，不能覆盖。

重启并检查后，用数据库所有者执行 `deploy/sql/enable-query-statistics.sql`。监控角色运行 `deploy/sql/query-performance.sql`，读取 SQL 调用次数、累计/平均耗时、缓存/磁盘块、临时块、锁阻塞和表维护情况。脚本只输出 queryid，不输出完整 SQL 或用户消息。

先按累计耗时和调用频率定位 SQL，再由 DBA 将 queryid 映射到受控 SQL。接口 P95/P99 由请求监控采集；`pg_stat_statements` 的平均值不能代表尾延迟。执行计划检查使用代表性数据、真实筛选比例和多次预热后的比较。

## 验证

- 完整回归中全部 269 项后端单元/HTTP 测试和 99 项隔离 PostgreSQL 集成测试通过，无失败、错误、跳过；日志 `backend/target/query-optimization-verify.log`。最终增加游标偏移解析测试后重新运行全部单元/HTTP 回归，270 项全部通过，无失败、错误、跳过，日志 `backend/target/query-optimization-unit-final.log`。共验证 369 项不同测试。
- 新增数据库测试覆盖字段裁剪后不加载实体、业务日结束边界、空值、游标同时间排序和插入稳定性、上传统计、旧失败键以及 V29 升级。既有失败任务测试补充分页关联；既有周报人工重试、幂等和执行令牌保护测试继续适用。
- `AdminQueryOptimizationDatabaseIntegrationTest` 在隔离数据库准备 20,000 条含长建议的餐食，记录实际列表 SQL 与完整行查询的执行计划到 `backend/target/admin-query-plans.txt`。已确认列表使用 `idx_meal_records_admin_cursor`，分页子查询仅输出 20 行，无 JPA 实体加载。单用户、热缓存、小页查询的毫秒耗时和执行计划 width 估算不代表生产吞吐量或实际网络字节。
- 空库全量迁移及 V29 到 V31 的旧数据升级均通过；并发索引有效，周报人工重试、执行令牌保护、账户删除等既有集成测试通过。

复现完整验证（需要 Docker）：

```powershell
Set-Location E:/mealcheck-fullstack/backend
mvn --batch-mode --no-transfer-progress verify -Pintegration
```

上线容量验证需覆盖生产预计数据规模、读写混合、冷热缓存、热点用户、全局预算/准入锁等待、多个应用实例和故障恢复，并测量接口 P95/P99、数据库 CPU/I/O、连接池等待、锁等待及缓存命中率。

## 后续重点

当前仍保留管理列表的精确计数、管理分析的实时 JSON 聚合，以及 AI 全局预算的强一致热点行。每日汇总需要覆盖新增、纠错、删除、改餐食日期、历史回填和重建；多实例缓存刷新需要可靠协调与失效。应在数据库验证和压测确定瓶颈后逐项实施，不将本轮当作这些机制已完成。
