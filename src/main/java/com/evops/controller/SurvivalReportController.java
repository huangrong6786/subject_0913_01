package com.evops.controller;

import com.evops.aquaculture.dto.SurvivalReportCreateRequest;
import com.evops.aquaculture.entity.SurvivalReport;
import com.evops.aquaculture.service.SurvivalReportService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/survival-reports")
public class SurvivalReportController {

    private final SurvivalReportService survivalReportService;

    public SurvivalReportController(SurvivalReportService survivalReportService) {
        this.survivalReportService = survivalReportService;
    }

    /** 登记存活率报告。 */
    @PostMapping
    public ApiResponse<SurvivalReport> create(@Valid @RequestBody SurvivalReportCreateRequest request) {
        return ApiResponse.ok(survivalReportService.create(request));
    }

    @GetMapping
    public ApiResponse<List<SurvivalReport>> list(@RequestParam(required = false) Long batchId,
                                                  @RequestParam(required = false) String cageNo) {
        return ApiResponse.ok(survivalReportService.list(batchId, cageNo));
    }
}
