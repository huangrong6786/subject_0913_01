package com.evops.aquaculture.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 网箱迁移链：同一鱼群（鱼苗批次）的一条不可断裂时间链。
 * 链上每个迁移事件使 current_seq +1、current_cage_no 前移一个网箱；
 * current_seq 同时是乐观锁版本：链头条件更新 WHERE current_seq=期望值，
 * 两个操作者并发提交同一鱼群的迁移时仅一方 affected=1。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_cage_migration_chain")
public class CageMigrationChain extends BaseEntity {

    /** 链编号（关键业务键，唯一） */
    private String chainNo;

    /** 鱼群身份：鱼苗批次 ID（一批鱼一条链，唯一） */
    private Long batchId;

    /** 建链时的起始网箱（时间链起点，不再变化） */
    private String originCageNo;

    /** 当前所在网箱（链头指针） */
    private String currentCageNo;

    /** 当前链头版本/已发生迁移次数（乐观锁版本） */
    private Integer currentSeq;
}
