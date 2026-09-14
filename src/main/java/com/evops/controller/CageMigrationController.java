package com.evops.controller;

import com.evops.aquaculture.dto.CageMigrationHistoryView;
import com.evops.aquaculture.dto.CageMigrationRequest;
import com.evops.aquaculture.entity.CageMigration;
import com.evops.aquaculture.service.CageMigrationService;
import com.evops.common.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 网箱替换/转移（换箱/转场）接口。
 *
 * 提交迁移后返回迁移事件（含原对象结束值/新对象起始值/发生时间/操作者）；
 * 历史链路查询沿鱼群（批次）或网箱给出有序事件与链完整性、交接守恒校验结果。
 */
@RestController
@RequestMapping("/api/cage-migrations")
public class CageMigrationController {

    private final CageMigrationService migrationService;

    public CageMigrationController(CageMigrationService migrationService) {
        this.migrationService = migrationService;
    }

    /** 换箱/转场：同一事务内完成鱼群、未完成投饵计划、ONLINE 传感器的迁移与交接快照固化。 */
    @PostMapping
    public ApiResponse<CageMigration> migrate(@Valid @RequestBody CageMigrationRequest request) {
        return ApiResponse.ok(migrationService.migrate(request));
    }

    /** 迁移事件台账查询（支持批次/原网箱/新网箱过滤，按链上序号升序）。 */
    @GetMapping
    public ApiResponse<List<CageMigration>> list(@RequestParam(required = false) Long batchId,
                                                 @RequestParam(required = false) String sourceCageNo,
                                                 @RequestParam(required = false) String targetCageNo) {
        return ApiResponse.ok(migrationService.list(batchId, sourceCageNo, targetCageNo));
    }

    @GetMapping("/{id}")
    public ApiResponse<CageMigration> get(@PathVariable Long id) {
        return ApiResponse.ok(migrationService.getById(id));
    }

    @GetMapping("/no/{migrationNo}")
    public ApiResponse<CageMigration> getByNo(@PathVariable String migrationNo) {
        return ApiResponse.ok(migrationService.getByNo(migrationNo));
    }

    /** 交接快照：原对象结束值与新对象起始值逐项并列（只读，历史不可回写）。 */
    @GetMapping("/{id}/snapshot")
    public ApiResponse<JsonNode> snapshot(@PathVariable Long id) {
        return ApiResponse.ok(migrationService.snapshotTree(id));
    }

    /** 鱼群（批次）维度历史链路：有序事件 + 序号连续/首尾相接/时间递增/交接守恒校验。 */
    @GetMapping("/history/batch/{batchId}")
    public ApiResponse<CageMigrationHistoryView> historyByBatch(@PathVariable Long batchId) {
        return ApiResponse.ok(migrationService.historyByBatch(batchId));
    }

    /** 网箱维度历史链路：所有起点/终点/当前/起始命中该网箱的鱼群迁移链。 */
    @GetMapping("/history/cage/{cageNo}")
    public ApiResponse<List<CageMigrationHistoryView>> historyByCage(@PathVariable String cageNo) {
        return ApiResponse.ok(migrationService.historyByCage(cageNo));
    }
}
