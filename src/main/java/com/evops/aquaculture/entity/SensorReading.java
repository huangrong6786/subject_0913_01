package com.evops.aquaculture.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 水下传感器读数。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_sensor_reading")
public class SensorReading extends BaseEntity {

    /** 读数流水号（关键业务键，唯一） */
    private String readingNo;

    private Long sensorId;

    private String cageNo;

    /** 读数值（带单位见传感器 metricUnit） */
    private BigDecimal metricValue;

    private LocalDateTime readingTime;

    /** 读数归属锚点：读数发生时鱼群所处迁移段（历史读数留原箱，按 reading_time 与迁移时刻切分） */
    private Long migrationId;
}
