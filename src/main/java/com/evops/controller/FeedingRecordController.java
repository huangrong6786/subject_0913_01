package com.evops.controller;

import com.evops.aquaculture.dto.FeedingRecordCreateRequest;
import com.evops.aquaculture.dto.FeedingPostTaskResult;
import com.evops.aquaculture.entity.FeedingRecord;
import com.evops.aquaculture.service.FeedingPostTaskService;
import com.evops.aquaculture.service.FeedingRecordService;
import com.evops.common.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
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
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/feeding-records")
public class FeedingRecordController {

    private final FeedingRecordService feedingRecordService;
    private final FeedingPostTaskService feedingPostTaskService;

    public FeedingRecordController(FeedingRecordService feedingRecordService,
                                   FeedingPostTaskService feedingPostTaskService) {
        this.feedingRecordService = feedingRecordService;
        this.feedingPostTaskService = feedingPostTaskService;
    }

    /** 登记实际投饵量。 */
    @PostMapping
    public ApiResponse<FeedingRecord> create(@Valid @RequestBody FeedingRecordCreateRequest request) {
        return ApiResponse.ok(feedingRecordService.create(request));
    }

    @GetMapping
    public ApiResponse<List<FeedingRecord>> list(
            @RequestParam(required = false) Long batchId,
            @RequestParam(required = false) String cageNo,
            @RequestParam(required = false) Integer posted,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime endTime) {
        return ApiResponse.ok(feedingRecordService.list(batchId, cageNo, posted, startTime, endTime));
    }

    @GetMapping("/{id}")
    public ApiResponse<FeedingRecord> get(@PathVariable Long id) {
        return ApiResponse.ok(feedingRecordService.getById(id));
    }

    /** 落账。 */
    @PutMapping("/{id}/post")
    public ApiResponse<FeedingRecord> post(@PathVariable Long id) {
        return ApiResponse.ok(feedingRecordService.post(id));
    }

    /**
     * 手工触发当天投饵记录批量落账，与定时任务共用同一执行路径。
     * data.status：SUCCESS 本次执行成功；SKIPPED 本周期已处理/有执行中的任务；
     * FAILED 本次失败（已记录为可重试，再次触发或下次调度会重试）。
     */
    @PostMapping("/post-task/trigger")
    public ApiResponse<FeedingPostTaskResult> triggerPostTask() {
        return ApiResponse.ok(feedingPostTaskService.triggerManual());
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        feedingRecordService.delete(id);
        return ApiResponse.ok(null);
    }
}
