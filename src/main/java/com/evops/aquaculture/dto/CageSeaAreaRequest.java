package com.evops.aquaculture.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class CageSeaAreaRequest {

    @NotBlank(message = "网箱编号不能为空")
    private String cageNo;

    @NotBlank(message = "海区不能为空")
    private String seaArea;

    @NotBlank(message = "业务时区不能为空")
    private String timeZone;

    private String remark;
}
