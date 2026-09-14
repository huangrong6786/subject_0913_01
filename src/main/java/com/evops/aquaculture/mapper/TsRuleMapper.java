package com.evops.aquaculture.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.aquaculture.entity.TsRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface TsRuleMapper extends BaseMapper<TsRule> {

    /**
     * 退役同规则编码下当前启用的版本（启用新版本前调用，同事务）。
     * 仅 ENABLED 行可被改写，重复调用幂等。
     */
    @Update("UPDATE t_ts_rule SET status = 'RETIRED', retired_time = #{now}, update_time = #{now} " +
            "WHERE rule_code = #{ruleCode} AND status = 'ENABLED'")
    int retireEnabledVersions(@Param("ruleCode") String ruleCode, @Param("now") LocalDateTime now);
}
