package com.evops.aquaculture.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 观测数据 CSV 导入批次台账。
 * fileChecksum（SHA-256）唯一：同一文件重复上传直接返回首次处理结果（文件级幂等）；
 * 存在失败/未完成分片时续传这些分片。
 */
@Data
@TableName("t_obs_import_batch")
public class ObsImportBatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 导入批次号（IMP-yyyyMMddHHmmss-随机串） */
    private String importNo;

    private String fileName;

    /** 文件内容 SHA-256（hex），文件级幂等键 */
    private String fileChecksum;

    /** 原始上传文件落盘路径（失败分片重试的数据来源） */
    private String filePath;

    private Integer totalRows;

    private Integer successCount;

    private Integer updatedCount;

    private Integer skippedCount;

    private Integer failedCount;

    /** RUNNING / SUCCESS / PARTIAL / FAILED */
    private String status;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createTime;
}
