package com.evops.aquaculture.enums;

/**
 * 网箱迁移类型。
 */
public enum MigrationType {
    /** 换箱：同海区更换网箱对象（设备/鱼群移入新网箱） */
    REPLACE,
    /** 转场：网箱整体转移到另一海区/场地 */
    TRANSFER;

    /** 解析入参，缺省（null/空）按转场处理；非法值抛 IllegalArgumentException。 */
    public static MigrationType fromParam(String value) {
        if (value == null || value.trim().isEmpty()) {
            return TRANSFER;
        }
        return MigrationType.valueOf(value.trim().toUpperCase());
    }
}
