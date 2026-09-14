package com.evops.aquaculture.service;

import com.evops.aquaculture.dto.TsCalcResultView;
import com.evops.aquaculture.dto.TsRuleCreateRequest;
import com.evops.aquaculture.dto.TsRuleSnapshot;
import com.evops.aquaculture.dto.TsRuleUpdateRequest;
import com.evops.aquaculture.entity.CageSeaArea;
import com.evops.aquaculture.entity.FeedObservation;
import com.evops.aquaculture.entity.FishBatch;
import com.evops.aquaculture.entity.TideSession;
import com.evops.aquaculture.entity.TsCalcDetail;
import com.evops.aquaculture.entity.TsRule;
import com.evops.aquaculture.mapper.FishBatchMapper;
import com.evops.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 深海网箱养殖批次与投饵监测时序规则计算 —— 困难级约束集成回归：
 * 1. 峰值/平段/谷值规则的版本化维护（≥4 个版本化规则），启用后不能原地修改；
 * 2. 规则区间重叠必须拒绝（含跨午夜环形区间，左闭右开）；
 * 3. 业务时区跨日：跨午夜潮次按网箱所在海区时区归属业务日；
 * 4. 潮汐与规则区间均为左闭右开；
 * 5. 累计计算 BigDecimal 精确运算，最终步骤统一舍入（HALF_UP）；
 * 6. 历史结果保留采用的规则版本快照与潮次快照；
 * 7. 并发重算不会产生两份结果。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class TsRuleCalcIntegrationTest {

    @Autowired
    private TsRuleService tsRuleService;
    @Autowired
    private CageSeaAreaService cageSeaAreaService;
    @Autowired
    private TideSessionService tideSessionService;
    @Autowired
    private FeedObservationService feedObservationService;
    @Autowired
    private TsCalcService tsCalcService;
    @Autowired
    private FishBatchMapper fishBatchMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long seq;

    @BeforeEach
    void cleanTsTables() {
        jdbcTemplate.update("DELETE FROM t_ts_calc_detail");
        jdbcTemplate.update("DELETE FROM t_ts_calc_result");
        jdbcTemplate.update("DELETE FROM t_feed_observation");
        jdbcTemplate.update("DELETE FROM t_tide_session");
        jdbcTemplate.update("DELETE FROM t_ts_rule");
        jdbcTemplate.update("DELETE FROM t_cage_sea_area");
    }

    // ------------------------------------------------------------------
    // 1. 规则版本化：≥4 个版本化规则、启用后不可原地修改、重叠拒绝
    // ------------------------------------------------------------------

    @Test
    void ruleLifecycle_versionedImmutableAndOverlapRejected() {
        // 峰值/平段/谷值 + 跨午夜峰值：4 个版本化规则
        TsRule peak = createEnabledRule("PEAK-DAY", "PEAK", 360, 600, "1.5000");
        createEnabledRule("FLAT-DAY", "FLAT", 600, 1080, "1.0000");
        createEnabledRule("VALLEY-EVE", "VALLEY", 1080, 1320, "0.8000");
        createEnabledRule("PEAK-NIGHT", "PEAK", 1320, 120, "1.2000");
        assertEquals(4, tsRuleService.listEnabled().size());
        assertEquals(1, peak.getVersionNo());

        // 启用后不能原地修改、不能删除
        assertThrows(BusinessException.class, () -> tsRuleService.update(peak.getId(),
                updateRequest("PEAK", 360, 660, "1.6000")), "启用版本原地修改必须拒绝");
        assertThrows(BusinessException.class, () -> tsRuleService.delete(peak.getId()),
                "启用版本删除必须拒绝");

        // 调整须新建版本：v2 草稿可改，启用后 v1 自动退役（同区间调系数，不影响区间拼接）
        TsRule peakV2 = tsRuleService.create(createRequest("PEAK-DAY", "PEAK", 360, 600, "1.6000"));
        assertEquals(2, peakV2.getVersionNo());
        assertEquals("DRAFT", peakV2.getStatus());
        tsRuleService.update(peakV2.getId(), updateRequest("PEAK", 360, 600, "1.6000"));
        tsRuleService.enable(peakV2.getId());
        assertEquals("RETIRED", tsRuleService.getById(peak.getId()).getStatus());
        assertEquals("ENABLED", tsRuleService.getById(peakV2.getId()).getStatus());

        // 继续演进到 v3、v4：同一编码保留全部历史版本，仅最新版启用
        TsRule peakV3 = tsRuleService.create(createRequest("PEAK-DAY", "PEAK", 360, 600, "1.7000"));
        tsRuleService.enable(peakV3.getId());
        TsRule peakV4 = tsRuleService.create(createRequest("PEAK-DAY", "PEAK", 360, 600, "1.8000"));
        tsRuleService.enable(peakV4.getId());
        List<TsRule> versions = tsRuleService.list(null, "PEAK-DAY");
        assertEquals(4, versions.size(), "PEAK-DAY 应保留 4 个版本");
        assertEquals("RETIRED", versions.get(0).getStatus());
        assertEquals("RETIRED", versions.get(1).getStatus());
        assertEquals("RETIRED", versions.get(2).getStatus());
        assertEquals("ENABLED", versions.get(3).getStatus());
        assertEquals(4, versions.get(3).getVersionNo());

        // 退役版本同样冻结：不可改、不可重新启用
        assertThrows(BusinessException.class, () -> tsRuleService.update(peakV3.getId(),
                updateRequest("PEAK", 360, 700, "1.9000")));
        assertThrows(BusinessException.class, () -> tsRuleService.enable(peakV3.getId()));

        // 区间重叠必须拒绝：与在启 FLAT-DAY [10:00,18:00) 重叠
        TsRule overlap = tsRuleService.create(createRequest("X-OVERLAP", "FLAT", 500, 700, "1.0000"));
        BusinessException ex = assertThrows(BusinessException.class, () -> tsRuleService.enable(overlap.getId()));
        assertTrue(ex.getMessage().contains("重叠"), "重叠拒绝信息应说明原因: " + ex.getMessage());

        // 跨午夜环形区间重叠也必须拒绝：[21:40,02:10) 与在启 PEAK-NIGHT [22:00,02:00) 重叠
        TsRule overlapNight = tsRuleService.create(createRequest("X-NIGHT", "VALLEY", 1300, 130, "1.0000"));
        assertThrows(BusinessException.class, () -> tsRuleService.enable(overlapNight.getId()));

        // 左闭右开相邻不算重叠：[02:00,06:00) 与 [22:00,02:00)、[06:00,10:00) 均相邻，允许启用
        TsRule gap = tsRuleService.create(createRequest("GAP-EARLY", "VALLEY", 120, 360, "0.5000"));
        tsRuleService.enable(gap.getId());
        assertEquals("ENABLED", tsRuleService.getById(gap.getId()).getStatus());

        // 草稿可删除；非法区间与非法类型在创建即拒绝
        TsRule draft = tsRuleService.create(createRequest("X-DRAFT", "FLAT", 700, 800, "1.0000"));
        tsRuleService.delete(draft.getId());
        assertThrows(BusinessException.class, () -> tsRuleService.create(createRequest("X-BAD", "FLAT", 600, 600, "1.0")));
        assertThrows(BusinessException.class, () -> tsRuleService.create(createRequest("X-BAD2", "PEAK2", 100, 200, "1.0")));
    }

    // ------------------------------------------------------------------
    // 2. 业务时区跨日 + 左闭右开：跨午夜潮次归属与区间边界
    // ------------------------------------------------------------------

    @Test
    void crossMidnightTide_attributionAndIntervalBoundaries() {
        // 网箱海区：东海一区，业务时区 UTC+8
        String cageNo = "CAGE-TS-X1";
        registerSeaArea(cageNo, "东海一区", "Asia/Shanghai");
        Long batchId = newBatch(cageNo);
        // 4 个版本化规则：峰/平/谷 + 跨午夜峰值 [22:00,02:00)
        createEnabledRule("PEAK-DAY", "PEAK", 360, 600, "1.5000");
        createEnabledRule("FLAT-DAY", "FLAT", 600, 1080, "1.0000");
        createEnabledRule("VALLEY-EVE", "VALLEY", 1080, 1320, "0.8000");
        createEnabledRule("PEAK-NIGHT", "PEAK", 1320, 120, "1.2000");

        // 跨午夜潮次 T1：当地 2026-09-13 22:00 → 2026-09-14 02:00（UTC 14:00→18:00）
        TideSession t1 = newTide("TIDE-X1-1", cageNo,
                LocalDateTime.of(2026, 9, 13, 14, 0), LocalDateTime.of(2026, 9, 13, 18, 0));
        assertEquals(LocalDate.of(2026, 9, 13), t1.getBusinessDate(),
                "跨午夜潮次必须按海区时区归属到开始日");
        // 接续潮次 T2：当地 02:00 → 06:00（左闭右开相接）
        TideSession t2 = newTide("TIDE-X1-2", cageNo,
                LocalDateTime.of(2026, 9, 13, 18, 0), LocalDateTime.of(2026, 9, 13, 22, 0));
        assertEquals(LocalDate.of(2026, 9, 14), t2.getBusinessDate());

        // 观测（UTC 时刻）：边界全覆盖
        newObs("OBS-X1-1", batchId, LocalDateTime.of(2026, 9, 13, 14, 0, 0), "10.004"); // 当地 22:00:00 潮次左闭
        newObs("OBS-X1-2", batchId, LocalDateTime.of(2026, 9, 13, 16, 30, 0), "20.000"); // 当地 00:30:00 跨午夜
        newObs("OBS-X1-3", batchId, LocalDateTime.of(2026, 9, 13, 17, 59, 59), "5.000"); // 当地 01:59:59
        newObs("OBS-X1-4", batchId, LocalDateTime.of(2026, 9, 13, 18, 0, 0), "7.000"); // 当地 02:00:00 T1右开→T2左闭
        newObs("OBS-X1-5", batchId, LocalDateTime.of(2026, 9, 13, 19, 0, 0), "3.000"); // 当地 03:00:00 规则空档

        // 业务日 2026-09-13：T1 内 3 条观测全部命中跨午夜规则 PEAK-NIGHT
        TsCalcResultView day1 = tsCalcService.recalculate(batchId, LocalDate.of(2026, 9, 13));
        assertEquals("DONE", day1.getStatus());
        assertEquals(3, day1.getObsTotal());
        assertEquals(3, day1.getObsMatched());
        assertEquals(0, day1.getObsUnmatched());
        // 精确累计 10.004×1.2 + 20×1.2 + 5×1.2 = 42.0048 → 最终统一舍入 42.00
        assertBd("42.00", day1.getPeakTotalKg());
        assertBd("0.00", day1.getFlatTotalKg());
        assertBd("0.00", day1.getValleyTotalKg());
        assertBd("42.00", day1.getTotalWeightedKg());
        assertEquals(4, day1.getRuleSnapshot().size(), "快照应含全部 4 个启用规则");

        List<TsCalcDetail> details1 = tsCalcService.listDetails(day1.getResultId());
        assertEquals(3, details1.size());
        // 潮次快照 + 规则版本快照随明细固化
        for (TsCalcDetail d : details1) {
            assertEquals("TIDE-X1-1", d.getTideNo());
            assertEquals(t1.getId(), d.getTideSessionId());
            assertEquals(LocalDateTime.of(2026, 9, 13, 14, 0), d.getTideStartUtc());
            assertEquals(LocalDateTime.of(2026, 9, 13, 18, 0), d.getTideEndUtc());
            assertEquals("PEAK-NIGHT", d.getRuleCode());
            assertEquals(1, d.getRuleVersion());
            assertEquals("PEAK", d.getRuleType());
        }
        // 明细保留未舍入精确乘积
        assertBd("12.0048", details1.get(0).getWeightedKg());
        assertBd("24", details1.get(1).getWeightedKg());
        assertBd("6", details1.get(2).getWeightedKg());
        // 当地时刻按网箱海区时区归算（含跨午夜）
        assertEquals("22:00:00", details1.get(0).getObsLocalTime());
        assertEquals("00:30:00", details1.get(1).getObsLocalTime());
        assertEquals("01:59:59", details1.get(2).getObsLocalTime());

        // 业务日 2026-09-14：T2 内 2 条观测处于规则空档（02:00 右开、03:00 无规则覆盖）
        TsCalcResultView day2 = tsCalcService.recalculate(batchId, LocalDate.of(2026, 9, 14));
        assertEquals("DONE", day2.getStatus());
        assertEquals(2, day2.getObsTotal());
        assertEquals(0, day2.getObsMatched());
        assertEquals(2, day2.getObsUnmatched());
        assertBd("0.00", day2.getTotalWeightedKg());
        assertEquals(0, tsCalcService.listDetails(day2.getResultId()).size());
    }

    // ------------------------------------------------------------------
    // 3. 累计计算 BigDecimal：最终步骤统一舍入（HALF_UP）
    // ------------------------------------------------------------------

    @Test
    void accumulation_bigDecimalFinalStepRounding() {
        String cageNo = "CAGE-TS-R1";
        registerSeaArea(cageNo, "南海试验区", "UTC");
        Long batchId = newBatch(cageNo);
        createEnabledRule("FLAT-ALL", "FLAT", 0, 1440, "1.0000");

        // 场景 A：3 × 0.004kg —— 逐条舍入会得 0.00，统一最终舍入得 0.01
        newTide("TIDE-R1-A", cageNo, LocalDateTime.of(2026, 9, 13, 6, 0), LocalDateTime.of(2026, 9, 13, 12, 0));
        newObs("OBS-R1-A1", batchId, LocalDateTime.of(2026, 9, 13, 7, 0), "0.004");
        newObs("OBS-R1-A2", batchId, LocalDateTime.of(2026, 9, 13, 8, 0), "0.004");
        newObs("OBS-R1-A3", batchId, LocalDateTime.of(2026, 9, 13, 9, 0), "0.004");
        TsCalcResultView scenarioA = tsCalcService.recalculate(batchId, LocalDate.of(2026, 9, 13));
        assertBd("0.01", scenarioA.getFlatTotalKg(), "0.012 必须在最终步骤统一舍入为 0.01，而非逐条舍入为 0.00");
        assertBd("0.01", scenarioA.getTotalWeightedKg());
        List<TsCalcDetail> detailsA = tsCalcService.listDetails(scenarioA.getResultId());
        assertEquals(3, detailsA.size());
        for (TsCalcDetail d : detailsA) {
            assertBd("0.004", d.getWeightedKg(), "明细层保留未舍入精确乘积");
        }

        // 场景 B：规则演进到 v2（系数 1.5），2 × 3.335kg —— 精确和 10.005，HALF_UP 得 10.01
        TsRule v2 = tsRuleService.create(createRequest("FLAT-ALL", "FLAT", 0, 1440, "1.5000"));
        tsRuleService.enable(v2.getId());
        newTide("TIDE-R1-B", cageNo, LocalDateTime.of(2026, 9, 14, 6, 0), LocalDateTime.of(2026, 9, 14, 12, 0));
        newObs("OBS-R1-B1", batchId, LocalDateTime.of(2026, 9, 14, 7, 0), "3.335");
        newObs("OBS-R1-B2", batchId, LocalDateTime.of(2026, 9, 14, 8, 0), "3.335");
        TsCalcResultView scenarioB = tsCalcService.recalculate(batchId, LocalDate.of(2026, 9, 14));
        assertBd("10.01", scenarioB.getFlatTotalKg(), "10.005 必须按 HALF_UP 舍入为 10.01");
        assertBd("10.01", scenarioB.getTotalWeightedKg());
        // 场景 B 采用 v2 快照，场景 A 仍为 v1 快照
        assertEquals(2, scenarioB.getRuleSnapshot().get(0).getVersionNo());
        assertEquals(1, tsCalcService.getResult(scenarioA.getResultId()).getRuleSnapshot().get(0).getVersionNo());
    }

    // ------------------------------------------------------------------
    // 4. 历史结果保留采用的版本：规则切换后读取当时快照（含潮次快照）
    // ------------------------------------------------------------------

    @Test
    void historicalResults_keepRuleAndTideSnapshotAcrossRuleSwitch() {
        String cageNo = "CAGE-TS-H1";
        registerSeaArea(cageNo, "黄海一区", "UTC");
        Long batchId = newBatch(cageNo);
        createEnabledRule("FLAT-DAY", "FLAT", 0, 720, "1.0000");

        TideSession t1 = newTide("TIDE-H1-1", cageNo,
                LocalDateTime.of(2026, 9, 13, 6, 0), LocalDateTime.of(2026, 9, 13, 12, 0));
        newObs("OBS-H1-1", batchId, LocalDateTime.of(2026, 9, 13, 8, 0), "10.000");
        LocalDate day1 = LocalDate.of(2026, 9, 13);
        TsCalcResultView result1 = tsCalcService.recalculate(batchId, day1);
        assertBd("10.00", result1.getFlatTotalKg());
        assertEquals(1, result1.getRuleSnapshot().get(0).getVersionNo());

        // 规则切换：v2 系数翻倍，v1 退役
        TsRule v2 = tsRuleService.create(createRequest("FLAT-DAY", "FLAT", 0, 720, "2.0000"));
        tsRuleService.enable(v2.getId());

        // 新业务日按 v2 计算
        newTide("TIDE-H1-2", cageNo, LocalDateTime.of(2026, 9, 14, 6, 0), LocalDateTime.of(2026, 9, 14, 12, 0));
        newObs("OBS-H1-2", batchId, LocalDateTime.of(2026, 9, 14, 8, 0), "10.000");
        TsCalcResultView result2 = tsCalcService.recalculate(batchId, LocalDate.of(2026, 9, 14));
        assertEquals(2, result2.getRuleSnapshot().get(0).getVersionNo());
        assertBd("20.00", result2.getFlatTotalKg());

        // 历史结果读取当时快照：day1 仍是 v1 / 系数 1.0 / 合计 10.00
        TsCalcResultView history1 = tsCalcService.getResult(result1.getResultId());
        assertEquals(1, history1.getRuleSnapshot().get(0).getVersionNo());
        assertBd("1.0000", history1.getRuleSnapshot().get(0).getCoefficient());
        assertBd("10.00", history1.getFlatTotalKg());
        // 规则切换保留潮次快照：day1 明细仍引用当时潮次与规则版本
        List<TsCalcDetail> historyDetails = tsCalcService.listDetails(result1.getResultId());
        assertEquals(1, historyDetails.size());
        assertEquals("TIDE-H1-1", historyDetails.get(0).getTideNo());
        assertEquals(t1.getId(), historyDetails.get(0).getTideSessionId());
        assertEquals(1, historyDetails.get(0).getRuleVersion());
        assertBd("1.0000", historyDetails.get(0).getCoefficient());

        // 重算才采用新版本：day1 重算后快照升级为 v2，合计按新系数
        TsCalcResultView recalc1 = tsCalcService.recalculate(batchId, day1);
        assertEquals(2, recalc1.getRuleSnapshot().get(0).getVersionNo());
        assertBd("20.00", recalc1.getFlatTotalKg());
        assertEquals(2, recalc1.getAttempts());
        // 未被重算的 day2 结果不受影响
        TsCalcResultView untouched2 = tsCalcService.getResult(result2.getResultId());
        assertEquals(2, untouched2.getRuleSnapshot().get(0).getVersionNo());
        assertBd("20.00", untouched2.getFlatTotalKg());
    }

    // ------------------------------------------------------------------
    // 5. 并发重算幂等：8 线程同时重算同一批次同一业务日，不产生两份结果
    // ------------------------------------------------------------------

    @Test
    void concurrentRecalculation_singleConsistentResult() throws Exception {
        String cageNo = "CAGE-TS-C1";
        registerSeaArea(cageNo, "东海二区", "UTC");
        Long batchId = newBatch(cageNo);
        createEnabledRule("FLAT-ALL", "FLAT", 0, 1440, "1.0000");
        newTide("TIDE-C1-1", cageNo, LocalDateTime.of(2026, 9, 13, 0, 0), LocalDateTime.of(2026, 9, 13, 23, 0));
        // 10 条观测，投饵量 1..10 kg，合计 55 kg
        for (int i = 1; i <= 10; i++) {
            newObs(String.format("OBS-C1-%02d", i), batchId,
                    LocalDateTime.of(2026, 9, 13, i, 0), i + ".000");
        }
        LocalDate businessDate = LocalDate.of(2026, 9, 13);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TsCalcResultView>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return tsCalcService.recalculate(batchId, businessDate);
                }));
            }
            start.countDown();
            for (Future<TsCalcResultView> future : futures) {
                TsCalcResultView view = future.get(60, TimeUnit.SECONDS);
                assertTrue("DONE".equals(view.getStatus()) || "SKIPPED".equals(view.getStatus()),
                        "并发重算只能完成或被跳过，不得异常: " + view.getStatus());
            }
        } finally {
            pool.shutdownNow();
        }

        // 恰好一份结果：结果行唯一、明细恰好一代（10 条）、合计正确
        Integer resultCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_ts_calc_result WHERE batch_id = ? AND business_date = ?",
                Integer.class, batchId, businessDate);
        assertEquals(1, resultCount, "并发重算不得产生两份结果");
        TsCalcResultView finalView = tsCalcService.listResults(batchId, businessDate).get(0);
        assertEquals("DONE", finalView.getStatus());
        assertEquals(10, finalView.getObsTotal());
        assertEquals(10, finalView.getObsMatched());
        assertBd("55.00", finalView.getTotalWeightedKg());
        Integer detailCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_ts_calc_detail WHERE result_id = ?",
                Integer.class, finalView.getResultId());
        assertEquals(10, detailCount, "明细必须恰好一代，不得因并发重算翻倍");
    }

    // ------------------------------------------------------------------
    // 6. 潮次登记：海区时区归属、左闭右开重叠校验、档案快照
    // ------------------------------------------------------------------

    @Test
    void tideSession_timezoneAttributionOverlapAndSnapshot() {
        registerSeaArea("CAGE-TS-TA", "东海一区", "Asia/Shanghai");
        registerSeaArea("CAGE-TS-TB", "远洋海区", "UTC");

        // 同一 UTC 时刻，不同海区时区归属不同业务日
        TideSession tideA = newTide("TIDE-TA-1", "CAGE-TS-TA",
                LocalDateTime.of(2026, 9, 13, 16, 30), LocalDateTime.of(2026, 9, 13, 20, 30));
        assertEquals(LocalDate.of(2026, 9, 14), tideA.getBusinessDate(),
                "UTC+8 海区：UTC 16:30 已是当地次日 00:30");
        TideSession tideB = newTide("TIDE-TB-1", "CAGE-TS-TB",
                LocalDateTime.of(2026, 9, 13, 16, 30), LocalDateTime.of(2026, 9, 13, 20, 30));
        assertEquals(LocalDate.of(2026, 9, 13), tideB.getBusinessDate(),
                "UTC 海区：同一时刻仍属当日");

        // 同网箱潮次重叠拒绝；左闭右开相接允许
        assertThrows(BusinessException.class, () -> newTide("TIDE-TA-2", "CAGE-TS-TA",
                LocalDateTime.of(2026, 9, 13, 18, 0), LocalDateTime.of(2026, 9, 13, 22, 0)));
        TideSession adjacent = newTide("TIDE-TA-3", "CAGE-TS-TA",
                LocalDateTime.of(2026, 9, 13, 20, 30), LocalDateTime.of(2026, 9, 13, 23, 0));
        assertNotNull(adjacent.getId());

        // 开始必须早于结束（左闭右开非空区间）；未登记海区档案的网箱拒绝
        assertThrows(BusinessException.class, () -> newTide("TIDE-TA-4", "CAGE-TS-TA",
                LocalDateTime.of(2026, 9, 14, 2, 0), LocalDateTime.of(2026, 9, 14, 2, 0)));
        assertThrows(BusinessException.class, () -> newTide("TIDE-TN-1", "CAGE-NONE",
                LocalDateTime.of(2026, 9, 13, 0, 0), LocalDateTime.of(2026, 9, 13, 6, 0)));

        // 档案调整不影响已登记潮次的时区快照（历史归属不变）
        CageSeaArea area = cageSeaAreaService.findByCageNo("CAGE-TS-TA");
        com.evops.aquaculture.dto.CageSeaAreaRequest update = new com.evops.aquaculture.dto.CageSeaAreaRequest();
        update.setCageNo("CAGE-TS-TA");
        update.setSeaArea("东海一区");
        update.setTimeZone("Pacific/Guam");
        cageSeaAreaService.update(area.getId(), update);
        TideSession reloaded = tideSessionService.getById(tideA.getId());
        assertEquals("Asia/Shanghai", reloaded.getTimeZone(), "潮次必须保留登记时的时区快照");
        assertEquals(LocalDate.of(2026, 9, 14), reloaded.getBusinessDate());
    }

    // ------------------------------------------------------------------ helpers

    private Long newBatch(String cageNo) {
        FishBatch batch = new FishBatch();
        batch.setBatchNo("FB-TS-" + (++seq) + "-" + System.nanoTime());
        batch.setCageNo(cageNo);
        batch.setSpecies("大黄鱼");
        batch.setFingerlingCount(10000);
        batch.setStatus("MONITORING");
        batch.setStockingTime(LocalDateTime.now().minusDays(30));
        fishBatchMapper.insert(batch);
        return batch.getId();
    }

    private void registerSeaArea(String cageNo, String seaArea, String timeZone) {
        com.evops.aquaculture.dto.CageSeaAreaRequest request = new com.evops.aquaculture.dto.CageSeaAreaRequest();
        request.setCageNo(cageNo);
        request.setSeaArea(seaArea);
        request.setTimeZone(timeZone);
        cageSeaAreaService.create(request);
    }

    private TsRule createEnabledRule(String code, String type, int start, int end, String coefficient) {
        TsRule rule = tsRuleService.create(createRequest(code, type, start, end, coefficient));
        tsRuleService.enable(rule.getId());
        return tsRuleService.getById(rule.getId());
    }

    private TsRuleCreateRequest createRequest(String code, String type, int start, int end, String coefficient) {
        TsRuleCreateRequest request = new TsRuleCreateRequest();
        request.setRuleCode(code);
        request.setRuleType(type);
        request.setStartMinute(start);
        request.setEndMinute(end);
        request.setCoefficient(new BigDecimal(coefficient));
        return request;
    }

    private TsRuleUpdateRequest updateRequest(String type, int start, int end, String coefficient) {
        TsRuleUpdateRequest request = new TsRuleUpdateRequest();
        request.setRuleType(type);
        request.setStartMinute(start);
        request.setEndMinute(end);
        request.setCoefficient(new BigDecimal(coefficient));
        return request;
    }

    private TideSession newTide(String tideNo, String cageNo, LocalDateTime startUtc, LocalDateTime endUtc) {
        com.evops.aquaculture.dto.TideSessionCreateRequest request =
                new com.evops.aquaculture.dto.TideSessionCreateRequest();
        request.setTideNo(tideNo);
        request.setCageNo(cageNo);
        request.setStartAtUtc(startUtc);
        request.setEndAtUtc(endUtc);
        return tideSessionService.create(request);
    }

    private FeedObservation newObs(String obsNo, Long batchId, LocalDateTime observedAtUtc, String amountKg) {
        com.evops.aquaculture.dto.FeedObservationCreateRequest request =
                new com.evops.aquaculture.dto.FeedObservationCreateRequest();
        request.setObsNo(obsNo);
        request.setBatchId(batchId);
        request.setObservedAtUtc(observedAtUtc);
        request.setFeedAmountKg(new BigDecimal(amountKg));
        return feedObservationService.create(request);
    }

    private void assertBd(String expected, BigDecimal actual) {
        assertBd(expected, actual, null);
    }

    private void assertBd(String expected, BigDecimal actual, String message) {
        assertNotNull(actual, message);
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                (message == null ? "" : message + "：") + "期望 " + expected + " 实际 " + actual);
    }
}
