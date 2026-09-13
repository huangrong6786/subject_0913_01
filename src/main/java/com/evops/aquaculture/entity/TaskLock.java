package com.evops.aquaculture.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 批量任务互斥锁（每个 lock_name 唯一一行）。
 * ownerToken 标识持锁执行方；leaseExpireTime 为租约到期时间，
 * 执行方异常退出后租约到期可被下一次触发接管。
 */
@Data
@TableName("t_task_lock")
public class TaskLock {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 锁名（任务标识，唯一） */
    private String lockName;

    /** 持锁执行方令牌 */
    private String ownerToken;

    private LocalDateTime lockedAt;

    /** 租约到期时间：早于当前时间视为僵死锁，可接管 */
    private LocalDateTime leaseExpireTime;
}
