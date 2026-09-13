package com.evops.aquaculture.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ReadingCreateRequest {

    @NotBlank(message = "读数流水号不能为空")
    private String readingNo;

    @NotNull(message = "传感器ID不能为空")
    private Long sensorId;

    @NotNull(message = "读数不能为空")
    @DecimalMin(value = "0", message = "读数不能为负")
    private BigDecimal metricValue;

    @NotNull(message = "读数时间不能为空")
    private LocalDateTime readingTime;
}
