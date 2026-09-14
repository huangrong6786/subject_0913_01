package com.evops.aquaculture.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 登记投饵监测观测：观测时刻按 UTC 传入，归算时换算到网箱海区时区。 */
@Data
public class FeedObservationCreateRequest {

    @NotBlank(message = "观测编号不能为空")
    private String obsNo;

    @NotNull(message = "养殖批次ID不能为空")
    private Long batchId;

    @NotNull(message = "观测时刻不能为空")
    private LocalDateTime observedAtUtc;

    @NotNull(message = "观测投饵量不能为空")
    @DecimalMin(value = "0", inclusive = false, message = "观测投饵量必须大于0")
    private BigDecimal feedAmountKg;
}
