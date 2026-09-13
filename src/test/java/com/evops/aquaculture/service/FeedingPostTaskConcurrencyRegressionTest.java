package com.evops.aquaculture.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.aquaculture.dto.FeedingPostTaskResult;
import com.evops.aquaculture.entity.FeedingRecord;
import com.evops.aquaculture.entity.FishBatch;
import com.evops.aquaculture.entity.TaskLock;
import com.evops.aquaculture.entity.TaskRun;
import com.evops.aquaculture.enums.TaskTriggerSource;
import com.evops.aquaculture.mapper.FeedingRecordMapper;
import com.evops.aquaculture.mapper.FishBatchMapper;
import com.evops.aquaculture.mapper.TaskLockMapper;
import com.evops.aquaculture.mapper.TaskRunMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * “重复定时处理”缺陷的困难级回归约束：
 * 1. 定时任务与手工触发同时执行，连续 5 轮，同一业务周期最多产生一次副作用；
 * 2. 处理中途失败后重试，已落账记录不被重复处理；
 * 3. 执行方异常退出（未来得及释放锁）时，租约内跳过、租约过期后可接管，锁不会永久阻塞；
 * 4. 手工触发 REST 接口与定时入口共用同一执行路径，返回结构与既有接口一致；
 * 5. 定时入口受开关控制，且重复调度不会重复处理。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
// 与闭环测试类隔离出全新上下文/内存库，避免其他用例遗留的未落账记录干扰全表扫描计数
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class FeedingPostTaskConcurrencyRegressionTest {

    private static final String TASK = "FEEDING_POST";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private FeedingPostTaskService taskService;

    @Autowired
    private FeedingRecordMapper feedingRecordMapper;

    @Autowired
    private FishBatchMapper fishBatchMapper;

    @Autowired
    private TaskLockMapper taskLockMapper;

    @Autowired
    private TaskRunMapper taskRunMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long batchId;
    private long recordSeq;

    @BeforeEach
    void cleanTaskTables() {
        // 共享 Spring 上下文：每个用例从干净的锁/台账开始，投饵记录用唯一流水号前缀互不影响。
        jdbcTemplate.update("DELETE FROM t_task_run");
        jdbcTemplate.update("DELETE FROM t_task_lock");

        FishBatch batch = new FishBatch();
        batch.setBatchNo("FB-REG-" + System.nanoTime());
        batch.setCageNo("CAGE-REG-01");
        batch.setSpecies("大黄鱼");
        batch.setFingerlingCount(10000);
        batch.setStatus("MONITORING");
        batch.setStockingTime(LocalDateTime.now().minusDays(10));
        fishBatchMapper.insert(batch);
        batchId = batch.getId();
    }

    // ------------------------------------------------------------------
    // 1. 困难场景：定时 + 手工同时触发，重复 5 轮
    // ------------------------------------------------------------------

    @Test
    void scheduledAndManualConcurrent_fiveRounds_sideEffectOncePerPeriod() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 1; round <= 5; round++) {
                String period = String.format("2026-08-%02d", round);
                int recordsPerRound = 5;
                insertPendingRecords("FR-REG-CYC" + round, recordsPerRound);

                // 第一波：定时与手工严格同时刻进入同一周期
                CountDownLatch start = new CountDownLatch(1);
                List<Future<FeedingPostTaskResult>> futures = new ArrayList<>();
                futures.add(pool.submit(() -> {
                    start.await();
                    return taskService.runCycle(period, TaskTriggerSource.SCHEDULED, LocalDateTime.now());
                }));
                futures.add(pool.submit(() -> {
                    start.await();
                    return taskService.runCycle(period, TaskTriggerSource.MANUAL, LocalDateTime.now());
                }));
                start.countDown();

                int success = 0;
                int skipped = 0;
                for (Future<FeedingPostTaskResult> f : futures) {
                    FeedingPostTaskResult r = f.get(30, TimeUnit.SECONDS);
                    if ("SUCCESS".equals(r.getStatus())) {
                        success++;
                    } else if ("SKIPPED".equals(r.getStatus())) {
                        skipped++;
                    }
                }
                assertEquals(1, success, "第 " + round + " 轮：定时与手工同时执行，必须恰好一方成功");
                assertEquals(1, skipped, "第 " + round + " 轮：另一方必须被跳过");

                // 同一周期台账唯一一行：成功终态、只尝试一次、副作用计数恰为本轮记录数
                Map<String, Object> run = runRow(period);
                assertEquals("SUCCESS", run.get("STATUS"));
                assertEquals(1, ((Number) run.get("ATTEMPTS")).intValue());
                assertEquals(recordsPerRound, ((Number) run.get("POSTED_COUNT")).intValue());
                assertEquals(0, lockCount(), "正常结束后锁必须释放");

                // 第二波：同周期再次“定时+手工”同时触发，全部短路跳过，副作用不增加
                CountDownLatch start2 = new CountDownLatch(1);
                List<Future<FeedingPostTaskResult>> wave2 = new ArrayList<>();
                wave2.add(pool.submit(() -> {
                    start2.await();
                    return taskService.runCycle(period, TaskTriggerSource.SCHEDULED, LocalDateTime.now());
                }));
                wave2.add(pool.submit(() -> {
                    start2.await();
                    return taskService.runCycle(period, TaskTriggerSource.MANUAL, LocalDateTime.now());
                }));
                start2.countDown();
                for (Future<FeedingPostTaskResult> f : wave2) {
                    assertEquals("SKIPPED", f.get(30, TimeUnit.SECONDS).getStatus());
                }
                Map<String, Object> runAfter = runRow(period);
                assertEquals(1, ((Number) runAfter.get("ATTEMPTS")).intValue());
                assertEquals(recordsPerRound, ((Number) runAfter.get("POSTED_COUNT")).intValue());
            }

            // 5 轮共 25 条记录，全部且仅落账一次，成功台账恰好 5 行（每周期一行）
            assertEquals(25, postedCountForPrefix("FR-REG-CYC"));
            assertEquals(5, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM t_task_run WHERE task_name = ? AND status = 'SUCCESS'",
                    Integer.class, TASK));
            assertEquals(0, lockCount());
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // 2. 一方持锁处理中，另一方（手工）同时进入被锁挡住，确定互斥
    // ------------------------------------------------------------------

    @Test
    void concurrentAttemptWhileProcessing_isBlockedByLock() throws Exception {
        String period = "2026-08-10";
        insertPendingRecords("FR-REG-LOCK", 4);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FeedingPostTaskService blockingWorker = new FeedingPostTaskService(
                taskLockMapper, taskRunMapper, null) {
            @Override
            protected int doPostPending() {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // 由持锁线程直接用 Mapper 完成落账，模拟处理耗时
                LocalDateTime now = LocalDateTime.now();
                List<FeedingRecord> pending = feedingRecordMapper.selectList(
                        new LambdaQueryWrapper<FeedingRecord>()
                                .eq(FeedingRecord::getPosted, 0)
                                .likeRight(FeedingRecord::getRecordNo, "FR-REG-LOCK"));
                int posted = 0;
                for (FeedingRecord r : pending) {
                    posted += feedingRecordMapper.markPostedIfPending(r.getId(), now);
                }
                return posted;
            }
        };
        ReflectionTestUtils.setField(blockingWorker, "leaseSeconds", 300L);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<FeedingPostTaskResult> worker = pool.submit(
                    () -> blockingWorker.runCycle(period, TaskTriggerSource.SCHEDULED, LocalDateTime.now()));
            assertTrue(entered.await(10, TimeUnit.SECONDS), "持锁执行方应已进入落账处理");

            // 持锁处理期间手工触发同时进入：拿不到锁立即跳过，不产生重复副作用
            FeedingPostTaskResult manual =
                    taskService.runCycle(period, TaskTriggerSource.MANUAL, LocalDateTime.now());
            assertEquals("SKIPPED", manual.getStatus());
            assertEquals(0, postedCountForPrefix("FR-REG-LOCK"), "等待期间不应有任何额外落账");

            release.countDown();
            assertEquals("SUCCESS", worker.get(30, TimeUnit.SECONDS).getStatus());
            assertEquals(4, postedCountForPrefix("FR-REG-LOCK"));
            assertEquals(0, lockCount());
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // 3. 失败重试：中途异常仅部分落账，重试不重复处理已落账记录
    // ------------------------------------------------------------------

    @Test
    void failureThenRetryDoesNotReprocess_alreadyPostedRecords() throws Exception {
        String period = "2026-08-11";
        insertPendingRecords("FR-REG-RETRY", 6);

        AtomicInteger attempts = new AtomicInteger();
        FeedingPostTaskService flakyWorker = new FeedingPostTaskService(
                taskLockMapper, taskRunMapper, null) {
            @Override
            protected int doPostPending() {
                if (attempts.incrementAndGet() == 1) {
                    // 模拟批量处理中途异常退出：前 3 条已独立提交落账，后 3 条仍未落账
                    List<FeedingRecord> head = feedingRecordMapper.selectList(
                            new LambdaQueryWrapper<FeedingRecord>()
                                    .eq(FeedingRecord::getPosted, 0)
                                    .orderByAsc(FeedingRecord::getId)
                                    .last("LIMIT 3"));
                    LocalDateTime now = LocalDateTime.now();
                    for (FeedingRecord r : head) {
                        feedingRecordMapper.markPostedIfPending(r.getId(), now);
                    }
                    throw new IllegalStateException("模拟投饵落账中途异常");
                }
                // 第二次起：只处理剩余未落账记录（逐条 CAS，已落账的跳过）
                LocalDateTime now = LocalDateTime.now();
                int posted = 0;
                for (FeedingRecord r : feedingRecordMapper.selectList(new LambdaQueryWrapper<FeedingRecord>()
                        .eq(FeedingRecord::getPosted, 0)
                        .likeRight(FeedingRecord::getRecordNo, "FR-REG-RETRY"))) {
                    posted += feedingRecordMapper.markPostedIfPending(r.getId(), now);
                }
                return posted;
            }
        };
        ReflectionTestUtils.setField(flakyWorker, "leaseSeconds", 300L);

        // 第一次执行失败：3 条已落账，台账 FAILED，锁释放，可重试
        FeedingPostTaskResult failed =
                flakyWorker.runCycle(period, TaskTriggerSource.SCHEDULED, LocalDateTime.now());
        assertEquals("FAILED", failed.getStatus());
        assertEquals(3, postedCountForPrefix("FR-REG-RETRY"));
        assertEquals(0, lockCount(), "失败后锁必须释放");
        Map<String, Object> failedRun = runRow(period);
        assertEquals("FAILED", failedRun.get("STATUS"));
        assertEquals(1, ((Number) failedRun.get("ATTEMPTS")).intValue());

        // 固定首批 3 条已落账记录的落账时间（升序），作为“不被重复处理”的判定基线
        List<LocalDateTime> firstPostedTimes = postedRecordTimes("FR-REG-RETRY");
        assertEquals(3, firstPostedTimes.size());

        // 重试时“定时+手工”同时触发：恰好一方接管成功，另一方跳过；不重复落账
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<FeedingPostTaskResult>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> {
                start.await();
                return flakyWorker.runCycle(period, TaskTriggerSource.SCHEDULED, LocalDateTime.now());
            }));
            futures.add(pool.submit(() -> {
                start.await();
                return flakyWorker.runCycle(period, TaskTriggerSource.MANUAL, LocalDateTime.now());
            }));
            start.countDown();
            int success = 0;
            int skipped = 0;
            for (Future<FeedingPostTaskResult> f : futures) {
                FeedingPostTaskResult r = f.get(30, TimeUnit.SECONDS);
                if ("SUCCESS".equals(r.getStatus())) {
                    success++;
                } else if ("SKIPPED".equals(r.getStatus())) {
                    skipped++;
                }
            }
            assertEquals(1, success);
            assertEquals(1, skipped);
        } finally {
            pool.shutdownNow();
        }

        // 6 条全部落账；重试仅新增 3 条；首批 3 条落账时间保持不变 → 失败重试没有重复处理
        assertEquals(6, postedCountForPrefix("FR-REG-RETRY"));
        List<LocalDateTime> postedAfter = postedRecordTimes("FR-REG-RETRY");
        assertEquals(6, postedAfter.size());
        assertEquals(firstPostedTimes, postedAfter.subList(0, 3),
                "首轮已落账记录的落账时间不得被重试改写");
        Map<String, Object> finalRun = runRow(period);
        assertEquals("SUCCESS", finalRun.get("STATUS"));
        assertEquals(2, ((Number) finalRun.get("ATTEMPTS")).intValue(), "失败后接管重试，尝试次数累加为 2");
        assertEquals(3, ((Number) finalRun.get("POSTED_COUNT")).intValue(), "台账只累计重试本次新增的 3 条");
        assertEquals(0, lockCount());
    }

    // ------------------------------------------------------------------
    // 4. 异常退出恢复：租约未过期跳过；过期后接管，不永久阻塞
    // ------------------------------------------------------------------

    @Test
    void staleLockAndRunningRun_skippedWithinLease_takenOverAfterLeaseExpiry() {
        String period = "2026-08-12";
        insertPendingRecords("FR-REG-CRASH", 4);

        // 模拟执行方刚持锁进入处理即 JVM 崩溃：锁与 RUNNING 台账残留，租约未过期
        LocalDateTime now = LocalDateTime.now();
        insertLock("dead-owner-token", now.minusSeconds(10), now.plusSeconds(290));
        insertRun(period, "RUNNING", 1, now.minusSeconds(10));

        FeedingPostTaskResult withinLease =
                taskService.runCycle(period, TaskTriggerSource.MANUAL, now);
        assertEquals("SKIPPED", withinLease.getStatus());
        assertEquals(0, postedCountForPrefix("FR-REG-CRASH"), "租约内不得抢锁处理");
        assertEquals(1, lockCount(), "原锁保持不变");

        // 时间推进到租约过期之后（模拟崩溃后等待超过租约）
        jdbcTemplate.update("UPDATE t_task_lock SET lease_expire_time = ?", now.minusSeconds(1));
        jdbcTemplate.update("UPDATE t_task_run SET started_at = ?", now.minusSeconds(301));

        FeedingPostTaskResult recovered =
                taskService.runCycle(period, TaskTriggerSource.SCHEDULED, now.plusSeconds(301));
        assertEquals("SUCCESS", recovered.getStatus());
        assertEquals(4, postedCountForPrefix("FR-REG-CRASH"));
        Map<String, Object> run = runRow(period);
        assertEquals("SUCCESS", run.get("STATUS"));
        assertEquals(2, ((Number) run.get("ATTEMPTS")).intValue(), "僵死 RUNNING 被接管，尝试次数累加");
        assertEquals(4, ((Number) run.get("POSTED_COUNT")).intValue());
        assertEquals(0, lockCount(), "接管方正常结束后释放锁");
    }

    @Test
    void expiredLockLeftByCrash_doesNotBlockFuturePeriods() {
        // 崩溃残留一把已过期锁：新周期必须能接管，而不是永久阻塞
        LocalDateTime now = LocalDateTime.now();
        insertLock("dead-owner-token", now.minusSeconds(400), now.minusSeconds(100));
        insertPendingRecords("FR-REG-NEWPERIOD", 3);

        FeedingPostTaskResult r =
                taskService.runCycle("2026-08-13", TaskTriggerSource.SCHEDULED, now);
        assertEquals("SUCCESS", r.getStatus());
        assertEquals(3, postedCountForPrefix("FR-REG-NEWPERIOD"));
        assertEquals(0, lockCount());
    }

    // ------------------------------------------------------------------
    // 5. 手工触发 REST 接口：统一返回结构、Basic 认证、与定时路径幂等一致
    // ------------------------------------------------------------------

    @Test
    void manualTriggerEndpoint_unifiedResponse_authAndIdempotency() {
        insertPendingRecords("FR-REG-HTTP", 2);
        String url = "http://localhost:" + port + "/api/feeding-records/post-task/trigger";

        // 未认证被拒绝（与既有业务接口一致）
        assertEquals(HttpStatus.UNAUTHORIZED,
                restTemplate.postForEntity(url, null, JsonNode.class).getStatusCode());

        // 首次手工触发成功，返回统一 {success,message,data}
        ResponseEntity<JsonNode> first = restTemplate.withBasicAuth("bootstrap", "bootstrap")
                .postForEntity(url, null, JsonNode.class);
        assertEquals(HttpStatus.OK, first.getStatusCode());
        JsonNode body1 = first.getBody();
        assertTrue(body1.get("success").asBoolean());
        assertEquals(TASK, body1.get("data").get("taskName").asText());
        assertEquals("MANUAL", body1.get("data").get("triggerSource").asText());
        assertEquals("SUCCESS", body1.get("data").get("status").asText());
        assertEquals(2, body1.get("data").get("postedCount").asInt());
        assertNotNull(body1.get("data").get("period").asText());

        // 再次手工触发（重复点击/与定时撞车）：短路跳过，副作用不增加
        ResponseEntity<JsonNode> second = restTemplate.withBasicAuth("bootstrap", "bootstrap")
                .postForEntity(url, null, JsonNode.class);
        assertTrue(second.getBody().get("success").asBoolean());
        assertEquals("SKIPPED", second.getBody().get("data").get("status").asText());
        assertEquals(2, postedCountForPrefix("FR-REG-HTTP"));
    }

    // ------------------------------------------------------------------
    // 6. 定时入口与调度开关
    // ------------------------------------------------------------------

    @Test
    void scheduledTick_usesSamePath_andRespectsEnabledSwitch() {
        String today = LocalDateTime.now().toLocalDate().toString();

        // 开关关闭：定时触发不产生台账
        ReflectionTestUtils.setField(taskService, "enabled", false);
        taskService.scheduledTick();
        assertNull(taskRunMapper.selectOne(new LambdaQueryWrapper<TaskRun>()
                        .eq(TaskRun::getTaskName, TASK).eq(TaskRun::getPeriod, today)),
                "关闭开关后定时触发不应产生台账");

        // 打开开关：定时入口走与手工完全相同的执行路径，当天周期成功落账
        insertPendingRecords("FR-REG-TICK", 2);
        ReflectionTestUtils.setField(taskService, "enabled", true);
        taskService.scheduledTick();
        Map<String, Object> run = runRow(today);
        assertEquals("SUCCESS", run.get("STATUS"));
        assertEquals("SCHEDULED", run.get("TRIGGER_SOURCE"));
        assertEquals(2, postedCountForPrefix("FR-REG-TICK"));

        // 调度再次触发不重复处理
        taskService.scheduledTick();
        assertEquals(2, postedCountForPrefix("FR-REG-TICK"));
        ReflectionTestUtils.setField(taskService, "enabled", false);
    }

    // ------------------------------------------------------------------ helpers

    private void insertPendingRecords(String prefix, int count) {
        LocalDateTime base = LocalDateTime.now();
        for (int i = 0; i < count; i++) {
            FeedingRecord r = new FeedingRecord();
            r.setRecordNo(String.format("%s-%03d", prefix, ++recordSeq));
            r.setBatchId(batchId);
            r.setCageNo("CAGE-REG-01");
            r.setFeedType("配合饲料");
            r.setAmountKg(new BigDecimal("10.00"));
            r.setFeedingTime(base.plusMinutes(i));
            r.setPosted(0);
            feedingRecordMapper.insert(r);
        }
    }

    private int postedCountForPrefix(String prefix) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_feeding_record WHERE record_no LIKE ? AND posted = 1",
                Integer.class, prefix + "%");
        return n == null ? 0 : n;
    }

    private List<LocalDateTime> postedRecordTimes(String prefix) {
        return jdbcTemplate.query(
                "SELECT posted_time FROM t_feeding_record WHERE record_no LIKE ? AND posted = 1 ORDER BY id",
                new Object[]{prefix + "%"},
                (rs, rowNum) -> rs.getTimestamp("posted_time").toLocalDateTime());
    }

    private Map<String, Object> runRow(String period) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM t_task_run WHERE task_name = ? AND period = ?", TASK, period);
        // H2 列名默认大写，统一转换便于断言
        Map<String, Object> upper = new HashMap<>();
        row.forEach((k, v) -> upper.put(k.toUpperCase(), v));
        return upper;
    }

    private int lockCount() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_task_lock WHERE lock_name = ?", Integer.class, TASK);
        return n == null ? 0 : n;
    }

    private void insertLock(String token, LocalDateTime lockedAt, LocalDateTime expireAt) {
        TaskLock lock = new TaskLock();
        lock.setLockName(TASK);
        lock.setOwnerToken(token);
        lock.setLockedAt(lockedAt);
        lock.setLeaseExpireTime(expireAt);
        taskLockMapper.insert(lock);
    }

    private void insertRun(String period, String status, int attempts, LocalDateTime startedAt) {
        TaskRun run = new TaskRun();
        run.setTaskName(TASK);
        run.setPeriod(period);
        run.setStatus(status);
        run.setTriggerSource("SCHEDULED");
        run.setAttempts(attempts);
        run.setPostedCount(0);
        run.setStartedAt(startedAt);
        taskRunMapper.insert(run);
    }
}
