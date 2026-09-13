package com.evops.aquaculture.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 投饵批量落账任务一次触发的结果（统一返回结构的 data）。
 * status：SUCCESS 本次执行成功；SKIPPED 未执行（周期已成功/已有执行进行中）；FAILED 本次失败可重试。
 */
@Data
public class FeedingPostTaskResult {

    private String taskName;

    /** 业务周期标识（yyyy-MM-dd） */
    private String period;

    /** SUCCESS / SKIPPED / FAILED */
    private String status;

    /** SCHEDULED / MANUAL */
    private String triggerSource;

    private Integer attempts;

    /** 本周期台账累计落账条数 */
    private Integer postedCount;

    private String message;

    private LocalDateTime finishedAt;

    public static FeedingPostTaskResult skipped(String taskName, String period,
                                                String triggerSource, String message) {
        FeedingPostTaskResult result = new FeedingPostTaskResult();
        result.taskName = taskName;
        result.period = period;
        result.status = "SKIPPED";
        result.triggerSource = triggerSource;
        result.attempts = 0;
        result.postedCount = 0;
        result.message = message;
        return result;
    }
}
