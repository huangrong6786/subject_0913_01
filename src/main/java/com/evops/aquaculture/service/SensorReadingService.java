package com.evops.aquaculture.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.aquaculture.dto.ReadingCreateRequest;
import com.evops.aquaculture.entity.SensorReading;
import com.evops.aquaculture.entity.UnderwaterSensor;
import com.evops.aquaculture.enums.SensorStatus;
import com.evops.aquaculture.mapper.SensorReadingMapper;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class SensorReadingService {

    private final SensorReadingMapper readingMapper;
    private final UnderwaterSensorService sensorService;

    public SensorReadingService(SensorReadingMapper readingMapper, UnderwaterSensorService sensorService) {
        this.readingMapper = readingMapper;
        this.sensorService = sensorService;
    }

    /** 上报读数：继承传感器网箱编号；维护中/离线传感器拒绝上报。 */
    @Transactional
    public SensorReading report(ReadingCreateRequest request) {
        UnderwaterSensor sensor = sensorService.getById(request.getSensorId());
        if (SensorStatus.OFFLINE.name().equals(sensor.getStatus())
                || SensorStatus.MAINTENANCE.name().equals(sensor.getStatus())) {
            throw new BusinessException("传感器当前状态为 " + sensor.getStatus() + "，不能上报读数: "
                    + sensor.getSensorNo());
        }

        SensorReading existing = readingMapper.selectOne(new LambdaQueryWrapper<SensorReading>()
                .eq(SensorReading::getReadingNo, request.getReadingNo()));
        if (existing != null) {
            throw new BusinessException("读数流水号已存在: " + request.getReadingNo());
        }

        SensorReading reading = new SensorReading();
        reading.setReadingNo(request.getReadingNo());
        reading.setSensorId(sensor.getId());
        reading.setCageNo(sensor.getCageNo());
        reading.setMetricValue(request.getMetricValue());
        reading.setReadingTime(request.getReadingTime());
        readingMapper.insert(reading);
        return reading;
    }

    public List<SensorReading> list(Long sensorId, String cageNo,
                                    LocalDateTime startTime, LocalDateTime endTime) {
        return readingMapper.selectList(new LambdaQueryWrapper<SensorReading>()
                .eq(sensorId != null, SensorReading::getSensorId, sensorId)
                .eq(cageNo != null && !cageNo.isEmpty(), SensorReading::getCageNo, cageNo)
                .ge(startTime != null, SensorReading::getReadingTime, startTime)
                .le(endTime != null, SensorReading::getReadingTime, endTime)
                .orderByDesc(SensorReading::getReadingTime));
    }
}
