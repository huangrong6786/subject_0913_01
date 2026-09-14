package com.evops.aquaculture.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.aquaculture.entity.FeedingPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface FeedingPlanMapper extends BaseMapper<FeedingPlan> {

    /**
     * 未完成投饵计划随群迁移（乐观锁 CAS）：仅当计划版本仍为 expectedVersion 时
     * 切换 cage_no 到目标网箱并版本 +1。与“转场与未完成投饵计划并发只能一个版本生效”对应：
     * 计划状态流转（改 status）与迁移（改 cage_no）共用同一 version，后到一方 affected=0。
     */
    @Update("UPDATE t_feeding_plan SET cage_no = #{targetCageNo}, version = version + 1, " +
            "migrated_at = #{now}, migration_id = #{migrationId}, update_time = #{now} " +
            "WHERE id = #{planId} AND version = #{expectedVersion}")
    int migrateCageIfVersion(@Param("planId") Long planId,
                             @Param("targetCageNo") String targetCageNo,
                             @Param("expectedVersion") int expectedVersion,
                             @Param("migrationId") Long migrationId,
                             @Param("now") LocalDateTime now);

    /** 计划状态流转条件更新（乐观锁 CAS）：与迁移互斥。 */
    @Update("UPDATE t_feeding_plan SET status = #{status}, version = version + 1, update_time = #{now} " +
            "WHERE id = #{planId} AND version = #{expectedVersion}")
    int updateStatusIfVersion(@Param("planId") Long planId,
                              @Param("status") String status,
                              @Param("expectedVersion") int expectedVersion,
                              @Param("now") LocalDateTime now);
}
