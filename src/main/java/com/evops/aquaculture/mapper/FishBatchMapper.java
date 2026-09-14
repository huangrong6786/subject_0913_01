package com.evops.aquaculture.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.aquaculture.entity.FishBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface FishBatchMapper extends BaseMapper<FishBatch> {

    /**
     * 行级悲观锁：迁移与“未完成投饵计划状态变更”共用批次行锁串行化，
     * 拿到锁后再复查关联对象，保证并发双方不会各自读到稳定快照而同时成功。
     */
    @Select("SELECT * FROM t_fish_batch WHERE id = #{id} FOR UPDATE")
    FishBatch selectForUpdate(@Param("id") Long id);

    /**
     * 鱼群迁移条件更新（乐观锁 CAS）：仅当批次版本仍为 expectedVersion 时
     * 把鱼群（批次）落位到目标网箱并版本 +1。
     * 与批次状态流转、另一操作者的并发迁移竞争时，只有一方 affected=1。
     */
    @Update("UPDATE t_fish_batch SET cage_no = #{targetCageNo}, version = version + 1, " +
            "migrated_at = #{now}, migration_id = #{migrationId}, update_time = #{now} " +
            "WHERE id = #{batchId} AND version = #{expectedVersion}")
    int migrateCageIfVersion(@Param("batchId") Long batchId,
                             @Param("targetCageNo") String targetCageNo,
                             @Param("expectedVersion") int expectedVersion,
                             @Param("migrationId") Long migrationId,
                             @Param("now") LocalDateTime now);

    /**
     * 状态流转条件更新（乐观锁 CAS）：与迁移共用 version，
     * 状态改写期间发生迁移（或反过来）时版本失配，仅一方生效。
     */
    @Update("UPDATE t_fish_batch SET status = #{status}, version = version + 1, update_time = #{now} " +
            "WHERE id = #{batchId} AND version = #{expectedVersion}")
    int updateStatusIfVersion(@Param("batchId") Long batchId,
                              @Param("status") String status,
                              @Param("expectedVersion") int expectedVersion,
                              @Param("now") LocalDateTime now);

    /**
     * 仅推进鱼群聚合根版本（条件更新）：未完成投饵计划的状态变更与换箱/转场共享该版本计数器，
     * 任一方先提交都会使另一方的条件更新失配，保证“转场与未完成投饵计划并发仅一个版本生效”。
     */
    @Update("UPDATE t_fish_batch SET version = version + 1, update_time = #{now} " +
            "WHERE id = #{batchId} AND version = #{expectedVersion}")
    int bumpVersionIfVersion(@Param("batchId") Long batchId,
                             @Param("expectedVersion") int expectedVersion,
                             @Param("now") LocalDateTime now);
}
