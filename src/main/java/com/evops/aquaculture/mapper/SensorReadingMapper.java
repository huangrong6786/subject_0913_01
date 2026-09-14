package com.evops.aquaculture.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.aquaculture.entity.SensorReading;
import com.evops.aquaculture.entity.UnderwaterSensor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SensorReadingMapper extends BaseMapper<SensorReading> {

    /** 对传感器行加锁（读数上报与物理迁移串行化）。 */
    @Select("SELECT * FROM t_underwater_sensor WHERE id = #{id} FOR UPDATE")
    UnderwaterSensor selectSensorForUpdate(@Param("id") Long id);
}
