package com.evops.aquaculture.enums;

/** 投饵计划状态。 */
public enum PlanStatus {
    /** 生效中 */
    ACTIVE,
    /** 已暂停 */
    SUSPENDED,
    /** 已结束 */
    FINISHED;

    public boolean canTransitTo(PlanStatus target) {
        if (this == target) {
            return true;
        }
        switch (this) {
            case ACTIVE:
                return target == SUSPENDED || target == FINISHED;
            case SUSPENDED:
                return target == ACTIVE || target == FINISHED;
            default:
                return false;
        }
    }
}
