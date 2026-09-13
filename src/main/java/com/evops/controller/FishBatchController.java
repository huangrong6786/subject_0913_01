package com.evops.controller;

import com.evops.aquaculture.dto.BatchStatusRequest;
import com.evops.aquaculture.dto.FishBatchCreateRequest;
import com.evops.aquaculture.entity.FishBatch;
import com.evops.aquaculture.service.CageQueryService;
import com.evops.aquaculture.service.FishBatchService;
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
import java.util.Map;

@RestController
@RequestMapping("/api/fish-batches")
public class FishBatchController {

    private final FishBatchService fishBatchService;
    private final CageQueryService cageQueryService;

    public FishBatchController(FishBatchService fishBatchService, CageQueryService cageQueryService) {
        this.fishBatchService = fishBatchService;
        this.cageQueryService = cageQueryService;
    }

    /** 建立鱼苗批次。 */
    @PostMapping
    public ApiResponse<FishBatch> create(@Valid @RequestBody FishBatchCreateRequest request) {
        return ApiResponse.ok(fishBatchService.create(request));
    }

    @GetMapping
    public ApiResponse<List<FishBatch>> list(@RequestParam(required = false) String cageNo,
                                             @RequestParam(required = false) String status) {
        return ApiResponse.ok(fishBatchService.list(cageNo, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<FishBatch> get(@PathVariable Long id) {
        return ApiResponse.ok(fishBatchService.getById(id));
    }

    @GetMapping("/no/{batchNo}")
    public ApiResponse<FishBatch> getByNo(@PathVariable String batchNo) {
        return ApiResponse.ok(fishBatchService.getByNo(batchNo));
    }

    /** 批次状态流转。 */
    @PutMapping("/{id}/status")
    public ApiResponse<FishBatch> transitStatus(@PathVariable Long id,
                                                @Valid @RequestBody BatchStatusRequest request) {
        return ApiResponse.ok(fishBatchService.transitStatus(id, request));
    }

    /** 批次关联明细：投饵记录、投饵量合计、存活率报告、出网批次。 */
    @GetMapping("/{id}/detail")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(cageQueryService.batchDetail(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        fishBatchService.delete(id);
        return ApiResponse.ok(null);
    }
}
