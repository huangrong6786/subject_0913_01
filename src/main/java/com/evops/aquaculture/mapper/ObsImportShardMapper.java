package com.evops.aquaculture.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.aquaculture.entity.ObsImportShard;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface ObsImportShardMapper extends BaseMapper<ObsImportShard> {

    /**
     * 抢占分片处理权（CAS）：仅 PENDING/FAILED，或已僵死（started_at 早于租约阈值）的 RUNNING
     * 才能被接管，attempts 累加；两个并发执行方只有一方 affected=1。
     */
    @Update("UPDATE t_obs_import_shard SET status = 'RUNNING', attempts = attempts + 1, " +
            "started_at = #{now}, finished_at = NULL, error_message = NULL " +
            "WHERE id = #{id} AND (status IN ('PENDING','FAILED') " +
            "OR (status = 'RUNNING' AND started_at <= #{staleBefore}))")
    int claimRunning(@Param("id") Long id,
                     @Param("now") LocalDateTime now,
                     @Param("staleBefore") LocalDateTime staleBefore);
}
