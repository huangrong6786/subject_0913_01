package com.evops.aquaculture.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.aquaculture.dto.CageOverview;
import com.evops.aquaculture.entity.FeedingRecord;
import com.evops.aquaculture.entity.FishBatch;
import com.evops.aquaculture.entity.HarvestBatch;
import com.evops.aquaculture.entity.SensorReading;
import com.evops.aquaculture.entity.SurvivalReport;
import com.evops.aquaculture.entity.UnderwaterSensor;
import com.evops.aquaculture.mapper.FeedingRecordMapper;
import com.evops.aquaculture.mapper.FishBatchMapper;
import com.evops.aquaculture.mapper.HarvestBatchMapper;
import com.evops.aquaculture.mapper.SensorReadingMapper;
import com.evops.aquaculture.mapper.SurvivalReportMapper;
import com.evops.aquaculture.mapper.UnderwaterSensorMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网箱 / 批次维度的关联查询：把投饵量、存活率、传感器读数、出网批次
 * 通过 cage_no 与 batch_id 关联键聚合到统一视图。
 */
@Service
public class CageQueryService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FishBatchMapper fishBatchMapper;
    private final UnderwaterSensorMapper sensorMapper;
    private final SensorReadingMapper readingMapper;
    private final FeedingRecordMapper feedingRecordMapper;
    private final SurvivalReportMapper survivalReportMapper;
    private final HarvestBatchMapper harvestBatchMapper;

    public CageQueryService(FishBatchMapper fishBatchMapper,
                            UnderwaterSensorMapper sensorMapper,
                            SensorReadingMapper readingMapper,
                            FeedingRecordMapper feedingRecordMapper,
                            SurvivalReportMapper survivalReportMapper,
                            HarvestBatchMapper harvestBatchMapper) {
        this.fishBatchMapper = fishBatchMapper;
        this.sensorMapper = sensorMapper;
        this.readingMapper = readingMapper;
        this.feedingRecordMapper = feedingRecordMapper;
        this.survivalReportMapper = survivalReportMapper;
        this.harvestBatchMapper = harvestBatchMapper;
    }

    /** 单个网箱监测总览。 */
    public CageOverview overview(String cageNo) {
        CageOverview overview = new CageOverview();
        overview.setCageNo(cageNo);

        List<FishBatch> batches = fishBatchMapper.selectList(new LambdaQueryWrapper<FishBatch>()
                .eq(FishBatch::getCageNo, cageNo)
                .ne(FishBatch::getStatus, "CLOSED")
                .orderByDesc(FishBatch::getStockingTime));

        List<CageOverview.FishBatchView> batchViews = new ArrayList<>();
        BigDecimal cageFeedTotal = BigDecimal.ZERO;
        for (FishBatch batch : batches) {
            CageOverview.FishBatchView view = new CageOverview.FishBatchView();
            view.setBatchId(batch.getId());
            view.setBatchNo(batch.getBatchNo());
            view.setSpecies(batch.getSpecies());
            view.setFingerlingCount(batch.getFingerlingCount());
            view.setStatus(batch.getStatus());

            BigDecimal batchFeed = sumPostedFeed(batch.getId(), null);
            view.setPostedFeedAmountKg(batchFeed);
            cageFeedTotal = cageFeedTotal.add(batchFeed);

            SurvivalReport latest = survivalReportMapper.selectOne(new LambdaQueryWrapper<SurvivalReport>()
                    .eq(SurvivalReport::getBatchId, batch.getId())
                    .orderByDesc(SurvivalReport::getReportTime)
                    .last("LIMIT 1"));
            view.setLatestSurvivalRate(latest == null ? null : latest.getSurvivalRate());
            batchViews.add(view);
        }
        overview.setBatches(batchViews);
        overview.setPostedFeedAmountKg(cageFeedTotal);

        List<UnderwaterSensor> sensors = sensorMapper.selectList(new LambdaQueryWrapper<UnderwaterSensor>()
                .eq(UnderwaterSensor::getCageNo, cageNo)
                .orderByAsc(UnderwaterSensor::getSensorNo));
        List<CageOverview.SensorView> sensorViews = new ArrayList<>();
        for (UnderwaterSensor sensor : sensors) {
            CageOverview.SensorView view = new CageOverview.SensorView();
            view.setSensorId(sensor.getId());
            view.setSensorNo(sensor.getSensorNo());
            view.setSensorType(sensor.getSensorType());
            view.setMetricUnit(sensor.getMetricUnit());
            view.setStatus(sensor.getStatus());

            SensorReading latestReading = readingMapper.selectOne(new LambdaQueryWrapper<SensorReading>()
                    .eq(SensorReading::getSensorId, sensor.getId())
                    .orderByDesc(SensorReading::getReadingTime)
                    .last("LIMIT 1"));
            if (latestReading != null) {
                view.setLatestValue(latestReading.getMetricValue());
                view.setLatestReadingTime(latestReading.getReadingTime().format(FORMATTER));
            }
            sensorViews.add(view);
        }
        overview.setSensors(sensorViews);

        List<HarvestBatch> harvests = harvestBatchMapper.selectList(new LambdaQueryWrapper<HarvestBatch>()
                .eq(HarvestBatch::getCageNo, cageNo)
                .orderByDesc(HarvestBatch::getHarvestTime));
        List<CageOverview.HarvestView> harvestViews = new ArrayList<>();
        for (HarvestBatch harvest : harvests) {
            CageOverview.HarvestView view = new CageOverview.HarvestView();
            view.setHarvestId(harvest.getId());
            view.setHarvestNo(harvest.getHarvestNo());
            view.setHarvestCount(harvest.getHarvestCount());
            view.setTotalWeightKg(harvest.getTotalWeightKg());
            view.setStatus(harvest.getStatus());
            view.setHarvestTime(harvest.getHarvestTime().format(FORMATTER));
            harvestViews.add(view);
        }
        overview.setHarvests(harvestViews);
        return overview;
    }

    /** 批次明细：批次信息 + 全部已落账投饵记录 + 存活率报告 + 出网批次。 */
    public Map<String, Object> batchDetail(Long batchId) {
        FishBatch batch = fishBatchMapper.selectById(batchId);
        if (batch == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("batch", batch);
        detail.put("postedFeedAmountKg", sumPostedFeed(batchId, null));
        detail.put("feedingRecords", feedingRecordMapper.selectList(new LambdaQueryWrapper<FeedingRecord>()
                .eq(FeedingRecord::getBatchId, batchId)
                .orderByDesc(FeedingRecord::getFeedingTime)));
        detail.put("survivalReports", survivalReportMapper.selectList(new LambdaQueryWrapper<SurvivalReport>()
                .eq(SurvivalReport::getBatchId, batchId)
                .orderByDesc(SurvivalReport::getReportTime)));
        detail.put("harvests", harvestBatchMapper.selectList(new LambdaQueryWrapper<HarvestBatch>()
                .eq(HarvestBatch::getFishBatchId, batchId)
                .orderByDesc(HarvestBatch::getHarvestTime)));
        return detail;
    }

    private BigDecimal sumPostedFeed(Long batchId, String cageNo) {
        List<FeedingRecord> records = feedingRecordMapper.selectList(new LambdaQueryWrapper<FeedingRecord>()
                .eq(batchId != null, FeedingRecord::getBatchId, batchId)
                .eq(cageNo != null && !cageNo.isEmpty(), FeedingRecord::getCageNo, cageNo)
                .eq(FeedingRecord::getPosted, 1));
        return records.stream()
                .map(FeedingRecord::getAmountKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
