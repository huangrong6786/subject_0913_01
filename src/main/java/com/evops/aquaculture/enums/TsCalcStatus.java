package com.evops.aquaculture.enums;

/**
 * 时序规则计算结果状态：
 * CALCULATING 计算中（CAS 抢占标识，并发重算只有一方持有）；
 * DONE 完成（结果与明细已同事务落库，可读取）。
 */
public enum TsCalcStatus {
    CALCULATING,
    DONE
}
