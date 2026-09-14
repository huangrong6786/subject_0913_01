package com.evops.aquaculture.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 时序规则计算明细：每条命中观测一行。
 * 潮次快照与规则版本快照随明细固化——规则切换/潮次档案调整后，历史明细仍读取当时快照。
 * weightedKg 为未舍入的精确乘积，舍入只在结果合计层统一执行。
 */
@Data
@TableName("t_ts_calc_detail")
public class TsCalcDetail {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属计算结果 */
    private Long resultId;

    private Long obsId;

    private String obsNo;

    /** 观测时刻（UTC） */
    private LocalDateTime observedAtUtc;

    /** 观测时刻在网箱海区时区下的当地时间（HH:mm:ss，便于运营核对） */
    private String obsLocalTime;

    /** 潮次快照：所属潮次 */
    private Long tideSessionId;

    private String tideNo;

    /** 潮次快照：潮汐开始时刻（UTC，左闭） */
    private LocalDateTime tideStartUtc;

    /** 潮次快照：潮汐结束时刻（UTC，右开） */
    private LocalDateTime tideEndUtc;

    /** 规则快照：命中规则 */
    private Long ruleId;

    private String ruleCode;

    /** 规则快照：采用版本号 */
    private Integer ruleVersion;

    private String ruleType;

    /** 规则快照：采用系数 */
    private BigDecimal coefficient;

    /** 观测投饵量（千克） */
    private BigDecimal feedAmountKg;

    /** 折算量 = 投饵量 × 系数（精确乘积，不在本层舍入） */
    private BigDecimal weightedKg;

    private LocalDateTime createTime;
}
