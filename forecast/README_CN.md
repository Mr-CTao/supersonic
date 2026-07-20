# SuperSonic 出入库预测 MVP

本模块提供“客户任务数据接入 → 标准事件 → 日汇总 → Java 统计预测 → 看板”的最小闭环。
首版只预测未来 7、14、30 天的入库量、出库量和可选任务数，不包含补货、库位、波次、呆滞料、
Python、LLM、向量数据库或 MES/BOM 数据。

## 模块与进程

- `forecast-api`：公共请求、响应、标准事件模型以及 `ForecastConnector`、`ForecastAlgorithm` SPI。
- `forecast-core`：映射白名单、MySQL/PostgreSQL/SQL Server 方言、复合水位分页和统计选模。
- `forecast-server`：元数据库控制面、PostgreSQL 决策仓储、REST API、权限和 Worker 运行时。
- `launchers/forecast-worker`：无 HTTP Server 的独立 Spring Boot Worker。
- `launchers/standalone`：只承载配置、查询和看板 API，默认不执行扫描或预测任务。

Standalone 与 Worker 必须连接同一个 SuperSonic 元数据库。源库和决策库继续复用现有
`/database` 数据源记录，Forecast 控制表不保存或复制数据库密码。

## 数据库迁移

生产环境不会在应用启动时自动建表。部署顺序如下：

1. 停止 Forecast Worker。
2. 全新安装时，按元数据库类型执行一个 MVP 正向脚本（脚本直接创建
   `forecast_meta=2`）：
   - H2：`launchers/standalone/src/main/resources/config.update/sql-update-h2-20260718-forecast-mvp.sql`
   - MySQL：`launchers/standalone/src/main/resources/config.update/sql-update-mysql-20260718-forecast-mvp.sql`
   - PostgreSQL：`launchers/standalone/src/main/resources/config.update/sql-update-postgres-20260718-forecast-mvp.sql`
   已经运行 `forecast_meta=1` 的环境则只执行对应的
   `sql-update-*-20260720-forecast-activation-guard.sql`，不要重跑全量建表脚本。
3. 在每一个 Forecast 决策 PostgreSQL 中依次执行：
   - `forecast/server/src/main/resources/db/decision-postgres/V1__forecast_decision.sql`
   - `forecast/server/src/main/resources/db/decision-postgres/V2__forecast_publication.sql`
4. 启动 Standalone，再启动 Worker。Worker 会校验决策库最高 Schema 版本必须为 `2`。

Standalone 与 Worker 均只读校验元数据库 `forecast_meta=2` 版本标记，不会自动执行上述 DDL；
任一正向脚本未完整执行时，应用会拒绝进入就绪态。

回滚前必须停止 Worker 并备份数据。决策库先执行 `U2__forecast_publication.sql`，再执行
`U1__forecast_decision.sql`；元数据库执行对应 `sql-rollback-*` 脚本。`U1` 会删除全部 Forecast
事实、汇总、模型运行和预测结果，不能作为日常版本回退手段。

仅回退首次同步并发保护时，执行对应的
`sql-rollback-*-20260720-forecast-activation-guard.sql` 可把元数据库从版本 2 降回版本 1；该脚本
只删除并发键和索引，不删除历史任务。全量 `20260718-forecast-mvp` 回滚脚本仍会删除整个 Forecast
控制面，二者不可混用。

若使用非默认决策 Schema，发布前应在受控迁移流程中同步替换 SQL 脚本中的 `forecast`，并设置
`FORECAST_DECISION_SCHEMA`。Schema 名称只允许字母、数字和下划线。

## 构建和启动

```bash
# 独立 Worker fat jar（Java 21）
mvn -pl launchers/forecast-worker -am -DskipTests -Dspotless.skip=true package

S2_META_DB_DRIVER=org.postgresql.Driver \
S2_META_DB_URL='jdbc:postgresql://meta-host:5432/supersonic' \
S2_META_DB_USER='supersonic' \
S2_META_DB_PASSWORD='***' \
java -jar launchers/forecast-worker/target/launchers-forecast-worker-1.0.0-SNAPSHOT.jar
```

常用 Worker 环境变量及默认值：

| 环境变量 | 默认值 | 说明 |
| --- | ---: | --- |
| `FORECAST_WORKER_CONCURRENCY` | 2 | 单 Worker 全局任务并发 |
| `FORECAST_WORKER_BATCH_SIZE` | 5000 | 单页记录数，允许 500–20000 |
| `FORECAST_WORKER_POLL_SECONDS` | 5 | 队列轮询间隔 |
| `FORECAST_WORKER_HEARTBEAT_SECONDS` | 15 | 心跳间隔 |
| `FORECAST_WORKER_LEASE_SECONDS` | 90 | 任务和资源租约失效时间 |
| `FORECAST_WORKER_MAX_RETRIES` | 3 | 自动重试上限 |
| `FORECAST_STALE_AFTER_HOURS` | 6 | 健康接口数据过期阈值 |
| `FORECAST_DECISION_SCHEMA` | forecast | 决策库 Schema |

数据库租约还会强制同一源库并发为 1，并使同一 Profile 的同步、对账、首次激活和预测互斥。
Standalone 的 `forecast.worker.enabled` 固定默认关闭。

## 接入流程

1. 在 `/forecast/sources` 创建 Profile，选择 MySQL、PostgreSQL 或 SQL Server 源库，以及独立
   PostgreSQL 决策库。
2. 创建一个或多个数据流。
3. 创建映射草稿：可选单表/视图，或一次 `INNER/LEFT` 主表明细表等值关联；配置源记录 ID、
   数量、业务时间、仓库、方向和可选任务 ID、状态、删除标记、更新时间。
4. 使用最多 100 行只读预览校验，发布映射，再提交首次同步。
5. Worker 完成回填和整批预测后，才会同时切换决策库发布指针与元数据库活动版本；失败时旧版本
   继续服务。
6. 在 `/forecast/overview` 查看预测，在 `/forecast/runs` 查看水位、吞吐、错误、取消和重试。

`sourceUpdatedAt + sourceRecordId` 构成增量复合水位。没有更新时间时必须选择最近窗口重扫；没有
软删除字段时无法保证发现源库硬删除。多级关联、子查询、表达式和任意 SQL 不被接受，复杂客户表
应先创建标准视图。

## 预测规则

- 少于 14 个连续业务日：`INSUFFICIENT_DATA`，不伪造预测和区间。
- 14–27 日：7 日移动平均兜底，标记 `LOW_CONFIDENCE`。
- 28 日以上：在 7 日周期朴素、7/14/28 日移动平均、α=0.2/0.4/0.6/0.8 简单指数平滑之间滚动回测。
- 实际总量非零时按 WAPE 选模，为零时按 MAE；同时记录 Bias。
- 至少 14 个回测残差时，使用残差 P10/P90 生成经验 80% 区间。
- 每次生成未来 30 天，查询 API 只裁剪为 7、14 或 30 天。

## 测试

```bash
# Java 算法、映射、方言和 H2 迁移/CAS
mvn -pl forecast/core,launchers/standalone -am -Dspotless.skip=true \
  -Dtest='ForecastModelSelectorTest,ForecastMappingValidatorTest,ForecastRowTransformerTest,ForecastSqlDialectTest,ForecastH2MigrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test

# 有 Docker 时执行三种源库与 PostgreSQL 决策库 Testcontainers 测试
mvn -pl forecast/server -am -Dspotless.skip=true test

# 显式执行一百万行性能验收
RUN_FORECAST_PERFORMANCE_TESTS=true \
mvn -pl forecast/server -am -Dspotless.skip=true -Dtest=ForecastMillionRowPerformanceTest test

# 前端生产构建
cd webapp/packages/supersonic-fe && pnpm build
```

Testcontainers 用例在没有 Docker 时自动跳过，不会连接开发者本地数据库。性能用例会在测试输出中
记录总耗时、`readPage` 累计耗时和每秒行数。
