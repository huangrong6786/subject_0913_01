package com.evops.aquaculture.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 存活率报告（网箱批次的阶段性盘点结果）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_survival_report")
public class SurvivalReport extends BaseEntity {

    /** 报告编号（关键业务键，唯一） */
    private String reportNo;

    private Long batchId;

    private String cageNo;

    /** 存活数量（尾） */
    private Integer aliveCount;

    /** 存活率，0~1（四位小数） */
    private BigDecimal survivalRate;

    private LocalDateTime reportTime;

    private String remark;
}
