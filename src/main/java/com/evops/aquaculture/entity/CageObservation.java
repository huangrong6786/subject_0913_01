package com.evops.aquaculture.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 网箱观测数据（CSV 批量导入落地区）。
 * 幂等业务键 = (voyageNo, cageNo, observedAt)：航次断网恢复后重复上传同一文件时，
 * 行级 upsert 保证同一采样时刻的观测只入账一次。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_cage_observation")
public class CageObservation extends BaseEntity {

    /** 航次号（业务键之一） */
    private String voyageNo;

    /** 网箱号/对象编号（业务键之一） */
    private String cageNo;

    /** 采样时刻/观测时间（业务键之一） */
    private LocalDateTime observedAt;

    /** 投饵量（千克），可空表示本次未观测 */
    private BigDecimal feedAmountKg;

    /** 存活率 0~1（四位小数），可空 */
    private BigDecimal survivalRate;

    /** 水下传感器读数，可空 */
    private BigDecimal sensorValue;

    /** 读数单位（须与来源设备登记单位一致） */
    private String sensorUnit;

    /** 来源设备编号（对应 t_underwater_sensor.sensor_no） */
    private String sourceDevice;

    /** 联动生成的投饵记录 id（已落账则本观测锁定，不得覆盖） */
    private Long feedingRecordId;

    /** 联动生成的存活率报告 id */
    private Long survivalReportId;

    /** 联动生成的传感器读数 id */
    private Long sensorReadingId;

    /** 最近一次写入本观测的导入批次 id */
    private Long importId;
}
