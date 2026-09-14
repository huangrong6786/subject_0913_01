package com.evops.aquaculture.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 时序规则计算结果：同一批次同一业务日唯一一行。
 * 重算通过 CALCULATING 状态 CAS 抢占（并发重算不会产生两份结果），
 * 明细在同一事务内整体替换；各区间合计为 BigDecimal 精确累计后最终统一舍入。
 * ruleSnapshot 为计算当时启用规则（含版本号）的 JSON 快照，历史结果读取当时快照。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_ts_calc_result")
public class TsCalcResult extends BaseEntity {

    private Long batchId;

    private String cageNo;

    /** 业务日（网箱海区时区下的当地日期） */
    private LocalDate businessDate;

    /** CALCULATING 计算中 / DONE 完成 */
    private String status;

    /** 累计重算次数 */
    private Integer attempts;

    /** 纳入归算的观测总数（业务日潮次时间窗内） */
    private Integer obsTotal;

    /** 命中规则区间的观测数 */
    private Integer obsMatched;

    /** 未命中任何规则区间的观测数 */
    private Integer obsUnmatched;

    /** 峰值区间折算合计（千克，最终统一舍入） */
    private BigDecimal peakTotalKg;

    /** 平段区间折算合计（千克） */
    private BigDecimal flatTotalKg;

    /** 谷值区间折算合计（千克） */
    private BigDecimal valleyTotalKg;

    /** 全部区间折算合计（千克） */
    private BigDecimal totalWeightedKg;

    /** 计算当时启用规则的 JSON 快照（含规则编码、版本号、区间、系数） */
    private String ruleSnapshot;

    private LocalDateTime calcTime;
}
