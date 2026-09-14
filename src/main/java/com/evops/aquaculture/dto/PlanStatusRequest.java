package com.evops.aquaculture.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class PlanStatusRequest {

    @NotBlank(message = "目标状态不能为空")
    private String targetStatus;

    /**
     * 操作者决策时看到的鱼群（批次）版本，可选。
     * 换箱/转场与未完成投饵计划状态变更共享鱼群版本：提交时条件更新必须仍匹配该版本，
     * 并发双方基于同一旧版本提交时仅一方成功；缺省由服务端按加锁前读取值判定。
     */
    private Integer expectedBatchVersion;

    /** 操作者决策时看到的计划自身版本，可选；缺省由服务端读取。 */
    private Integer expectedVersion;
}
