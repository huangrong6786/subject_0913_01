package com.evops.aquaculture.enums;

/** 时序规则业务区间类型：峰值 / 平段 / 谷值。 */
public enum RuleType {
    /** 峰值区间（如摄食高峰） */
    PEAK,
    /** 平段区间 */
    FLAT,
    /** 谷值区间 */
    VALLEY
}
