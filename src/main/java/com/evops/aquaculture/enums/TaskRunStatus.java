package com.evops.aquaculture.enums;

/** 任务执行台账状态。RUNNING 执行中；SUCCESS/FAILED 为终态（FAILED 可重试）。 */
public enum TaskRunStatus {
    RUNNING,
    SUCCESS,
    FAILED
}
