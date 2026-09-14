package com.evops.aquaculture.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.aquaculture.dto.TsCalcResultView;
import com.evops.aquaculture.dto.TsRuleSnapshot;
import com.evops.aquaculture.entity.CageSeaArea;
import com.evops.aquaculture.entity.FeedObservation;
import com.evops.aquaculture.entity.FishBatch;
import com.evops.aquaculture.entity.TideSession;
import com.evops.aquaculture.entity.TsCalcDetail;
import com.evops.aquaculture.entity.TsCalcResult;
import com.evops.aquaculture.entity.TsRule;
import com.evops.aquaculture.enums.RuleType;
import com.evops.aquaculture.enums.TsCalcStatus;
import com.evops.aquaculture.mapper.FeedObservationMapper;
import com.evops.aquaculture.mapper.TideSessionMapper;
import com.evops.aquaculture.mapper.TsCalcDetailMapper;
import com.evops.aquaculture.mapper.TsCalcResultMapper;
import com.evops.common.BusinessException;
import com.evops.common.util.TimeIntervals;
import com.evops.common.util.TzUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 深海网箱养殖批次与投饵监测时序规则计算。
 *
 * 计算口径：
 * 1. 按网箱所在海区时区，把业务日内的投饵观测归入潮次（左闭右开，跨午夜潮次按海区时区归属业务日），
 *    再按观测当地时刻落入的启用规则区间（左闭右开，含跨午夜环形区间）生成计算明细；
 * 2. 明细固化潮次快照与规则版本快照——规则切换后历史结果仍读取当时快照；
 * 3. 折算量累计全程使用 BigDecimal 精确运算，仅在写入结果合计的最终步骤统一舍入（HALF_UP，2 位）。
 *
 * 幂等约束：结果按 (batch_id, business_date) 唯一；重算先 CAS 抢占 CALCULATING 状态，
 * 明细与结果在同一事务内整体替换——并发重算不会产生两份结果，失败回滚不破坏既有结果。
 */
@Service
public class TsCalcService {

    private static final Logger log = LoggerFactory.getLogger(TsCalcService.class);

    /** 合计统一舍入标度（最终步骤执行） */
    private static final int TOTAL_SCALE = 2;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final TsCalcResultMapper resultMapper;
    private final TsCalcDetailMapper detailMapper;
    private final TideSessionMapper tideSessionMapper;
    private final FeedObservationMapper feedObservationMapper;
    private final FishBatchService fishBatchService;
    private final CageSeaAreaService cageSeaAreaService;
    private final TsRuleService tsRuleService;
    private final ObjectMapper objectMapper;

    public TsCalcService(TsCalcResultMapper resultMapper,
                         TsCalcDetailMapper detailMapper,
                         TideSessionMapper tideSessionMapper,
                         FeedObservationMapper feedObservationMapper,
                         FishBatchService fishBatchService,
                         CageSeaAreaService cageSeaAreaService,
                         TsRuleService tsRuleService,
                         ObjectMapper objectMapper) {
        this.resultMapper = resultMapper;
        this.detailMapper = detailMapper;
        this.tideSessionMapper = tideSessionMapper;
        this.feedObservationMapper = feedObservationMapper;
        this.fishBatchService = fishBatchService;
        this.cageSeaAreaService = cageSeaAreaService;
        this.tsRuleService = tsRuleService;
        this.objectMapper = objectMapper;
    }

    /**
     * 计算/重算某批次某业务日。并发安全：唯一键 + CALCULATING CAS 抢占 +
     * 同事务明细整体替换；抢占失败返回 SKIPPED，不产生第二份结果。
     */
    @Transactional
    public TsCalcResultView recalculate(Long batchId, LocalDate businessDate) {
        // 前置校验（失败不落任何状态，既有结果不受影响）
        FishBatch batch = fishBatchService.getById(batchId);
        String cageNo = batch.getCageNo();
        CageSeaArea seaArea = cageSeaAreaService.requireByCageNo(cageNo);
        ZoneId zoneId = TzUtils.requireZone(seaArea.getTimeZone());
        List<TsRule> enabledRules = tsRuleService.listEnabled();
        if (enabledRules.isEmpty()) {
            throw new BusinessException("无启用中的时序规则，无法执行计算");
        }

        TsCalcResult result = claimResult(batchId, cageNo, businessDate);
        if (result == null) {
            TsCalcResultView skipped = new TsCalcResultView();
            skipped.setBatchId(batchId);
            skipped.setCageNo(cageNo);
            skipped.setBusinessDate(businessDate.toString());
            skipped.setStatus("SKIPPED");
            skipped.setMessage("该批次该业务日正在由其他执行方重算，本次跳过");
            TsCalcResult current = findResult(batchId, businessDate);
            if (current != null) {
                skipped.setResultId(current.getId());
                skipped.setAttempts(current.getAttempts());
            }
            return skipped;
        }

        // 业务日潮次（跨午夜潮次已按海区时区归属到本业务日）
        List<TideSession> tides = tideSessionMapper.selectList(new LambdaQueryWrapper<TideSession>()
                .eq(TideSession::getCageNo, cageNo)
                .eq(TideSession::getBusinessDate, businessDate)
                .orderByAsc(TideSession::getStartAtUtc));

        // 业务日潮次时间窗内的批次观测
        List<FeedObservation> observations = loadObservations(batchId, tides);

        // 逐条归类：潮次（左闭右开）→ 海区时区当地时刻 → 启用规则区间（左闭右开）
        Map<RuleType, BigDecimal> typeTotals = new EnumMap<>(RuleType.class);
        for (RuleType type : RuleType.values()) {
            typeTotals.put(type, BigDecimal.ZERO);
        }
        BigDecimal grandTotal = BigDecimal.ZERO;
        List<TsCalcDetail> details = new ArrayList<>();
        for (FeedObservation obs : observations) {
            TideSession tide = findTide(tides, obs.getObservedAtUtc());
            if (tide == null) {
                continue;
            }
            LocalDateTime localTime = TzUtils.toLocal(obs.getObservedAtUtc(), zoneId);
            int minuteOfDay = localTime.getHour() * 60 + localTime.getMinute();
            TsRule rule = findRule(enabledRules, minuteOfDay);
            if (rule == null) {
                continue;
            }
            // 精确乘积，不在明细层舍入
            BigDecimal weighted = obs.getFeedAmountKg().multiply(rule.getCoefficient());
            details.add(buildDetail(result.getId(), obs, tide, rule, localTime, weighted));
            typeTotals.merge(RuleType.valueOf(rule.getRuleType()), weighted, BigDecimal::add);
            grandTotal = grandTotal.add(weighted);
        }

        // 明细整体替换 + 结果落库，同一事务提交
        detailMapper.deleteByResultId(result.getId());
        for (TsCalcDetail detail : details) {
            detailMapper.insert(detail);
        }

        LocalDateTime calcTime = LocalDateTime.now();
        // 最终步骤统一舍入
        BigDecimal peakTotal = roundTotal(typeTotals.get(RuleType.PEAK));
        BigDecimal flatTotal = roundTotal(typeTotals.get(RuleType.FLAT));
        BigDecimal valleyTotal = roundTotal(typeTotals.get(RuleType.VALLEY));
        BigDecimal totalWeighted = roundTotal(grandTotal);
        String snapshotJson = serializeSnapshot(enabledRules);

        int matched = details.size();
        resultMapper.markDone(result.getId(), observations.size(), matched, observations.size() - matched,
                peakTotal, flatTotal, valleyTotal, totalWeighted, snapshotJson, calcTime);
        log.info("时序规则计算完成：批次 {} 业务日 {} 观测 {} 条命中 {} 条，采用规则版本快照 {} 条",
                batchId, businessDate, observations.size(), matched, enabledRules.size());
        return toView(resultMapper.selectById(result.getId()));
    }

    public TsCalcResultView getResult(Long id) {
        TsCalcResult result = resultMapper.selectById(id);
        if (result == null) {
            throw new BusinessException("计算结果不存在: " + id);
        }
        return toView(result);
    }

    public List<TsCalcResultView> listResults(Long batchId, LocalDate businessDate) {
        List<TsCalcResult> results = resultMapper.selectList(new LambdaQueryWrapper<TsCalcResult>()
                .eq(batchId != null, TsCalcResult::getBatchId, batchId)
                .eq(businessDate != null, TsCalcResult::getBusinessDate, businessDate)
                .orderByAsc(TsCalcResult::getBusinessDate));
        List<TsCalcResultView> views = new ArrayList<>(results.size());
        for (TsCalcResult result : results) {
            views.add(toView(result));
        }
        return views;
    }

    /** 计算明细（含潮次快照与规则版本快照）。 */
    public List<TsCalcDetail> listDetails(Long resultId) {
        return detailMapper.selectList(new LambdaQueryWrapper<TsCalcDetail>()
                .eq(TsCalcDetail::getResultId, resultId)
                .orderByAsc(TsCalcDetail::getObservedAtUtc)
                .orderByAsc(TsCalcDetail::getId));
    }

    // ---------------- 抢占与归集 ----------------

    /** 抢占 (batch, businessDate) 的计算权；被其他执行方占用时返回 null。 */
    private TsCalcResult claimResult(Long batchId, String cageNo, LocalDate businessDate) {
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 2; i++) {
            TsCalcResult existing = findResult(batchId, businessDate);
            if (existing == null) {
                TsCalcResult fresh = new TsCalcResult();
                fresh.setBatchId(batchId);
                fresh.setCageNo(cageNo);
                fresh.setBusinessDate(businessDate);
                fresh.setStatus(TsCalcStatus.CALCULATING.name());
                fresh.setAttempts(1);
                fresh.setObsTotal(0);
                fresh.setObsMatched(0);
                fresh.setObsUnmatched(0);
                try {
                    resultMapper.insert(fresh);
                    return fresh;
                } catch (DuplicateKeyException dup) {
                    // 并发首建：重新竞争 CAS
                    continue;
                }
            }
            if (resultMapper.claimForRecalc(existing.getId(), now) == 1) {
                return resultMapper.selectById(existing.getId());
            }
            return null;
        }
        return null;
    }

    private TsCalcResult findResult(Long batchId, LocalDate businessDate) {
        return resultMapper.selectOne(new LambdaQueryWrapper<TsCalcResult>()
                .eq(TsCalcResult::getBatchId, batchId)
                .eq(TsCalcResult::getBusinessDate, businessDate));
    }

    private List<FeedObservation> loadObservations(Long batchId, List<TideSession> tides) {
        if (tides.isEmpty()) {
            return Collections.emptyList();
        }
        LocalDateTime windowStart = tides.get(0).getStartAtUtc();
        LocalDateTime windowEnd = tides.get(0).getEndAtUtc();
        for (TideSession tide : tides) {
            if (tide.getStartAtUtc().isBefore(windowStart)) {
                windowStart = tide.getStartAtUtc();
            }
            if (tide.getEndAtUtc().isAfter(windowEnd)) {
                windowEnd = tide.getEndAtUtc();
            }
        }
        return feedObservationMapper.selectList(new LambdaQueryWrapper<FeedObservation>()
                .eq(FeedObservation::getBatchId, batchId)
                .ge(FeedObservation::getObservedAtUtc, windowStart)
                .lt(FeedObservation::getObservedAtUtc, windowEnd)
                .orderByAsc(FeedObservation::getObservedAtUtc));
    }

    /** 潮次归属：左闭右开 [startAtUtc, endAtUtc)。 */
    private TideSession findTide(List<TideSession> tides, LocalDateTime observedAtUtc) {
        for (TideSession tide : tides) {
            if (!observedAtUtc.isBefore(tide.getStartAtUtc()) && observedAtUtc.isBefore(tide.getEndAtUtc())) {
                return tide;
            }
        }
        return null;
    }

    /** 规则归类：观测当地时刻的日内分钟落入的启用规则区间（左闭右开）。 */
    private TsRule findRule(List<TsRule> enabledRules, int minuteOfDay) {
        for (TsRule rule : enabledRules) {
            if (TimeIntervals.contains(rule.getStartMinute(), rule.getEndMinute(), minuteOfDay)) {
                return rule;
            }
        }
        return null;
    }

    private TsCalcDetail buildDetail(Long resultId, FeedObservation obs, TideSession tide,
                                     TsRule rule, LocalDateTime localTime, BigDecimal weighted) {
        TsCalcDetail detail = new TsCalcDetail();
        detail.setResultId(resultId);
        detail.setObsId(obs.getId());
        detail.setObsNo(obs.getObsNo());
        detail.setObservedAtUtc(obs.getObservedAtUtc());
        detail.setObsLocalTime(localTime.format(TIME_FORMATTER));
        // 潮次快照
        detail.setTideSessionId(tide.getId());
        detail.setTideNo(tide.getTideNo());
        detail.setTideStartUtc(tide.getStartAtUtc());
        detail.setTideEndUtc(tide.getEndAtUtc());
        // 规则版本快照
        detail.setRuleId(rule.getId());
        detail.setRuleCode(rule.getRuleCode());
        detail.setRuleVersion(rule.getVersionNo());
        detail.setRuleType(rule.getRuleType());
        detail.setCoefficient(rule.getCoefficient());
        detail.setFeedAmountKg(obs.getFeedAmountKg());
        detail.setWeightedKg(weighted);
        return detail;
    }

    /** 最终步骤统一舍入：HALF_UP 到 2 位小数。 */
    private BigDecimal roundTotal(BigDecimal exact) {
        return exact.setScale(TOTAL_SCALE, RoundingMode.HALF_UP);
    }

    // ---------------- 快照与视图 ----------------

    private String serializeSnapshot(List<TsRule> enabledRules) {
        List<TsRuleSnapshot> snapshots = new ArrayList<>(enabledRules.size());
        for (TsRule rule : enabledRules) {
            TsRuleSnapshot snapshot = new TsRuleSnapshot();
            snapshot.setRuleId(rule.getId());
            snapshot.setRuleCode(rule.getRuleCode());
            snapshot.setVersionNo(rule.getVersionNo());
            snapshot.setRuleType(rule.getRuleType());
            snapshot.setStartMinute(rule.getStartMinute());
            snapshot.setEndMinute(rule.getEndMinute());
            snapshot.setCoefficient(rule.getCoefficient());
            snapshots.add(snapshot);
        }
        try {
            return objectMapper.writeValueAsString(snapshots);
        } catch (Exception ex) {
            throw new IllegalStateException("规则快照序列化失败", ex);
        }
    }

    private TsCalcResultView toView(TsCalcResult result) {
        TsCalcResultView view = new TsCalcResultView();
        view.setResultId(result.getId());
        view.setBatchId(result.getBatchId());
        view.setCageNo(result.getCageNo());
        view.setBusinessDate(result.getBusinessDate() == null ? null : result.getBusinessDate().toString());
        view.setStatus(result.getStatus());
        view.setAttempts(result.getAttempts());
        view.setObsTotal(result.getObsTotal());
        view.setObsMatched(result.getObsMatched());
        view.setObsUnmatched(result.getObsUnmatched());
        view.setPeakTotalKg(result.getPeakTotalKg());
        view.setFlatTotalKg(result.getFlatTotalKg());
        view.setValleyTotalKg(result.getValleyTotalKg());
        view.setTotalWeightedKg(result.getTotalWeightedKg());
        view.setCalcTime(result.getCalcTime() == null
                ? null : result.getCalcTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        view.setRuleSnapshot(deserializeSnapshot(result.getRuleSnapshot()));
        return view;
    }

    private List<TsRuleSnapshot> deserializeSnapshot(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(snapshotJson, new TypeReference<List<TsRuleSnapshot>>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("规则快照反序列化失败", ex);
        }
    }
}
