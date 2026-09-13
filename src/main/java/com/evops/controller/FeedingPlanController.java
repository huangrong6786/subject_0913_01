package com.evops.controller;

import com.evops.aquaculture.dto.FeedingPlanCreateRequest;
import com.evops.aquaculture.dto.PlanStatusRequest;
import com.evops.aquaculture.entity.FeedingPlan;
import com.evops.aquaculture.service.FeedingPlanService;
import com.evops.common.ApiResponse;
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

@RestController
@RequestMapping("/api/feeding-plans")
public class FeedingPlanController {

    private final FeedingPlanService feedingPlanService;

    public FeedingPlanController(FeedingPlanService feedingPlanService) {
        this.feedingPlanService = feedingPlanService;
    }

    @PostMapping
    public ApiResponse<FeedingPlan> create(@Valid @RequestBody FeedingPlanCreateRequest request) {
        return ApiResponse.ok(feedingPlanService.create(request));
    }

    @GetMapping
    public ApiResponse<List<FeedingPlan>> list(@RequestParam(required = false) Long batchId,
                                               @RequestParam(required = false) String cageNo,
                                               @RequestParam(required = false) String status) {
        return ApiResponse.ok(feedingPlanService.list(batchId, cageNo, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<FeedingPlan> get(@PathVariable Long id) {
        return ApiResponse.ok(feedingPlanService.getById(id));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<FeedingPlan> transitStatus(@PathVariable Long id,
                                                  @Valid @RequestBody PlanStatusRequest request) {
        return ApiResponse.ok(feedingPlanService.transitStatus(id, request));
    }
}
