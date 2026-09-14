package com.evops.aquaculture.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 潮次：一次潮汐过程，开始/结束时刻左闭右开 [startAtUtc, endAtUtc)。
 * 跨午夜潮次按网箱所在海区时区归属业务日（businessDate = 开始时刻在海区时区下的当地日期）。
 * seaArea/timeZone 为登记时网箱海区档案快照，档案调整后历史潮次归属不变。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tide_session")
public class TideSession extends BaseEntity {

    /** 潮次编号（关键业务键，唯一） */
    private String tideNo;

    private String cageNo;

    /** 登记时网箱所属海区（快照） */
    private String seaArea;

    /** 登记时网箱业务时区（快照） */
    private String timeZone;

    /** 潮汐开始时刻（UTC，左闭） */
    private LocalDateTime startAtUtc;

    /** 潮汐结束时刻（UTC，右开） */
    private LocalDateTime endAtUtc;

    /** 归属业务日：startAtUtc 在海区时区下的当地日期 */
    private LocalDate businessDate;
}
