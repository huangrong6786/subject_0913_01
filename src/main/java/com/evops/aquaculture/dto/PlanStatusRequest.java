package com.evops.aquaculture.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class PlanStatusRequest {

    @NotBlank(message = "目标状态不能为空")
    private String targetStatus;
}
