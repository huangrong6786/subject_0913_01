package com.evops.aquaculture.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.aquaculture.dto.BatchStatusRequest;
import com.evops.aquaculture.dto.FishBatchCreateRequest;
import com.evops.aquaculture.entity.FishBatch;
import com.evops.aquaculture.entity.HarvestBatch;
import com.evops.aquaculture.entity.FeedingRecord;
import com.evops.aquaculture.enums.BatchStatus;
import com.evops.aquaculture.enums.HarvestStatus;
import com.evops.aquaculture.mapper.FeedingRecordMapper;
import com.evops.aquaculture.mapper.FishBatchMapper;
import com.evops.aquaculture.mapper.HarvestBatchMapper;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class FishBatchService {

    private final FishBatchMapper fishBatchMapper;
    private final HarvestBatchMapper harvestBatchMapper;
    private final FeedingRecordMapper feedingRecordMapper;

    public FishBatchService(FishBatchMapper fishBatchMapper,
                            HarvestBatchMapper harvestBatchMapper,
                            FeedingRecordMapper feedingRecordMapper) {
        this.fishBatchMapper = fishBatchMapper;
        this.harvestBatchMapper = harvestBatchMapper;
        this.feedingRecordMapper = feedingRecordMapper;
    }

    /** 建立鱼苗批次，batchNo 为关键业务键必须唯一。 */
    @Transactional
    public FishBatch create(FishBatchCreateRequest request) {
        FishBatch existing = fishBatchMapper.selectOne(new LambdaQueryWrapper<FishBatch>()
                .eq(FishBatch::getBatchNo, request.getBatchNo()));
        if (existing != null) {
            throw new BusinessException("批次编号已存在: " + request.getBatchNo());
        }

        FishBatch batch = new FishBatch();
        batch.setBatchNo(request.getBatchNo());
        batch.setCageNo(request.getCageNo());
        batch.setSpecies(request.getSpecies());
        batch.setFingerlingCount(request.getFingerlingCount());
        batch.setAverageWeightG(request.getAverageWeightG());
        batch.setStockingTime(request.getStockingTime());
        batch.setRemark(request.getRemark());
        batch.setStatus(BatchStatus.BREEDING.name());
        fishBatchMapper.insert(batch);
        return batch;
    }

    public FishBatch getById(Long id) {
        FishBatch batch = fishBatchMapper.selectById(id);
        if (batch == null) {
            throw new BusinessException("鱼苗批次不存在: " + id);
        }
        return batch;
    }

    public FishBatch getByNo(String batchNo) {
        FishBatch batch = fishBatchMapper.selectOne(new LambdaQueryWrapper<FishBatch>()
                .eq(FishBatch::getBatchNo, batchNo));
        if (batch == null) {
            throw new BusinessException("鱼苗批次不存在: " + batchNo);
        }
        return batch;
    }

    public List<FishBatch> list(String cageNo, String status) {
        LambdaQueryWrapper<FishBatch> wrapper = new LambdaQueryWrapper<FishBatch>()
                .eq(cageNo != null && !cageNo.isEmpty(), FishBatch::getCageNo, cageNo)
                .eq(status != null && !status.isEmpty(), FishBatch::getStatus, status)
                .orderByDesc(FishBatch::getStockingTime);
        return fishBatchMapper.selectList(wrapper);
    }

    /**
     * 批次状态流转，仅允许枚举中定义的合法路径。
     * 条件更新（乐观锁 CAS）：与网箱迁移共用 version，状态改写期间发生迁移（或反过来）时，
     * 后到一方版本失配失败，保证迁移与状态流转只有一个版本生效。
     */
    @Transactional
    public FishBatch transitStatus(Long id, BatchStatusRequest request) {
        FishBatch batch = getById(id);
        BatchStatus current;
        BatchStatus target;
        try {
            current = BatchStatus.valueOf(batch.getStatus());
            target = BatchStatus.valueOf(request.getTargetStatus());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("非法的批次状态: " + request.getTargetStatus()
                    + "，可选 BREEDING/MONITORING/HARVESTED/CLOSED");
        }
        if (!current.canTransitTo(target)) {
            throw new BusinessException("非法状态流转: " + current + " -> " + target);
        }
        int expected = batch.getVersion() == null ? 0 : batch.getVersion();
        int affected = fishBatchMapper.updateStatusIfVersion(
                id, target.name(), expected, java.time.LocalDateTime.now());
        if (affected == 0) {
            throw new BusinessException("批次刚被并发改写（网箱迁移或其他状态流转），本次状态变更失败: "
                    + batch.getBatchNo());
        }
        batch.setStatus(target.name());
        batch.setVersion(expected + 1);
        return batch;
    }

    /**
     * 删除批次。存在已落账投饵记录或已验收出网单（均为终态凭证）的批次不可删除，
     * 防止落账/验收数据成为孤儿记录。
     */
    @Transactional
    public void delete(Long id) {
        FishBatch batch = getById(id);
        Long acceptedCount = harvestBatchMapper.selectCount(new LambdaQueryWrapper<HarvestBatch>()
                .eq(HarvestBatch::getFishBatchId, id)
                .eq(HarvestBatch::getStatus, HarvestStatus.ACCEPTED.name()));
        if (acceptedCount != null && acceptedCount > 0) {
            throw new BusinessException("批次已存在已验收的出网单，不能删除: " + batch.getBatchNo());
        }
        Long postedCount = feedingRecordMapper.selectCount(new LambdaQueryWrapper<FeedingRecord>()
                .eq(FeedingRecord::getBatchId, id)
                .eq(FeedingRecord::getPosted, 1));
        if (postedCount != null && postedCount > 0) {
            throw new BusinessException("批次已存在已落账的投饵记录，不能删除: " + batch.getBatchNo());
        }
        fishBatchMapper.deleteById(id);
    }
}
