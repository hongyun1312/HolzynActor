package com.holzyn.actor.domain.mapnav.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * AI 意图驻留锁。
 *
 * <p>这组断言保护的是「AI 让人去哪、人到了能待一会儿」这件事：窗口内日程不得抢人，
 * 窗口一过必须立刻放手（否则角色会永远钉在目的地，作息全废）。</p>
 */
class NpcIntentLockTest {

    private static final Long P = 143L;

    @Test
    void holdsUntilDeadlineThenExpires() {
        NpcIntentLock lock = new NpcIntentLock();
        lock.hold(P, "npc:5", 1_000L, "f_rice_shop_40", "顺路去米行讨碗水喝", "special");

        assertNotNull(lock.holding(P, "npc:5", 900L), "窗口内应持有");
        assertNull(lock.holding(P, "npc:5", 1_000L), "到点即失效（不拖延一个 tick）");
        assertNull(lock.holding(P, "npc:5", 1_001L), "过期后不再持有");
        assertEquals(0, lock.size(), "过期项应被顺手清掉，避免无界增长");
    }

    @Test
    void remainingCountsDown() {
        NpcIntentLock lock = new NpcIntentLock();
        lock.hold(P, "npc:5", 1_000L, "f_home_62", "去阿珍家", "special");
        NpcIntentLock.Hold h = lock.holding(P, "npc:5", 400L);
        assertNotNull(h);
        assertEquals(600L, h.remaining(400L));
        assertEquals("f_home_62", h.featureCode());
        assertEquals("去阿珍家", h.action());
    }

    @Test
    void newIntentOverwritesOldOne() {
        NpcIntentLock lock = new NpcIntentLock();
        lock.hold(P, "npc:5", 1_000L, "f_rice_shop_40", "去米行", "special");
        lock.hold(P, "npc:5", 5_000L, "f_home_62", "回家", "special");

        NpcIntentLock.Hold h = lock.holding(P, "npc:5", 2_000L);
        assertNotNull(h, "新意图应覆盖旧窗口（否则人会被旧目的地钉住）");
        assertEquals("f_home_62", h.featureCode());
    }

    @Test
    void clearReleasesImmediately() {
        NpcIntentLock lock = new NpcIntentLock();
        lock.hold(P, "npc:5", 9_999L, "f_home_62", "回家", "special");
        lock.clear(P, "npc:5");
        assertNull(lock.holding(P, "npc:5", 100L), "被摆位/被打断后应立刻放手");
    }

    @Test
    void blankRefIgnored() {
        NpcIntentLock lock = new NpcIntentLock();
        lock.hold(P, "  ", 9_999L, "x", "y", "special");
        assertEquals(0, lock.size(), "空 ref 不登记（防脏键堆积）");
        assertNull(lock.holding(P, null, 1L));
    }

    @Test
    void differentProjectIsolated() {
        NpcIntentLock lock = new NpcIntentLock();
        lock.hold(143L, "npc:5", 9_999L, "f_home_62", "回家", "special");
        assertNull(lock.holding(144L, "npc:5", 100L), "另一个世界不该继承本世界的意图");
    }
}
