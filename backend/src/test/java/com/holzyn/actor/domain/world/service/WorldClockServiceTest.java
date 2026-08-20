package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorldClockService 纯逻辑单元测试（P4-2 世界时钟）。
 * <p>职责：验证游戏时刻换算（真实→游戏小时）、单次补算封顶、游戏日边界判断与时刻格式化。</p>
 */
class WorldClockServiceTest {

    /**
     * 游戏时刻换算：rate=24（1 真实小时=1 游戏日），1 小时前锚点 → 24 游戏小时 + 起始时刻。
     */
    @Test
    void gameHourOfConvertsByRate() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 14, 12, 0);
        LocalDateTime startAt = now.minusHours(1);
        assertEquals(24, WorldClockService.gameHourOf(now, startAt, 0, 24), "1 真实小时×24=24 游戏小时");
        assertEquals(25, WorldClockService.gameHourOf(now, startAt, 1, 24), "起始游戏时刻累加");
    }

    /**
     * 游戏时刻换算：rate=1 时与真实时间 1:1；半小时精度按分钟计算不丢失。
     */
    @Test
    void gameHourOfLowRateAndMinutes() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 14, 12, 30);
        LocalDateTime startAt = LocalDateTime.of(2026, 8, 14, 10, 0);
        assertEquals(2, WorldClockService.gameHourOf(now, startAt, 0, 1), "rate=1：2.5 真实小时→2 游戏小时（向下取整）");
        assertEquals(5, WorldClockService.gameHourOf(now, startAt, 0, 2), "rate=2：2.5 真实小时→5 游戏小时");
    }

    /**
     * 游戏时刻换算边界：锚点为空返回起始时刻；now 早于锚点返回负值；rate≤0 返回起始时刻。
     */
    @Test
    void gameHourOfEdgeCases() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 14, 12, 0);
        assertEquals(7, WorldClockService.gameHourOf(now, null, 7, 24), "锚点为空=以 now 为锚点");
        assertEquals(-24, WorldClockService.gameHourOf(now.minusHours(1), now, 0, 24), "now 早于锚点返回负值");
        assertEquals(0, WorldClockService.gameHourOf(now, now.minusHours(1), 0, 0), "rate≤0 不换算");
    }

    /**
     * 单次补算封顶：超过上限截断；未超上限原样；无流逝返回 0。
     */
    @Test
    void capElapsedLimitsCatchUp() {
        assertEquals(24, WorldClockService.capElapsed(100, 24), "超上限截断到 24");
        assertEquals(10, WorldClockService.capElapsed(10, 24), "未超上限原样");
        assertEquals(0, WorldClockService.capElapsed(0, 24), "无流逝返回 0");
        assertEquals(0, WorldClockService.capElapsed(-5, 24), "负流逝返回 0");
        assertEquals(50, WorldClockService.capElapsed(50, 0), "cap<=0 视为不封顶");
    }

    /**
     * 游戏日边界判断：跨日返回 true；同日返回 false。
     */
    @Test
    void crossesDayDetectsDayBoundary() {
        assertTrue(WorldClockService.crossesDay(0, 24), "第 0→24 小时跨 1 个游戏日");
        assertTrue(WorldClockService.crossesDay(23, 25), "第 23→25 小时跨日");
        assertFalse(WorldClockService.crossesDay(0, 10), "同日不跨");
        assertFalse(WorldClockService.crossesDay(20, 23), "同日不跨");
        assertFalse(WorldClockService.crossesDay(24, 47), "第 2 天内不跨");
    }

    /**
     * 时刻格式化：第 X 日 + 时段描述；时段映射正确。
     */
    @Test
    void formatGameTimeAndPeriod() {
        assertEquals("第 1 日·深夜", WorldClockService.formatGameTime(0));
        assertEquals("第 1 日·正午", WorldClockService.formatGameTime(12));
        assertEquals("第 2 日·正午", WorldClockService.formatGameTime(36), "36 小时=第 2 天正午");
        assertEquals("深夜", WorldClockService.periodText(2));
        assertEquals("清晨", WorldClockService.periodText(7));
        assertEquals("上午", WorldClockService.periodText(10));
        assertEquals("午后", WorldClockService.periodText(15));
        assertEquals("傍晚", WorldClockService.periodText(18));
        assertEquals("夜晚", WorldClockService.periodText(21));
        assertEquals("深夜", WorldClockService.periodText(-1), "负小时取模到当日");
    }

    // ==================== vP5-7.11：秒级换算 + 世界历完整格式 ====================

    /**
     * 秒级换算：rate=24（1 真实小时=1 游戏日=86400 游戏秒），1 真实小时 → 86400 游戏秒；起始时刻累加。
     */
    @Test
    void gameSecondOfConvertsByRate() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 14, 12, 0);
        LocalDateTime startAt = now.minusHours(1);
        assertEquals(86400L, WorldClockService.gameSecondOf(now, startAt, 0, 24), "1 真实小时×24=86400 游戏秒");
        assertEquals(3600L + 86400L, WorldClockService.gameSecondOf(now, startAt, 1, 24), "起始游戏时刻(1小时)累加");
        assertEquals(0L, WorldClockService.gameSecondOf(now, null, 0, 24), "锚点为空=以 now 为锚点");
        assertEquals(7200L, WorldClockService.gameSecondOf(now, now.minusHours(2), 0, 1), "rate=1：1:1 换算");
    }

    /**
     * 世界历完整格式（秒级）：0 → 0001年01月01日 00时00分00秒；日/时/分/月/年进位正确。
     */
    @Test
    void formatWorldTimeFull() {
        assertEquals("世界历 0001年01月01日 00时00分00秒", WorldClockService.formatWorldTime(0, null));
        assertEquals("世界历 0001年01月01日 01时00分00秒", WorldClockService.formatWorldTime(3600, "世界历"));
        assertEquals("世界历 0001年01月01日 00时01分00秒", WorldClockService.formatWorldTime(60, null));
        assertEquals("世界历 0001年01月02日 00时00分00秒", WorldClockService.formatWorldTime(86400, null), "1 游戏日进位");
        assertEquals("世界历 0001年02月01日 00时00分00秒", WorldClockService.formatWorldTime(30L * 86400, null), "1 游戏月（30日）进位");
        assertEquals("世界历 0002年01月01日 00时00分00秒", WorldClockService.formatWorldTime(360L * 86400, null), "1 游戏年（360日）进位");
        assertEquals("暖绒市历 0001年01月01日 00时00分00秒", WorldClockService.formatWorldTime(0, "暖绒市历"), "自定义历法名");
        assertEquals("世界历 0001年01月01日 00时00分00秒", WorldClockService.formatWorldTime(-100, null), "负数按 0 处理");
    }

    /**
     * 世界历到分格式（时间线节点展示）：秒忽略，含历法前缀。
     */
    @Test
    void formatWorldTimeMinuteOnly() {
        assertEquals("世界历 0001年01月01日 00时00分", WorldClockService.formatWorldTimeMinute(59, null));
        assertEquals("世界历 0001年01月01日 12时05分", WorldClockService.formatWorldTimeMinute(12 * 3600L + 5 * 60L + 42, null));
    }

    /**
     * 世界历时间点 → 游戏总秒数（2026-08-19 世界初始化第 5 步）：按 1年=12月=30日=360日 换算。
     */
    @Test
    void calendarToGameSecondConverts() {
        // 0001-01-01 00:00:00 → 0
        assertEquals(0L, WorldClockService.calendarToGameSecond(1, 1, 1, 0, 0, 0));
        // 0001-01-01 01:00:00 → 3600
        assertEquals(3600L, WorldClockService.calendarToGameSecond(1, 1, 1, 1, 0, 0));
        // 0001-01-02 00:00:00 → 1 日 = 86400
        assertEquals(86400L, WorldClockService.calendarToGameSecond(1, 1, 2, 0, 0, 0));
        // 0001-02-01 → 1 月 = 30 日 = 2592000
        assertEquals(30L * 86400L, WorldClockService.calendarToGameSecond(1, 2, 1, 0, 0, 0));
        // 0002-01-01 → 1 年 = 360 日 = 31104000
        assertEquals(360L * 86400L, WorldClockService.calendarToGameSecond(2, 1, 1, 0, 0, 0));
        // 普通时间点：1050-03-12 08:30:00
        long expected = 1049L * 360L * 86400L + 2L * 30L * 86400L + 11L * 86400L + 8L * 3600L + 30L * 60L;
        assertEquals(expected, WorldClockService.calendarToGameSecond(1050, 3, 12, 8, 30, 0));
    }

    /**
     * 世界历时间点换算边界：越界分量自动夹取（year 最小 1，month 1-12，day 1-30，hour 0-23，minute/second 0-59）。
     */
    @Test
    void calendarToGameSecondClampsBounds() {
        assertEquals(0L, WorldClockService.calendarToGameSecond(0, 0, 0, -1, -1, -1), "全越界夹取到 0001-01-01 00:00:00");
        assertEquals(0L, WorldClockService.calendarToGameSecond(-5, 1, 1, 0, 0, 0), "year<1 夹到 1");
        assertEquals(29L * 86400L, WorldClockService.calendarToGameSecond(1, 1, 99, 0, 0, 0), "day>30 夹到 30");
        assertEquals(86400L + 59L, WorldClockService.calendarToGameSecond(1, 1, 2, 0, 0, 60), "second=60 夹到 59");
    }
}
