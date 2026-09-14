package com.evops.aquaculture.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.aquaculture.dto.FeedObservationCreateRequest;
import com.evops.aquaculture.entity.FeedObservation;
import com.evops.aquaculture.entity.FishBatch;
import com.evops.aquaculture.mapper.FeedObservationMapper;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 投饵监测观测登记：观测时刻按 UTC 存储，网箱归属由养殖批次带出，
 * 时序规则计算时按网箱海区时区归算。
 */
@Service
public class FeedObservationService {

    private final FeedObservationMapper feedObservationMapper;
    private final FishBatchService fishBatchService;

    public FeedObservationService(FeedObservationMapper feedObservationMapper,
                                  FishBatchService fishBatchService) {
        this.feedObservationMapper = feedObservationMapper;
        this.fishBatchService = fishBatchService;
    }

    /** 登记观测：必须关联在册养殖批次；obsNo 为关键业务键必须唯一。 */
    @Transactional
    public FeedObservation create(FeedObservationCreateRequest request) {
        FishBatch batch = fishBatchService.getById(request.getBatchId());
        FeedObservation existing = feedObservationMapper.selectOne(new LambdaQueryWrapper<FeedObservation>()
                .eq(FeedObservation::getObsNo, request.getObsNo()));
        if (existing != null) {
            throw new BusinessException("观测编号已存在: " + request.getObsNo());
        }
        FeedObservation entity = new FeedObservation();
        entity.setObsNo(request.getObsNo());
        entity.setBatchId(batch.getId());
        entity.setCageNo(batch.getCageNo());
        entity.setObservedAtUtc(request.getObservedAtUtc());
        entity.setFeedAmountKg(request.getFeedAmountKg());
        feedObservationMapper.insert(entity);
        return entity;
    }

    public FeedObservation getById(Long id) {
        FeedObservation entity = feedObservationMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("投饵监测观测不存在: " + id);
        }
        return entity;
    }

    public List<FeedObservation> list(Long batchId, String cageNo) {
        return feedObservationMapper.selectList(new LambdaQueryWrapper<FeedObservation>()
                .eq(batchId != null, FeedObservation::getBatchId, batchId)
                .eq(cageNo != null && !cageNo.isEmpty(), FeedObservation::getCageNo, cageNo)
                .orderByAsc(FeedObservation::getObservedAtUtc));
    }
}
