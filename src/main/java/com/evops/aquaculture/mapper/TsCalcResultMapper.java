package com.evops.aquaculture.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.aquaculture.entity.TsCalcResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Mapper
public interface TsCalcResultMapper extends BaseMapper<TsCalcResult> {

    /**
     * 抢占重算权（CAS）：仅当前没有其他执行方在算（status 非 CALCULATING）时置为 CALCULATING，
     * attempts 累加。并发重算只有一方 affected=1，另一方跳过，保证不会产生两份结果。
     */
    @Update("UPDATE t_ts_calc_result SET status = 'CALCULATING', attempts = attempts + 1, update_time = #{now} " +
            "WHERE id = #{id} AND status <> 'CALCULATING'")
    int claimForRecalc(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 计算完成：写入计数、统一舍入后的合计与规则快照。仅 CALCULATING 行可写。 */
    @Update("UPDATE t_ts_calc_result SET status = 'DONE', obs_total = #{obsTotal}, obs_matched = #{obsMatched}, " +
            "obs_unmatched = #{obsUnmatched}, peak_total_kg = #{peakTotalKg}, flat_total_kg = #{flatTotalKg}, " +
            "valley_total_kg = #{valleyTotalKg}, total_weighted_kg = #{totalWeightedKg}, " +
            "rule_snapshot = #{ruleSnapshot}, calc_time = #{calcTime}, update_time = #{calcTime} " +
            "WHERE id = #{id} AND status = 'CALCULATING'")
    int markDone(@Param("id") Long id,
                 @Param("obsTotal") int obsTotal,
                 @Param("obsMatched") int obsMatched,
                 @Param("obsUnmatched") int obsUnmatched,
                 @Param("peakTotalKg") BigDecimal peakTotalKg,
                 @Param("flatTotalKg") BigDecimal flatTotalKg,
                 @Param("valleyTotalKg") BigDecimal valleyTotalKg,
                 @Param("totalWeightedKg") BigDecimal totalWeightedKg,
                 @Param("ruleSnapshot") String ruleSnapshot,
                 @Param("calcTime") LocalDateTime calcTime);
}
