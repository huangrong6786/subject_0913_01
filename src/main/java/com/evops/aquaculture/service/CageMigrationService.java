package com.evops.aquaculture.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.aquaculture.dto.CageMigrationHistoryView;
import com.evops.aquaculture.dto.CageMigrationRequest;
import com.evops.aquaculture.dto.CageMigrationSnapshot;
import com.evops.aquaculture.entity.CageMigration;
import com.evops.aquaculture.entity.CageMigrationChain;
import com.evops.aquaculture.entity.FeedingPlan;
import com.evops.aquaculture.entity.FeedingRecord;
import com.evops.aquaculture.entity.FishBatch;
import com.evops.aquaculture.entity.SensorReading;
import com.evops.aquaculture.entity.SurvivalReport;
import com.evops.aquaculture.entity.UnderwaterSensor;
import com.evops.aquaculture.enums.BatchStatus;
import com.evops.aquaculture.enums.MigrationType;
import com.evops.aquaculture.enums.SensorStatus;
import com.evops.aquaculture.mapper.CageMigrationChainMapper;
import com.evops.aquaculture.mapper.CageMigrationMapper;
import com.evops.aquaculture.mapper.FeedingPlanMapper;
import com.evops.aquaculture.mapper.FeedingRecordMapper;
import com.evops.aquaculture.mapper.FishBatchMapper;
import com.evops.aquaculture.mapper.SensorReadingMapper;
import com.evops.aquaculture.mapper.SurvivalReportMapper;
import com.evops.aquaculture.mapper.UnderwaterSensorMapper;
import com.evops.common.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 网箱替换/转移（换箱/转场）服务。
 *
 * 一次迁移在同一个数据库事务内完成全部变更：
 *  1. 采集原对象结束值（鱼群估算量、累计投饵量、最新存活率、各传感器最新读数、未完成投饵计划）；
 *  2. 写入一条不可改的迁移事件（含双方交接快照 JSON）；
 *  3. 链头按版本条件推进（seq CAS）；
 *  4. 鱼群（批次）、未完成投饵计划、ONLINE 传感器分别按各自 version 条件更新到新网箱。
 * 任一条件更新 affected=0（版本被并发操作者抢先）→ 抛业务异常整事务回滚，
 * 因而两个操作者并发提交时只有一个版本成功，不存在“链推进了但对象没迁完”的半成品。
 */
@Service
public class CageMigrationService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final CageMigrationMapper migrationMapper;
    private final CageMigrationChainMapper chainMapper;
    private final FishBatchMapper fishBatchMapper;
    private final FeedingPlanMapper planMapper;
    private final FeedingRecordMapper recordMapper;
    private final SurvivalReportMapper survivalMapper;
    private final UnderwaterSensorMapper sensorMapper;
    private final SensorReadingMapper readingMapper;
    private final ObjectMapper objectMapper;

    public CageMigrationService(CageMigrationMapper migrationMapper,
                                CageMigrationChainMapper chainMapper,
                                FishBatchMapper fishBatchMapper,
                                FeedingPlanMapper planMapper,
                                FeedingRecordMapper recordMapper,
                                SurvivalReportMapper survivalMapper,
                                UnderwaterSensorMapper sensorMapper,
                                SensorReadingMapper readingMapper,
                                ObjectMapper objectMapper) {
        this.migrationMapper = migrationMapper;
        this.chainMapper = chainMapper;
        this.fishBatchMapper = fishBatchMapper;
        this.planMapper = planMapper;
        this.recordMapper = recordMapper;
        this.survivalMapper = survivalMapper;
        this.sensorMapper = sensorMapper;
        this.readingMapper = readingMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行一次换箱/转场。同一事务内：采集结束值 → 写快照事件 → CAS 推进链 → CAS 迁移全部关联对象。
     */
    @Transactional
    public CageMigration migrate(CageMigrationRequest request) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime occurredAt = request.getOccurredAt() == null ? now : request.getOccurredAt();
        if (occurredAt.isAfter(now.plusSeconds(1))) {
            throw new BusinessException("迁移发生时间不能晚于当前时间");
        }
        MigrationType type;
        try {
            type = MigrationType.fromParam(request.getTransferType());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("非法迁移类型: " + request.getTransferType() + "，可选 REPLACE/TRANSFER");
        }

        long operatorId = request.getOperatorId() == null
                ? OperatorContext.currentOperatorId() : request.getOperatorId();
        String operatorName = (request.getOperatorName() == null || request.getOperatorName().trim().isEmpty())
                ? OperatorContext.currentOperatorName() : request.getOperatorName().trim();

        FishBatch batch = fishBatchMapper.selectById(request.getBatchId());
        if (batch == null) {
            throw new BusinessException("鱼苗批次不存在: " + request.getBatchId());
        }
        if (isTerminal(batch)) {
            throw new BusinessException("批次已终结/已验收锁定（" + batch.getStatus()
                    + "），不能换箱或转场: " + batch.getBatchNo());
        }
        String sourceCage = batch.getCageNo();
        String targetCage = request.getTargetCageNo().trim();
        if (targetCage.equals(sourceCage)) {
            throw new BusinessException("目标网箱与当前网箱相同，无需迁移: " + sourceCage);
        }

        // 目标网箱必须为空：不允许把鱼群迁入仍有在养鱼群的网箱（对象身份不能重叠）。
        Long occupied = fishBatchMapper.selectCount(new LambdaQueryWrapper<FishBatch>()
                .eq(FishBatch::getCageNo, targetCage)
                .in(FishBatch::getStatus, BatchStatus.BREEDING.name(), BatchStatus.MONITORING.name()));
        if (occupied != null && occupied > 0) {
            throw new BusinessException("目标网箱仍有在养批次，不能迁入: " + targetCage);
        }

        CageMigration existingNo = migrationMapper.selectOne(new LambdaQueryWrapper<CageMigration>()
                .eq(CageMigration::getMigrationNo, request.getMigrationNo()));
        if (existingNo != null) {
            throw new BusinessException("迁移单号已存在: " + request.getMigrationNo());
        }

        // 加锁前的冲突检测版本：记录此刻链头版本、批次版本、未完成计划集合（id+version）。
        // 操作者可在请求中显式携带决策时看到的版本（教科书式乐观锁）；缺省才用服务端读取值。
        int preBatchVersion = batch.getVersion() == null ? 0 : batch.getVersion();
        int expectedBatchVersion = request.getExpectedBatchVersion() == null
                ? preBatchVersion : request.getExpectedBatchVersion();
        CageMigrationChain preChain = chainMapper.selectOne(new LambdaQueryWrapper<CageMigrationChain>()
                .eq(CageMigrationChain::getBatchId, batch.getId()));
        int preSeq = preChain == null ? 0 : preChain.getCurrentSeq();
        int expectedSeqParam = request.getExpectedChainSeq() == null
                ? preSeq : request.getExpectedChainSeq();
        List<FeedingPlan> prePlans = planMapper.selectList(new LambdaQueryWrapper<FeedingPlan>()
                .eq(FeedingPlan::getBatchId, batch.getId())
                .in(FeedingPlan::getStatus,
                        com.evops.aquaculture.enums.PlanStatus.ACTIVE.name(),
                        com.evops.aquaculture.enums.PlanStatus.SUSPENDED.name()));

        // 取批次行锁：两个迁移之间、迁移与未完成投饵计划状态变更之间在此串行化，
        // 锁内读取的链版本/计划集合/传感器状态才是判定与 CAS 的稳定依据。
        batch = fishBatchMapper.selectForUpdate(batch.getId());
        int lockedBatchVersion = batch.getVersion() == null ? 0 : batch.getVersion();
        if (isTerminal(batch) || !sourceCage.equals(batch.getCageNo())
                || lockedBatchVersion != expectedBatchVersion) {
            throw new BusinessException("鱼群状态在并发期间被其他操作者改写（迁移或未完成投饵计划变更），"
                    + "本次提交失败，请刷新后重试: " + batch.getBatchNo());
        }

        CageMigrationChain chain = getOrCreateChain(batch, sourceCage);
        if (!sourceCage.equals(chain.getCurrentCageNo())) {
            // 鱼群当前网箱与链头不一致：对象状态与时间链脱节，必须人工核对，拒绝自动迁移。
            throw new BusinessException("批次当前网箱 " + sourceCage + " 与迁移链链头 "
                    + chain.getCurrentCageNo() + " 不一致，时间链不可断裂，请先核对");
        }
        int expectedSeq = chain.getCurrentSeq() == null ? 0 : chain.getCurrentSeq();
        if (expectedSeq != expectedSeqParam) {
            throw new BusinessException("迁移链已被其他操作者推进（期望版本 " + expectedSeqParam
                    + "，当前 " + expectedSeq + "），本次提交失败，请刷新后重试: " + batch.getBatchNo());
        }
        int nextSeq = expectedSeq + 1;

        // 未完成投饵计划：锁内重读并与锁前快照比对。
        //  - 计划状态变更先持锁提交（ACTIVE→FINISHED 等）：集合/版本变化，迁移失败，仅状态变更生效；
        //  - 迁移先持锁：计划状态变更阻塞在批次行锁，迁移 CAS 带计划到新箱提交后，其 CAS 失配失败。
        List<FeedingPlan> unfinishedPlans = planMapper.selectList(new LambdaQueryWrapper<FeedingPlan>()
                .eq(FeedingPlan::getBatchId, batch.getId())
                .in(FeedingPlan::getStatus,
                        com.evops.aquaculture.enums.PlanStatus.ACTIVE.name(),
                        com.evops.aquaculture.enums.PlanStatus.SUSPENDED.name())
                .orderByAsc(FeedingPlan::getId));
        if (!samePlanSet(prePlans, unfinishedPlans)) {
            throw new BusinessException("未完成投饵计划在并发期间被其他操作者处理（暂停/结束/恢复），"
                    + "转场与计划变更只能一个版本生效，请刷新后重试");
        }
        List<FeedingRecord> records = recordMapper.selectList(new LambdaQueryWrapper<FeedingRecord>()
                .eq(FeedingRecord::getBatchId, batch.getId()));
        List<UnderwaterSensor> sensors = sensorMapper.selectList(new LambdaQueryWrapper<UnderwaterSensor>()
                .eq(UnderwaterSensor::getCageNo, sourceCage)
                .orderByAsc(UnderwaterSensor::getId));

        BigDecimal postedSum = BigDecimal.ZERO;
        BigDecimal unpostedSum = BigDecimal.ZERO;
        int postedCount = 0;
        for (FeedingRecord r : records) {
            if (Integer.valueOf(1).equals(r.getPosted())) {
                postedSum = postedSum.add(r.getAmountKg());
                postedCount++;
            } else {
                unpostedSum = unpostedSum.add(r.getAmountKg());
            }
        }

        SurvivalReport latestReport = survivalMapper.selectOne(new LambdaQueryWrapper<SurvivalReport>()
                .eq(SurvivalReport::getBatchId, batch.getId())
                .orderByDesc(SurvivalReport::getReportTime)
                .last("LIMIT 1"));
        Integer fishEstimate;
        String estimateBasis;
        BigDecimal latestRate = null;
        String latestRateAt = null;
        if (latestReport != null) {
            fishEstimate = latestReport.getAliveCount();
            estimateBasis = "LATEST_SURVIVAL";
            latestRate = latestReport.getSurvivalRate();
            latestRateAt = latestReport.getReportTime() == null ? null : latestReport.getReportTime().format(TS);
        } else {
            fishEstimate = batch.getFingerlingCount();
            estimateBasis = "FINGERLING";
        }

        List<CageMigrationSnapshot.SensorSnapshot> moved = new ArrayList<>();
        List<CageMigrationSnapshot.SensorSnapshot> retained = new ArrayList<>();
        for (UnderwaterSensor sensor : sensors) {
            CageMigrationSnapshot.SensorSnapshot snap = new CageMigrationSnapshot.SensorSnapshot();
            snap.setId(sensor.getId());
            snap.setSensorNo(sensor.getSensorNo());
            snap.setSensorType(sensor.getSensorType());
            snap.setMetricUnit(sensor.getMetricUnit());
            snap.setStatus(sensor.getStatus());
            snap.setVersion(sensor.getVersion() == null ? 0 : sensor.getVersion());
            SensorReading latestReading = readingMapper.selectOne(new LambdaQueryWrapper<SensorReading>()
                    .eq(SensorReading::getSensorId, sensor.getId())
                    .orderByDesc(SensorReading::getReadingTime)
                    .last("LIMIT 1"));
            Long readingCount = readingMapper.selectCount(new LambdaQueryWrapper<SensorReading>()
                    .eq(SensorReading::getSensorId, sensor.getId()));
            if (latestReading != null) {
                snap.setLatestValue(latestReading.getMetricValue());
                snap.setLatestReadingAt(latestReading.getReadingTime().format(TS));
            }
            snap.setReadingCount(readingCount == null ? 0 : readingCount.intValue());
            boolean online = SensorStatus.ONLINE.name().equals(sensor.getStatus());
            snap.setMoved(online);
            if (online) {
                moved.add(snap);
            } else {
                // 停用/维护设备不物理迁移，留在原箱；其历史读数仍在快照中可核对。
                retained.add(snap);
            }
        }

        CageMigrationSnapshot snapshot = buildSnapshot(request.getMigrationNo(), chain.getChainNo(), nextSeq,
                type, batch, occurredAt, estimateBasis, sourceCage, targetCage, fishEstimate,
                postedSum, unpostedSum, records.size(), postedCount, latestRate, latestRateAt,
                unfinishedPlans, moved, retained);
        String snapshotJson = writeJson(snapshot);

        // ---------- 链头条件推进先于事件写入（乐观锁 CAS） ----------
        // 两个并发操作者在此处分流：只有一方 affected=1；落败方立即失败，
        // 不会进入事件写入，也不会在 (chain_id, seq_no) 唯一键上堆积等待。
        int chainAdvanced = chainMapper.advanceIfSeq(chain.getId(), expectedSeq, nextSeq, targetCage, occurredAt);
        if (chainAdvanced == 0) {
            throw new BusinessException("迁移链已被其他操作者推进（版本 " + expectedSeq
                    + " 失配），本次提交失败，请刷新后重试: " + batch.getBatchNo());
        }

        // ---------- 写迁移事件（insert-only，落库后为各对象提供 migration_id 锚点） ----------
        CageMigration event = new CageMigration();
        event.setMigrationNo(request.getMigrationNo());
        event.setChainId(chain.getId());
        event.setBatchId(batch.getId());
        event.setSeqNo(nextSeq);
        event.setTransferType(type.name());
        event.setSourceCageNo(sourceCage);
        event.setTargetCageNo(targetCage);
        event.setOccurredAt(occurredAt);
        event.setOperatorId(operatorId);
        event.setOperatorName(operatorName);
        event.setFishEstimateCount(fishEstimate);
        event.setCumulativeFeedKg(postedSum);
        event.setLatestSurvivalRate(latestRate);
        event.setFeedingPlanCount(unfinishedPlans.size());
        event.setFeedingRecordCount(records.size());
        event.setSensorCount(moved.size());
        event.setBatchVersion(batch.getVersion() == null ? 0 : batch.getVersion());
        event.setPlanVersions(writeJson(versionRefs(unfinishedPlans)));
        event.setSensorVersions(writeJson(sensorVersionRefs(sensors)));
        event.setSnapshotJson(snapshotJson);
        event.setRemark(request.getRemark());
        migrationMapper.insert(event);

        // ---------- 其余对象条件更新（CAS）：任一失配整事务回滚，并发仅一方成功 ----------
        int batchMoved = fishBatchMapper.migrateCageIfVersion(
                batch.getId(), targetCage, event.getBatchVersion(), event.getId(), occurredAt);
        if (batchMoved == 0) {
            throw new BusinessException("鱼苗批次刚被并发改写（状态流转或其他迁移），本次迁移失败: "
                    + batch.getBatchNo());
        }
        for (FeedingPlan plan : unfinishedPlans) {
            int planMoved = planMapper.migrateCageIfVersion(
                    plan.getId(), targetCage, plan.getVersion() == null ? 0 : plan.getVersion(),
                    event.getId(), occurredAt);
            if (planMoved == 0) {
                throw new BusinessException("投饵计划刚被并发处理（状态变更或其他迁移），与本次转场冲突，"
                        + "只能一个版本生效: " + plan.getPlanNo());
            }
        }
        for (CageMigrationSnapshot.SensorSnapshot snap : moved) {
            int sensorMoved = sensorMapper.migrateCageIfVersion(
                    snap.getId(), targetCage, snap.getVersion(), event.getId(), occurredAt);
            if (sensorMoved == 0) {
                throw new BusinessException("传感器刚被并发停用/维护或迁移，与本次操作冲突: "
                        + snap.getSensorNo());
            }
        }
        return event;
    }

    // ---------------- 查询 ----------------

    public CageMigration getById(Long id) {
        CageMigration event = migrationMapper.selectById(id);
        if (event == null) {
            throw new BusinessException("迁移事件不存在: " + id);
        }
        return event;
    }

    public CageMigration getByNo(String migrationNo) {
        CageMigration event = migrationMapper.selectOne(new LambdaQueryWrapper<CageMigration>()
                .eq(CageMigration::getMigrationNo, migrationNo));
        if (event == null) {
            throw new BusinessException("迁移事件不存在: " + migrationNo);
        }
        return event;
    }

    public List<CageMigration> list(Long batchId, String sourceCageNo, String targetCageNo) {
        return migrationMapper.selectList(new LambdaQueryWrapper<CageMigration>()
                .eq(batchId != null, CageMigration::getBatchId, batchId)
                .eq(sourceCageNo != null && !sourceCageNo.isEmpty(),
                        CageMigration::getSourceCageNo, sourceCageNo)
                .eq(targetCageNo != null && !targetCageNo.isEmpty(),
                        CageMigration::getTargetCageNo, targetCageNo)
                .orderByAsc(CageMigration::getSeqNo));
    }

    /** 鱼群（批次）维度的历史链路：有序事件 + 链完整性/交接守恒校验。 */
    public CageMigrationHistoryView historyByBatch(Long batchId) {
        FishBatch batch = fishBatchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("鱼苗批次不存在: " + batchId);
        }
        CageMigrationChain chain = chainMapper.selectOne(new LambdaQueryWrapper<CageMigrationChain>()
                .eq(CageMigrationChain::getBatchId, batchId));
        if (chain == null) {
            throw new BusinessException("该批次尚未发生过网箱迁移，无历史链路: " + batchId);
        }
        return assembleHistory(chain, batch.getBatchNo());
    }

    /**
     * 网箱维度的历史链路：凡是起点/终点/当前/起始网箱命中该编号的链全部返回，
     * 可沿任一对象（网箱）正反向追溯经过它的全部鱼群时间链。
     */
    public List<CageMigrationHistoryView> historyByCage(String cageNo) {
        Set<Long> chainIds = new LinkedHashSet<>();
        List<CageMigrationChain> current = chainMapper.selectList(new LambdaQueryWrapper<CageMigrationChain>()
                .eq(CageMigrationChain::getCurrentCageNo, cageNo)
                .or().eq(CageMigrationChain::getOriginCageNo, cageNo));
        current.forEach(c -> chainIds.add(c.getId()));
        List<CageMigration> touched = migrationMapper.selectList(new LambdaQueryWrapper<CageMigration>()
                .eq(CageMigration::getSourceCageNo, cageNo)
                .or().eq(CageMigration::getTargetCageNo, cageNo));
        touched.forEach(e -> chainIds.add(e.getChainId()));

        List<CageMigrationHistoryView> views = new ArrayList<>();
        for (Long chainId : chainIds) {
            CageMigrationChain chain = chainMapper.selectById(chainId);
            FishBatch batch = fishBatchMapper.selectById(chain.getBatchId());
            views.add(assembleHistory(chain, batch == null ? null : batch.getBatchNo()));
        }
        return views;
    }

    /** 解析迁移事件的交接快照 JSON。 */
    public com.fasterxml.jackson.databind.JsonNode snapshotTree(Long id) {
        CageMigration event = getById(id);
        try {
            return objectMapper.readTree(event.getSnapshotJson());
        } catch (JsonProcessingException ex) {
            throw new BusinessException("迁移快照解析失败: " + event.getMigrationNo());
        }
    }

    // ---------------- 链路组装与校验 ----------------

    private CageMigrationHistoryView assembleHistory(CageMigrationChain chain, String batchNo) {
        List<CageMigration> events = migrationMapper.selectList(new LambdaQueryWrapper<CageMigration>()
                .eq(CageMigration::getChainId, chain.getId())
                .orderByAsc(CageMigration::getSeqNo));

        CageMigrationHistoryView view = new CageMigrationHistoryView();
        view.setChainNo(chain.getChainNo());
        view.setBatchId(chain.getBatchId());
        view.setBatchNo(batchNo);
        view.setOriginCageNo(chain.getOriginCageNo());
        view.setCurrentCageNo(chain.getCurrentCageNo());
        view.setCurrentSeq(chain.getCurrentSeq());
        view.setEventCount(events.size());
        view.setEvents(events);

        // 1) 序号连续：1..N 无缺号，且与链头版本一致。
        boolean seqContinuous = events.size() == chain.getCurrentSeq();
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).getSeqNo() != i + 1) {
                seqContinuous = false;
                break;
            }
        }
        view.getChecks().add(CageMigrationHistoryView.CheckItem.of("SEQ_CONTINUOUS", seqContinuous,
                "迁移序号 1.." + chain.getCurrentSeq() + " 无缺号，事件数=" + events.size()));

        // 2) 网箱首尾相接：首事件源=链起点，逐事件 target=下一事件 source，末事件 target=链头。
        boolean linked = !events.isEmpty()
                && chain.getOriginCageNo().equals(events.get(0).getSourceCageNo())
                && chain.getCurrentCageNo().equals(events.get(events.size() - 1).getTargetCageNo());
        for (int i = 0; linked && i < events.size() - 1; i++) {
            if (!events.get(i).getTargetCageNo().equals(events.get(i + 1).getSourceCageNo())) {
                linked = false;
            }
        }
        view.getChecks().add(CageMigrationHistoryView.CheckItem.of("CAGE_LINKED", linked,
                "前一事件新网箱=后一事件原网箱，链起点/链头与事件首尾一致"));

        // 3) 时间严格递增：不可断裂的时间链。
        boolean timeOrdered = true;
        for (int i = 1; i < events.size(); i++) {
            if (!events.get(i).getOccurredAt().isAfter(events.get(i - 1).getOccurredAt())) {
                timeOrdered = false;
            }
        }
        view.getChecks().add(CageMigrationHistoryView.CheckItem.of("TIME_ORDERED", timeOrdered,
                "迁移发生时间沿链严格递增"));

        // 4) 事件内交接守恒：快照原对象结束值 == 新对象起始值（逐事件）。
        boolean handoffEqual = true;
        StringBuilder handoffDetail = new StringBuilder();
        for (CageMigration event : events) {
            CageMigrationSnapshot snap = readSnapshot(event);
            if (snap.getSource() == null || snap.getTarget() == null
                    || !same(snap.getSource().getFishEstimateCount(), snap.getTarget().getFishEstimateCount())
                    || !eq(snap.getSource().getCumulativePostedFeedKg(),
                           snap.getTarget().getCumulativePostedFeedKg())
                    || !eq(snap.getSource().getLatestSurvivalRate(),
                           snap.getTarget().getLatestSurvivalRate())
                    || !same(snap.getSource().getFeedingPlanCount(),
                             snap.getTarget().getFeedingPlanCount())
                    || !same(snap.getSource().getMovedSensors() == null ? 0 : snap.getSource().getMovedSensors().size(),
                             snap.getTarget().getMovedSensors() == null ? 0 : snap.getTarget().getMovedSensors().size())) {
                handoffEqual = false;
                handoffDetail.append(event.getMigrationNo()).append(" ");
            }
        }
        view.getChecks().add(CageMigrationHistoryView.CheckItem.of("HANDOFF_EQUAL", handoffEqual,
                handoffEqual ? "每个事件原对象结束值=新对象起始值（鱼量/累计投饵/存活率/计划/传感器）"
                        : "交接值不一致事件: " + handoffDetail.toString().trim()));

        // 5) 累计投饵量沿链单调不减（后续迁移只可能投饵更多）。
        boolean feedMonotonic = true;
        for (int i = 1; i < events.size(); i++) {
            BigDecimal prev = events.get(i - 1).getCumulativeFeedKg();
            BigDecimal cur = events.get(i).getCumulativeFeedKg();
            if (prev != null && cur != null && cur.compareTo(prev) < 0) {
                feedMonotonic = false;
            }
        }
        view.getChecks().add(CageMigrationHistoryView.CheckItem.of("FEED_MONOTONIC", feedMonotonic,
                "累计已落账投饵量沿时间链单调不减"));

        // 6) 快照完整可解析（不可回写，只读出核对）。
        boolean snapshotsReadable = true;
        for (CageMigration event : events) {
            if (readSnapshot(event) == null) {
                snapshotsReadable = false;
            }
        }
        view.getChecks().add(CageMigrationHistoryView.CheckItem.of("SNAPSHOT_READABLE", snapshotsReadable,
                "全部历史快照可解析、可逐项核对"));

        return view;
    }

    // ---------------- 内部辅助 ----------------

    private CageMigrationChain getOrCreateChain(FishBatch batch, String sourceCage) {
        CageMigrationChain chain = chainMapper.selectOne(new LambdaQueryWrapper<CageMigrationChain>()
                .eq(CageMigrationChain::getBatchId, batch.getId()));
        if (chain != null) {
            return chain;
        }
        chain = new CageMigrationChain();
        // 链编号由批次业务号派生：一批鱼一条链，编号稳定可读。
        chain.setChainNo("CHAIN-" + batch.getBatchNo());
        chain.setBatchId(batch.getId());
        chain.setOriginCageNo(sourceCage);
        chain.setCurrentCageNo(sourceCage);
        chain.setCurrentSeq(0);
        try {
            chainMapper.insert(chain);
            return chain;
        } catch (DuplicateKeyException dup) {
            // 两个操作者同时发起该批次首次迁移：UK(batch_id) 只允许一行，落败方回读。
            return chainMapper.selectOne(new LambdaQueryWrapper<CageMigrationChain>()
                    .eq(CageMigrationChain::getBatchId, batch.getId()));
        }
    }

    private CageMigrationSnapshot buildSnapshot(String migrationNo, String chainNo, int seq, MigrationType type,
                                                FishBatch batch, LocalDateTime occurredAt, String estimateBasis,
                                                String sourceCage, String targetCage, Integer fishEstimate,
                                                BigDecimal postedSum, BigDecimal unpostedSum, int recordCount,
                                                int postedCount, BigDecimal latestRate, String latestRateAt,
                                                List<FeedingPlan> plans,
                                                List<CageMigrationSnapshot.SensorSnapshot> moved,
                                                List<CageMigrationSnapshot.SensorSnapshot> retained) {
        CageMigrationSnapshot snap = new CageMigrationSnapshot();
        snap.setMigrationNo(migrationNo);
        snap.setChainNo(chainNo);
        snap.setSeqNo(seq);
        snap.setTransferType(type.name());
        snap.setBatchId(batch.getId());
        snap.setBatchNo(batch.getBatchNo());
        snap.setOccurredAt(occurredAt.format(TS));
        snap.setFishEstimateBasis(estimateBasis);

        CageMigrationSnapshot.Side src = new CageMigrationSnapshot.Side();
        src.setCageNo(sourceCage);
        src.setFishEstimateCount(fishEstimate);
        src.setCumulativePostedFeedKg(postedSum);
        src.setCumulativeUnpostedFeedKg(unpostedSum);
        src.setFeedingRecordCount(recordCount);
        src.setPostedRecordCount(postedCount);
        src.setLatestSurvivalRate(latestRate);
        src.setLatestSurvivalAt(latestRateAt);
        src.setFeedingPlanCount(plans.size());
        src.setFeedingPlans(planRefs(plans));
        src.setMovedSensors(moved);
        src.setRetainedSensors(retained);

        CageMigrationSnapshot.Side tgt = new CageMigrationSnapshot.Side();
        tgt.setCageNo(targetCage);
        // 新对象起始值逐项承接原对象结束值（迁移瞬时守恒）。
        tgt.setFishEstimateCount(fishEstimate);
        tgt.setCumulativePostedFeedKg(postedSum);
        tgt.setCumulativeUnpostedFeedKg(unpostedSum);
        tgt.setFeedingRecordCount(recordCount);
        tgt.setPostedRecordCount(postedCount);
        tgt.setLatestSurvivalRate(latestRate);
        tgt.setLatestSurvivalAt(latestRateAt);
        tgt.setFeedingPlanCount(plans.size());
        tgt.setFeedingPlans(planRefs(plans));
        tgt.setMovedSensors(moved);
        tgt.setRetainedSensors(new ArrayList<>());

        snap.setSource(src);
        snap.setTarget(tgt);
        return snap;
    }

    private List<CageMigrationSnapshot.PlanRef> planRefs(List<FeedingPlan> plans) {
        List<CageMigrationSnapshot.PlanRef> refs = new ArrayList<>();
        for (FeedingPlan plan : plans) {
            CageMigrationSnapshot.PlanRef ref = new CageMigrationSnapshot.PlanRef();
            ref.setId(plan.getId());
            ref.setPlanNo(plan.getPlanNo());
            ref.setStatus(plan.getStatus());
            ref.setDailyAmountKg(plan.getDailyAmountKg());
            ref.setVersion(plan.getVersion() == null ? 0 : plan.getVersion());
            refs.add(ref);
        }
        return refs;
    }

    private List<VersionRef> versionRefs(List<FeedingPlan> plans) {
        List<VersionRef> refs = new ArrayList<>();
        for (FeedingPlan plan : plans) {
            refs.add(new VersionRef(plan.getId(), plan.getVersion() == null ? 0 : plan.getVersion()));
        }
        return refs;
    }

    private List<SensorVersionRef> sensorVersionRefs(List<UnderwaterSensor> sensors) {
        List<SensorVersionRef> refs = new ArrayList<>();
        for (UnderwaterSensor sensor : sensors) {
            refs.add(new SensorVersionRef(sensor.getId(),
                    sensor.getVersion() == null ? 0 : sensor.getVersion(),
                    sensor.getCageNo(), sensor.getStatus(),
                    SensorStatus.ONLINE.name().equals(sensor.getStatus())));
        }
        return refs;
    }

    private CageMigrationSnapshot readSnapshot(CageMigration event) {
        try {
            return objectMapper.readValue(event.getSnapshotJson(), CageMigrationSnapshot.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("迁移快照序列化失败: " + ex.getMessage());
        }
    }

    /**
     * 未完成投饵计划集合是否一致（id 相同且各自 version 未变）。
     * 迁移在批次行锁内复查：锁等待期间若有计划被暂停/结束（version+1 或移出未完成集合），
     * 返回 false，迁移失败，实现“转场与未完成投饵计划并发仅一个版本生效”。
     */
    private static boolean samePlanSet(List<FeedingPlan> before, List<FeedingPlan> after) {
        if (before.size() != after.size()) {
            return false;
        }
        java.util.Map<Long, Integer> beforeVersions = new java.util.HashMap<>();
        for (FeedingPlan p : before) {
            beforeVersions.put(p.getId(), p.getVersion() == null ? 0 : p.getVersion());
        }
        for (FeedingPlan p : after) {
            Integer v = beforeVersions.get(p.getId());
            if (v == null || v != (p.getVersion() == null ? 0 : p.getVersion())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTerminal(FishBatch batch) {        return BatchStatus.HARVESTED.name().equals(batch.getStatus())
                || BatchStatus.CLOSED.name().equals(batch.getStatus());
    }

    private static boolean same(Integer a, Integer b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean eq(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return a.compareTo(b) == 0;
    }

    /** 版本凭证 JSON 结构（投饵计划）。 */
    @lombok.Data
    @lombok.AllArgsConstructor
    private static class VersionRef {
        private Long id;
        private Integer version;
    }

    /** 版本凭证 JSON 结构（传感器，附迁移决策现场）。 */
    @lombok.Data
    @lombok.AllArgsConstructor
    private static class SensorVersionRef {
        private Long id;
        private Integer version;
        private String cageNo;
        private String status;
        private Boolean moved;
    }
}
