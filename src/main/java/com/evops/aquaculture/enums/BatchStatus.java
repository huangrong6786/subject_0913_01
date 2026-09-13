package com.evops.aquaculture.enums;

/** 鱼苗批次生命周期状态。 */
public enum BatchStatus {
    /** 养殖中（已投苗） */
    BREEDING,
    /** 监测中（有投饵/传感器监测，业务上仍属在养） */
    MONITORING,
    /** 已出网 */
    HARVESTED,
    /** 已关闭归档 */
    CLOSED;

    /**
     * 允许的批次状态流转：
     * BREEDING -> MONITORING / HARVESTED / CLOSED
     * MONITORING -> BREEDING / HARVESTED / CLOSED
     * HARVESTED -> CLOSED
     * CLOSED 为终态。
     */
    public boolean canTransitTo(BatchStatus target) {
        if (this == target) {
            return true;
        }
        switch (this) {
            case BREEDING:
                return target == MONITORING || target == HARVESTED || target == CLOSED;
            case MONITORING:
                return target == BREEDING || target == HARVESTED || target == CLOSED;
            case HARVESTED:
                return target == CLOSED;
            default:
                return false;
        }
    }
}
