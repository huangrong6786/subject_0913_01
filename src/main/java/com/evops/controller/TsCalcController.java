package com.evops.controller;

import com.evops.aquaculture.dto.TsCalcRecalcRequest;
import com.evops.aquaculture.dto.TsCalcResultView;
import com.evops.aquaculture.entity.TsCalcDetail;
import com.evops.aquaculture.service.TsCalcService;
import com.evops.common.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDate;
import java.util.List;

/**
 * 时序规则计算：按批次与业务日触发/重算，读取结果与计算明细。
 * 历史结果保留计算当时采用的规则版本快照与潮次快照；
 * 并发重算幂等，不会产生两份结果。
 */
@RestController
@RequestMapping("/api/ts-calc")
public class TsCalcController {

    private final TsCalcService tsCalcService;

    public TsCalcController(TsCalcService tsCalcService) {
        this.tsCalcService = tsCalcService;
    }

    /** 触发/重算某批次某业务日；并发占用时返回 SKIPPED，不产生第二份结果。 */
    @PostMapping("/recalculate")
    public ApiResponse<TsCalcResultView> recalculate(@Valid @RequestBody TsCalcRecalcRequest request) {
        return ApiResponse.ok(tsCalcService.recalculate(request.getBatchId(), request.getBusinessDate()));
    }

    @GetMapping("/results")
    public ApiResponse<List<TsCalcResultView>> listResults(
            @RequestParam(required = false) Long batchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate businessDate) {
        return ApiResponse.ok(tsCalcService.listResults(batchId, businessDate));
    }

    @GetMapping("/results/{id}")
    public ApiResponse<TsCalcResultView> getResult(@PathVariable Long id) {
        return ApiResponse.ok(tsCalcService.getResult(id));
    }

    /** 计算明细：每条命中观测一行，含潮次快照与规则版本快照。 */
    @GetMapping("/results/{id}/details")
    public ApiResponse<List<TsCalcDetail>> listDetails(@PathVariable Long id) {
        return ApiResponse.ok(tsCalcService.listDetails(id));
    }
}
