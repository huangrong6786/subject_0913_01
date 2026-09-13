package com.evops.aquaculture.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class FeedingRecordCreateRequest {

    @NotBlank(message = "投饵流水号不能为空")
    private String recordNo;

    @NotNull(message = "鱼苗批次ID不能为空")
    private Long batchId;

    private Long planId;

    @NotBlank(message = "饵料类型不能为空")
    private String feedType;

    @NotNull(message = "投饵量不能为空")
    @DecimalMin(value = "0", inclusive = false, message = "投饵量必须大于0")
    private BigDecimal amountKg;

    @NotNull(message = "投饵时间不能为空")
    private LocalDateTime feedingTime;
}
