package com.evops.aquaculture.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.aquaculture.dto.BatchStatusRequest;
import com.evops.aquaculture.dto.HarvestBatchCreateRequest;
import com.evops.aquaculture.entity.FishBatch;
import com.evops.aquaculture.entity.HarvestBatch;
import com.evops.aquaculture.enums.BatchStatus;
import com.evops.aquaculture.enums.HarvestStatus;
import com.evops.aquaculture.mapper.HarvestBatchMapper;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class HarvestBatchService {

    private final HarvestBatchMapper harvestBatchMapper;
    private final FishBatchService fishBatchService;

    public HarvestBatchService(HarvestBatchMapper harvestBatchMapper,
                               FishBatchService fishBatchService) {
        this.harvestBatchMapper = harvestBatchMapper;
        this.fishBatchService = fishBatchService;
    }

    /** 建立出网批次（待验收）：来源批次必须存在且未关闭，出网数量不得超过投苗数量。 */
    @Transactional
    public HarvestBatch create(HarvestBatchCreateRequest request) {
        FishBatch batch = fishBatchService.getById(request.getFishBatchId());
        if (BatchStatus.CLOSED.name().equals(batch.getStatus())) {
            throw new BusinessException("鱼苗批次已关闭，不能再出网: " + batch.getBatchNo());
        }
        if (request.getHarvestCount() > batch.getFingerlingCount()) {
            throw new BusinessException("出网数量不能超过投苗数量: " + batch.getFingerlingCount());
        }

        HarvestBatch existing = harvestBatchMapper.selectOne(new LambdaQueryWrapper<HarvestBatch>()
                .eq(HarvestBatch::getHarvestNo, request.getHarvestNo()));
        if (existing != null) {
            throw new BusinessException("出网单号已存在: " + request.getHarvestNo());
        }

        HarvestBatch harvest = new HarvestBatch();
        harvest.setHarvestNo(request.getHarvestNo());
        harvest.setFishBatchId(batch.getId());
        harvest.setCageNo(batch.getCageNo());
        harvest.setHarvestCount(request.getHarvestCount());
        harvest.setTotalWeightKg(request.getTotalWeightKg());
        harvest.setHarvestTime(request.getHarvestTime());
        harvest.setRemark(request.getRemark());
        harvest.setStatus(HarvestStatus.PENDING.name());
        harvestBatchMapper.insert(harvest);
        return harvest;
    }

    public HarvestBatch getById(Long id) {
        HarvestBatch harvest = harvestBatchMapper.selectById(id);
        if (harvest == null) {
            throw new BusinessException("出网批次不存在: " + id);
        }
        return harvest;
    }

    public List<HarvestBatch> list(Long fishBatchId, String cageNo, String status) {
        return harvestBatchMapper.selectList(new LambdaQueryWrapper<HarvestBatch>()
                .eq(fishBatchId != null, HarvestBatch::getFishBatchId, fishBatchId)
                .eq(cageNo != null && !cageNo.isEmpty(), HarvestBatch::getCageNo, cageNo)
                .eq(status != null && !status.isEmpty(), HarvestBatch::getStatus, status)
                .orderByDesc(HarvestBatch::getHarvestTime));
    }

    /** 验收：PENDING -> ACCEPTED（终态），同时把鱼苗批次流转为已出网。 */
    @Transactional
    public HarvestBatch accept(Long id) {
        HarvestBatch harvest = getById(id);
        if (HarvestStatus.ACCEPTED.name().equals(harvest.getStatus())) {
            throw new BusinessException("出网批次已验收，不能重复验收: " + harvest.getHarvestNo());
        }
        harvest.setStatus(HarvestStatus.ACCEPTED.name());
        harvest.setAcceptedTime(LocalDateTime.now());
        harvestBatchMapper.updateById(harvest);

        BatchStatusRequest toHarvested = new BatchStatusRequest();
        toHarvested.setTargetStatus(BatchStatus.HARVESTED.name());
        fishBatchService.transitStatus(harvest.getFishBatchId(), toHarvested);
        return harvest;
    }

    /** 已验收的出网批次不能直接删除。 */
    @Transactional
    public void delete(Long id) {
        HarvestBatch harvest = getById(id);
        if (HarvestStatus.ACCEPTED.name().equals(harvest.getStatus())) {
            throw new BusinessException("出网批次已验收，不能删除: " + harvest.getHarvestNo());
        }
        harvestBatchMapper.deleteById(id);
    }
}
