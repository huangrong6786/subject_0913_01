package com.evops.aquaculture.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 观测数据 CSV 导入结果（统一返回结构的 data）。
 * 行级结果：SUCCESS 新增 / UPDATED 更新 / SKIPPED 幂等跳过 / FAILED 失败（明细见 failures 与行明细接口）。
 */
@Data
public class ObsImportResult {

    private String importNo;

    private String fileName;

    private String fileChecksum;

    /** RUNNING / SUCCESS / PARTIAL / FAILED */
    private String status;

    private String message;

    private Integer totalRows;

    private Integer successCount;

    private Integer updatedCount;

    private Integer skippedCount;

    private Integer failedCount;

    private Integer shardCount;

    private List<ShardView> shards = new ArrayList<>();

    /** 失败明细（响应内截断，完整列表走 /import/{importNo}/rows?outcome=FAILED） */
    private List<RowErrorView> failures = new ArrayList<>();

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @Data
    public static class ShardView {
        private Integer shardIndex;
        private Integer startLine;
        private Integer endLine;
        private String status;
        private Integer attempts;
        private Integer rowCount;
        private Integer successCount;
        private Integer updatedCount;
        private Integer skippedCount;
        private Integer failedCount;
        private String errorMessage;
    }

    @Data
    public static class RowErrorView {
        private Integer lineNo;
        private String businessKey;
        private String fieldName;
        private String rawValue;
        private String errorReason;
    }
}
