package com.evops.aquaculture.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 水下传感器。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_underwater_sensor")
public class UnderwaterSensor extends BaseEntity {

    /** 传感器编号（关键业务键，唯一） */
    private String sensorNo;

    private String cageNo;

    /** 传感器类型：TEMPERATURE/DISSOLVED_OXYGEN/PH/SALINITY/TURBIDITY 等 */
    private String sensorType;

    /** 计量单位，如 ℃、mg/L */
    private String metricUnit;

    /** 布设水深（米） */
    private BigDecimal depthM;

    private LocalDateTime installTime;

    /** ONLINE/OFFLINE/MAINTENANCE */
    private String status;
}
