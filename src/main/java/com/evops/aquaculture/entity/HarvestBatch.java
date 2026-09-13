package com.evops.aquaculture.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 出网批次（捕捞记录）。
 * status=ACCEPTED 表示已验收，验收后记录不可删除。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_harvest_batch")
public class HarvestBatch extends BaseEntity {

    /** 出网单号（关键业务键，唯一） */
    private String harvestNo;

    /** 来源鱼苗批次 */
    private Long fishBatchId;

    private String cageNo;

    /** 出网数量（尾） */
    private Integer harvestCount;

    /** 出网总重量（千克） */
    private BigDecimal totalWeightKg;

    private LocalDateTime harvestTime;

    /** PENDING/ACCEPTED */
    private String status;

    private LocalDateTime acceptedTime;

    private String remark;
}
