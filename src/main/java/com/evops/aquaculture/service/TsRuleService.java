package com.evops.aquaculture.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.aquaculture.dto.TsRuleCreateRequest;
import com.evops.aquaculture.dto.TsRuleUpdateRequest;
import com.evops.aquaculture.entity.TsRule;
import com.evops.aquaculture.enums.RuleStatus;
import com.evops.aquaculture.enums.RuleType;
import com.evops.aquaculture.mapper.TsRuleMapper;
import com.evops.common.BusinessException;
import com.evops.common.util.TimeIntervals;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 时序规则维护（运营端）：峰值/平段/谷值业务区间的版本化管理。
 *
 * 版本化约束：
 * 1. 同一 ruleCode 的版本号单调递增；新建即产生新版本草稿。
 * 2. 启用后不能原地修改（内容冻结），调整须新建版本；启用新版本时同编码旧版本自动退役。
 * 3. 启用前校验与全部在启规则的区间不重叠（左闭右开、含跨午夜环形区间），重叠必须拒绝。
 */
@Service
public class TsRuleService {

    private final TsRuleMapper tsRuleMapper;

    public TsRuleService(TsRuleMapper tsRuleMapper) {
        this.tsRuleMapper = tsRuleMapper;
    }

    /** 新建规则（草稿）。ruleCode 已存在时生成该编码的下一版本。 */
    @Transactional
    public TsRule create(TsRuleCreateRequest request) {
        RuleType ruleType = parseType(request.getRuleType());
        validateInterval(request.getStartMinute(), request.getEndMinute());

        TsRule entity = new TsRule();
        entity.setRuleCode(request.getRuleCode());
        entity.setVersionNo(nextVersionNo(request.getRuleCode()));
        entity.setRuleType(ruleType.name());
        entity.setStartMinute(request.getStartMinute());
        entity.setEndMinute(request.getEndMinute());
        entity.setCoefficient(request.getCoefficient());
        entity.setStatus(RuleStatus.DRAFT.name());
        tsRuleMapper.insert(entity);
        return entity;
    }

    /** 修改草稿；启用/退役版本不能原地修改。 */
    @Transactional
    public TsRule update(Long id, TsRuleUpdateRequest request) {
        TsRule entity = getById(id);
        if (!RuleStatus.DRAFT.name().equals(entity.getStatus())) {
            throw new BusinessException("规则启用后不能原地修改，请新建版本: "
                    + entity.getRuleCode() + " v" + entity.getVersionNo());
        }
        RuleType ruleType = parseType(request.getRuleType());
        validateInterval(request.getStartMinute(), request.getEndMinute());
        entity.setRuleType(ruleType.name());
        entity.setStartMinute(request.getStartMinute());
        entity.setEndMinute(request.getEndMinute());
        entity.setCoefficient(request.getCoefficient());
        tsRuleMapper.updateById(entity);
        return entity;
    }

    /**
     * 启用规则版本：与全部在启规则（不含同编码）做区间重叠校验，重叠必须拒绝；
     * 通过后同编码旧启用版本自动退役，本版本进入启用态。整个切换在同一事务内完成。
     */
    @Transactional
    public TsRule enable(Long id) {
        TsRule entity = getById(id);
        if (!RuleStatus.DRAFT.name().equals(entity.getStatus())) {
            throw new BusinessException("仅草稿状态的规则可启用: "
                    + entity.getRuleCode() + " v" + entity.getVersionNo() + " 当前状态 " + entity.getStatus());
        }
        List<TsRule> enabledRules = listEnabled();
        for (TsRule other : enabledRules) {
            if (other.getRuleCode().equals(entity.getRuleCode())) {
                continue;
            }
            if (TimeIntervals.overlaps(entity.getStartMinute(), entity.getEndMinute(),
                    other.getStartMinute(), other.getEndMinute())) {
                throw new BusinessException("规则区间重叠，拒绝启用: "
                        + describe(entity) + " 与在启规则 " + describe(other) + " 存在重叠");
            }
        }
        LocalDateTime now = LocalDateTime.now();
        tsRuleMapper.retireEnabledVersions(entity.getRuleCode(), now);
        entity.setStatus(RuleStatus.ENABLED.name());
        entity.setEnabledTime(now);
        tsRuleMapper.updateById(entity);
        return entity;
    }

    /** 退役启用中的规则（内容仍不可改，仅状态流转）。 */
    @Transactional
    public TsRule retire(Long id) {
        TsRule entity = getById(id);
        if (!RuleStatus.ENABLED.name().equals(entity.getStatus())) {
            throw new BusinessException("仅启用状态的规则可退役: "
                    + entity.getRuleCode() + " v" + entity.getVersionNo() + " 当前状态 " + entity.getStatus());
        }
        entity.setStatus(RuleStatus.RETIRED.name());
        entity.setRetiredTime(LocalDateTime.now());
        tsRuleMapper.updateById(entity);
        return entity;
    }

    /** 删除草稿；启用/退役版本作为历史版本必须保留。 */
    @Transactional
    public void delete(Long id) {
        TsRule entity = getById(id);
        if (!RuleStatus.DRAFT.name().equals(entity.getStatus())) {
            throw new BusinessException("仅草稿状态的规则可删除，启用/退役版本须保留: "
                    + entity.getRuleCode() + " v" + entity.getVersionNo());
        }
        tsRuleMapper.deleteById(id);
    }

    public TsRule getById(Long id) {
        TsRule entity = tsRuleMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("时序规则不存在: " + id);
        }
        return entity;
    }

    /** 当前全部启用中的规则（计算归类的依据）。 */
    public List<TsRule> listEnabled() {
        return tsRuleMapper.selectList(new LambdaQueryWrapper<TsRule>()
                .eq(TsRule::getStatus, RuleStatus.ENABLED.name())
                .orderByAsc(TsRule::getStartMinute)
                .orderByAsc(TsRule::getRuleCode));
    }

    public List<TsRule> list(String status, String ruleCode) {
        return tsRuleMapper.selectList(new LambdaQueryWrapper<TsRule>()
                .eq(status != null && !status.isEmpty(), TsRule::getStatus, status)
                .eq(ruleCode != null && !ruleCode.isEmpty(), TsRule::getRuleCode, ruleCode)
                .orderByAsc(TsRule::getRuleCode)
                .orderByAsc(TsRule::getVersionNo));
    }

    private int nextVersionNo(String ruleCode) {
        TsRule latest = tsRuleMapper.selectOne(new LambdaQueryWrapper<TsRule>()
                .eq(TsRule::getRuleCode, ruleCode)
                .orderByDesc(TsRule::getVersionNo)
                .last("LIMIT 1"));
        return latest == null ? 1 : latest.getVersionNo() + 1;
    }

    private RuleType parseType(String ruleType) {
        try {
            return RuleType.valueOf(ruleType);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BusinessException("非法的区间类型: " + ruleType + "，可选 PEAK/FLAT/VALLEY");
        }
    }

    private void validateInterval(Integer startMinute, Integer endMinute) {
        if (startMinute == null || endMinute == null || !TimeIntervals.isValid(startMinute, endMinute)) {
            throw new BusinessException("非法的规则区间: [" + startMinute + ", " + endMinute
                    + ")，开始须为 0..1439、结束须为 1..1440 且区间非空");
        }
    }

    private String describe(TsRule rule) {
        return rule.getRuleCode() + " v" + rule.getVersionNo()
                + " [" + TimeIntervals.format(rule.getStartMinute())
                + ", " + TimeIntervals.format(rule.getEndMinute()) + ")";
    }
}
