package com.evops.aquaculture.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.aquaculture.entity.CageMigration;
import org.apache.ibatis.annotations.Mapper;

/**
 * 迁移事件台账 Mapper。
 *
 * 仅提供 insert/select：应用层没有任何 update/delete 路径；
 * 数据库另有 BEFORE UPDATE OR DELETE 触发器兜底，任何回写/删除历史快照的尝试都被拒绝。
 */
@Mapper
public interface CageMigrationMapper extends BaseMapper<CageMigration> {
}
