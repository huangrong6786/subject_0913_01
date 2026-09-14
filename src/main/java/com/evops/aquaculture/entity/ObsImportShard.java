package com.evops.aquaculture.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 导入分片台账：大文件按 shard-size 切分逐片处理。
 * 分片级失败（FAILED）与僵死（RUNNING 超租约）可被重试；行级失败不连坐分片内其他行。
 */
@Data
@TableName("t_obs_import_shard")
public class ObsImportShard {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long importId;

    /** 分片序号（0 起） */
    private Integer shardIndex;

    /** 在数据行列表中的起止下标（0 起，闭区间） */
    private Integer startIdx;

    private Integer endIdx;

    /** 覆盖的原始文件物理行号区间（表头为第 1 行），用于定位 */
    private Integer startLine;

    private Integer endLine;

    private Integer rowCount;

    private Integer successCount;

    private Integer updatedCount;

    private Integer skippedCount;

    private Integer failedCount;

    /** PENDING / RUNNING / SUCCESS / PARTIAL / FAILED */
    private String status;

    private Integer attempts;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createTime;
}
