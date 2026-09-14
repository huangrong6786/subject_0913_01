package com.evops.aquaculture.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 迁移交接快照（insert-only，随迁移事件写入一次，永不回写）。
 * 原对象结束值与新对象起始值逐项并列，供前后对象勾稽核对。
 */
@Data
public class CageMigrationSnapshot {

    private String migrationNo;
    private String chainNo;
    private Integer seqNo;
    private String transferType;
    private Long batchId;
    private String batchNo;
    private String occurredAt;

    /** 鱼群估算量口径：LATEST_SURVIVAL 最新存活报告存活数 / FINGERLING 投苗数 */
    private String fishEstimateBasis;

    /** 原对象结束值 */
    private Side source;
    /** 新对象起始值（迁移时刻必须与 source 逐项一致） */
    private Side target;

    @Data
    public static class Side {
        private String cageNo;
        /** 鱼群估算量（尾） */
        private Integer fishEstimateCount;
        /** 累计已落账投饵量（kg） */
        private BigDecimal cumulativePostedFeedKg;
        /** 累计未落账投饵量（kg，备查） */
        private BigDecimal cumulativeUnpostedFeedKg;
        /** 投饵记录条数（其中已落账条数另计） */
        private Integer feedingRecordCount;
        private Integer postedRecordCount;
        /** 最新存活率（0~1） */
        private BigDecimal latestSurvivalRate;
        private String latestSurvivalAt;
        /** 未完成投饵计划数（随群迁移） */
        private Integer feedingPlanCount;
        private List<PlanRef> feedingPlans;
        /** 随群物理迁移的传感器（ONLINE） */
        private List<SensorSnapshot> movedSensors;
        /** 留在原箱的停用/维护传感器（历史读数仍可核对） */
        private List<SensorSnapshot> retainedSensors;
    }

    @Data
    public static class PlanRef {
        private Long id;
        private String planNo;
        private String status;
        private BigDecimal dailyAmountKg;
        /** 迁移时刻版本凭证 */
        private Integer version;
    }

    @Data
    public static class SensorSnapshot {
        private Long id;
        private String sensorNo;
        private String sensorType;
        private String metricUnit;
        private String status;
        /** 迁移前最新读数（原对象结束值），target 侧同值即新对象起始值 */
        private BigDecimal latestValue;
        private String latestReadingAt;
        /** 该设备历史读数条数 */
        private Integer readingCount;
        /** 是否物理迁移到新箱 */
        private Boolean moved;
        /** 迁移时刻版本凭证 */
        private Integer version;
    }
}
