package com.evops.aquaculture.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class SurvivalReportCreateRequest {

    @NotBlank(message = "报告编号不能为空")
    private String reportNo;

    @NotNull(message = "鱼苗批次ID不能为空")
    private Long batchId;

    @NotNull(message = "存活数量不能为空")
    @PositiveOrZero(message = "存活数量不能为负")
    private Integer aliveCount;

    /** 存活率，可选；缺省时由投苗数量与存活数量自动计算。 */
    @DecimalMin(value = "0", message = "存活率不能小于0")
    @DecimalMax(value = "1", message = "存活率不能大于1")
    private BigDecimal survivalRate;

    @NotNull(message = "报告时间不能为空")
    private LocalDateTime reportTime;

    private String remark;
}
