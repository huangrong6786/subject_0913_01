package com.evops.aquaculture.service;

import com.evops.aquaculture.csv.ValidatedObsRow;
import com.evops.aquaculture.entity.CageObservation;
import com.evops.aquaculture.entity.FeedingRecord;
import com.evops.aquaculture.entity.FishBatch;
import com.evops.aquaculture.entity.SensorReading;
import com.evops.aquaculture.entity.SurvivalReport;
import com.evops.aquaculture.entity.UnderwaterSensor;
import com.evops.aquaculture.enums.BatchStatus;
import com.evops.aquaculture.enums.ObsRowOutcome;
import com.evops.aquaculture.enums.SensorStatus;
import com.evops.aquaculture.mapper.CageObservationMapper;
import com.evops.aquaculture.mapper.FeedingRecordMapper;
import com.evops.aquaculture.mapper.SensorReadingMapper;
import com.evops.aquaculture.mapper.SurvivalReportMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 单行观测应用器：每行一个独立事务（REQUIRES_NEW），单行失败只回滚本行，不连坐整批。
 *
 * 行级幂等：业务键（航次号|网箱号|采样时刻）已存在时
 *  - 内容一致 → SKIPPED（航次断网重传同一文件不产生二次副作用）；
 *  - 内容有变 → UPDATED，同步更新联动记录；
 *  - 目标数据已落账（投饵记录 posted=1）或网箱出网已验收/批次已终结 → FAILED，拒绝覆盖。
 *
 * 单个坏传感器（不存在/离线/维护中/单位不符）只使引用它的行失败，不阻塞整船其他数据。
 */
@Service
public class ObsImportRowProcessor {

    /** CSV 未携带饵料类型，导入生成的投饵记录统一标记来源。 */
    static final String IMPORT_FEED_TYPE = "IMPORT-CSV";

    private final CageObservationMapper observationMapper;
    private final FeedingRecordMapper feedingRecordMapper;
    private final SurvivalReportMapper survivalReportMapper;
    private final SensorReadingMapper sensorReadingMapper;

    public ObsImportRowProcessor(CageObservationMapper observationMapper,
                                 FeedingRecordMapper feedingRecordMapper,
                                 SurvivalReportMapper survivalReportMapper,
                                 SensorReadingMapper sensorReadingMapper) {
        this.observationMapper = observationMapper;
        this.feedingRecordMapper = feedingRecordMapper;
        this.survivalReportMapper = survivalReportMapper;
        this.sensorReadingMapper = sensorReadingMapper;
    }

    /**
     * 应用一行（独立事务）。existingByKey 为分片预取快照，调用方在本方法返回成功后
     * 负责把新观测写回快照，保证同分片后续重复行走更新/跳过路径。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RowApplyResult apply(long importId, ValidatedObsRow row, ImportRefData ref,
                                Map<String, CageObservation> existingByKey) {
        String key = row.businessKey();
        CageObservation existing = existingByKey.get(key);

        // 幂等短路：已存在且本次提供的数据完全一致 → 跳过，不做任何写操作。
        if (existing != null && matchesProvided(existing, row)) {
            return RowApplyResult.of(ObsRowOutcome.SKIPPED, existing);
        }

        // 来源设备校验（新增与更新一致要求）：坏传感器只让本行失败。
        UnderwaterSensor device = null;
        if (row.hasSensor()) {
            RowApplyResult deviceFailure;
            device = ref.sensorsByNo.get(row.getSourceDevice());
            if (device == null) {
                deviceFailure = RowApplyResult.failed("source_device", row.getSourceDevice(),
                        "来源设备不存在: " + row.getSourceDevice());
            } else if (!device.getCageNo().equals(row.getCageNo())) {
                deviceFailure = RowApplyResult.failed("source_device", row.getSourceDevice(),
                        "来源设备不属于网箱 " + row.getCageNo() + "（设备登记在 " + device.getCageNo() + "）");
            } else if (!device.getMetricUnit().equals(row.getSensorUnit())) {
                deviceFailure = RowApplyResult.failed("sensor_unit", row.getSensorUnit(),
                        "读数单位与设备登记单位不符（设备单位 " + device.getMetricUnit() + "）");
            } else if (!SensorStatus.ONLINE.name().equals(device.getStatus())) {
                deviceFailure = RowApplyResult.failed("source_device", row.getSourceDevice(),
                        "来源设备状态为 " + device.getStatus() + "，不能接收读数");
            } else {
                deviceFailure = null;
            }
            if (deviceFailure != null) {
                return deviceFailure;
            }
        }

        return existing == null
                ? doInsert(importId, row, ref, device)
                : doUpdate(importId, row, ref, existing, device);
    }

    // ---------------- 新增 ----------------

    private RowApplyResult doInsert(long importId, ValidatedObsRow row, ImportRefData ref,
                                    UnderwaterSensor device) {
        FishBatch batch = null;
        if (row.needsBatch()) {
            batch = ref.activeBatchByCage.get(row.getCageNo());
            if (batch == null) {
                FishBatch terminal = ref.terminalBatchByCage.get(row.getCageNo());
                return RowApplyResult.failed("cage_no", row.getCageNo(), terminal != null
                        ? "网箱批次已终结（" + terminal.getStatus() + "，数据已验收锁定），不能写入新观测"
                        : "网箱无在养批次，不能写入投饵/存活率观测: " + row.getCageNo());
            }
        }

        CageObservation observation = new CageObservation();
        observation.setVoyageNo(row.getVoyageNo());
        observation.setCageNo(row.getCageNo());
        observation.setObservedAt(row.getObservedAt());
        observation.setImportId(importId);

        if (row.getFeedAmountKg() != null) {
            FeedingRecord record = newFeedingRecord(row, batch);
            feedingRecordMapper.insert(record);
            // 登记进分片快照：同分片后续重复行（更新路径）能命中本记录。
            ref.feedingRecordsById.put(record.getId(), record);
            observation.setFeedingRecordId(record.getId());
            observation.setFeedAmountKg(row.getFeedAmountKg());
        }
        if (row.getSurvivalRate() != null) {
            SurvivalReport report = newSurvivalReport(row, batch);
            survivalReportMapper.insert(report);
            ref.survivalReportsById.put(report.getId(), report);
            observation.setSurvivalReportId(report.getId());
            observation.setSurvivalRate(row.getSurvivalRate());
        }
        if (row.hasSensor()) {
            SensorReading reading = newSensorReading(row, device);
            sensorReadingMapper.insert(reading);
            ref.sensorReadingsById.put(reading.getId(), reading);
            observation.setSensorReadingId(reading.getId());
            observation.setSensorValue(row.getSensorValue());
            observation.setSensorUnit(row.getSensorUnit());
            observation.setSourceDevice(row.getSourceDevice());
        }

        observationMapper.insert(observation);
        return RowApplyResult.of(ObsRowOutcome.SUCCESS, observation);
    }

    // ---------------- 更新 ----------------

    private RowApplyResult doUpdate(long importId, ValidatedObsRow row, ImportRefData ref,
                                    CageObservation existing, UnderwaterSensor device) {
        // 保护判定按“本行要改写的数据域”生效：纯传感器行不受投饵落账/出网验收影响。
        boolean touchesBatchData = row.getFeedAmountKg() != null || row.getSurvivalRate() != null;

        // 已锁定：本行要改写的联动投饵记录已落账，拒绝覆盖。
        FeedingRecord linkedRecord = feedingRecordOf(ref, existing.getFeedingRecordId());
        if (row.getFeedAmountKg() != null && linkedRecord != null
                && Integer.valueOf(1).equals(linkedRecord.getPosted())) {
            return RowApplyResult.failed("_row", null,
                    "关联投饵记录已落账（数据已锁定），不得覆盖: " + linkedRecord.getRecordNo());
        }
        if (touchesBatchData) {
            // 已验收：网箱出网单已验收，批次级观测数据（投饵/存活率）拒绝覆盖。
            if (ref.acceptedCages.contains(row.getCageNo())) {
                return RowApplyResult.failed("cage_no", row.getCageNo(),
                        "网箱出网批次已验收，观测数据不得覆盖: " + row.getCageNo());
            }
            // 批次已终结（出网/关闭）：拒绝覆盖批次级数据。
            Long linkedBatchId = linkedRecord != null ? linkedRecord.getBatchId() : linkedBatchIdOf(existing, ref);
            if (linkedBatchId != null) {
                FishBatch linkedBatch = ref.batchesById.get(linkedBatchId);
                if (linkedBatch != null && isTerminal(linkedBatch)) {
                    return RowApplyResult.failed("cage_no", row.getCageNo(),
                            "关联批次已终结（" + linkedBatch.getStatus() + "），观测数据不得覆盖");
                }
            }
        }

        if (row.getFeedAmountKg() != null) {
            if (linkedRecord != null) {
                // CAS 防并发落账：改写仅当记录仍未落账；affected=0 表示被落账任务抢先，按已锁定拒绝。
                int affected = feedingRecordMapper.updateAmountIfUnposted(linkedRecord.getId(),
                        row.getFeedAmountKg(), row.getObservedAt(), LocalDateTime.now());
                if (affected == 0) {
                    return RowApplyResult.failed("_row", null,
                            "关联投饵记录已落账（数据已锁定），不得覆盖: " + linkedRecord.getRecordNo());
                }
                linkedRecord.setAmountKg(row.getFeedAmountKg());
                linkedRecord.setFeedingTime(row.getObservedAt());
            } else {
                FishBatch batch = requireActiveBatch(row, ref);
                if (batch == null) {
                    return noActiveBatchFailure(row, ref);
                }
                FeedingRecord record = newFeedingRecord(row, batch);
                feedingRecordMapper.insert(record);
                ref.feedingRecordsById.put(record.getId(), record);
                existing.setFeedingRecordId(record.getId());
            }
            existing.setFeedAmountKg(row.getFeedAmountKg());
        }

        if (row.getSurvivalRate() != null) {
            SurvivalReport linkedReport = survivalReportOf(ref, existing.getSurvivalReportId());
            if (linkedReport != null) {
                linkedReport.setSurvivalRate(row.getSurvivalRate());
                FishBatch reportBatch = ref.batchesById.get(linkedReport.getBatchId());
                linkedReport.setAliveCount(computeAliveCount(row.getSurvivalRate(), reportBatch));
                linkedReport.setReportTime(row.getObservedAt());
                survivalReportMapper.updateById(linkedReport);
            } else {
                FishBatch batch = requireActiveBatch(row, ref);
                if (batch == null) {
                    return noActiveBatchFailure(row, ref);
                }
                SurvivalReport report = newSurvivalReport(row, batch);
                survivalReportMapper.insert(report);
                ref.survivalReportsById.put(report.getId(), report);
                existing.setSurvivalReportId(report.getId());
            }
            existing.setSurvivalRate(row.getSurvivalRate());
        }

        if (row.hasSensor()) {
            SensorReading linkedReading = sensorReadingOf(ref, existing.getSensorReadingId());
            if (linkedReading != null) {
                linkedReading.setMetricValue(row.getSensorValue());
                linkedReading.setReadingTime(row.getObservedAt());
                linkedReading.setSensorId(device.getId());
                linkedReading.setCageNo(device.getCageNo());
                sensorReadingMapper.updateById(linkedReading);
            } else {
                SensorReading reading = newSensorReading(row, device);
                sensorReadingMapper.insert(reading);
                ref.sensorReadingsById.put(reading.getId(), reading);
                existing.setSensorReadingId(reading.getId());
            }
            existing.setSensorValue(row.getSensorValue());
            existing.setSensorUnit(row.getSensorUnit());
            existing.setSourceDevice(row.getSourceDevice());
        }

        existing.setImportId(importId);
        observationMapper.updateById(existing);
        return RowApplyResult.of(ObsRowOutcome.UPDATED, existing);
    }

    // ---------------- 联动记录构造 ----------------

    private FeedingRecord newFeedingRecord(ValidatedObsRow row, FishBatch batch) {
        FeedingRecord record = new FeedingRecord();
        record.setRecordNo(derivedNo("FR-", row.businessKey()));
        record.setBatchId(batch.getId());
        record.setCageNo(row.getCageNo());
        record.setFeedType(IMPORT_FEED_TYPE);
        record.setAmountKg(row.getFeedAmountKg());
        record.setFeedingTime(row.getObservedAt());
        record.setPosted(0);
        return record;
    }

    private SurvivalReport newSurvivalReport(ValidatedObsRow row, FishBatch batch) {
        SurvivalReport report = new SurvivalReport();
        report.setReportNo(derivedNo("SR-", row.businessKey()));
        report.setBatchId(batch.getId());
        report.setCageNo(row.getCageNo());
        report.setAliveCount(computeAliveCount(row.getSurvivalRate(), batch));
        report.setSurvivalRate(row.getSurvivalRate());
        report.setReportTime(row.getObservedAt());
        report.setRemark("CSV导入观测联动生成");
        return report;
    }

    private SensorReading newSensorReading(ValidatedObsRow row, UnderwaterSensor device) {
        SensorReading reading = new SensorReading();
        reading.setReadingNo(derivedNo("RD-", row.businessKey()));
        reading.setSensorId(device.getId());
        reading.setCageNo(device.getCageNo());
        reading.setMetricValue(row.getSensorValue());
        reading.setReadingTime(row.getObservedAt());
        return reading;
    }

    // ---------------- 辅助 ----------------

    /** 已存在观测与本次提供的数据是否完全一致（仅比较行内出现的字段）。 */
    private boolean matchesProvided(CageObservation existing, ValidatedObsRow row) {
        if (row.getFeedAmountKg() != null
                && !sameDecimal(existing.getFeedAmountKg(), row.getFeedAmountKg())) {
            return false;
        }
        if (row.getSurvivalRate() != null
                && !sameDecimal(existing.getSurvivalRate(), row.getSurvivalRate())) {
            return false;
        }
        if (row.hasSensor()) {
            return sameDecimal(existing.getSensorValue(), row.getSensorValue())
                    && row.getSensorUnit().equals(existing.getSensorUnit())
                    && row.getSourceDevice().equals(existing.getSourceDevice());
        }
        return true;
    }

    private static boolean sameDecimal(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
    }

    private FishBatch requireActiveBatch(ValidatedObsRow row, ImportRefData ref) {
        return ref.activeBatchByCage.get(row.getCageNo());
    }

    private RowApplyResult noActiveBatchFailure(ValidatedObsRow row, ImportRefData ref) {
        FishBatch terminal = ref.terminalBatchByCage.get(row.getCageNo());
        return RowApplyResult.failed("cage_no", row.getCageNo(), terminal != null
                ? "网箱批次已终结（" + terminal.getStatus() + "，数据已验收锁定），不能写入新观测"
                : "网箱无在养批次，不能写入投饵/存活率观测: " + row.getCageNo());
    }

    private Long linkedBatchIdOf(CageObservation existing, ImportRefData ref) {
        if (existing.getSurvivalReportId() != null) {
            SurvivalReport report = survivalReportOf(ref, existing.getSurvivalReportId());
            if (report != null) {
                return report.getBatchId();
            }
        }
        return null;
    }

    /** 联动记录按 id 取用：优先分片快照，未命中回源数据库（并发/跨分片场景兜底）。 */
    private FeedingRecord feedingRecordOf(ImportRefData ref, Long id) {
        if (id == null) {
            return null;
        }
        FeedingRecord cached = ref.feedingRecordsById.get(id);
        if (cached == null) {
            cached = feedingRecordMapper.selectById(id);
            if (cached != null) {
                ref.feedingRecordsById.put(id, cached);
            }
        }
        return cached;
    }

    private SurvivalReport survivalReportOf(ImportRefData ref, Long id) {
        if (id == null) {
            return null;
        }
        SurvivalReport cached = ref.survivalReportsById.get(id);
        if (cached == null) {
            cached = survivalReportMapper.selectById(id);
            if (cached != null) {
                ref.survivalReportsById.put(id, cached);
            }
        }
        return cached;
    }

    private SensorReading sensorReadingOf(ImportRefData ref, Long id) {
        if (id == null) {
            return null;
        }
        SensorReading cached = ref.sensorReadingsById.get(id);
        if (cached == null) {
            cached = sensorReadingMapper.selectById(id);
            if (cached != null) {
                ref.sensorReadingsById.put(id, cached);
            }
        }
        return cached;
    }

    private static boolean isTerminal(FishBatch batch) {
        return BatchStatus.HARVESTED.name().equals(batch.getStatus())
                || BatchStatus.CLOSED.name().equals(batch.getStatus());
    }

    /** 存活率 → 存活数量：按投苗数换算（四舍五入，不超过投苗数）。 */
    private Integer computeAliveCount(BigDecimal rate, FishBatch batch) {
        if (batch == null || batch.getFingerlingCount() == null) {
            return 0;
        }
        int alive = rate.multiply(new BigDecimal(batch.getFingerlingCount()))
                .setScale(0, RoundingMode.HALF_UP).intValue();
        return Math.min(alive, batch.getFingerlingCount());
    }

    /** 业务键派生联动记录编号：确定性前缀+散列，保证重试/重传单键一单。 */
    static String derivedNo(String prefix, String businessKey) {
        return prefix + sha256Hex(businessKey.getBytes(StandardCharsets.UTF_8)).substring(0, 29);
    }

    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
