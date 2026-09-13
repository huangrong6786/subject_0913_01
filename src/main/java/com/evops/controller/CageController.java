package com.evops.controller;

import com.evops.aquaculture.dto.CageOverview;
import com.evops.aquaculture.service.CageQueryService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 网箱维度关联查询：聚合批次、投饵量、存活率、传感器最新读数、出网批次。
 */
@RestController
@RequestMapping("/api/cages")
public class CageController {

    private final CageQueryService cageQueryService;

    public CageController(CageQueryService cageQueryService) {
        this.cageQueryService = cageQueryService;
    }

    @GetMapping("/{cageNo}/overview")
    public ApiResponse<CageOverview> overview(@PathVariable String cageNo) {
        return ApiResponse.ok(cageQueryService.overview(cageNo));
    }
}
