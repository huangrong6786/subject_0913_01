package com.evops.aquaculture.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.aquaculture.dto.SurvivalReportCreateRequest;
import com.evops.aquaculture.entity.FishBatch;
import com.evops.aquaculture.entity.SurvivalReport;
import com.evops.aquaculture.mapper.FishBatchMapper;
import com.evops.aquaculture.mapper.SurvivalReportMapper;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class SurvivalReportService {

    private final SurvivalReportMapper survivalReportMapper;
    private final FishBatchMapper fishBatchMapper;
    private final FishBatchService fishBatchService;

    public SurvivalReportService(SurvivalReportMapper survivalReportMapper,
                                 FishBatchMapper fishBatchMapper,
                                 FishBatchService fishBatchService) {
        this.survivalReportMapper = survivalReportMapper;
        this.fishBatchMapper = fishBatchMapper;
        this.fishBatchService = fishBatchService;
    }

    /** 登记存活率报告；存活率缺省时按 存活数/投苗数 计算，存活数不得超过投苗数。 */
    @Transactional
    public SurvivalReport create(SurvivalReportCreateRequest request) {
        FishBatch batch = fishBatchService.getById(request.getBatchId());
        if (request.getAliveCount() > batch.getFingerlingCount()) {
            throw new BusinessException("存活数量不能超过投苗数量: " + batch.getFingerlingCount());
        }

        SurvivalReport existing = survivalReportMapper.selectOne(new LambdaQueryWrapper<SurvivalReport>()
                .eq(SurvivalReport::getReportNo, request.getReportNo()));
        if (existing != null) {
            throw new BusinessException("存活率报告编号已存在: " + request.getReportNo());
        }

        // 取批次行锁与换箱/转场串行化：迁移持锁期间盘点等待，锁后重读批次，
        // 保证新盘点报告随鱼群落位当前网箱，鱼群估算量前后衔接。
        batch = fishBatchMapper.selectForUpdate(batch.getId());

        BigDecimal rate = request.getSurvivalRate();
        if (rate == null) {
            rate = new BigDecimal(batch.getFingerlingCount()).compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : new BigDecimal(request.getAliveCount())
                            .divide(new BigDecimal(batch.getFingerlingCount()), 4, RoundingMode.HALF_UP);
        }

        SurvivalReport report = new SurvivalReport();
        report.setReportNo(request.getReportNo());
        report.setBatchId(batch.getId());
        report.setCageNo(batch.getCageNo());
        report.setAliveCount(request.getAliveCount());
        report.setSurvivalRate(rate);
        report.setReportTime(request.getReportTime());
        report.setRemark(request.getRemark());
        survivalReportMapper.insert(report);
        return report;
    }

    public List<SurvivalReport> list(Long batchId, String cageNo) {
        return survivalReportMapper.selectList(new LambdaQueryWrapper<SurvivalReport>()
                .eq(batchId != null, SurvivalReport::getBatchId, batchId)
                .eq(cageNo != null && !cageNo.isEmpty(), SurvivalReport::getCageNo, cageNo)
                .orderByDesc(SurvivalReport::getReportTime));
    }

    /** 批次最新一期存活率报告。 */
    public SurvivalReport latestByBatch(Long batchId) {
        return survivalReportMapper.selectOne(new LambdaQueryWrapper<SurvivalReport>()
                .eq(SurvivalReport::getBatchId, batchId)
                .orderByDesc(SurvivalReport::getReportTime)
                .last("LIMIT 1"));
    }
}
