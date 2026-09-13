package com.evops.controller;

import com.evops.aquaculture.dto.ReadingCreateRequest;
import com.evops.aquaculture.dto.SensorCreateRequest;
import com.evops.aquaculture.dto.SensorStatusRequest;
import com.evops.aquaculture.entity.SensorReading;
import com.evops.aquaculture.entity.UnderwaterSensor;
import com.evops.aquaculture.service.SensorReadingService;
import com.evops.aquaculture.service.UnderwaterSensorService;
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
@RequestMapping("/api/sensors")
public class UnderwaterSensorController {

    private final UnderwaterSensorService sensorService;
    private final SensorReadingService readingService;

    public UnderwaterSensorController(UnderwaterSensorService sensorService,
                                      SensorReadingService readingService) {
        this.sensorService = sensorService;
        this.readingService = readingService;
    }

    @PostMapping
    public ApiResponse<UnderwaterSensor> create(@Valid @RequestBody SensorCreateRequest request) {
        return ApiResponse.ok(sensorService.create(request));
    }

    @GetMapping
    public ApiResponse<List<UnderwaterSensor>> list(@RequestParam(required = false) String cageNo,
                                                    @RequestParam(required = false) String sensorType,
                                                    @RequestParam(required = false) String status) {
        return ApiResponse.ok(sensorService.list(cageNo, sensorType, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<UnderwaterSensor> get(@PathVariable Long id) {
        return ApiResponse.ok(sensorService.getById(id));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<UnderwaterSensor> transitStatus(@PathVariable Long id,
                                                       @Valid @RequestBody SensorStatusRequest request) {
        return ApiResponse.ok(sensorService.transitStatus(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        sensorService.delete(id);
        return ApiResponse.ok(null);
    }

    // ==================== 读数 ====================

    /** 上报水下传感器读数。 */
    @PostMapping("/readings")
    public ApiResponse<SensorReading> reportReading(@Valid @RequestBody ReadingCreateRequest request) {
        return ApiResponse.ok(readingService.report(request));
    }

    @GetMapping("/readings")
    public ApiResponse<List<SensorReading>> listReadings(
            @RequestParam(required = false) Long sensorId,
            @RequestParam(required = false) String cageNo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime endTime) {
        return ApiResponse.ok(readingService.list(sensorId, cageNo, startTime, endTime));
    }
}
