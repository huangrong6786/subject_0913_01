package com.evops.aquaculture.enums;

/** 观测数据 CSV 导入批次状态。 */
public enum ObsImportStatus {
    /** 处理中（崩溃后可由重传/重试接管） */
    RUNNING,
    /** 全部行处理成功 */
    SUCCESS,
    /** 处理完成但存在失败行或失败分片（失败分片可重试） */
    PARTIAL,
    /** 文件级失败（表头非法/无数据行/全部失败），未产生有效导入 */
    FAILED
}
