package com.evops.aquaculture.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.aquaculture.entity.FeedingRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface FeedingRecordMapper extends BaseMapper<FeedingRecord> {

    /**
     * 原子落账（CAS）：仅当 posted=0 时置为已落账。
     * 定时任务与手工触发并发、或失败重试时，同一条记录只会被落账一次，
     *  affected=1 表示本次调用真正完成落账，affected=0 表示已被其他执行方抢先落账。
     */
    @Update("UPDATE t_feeding_record SET posted = 1, posted_time = #{postedTime}, update_time = #{postedTime} " +
            "WHERE id = #{id} AND posted = 0")
    int markPostedIfPending(@Param("id") Long id, @Param("postedTime") LocalDateTime postedTime);
}
