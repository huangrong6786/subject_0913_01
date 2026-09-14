package com.evops.aquaculture.enums;

/**
 * 时序规则版本生命周期：
 * DRAFT 草稿（可编辑、可删除）→ ENABLED 启用（内容冻结，不能原地修改）→ RETIRED 退役（只读存档）。
 * 启用新版本时同 rule_code 的旧启用版本自动退役。
 */
public enum RuleStatus {
    DRAFT,
    ENABLED,
    RETIRED
}
