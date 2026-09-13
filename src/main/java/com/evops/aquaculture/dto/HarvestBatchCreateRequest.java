package com.evops.aquaculture.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class HarvestBatchCreateRequest {

    @NotBlank(message = "出网单号不能为空")
    private String harvestNo;

    @NotNull(message = "来源鱼苗批次ID不能为空")
    private Long fishBatchId;

    @NotNull(message = "出网数量不能为空")
    @Positive(message = "出网数量必须为正整数")
    private Integer harvestCount;

    @NotNull(message = "出网总重量不能为空")
    @DecimalMin(value = "0", inclusive = false, message = "出网总重量必须大于0")
    private BigDecimal totalWeightKg;

    @NotNull(message = "出网时间不能为空")
    private LocalDateTime harvestTime;

    private String remark;
}
