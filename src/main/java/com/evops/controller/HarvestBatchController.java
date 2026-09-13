package com.evops.controller;

import com.evops.aquaculture.dto.HarvestBatchCreateRequest;
import com.evops.aquaculture.entity.HarvestBatch;
import com.evops.aquaculture.service.HarvestBatchService;
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

@RestController
@RequestMapping("/api/harvest-batches")
public class HarvestBatchController {

    private final HarvestBatchService harvestBatchService;

    public HarvestBatchController(HarvestBatchService harvestBatchService) {
        this.harvestBatchService = harvestBatchService;
    }

    /** 建立出网批次（待验收）。 */
    @PostMapping
    public ApiResponse<HarvestBatch> create(@Valid @RequestBody HarvestBatchCreateRequest request) {
        return ApiResponse.ok(harvestBatchService.create(request));
    }

    @GetMapping
    public ApiResponse<List<HarvestBatch>> list(@RequestParam(required = false) Long fishBatchId,
                                                @RequestParam(required = false) String cageNo,
                                                @RequestParam(required = false) String status) {
        return ApiResponse.ok(harvestBatchService.list(fishBatchId, cageNo, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<HarvestBatch> get(@PathVariable Long id) {
        return ApiResponse.ok(harvestBatchService.getById(id));
    }

    /** 验收出网批次（终态），并联动鱼苗批次流转为 HARVESTED。 */
    @PutMapping("/{id}/accept")
    public ApiResponse<HarvestBatch> accept(@PathVariable Long id) {
        return ApiResponse.ok(harvestBatchService.accept(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        harvestBatchService.delete(id);
        return ApiResponse.ok(null);
    }
}
