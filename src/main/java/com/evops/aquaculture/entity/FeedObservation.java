package com.evops.aquaculture.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 投饵监测观测：观测时刻统一按 UTC 存储，
 * 时序规则计算时按网箱所在海区时区换算当地时刻后归入业务区间。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_feed_observation")
public class FeedObservation extends BaseEntity {

    /** 观测编号（关键业务键，唯一） */
    private String obsNo;

    private String cageNo;

    /** 所属养殖批次 */
    private Long batchId;

    /** 观测时刻（UTC） */
    private LocalDateTime observedAtUtc;

    /** 观测投饵量（千克） */
    private BigDecimal feedAmountKg;
}
