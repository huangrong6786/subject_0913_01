package com.evops.aquaculture.service;

import com.evops.aquaculture.entity.FeedingRecord;
import com.evops.aquaculture.entity.FishBatch;
import com.evops.aquaculture.entity.SensorReading;
import com.evops.aquaculture.entity.SurvivalReport;
import com.evops.aquaculture.entity.UnderwaterSensor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 分片级参考数据快照（每个分片处理前重新加载，长导入中也能感知主数据变化）：
 * 传感器、批次、已验收网箱，以及更新路径需要的既有联动记录。
 */
class ImportRefData {

    /** 全部传感器（sensor_no -> 传感器） */
    final Map<String, UnderwaterSensor> sensorsByNo = new HashMap<>();

    /** 网箱 -> 最新在养批次（BREEDING/MONITORING） */
    final Map<String, FishBatch> activeBatchByCage = new HashMap<>();

    /** 网箱 -> 最新终态批次（HARVESTED/CLOSED），用于失败原因说明 */
    final Map<String, FishBatch> terminalBatchByCage = new HashMap<>();

    /** 批次 id -> 批次 */
    final Map<Long, FishBatch> batchesById = new HashMap<>();

    /** 存在已验收出网单的网箱号 */
    final Set<String> acceptedCages = new HashSet<>();

    /** 既有观测联动记录（按 id 预取，更新路径判定落账锁定用） */
    final Map<Long, FeedingRecord> feedingRecordsById = new HashMap<>();

    final Map<Long, SurvivalReport> survivalReportsById = new HashMap<>();

    final Map<Long, SensorReading> sensorReadingsById = new HashMap<>();
}
