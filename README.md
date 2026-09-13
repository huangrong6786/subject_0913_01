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
- `POST/GET /api/survival-reports`
- `POST/GET /api/harvest-batches`，`GET /{id}`，`PUT /{id}/accept`，`DELETE /{id}`
- `GET /api/cages/{cageNo}/overview`：网箱维度聚合在养批次、已落账投饵量合计、各批次最新存活率、传感器最新读数、出网批次

时间参数使用 ISO-8601（如 `2026-09-13T07:00:00`）。

## 构建与运行

1. `mvn -q -DskipTests compile` 编译。
2. `mvn test` 运行集成测试（`AquacultureClosedLoopIntegrationTest` 覆盖完整闭环与全部保护规则，使用内存 H2）。
3. `mvn spring-boot:run` 启动，自动执行 `src/main/resources/schema.sql`；H2 文件写入 `data/evops`（已 gitignore）。

配置说明：鉴权由 Shiro 过滤器链 `authcBasic` 完成，`shiro.annotations.enabled=false` 关闭注解 AOP，避免 Shiro 的 JDK 动态代理创建器与 Spring 事务的 CGLIB 代理创建器互相二次包装导致按具体类注入失败。
