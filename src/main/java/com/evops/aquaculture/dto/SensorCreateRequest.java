package com.evops.aquaculture.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class SensorCreateRequest {

    @NotBlank(message = "传感器编号不能为空")
    private String sensorNo;

    @NotBlank(message = "网箱编号不能为空")
    private String cageNo;

    @NotBlank(message = "传感器类型不能为空")
    private String sensorType;

    @NotBlank(message = "计量单位不能为空")
    private String metricUnit;

    @DecimalMin(value = "0", message = "布设水深不能为负")
    private BigDecimal depthM;

    @NotNull(message = "安装时间不能为空")
    private LocalDateTime installTime;
}
