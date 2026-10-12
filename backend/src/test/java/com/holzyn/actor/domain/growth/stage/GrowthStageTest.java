package com.holzyn.actor.domain.growth.stage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全生命周期发育阶段枚举单元测试（O6 轨道 G2，纯逻辑）。
 * <p>职责：阶段边界（左闭右开）、ofAge/of 解析、isMinor/isAdult/isElder/isAlive 语义、
 * 中文 label/summary 完整性。</p>
 */
class GrowthStageTest {

    @Test
    @DisplayName("阶段边界：ofAge 左闭右开，边界值落在正确的阶段")
    void stageBoundaries() {
        assertSame(GrowthStage.INFANT, GrowthStage.ofAge(0));
        assertSame(GrowthStage.INFANT, GrowthStage.ofAge(1));
        assertSame(GrowthStage.TODDLER, GrowthStage.ofAge(2));
        assertSame(GrowthStage.TODDLER, GrowthStage.ofAge(6));
        assertSame(GrowthStage.CHILD, GrowthStage.ofAge(7));
        assertSame(GrowthStage.CHILD, GrowthStage.ofAge(11));
        assertSame(GrowthStage.ADOLESCENT, GrowthStage.ofAge(12));
        assertSame(GrowthStage.ADOLESCENT, GrowthStage.ofAge(17));
        assertSame(GrowthStage.YOUTH, GrowthStage.ofAge(18));
        assertSame(GrowthStage.YOUTH, GrowthStage.ofAge(29));
        assertSame(GrowthStage.PRIME, GrowthStage.ofAge(30));
        assertSame(GrowthStage.PRIME, GrowthStage.ofAge(44));
        assertSame(GrowthStage.MIDDLE, GrowthStage.ofAge(45));
        assertSame(GrowthStage.MIDDLE, GrowthStage.ofAge(59));
        assertSame(GrowthStage.ELDER, GrowthStage.ofAge(60));
        assertSame(GrowthStage.ELDER, GrowthStage.ofAge(74));
        assertSame(GrowthStage.ANCIENT, GrowthStage.ofAge(75));
        assertSame(GrowthStage.ANCIENT, GrowthStage.ofAge(90));
        assertSame(GrowthStage.ANCIENT, GrowthStage.ofAge(120));
    }

    @Test
    @DisplayName("of 解析：合法码/大小写/非法码/空")
    void ofParsing() {
        assertSame(GrowthStage.YOUTH, GrowthStage.of("YOUTH"));
        assertSame(GrowthStage.YOUTH, GrowthStage.of("youth"));
        assertNull(GrowthStage.of("NOT_A_STAGE"));
        assertNull(GrowthStage.of(null));
        assertNull(GrowthStage.of(""));
    }

    @Test
    @DisplayName("成年/未成年/老年/在世语义")
    void lifeSemantics() {
        assertTrue(GrowthStage.ofAge(0).isMinor());
        assertTrue(GrowthStage.ofAge(6).isMinor());
        assertTrue(GrowthStage.ofAge(17).isMinor());
        assertFalse(GrowthStage.ofAge(18).isMinor());
        assertTrue(GrowthStage.ofAge(18).isAdult());
        assertTrue(GrowthStage.ofAge(45).isAdult());
        assertFalse(GrowthStage.ofAge(12).isAdult());
        assertTrue(GrowthStage.ofAge(60).isElder());
        assertTrue(GrowthStage.ofAge(75).isElder());
        assertFalse(GrowthStage.ofAge(45).isElder());
        assertFalse(GrowthStage.ofAge(30).isElder());
        assertTrue(GrowthStage.INFANT.isAlive());
        assertTrue(GrowthStage.ANCIENT.isAlive());
        assertFalse(GrowthStage.DECEASED.isAlive());
    }

    @Test
    @DisplayName("10 阶段齐备：label/summary 非空，DECEASED 排最后")
    void completeness() {
        assertEquals(10, GrowthStage.values().length);
        for (GrowthStage s : GrowthStage.values()) {
            assertFalse(s.label().isBlank(), s.name() + " label 非空");
            assertFalse(s.summary().isBlank(), s.name() + " summary 非空");
        }
        assertEquals("已故", GrowthStage.DECEASED.label());
        assertEquals("婴儿期", GrowthStage.INFANT.label());
    }
}
