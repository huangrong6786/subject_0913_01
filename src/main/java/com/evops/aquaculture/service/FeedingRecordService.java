package com.evops.aquaculture.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.aquaculture.dto.FeedingRecordCreateRequest;
import com.evops.aquaculture.entity.FeedingPlan;
import com.evops.aquaculture.entity.FeedingRecord;
import com.evops.aquaculture.entity.FishBatch;
import com.evops.aquaculture.enums.PlanStatus;
import com.evops.aquaculture.mapper.FeedingRecordMapper;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class FeedingRecordService {

    private final FeedingRecordMapper feedingRecordMapper;
    private final FishBatchService fishBatchService;
    private final FeedingPlanService feedingPlanService;

    public FeedingRecordService(FeedingRecordMapper feedingRecordMapper,
                                FishBatchService fishBatchService,
                                FeedingPlanService feedingPlanService) {
        this.feedingRecordMapper = feedingRecordMapper;
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

        String cageNo = batch.getCageNo();
        if (request.getPlanId() != null) {
            FeedingPlan plan = feedingPlanService.getById(request.getPlanId());
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
        if (record.getPosted() == 1) {
            throw new BusinessException("投饵记录已落账，不能重复落账: " + record.getRecordNo());
        }
        record.setPosted(1);
        record.setPostedTime(LocalDateTime.now());
        feedingRecordMapper.updateById(record);
        return record;
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
