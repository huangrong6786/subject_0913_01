package com.evops.controller;

import com.evops.aquaculture.dto.FeedObservationCreateRequest;
import com.evops.aquaculture.entity.FeedObservation;
import com.evops.aquaculture.service.FeedObservationService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/** 投饵监测观测登记：观测时刻按 UTC 传入，计算时按网箱海区时区归算。 */
@RestController
@RequestMapping("/api/feed-observations")
public class FeedObservationController {

    private final FeedObservationService feedObservationService;

    public FeedObservationController(FeedObservationService feedObservationService) {
        this.feedObservationService = feedObservationService;
    }

    @PostMapping
    public ApiResponse<FeedObservation> create(@Valid @RequestBody FeedObservationCreateRequest request) {
        return ApiResponse.ok(feedObservationService.create(request));
    }

    @GetMapping
    public ApiResponse<List<FeedObservation>> list(@RequestParam(required = false) Long batchId,
                                                   @RequestParam(required = false) String cageNo) {
        return ApiResponse.ok(feedObservationService.list(batchId, cageNo));
    }

    @GetMapping("/{id}")
    public ApiResponse<FeedObservation> get(@PathVariable Long id) {
        return ApiResponse.ok(feedObservationService.getById(id));
    }
}
