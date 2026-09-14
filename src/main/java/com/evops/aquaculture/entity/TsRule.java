package com.evops.aquaculture.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 时序规则（版本化）：把一日划分为峰值/平段/谷值业务区间，区间内投饵量按系数折算。
 * 区间左闭右开 [startMinute, endMinute)；endMinute &lt;= startMinute 表示跨午夜区间。
 * 启用后内容冻结不能原地修改，调整须新建版本；历史计算结果保留当时采用的版本快照。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_ts_rule")
public class TsRule extends BaseEntity {

    /** 规则业务编码（同一编码的版本单调递增） */
    private String ruleCode;

    /** 版本号（自 1 起递增） */
    private Integer versionNo;

    /** 区间类型：PEAK 峰值 / FLAT 平段 / VALLEY 谷值 */
    private String ruleType;

    /** 区间开始（日内分钟，左闭，0..1439） */
    private Integer startMinute;

    /** 区间结束（日内分钟，右开，1..1440；不大于 startMinute 表示跨午夜） */
    private Integer endMinute;

    /** 折算系数 */
    private BigDecimal coefficient;

    /** 状态：DRAFT 草稿 / ENABLED 启用 / RETIRED 退役 */
    private String status;

    private LocalDateTime enabledTime;

    private LocalDateTime retiredTime;
}
