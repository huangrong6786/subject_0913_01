package com.evops.aquaculture.enums;

/** 观测数据导入分片状态。 */
public enum ObsShardStatus {
    /** 待处理（导入中断后由重试接管） */
    PENDING,
    /** 处理中（超过租约时长视为僵死，可接管重试） */
    RUNNING,
    /** 分片内全部行成功 */
    SUCCESS,
    /** 分片处理完成但存在失败行（终态，行级失败不通过分片重试修复，需修正数据后重新上传） */
    PARTIAL,
    /** 分片级异常（如存储闪断），可重试 */
    FAILED
}
