package com.evops.aquaculture.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.aquaculture.entity.ObsImportRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ObsImportRowMapper extends BaseMapper<ObsImportRow> {

    /** 分片重试前清除该片旧明细（重算后重新写入）。 */
    @Delete("DELETE FROM t_obs_import_row WHERE shard_id = #{shardId}")
    int deleteByShardId(@Param("shardId") Long shardId);
}
