package com.evops.aquaculture.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 时序规则计算结果视图：结果行 + 计算当时采用的规则版本快照。
 * status = DONE 计算完成；SKIPPED 表示本次触发被并发执行方占用而跳过。
 */
@Data
public class TsCalcResultView {

    private Long resultId;

    private Long batchId;

    private String cageNo;

    private String businessDate;

    private String status;

    private Integer attempts;

    private Integer obsTotal;

    private Integer obsMatched;

    private Integer obsUnmatched;

    private BigDecimal peakTotalKg;

    private BigDecimal flatTotalKg;

    private BigDecimal valleyTotalKg;

    private BigDecimal totalWeightedKg;

    private String calcTime;

    /** 计算当时启用规则的版本快照（历史结果读取当时快照） */
    private List<TsRuleSnapshot> ruleSnapshot;

    private String message;
}
