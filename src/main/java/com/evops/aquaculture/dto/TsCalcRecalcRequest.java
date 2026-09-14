package com.evops.aquaculture.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.time.LocalDate;

/** 触发/重算某批次某业务日的时序规则计算。 */
@Data
public class TsCalcRecalcRequest {

    @NotNull(message = "养殖批次ID不能为空")
    private Long batchId;

    @NotNull(message = "业务日不能为空")
    private LocalDate businessDate;
}
