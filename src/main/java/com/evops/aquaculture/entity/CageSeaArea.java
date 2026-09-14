package com.evops.aquaculture.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 网箱海区档案：网箱所属海区与业务时区（IANA 时区标识）。
 * 是“按对象时区归算”的时区来源；潮次登记与规则计算均以本档案为准。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_cage_sea_area")
public class CageSeaArea extends BaseEntity {

    /** 网箱编号（关键业务键，唯一） */
    private String cageNo;

    /** 海区名称（如 东海一区） */
    private String seaArea;

    /** 业务时区（IANA 标识，如 Asia/Shanghai） */
    private String timeZone;

    private String remark;
}
