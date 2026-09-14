# EVOPS 基础工作区（深海网箱养殖批次与投饵监测）

在初始骨架上实现了深海网箱养殖批次与投饵监测基础闭环：维护鱼苗批次、投饵计划、水下传感器与读数、投饵记录、存活率报告、出网批次，记录网箱的投饵量、存活率和水下传感器读数，支持批次建立、状态流转、关联查询和统一 REST 返回。在此之上提供观测数据 CSV 批量导入：航次采集的投饵量、存活率与水下传感器读数按分片批量入库，文件校验和+业务键两级幂等，断网重传不重复，失败分片可重试，逐行隔离并返回明细。

技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro（HTTP Basic，账号 `bootstrap/bootstrap`）、Thymeleaf。

## 业务模型

| 表 | 关键唯一键 | 说明 |
| --- | --- | --- |
| `t_fish_batch` | `batch_no` | 鱼苗批次，状态 BREEDING→MONITORING→HARVESTED→CLOSED |
| `t_feeding_plan` | `plan_no` | 投饵计划，状态 ACTIVE/SUSPENDED/FINISHED |
| `t_underwater_sensor` | `sensor_no` | 水下传感器，状态 ONLINE/OFFLINE/MAINTENANCE |
| `t_sensor_reading` | `reading_no` | 传感器读数，按 `sensor_id`/`cage_no` 关联 |
| `t_feeding_record` | `record_no` | 实际投饵量，`posted=1` 已落账 |
| `t_survival_report` | `report_no` | 存活率报告，存活率缺省时按存活数/投苗数自动计算 |
| `t_harvest_batch` | `harvest_no` | 出网批次，PENDING→ACCEPTED |
| `t_task_lock` | `lock_name` | 批量任务互斥锁（单行/任务），带 owner_token 与租约到期时间 |
| `t_task_run` | `(task_name, period)` | 任务执行台账，同一业务周期唯一一行：RUNNING/SUCCESS/FAILED |
| `t_cage_observation` | `(voyage_no, cage_no, observed_at)` | 网箱观测数据（CSV 导入落地区），行级幂等键=航次号+网箱号+采样时刻 |
| `t_obs_import_batch` | `import_no`、`file_checksum` | 导入批次台账，文件 SHA-256 唯一（文件级幂等） |
| `t_obs_import_shard` | `(import_id, shard_index)` | 导入分片台账：PENDING/RUNNING/SUCCESS/PARTIAL/FAILED，失败分片可重试 |
| `t_obs_import_row` | — | 逐行处理明细，失败行保留原始行号、字段、原值与失败原因 |

## 投饵批量落账定时任务（防重复定时处理）

定时将未落账的投饵记录批量落账（`posted=1`），默认每天 02:00 处理当天业务周期；也可通过
`POST /api/feeding-records/post-task/trigger` 手工触发。定时与手工共用同一执行路径，返回统一 `ApiResponse`，
`data.status` 为 `SUCCESS`（本次执行成功）/`SKIPPED`（周期已成功或有执行中的任务）/`FAILED`（本次失败、已记录可重试）。

防重复处理由三层共同保证（即使其中一层失效也不会重复落账）：

1. **任务锁 `t_task_lock`（跨线程互斥）**：定时线程与手工请求竞争同一把锁；持锁方异常退出（JVM 崩溃/kill）
   来不及释放时，租约 `lease_expire_time` 到期后下一次触发可条件接管，锁不会永久阻塞；正常结束在 finally 中按 owner_token 释放。
2. **业务周期台账 `t_task_run`（跨触发幂等）**：`(task_name, period)` 唯一；`SUCCESS` 为成功终态，重复触发直接短路；
   `FAILED` 与僵死 `RUNNING`（started_at 早于租约阈值）可被接管重试，`attempts` 累加；活跃 `RUNNING` 跳过。
3. **落账动作原子 CAS（最终防线）**：`UPDATE ... SET posted=1 WHERE id=? AND posted=0`，逐条独立提交；
   批量中途失败后重试只处理剩余记录，已落账记录绝不会被处理第二次。

任务配置（`application.yml`，均可不改动）：

```yaml
evops.task.feeding-post:
  enabled: true            # 总开关；测试环境关闭真实调度
  cron: "0 0 2 * * *"      # Spring 6 段 cron（秒 分 时 日 月 周）
  lease-seconds: 300       # 租约时长：异常退出后超过该时长可被接管
```

**修复边界**：本方案保证单实例（当前 H2 本地文件库、单 JVM）内定时与手工并发、重复触发、失败重试、
异常退出恢复四类场景下“同一业务周期最多一次副作用”。多实例/集群部署需要把锁与台账升级为共享存储
（H2 AUTO_SERVER 仅提供文件级共享、不保证跨进程事务可见性语义），并引入时钟同步；
`lease-seconds` 必须大于单周期最坏处理时长，否则长事务有被误判僵死的风险（逐条 CAS 仍保证不重复落账，
但可能出现两个执行方先后成功）。

## 观测数据 CSV 批量导入（分片 + 两级幂等）

航次（船端）采集的网箱观测数据经 `POST /api/observations/import`  multipart 上传导入，
逐行联动生成投饵记录（未落账）、存活率报告（存活数按投苗数换算）与传感器读数，并落入观测表
`t_cage_observation`。单个坏传感器（不存在/离线/维护中/单位不符）只使引用它的行失败，不阻塞整船数据。

**CSV 格式**：UTF-8（容忍 BOM），首行固定表头（顺序敏感），支持引号包裹字段：

```
voyage_no,cage_no,observed_at,feed_amount_kg,survival_rate,sensor_value,sensor_unit,source_device
VOY-2026-001,CAGE-01,2026-09-10T08:00:00,12.50,0.9500,6.8,mg/L,SONDE-A1
```

| 列 | 校验规则 |
| --- | --- |
| `voyage_no` 航次号 | 必填；字母/数字/中划线/下划线，≤32 字符（幂等键之一） |
| `cage_no` 网箱号（对象编号） | 必填；同上（幂等键之一） |
| `observed_at` 观测时间（采样时刻） | 必填；ISO-8601，不得晚于当前时间，按秒归一（幂等键之一） |
| `feed_amount_kg` 投饵量 | 可空；>0 且 ≤99999.99，最多 2 位小数（单位 kg） |
| `survival_rate` 存活率 | 可空；0~1，最多 4 位小数 |
| `sensor_value` 传感器读数 | 可空；≥0，最多 4 位小数；存在时来源设备与单位必填 |
| `sensor_unit` 读数单位 | 有读数时必填，且必须与来源设备登记的 `metric_unit` 一致 |
| `source_device` 来源设备 | 有读数时必填；须已注册、归属本网箱、状态 ONLINE |

三类观测至少上报一项，否则整行拒绝。行数不受限：数据行按 `evops.import.shard-size`
（默认 1000 行/片）切片处理，50,000 行切 50 片；multipart 上限 50MB。

**两级幂等（航次断网重传同一文件不产生重复数据）**：

1. **文件级：`file_checksum`（SHA-256）唯一**。重复上传同一文件直接返回首次处理结果；
   存在失败/未完成分片时自动续传这些分片。可选请求参数 `checksum` 由调用方预计算，
   不一致说明传输损坏，直接拒绝（不产生批次）。
2. **行级：业务键（航次号+网箱号+采样时刻）唯一**。修正数据后重新上传（新校验和）逐行 upsert：
   内容一致 → `SKIPPED`；有变化且未锁定 → `UPDATED`（联动记录同步更新）；
   首次出现 → `SUCCESS`。联动记录编号由业务键派生（`FR-/SR-/RD-`+散列），单键一单。

**逐行隔离与明细**：每行一个独立事务（`REQUIRES_NEW`），单行失败只回滚本行、绝不回滚整批；
合法、重复、缺列、坏数值行混合时互不影响。每行结果（SUCCESS/UPDATED/SKIPPED/FAILED）落账
`t_obs_import_row`，失败行保留**原始行号、字段名、原始值与失败原因**（另存原始行内容），
经 `GET /api/observations/import/{importNo}/rows?outcome=FAILED` 查询；导入响应自带前 100 条失败明细。

**分片重试**：分片级异常（如存储闪断）只标记本片 `FAILED` 并继续后续分片；
`POST /api/observations/import/{importNo}/retry` 或重新上传同一文件可续传
`PENDING`/`FAILED`/僵死 `RUNNING`（超 `evops.import.stale-seconds`）分片，`attempts` 累加。
原始上传文件落盘 `evops.import.storage-dir`（默认 `./data/imports`）作为重试数据来源。
行级失败属数据问题，不经分片重试修复——修正后重新上传（新校验和）走行级 upsert。

**锁定/验收保护（不得覆盖）**：本行要改写的投饵记录已落账（`posted=1`，CAS 防并发落账）→ 整行拒绝；
网箱出网已验收或关联批次已终结（HARVESTED/CLOSED）→ 批次级数据（投饵量/存活率）拒绝覆盖；
纯传感器行的读数更新不受批次锁定影响。无在养批次的网箱不能写入投饵/存活率观测。

导入配置（`application.yml`，均可不改动）：

```yaml
evops.import:
  shard-size: 1000                 # 数据行数/片，50,000 行切 50 片
  storage-dir: ./data/imports      # 原始文件落盘目录（重试/续传数据来源）
  stale-seconds: 300               # 分片/批次僵死判定
  max-failures-in-response: 100    # 响应内失败明细上限（完整明细走行明细接口）
```

保护规则：

- 关键业务键（各编号）唯一，重复创建被拒绝；数据库层另有唯一约束兜底。
- **已落账**投饵记录不能删除、不能重复落账；存在已落账投饵记录的鱼苗批次不能删除。
- **已验收**出网批次不能删除、不能重复验收；存在已验收出网单的鱼苗批次不能删除。
- 验收出网批次联动鱼苗批次流转为 HARVESTED；状态流转必须符合枚举允许的路径。
- 维护中/离线传感器不能上报读数；已有读数的传感器不能删除。
- CSV 导入：已落账投饵记录对应的观测行拒绝覆盖；网箱出网已验收/批次已终结后批次级观测拒绝覆盖；
  同一文件（校验和一致）重复导入不产生重复数据。

## REST 接口（统一返回 `{success,message,data}`，需 Basic 认证，`/api/health` 除外）

- `POST/GET /api/fish-batches`，`GET /api/fish-batches/{id}`，`GET /no/{batchNo}`，`PUT /{id}/status`，`GET /{id}/detail`，`DELETE /{id}`
- `POST/GET /api/feeding-plans`，`GET /{id}`，`PUT /{id}/status`
- `POST/GET /api/sensors`，`GET /{id}`，`PUT /{id}/status`，`DELETE /{id}`
- `POST/GET /api/sensors/readings`（支持 `sensorId/cageNo/startTime/endTime` 过滤）
- `POST/GET /api/feeding-records`，`GET /{id}`，`PUT /{id}/post`，`DELETE /{id}`
- `POST /api/feeding-records/post-task/trigger`：手工触发当天投饵记录批量落账（与定时任务同路径）
- `POST/GET /api/survival-reports`
- `POST/GET /api/harvest-batches`，`GET /{id}`，`PUT /{id}/accept`，`DELETE /{id}`
- `GET /api/cages/{cageNo}/overview`：网箱维度聚合在养批次、已落账投饵量合计、各批次最新存活率、传感器最新读数、出网批次
- `POST /api/observations/import`：上传观测 CSV 并导入（multipart `file`，可选 `checksum`=SHA-256）；同一文件重复上传幂等返回首次结果
- `POST /api/observations/import/{importNo}/retry`：重试失败/未完成分片
- `GET /api/observations/import/{importNo}`：导入批次状态与汇总（含分片进度与前 100 条失败明细）
- `GET /api/observations/import/{importNo}/rows?outcome=FAILED&limit=&offset=`：逐行明细（行号/字段/原值/原因）
- `GET /api/observations`（支持 `voyageNo/cageNo` 过滤）

时间参数使用 ISO-8601（如 `2026-09-13T07:00:00`）。

## 构建与运行

1. `mvn -q -DskipTests compile` 编译。
2. `mvn test` 运行集成测试：`AquacultureClosedLoopIntegrationTest` 覆盖完整闭环与全部保护规则；
   `FeedingPostTaskConcurrencyRegressionTest` 覆盖定时与手工并发 5 轮、失败重试不重复、僵死锁租约接管等“重复定时处理”回归约束；
   `ObservationCsvImportIntegrationTest` 覆盖 CSV 导入混合行逐行隔离、两级幂等、已落账/已验收保护、校验和与解析边界；
   `ObsImportShardRetryTest` 覆盖分片失败重试与僵死分片接管；`ObsImportScaleShardingTest` 覆盖 50,000 行分片规模导入（均使用内存 H2）。
3. `mvn spring-boot:run` 启动，自动执行 `src/main/resources/schema.sql`；H2 文件写入 `data/evops`（已 gitignore）。

配置说明：鉴权由 Shiro 过滤器链 `authcBasic` 完成，`shiro.annotations.enabled=false` 关闭注解 AOP，避免 Shiro 的 JDK 动态代理创建器与 Spring 事务的 CGLIB 代理创建器互相二次包装导致按具体类注入失败。
