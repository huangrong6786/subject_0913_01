package com.evops.aquaculture.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.aquaculture.entity.UnderwaterSensor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface UnderwaterSensorMapper extends BaseMapper<UnderwaterSensor> {

    /** 行级悲观锁：物理迁移与读数上报在传感器行上串行化。 */
    @Select("SELECT * FROM t_underwater_sensor WHERE id = #{id} FOR UPDATE")
    UnderwaterSensor selectForUpdate(@Param("id") Long id);

    /**
     * 传感器物理迁移（乐观锁 CAS）：仅当设备版本仍为 expectedVersion 时
     * 落位目标网箱并版本 +1。与传感器状态流转（停用/维护）竞争时仅一方生效，
     * 迁移快照采集后被停用的设备不会被误迁。
     */
    @Update("UPDATE t_underwater_sensor SET cage_no = #{targetCageNo}, version = version + 1, " +
            "migrated_at = #{now}, migration_id = #{migrationId}, update_time = #{now} " +
            "WHERE id = #{sensorId} AND version = #{expectedVersion}")
    int migrateCageIfVersion(@Param("sensorId") Long sensorId,
                             @Param("targetCageNo") String targetCageNo,
                             @Param("expectedVersion") int expectedVersion,
                             @Param("migrationId") Long migrationId,
                             @Param("now") LocalDateTime now);

    /** 传感器状态流转条件更新（乐观锁 CAS）：与物理迁移互斥。 */
    @Update("UPDATE t_underwater_sensor SET status = #{status}, version = version + 1, update_time = #{now} " +
            "WHERE id = #{sensorId} AND version = #{expectedVersion}")
    int updateStatusIfVersion(@Param("sensorId") Long sensorId,
                              @Param("status") String status,
                              @Param("expectedVersion") int expectedVersion,
                              @Param("now") LocalDateTime now);
}
