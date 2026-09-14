package com.evops.aquaculture.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/** 新建时序规则：ruleCode 已存在时自动生成下一版本（草稿）。 */
@Data
public class TsRuleCreateRequest {

    @NotBlank(message = "规则编码不能为空")
    private String ruleCode;

    @NotBlank(message = "区间类型不能为空")
    private String ruleType;

    @NotNull(message = "区间开始分钟不能为空")
    private Integer startMinute;

    @NotNull(message = "区间结束分钟不能为空")
    private Integer endMinute;

    @NotNull(message = "折算系数不能为空")
    @DecimalMin(value = "0", message = "折算系数不能为负")
    private BigDecimal coefficient;
}
