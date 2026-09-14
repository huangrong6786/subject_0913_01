package com.evops.aquaculture.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 逐行处理明细：每个数据行一条。
 * 失败行保留原始行号、字段名、原始值与失败原因；单行失败不影响同批其他行。
 */
@Data
@TableName("t_obs_import_row")
public class ObsImportRow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long importId;

    private Long shardId;

    /** 原始文件物理行号（表头为第 1 行） */
    private Integer lineNo;

    /** 幂等业务键 voyageNo|cageNo|observedAt（可解析时记录） */
    private String businessKey;

    /** SUCCESS / UPDATED / SKIPPED / FAILED */
    private String outcome;

    /** 失败字段名（_row 表示整行级问题，如列数不符） */
    private String fieldName;

    /** 失败字段原始值 */
    private String rawValue;

    /** 原始行内容（仅失败行保留，截断 1000 字符） */
    private String rawLine;

    private String errorReason;

    private LocalDateTime createTime;
}
