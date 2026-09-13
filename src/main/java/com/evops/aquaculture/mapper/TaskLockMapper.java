package com.evops.aquaculture.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.aquaculture.entity.TaskLock;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface TaskLockMapper extends BaseMapper<TaskLock> {

    /**
     * 接管僵死锁：仅当租约已过期时才能把 owner 换成自己。
     * 两个执行方同时接管时，行锁保证只有一方 affected=1。
     */
    @Update("UPDATE t_task_lock SET owner_token = #{ownerToken}, locked_at = #{now}, lease_expire_time = #{expireTime} " +
            "WHERE lock_name = #{lockName} AND lease_expire_time <= #{now}")
    int takeOverExpired(@Param("lockName") String lockName,
                        @Param("ownerToken") String ownerToken,
                        @Param("now") LocalDateTime now,
                        @Param("expireTime") LocalDateTime expireTime);

    /** 释放锁：只有持锁方自身可以删除，避免误删别人的锁。 */
    @Delete("DELETE FROM t_task_lock WHERE lock_name = #{lockName} AND owner_token = #{ownerToken}")
    int release(@Param("lockName") String lockName, @Param("ownerToken") String ownerToken);
}
