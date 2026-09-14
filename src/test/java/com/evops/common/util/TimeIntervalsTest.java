package com.evops.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日内分钟区间工具：左闭右开、跨午夜环形区间、重叠判定的边界行为。
 */
class TimeIntervalsTest {

    // ---------------- 合法性 ----------------

    @Test
    void isValid_boundaries() {
        assertTrue(TimeIntervals.isValid(0, 1440), "全天区间合法");
        assertTrue(TimeIntervals.isValid(360, 600));
        assertTrue(TimeIntervals.isValid(1320, 120), "跨午夜区间合法");
        assertFalse(TimeIntervals.isValid(600, 600), "空区间非法");
        assertFalse(TimeIntervals.isValid(-1, 600));
        assertFalse(TimeIntervals.isValid(600, 1441));
        assertFalse(TimeIntervals.isValid(1440, 100), "开始分钟必须落在 0..1439");
        assertFalse(TimeIntervals.isValid(0, 0));
    }

    // ---------------- 左闭右开包含 ----------------

    @Test
    void contains_leftClosedRightOpen() {
        // [06:00, 10:00)
        assertTrue(TimeIntervals.contains(360, 600, 360), "左边界闭：06:00 命中");
        assertTrue(TimeIntervals.contains(360, 600, 599));
        assertFalse(TimeIntervals.contains(360, 600, 600), "右边界开：10:00 不命中");
        assertFalse(TimeIntervals.contains(360, 600, 359));
    }

    @Test
    void contains_crossMidnight() {
        // [22:00, 02:00) 跨午夜
        assertTrue(TimeIntervals.contains(1320, 120, 1320), "左边界闭：22:00 命中");
        assertTrue(TimeIntervals.contains(1320, 120, 1439));
        assertTrue(TimeIntervals.contains(1320, 120, 0), "跨午夜后 00:00 命中");
        assertTrue(TimeIntervals.contains(1320, 120, 119));
        assertFalse(TimeIntervals.contains(1320, 120, 120), "右边界开：02:00 不命中");
        assertFalse(TimeIntervals.contains(1320, 120, 1319));
        assertFalse(TimeIntervals.contains(1320, 120, 600));
    }

    @Test
    void contains_fullDay() {
        assertTrue(TimeIntervals.contains(0, 1440, 0));
        assertTrue(TimeIntervals.contains(0, 1440, 1439));
    }

    // ---------------- 重叠判定 ----------------

    @Test
    void overlaps_adjacentIntervalsDoNotOverlap() {
        // 左闭右开：[06:00,10:00) 与 [10:00,12:00) 相邻不重叠
        assertFalse(TimeIntervals.overlaps(360, 600, 600, 720));
        assertFalse(TimeIntervals.overlaps(600, 720, 360, 600));
    }

    @Test
    void overlaps_realOverlap() {
        assertTrue(TimeIntervals.overlaps(360, 600, 599, 700));
        assertTrue(TimeIntervals.overlaps(360, 600, 360, 600), "完全相等必重叠");
        assertTrue(TimeIntervals.overlaps(360, 600, 400, 500), "包含必重叠");
    }

    @Test
    void overlaps_crossMidnight() {
        // [22:00,02:00) 与 [00:00,02:00)：共享 [00:00,02:00) 段
        assertTrue(TimeIntervals.overlaps(1320, 120, 0, 120));
        // [22:00,02:00) 与 [02:00,06:00)：相邻不重叠
        assertFalse(TimeIntervals.overlaps(1320, 120, 120, 360));
        // [22:00,02:00) 与 [20:00,22:00)：相邻不重叠
        assertFalse(TimeIntervals.overlaps(1320, 120, 1200, 1320));
        // [22:00,02:00) 与 [21:00,23:00)：重叠
        assertTrue(TimeIntervals.overlaps(1320, 120, 1260, 1380));
        // 两个跨午夜区间 [21:30,01:00) 与 [22:00,02:00)
        assertTrue(TimeIntervals.overlaps(1290, 60, 1320, 120));
        // [22:00,02:00) 与 [03:00,21:00)：不重叠
        assertFalse(TimeIntervals.overlaps(1320, 120, 180, 1260));
    }

    @Test
    void overlaps_fullDayOverlapsEverything() {
        assertTrue(TimeIntervals.overlaps(0, 1440, 360, 600));
        assertTrue(TimeIntervals.overlaps(0, 1440, 1320, 120));
    }
}
