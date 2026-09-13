package com.evops.aquaculture.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class FeedingPlanCreateRequest {

    @NotBlank(message = "计划编号不能为空")
    private String planNo;

    @NotNull(message = "鱼苗批次ID不能为空")
    private Long batchId;

    @NotBlank(message = "饵料类型不能为空")
    private String feedType;

    @NotNull(message = "日投饵量不能为空")
    @DecimalMin(value = "0", inclusive = false, message = "日投饵量必须大于0")
    private BigDecimal dailyAmountKg;

    @NotNull(message = "投喂次数不能为空")
    @Positive(message = "投喂次数必须为正整数")
    private Integer feedFrequency;

    @NotNull(message = "开始日期不能为空")
    private LocalDate startDate;

    @NotNull(message = "结束日期不能为空")
    private LocalDate endDate;
}
