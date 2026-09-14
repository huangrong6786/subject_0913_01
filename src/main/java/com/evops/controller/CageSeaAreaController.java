package com.evops.controller;

import com.evops.aquaculture.dto.CageSeaAreaRequest;
import com.evops.aquaculture.entity.CageSeaArea;
import com.evops.aquaculture.service.CageSeaAreaService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/** 网箱海区档案维护：网箱 → 海区/业务时区（按对象时区归算的时区来源）。 */
@RestController
@RequestMapping("/api/cage-sea-areas")
public class CageSeaAreaController {

    private final CageSeaAreaService cageSeaAreaService;

    public CageSeaAreaController(CageSeaAreaService cageSeaAreaService) {
        this.cageSeaAreaService = cageSeaAreaService;
    }

    @PostMapping
    public ApiResponse<CageSeaArea> create(@Valid @RequestBody CageSeaAreaRequest request) {
        return ApiResponse.ok(cageSeaAreaService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<CageSeaArea> update(@PathVariable Long id,
                                           @Valid @RequestBody CageSeaAreaRequest request) {
        return ApiResponse.ok(cageSeaAreaService.update(id, request));
    }

    @GetMapping
    public ApiResponse<List<CageSeaArea>> list() {
        return ApiResponse.ok(cageSeaAreaService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<CageSeaArea> get(@PathVariable Long id) {
        return ApiResponse.ok(cageSeaAreaService.getById(id));
    }
}
