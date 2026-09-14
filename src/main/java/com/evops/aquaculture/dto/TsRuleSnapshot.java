package com.evops.aquaculture.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 规则快照：计算落库时启用规则的完整镜像。
 * 历史结果读取当时快照，规则后续切换/退役不影响已落库结果。
 */
@Data
public class TsRuleSnapshot {

    private Long ruleId;

    private String ruleCode;

    private Integer versionNo;

    private String ruleType;

    private Integer startMinute;

    private Integer endMinute;

    private BigDecimal coefficient;
}
