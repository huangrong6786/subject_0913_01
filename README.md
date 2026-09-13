# EVOPS 基础工作区（深海网箱养殖批次与投饵监测）

在初始骨架上实现了深海网箱养殖批次与投饵监测基础闭环：维护鱼苗批次、投饵计划、水下传感器与读数、投饵记录、存活率报告、出网批次，记录网箱的投饵量、存活率和水下传感器读数，支持批次建立、状态流转、关联查询和统一 REST 返回。

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

保护规则：

- 关键业务键（各编号）唯一，重复创建被拒绝；数据库层另有唯一约束兜底。
- **已落账**投饵记录不能删除、不能重复落账；存在已落账投饵记录的鱼苗批次不能删除。
- **已验收**出网批次不能删除、不能重复验收；存在已验收出网单的鱼苗批次不能删除。
- 验收出网批次联动鱼苗批次流转为 HARVESTED；状态流转必须符合枚举允许的路径。
- 维护中/离线传感器不能上报读数；已有读数的传感器不能删除。

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

时间参数使用 ISO-8601（如 `2026-09-13T07:00:00`）。

## 构建与运行

1. `mvn -q -DskipTests compile` 编译。
2. `mvn test` 运行集成测试：`AquacultureClosedLoopIntegrationTest` 覆盖完整闭环与全部保护规则；
   `FeedingPostTaskConcurrencyRegressionTest` 覆盖定时与手工并发 5 轮、失败重试不重复、僵死锁租约接管等“重复定时处理”回归约束（均使用内存 H2）。
3. `mvn spring-boot:run` 启动，自动执行 `src/main/resources/schema.sql`；H2 文件写入 `data/evops`（已 gitignore）。

配置说明：鉴权由 Shiro 过滤器链 `authcBasic` 完成，`shiro.annotations.enabled=false` 关闭注解 AOP，避免 Shiro 的 JDK 动态代理创建器与 Spring 事务的 CGLIB 代理创建器互相二次包装导致按具体类注入失败。
