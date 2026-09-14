package com.evops.aquaculture.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 网箱迁移事件（历史台账）。
 *
 * 台账只增不改不删（insert-only）：每个事件固化“原对象结束值 → 新对象起始值”的完整交接快照，
 * 迁移完成后任何接口不得 update/delete 本表行，保证历史快照不可被回写。
 *
 * 可核对字段：
 *  - 鱼群估算量 fishEstimateCount：原网箱结束值 = 新网箱起始值；
 *  - 累计投饵量 cumulativeFeedKg：已落账口径在迁移时刻的批次累计，前后衔接；
 *  - 最新存活率 latestSurvivalRate：迁移时刻该批次最新报告值；
 *  - 传感器最新读数：snapshot_json 内逐设备列示，仅物理迁移 ONLINE+有读数的设备，
 *    OFFLINE/MAINTENANCE 留在原箱；offline 设备若有历史读数也列入快照备查。
 *
 * 并发凭证：
 *  - batchVersion/planVersions/sensorVersions 记录迁移时刻各对象版本号，
 *    与链头 seq 一起构成多对象条件更新（CAS），并发双方仅一方全部匹配成功。
 */
@Data
@TableName("t_cage_migration")
public class CageMigration {

    /** 主键（insert-only 台账，不继承 BaseEntity：本表无 update_by/update_time，杜绝任何更新语义） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 落库时间（数据库默认 CURRENT_TIMESTAMP），仅用于台账展示，不作为业务发生时间 */
    private java.time.LocalDateTime createTime;

    /** 迁移单号（关键业务键，唯一） */
    private String migrationNo;

    private Long chainId;

    private Long batchId;

    /** 链上序号（第几次迁移，从 1 起；与链头版本严格相等） */
    private Integer seqNo;

    /** REPLACE 换箱 / TRANSFER 转场 */
    private String transferType;

    private String sourceCageNo;

    private String targetCageNo;

    /** 发生时间 */
    private LocalDateTime occurredAt;

    /** 操作者 ID（>=0，真实操作者；0 表示系统） */
    private Long operatorId;

    /** 操作者名称/账号（冗余存档，账号体系变化不影响历史可读） */
    private String operatorName;

    /** 鱼群估算量（尾）：原对象结束值=新对象起始值，迁移期间不允许出现鱼量差异 */
    private Integer fishEstimateCount;

    /** 累计投饵量（kg，已落账未落账分别列示，默认取已落账口径） */
    private BigDecimal cumulativeFeedKg;

    /** 迁移时刻最新存活率（0~1），无报告时为 null */
    private BigDecimal latestSurvivalRate;

    /** 随群迁移的未完成投饵计划数（同事务切换 cage_no，并发改计划状态与之冲突仅一方生效） */
    private Integer feedingPlanCount;

    /** 锚定到本事件的投饵记录数（历史流水保留在原箱，以时间轴切分） */
    private Integer feedingRecordCount;

    /** 物理迁移的传感器数量（ONLINE 且有读数） */
    private Integer sensorCount;

    /** 批次版本凭证（CAS） */
    private Integer batchVersion;

    /** 各计划版本凭证 JSON：[{id,version}] */
    private String planVersions;

    /** 各传感器版本凭证与最新读数快照 JSON（同时是读数连续性的结构化备份） */
    private String sensorVersions;

    /** 交接快照 JSON（只写一次，不可回写）：鱼量/投饵/存活率/读数逐项可核对 */
    private String snapshotJson;

    private String remark;
}
