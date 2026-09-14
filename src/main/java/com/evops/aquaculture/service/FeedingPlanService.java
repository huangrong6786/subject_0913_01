package com.evops.aquaculture.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.aquaculture.dto.FeedingPlanCreateRequest;
import com.evops.aquaculture.dto.PlanStatusRequest;
import com.evops.aquaculture.entity.FeedingPlan;
import com.evops.aquaculture.entity.FishBatch;
import com.evops.aquaculture.enums.PlanStatus;
import com.evops.aquaculture.mapper.FeedingPlanMapper;
import com.evops.aquaculture.mapper.FishBatchMapper;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class FeedingPlanService {

    private final FeedingPlanMapper feedingPlanMapper;
    private final FishBatchMapper fishBatchMapper;
    private final FishBatchService fishBatchService;

    public FeedingPlanService(FeedingPlanMapper feedingPlanMapper,
                              FishBatchMapper fishBatchMapper,
                              FishBatchService fishBatchService) {
        this.feedingPlanMapper = feedingPlanMapper;
        this.fishBatchMapper = fishBatchMapper;
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

    /**
     * 计划状态流转（乐观锁 CAS）。
     *
     * 同一事务内做两个条件更新：
     *  1. 计划自身 version 上的状态 CAS；
     *  2. 鱼群（批次聚合根）version 的推进 CAS——与换箱/转场共享同一版本计数器。
     * 取批次行锁与迁移串行化；两个操作者基于同一旧版本并发提交（迁移 vs 计划状态变更）时，
     * 只有一方的批次版本 CAS 匹配成功，另一方整体回滚，满足“转场与未完成投饵计划并发仅一个版本生效”。
     */
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
        int expectedPlanVersion = request.getExpectedVersion() == null
                ? (plan.getVersion() == null ? 0 : plan.getVersion()) : request.getExpectedVersion();
        // 期望鱼群版本：优先用操作者决策时看到的版本（乐观锁的比较基准必须来自决策时刻），
        // 缺省取服务端当前值。
        FishBatch batch = fishBatchService.getById(plan.getBatchId());
        int expectedBatchVersion = request.getExpectedBatchVersion() == null
                ? (batch.getVersion() == null ? 0 : batch.getVersion())
                : request.getExpectedBatchVersion();

        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        // 行锁串行化 + 条件更新：任一失配整体回滚。
        fishBatchMapper.selectForUpdate(plan.getBatchId());
        int batchBumped = fishBatchMapper.bumpVersionIfVersion(plan.getBatchId(), expectedBatchVersion, now);
        if (batchBumped == 0) {
            throw new BusinessException("鱼群刚被并发处理（网箱转移或其他变更），投饵计划状态变更失败: "
                    + plan.getPlanNo());
        }
        int affected = feedingPlanMapper.updateStatusIfVersion(id, target.name(), expectedPlanVersion, now);
        if (affected == 0) {
            throw new BusinessException("投饵计划刚被并发处理（网箱转移或其他状态变更），本次操作失败: "
                    + plan.getPlanNo());
        }
        plan.setStatus(target.name());
        plan.setVersion(expectedPlanVersion + 1);
        return plan;
    }
}
