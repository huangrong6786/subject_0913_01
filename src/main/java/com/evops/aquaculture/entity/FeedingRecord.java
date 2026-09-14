package com.evops.aquaculture.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 投饵记录（实际投饵量）。
 * posted=1 表示已落账，落账记录不可删除、投饵量不可修改。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_feeding_record")
public class FeedingRecord extends BaseEntity {

    /** 投饵流水号（关键业务键，唯一） */
    private String recordNo;

    private Long batchId;

    private Long planId;

    private String cageNo;

    private String feedType;

    /** 实际投饵量（千克） */
    private BigDecimal amountKg;

    private LocalDateTime feedingTime;

    /** 是否落账：0 否，1 是 */
    private Integer posted;

    private LocalDateTime postedTime;

    /** 若该投饵由迁移后新箱登记，锚定使其落位的迁移事件（历史流水留原箱，按时间轴切分） */
    private Long migrationId;
}
