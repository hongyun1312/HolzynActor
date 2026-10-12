package com.holzyn.actor.domain.growth.genetic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表观遗传修饰因子单元测试（O6 G4 验收：Q5 双源+单代+四类修正）。
 *
 * <p>口径：importance≥4；世界事件（有时刻）须落孕前窗口；L3 蒸馏块（无时刻）视为符合；
 * 四类关键词分类；同目标取最强；有效期为出生 + 7 游戏年；无命中=空印记零阻断。</p>
 */
class EpigeneticModifierTest {

    private static final long DAY = 86400L;
    private static final long YEAR = 31_536_000L;

    @Test
    @DisplayName("Q5：创伤/战乱事件 → 神经质上调 ×1.10~1.30，印记有效期到幼儿期末")
    void traumaRaisesNeuroticism() {
        long pregnancyStart = 10_000_000L;
        long birth = pregnancyStart + 100 * DAY;
        List<EpigeneticModifier.ParentEvent> events = List.of(
                new EpigeneticModifier.ParentEvent("村中械斗，兄长被杀，家宅被焚", 5, pregnancyStart - 10 * DAY));
        EpigeneticMarks marks = EpigeneticModifier.apply(events, pregnancyStart, birth, 90, YEAR);
        assertEquals(1, marks.getMarks().size());
        EpigeneticMarks.Mark mark = marks.getMarks().get(0);
        assertEquals(EpigeneticModifier.TARGET_NEUROTICISM, mark.getTarget());
        assertTrue(mark.getFactor() >= 1.10 && mark.getFactor() <= 1.30, "因子 " + mark.getFactor());
        assertEquals(birth + 7 * YEAR, mark.getValidUntilGameSecond());
        assertEquals("trauma_war", mark.getSourceEvent());
    }

    @Test
    @DisplayName("Q5：饥荒 → 代谢上调；孤立 → 外向下调；丰饶 → 宜人上调")
    void fourCategoryClassification() {
        long pregnancyStart = 10_000_000L;
        long birth = pregnancyStart + 100 * DAY;
        List<EpigeneticModifier.ParentEvent> events = List.of(
                new EpigeneticModifier.ParentEvent("大旱绝收，全村闹饥荒，挖野菜度日", 5, pregnancyStart - 20 * DAY),
                new EpigeneticModifier.ParentEvent("被逐出宗祠，独居山脚三年", 4, pregnancyStart - 30 * DAY),
                new EpigeneticModifier.ParentEvent("丰年庆典，村中大摆流水席", 4, pregnancyStart - 5 * DAY));
        EpigeneticMarks marks = EpigeneticModifier.apply(events, pregnancyStart, birth, 90, YEAR);
        assertEquals(3, marks.getMarks().size());
        EpigeneticMarks.Mark famine = marks.getMarks().stream()
                .filter(m -> EpigeneticModifier.TARGET_METABOLISM.equals(m.getTarget())).findFirst().orElseThrow();
        EpigeneticMarks.Mark isolated = marks.getMarks().stream()
                .filter(m -> EpigeneticModifier.TARGET_EXTRAVERSION.equals(m.getTarget())).findFirst().orElseThrow();
        EpigeneticMarks.Mark abundant = marks.getMarks().stream()
                .filter(m -> EpigeneticModifier.TARGET_AGREEABLENESS.equals(m.getTarget())).findFirst().orElseThrow();
        assertTrue(famine.getFactor() >= 1.05 && famine.getFactor() <= 1.15);
        assertTrue(isolated.getFactor() >= 0.85 && isolated.getFactor() <= 0.95, "孤立应下调外向");
        assertTrue(abundant.getFactor() >= 1.05 && abundant.getFactor() <= 1.10);
    }

    @Test
    @DisplayName("Q5：窗口过滤——孕前窗口外的事件不产生印记；importance<4 不产生印记")
    void windowAndImportanceFilter() {
        long pregnancyStart = 10_000_000L;
        long birth = pregnancyStart + 100 * DAY;
        List<EpigeneticModifier.ParentEvent> outside = List.of(
                new EpigeneticModifier.ParentEvent("年轻时上过战场", 5, pregnancyStart - 200 * DAY));
        assertTrue(EpigeneticModifier.apply(outside, pregnancyStart, birth, 90, YEAR).getMarks().isEmpty(),
                "窗口外事件不产生印记");
        List<EpigeneticModifier.ParentEvent> lowImportance = List.of(
                new EpigeneticModifier.ParentEvent("和邻居吵了一架", 2, pregnancyStart - 10 * DAY));
        assertTrue(EpigeneticModifier.apply(lowImportance, pregnancyStart, birth, 90, YEAR).getMarks().isEmpty(),
                "importance<4 不产生印记");
    }

    @Test
    @DisplayName("Q5：L3 蒸馏块（无时刻）视为窗口内；同目标多事件取最强一条（不叠加）")
    void coreMemoryWithoutTimeAndStrongestWins() {
        long pregnancyStart = 10_000_000L;
        long birth = pregnancyStart + 100 * DAY;
        List<EpigeneticModifier.ParentEvent> events = List.of(
                new EpigeneticModifier.ParentEvent("丧父之痛", 4, null),
                new EpigeneticModifier.ParentEvent("村破家亡，妻离子散", 5, null));
        EpigeneticMarks marks = EpigeneticModifier.apply(events, pregnancyStart, birth, 90, YEAR);
        // 同目标（两条都命中创伤）→ 只出一条印记（取强度最大：importance=5 那条）
        assertEquals(1, marks.getMarks().stream()
                .filter(m -> EpigeneticModifier.TARGET_NEUROTICISM.equals(m.getTarget())).count());
        assertEquals("村破家亡，妻离子散", marks.getMarks().get(0).getSourceDesc());
    }

    @Test
    @DisplayName("Q5：空事件/空文本 → 空印记（零阻断）")
    void emptyEventsYieldNoMarks() {
        EpigeneticMarks marks = EpigeneticModifier.apply(List.of(), 1L, 2L, 90, YEAR);
        assertTrue(marks.getMarks().isEmpty());
        EpigeneticMarks marks2 = EpigeneticModifier.apply(List.of(
                new EpigeneticModifier.ParentEvent(" ", 5, null)), 1L, 2L, 90, YEAR);
        assertTrue(marks2.getMarks().isEmpty());
    }
}
