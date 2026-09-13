package com.evops.aquaculture.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.aquaculture.dto.SensorCreateRequest;
import com.evops.aquaculture.dto.SensorStatusRequest;
import com.evops.aquaculture.entity.SensorReading;
import com.evops.aquaculture.entity.UnderwaterSensor;
import com.evops.aquaculture.enums.SensorStatus;
import com.evops.aquaculture.mapper.SensorReadingMapper;
import com.evops.aquaculture.mapper.UnderwaterSensorMapper;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UnderwaterSensorService {

    private final UnderwaterSensorMapper sensorMapper;
    private final SensorReadingMapper readingMapper;

    public UnderwaterSensorService(UnderwaterSensorMapper sensorMapper, SensorReadingMapper readingMapper) {
        this.sensorMapper = sensorMapper;
        this.readingMapper = readingMapper;
    }

    @Transactional
    public UnderwaterSensor create(SensorCreateRequest request) {
        UnderwaterSensor existing = sensorMapper.selectOne(new LambdaQueryWrapper<UnderwaterSensor>()
                .eq(UnderwaterSensor::getSensorNo, request.getSensorNo()));
        if (existing != null) {
            throw new BusinessException("传感器编号已存在: " + request.getSensorNo());
        }

        UnderwaterSensor sensor = new UnderwaterSensor();
        sensor.setSensorNo(request.getSensorNo());
        sensor.setCageNo(request.getCageNo());
        sensor.setSensorType(request.getSensorType());
        sensor.setMetricUnit(request.getMetricUnit());
        sensor.setDepthM(request.getDepthM());
        sensor.setInstallTime(request.getInstallTime());
        sensor.setStatus(SensorStatus.ONLINE.name());
        sensorMapper.insert(sensor);
        return sensor;
    }

    public UnderwaterSensor getById(Long id) {
        UnderwaterSensor sensor = sensorMapper.selectById(id);
        if (sensor == null) {
            throw new BusinessException("水下传感器不存在: " + id);
        }
        return sensor;
    }

    public List<UnderwaterSensor> list(String cageNo, String sensorType, String status) {
        return sensorMapper.selectList(new LambdaQueryWrapper<UnderwaterSensor>()
                .eq(cageNo != null && !cageNo.isEmpty(), UnderwaterSensor::getCageNo, cageNo)
                .eq(sensorType != null && !sensorType.isEmpty(), UnderwaterSensor::getSensorType, sensorType)
                .eq(status != null && !status.isEmpty(), UnderwaterSensor::getStatus, status)
                .orderByAsc(UnderwaterSensor::getCageNo)
                .orderByDesc(UnderwaterSensor::getInstallTime));
    }

    @Transactional
    public UnderwaterSensor transitStatus(Long id, SensorStatusRequest request) {
        UnderwaterSensor sensor = getById(id);
        SensorStatus target;
        try {
            target = SensorStatus.valueOf(request.getTargetStatus());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("非法的传感器状态: " + request.getTargetStatus()
                    + "，可选 ONLINE/OFFLINE/MAINTENANCE");
        }
        sensor.setStatus(target.name());
        sensorMapper.updateById(sensor);
        return sensor;
    }

    /**
     * 删除传感器：已有读数上报的传感器不允许删除，保证监测数据可溯源。
     */
    @Transactional
    public void delete(Long id) {
        UnderwaterSensor sensor = getById(id);
        Long readingCount = readingMapper.selectCount(new LambdaQueryWrapper<SensorReading>()
                .eq(SensorReading::getSensorId, id));
        if (readingCount != null && readingCount > 0) {
            throw new BusinessException("传感器已上报读数，不能删除: " + sensor.getSensorNo());
        }
        sensorMapper.deleteById(id);
    }
}
