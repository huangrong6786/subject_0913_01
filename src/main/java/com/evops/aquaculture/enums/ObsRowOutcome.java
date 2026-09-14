package com.evops.aquaculture.enums;

/** 观测数据导入逐行处理结果。 */
public enum ObsRowOutcome {
    /** 新增（业务键首次出现） */
    SUCCESS,
    /** 更新（业务键已存在且内容有变化，未被锁定） */
    UPDATED,
    /** 跳过（业务键已存在且内容一致，幂等重传不产生副作用） */
    SKIPPED,
    /** 失败（校验未通过或目标数据已锁定/已验收，整行拒绝，不影响其他行） */
    FAILED
}
