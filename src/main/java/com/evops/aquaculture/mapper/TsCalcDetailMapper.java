package com.evops.aquaculture.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.aquaculture.entity.TsCalcDetail;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TsCalcDetailMapper extends BaseMapper<TsCalcDetail> {

    /** 重算时整体清除上一代明细（与新明细插入处于同一事务，要么全换要么全留）。 */
    @Delete("DELETE FROM t_ts_calc_detail WHERE result_id = #{resultId}")
    int deleteByResultId(@Param("resultId") Long resultId);
}
