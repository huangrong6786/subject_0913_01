package com.evops.aquaculture.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.aquaculture.dto.FeedingRecordCreateRequest;
import com.evops.aquaculture.entity.FeedingPlan;
import com.evops.aquaculture.entity.FeedingRecord;
import com.evops.aquaculture.entity.FishBatch;
import com.evops.aquaculture.enums.PlanStatus;
import com.evops.aquaculture.mapper.FeedingRecordMapper;
import com.evops.aquaculture.mapper.FeedingPlanMapper;
import com.evops.aquaculture.mapper.FishBatchMapper;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class FeedingRecordService {

    private final FeedingRecordMapper feedingRecordMapper;
    private final FeedingPlanMapper feedingPlanMapper;
    private final FishBatchMapper fishBatchMapper;
    private final FishBatchService fishBatchService;
    private final FeedingPlanService feedingPlanService;

    public FeedingRecordService(FeedingRecordMapper feedingRecordMapper,
                                FeedingPlanMapper feedingPlanMapper,
                                FishBatchMapper fishBatchMapper,
                                FishBatchService fishBatchService,
                                FeedingPlanService feedingPlanService) {
        this.feedingRecordMapper = feedingRecordMapper;
        this.feedingPlanMapper = feedingPlanMapper;
        this.fishBatchMapper = fishBatchMapper;
        this.fishBatchService = fishBatchService;
        this.feedingPlanService = feedingPlanService;
    }

    /** 登记一次实际投饵：必须关联在养批次；如指定计划则校验计划生效中且批次一致。 */
    @Transactional
    public FeedingRecord create(FeedingRecordCreateRequest request) {
        FishBatch batch = fishBatchService.getById(request.getBatchId());

        FeedingRecord existing = feedingRecordMapper.selectOne(new LambdaQueryWrapper<FeedingRecord>()
                .eq(FeedingRecord::getRecordNo, request.getRecordNo()));
        if (existing != null) {
            throw new BusinessException("投饵流水号已存在: " + request.getRecordNo());
        }

        // 取批次行锁与换箱/转场串行化：迁移持锁期间投饵登记等待，
        // 锁后重读批次/计划，保证迁移后新投饵落新网箱，不会遗留在旧箱。
        batch = fishBatchMapper.selectForUpdate(batch.getId());
        String cageNo = batch.getCageNo();
        FeedingPlan plan = null;
        if (request.getPlanId() != null) {
            plan = feedingPlanMapper.selectById(request.getPlanId());
            if (plan == null) {
                throw new BusinessException("投饵计划不存在: " + request.getPlanId());
            }
            if (!plan.getBatchId().equals(batch.getId())) {
                throw new BusinessException("投饵计划与鱼苗批次不匹配");
            }
            if (!PlanStatus.ACTIVE.name().equals(plan.getStatus())) {
                throw new BusinessException("投饵计划非生效中状态，不能登记投饵: " + plan.getPlanNo());
            }
            cageNo = plan.getCageNo();
        }

        FeedingRecord record = new FeedingRecord();
        record.setRecordNo(request.getRecordNo());
        record.setBatchId(batch.getId());
        record.setPlanId(request.getPlanId());
        record.setCageNo(cageNo);
        record.setFeedType(request.getFeedType());
        record.setAmountKg(request.getAmountKg());
        record.setFeedingTime(request.getFeedingTime());
        record.setPosted(0);
        // 锚定当前迁移段：迁移后新投饵自然落在新网箱，并可按迁移事件切分时间轴。
        record.setMigrationId(batch.getMigrationId());
        feedingRecordMapper.insert(record);
        return record;
    }

    public FeedingRecord getById(Long id) {
        FeedingRecord record = feedingRecordMapper.selectById(id);
        if (record == null) {
            throw new BusinessException("投饵记录不存在: " + id);
        }
        return record;
    }

    public List<FeedingRecord> list(Long batchId, String cageNo, Integer posted,
                                    LocalDateTime startTime, LocalDateTime endTime) {
        return feedingRecordMapper.selectList(new LambdaQueryWrapper<FeedingRecord>()
                .eq(batchId != null, FeedingRecord::getBatchId, batchId)
                .eq(cageNo != null && !cageNo.isEmpty(), FeedingRecord::getCageNo, cageNo)
                .eq(posted != null, FeedingRecord::getPosted, posted)
                .ge(startTime != null, FeedingRecord::getFeedingTime, startTime)
                .le(endTime != null, FeedingRecord::getFeedingTime, endTime)
                .orderByDesc(FeedingRecord::getFeedingTime));
    }

    /** 落账：投饵量进入统计口径，落账后不可删除、不可修改。 */
    @Transactional
    public FeedingRecord post(Long id) {
        FeedingRecord record = getById(id);
        LocalDateTime now = LocalDateTime.now();
        // 原子条件更新：并发重复落账时只有一方 affected=1，另一方按"已落账"拒绝。
        int affected = feedingRecordMapper.markPostedIfPending(id, now);
        if (affected == 0) {
            throw new BusinessException("投饵记录已落账，不能重复落账: " + record.getRecordNo());
        }
        record.setPosted(1);
        record.setPostedTime(now);
        return record;
    }

    /**
     * 批量自动落账（定时任务/手工触发共用）：扫描所有未落账记录逐条原子落账。
     * 故意不加整体事务：每条 CAS 更新独立提交，配合调用方的周期幂等台账构成
     * "至少一次执行、副作用只生效一次"——执行方在批量中途异常退出后重试，
     * 已落账记录 affected=0 被跳过，仅处理剩余记录，返回本次实际落账条数。
     */
    public int postPending() {
        List<FeedingRecord> pending = feedingRecordMapper.selectList(new LambdaQueryWrapper<FeedingRecord>()
                .eq(FeedingRecord::getPosted, 0)
                .orderByAsc(FeedingRecord::getId));
        LocalDateTime now = LocalDateTime.now();
        int posted = 0;
        for (FeedingRecord record : pending) {
            posted += feedingRecordMapper.markPostedIfPending(record.getId(), now);
        }
        return posted;
    }

    /** 已落账记录不能直接删除。 */
    @Transactional
    public void delete(Long id) {
        FeedingRecord record = getById(id);
        if (record.getPosted() == 1) {
            throw new BusinessException("投饵记录已落账，不能删除: " + record.getRecordNo());
        }
        feedingRecordMapper.deleteById(id);
    }

    /** 批次投饵量合计（默认只统计已落账记录）。 */
    public BigDecimal sumPostedAmountKg(Long batchId) {
        List<FeedingRecord> records = feedingRecordMapper.selectList(new LambdaQueryWrapper<FeedingRecord>()
                .eq(FeedingRecord::getBatchId, batchId)
                .eq(FeedingRecord::getPosted, 1));
        return records.stream()
                .map(FeedingRecord::getAmountKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
