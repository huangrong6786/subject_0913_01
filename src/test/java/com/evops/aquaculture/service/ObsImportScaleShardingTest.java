package com.evops.aquaculture.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.aquaculture.dto.ObsImportResult;
import com.evops.aquaculture.entity.CageObservation;
import com.evops.aquaculture.entity.FeedingRecord;
import com.evops.aquaculture.entity.FishBatch;
import com.evops.aquaculture.entity.ObsImportBatch;
import com.evops.aquaculture.entity.ObsImportRow;
import com.evops.aquaculture.entity.SensorReading;
import com.evops.aquaculture.entity.SurvivalReport;
import com.evops.aquaculture.entity.UnderwaterSensor;
import com.evops.aquaculture.enums.BatchStatus;
import com.evops.aquaculture.enums.ObsImportStatus;
import com.evops.aquaculture.enums.ObsShardStatus;
import com.evops.aquaculture.enums.SensorStatus;
import com.evops.aquaculture.mapper.CageObservationMapper;
import com.evops.aquaculture.mapper.FeedingRecordMapper;
import com.evops.aquaculture.mapper.FishBatchMapper;
import com.evops.aquaculture.mapper.ObsImportBatchMapper;
import com.evops.aquaculture.mapper.ObsImportRowMapper;
import com.evops.aquaculture.mapper.SensorReadingMapper;
import com.evops.aquaculture.mapper.SurvivalReportMapper;
import com.evops.aquaculture.mapper.UnderwaterSensorMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规模回归：50,000 行混合数据按分片处理（5000 行/片 → 10 片），
 * 合法行与坏数值行混合时逐行隔离；重复上传同一文件幂等不重复入账。
 * 计数断言均按本测试专属航次/网箱/导入批次作用域，避免与同库其他用例互相干扰。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"evops.import.shard-size=5000"})
class ObsImportScaleShardingTest {

    private static final int TOTAL_ROWS = 50_000;
    private static final int CAGE_COUNT = 5;
    private static final int SHARD_SIZE = 5000;
    private static final String VOYAGE = "VOY-SCALE";
    private static final String CAGE_PREFIX = "CAGE-S-";
    private static final String HEADER =
            "voyage_no,cage_no,observed_at,feed_amount_kg,survival_rate,sensor_value,sensor_unit,source_device";

    @Autowired
    private ObsImportService obsImportService;

    @Autowired
    private CageObservationMapper observationMapper;

    @Autowired
    private FeedingRecordMapper feedingRecordMapper;

    @Autowired
    private SurvivalReportMapper survivalReportMapper;

    @Autowired
    private SensorReadingMapper sensorReadingMapper;

    @Autowired
    private ObsImportRowMapper importRowMapper;

    @Autowired
    private ObsImportBatchMapper importBatchMapper;

    @BeforeAll
    static void setUpMasterData(@Autowired FishBatchMapper fishBatchMapper,
                                @Autowired UnderwaterSensorMapper sensorMapper) {
        for (int i = 0; i < CAGE_COUNT; i++) {
            FishBatch batch = new FishBatch();
            batch.setBatchNo("FB-SCALE-" + i);
            batch.setCageNo(CAGE_PREFIX + i);
            batch.setSpecies("大黄鱼");
            batch.setFingerlingCount(200000);
            batch.setAverageWeightG(new BigDecimal("5.00"));
            batch.setStockingTime(LocalDateTime.now().minusDays(60));
            batch.setStatus(BatchStatus.BREEDING.name());
            fishBatchMapper.insert(batch);

            UnderwaterSensor sensor = new UnderwaterSensor();
            sensor.setSensorNo("S-SCALE-" + i);
            sensor.setCageNo(CAGE_PREFIX + i);
            sensor.setSensorType("DISSOLVED_OXYGEN");
            sensor.setMetricUnit("mg/L");
            sensor.setDepthM(new BigDecimal("12.0"));
            sensor.setInstallTime(LocalDateTime.now().minusDays(50));
            sensor.setStatus(SensorStatus.ONLINE.name());
            sensorMapper.insert(sensor);
        }
    }

    @Test
    @Timeout(600)
    void fiftyThousandRowsAreShardedWithRowLevelIsolation() {
        String csv = buildScaleCsv();
        long start = System.currentTimeMillis();

        ObsImportResult result = obsImportService.importCsv(
                csv.getBytes(StandardCharsets.UTF_8), "scale-50k.csv", null);
        System.out.println("[scale] 50,000 行导入耗时 " + (System.currentTimeMillis() - start) + " ms");

        // 每 1000 行一个坏数值行（共 50 行），其余全部成功
        int expectedFailed = TOTAL_ROWS / 1000;
        int expectedSuccess = TOTAL_ROWS - expectedFailed;
        assertEquals(ObsImportStatus.PARTIAL.name(), result.getStatus());
        assertEquals(TOTAL_ROWS, result.getTotalRows());
        assertEquals(expectedSuccess, result.getSuccessCount());
        assertEquals(expectedFailed, result.getFailedCount());
        assertEquals(TOTAL_ROWS / SHARD_SIZE, result.getShardCount(), "50,000 行按 5000/片应切 10 片");
        for (ObsImportResult.ShardView shard : result.getShards()) {
            assertEquals(ObsShardStatus.PARTIAL.name(), shard.getStatus(),
                    "每片含 5 个坏行 → PARTIAL: " + shard.getShardIndex());
            assertEquals(SHARD_SIZE, shard.getRowCount().intValue());
            assertEquals(1, shard.getAttempts().intValue());
            assertEquals(5, shard.getFailedCount().intValue());
        }

        // 逐行明细完整落账：本批次 50,000 条，其中 50 条 FAILED 带原始行号/字段/原值
        ObsImportBatch batch = importBatchMapper.selectOne(new LambdaQueryWrapper<ObsImportBatch>()
                .eq(ObsImportBatch::getImportNo, result.getImportNo()));
        Long ledgerRows = importRowMapper.selectCount(new LambdaQueryWrapper<ObsImportRow>()
                .eq(ObsImportRow::getImportId, batch.getId()));
        assertEquals(TOTAL_ROWS, ledgerRows.intValue());
        Long ledgerFailed = importRowMapper.selectCount(new LambdaQueryWrapper<ObsImportRow>()
                .eq(ObsImportRow::getImportId, batch.getId())
                .eq(ObsImportRow::getOutcome, "FAILED"));
        assertEquals(expectedFailed, ledgerFailed.intValue());

        // 领域联动数量与成功行一致（按本测试作用域统计）：观测/投饵/存活率/读数各 49,950
        assertEquals(expectedSuccess, observationMapper.selectCount(new LambdaQueryWrapper<CageObservation>()
                .eq(CageObservation::getVoyageNo, VOYAGE)).intValue());
        List<String> cages = new ArrayList<>();
        for (int i = 0; i < CAGE_COUNT; i++) {
            cages.add(CAGE_PREFIX + i);
        }
        assertEquals(expectedSuccess, feedingRecordMapper.selectCount(new LambdaQueryWrapper<FeedingRecord>()
                .in(FeedingRecord::getCageNo, cages)).intValue());
        assertEquals(expectedSuccess, survivalReportMapper.selectCount(new LambdaQueryWrapper<SurvivalReport>()
                .in(SurvivalReport::getCageNo, cages)).intValue());
        assertEquals(expectedSuccess, sensorReadingMapper.selectCount(new LambdaQueryWrapper<SensorReading>()
                .in(SensorReading::getCageNo, cages)).intValue());

        // 断网重传同一文件：文件级幂等，计数与数据量不变
        ObsImportResult reupload = obsImportService.importCsv(
                csv.getBytes(StandardCharsets.UTF_8), "scale-50k.csv", null);
        assertEquals(result.getImportNo(), reupload.getImportNo());
        assertEquals(expectedSuccess, reupload.getSuccessCount());
        assertEquals(expectedSuccess, observationMapper.selectCount(new LambdaQueryWrapper<CageObservation>()
                .eq(CageObservation::getVoyageNo, VOYAGE)).intValue());
        assertTrue(reupload.getMessage().contains("校验和"));
    }

    /** 生成 50,000 行 CSV：5 网箱轮转，采样时刻逐行递增；每第 1000 行为坏数值行。 */
    private String buildScaleCsv() {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        LocalDateTime base = LocalDateTime.now().minusDays(30);
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        for (int i = 0; i < TOTAL_ROWS; i++) {
            int cage = i % CAGE_COUNT;
            String time = base.plusSeconds(30L * (i / CAGE_COUNT)).format(fmt);
            csv.append(VOYAGE).append(',')
                    .append(CAGE_PREFIX).append(cage).append(',')
                    .append(time).append(',');
            if (i % 1000 == 999) {
                csv.append("not-a-number");   // 坏数值行：投饵量无法解析
            } else {
                csv.append("12.50");
            }
            csv.append(",0.9500,7.1,mg/L,S-SCALE-").append(cage).append('\n');
        }
        return csv.toString();
    }
}
