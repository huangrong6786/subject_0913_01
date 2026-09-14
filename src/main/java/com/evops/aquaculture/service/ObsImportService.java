package com.evops.aquaculture.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.aquaculture.csv.DataLine;
import com.evops.aquaculture.csv.ObservationCsvParser;
import com.evops.aquaculture.csv.ObservationRowValidator;
import com.evops.aquaculture.csv.RowValidationException;
import com.evops.aquaculture.csv.ValidatedObsRow;
import com.evops.aquaculture.dto.ObsImportResult;
import com.evops.aquaculture.entity.CageObservation;
import com.evops.aquaculture.entity.FeedingRecord;
import com.evops.aquaculture.entity.FishBatch;
import com.evops.aquaculture.entity.HarvestBatch;
import com.evops.aquaculture.entity.ObsImportBatch;
import com.evops.aquaculture.entity.ObsImportRow;
import com.evops.aquaculture.entity.ObsImportShard;
import com.evops.aquaculture.entity.SensorReading;
import com.evops.aquaculture.entity.SurvivalReport;
import com.evops.aquaculture.entity.UnderwaterSensor;
import com.evops.aquaculture.enums.BatchStatus;
import com.evops.aquaculture.enums.HarvestStatus;
import com.evops.aquaculture.enums.ObsImportStatus;
import com.evops.aquaculture.enums.ObsRowOutcome;
import com.evops.aquaculture.enums.ObsShardStatus;
import com.evops.aquaculture.mapper.CageObservationMapper;
import com.evops.aquaculture.mapper.FeedingRecordMapper;
import com.evops.aquaculture.mapper.FishBatchMapper;
import com.evops.aquaculture.mapper.HarvestBatchMapper;
import com.evops.aquaculture.mapper.ObsImportBatchMapper;
import com.evops.aquaculture.mapper.ObsImportRowMapper;
import com.evops.aquaculture.mapper.ObsImportShardMapper;
import com.evops.aquaculture.mapper.SensorReadingMapper;
import com.evops.aquaculture.mapper.SurvivalReportMapper;
import com.evops.aquaculture.mapper.UnderwaterSensorMapper;
import com.evops.common.BusinessException;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 观测数据 CSV 批量导入编排服务。
 *
 * 两级幂等：
 * 1. 文件级：file_checksum（SHA-256）唯一。航次断网恢复后重复上传同一文件，
 *    直接返回首次处理结果；存在失败/未完成分片时续传这些分片。
 * 2. 行级：业务键（航次号|网箱号|采样时刻）唯一。已存在行内容一致 → SKIPPED，
 *    有变化且未锁定 → UPDATED；修正数据后重新上传（新校验和）逐行 upsert。
 *
 * 分片处理：数据行按 shard-size 切片，逐片校验、预取、逐行独立事务应用；
 * 分片级异常只标记本片 FAILED 并继续后续分片，失败分片可经重试端点或重新上传续传。
 * 单行失败只回滚本行（行处理器 REQUIRES_NEW），绝不回滚整批。
 */
@Service
public class ObsImportService {

    private static final Logger log = LoggerFactory.getLogger(ObsImportService.class);

    private static final DateTimeFormatter IMPORT_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int PREFETCH_CHUNK = 200;

    private final ObsImportBatchMapper batchMapper;
    private final ObsImportShardMapper shardMapper;
    private final ObsImportRowMapper rowMapper;
    private final CageObservationMapper observationMapper;
    private final UnderwaterSensorMapper sensorMapper;
    private final FishBatchMapper fishBatchMapper;
    private final HarvestBatchMapper harvestBatchMapper;
    private final FeedingRecordMapper feedingRecordMapper;
    private final SurvivalReportMapper survivalReportMapper;
    private final SensorReadingMapper sensorReadingMapper;
    private final ObsImportRowProcessor rowProcessor;
    private final SqlSessionFactory sqlSessionFactory;
    private final ObservationCsvParser parser = new ObservationCsvParser();

    /** 分片大小（数据行数/片）：默认 1000，50,000 行文件切为 50 片。 */
    @Value("${evops.import.shard-size:1000}")
    private int shardSize;

    /** 原始上传文件落盘目录（失败分片重试的数据来源）。 */
    @Value("${evops.import.storage-dir:./data/imports}")
    private String storageDir;

    /** 僵死判定（秒）：RUNNING 超过该时长视为执行方异常退出，可接管。 */
    @Value("${evops.import.stale-seconds:300}")
    private long staleSeconds;

    /** 响应中携带的失败明细上限（完整明细走行明细接口）。 */
    @Value("${evops.import.max-failures-in-response:100}")
    private int maxFailuresInResponse;

    public ObsImportService(ObsImportBatchMapper batchMapper,
                            ObsImportShardMapper shardMapper,
                            ObsImportRowMapper rowMapper,
                            CageObservationMapper observationMapper,
                            UnderwaterSensorMapper sensorMapper,
                            FishBatchMapper fishBatchMapper,
                            HarvestBatchMapper harvestBatchMapper,
                            FeedingRecordMapper feedingRecordMapper,
                            SurvivalReportMapper survivalReportMapper,
                            SensorReadingMapper sensorReadingMapper,
                            ObsImportRowProcessor rowProcessor,
                            SqlSessionFactory sqlSessionFactory) {
        this.batchMapper = batchMapper;
        this.shardMapper = shardMapper;
        this.rowMapper = rowMapper;
        this.observationMapper = observationMapper;
        this.sensorMapper = sensorMapper;
        this.fishBatchMapper = fishBatchMapper;
        this.harvestBatchMapper = harvestBatchMapper;
        this.feedingRecordMapper = feedingRecordMapper;
        this.survivalReportMapper = survivalReportMapper;
        this.sensorReadingMapper = sensorReadingMapper;
        this.rowProcessor = rowProcessor;
        this.sqlSessionFactory = sqlSessionFactory;
    }

    // ---------------- 导入入口 ----------------

    /**
     * 上传 CSV 并导入。同一文件（校验和一致）重复上传幂等：
     * 已完成 → 返回首次结果；有失败/未完成分片 → 续传。
     *
     * @param expectedChecksum 可选，调用方预计算的 SHA-256，用于发现传输损坏
     */
    public ObsImportResult importCsv(byte[] bytes, String fileName, String expectedChecksum) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException("上传文件为空");
        }
        String checksum = ObsImportRowProcessor.sha256Hex(bytes);
        if (expectedChecksum != null && !expectedChecksum.trim().isEmpty()
                && !checksum.equalsIgnoreCase(expectedChecksum.trim())) {
            throw new BusinessException("文件校验和不匹配：期望 " + expectedChecksum.trim()
                    + "，实际 " + checksum + "，文件可能在传输中损坏");
        }

        ObsImportBatch existing = findByChecksum(checksum);
        if (existing != null) {
            boolean running = ObsImportStatus.RUNNING.name().equals(existing.getStatus());
            if (running && !isStale(existing.getStartedAt())) {
                return toResult(existing.getId(), "同一文件正在处理中（校验和一致），请稍后查询处理结果");
            }
            if (hasRetryableShards(existing.getId())) {
                resumeBatch(existing);
                return toResult(existing.getId(), "同一文件重复上传：已续传失败/未完成分片");
            }
            if (running) {
                // 僵死于 finalize 之前（分片均终态）：补齐批次终态后返回。
                finalizeBatch(existing);
            }
            return toResult(existing.getId(), "同一文件已导入（校验和一致），返回首次处理结果");
        }

        ObsImportBatch batch = newBatch(fileName, checksum);
        try {
            batchMapper.insert(batch);
        } catch (DuplicateKeyException dup) {
            // 并发上传同一文件：唯一约束兜底，返回已存在批次。
            ObsImportBatch concurrent = findByChecksum(checksum);
            return toResult(concurrent.getId(), "同一文件已存在导入批次（并发上传去重）");
        }

        try {
            batch.setFilePath(storeFile(batch.getImportNo(), bytes));
            batchMapper.updateById(batch);
        } catch (IOException io) {
            failBatch(batch, "原始文件落盘失败: " + io.getMessage());
            return toResult(batch.getId(), "导入失败：原始文件落盘失败");
        }

        List<DataLine> dataLines;
        try {
            dataLines = parser.extractDataLines(readLines(bytes));
        } catch (IllegalArgumentException headerError) {
            failBatch(batch, headerError.getMessage());
            return toResult(batch.getId(), "导入失败：" + headerError.getMessage());
        }
        if (dataLines.isEmpty()) {
            failBatch(batch, "文件无数据行");
            return toResult(batch.getId(), "导入失败：文件无数据行");
        }

        List<ObsImportShard> shards = createShards(batch, dataLines);
        processShards(batch, shards, dataLines);
        finalizeBatch(batch);
        return toResult(batch.getId(), "导入处理完成");
    }

    /** 重试失败/未完成分片（PENDING、FAILED、僵死 RUNNING）。 */
    public ObsImportResult retry(String importNo) {
        ObsImportBatch batch = getBatch(importNo);
        if (!hasRetryableShards(batch.getId())) {
            return toResult(batch.getId(), "没有可重试的分片（仅 FAILED/PENDING/僵死 RUNNING 分片可重试）");
        }
        resumeBatch(batch);
        return toResult(batch.getId(), "失败分片重试完成");
    }

    public ObsImportResult getImport(String importNo) {
        return toResult(getBatch(importNo).getId(), "OK");
    }

    /** 逐行明细（可按结果类型过滤），按原始行号升序。 */
    public List<ObsImportRow> listRows(String importNo, String outcome, int limit, int offset) {
        ObsImportBatch batch = getBatch(importNo);
        int safeLimit = Math.min(Math.max(limit, 1), 1000);
        int safeOffset = Math.max(offset, 0);
        return rowMapper.selectList(new LambdaQueryWrapper<ObsImportRow>()
                .eq(ObsImportRow::getImportId, batch.getId())
                .eq(outcome != null && !outcome.isEmpty(), ObsImportRow::getOutcome,
                        outcome == null ? null : outcome.toUpperCase())
                .orderByAsc(ObsImportRow::getLineNo)
                .last("LIMIT " + safeLimit + " OFFSET " + safeOffset));
    }

    public List<CageObservation> listObservations(String voyageNo, String cageNo) {
        return observationMapper.selectList(new LambdaQueryWrapper<CageObservation>()
                .eq(voyageNo != null && !voyageNo.isEmpty(), CageObservation::getVoyageNo, voyageNo)
                .eq(cageNo != null && !cageNo.isEmpty(), CageObservation::getCageNo, cageNo)
                .orderByDesc(CageObservation::getObservedAt)
                .last("LIMIT 500"));
    }

    // ---------------- 批次续传与分片处理 ----------------

    /** 续传：从落盘原件重新读取数据行，处理所有可重试分片后收尾。 */
    private void resumeBatch(ObsImportBatch batch) {
        if (batch.getFilePath() == null) {
            throw new BusinessException("原始文件未落盘，无法续传: " + batch.getImportNo());
        }
        Path path = Paths.get(batch.getFilePath());
        if (!Files.exists(path)) {
            throw new BusinessException("原始上传文件已丢失，无法重试分片: " + batch.getFilePath());
        }
        List<String> physicalLines;
        try {
            physicalLines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException io) {
            throw new BusinessException("原始文件读取失败: " + io.getMessage());
        }
        List<DataLine> dataLines = parser.extractDataLines(physicalLines);

        markRunning(batch);
        List<ObsImportShard> retryable = new ArrayList<>();
        for (ObsImportShard shard : shardsOf(batch.getId())) {
            if (isRetryable(shard)) {
                retryable.add(shard);
            }
        }
        processShards(batch, retryable, dataLines);
        finalizeBatch(batch);
    }

    private void processShards(ObsImportBatch batch, List<ObsImportShard> shards, List<DataLine> dataLines) {
        for (ObsImportShard shard : shards) {
            try {
                processShard(batch, shard, dataLines);
            } catch (Exception ex) {
                // 分片级异常只标记本片失败，不阻塞后续分片；失败分片可重试。
                log.warn("导入分片处理失败 importNo={} shardIndex={}: {}",
                        batch.getImportNo(), shard.getShardIndex(), ex.getMessage());
                markShardFailed(shard, ex.getMessage());
            }
            refreshBatchCounts(batch);
        }
    }

    /**
     * 处理一个分片（业务执行 seam：回归测试可经子类注入分片级失败）。
     * 行级流程：解析/校验（坏行隔离）→ 分片级参考数据与既有业务键预取 → 逐行独立事务应用。
     */
    protected void processShard(ObsImportBatch batch, ObsImportShard shard, List<DataLine> dataLines) {
        LocalDateTime now = LocalDateTime.now();
        int claimed = shardMapper.claimRunning(shard.getId(), now, now.minusSeconds(staleSeconds));
        if (claimed == 0) {
            log.info("分片已被接管或已终态，跳过: importNo={} shardIndex={}",
                    batch.getImportNo(), shard.getShardIndex());
            return;
        }

        List<DataLine> lines = dataLines.subList(shard.getStartIdx(), shard.getEndIdx() + 1);
        List<ObsImportRow> outcomes = new ArrayList<>(lines.size());
        List<ValidatedObsRow> validRows = new ArrayList<>(lines.size());

        // 1) 解析与字段/单位/时间校验：坏行只记录失败，不影响其他行。
        for (DataLine line : lines) {
            String[] cols = parser.parseLine(line.getText());
            if (cols.length != ObservationCsvParser.COLUMN_COUNT) {
                String field = cols.length < ObservationCsvParser.COLUMN_COUNT
                        ? ObservationCsvParser.EXPECTED_HEADER[cols.length] : "_row";
                outcomes.add(failedRow(batch.getId(), shard.getId(), line, field, null,
                        "列数与表头不符：期望 " + ObservationCsvParser.COLUMN_COUNT
                                + " 列，实际 " + cols.length + " 列"));
                continue;
            }
            try {
                validRows.add(ObservationRowValidator.validate(cols, line.getLineNo(), line.getText()));
            } catch (RowValidationException ex) {
                outcomes.add(failedRow(batch.getId(), shard.getId(), line,
                        ex.getFieldName(), ex.getRawValue(), ex.getMessage()));
            }
        }

        // 2) 分片级参考数据快照与既有业务键预取（减少逐行查询）。
        ImportRefData ref = loadRefData();
        Map<String, CageObservation> existingByKey = prefetchObservations(validRows);
        prefetchLinkedRecords(ref, existingByKey.values());

        // 3) 逐行独立事务应用：单行失败只回滚本行。
        for (ValidatedObsRow row : validRows) {
            RowApplyResult result = applyRowSafely(batch.getId(), row, ref, existingByKey);
            if (result.getOutcome() != ObsRowOutcome.FAILED && result.getObservation() != null) {
                existingByKey.put(row.businessKey(), result.getObservation());
            }
            outcomes.add(toImportRow(batch.getId(), shard.getId(), row, result));
        }

        // 4) 落账逐行明细（重试时先清旧明细，JDBC 批量一次提交）并汇总分片终态。
        recordShardOutcomes(shard.getId(), outcomes);
        completeShard(shard, outcomes);
    }

    /** 分片明细整体换写：删除旧明细 + 批量插入，单批提交；失败则整片 FAILED 可重试。 */
    private void recordShardOutcomes(Long shardId, List<ObsImportRow> outcomes) {
        try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
            ObsImportRowMapper batchMapper = session.getMapper(ObsImportRowMapper.class);
            batchMapper.deleteByShardId(shardId);
            for (ObsImportRow outcome : outcomes) {
                batchMapper.insert(outcome);
            }
            session.commit();
        }
    }

    private RowApplyResult applyRowSafely(long importId, ValidatedObsRow row, ImportRefData ref,
                                          Map<String, CageObservation> existingByKey) {
        try {
            return rowProcessor.apply(importId, row, ref, existingByKey);
        } catch (DuplicateKeyException dup) {
            // 与其他导入并发写入同一业务键：重新加载后按更新路径再试一次。
            CageObservation current = findObservation(row);
            if (current != null) {
                try {
                    existingByKey.put(row.businessKey(), current);
                    return rowProcessor.apply(importId, row, ref, existingByKey);
                } catch (Exception retryEx) {
                    return RowApplyResult.failed("_row", null,
                            "业务键并发冲突重试失败: " + truncate(retryEx.getMessage(), 400));
                }
            }
            return RowApplyResult.failed("_row", null, "业务键并发冲突，请重试: " + row.businessKey());
        } catch (Exception ex) {
            return RowApplyResult.failed("_row", null, "行处理异常: " + truncate(ex.getMessage(), 400));
        }
    }

    // ---------------- 分片级数据装配 ----------------

    private ImportRefData loadRefData() {
        ImportRefData ref = new ImportRefData();
        for (UnderwaterSensor sensor : sensorMapper.selectList(null)) {
            ref.sensorsByNo.put(sensor.getSensorNo(), sensor);
        }
        for (FishBatch batch : fishBatchMapper.selectList(null)) {
            ref.batchesById.put(batch.getId(), batch);
            boolean active = BatchStatus.BREEDING.name().equals(batch.getStatus())
                    || BatchStatus.MONITORING.name().equals(batch.getStatus());
            Map<String, FishBatch> target = active ? ref.activeBatchByCage : ref.terminalBatchByCage;
            target.merge(batch.getCageNo(), batch,
                    (older, newer) -> newer.getStockingTime() != null && older.getStockingTime() != null
                            && newer.getStockingTime().isAfter(older.getStockingTime()) ? newer : older);
        }
        for (HarvestBatch harvest : harvestBatchMapper.selectList(new LambdaQueryWrapper<HarvestBatch>()
                .eq(HarvestBatch::getStatus, HarvestStatus.ACCEPTED.name()))) {
            ref.acceptedCages.add(harvest.getCageNo());
        }
        return ref;
    }

    /** 按业务键批量预取既有观测（OR 分组，每组 200 键）。 */
    private Map<String, CageObservation> prefetchObservations(List<ValidatedObsRow> rows) {
        Map<String, CageObservation> map = new HashMap<>();
        Map<String, ValidatedObsRow> distinct = new HashMap<>();
        for (ValidatedObsRow row : rows) {
            distinct.putIfAbsent(row.businessKey(), row);
        }
        List<ValidatedObsRow> keys = new ArrayList<>(distinct.values());
        for (int from = 0; from < keys.size(); from += PREFETCH_CHUNK) {
            List<ValidatedObsRow> chunk = keys.subList(from, Math.min(from + PREFETCH_CHUNK, keys.size()));
            LambdaQueryWrapper<CageObservation> wrapper = new LambdaQueryWrapper<>();
            wrapper.and(outer -> {
                for (ValidatedObsRow row : chunk) {
                    outer.or(inner -> inner
                            .eq(CageObservation::getVoyageNo, row.getVoyageNo())
                            .eq(CageObservation::getCageNo, row.getCageNo())
                            .eq(CageObservation::getObservedAt, row.getObservedAt()));
                }
            });
            for (CageObservation existing : observationMapper.selectList(wrapper)) {
                map.put(ValidatedObsRow.businessKeyOf(existing.getVoyageNo(), existing.getCageNo(),
                        existing.getObservedAt()), existing);
            }
        }
        return map;
    }

    private void prefetchLinkedRecords(ImportRefData ref, Iterable<CageObservation> observations) {
        Set<Long> feedingIds = new HashSet<>();
        Set<Long> reportIds = new HashSet<>();
        Set<Long> readingIds = new HashSet<>();
        for (CageObservation observation : observations) {
            if (observation.getFeedingRecordId() != null) {
                feedingIds.add(observation.getFeedingRecordId());
            }
            if (observation.getSurvivalReportId() != null) {
                reportIds.add(observation.getSurvivalReportId());
            }
            if (observation.getSensorReadingId() != null) {
                readingIds.add(observation.getSensorReadingId());
            }
        }
        if (!feedingIds.isEmpty()) {
            for (FeedingRecord record : feedingRecordMapper.selectBatchIds(feedingIds)) {
                ref.feedingRecordsById.put(record.getId(), record);
            }
        }
        if (!reportIds.isEmpty()) {
            for (SurvivalReport report : survivalReportMapper.selectBatchIds(reportIds)) {
                ref.survivalReportsById.put(report.getId(), report);
            }
        }
        if (!readingIds.isEmpty()) {
            for (SensorReading reading : sensorReadingMapper.selectBatchIds(readingIds)) {
                ref.sensorReadingsById.put(reading.getId(), reading);
            }
        }
    }

    // ---------------- 台账 ----------------

    private ObsImportBatch newBatch(String fileName, String checksum) {
        ObsImportBatch batch = new ObsImportBatch();
        batch.setImportNo("IMP-" + LocalDateTime.now().format(IMPORT_NO_FORMAT)
                + "-" + UUID.randomUUID().toString().substring(0, 8));
        batch.setFileName(fileName);
        batch.setFileChecksum(checksum);
        batch.setTotalRows(0);
        batch.setSuccessCount(0);
        batch.setUpdatedCount(0);
        batch.setSkippedCount(0);
        batch.setFailedCount(0);
        batch.setStatus(ObsImportStatus.RUNNING.name());
        batch.setStartedAt(LocalDateTime.now());
        return batch;
    }

    private List<ObsImportShard> createShards(ObsImportBatch batch, List<DataLine> dataLines) {
        List<ObsImportShard> shards = new ArrayList<>();
        int index = 0;
        for (int from = 0; from < dataLines.size(); from += shardSize, index++) {
            int to = Math.min(from + shardSize, dataLines.size()) - 1;
            ObsImportShard shard = new ObsImportShard();
            shard.setImportId(batch.getId());
            shard.setShardIndex(index);
            shard.setStartIdx(from);
            shard.setEndIdx(to);
            shard.setStartLine(dataLines.get(from).getLineNo());
            shard.setEndLine(dataLines.get(to).getLineNo());
            shard.setRowCount(to - from + 1);
            shard.setStatus(ObsShardStatus.PENDING.name());
            shard.setAttempts(0);
            shardMapper.insert(shard);
            shards.add(shard);
        }
        return shards;
    }

    private void completeShard(ObsImportShard shard, List<ObsImportRow> outcomes) {
        int success = 0, updated = 0, skipped = 0, failed = 0;
        for (ObsImportRow outcome : outcomes) {
            switch (ObsRowOutcome.valueOf(outcome.getOutcome())) {
                case SUCCESS: success++; break;
                case UPDATED: updated++; break;
                case SKIPPED: skipped++; break;
                default: failed++;
            }
        }
        ObsImportShard update = new ObsImportShard();
        update.setId(shard.getId());
        update.setRowCount(outcomes.size());
        update.setSuccessCount(success);
        update.setUpdatedCount(updated);
        update.setSkippedCount(skipped);
        update.setFailedCount(failed);
        update.setStatus(failed == 0 ? ObsShardStatus.SUCCESS.name() : ObsShardStatus.PARTIAL.name());
        update.setFinishedAt(LocalDateTime.now());
        shardMapper.updateById(update);
    }

    private void markShardFailed(ObsImportShard shard, String message) {
        ObsImportShard update = new ObsImportShard();
        update.setId(shard.getId());
        update.setStatus(ObsShardStatus.FAILED.name());
        // 整片未处理：行数计入失败数，批次汇总能真实反映未入账行。
        update.setFailedCount(shard.getRowCount());
        update.setErrorMessage(truncate(message, 500));
        update.setFinishedAt(LocalDateTime.now());
        shardMapper.updateById(update);
    }

    private void markRunning(ObsImportBatch batch) {
        LocalDateTime now = LocalDateTime.now();
        batchMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ObsImportBatch>()
                .eq(ObsImportBatch::getId, batch.getId())
                .set(ObsImportBatch::getStatus, ObsImportStatus.RUNNING.name())
                .set(ObsImportBatch::getStartedAt, now)
                .set(ObsImportBatch::getFinishedAt, null));
        batch.setStatus(ObsImportStatus.RUNNING.name());
        batch.setStartedAt(now);
    }

    private void refreshBatchCounts(ObsImportBatch batch) {
        int success = 0, updated = 0, skipped = 0, failed = 0, total = 0;
        for (ObsImportShard shard : shardsOf(batch.getId())) {
            success += nullToZero(shard.getSuccessCount());
            updated += nullToZero(shard.getUpdatedCount());
            skipped += nullToZero(shard.getSkippedCount());
            failed += nullToZero(shard.getFailedCount());
            total += nullToZero(shard.getRowCount());
        }
        ObsImportBatch update = new ObsImportBatch();
        update.setId(batch.getId());
        update.setTotalRows(total);
        update.setSuccessCount(success);
        update.setUpdatedCount(updated);
        update.setSkippedCount(skipped);
        update.setFailedCount(failed);
        batchMapper.updateById(update);
        batch.setTotalRows(total);
        batch.setSuccessCount(success);
        batch.setUpdatedCount(updated);
        batch.setSkippedCount(skipped);
        batch.setFailedCount(failed);
    }

    /** 收尾：全部分片终态且无失败行 → SUCCESS；否则 PARTIAL（失败分片可重试）。 */
    private void finalizeBatch(ObsImportBatch batch) {
        refreshBatchCounts(batch);
        boolean clean = true;
        for (ObsImportShard shard : shardsOf(batch.getId())) {
            if (ObsShardStatus.FAILED.name().equals(shard.getStatus())
                    || ObsShardStatus.PENDING.name().equals(shard.getStatus())
                    || ObsShardStatus.RUNNING.name().equals(shard.getStatus())) {
                clean = false;
                break;
            }
        }
        String status = clean && batch.getFailedCount() == 0
                ? ObsImportStatus.SUCCESS.name() : ObsImportStatus.PARTIAL.name();
        ObsImportBatch update = new ObsImportBatch();
        update.setId(batch.getId());
        update.setStatus(status);
        update.setFinishedAt(LocalDateTime.now());
        batchMapper.updateById(update);
        batch.setStatus(status);
        log.info("观测数据导入完成 importNo={} status={} total={} success={} updated={} skipped={} failed={}",
                batch.getImportNo(), status, batch.getTotalRows(), batch.getSuccessCount(),
                batch.getUpdatedCount(), batch.getSkippedCount(), batch.getFailedCount());
    }

    private void failBatch(ObsImportBatch batch, String message) {
        ObsImportBatch update = new ObsImportBatch();
        update.setId(batch.getId());
        update.setStatus(ObsImportStatus.FAILED.name());
        update.setErrorMessage(truncate(message, 500));
        update.setFinishedAt(LocalDateTime.now());
        batchMapper.updateById(update);
        batch.setStatus(ObsImportStatus.FAILED.name());
    }

    // ---------------- 行明细 ----------------

    private ObsImportRow failedRow(Long importId, Long shardId, DataLine line,
                                   String field, String rawValue, String reason) {
        ObsImportRow row = new ObsImportRow();
        row.setImportId(importId);
        row.setShardId(shardId);
        row.setLineNo(line.getLineNo());
        row.setBusinessKey(bestEffortKey(line.getText()));
        row.setOutcome(ObsRowOutcome.FAILED.name());
        row.setFieldName(field);
        row.setRawValue(truncate(rawValue, 255));
        row.setRawLine(truncate(line.getText(), 1000));
        row.setErrorReason(truncate(reason, 500));
        return row;
    }

    private ObsImportRow toImportRow(Long importId, Long shardId, ValidatedObsRow row,
                                     RowApplyResult result) {
        ObsImportRow outcome = new ObsImportRow();
        outcome.setImportId(importId);
        outcome.setShardId(shardId);
        outcome.setLineNo(row.getLineNo());
        outcome.setBusinessKey(row.businessKey());
        outcome.setOutcome(result.getOutcome().name());
        if (result.getOutcome() == ObsRowOutcome.FAILED) {
            outcome.setFieldName(result.getFieldName());
            outcome.setRawValue(truncate(result.getRawValue(), 255));
            outcome.setRawLine(truncate(row.getRawLine(), 1000));
            outcome.setErrorReason(truncate(result.getErrorReason(), 500));
        }
        return outcome;
    }

    /** 尽力从原始行解析业务键（解析失败返回 null）。 */
    private String bestEffortKey(String rawLine) {
        try {
            String[] cols = parser.parseLine(rawLine);
            if (cols.length >= 3 && cols[0] != null && !cols[0].isEmpty()
                    && cols[1] != null && !cols[1].isEmpty()) {
                return truncate(cols[0] + "|" + cols[1] + "|" + cols[2], 128);
            }
        } catch (Exception ignored) {
            // 忽略：业务键仅用于辅助定位
        }
        return null;
    }

    // ---------------- 查询与工具 ----------------

    private ObsImportBatch getBatch(String importNo) {
        ObsImportBatch batch = batchMapper.selectOne(new LambdaQueryWrapper<ObsImportBatch>()
                .eq(ObsImportBatch::getImportNo, importNo));
        if (batch == null) {
            throw new BusinessException("导入批次不存在: " + importNo);
        }
        return batch;
    }

    private ObsImportBatch findByChecksum(String checksum) {
        return batchMapper.selectOne(new LambdaQueryWrapper<ObsImportBatch>()
                .eq(ObsImportBatch::getFileChecksum, checksum));
    }

    private List<ObsImportShard> shardsOf(Long importId) {
        return shardMapper.selectList(new LambdaQueryWrapper<ObsImportShard>()
                .eq(ObsImportShard::getImportId, importId)
                .orderByAsc(ObsImportShard::getShardIndex));
    }

    private boolean hasRetryableShards(Long importId) {
        for (ObsImportShard shard : shardsOf(importId)) {
            if (isRetryable(shard)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRetryable(ObsImportShard shard) {
        if (ObsShardStatus.PENDING.name().equals(shard.getStatus())
                || ObsShardStatus.FAILED.name().equals(shard.getStatus())) {
            return true;
        }
        return ObsShardStatus.RUNNING.name().equals(shard.getStatus()) && isStale(shard.getStartedAt());
    }

    private boolean isStale(LocalDateTime startedAt) {
        return startedAt == null
                || startedAt.isBefore(LocalDateTime.now().minusSeconds(staleSeconds));
    }

    private CageObservation findObservation(ValidatedObsRow row) {
        return observationMapper.selectOne(new LambdaQueryWrapper<CageObservation>()
                .eq(CageObservation::getVoyageNo, row.getVoyageNo())
                .eq(CageObservation::getCageNo, row.getCageNo())
                .eq(CageObservation::getObservedAt, row.getObservedAt()));
    }

    private String storeFile(String importNo, byte[] bytes) throws IOException {
        Path dir = Paths.get(storageDir);
        Files.createDirectories(dir);
        Path path = dir.resolve(importNo + ".csv");
        Files.write(path, bytes);
        return path.toString();
    }

    private static List<String> readLines(byte[] bytes) {
        String content = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = content.split("\r\n|\r|\n", -1);
        List<String> result = new ArrayList<>(lines.length);
        for (String line : lines) {
            result.add(line);
        }
        return result;
    }

    private ObsImportResult toResult(Long batchId, String message) {
        ObsImportBatch batch = batchMapper.selectById(batchId);
        ObsImportResult result = new ObsImportResult();
        result.setImportNo(batch.getImportNo());
        result.setFileName(batch.getFileName());
        result.setFileChecksum(batch.getFileChecksum());
        result.setStatus(batch.getStatus());
        result.setMessage(message);
        result.setTotalRows(batch.getTotalRows());
        result.setSuccessCount(batch.getSuccessCount());
        result.setUpdatedCount(batch.getUpdatedCount());
        result.setSkippedCount(batch.getSkippedCount());
        result.setFailedCount(batch.getFailedCount());
        result.setStartedAt(batch.getStartedAt());
        result.setFinishedAt(batch.getFinishedAt());

        List<ObsImportShard> shards = shardsOf(batchId);
        result.setShardCount(shards.size());
        for (ObsImportShard shard : shards) {
            ObsImportResult.ShardView view = new ObsImportResult.ShardView();
            view.setShardIndex(shard.getShardIndex());
            view.setStartLine(shard.getStartLine());
            view.setEndLine(shard.getEndLine());
            view.setStatus(shard.getStatus());
            view.setAttempts(shard.getAttempts());
            view.setRowCount(shard.getRowCount());
            view.setSuccessCount(shard.getSuccessCount());
            view.setUpdatedCount(shard.getUpdatedCount());
            view.setSkippedCount(shard.getSkippedCount());
            view.setFailedCount(shard.getFailedCount());
            view.setErrorMessage(shard.getErrorMessage());
            result.getShards().add(view);
        }

        List<ObsImportRow> failures = rowMapper.selectList(new LambdaQueryWrapper<ObsImportRow>()
                .eq(ObsImportRow::getImportId, batchId)
                .eq(ObsImportRow::getOutcome, ObsRowOutcome.FAILED.name())
                .orderByAsc(ObsImportRow::getLineNo)
                .last("LIMIT " + Math.max(maxFailuresInResponse, 1)));
        for (ObsImportRow failure : failures) {
            ObsImportResult.RowErrorView view = new ObsImportResult.RowErrorView();
            view.setLineNo(failure.getLineNo());
            view.setBusinessKey(failure.getBusinessKey());
            view.setFieldName(failure.getFieldName());
            view.setRawValue(failure.getRawValue());
            view.setErrorReason(failure.getErrorReason());
            result.getFailures().add(view);
        }
        return result;
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
