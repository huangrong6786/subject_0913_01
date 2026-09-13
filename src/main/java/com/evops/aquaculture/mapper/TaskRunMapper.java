package com.evops.aquaculture.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.aquaculture.entity.TaskRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface TaskRunMapper extends BaseMapper<TaskRun> {

    /**
     * 抢占一个周期的执行权（CAS）：仅 FAILED，或已僵死（started_at 早于租约阈值）的 RUNNING
     * 才能被接管，attempts 累加。SUCCESS 永远不会被改写；两个并发接管方只有一方 affected=1。
     */
    @Update("UPDATE t_task_run SET status = 'RUNNING', trigger_source = #{triggerSource}, " +
            "attempts = attempts + 1, error_message = NULL, started_at = #{now}, finished_at = NULL, " +
            "update_time = #{now} " +
            "WHERE id = #{id} AND (status = 'FAILED' OR (status = 'RUNNING' AND started_at <= #{staleBefore}))")
    int claimRunning(@Param("id") Long id,
                     @Param("triggerSource") String triggerSource,
                     @Param("now") LocalDateTime now,
                     @Param("staleBefore") LocalDateTime staleBefore);

    /** 成功终态：累计本次新增落账条数。仅 RUNNING 行可写，重复回调不会再改。 */
    @Update("UPDATE t_task_run SET status = 'SUCCESS', posted_count = posted_count + #{delta}, " +
            "finished_at = #{now}, update_time = #{now} " +
            "WHERE id = #{id} AND status = 'RUNNING'")
    int markSuccess(@Param("id") Long id, @Param("delta") int delta, @Param("now") LocalDateTime now);

    /** 失败终态（可重试）：保留 attempts，记录原因。 */
    @Update("UPDATE t_task_run SET status = 'FAILED', error_message = #{errorMessage}, " +
            "finished_at = #{now}, update_time = #{now} " +
            "WHERE id = #{id} AND status = 'RUNNING'")
    int markFailed(@Param("id") Long id, @Param("errorMessage") String errorMessage,
                   @Param("now") LocalDateTime now);
}
