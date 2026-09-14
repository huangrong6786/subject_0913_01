package com.evops.controller;

import com.evops.aquaculture.dto.TideSessionCreateRequest;
import com.evops.aquaculture.entity.TideSession;
import com.evops.aquaculture.service.TideSessionService;
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
 * 潮次登记：潮汐开始/结束时刻左闭右开；
 * 跨午夜潮次按网箱所在海区时区归属业务日。
 */
@RestController
@RequestMapping("/api/tide-sessions")
public class TideSessionController {

    private final TideSessionService tideSessionService;

    public TideSessionController(TideSessionService tideSessionService) {
        this.tideSessionService = tideSessionService;
    }

    @PostMapping
    public ApiResponse<TideSession> create(@Valid @RequestBody TideSessionCreateRequest request) {
        return ApiResponse.ok(tideSessionService.create(request));
    }

    @GetMapping
    public ApiResponse<List<TideSession>> list(
            @RequestParam(required = false) String cageNo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate businessDate) {
        return ApiResponse.ok(tideSessionService.list(cageNo, businessDate));
    }

    @GetMapping("/{id}")
    public ApiResponse<TideSession> get(@PathVariable Long id) {
        return ApiResponse.ok(tideSessionService.getById(id));
    }
}
