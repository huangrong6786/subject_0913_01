package com.evops.aquaculture.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 投饵计划。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_feeding_plan")
public class FeedingPlan extends BaseEntity {

    /** 计划编号（关键业务键，唯一） */
    private String planNo;

    /** 所属鱼苗批次 */
    private Long batchId;

    private String cageNo;

    /** 饵料类型 */
    private String feedType;

    /** 计划日投饵量（千克） */
    private BigDecimal dailyAmountKg;

    /** 日投喂次数 */
    private Integer feedFrequency;

    private LocalDate startDate;

    private LocalDate endDate;

    /** ACTIVE/SUSPENDED/FINISHED */
    private String status;

    /** 乐观锁版本：迁移切换 cage_no 与状态流转互斥，条件更新仅一方生效 */
    private Integer version;

    /** 最近一次随群迁移时间 */
    private java.time.LocalDateTime migratedAt;

    /** 锚定的迁移事件 ID */
    private Long migrationId;
}
