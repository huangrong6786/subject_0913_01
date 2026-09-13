package com.evops.aquaculture.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.aquaculture.dto.FeedingPlanCreateRequest;
import com.evops.aquaculture.dto.PlanStatusRequest;
import com.evops.aquaculture.entity.FeedingPlan;
import com.evops.aquaculture.entity.FishBatch;
import com.evops.aquaculture.enums.PlanStatus;
import com.evops.aquaculture.mapper.FeedingPlanMapper;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class FeedingPlanService {

    private final FeedingPlanMapper feedingPlanMapper;
    private final FishBatchService fishBatchService;

    public FeedingPlanService(FeedingPlanMapper feedingPlanMapper, FishBatchService fishBatchService) {
        this.feedingPlanMapper = feedingPlanMapper;
        this.fishBatchService = fishBatchService;
    }

    @Transactional
    public FeedingPlan create(FeedingPlanCreateRequest request) {
        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new BusinessException("投饵计划结束日期不能早于开始日期");
        }
        // 关联批次必须存在，并继承网箱编号作为关联查询键。
        FishBatch batch = fishBatchService.getById(request.getBatchId());

        FeedingPlan existing = feedingPlanMapper.selectOne(new LambdaQueryWrapper<FeedingPlan>()
                .eq(FeedingPlan::getPlanNo, request.getPlanNo()));
        if (existing != null) {
            throw new BusinessException("投饵计划编号已存在: " + request.getPlanNo());
        }

        FeedingPlan plan = new FeedingPlan();
        plan.setPlanNo(request.getPlanNo());
        plan.setBatchId(batch.getId());
        plan.setCageNo(batch.getCageNo());
        plan.setFeedType(request.getFeedType());
        plan.setDailyAmountKg(request.getDailyAmountKg());
        plan.setFeedFrequency(request.getFeedFrequency());
        plan.setStartDate(request.getStartDate());
        plan.setEndDate(request.getEndDate());
        plan.setStatus(PlanStatus.ACTIVE.name());
        feedingPlanMapper.insert(plan);
        return plan;
    }

    public FeedingPlan getById(Long id) {
        FeedingPlan plan = feedingPlanMapper.selectById(id);
        if (plan == null) {
            throw new BusinessException("投饵计划不存在: " + id);
        }
        return plan;
    }

    public List<FeedingPlan> list(Long batchId, String cageNo, String status) {
        return feedingPlanMapper.selectList(new LambdaQueryWrapper<FeedingPlan>()
                .eq(batchId != null, FeedingPlan::getBatchId, batchId)
                .eq(cageNo != null && !cageNo.isEmpty(), FeedingPlan::getCageNo, cageNo)
                .eq(status != null && !status.isEmpty(), FeedingPlan::getStatus, status)
                .orderByDesc(FeedingPlan::getCreateTime));
    }

    @Transactional
    public FeedingPlan transitStatus(Long id, PlanStatusRequest request) {
        FeedingPlan plan = getById(id);
        PlanStatus current;
        PlanStatus target;
        try {
            current = PlanStatus.valueOf(plan.getStatus());
            target = PlanStatus.valueOf(request.getTargetStatus());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("非法的计划状态: " + request.getTargetStatus()
                    + "，可选 ACTIVE/SUSPENDED/FINISHED");
        }
        if (!current.canTransitTo(target)) {
            throw new BusinessException("非法状态流转: " + current + " -> " + target);
        }
        plan.setStatus(target.name());
        feedingPlanMapper.updateById(plan);
        return plan;
    }
}
