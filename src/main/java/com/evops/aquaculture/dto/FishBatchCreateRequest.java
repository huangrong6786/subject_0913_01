package com.evops.aquaculture.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class FishBatchCreateRequest {

    @NotBlank(message = "批次编号不能为空")
    private String batchNo;

    @NotBlank(message = "网箱编号不能为空")
    private String cageNo;

    @NotBlank(message = "鱼种不能为空")
    private String species;

    @NotNull(message = "投苗数量不能为空")
    @Positive(message = "投苗数量必须为正整数")
    private Integer fingerlingCount;

    @DecimalMin(value = "0", message = "平均规格不能为负")
    private BigDecimal averageWeightG;

    @NotNull(message = "投苗时间不能为空")
    private LocalDateTime stockingTime;

    private String remark;
}
