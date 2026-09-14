package com.evops.aquaculture.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.PositiveOrZero;
import java.time.LocalDateTime;

/**
 * 网箱替换/转移申请。
 * 操作者必须显式给出（不依赖登录上下文），迁移事件固化 operatorId/operatorName。
 */
@Data
public class CageMigrationRequest {

    /** 迁移单号（关键业务键，唯一，由调用方生成） */
    @NotBlank(message = "迁移单号不能为空")
    @Pattern(regexp = "^[A-Za-z0-9_-]{1,40}$", message = "迁移单号仅允许字母/数字/中划线/下划线，≤40 字符")
    private String migrationNo;

    /** 被迁移鱼群（鱼苗批次）ID */
    @NotNull(message = "鱼苗批次ID不能为空")
    private Long batchId;

    /** 目标网箱编号（新对象）；不得与当前网箱相同，且目标网箱须为空网箱 */
    @NotBlank(message = "目标网箱编号不能为空")
    @Pattern(regexp = "^[A-Za-z0-9_-]{1,32}$", message = "网箱编号仅允许字母/数字/中划线/下划线，≤32 字符")
    private String targetCageNo;

    /** REPLACE 换箱 / TRANSFER 转场，缺省 TRANSFER */
    private String transferType;

    /**
     * 操作者 ID，可空：缺省时取请求头 X-Operator-Id，再缺省由 Basic 登录账号稳定派生。
     * 迁移事件会固化实际操作者（0 为系统）。
     */
    @PositiveOrZero(message = "操作者ID不能为负数")
    private Long operatorId;

    /** 操作者名称/账号，可空：缺省取请求头 X-Operator-Name，再缺省取 Basic 登录账号 */
    @Pattern(regexp = "^[一-龥A-Za-z0-9_.-]{1,64}$", message = "操作者名称≤64 字符")
    private String operatorName;

    /** 发生时间，缺省取服务端当前时间；不允许晚于当前时间 */
    private LocalDateTime occurredAt;

    /**
     * 操作者决策时看到的鱼群（批次）版本，可选。两个操作者并发提交迁移、或迁移与投饵计划
     * 状态变更并发时，条件更新必须仍匹配该版本，仅一方成功；缺省由服务端按加锁前读取值判定。
     */
    @PositiveOrZero(message = "期望批次版本不能为负数")
    private Integer expectedBatchVersion;

    /** 操作者决策时看到的迁移链头序号，可选；缺省由服务端读取。 */
    @PositiveOrZero(message = "期望链版本不能为负数")
    private Integer expectedChainSeq;

    private String remark;
}
