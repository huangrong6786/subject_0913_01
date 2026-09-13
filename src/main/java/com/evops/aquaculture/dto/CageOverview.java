package com.evops.aquaculture.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 网箱监测视图：按网箱编号聚合批次、投饵量、存活率与水下传感器读数。
 */
@Data
public class CageOverview {

    private String cageNo;

    /** 在养批次（BREEDING/MONITORING/HARVESTED，不含 CLOSED） */
    private List<FishBatchView> batches;

    /** 网箱内传感器 */
    private List<SensorView> sensors;

    /** 已落账投饵量合计（千克） */
    private BigDecimal postedFeedAmountKg;

    /** 出网批次 */
    private List<HarvestView> harvests;

    @Data
    public static class FishBatchView {
        private Long batchId;
        private String batchNo;
        private String species;
        private Integer fingerlingCount;
        private String status;
        /** 批次已落账投饵量合计（千克） */
        private BigDecimal postedFeedAmountKg;
        /** 最新存活率（0~1），无报告时为 null */
        private BigDecimal latestSurvivalRate;
    }

    @Data
    public static class SensorView {
        private Long sensorId;
        private String sensorNo;
        private String sensorType;
        private String metricUnit;
        private String status;
        /** 最新读数 */
        private BigDecimal latestValue;
        private String latestReadingTime;
    }

    @Data
    public static class HarvestView {
        private Long harvestId;
        private String harvestNo;
        private Integer harvestCount;
        private BigDecimal totalWeightKg;
        private String status;
        private String harvestTime;
    }
}
