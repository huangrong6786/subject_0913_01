package com.evops.common.util;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 观测时刻（UTC 存储）与对象业务时区之间的换算。
 * 库表中的 *_at_utc 字段一律按 UTC 解释；归算时换算到网箱所在海区的时区。
 */
public final class TzUtils {

    private TzUtils() {
    }

    /** UTC 时刻换算为指定时区的当地时刻。 */
    public static LocalDateTime toLocal(LocalDateTime utcDateTime, ZoneId zoneId) {
        return utcDateTime.atOffset(ZoneOffset.UTC).atZoneSameInstant(zoneId).toLocalDateTime();
    }

    /** UTC 时刻在指定时区下的日内分钟（0..1439）。 */
    public static int minuteOfDay(LocalDateTime utcDateTime, ZoneId zoneId) {
        LocalDateTime local = toLocal(utcDateTime, zoneId);
        return local.getHour() * 60 + local.getMinute();
    }

    /** 校验并解析 IANA 时区标识；非法时区抛业务异常友好的 IllegalArgumentException。 */
    public static ZoneId requireZone(String timeZone) {
        try {
            return ZoneId.of(timeZone);
        } catch (Exception ex) {
            throw new IllegalArgumentException("非法的时区标识: " + timeZone);
        }
    }
}
