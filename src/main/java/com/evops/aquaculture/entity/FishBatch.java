package com.evops.aquaculture.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 鱼苗批次。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_fish_batch")
public class FishBatch extends BaseEntity {

    /** 批次编号（关键业务键，唯一） */
    private String batchNo;

    /** 网箱编号（业务关联键，不建外框约束，支持跨表关联查询） */
    private String cageNo;

    /** 鱼种 */
    private String species;

    /** 投苗数量（尾） */
    private Integer fingerlingCount;

    /** 平均规格（克/尾） */
    private BigDecimal averageWeightG;

    /** 状态：BREEDING/MONITORING/HARVESTED/CLOSED */
    private String status;

    /** 投苗时间 */
    private LocalDateTime stockingTime;

    /** 乐观锁版本：每次迁移/状态改写 +1，迁移按版本条件更新，并发仅一方成功 */
    private Integer version;

    /** 最近一次迁移发生时间（迁移时间点把鱼群锚定到当前网箱） */
    private LocalDateTime migratedAt;

    /** 使其落位当前网箱的迁移事件 ID（首段在养为 null） */
    private Long migrationId;

    private String remark;
}
