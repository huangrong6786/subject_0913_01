package com.evops.common.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 日内分钟区间工具：区间一律左闭右开 [startMinute, endMinute)。
 * startMinute ∈ [0,1439]，endMinute ∈ [1,1440]；endMinute &lt;= startMinute 表示跨午夜区间
 * （如 22:00→02:00 记为 [1320, 120)）；[0,1440) 表示全天。
 */
public final class TimeIntervals {

    public static final int DAY_MINUTES = 1440;

    private TimeIntervals() {
    }

    /** 区间定义是否合法：边界在日内分钟范围内且非空区间。 */
    public static boolean isValid(int startMinute, int endMinute) {
        return startMinute >= 0 && startMinute < DAY_MINUTES
                && endMinute > 0 && endMinute <= DAY_MINUTES
                && startMinute != endMinute;
    }

    /** 区间是否覆盖一日全部 1440 分钟。 */
    public static boolean isFullDay(int startMinute, int endMinute) {
        return startMinute == 0 && endMinute == DAY_MINUTES;
    }

    /** 是否跨午夜区间（结束时刻不晚于开始时刻，跨越 0 点）。 */
    public static boolean crossesMidnight(int startMinute, int endMinute) {
        return endMinute <= startMinute;
    }

    /** 左闭右开判定：minute ∈ [start, end)；跨午夜区间按环形判定。 */
    public static boolean contains(int startMinute, int endMinute, int minute) {
        if (!crossesMidnight(startMinute, endMinute)) {
            return minute >= startMinute && minute < endMinute;
        }
        return minute >= startMinute || minute < endMinute;
    }

    /**
     * 两个日内分钟区间是否重叠（含跨午夜情形）。
     * 把跨午夜区间展开为 [start,1440) ∪ [0,end) 两段后做线性区间相交判定。
     */
    public static boolean overlaps(int start1, int end1, int start2, int end2) {
        for (int[] a : segments(start1, end1)) {
            for (int[] b : segments(start2, end2)) {
                if (a[0] < b[1] && b[0] < a[1]) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 展开为 [0,1440) 上的线性段（每段左闭右开）。 */
    private static List<int[]> segments(int start, int end) {
        List<int[]> segments = new ArrayList<>(2);
        if (!crossesMidnight(start, end)) {
            segments.add(new int[]{start, end});
        } else {
            segments.add(new int[]{start, DAY_MINUTES});
            segments.add(new int[]{0, end});
        }
        return segments;
    }

    /** 展示用：日内分钟格式化为 HH:mm（1440 归一化为 00:00 次日语义，展示为 24:00）。 */
    public static String format(int minuteOfDay) {
        if (minuteOfDay == DAY_MINUTES) {
            return "24:00";
        }
        return String.format("%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }
}
