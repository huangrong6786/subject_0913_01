package com.evops.aquaculture.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.aquaculture.entity.CageMigrationChain;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface CageMigrationChainMapper extends BaseMapper<CageMigrationChain> {

    /**
     * 链头条件推进（乐观锁 CAS）：仅当链头版本仍为 expectedSeq 时，
     * 把当前网箱与版本一并推进到 targetCage / expectedSeq+1。
     * 两个操作者并发提交同一鱼群的迁移时，只有一方 affected=1，
     * 另一方拿到 0 必须失败（不重试、不排队），保证同一时刻仅一个版本生效。
     */
    @Update("UPDATE t_cage_migration_chain SET current_cage_no = #{targetCageNo}, " +
            "current_seq = #{nextSeq}, update_time = #{now} " +
            "WHERE id = #{chainId} AND current_seq = #{expectedSeq}")
    int advanceIfSeq(@Param("chainId") Long chainId,
                     @Param("expectedSeq") int expectedSeq,
                     @Param("nextSeq") int nextSeq,
                     @Param("targetCageNo") String targetCageNo,
                     @Param("now") LocalDateTime now);
}
