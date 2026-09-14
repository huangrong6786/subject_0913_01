package com.evops.aquaculture.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/** 登记潮次：开始/结束时刻（UTC）左闭右开；跨午夜潮次按网箱海区时区归属业务日。 */
@Data
public class TideSessionCreateRequest {

    @NotBlank(message = "潮次编号不能为空")
    private String tideNo;

    @NotBlank(message = "网箱编号不能为空")
    private String cageNo;

    @NotNull(message = "潮汐开始时刻不能为空")
    private LocalDateTime startAtUtc;

    @NotNull(message = "潮汐结束时刻不能为空")
    private LocalDateTime endAtUtc;
}
