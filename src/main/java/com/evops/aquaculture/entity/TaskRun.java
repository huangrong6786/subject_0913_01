package com.evops.aquaculture.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务执行台账：同一任务同一业务周期唯一一行。
 * RUNNING 执行中 / SUCCESS 成功终态（重复触发短路，保证一个周期最多一次副作用）/
 * FAILED 失败终态（可重试）。
 */
@Data
@TableName("t_task_run")
public class TaskRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskName;

    /** 业务周期标识（如按日落账的 yyyy-MM-dd） */
    private String period;

    /** RUNNING / SUCCESS / FAILED */
    private String status;

    /** SCHEDULED 定时触发 / MANUAL 手工触发 */
    private String triggerSource;

    private Integer attempts;

    /** 本周期实际落账条数 */
    private Integer postedCount;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
