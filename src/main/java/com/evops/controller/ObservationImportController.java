package com.evops.controller;

import com.evops.aquaculture.dto.ObsImportResult;
import com.evops.aquaculture.entity.CageObservation;
import com.evops.aquaculture.entity.ObsImportRow;
import com.evops.aquaculture.service.ObsImportService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 深海网箱观测数据 CSV 批量导入。
 *
 * 文件级幂等：同一文件（SHA-256 校验和一致）重复上传直接返回首次处理结果，
 * 航次断网重传不会产生重复数据；行级幂等：航次号+网箱号+采样时刻组成业务键 upsert。
 * 大文件按分片处理（默认 1000 行/片，50,000 行 = 50 片），失败分片可重试，
 * 单行失败只回滚本行并保留原始行号/字段/原值/原因。
 */
@RestController
@RequestMapping("/api/observations")
public class ObservationImportController {

    private final ObsImportService obsImportService;

    public ObservationImportController(ObsImportService obsImportService) {
        this.obsImportService = obsImportService;
    }

    /**
     * 上传观测数据 CSV 并导入（同步按分片处理完成后返回汇总）。
     *
     * @param file     CSV 文件，表头固定为
     *                 voyage_no,cage_no,observed_at,feed_amount_kg,survival_rate,sensor_value,sensor_unit,source_device
     * @param checksum 可选，调用方预计算的 SHA-256（hex），不一致说明传输损坏，直接拒绝
     */
    @PostMapping("/import")
    public ApiResponse<ObsImportResult> importCsv(@RequestParam("file") MultipartFile file,
                                                  @RequestParam(required = false) String checksum)
            throws IOException {
        String fileName = file.getOriginalFilename() == null ? "upload.csv" : file.getOriginalFilename();
        return ApiResponse.ok(obsImportService.importCsv(file.getBytes(), fileName, checksum));
    }

    /** 重试失败/未完成分片（FAILED、PENDING、僵死 RUNNING）。 */
    @PostMapping("/import/{importNo}/retry")
    public ApiResponse<ObsImportResult> retry(@PathVariable String importNo) {
        return ApiResponse.ok(obsImportService.retry(importNo));
    }

    /** 导入批次状态与汇总（含分片进度与失败明细摘要）。 */
    @GetMapping("/import/{importNo}")
    public ApiResponse<ObsImportResult> getImport(@PathVariable String importNo) {
        return ApiResponse.ok(obsImportService.getImport(importNo));
    }

    /** 逐行处理明细：outcome 可过滤 SUCCESS/UPDATED/SKIPPED/FAILED，失败行含行号/字段/原值/原因。 */
    @GetMapping("/import/{importNo}/rows")
    public ApiResponse<List<ObsImportRow>> listRows(@PathVariable String importNo,
                                                    @RequestParam(required = false) String outcome,
                                                    @RequestParam(defaultValue = "200") int limit,
                                                    @RequestParam(defaultValue = "0") int offset) {
        return ApiResponse.ok(obsImportService.listRows(importNo, outcome, limit, offset));
    }

    /** 观测数据查询（按航次/网箱过滤）。 */
    @GetMapping
    public ApiResponse<List<CageObservation>> list(@RequestParam(required = false) String voyageNo,
                                                   @RequestParam(required = false) String cageNo) {
        return ApiResponse.ok(obsImportService.listObservations(voyageNo, cageNo));
    }
}
