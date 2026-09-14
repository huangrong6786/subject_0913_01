package com.evops.aquaculture;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.aquaculture.dto.CageMigrationRequest;
import com.evops.aquaculture.entity.CageMigration;
import com.evops.aquaculture.entity.CageMigrationChain;
import com.evops.aquaculture.entity.FeedingPlan;
import com.evops.aquaculture.entity.FeedingRecord;
import com.evops.aquaculture.entity.FishBatch;
import com.evops.aquaculture.entity.HarvestBatch;
import com.evops.aquaculture.entity.SensorReading;
import com.evops.aquaculture.entity.SurvivalReport;
import com.evops.aquaculture.entity.UnderwaterSensor;
import com.evops.aquaculture.dto.CageMigrationHistoryView;
import com.evops.aquaculture.enums.HarvestStatus;
import com.evops.aquaculture.enums.PlanStatus;
import com.evops.aquaculture.enums.SensorStatus;
import com.evops.aquaculture.mapper.CageMigrationChainMapper;
import com.evops.aquaculture.mapper.CageMigrationMapper;
import com.evops.aquaculture.mapper.FeedingPlanMapper;
import com.evops.aquaculture.mapper.FeedingRecordMapper;
import com.evops.aquaculture.mapper.FishBatchMapper;
import com.evops.aquaculture.mapper.HarvestBatchMapper;
import com.evops.aquaculture.mapper.SensorReadingMapper;
import com.evops.aquaculture.mapper.SurvivalReportMapper;
import com.evops.aquaculture.mapper.UnderwaterSensorMapper;
import com.evops.aquaculture.service.CageMigrationService;
import com.evops.aquaculture.service.FeedingRecordService;
import com.evops.aquaculture.service.HarvestBatchService;
import com.evops.aquaculture.service.SensorReadingService;
import com.evops.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网箱替换/转移（换箱/转场）集成测试，覆盖本次迭代的全部约束：
 *  - 原对象结束值/新对象起始值/发生时间/操作者落事件，投饵量、存活率、传感器读数前后连续可核对；
 *  - 不可断裂时间链（序号连续、首尾相接、时间递增），历史链路查询与交接守恒校验；
 *  - 两个操作者并发提交仅一个版本成功（链头 seq CAS + 各对象 version CAS）；
 *  - 转场与未完成投饵计划状态变更并发仅一个版本生效；
 *  - 迁移前后状态、数量、关联事件同一事务变更；
 *  - 历史快照不可回写（应用层只增 + 数据库触发器兜底）；
 *  - 已锁定/已终结批次拒绝迁移，目标网箱占用/同箱校验；
 *  - REST 接口与操作者解析（请求体/请求头）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CageMigrationIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private CageMigrationService migrationService;
    @Autowired
    private FishBatchMapper fishBatchMapper;
    @Autowired
    private FeedingPlanMapper planMapper;
    @Autowired
    private FeedingRecordMapper recordMapper;
    @Autowired
    private SurvivalReportMapper survivalMapper;
    @Autowired
    private UnderwaterSensorMapper sensorMapper;
    @Autowired
    private SensorReadingMapper readingMapper;
    @Autowired
    private CageMigrationMapper migrationMapper;
    @Autowired
    private CageMigrationChainMapper chainMapper;
    @Autowired
    private HarvestBatchMapper harvestBatchMapper;
    @Autowired
    private FeedingRecordService feedingRecordService;
    @Autowired
    private SensorReadingService sensorReadingService;
    @Autowired
    private HarvestBatchService harvestBatchService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    private final long uniq = System.nanoTime();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // ---------------- 搭数辅助 ----------------

    private FishBatch newBatch(String batchNo, String cageNo, String status, int count) {
        FishBatch batch = new FishBatch();
        batch.setBatchNo(batchNo);
        batch.setCageNo(cageNo);
        batch.setSpecies("大黄鱼");
        batch.setFingerlingCount(count);
        batch.setAverageWeightG(new BigDecimal("10.00"));
        batch.setStatus(status);
        batch.setStockingTime(LocalDateTime.now().minusDays(30));
        fishBatchMapper.insert(batch);
        return batch;
    }

    private FeedingPlan newActivePlan(FishBatch batch, String planNo) {
        FeedingPlan plan = new FeedingPlan();
        plan.setPlanNo(planNo);
        plan.setBatchId(batch.getId());
        plan.setCageNo(batch.getCageNo());
        plan.setFeedType("配合饲料");
        plan.setDailyAmountKg(new BigDecimal("30.00"));
        plan.setFeedFrequency(2);
        plan.setStartDate(LocalDate.now().minusDays(10));
        plan.setEndDate(LocalDate.now().plusDays(50));
        plan.setStatus(PlanStatus.ACTIVE.name());
        planMapper.insert(plan);
        return plan;
    }

    private FeedingRecord newRecord(FishBatch batch, String no, String amount, int posted,
                                    LocalDateTime time) {
        FeedingRecord r = new FeedingRecord();
        r.setRecordNo(no);
        r.setBatchId(batch.getId());
        r.setCageNo(batch.getCageNo());
        r.setFeedType("配合饲料");
        r.setAmountKg(new BigDecimal(amount));
        r.setFeedingTime(time);
        r.setPosted(posted);
        if (posted == 1) {
            r.setPostedTime(time);
        }
        recordMapper.insert(r);
        return r;
    }

    private SurvivalReport newReport(FishBatch batch, String no, int alive, String rate,
                                     LocalDateTime time) {
        SurvivalReport report = new SurvivalReport();
        report.setReportNo(no);
        report.setBatchId(batch.getId());
        report.setCageNo(batch.getCageNo());
        report.setAliveCount(alive);
        report.setSurvivalRate(new BigDecimal(rate));
        report.setReportTime(time);
        survivalMapper.insert(report);
        return report;
    }

    private UnderwaterSensor newSensor(String no, String cageNo, String status) {
        UnderwaterSensor sensor = new UnderwaterSensor();
        sensor.setSensorNo(no);
        sensor.setCageNo(cageNo);
        sensor.setSensorType("DISSOLVED_OXYGEN");
        sensor.setMetricUnit("mg/L");
        sensor.setDepthM(new BigDecimal("5.00"));
        sensor.setInstallTime(LocalDateTime.now().minusDays(20));
        sensor.setStatus(status);
        sensorMapper.insert(sensor);
        return sensor;
    }

    private SensorReading newReading(UnderwaterSensor sensor, String no, String value,
                                     LocalDateTime time) {
        SensorReading reading = new SensorReading();
        reading.setReadingNo(no);
        reading.setSensorId(sensor.getId());
        reading.setCageNo(sensor.getCageNo());
        reading.setMetricValue(new BigDecimal(value));
        reading.setReadingTime(time);
        readingMapper.insert(reading);
        return reading;
    }

    private CageMigrationRequest migrationRequest(String no, Long batchId, String target,
                                                   long operatorId, String operatorName) {
        CageMigrationRequest req = new CageMigrationRequest();
        req.setMigrationNo(no);
        req.setBatchId(batchId);
        req.setTargetCageNo(target);
        req.setTransferType("REPLACE");
        req.setOperatorId(operatorId);
        req.setOperatorName(operatorName);
        return req;
    }

    private JsonNode restPost(String path, Object body, String operatorIdHeader,
                              String operatorNameHeader) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (operatorIdHeader != null) {
            headers.set("X-Operator-Id", operatorIdHeader);
            headers.set("X-Operator-Name", operatorNameHeader);
        }
        ResponseEntity<JsonNode> resp = restTemplate.withBasicAuth("bootstrap", "bootstrap")
                .exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
        return resp.getBody();
    }

    private JsonNode restGet(String path) {
        return restTemplate.withBasicAuth("bootstrap", "bootstrap")
                .getForEntity(url(path), JsonNode.class).getBody();
    }

    // ---------------- 1. 主流程：交接值连续、同事务迁移、快照与链路可查（走 REST） ----------------

    @Test
    void migrate_handoffValuesContinuous_chainAndSnapshotQueryable() {
        String p = "M1-" + uniq;
        FishBatch batch = newBatch("FB-" + p, "CAGE-" + p + "-A", "MONITORING", 10000);
        FeedingPlan plan = newActivePlan(batch, "FP-" + p);
        LocalDateTime t0 = LocalDateTime.now().minusDays(5);
        newRecord(batch, "FR-" + p + "-1", "100.00", 1, t0.minusDays(2));
        newRecord(batch, "FR-" + p + "-2", "20.00", 0, t0.minusDays(1));
        newReport(batch, "SR-" + p, 9500, "0.9500", t0.minusDays(1));
        UnderwaterSensor online = newSensor("S-ON-" + p, batch.getCageNo(), SensorStatus.ONLINE.name());
        newReading(online, "RD-" + p + "-1", "6.80", t0.minusHours(10));
        newReading(online, "RD-" + p + "-2", "7.10", t0.minusHours(2));
        UnderwaterSensor offline = newSensor("S-OFF-" + p, batch.getCageNo(), SensorStatus.OFFLINE.name());
        newReading(offline, "RD-" + p + "-3", "5.50", t0.minusDays(3));

        // REST 提交：操作者由请求头给出，请求体不带操作者字段。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("migrationNo", "MIG-" + p);
        body.put("batchId", batch.getId());
        body.put("targetCageNo", "CAGE-" + p + "-B");
        body.put("transferType", "REPLACE");
        JsonNode resp = restPost("/api/cage-migrations", body, "2002", "李四");

        assertTrue(resp.get("success").asBoolean(), resp::toString);
        JsonNode event = resp.get("data");
        long eventId = event.get("id").asLong();
        assertEquals(1, event.get("seqNo").asInt());
        assertEquals("CAGE-" + p + "-A", event.get("sourceCageNo").asText());
        assertEquals("CAGE-" + p + "-B", event.get("targetCageNo").asText());
        assertEquals("REPLACE", event.get("transferType").asText());
        assertEquals(2002, event.get("operatorId").asLong());
        assertEquals("李四", event.get("operatorName").asText());
        assertNotNull(event.get("occurredAt").asText());
        // 领域衔接：鱼群估算量取最新存活数 9500；累计已落账投饵量 100.00。
        assertEquals(9500, event.get("fishEstimateCount").asInt());
        assertEquals(0, new BigDecimal("100.00").compareTo(new BigDecimal(event.get("cumulativeFeedKg").asText())));
        assertEquals(0, new BigDecimal("0.9500").compareTo(new BigDecimal(event.get("latestSurvivalRate").asText())));
        assertEquals(1, event.get("feedingPlanCount").asInt());
        assertEquals(2, event.get("feedingRecordCount").asInt());
        assertEquals(1, event.get("sensorCount").asInt());

        // 同事务迁移：鱼群、未完成计划、ONLINE 传感器全部落位新箱并版本 +1。
        FishBatch movedBatch = fishBatchMapper.selectById(batch.getId());
        assertEquals("CAGE-" + p + "-B", movedBatch.getCageNo());
        assertEquals(1, movedBatch.getVersion());
        assertNotNull(movedBatch.getMigratedAt());
        assertEquals(eventId, movedBatch.getMigrationId());

        FeedingPlan movedPlan = planMapper.selectById(plan.getId());
        assertEquals("CAGE-" + p + "-B", movedPlan.getCageNo());
        assertEquals(1, movedPlan.getVersion());
        assertEquals(eventId, movedPlan.getMigrationId());

        UnderwaterSensor movedSensor = sensorMapper.selectById(online.getId());
        assertEquals("CAGE-" + p + "-B", movedSensor.getCageNo());
        assertEquals(1, movedSensor.getVersion());
        assertEquals(eventId, movedSensor.getMigrationId());
        // OFFLINE 设备留原箱。
        UnderwaterSensor stayed = sensorMapper.selectById(offline.getId());
        assertEquals("CAGE-" + p + "-A", stayed.getCageNo());
        assertEquals(0, stayed.getVersion());

        // 交接快照：原对象结束值与新对象起始值逐项一致，传感器读数连续。
        JsonNode snapshot = restGet("/api/cage-migrations/" + eventId + "/snapshot").get("data");
        JsonNode src = snapshot.get("source");
        JsonNode tgt = snapshot.get("target");
        assertEquals(src.get("fishEstimateCount").asInt(), tgt.get("fishEstimateCount").asInt());
        assertEquals(src.get("cumulativePostedFeedKg").asText(), tgt.get("cumulativePostedFeedKg").asText());
        assertEquals(src.get("cumulativeUnpostedFeedKg").asText(), tgt.get("cumulativeUnpostedFeedKg").asText());
        assertEquals(src.get("latestSurvivalRate").asText(), tgt.get("latestSurvivalRate").asText());
        assertEquals(src.get("feedingPlanCount").asInt(), tgt.get("feedingPlanCount").asInt());
        assertEquals(1, tgt.get("movedSensors").size());
        assertEquals("S-ON-" + p, tgt.get("movedSensors").get(0).get("sensorNo").asText());
        assertEquals(0, new BigDecimal("7.10").compareTo(
                new BigDecimal(tgt.get("movedSensors").get(0).get("latestValue").asText())));
        assertEquals(1, src.get("retainedSensors").size());
        assertEquals("S-OFF-" + p, src.get("retainedSensors").get(0).get("sensorNo").asText());
        assertEquals(0, new BigDecimal("5.50").compareTo(
                new BigDecimal(src.get("retainedSensors").get(0).get("latestValue").asText())));

        // 历史链路：链完整、交接守恒，按批次与新旧网箱均可查。
        JsonNode history = restGet("/api/cage-migrations/history/batch/" + batch.getId()).get("data");
        assertEquals("CAGE-" + p + "-A", history.get("originCageNo").asText());
        assertEquals("CAGE-" + p + "-B", history.get("currentCageNo").asText());
        assertEquals(1, history.get("eventCount").asInt());
        history.get("checks").forEach(c -> assertTrue(c.get("passed").asBoolean(),
                () -> c.get("name").asText() + ": " + c.get("detail").asText()));

        JsonNode bySource = restGet("/api/cage-migrations/history/cage/CAGE-" + p + "-A").get("data");
        JsonNode byTarget = restGet("/api/cage-migrations/history/cage/CAGE-" + p + "-B").get("data");
        assertTrue(bySource.size() >= 1 && byTarget.size() >= 1);

        // 列表/单号查询。
        JsonNode list = restGet("/api/cage-migrations?batchId=" + batch.getId()).get("data");
        assertEquals(1, list.size());
        JsonNode byNo = restGet("/api/cage-migrations/no/MIG-" + p).get("data");
        assertEquals(eventId, byNo.get("id").asLong());
    }

    // ---------------- 2. 第二跳：链不断裂、值承接、投饵/读数沿时间轴分箱 ----------------

    @Test
    void secondMigration_chainUnbroken_valuesCarryForwardAndTimelineSplit() {
        String p = "M2-" + uniq;
        FishBatch batch = newBatch("FB-" + p, "CAGE-" + p + "-A", "MONITORING", 8000);
        FeedingPlan plan = newActivePlan(batch, "FP-" + p);
        LocalDateTime base = LocalDateTime.now().minusDays(8);
        FeedingRecord oldRecord = newRecord(batch, "FR-" + p + "-OLD", "100.00", 1, base);
        newReport(batch, "SR-" + p, 7600, "0.9500", base.minusDays(1));
        UnderwaterSensor sensor = newSensor("S-" + p, batch.getCageNo(), SensorStatus.ONLINE.name());
        SensorReading oldReading = newReading(sensor, "RD-" + p + "-OLD", "6.50", base.minusHours(3));

        CageMigration first = migrationService.migrate(
                migrationRequest("MIG-" + p + "-1", batch.getId(), "CAGE-" + p + "-B", 1, "甲"));

        // 迁移后在新箱发生的投饵（落账）与读数：自动锚定第一跳事件、落新箱。
        com.evops.aquaculture.dto.FeedingRecordCreateRequest frReq =
                new com.evops.aquaculture.dto.FeedingRecordCreateRequest();
        frReq.setRecordNo("FR-" + p + "-NEW");
        frReq.setBatchId(batch.getId());
        frReq.setPlanId(plan.getId());
        frReq.setFeedType("配合饲料");
        frReq.setAmountKg(new BigDecimal("50.00"));
        frReq.setFeedingTime(LocalDateTime.now().minusDays(1));
        FeedingRecord newRecord = feedingRecordService.create(frReq);
        feedingRecordService.post(newRecord.getId());

        com.evops.aquaculture.dto.ReadingCreateRequest rdReq =
                new com.evops.aquaculture.dto.ReadingCreateRequest();
        rdReq.setReadingNo("RD-" + p + "-NEW");
        rdReq.setSensorId(sensor.getId());
        rdReq.setMetricValue(new BigDecimal("7.20"));
        rdReq.setReadingTime(LocalDateTime.now().minusHours(20));
        SensorReading newReading = sensorReadingService.report(rdReq);

        // 第二跳 B -> C。
        CageMigration second = migrationService.migrate(
                migrationRequest("MIG-" + p + "-2", batch.getId(), "CAGE-" + p + "-C", 2, "乙"));
        assertEquals(2, second.getSeqNo());
        // 累计投饵量衔接：100 + 50 = 150，沿链单调不减。
        assertEquals(0, new BigDecimal("150.00").compareTo(second.getCumulativeFeedKg()));
        assertEquals(7600, second.getFishEstimateCount());

        CageMigrationHistoryView history = migrationService.historyByBatch(batch.getId());
        assertEquals(2, history.getEventCount());
        assertEquals("CAGE-" + p + "-C", history.getCurrentCageNo());
        history.getChecks().forEach(c -> assertTrue(c.isPassed(),
                () -> c.getName() + ": " + c.getDetail()));
        // 事件首尾相接。
        assertEquals("CAGE-" + p + "-B", history.getEvents().get(0).getTargetCageNo());
        assertEquals("CAGE-" + p + "-B", history.getEvents().get(1).getSourceCageNo());

        // 流水时间轴切分：迁移前记录/读数留原箱、不锚事件；新发生的落新箱并锚定迁移段。
        FeedingRecord oldRow = recordMapper.selectById(oldRecord.getId());
        assertEquals("CAGE-" + p + "-A", oldRow.getCageNo());
        assertNull(oldRow.getMigrationId());
        FeedingRecord newRow = recordMapper.selectById(newRecord.getId());
        // 投饵流水保留其发生时所在网箱 B（迁移不改写历史流水），锚定第一跳迁移段；
        // 批次口径累计投饵量仍连续（100+50=150，已在第二跳事件中核对）。
        assertEquals("CAGE-" + p + "-B", newRow.getCageNo());
        assertEquals(first.getId(), newRow.getMigrationId());

        SensorReading oldRd = readingMapper.selectById(oldReading.getId());
        assertEquals("CAGE-" + p + "-A", oldRd.getCageNo());
        assertNull(oldRd.getMigrationId());
        SensorReading newRd = readingMapper.selectById(newReading.getId());
        // 第二跳把设备带到 C；第二跳前在 B 的读数其 cage_no 已随第一跳为 B，保留不改。
        assertEquals("CAGE-" + p + "-B", newRd.getCageNo());
        assertEquals(first.getId(), newRd.getMigrationId());

        // 第二跳快照的传感器最新读数承接 7.20（读数连续可核对）。
        JsonNode snap2 = migrationService.snapshotTree(second.getId());
        assertEquals(0, new BigDecimal("7.20").compareTo(new BigDecimal(
                snap2.get("source").get("movedSensors").get(0).get("latestValue").asText())));
        assertEquals(0, new BigDecimal("7.20").compareTo(new BigDecimal(
                snap2.get("target").get("movedSensors").get(0).get("latestValue").asText())));
    }

    // ---------------- 3. 困难级：两个操作者并发提交仅一个版本成功 ----------------

    @Test
    void concurrentSubmissions_onlyOneVersionSucceeds() throws Exception {
        String p = "M3-" + uniq;
        FishBatch batch = newBatch("FB-" + p, "CAGE-" + p + "-A", "MONITORING", 5000);
        newActivePlan(batch, "FP-" + p);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> loser = new AtomicReference<>();
        // 两个操作者在同一时刻基于同一旧版本（批次 0、链 0）决策并提交。
        CageMigrationRequest req1 = migrationRequest(
                "MIG-" + p + "-X", batch.getId(), "CAGE-" + p + "-B", 11, "操作者11");
        req1.setExpectedBatchVersion(0);
        req1.setExpectedChainSeq(0);
        CageMigrationRequest req2 = migrationRequest(
                "MIG-" + p + "-Y", batch.getId(), "CAGE-" + p + "-C", 22, "操作者22");
        req2.setExpectedBatchVersion(0);
        req2.setExpectedChainSeq(0);
        try {
            Future<CageMigration> f1 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return migrationService.migrate(req1);
            });
            Future<CageMigration> f2 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return migrationService.migrate(req2);
            });

            CageMigration winner;
            int success = 0;
            try {
                winner = f1.get(10, TimeUnit.SECONDS);
                success++;
            } catch (Exception ex) {
                winner = null;
                loser.set(rootCause(ex));
            }
            try {
                CageMigration other = f2.get(10, TimeUnit.SECONDS);
                success++;
                if (winner == null) {
                    winner = other;
                }
            } catch (Exception ex) {
                if (loser.get() == null) {
                    loser.set(rootCause(ex));
                }
            }

            assertEquals(1, success, "并发提交必须仅一个版本成功");
            assertNotNull(winner);
            assertNotNull(loser.get());
            assertTrue(loser.get() instanceof BusinessException,
                    "落败方应为乐观锁版本冲突的业务异常，实际: " + loser.get());

            // 链与对象状态自洽：仅一条事件、链头版本 1、批次只落一个网箱、版本只 +1。
            CageMigrationChain chain = chainMapper.selectOne(new LambdaQueryWrapper<CageMigrationChain>()
                    .eq(CageMigrationChain::getBatchId, batch.getId()));
            assertEquals(1, chain.getCurrentSeq());
            Long eventCount = migrationMapper.selectCount(new LambdaQueryWrapper<CageMigration>()
                    .eq(CageMigration::getBatchId, batch.getId()));
            assertEquals(1L, eventCount);
            FishBatch after = fishBatchMapper.selectById(batch.getId());
            assertEquals(chain.getCurrentCageNo(), after.getCageNo());
            assertEquals(1, after.getVersion());
            // 落败方不得留下半成品迁移事件（同事务回滚）。
            String loserNo = winner.getMigrationNo().equals("MIG-" + p + "-Y")
                    ? "MIG-" + p + "-X" : "MIG-" + p + "-Y";
            assertEquals(0L, migrationMapper.selectCount(new LambdaQueryWrapper<CageMigration>()
                    .eq(CageMigration::getMigrationNo, loserNo)));
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------- 4. 困难级：转场与未完成投饵计划并发，仅一个版本生效 ----------------

    @Test
    void migrationConcurrentWithPlanStatusChange_onlyOneVersionTakesEffect() throws Exception {
        String p = "M4-" + uniq;
        FishBatch batch = newBatch("FB-" + p, "CAGE-" + p + "-A", "MONITORING", 6000);
        FeedingPlan plan = newActivePlan(batch, "FP-" + p);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> migrateError = new AtomicReference<>();
        AtomicReference<Throwable> statusError = new AtomicReference<>();
        // 双方基于同一鱼群版本 0 决策并在同一时刻提交（迁移链版本同为 0）。
        CageMigrationRequest migrationReq = migrationRequest(
                "MIG-" + p, batch.getId(), "CAGE-" + p + "-B", 1, "甲");
        migrationReq.setExpectedBatchVersion(0);
        migrationReq.setExpectedChainSeq(0);
        com.evops.aquaculture.dto.PlanStatusRequest finishReq =
                new com.evops.aquaculture.dto.PlanStatusRequest();
        finishReq.setTargetStatus(PlanStatus.FINISHED.name());
        finishReq.setExpectedBatchVersion(0);
        try {
            Future<?> migrationFuture = pool.submit(() -> {
                awaitQuiet(barrier);
                migrationService.migrate(migrationReq);
            });
            Future<?> statusFuture = pool.submit(() -> {
                awaitQuiet(barrier);
                applicationContext.getBean(com.evops.aquaculture.service.FeedingPlanService.class)
                        .transitStatus(plan.getId(), finishReq);
            });

            try {
                migrationFuture.get(10, TimeUnit.SECONDS);
            } catch (Exception ex) {
                migrateError.set(rootCause(ex));
            }
            try {
                statusFuture.get(10, TimeUnit.SECONDS);
            } catch (Exception ex) {
                statusError.set(rootCause(ex));
            }

            boolean migrationWon = migrateError.get() == null;
            boolean statusWon = statusError.get() == null;
            assertTrue(migrationWon ^ statusWon, "迁移与计划状态变更必须恰好一方成功");

            FeedingPlan after = planMapper.selectById(plan.getId());
            if (migrationWon) {
                // 迁移赢：计划随群到新箱且仍 ACTIVE，状态变更落败。
                assertEquals("CAGE-" + p + "-B", after.getCageNo());
                assertEquals(PlanStatus.ACTIVE.name(), after.getStatus());
                assertEquals(1, after.getVersion());
                assertTrue(statusError.get() instanceof BusinessException);
            } else {
                // 状态变更赢：计划 FINISHED 留原箱；迁移整体回滚，无事件、批次未动。
                assertEquals(PlanStatus.FINISHED.name(), after.getStatus());
                assertEquals("CAGE-" + p + "-A", after.getCageNo());
                assertEquals(1, after.getVersion());
                assertTrue(migrateError.get() instanceof BusinessException);
                assertEquals("CAGE-" + p + "-A", fishBatchMapper.selectById(batch.getId()).getCageNo());
                assertEquals(0L, migrationMapper.selectCount(new LambdaQueryWrapper<CageMigration>()
                        .eq(CageMigration::getMigrationNo, "MIG-" + p)));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------- 5. 已锁定/已终结拒绝迁移 ----------------

    @Test
    void terminalAndAcceptedBatch_migrationRejected() {
        String p = "M5-" + uniq;
        FishBatch harvested = newBatch("FB-" + p + "-H", "CAGE-" + p + "-H", "HARVESTED", 1000);
        BusinessException ex1 = assertThrows(BusinessException.class, () -> migrationService.migrate(
                migrationRequest("MIG-" + p + "-H", harvested.getId(), "CAGE-" + p + "-X", 1, "甲")));
        assertTrue(ex1.getMessage().contains("终结") || ex1.getMessage().contains("验收"));

        // 已验收出网：批次联动 HARVESTED，迁移同样拒绝（验收锁定期间不能改写）。
        FishBatch batch = newBatch("FB-" + p + "-A", "CAGE-" + p + "-A", "MONITORING", 2000);
        HarvestBatch harvest = new HarvestBatch();
        harvest.setHarvestNo("HB-" + p);
        harvest.setFishBatchId(batch.getId());
        harvest.setCageNo(batch.getCageNo());
        harvest.setHarvestCount(1900);
        harvest.setTotalWeightKg(new BigDecimal("950.00"));
        harvest.setHarvestTime(LocalDateTime.now().minusDays(1));
        harvest.setStatus(HarvestStatus.PENDING.name());
        harvestBatchMapper.insert(harvest);
        harvestBatchService.accept(harvest.getId());
        assertEquals("HARVESTED", fishBatchMapper.selectById(batch.getId()).getStatus());

        BusinessException ex2 = assertThrows(BusinessException.class, () -> migrationService.migrate(
                migrationRequest("MIG-" + p + "-A", batch.getId(), "CAGE-" + p + "-Y", 1, "甲")));
        assertTrue(ex2.getMessage().contains("终结") || ex2.getMessage().contains("验收"));
    }

    // ---------------- 6. 目标网箱占用 / 同箱 / 唯一单号 ----------------

    @Test
    void sameCageOccupiedTargetAndDuplicateNo_rejected() {
        String p = "M6-" + uniq;
        FishBatch batch = newBatch("FB-" + p + "-1", "CAGE-" + p + "-A", "MONITORING", 1000);
        // 同箱迁移。
        BusinessException same = assertThrows(BusinessException.class, () -> migrationService.migrate(
                migrationRequest("MIG-" + p + "-S", batch.getId(), "CAGE-" + p + "-A", 1, "甲")));
        assertTrue(same.getMessage().contains("相同"));

        // 目标网箱有在养批次。
        newBatch("FB-" + p + "-2", "CAGE-" + p + "-B", "BREEDING", 2000);
        BusinessException occupied = assertThrows(BusinessException.class, () -> migrationService.migrate(
                migrationRequest("MIG-" + p + "-O", batch.getId(), "CAGE-" + p + "-B", 1, "甲")));
        assertTrue(occupied.getMessage().contains("在养"));

        // 正常迁移一次，重复单号拒绝。
        CageMigration event = migrationService.migrate(
                migrationRequest("MIG-" + p + "-DUP", batch.getId(), "CAGE-" + p + "-C", 1, "甲"));
        BusinessException dupNo = assertThrows(BusinessException.class, () -> migrationService.migrate(
                migrationRequest("MIG-" + p + "-DUP", batch.getId(), "CAGE-" + p + "-D", 2, "乙")));
        assertTrue(dupNo.getMessage().contains("迁移单号已存在"));
        // 重复单号不得产生第二条事件。
        assertEquals(1L, migrationMapper.selectCount(new LambdaQueryWrapper<CageMigration>()
                .eq(CageMigration::getMigrationNo, "MIG-" + p + "-DUP")));
        assertEquals(event.getId(), migrationMapper.selectOne(new LambdaQueryWrapper<CageMigration>()
                .eq(CageMigration::getBatchId, batch.getId())
                .orderByDesc(CageMigration::getId)).getId());
    }

    // ---------------- 7. 历史快照不可回写（数据库触发器兜底） ----------------

    @Test
    void historySnapshot_isImmutable_atDatabaseLayer() {
        String p = "M7-" + uniq;
        FishBatch batch = newBatch("FB-" + p, "CAGE-" + p + "-A", "MONITORING", 3000);
        CageMigration event = migrationService.migrate(
                migrationRequest("MIG-" + p, batch.getId(), "CAGE-" + p + "-B", 1, "甲"));

        assertThrows(Exception.class, () -> jdbcTemplate.update(
                "UPDATE t_cage_migration SET remark = 'hacked' WHERE id = ?", event.getId()));
        assertThrows(Exception.class, () -> jdbcTemplate.update(
                "DELETE FROM t_cage_migration WHERE id = ?", event.getId()));

        CageMigration reloaded = migrationMapper.selectById(event.getId());
        assertNotNull(reloaded);
        assertNull(reloaded.getRemark());
        assertEquals("CAGE-" + p + "-B", reloaded.getTargetCageNo());
    }

    // ---------------- 8. 操作者缺省取登录账号；鉴权 ----------------

    @Test
    void operatorDefaultsToAuthenticatedPrincipal_andRequiresAuth() {
        String p = "M8-" + uniq;
        FishBatch batch = newBatch("FB-" + p, "CAGE-" + p + "-A", "MONITORING", 1000);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("migrationNo", "MIG-" + p);
        body.put("batchId", batch.getId());
        body.put("targetCageNo", "CAGE-" + p + "-B");
        // 不带 operatorId/operatorName 与操作者头：回落为 Basic 登录账号 bootstrap。
        JsonNode resp = restPost("/api/cage-migrations", body, null, null);
        assertTrue(resp.get("success").asBoolean(), resp::toString);
        assertEquals("bootstrap", resp.get("data").get("operatorName").asText());
        assertTrue(resp.get("data").get("operatorId").asLong() > 0);

        org.springframework.http.ResponseEntity<JsonNode> unauth = restTemplate.exchange(
                url("/api/cage-migrations/history/batch/" + batch.getId()),
                HttpMethod.GET, HttpEntity.EMPTY, JsonNode.class);
        assertEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, unauth.getStatusCode());
    }

    // ---------------- 辅助 ----------------

    private static void awaitQuiet(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException | BrokenBarrierException |
                 java.util.concurrent.TimeoutException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Throwable rootCause(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
