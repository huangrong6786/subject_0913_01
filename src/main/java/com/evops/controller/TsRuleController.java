package com.evops.controller;

import com.evops.aquaculture.dto.TsRuleCreateRequest;
import com.evops.aquaculture.dto.TsRuleUpdateRequest;
import com.evops.aquaculture.entity.TsRule;
import com.evops.aquaculture.service.TsRuleService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 时序规则维护（运营端）：峰值/平段/谷值业务区间的版本化管理。
 * 启用后不能原地修改，调整须新建版本；启用时校验区间重叠并自动退役同编码旧版本。
 */
@RestController
@RequestMapping("/api/ts-rules")
public class TsRuleController {

    private final TsRuleService tsRuleService;

    public TsRuleController(TsRuleService tsRuleService) {
        this.tsRuleService = tsRuleService;
    }

    /** 新建规则（草稿）；ruleCode 已存在时生成下一版本。 */
    @PostMapping
    public ApiResponse<TsRule> create(@Valid @RequestBody TsRuleCreateRequest request) {
        return ApiResponse.ok(tsRuleService.create(request));
    }

    /** 修改草稿；启用/退役版本不能原地修改。 */
    @PutMapping("/{id}")
    public ApiResponse<TsRule> update(@PathVariable Long id,
                                      @Valid @RequestBody TsRuleUpdateRequest request) {
        return ApiResponse.ok(tsRuleService.update(id, request));
    }

    /** 启用规则版本：区间重叠拒绝；同编码旧启用版本自动退役。 */
    @PostMapping("/{id}/enable")
    public ApiResponse<TsRule> enable(@PathVariable Long id) {
        return ApiResponse.ok(tsRuleService.enable(id));
    }

    /** 退役启用中的规则。 */
    @PostMapping("/{id}/retire")
    public ApiResponse<TsRule> retire(@PathVariable Long id) {
        return ApiResponse.ok(tsRuleService.retire(id));
    }

    /** 删除草稿版本；启用/退役版本作为历史必须保留。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        tsRuleService.delete(id);
        return ApiResponse.ok(null);
    }

    @GetMapping
    public ApiResponse<List<TsRule>> list(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) String ruleCode) {
        return ApiResponse.ok(tsRuleService.list(status, ruleCode));
    }

    @GetMapping("/{id}")
    public ApiResponse<TsRule> get(@PathVariable Long id) {
        return ApiResponse.ok(tsRuleService.getById(id));
    }
}
