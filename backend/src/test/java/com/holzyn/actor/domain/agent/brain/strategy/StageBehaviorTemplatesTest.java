package com.holzyn.actor.domain.agent.brain.strategy;

import com.holzyn.actor.domain.growth.stage.GrowthStage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StageBehaviorTemplates 发育阶段行为候选池单元测试（O6 发育演化）。
 * <p>验证：受限阶段判定语义（未成年+老年，INFANT 归链外状态机、成年能力全开放）、
 * 时段判定边界（晨/昼/昏/夜小时窗）、候选池覆盖（5 受限阶段 × 4 时段恒非空且文本
 * 非空）、actionType 合法键、随机选取确定性（同 seed 同结果）、非受限阶段无候选。</p>
 */
class StageBehaviorTemplatesTest {

    // ==================== 受限阶段判定 ====================

    @Test
    void restrictedStagesCoversMinorAndElder() {
        List<GrowthStage> restricted = StageBehaviorTemplates.restrictedStages();
        assertTrue(restricted.contains(GrowthStage.TODDLER), "幼儿受限");
        assertTrue(restricted.contains(GrowthStage.CHILD), "学龄受限");
        assertTrue(restricted.contains(GrowthStage.ADOLESCENT), "青少年受限");
        assertTrue(restricted.contains(GrowthStage.ELDER), "老年受限");
        assertTrue(restricted.contains(GrowthStage.ANCIENT), "耄耋受限");
        assertFalse(restricted.contains(GrowthStage.INFANT), "婴儿由链外状态机接管");
        assertFalse(restricted.contains(GrowthStage.YOUTH), "青年能力全开放");
        assertFalse(restricted.contains(GrowthStage.PRIME), "壮年能力全开放");
        assertFalse(restricted.contains(GrowthStage.MIDDLE), "中年能力全开放");
        assertFalse(restricted.contains(GrowthStage.DECEASED), "已故退出运行");
        assertFalse(StageBehaviorTemplates.isRestricted(null), "null 阶段不适用");
    }

    // ==================== 时段判定 ====================

    @Test
    void periodOfHourWindows() {
        assertEquals(StageBehaviorTemplates.PERIOD_NIGHT, StageBehaviorTemplates.periodOf(0), "0 点=夜");
        assertEquals(StageBehaviorTemplates.PERIOD_DAWN, StageBehaviorTemplates.periodOf(5), "5 点=晨");
        assertEquals(StageBehaviorTemplates.PERIOD_DAWN, StageBehaviorTemplates.periodOf(8), "8 点=晨");
        assertEquals(StageBehaviorTemplates.PERIOD_DAY, StageBehaviorTemplates.periodOf(9), "9 点=昼");
        assertEquals(StageBehaviorTemplates.PERIOD_DAY, StageBehaviorTemplates.periodOf(16), "16 点=昼");
        assertEquals(StageBehaviorTemplates.PERIOD_DUSK, StageBehaviorTemplates.periodOf(17), "17 点=昏");
        assertEquals(StageBehaviorTemplates.PERIOD_DUSK, StageBehaviorTemplates.periodOf(20), "20 点=昏");
        assertEquals(StageBehaviorTemplates.PERIOD_NIGHT, StageBehaviorTemplates.periodOf(21), "21 点=夜");
        assertEquals(StageBehaviorTemplates.PERIOD_NIGHT, StageBehaviorTemplates.periodOf(23), "23 点=夜");
        assertEquals(StageBehaviorTemplates.PERIOD_NIGHT, StageBehaviorTemplates.periodOf(-1), "负数取模=夜");
        assertEquals(StageBehaviorTemplates.PERIOD_DAY, StageBehaviorTemplates.periodOf(24 + 12), "超界取模=昼");
    }

    @Test
    void periodLabelMapsChinese() {
        assertEquals("清晨", StageBehaviorTemplates.periodLabel(StageBehaviorTemplates.PERIOD_DAWN));
        assertEquals("白天", StageBehaviorTemplates.periodLabel(StageBehaviorTemplates.PERIOD_DAY));
        assertEquals("傍晚", StageBehaviorTemplates.periodLabel(StageBehaviorTemplates.PERIOD_DUSK));
        assertEquals("夜里", StageBehaviorTemplates.periodLabel(StageBehaviorTemplates.PERIOD_NIGHT));
        assertEquals("此刻", StageBehaviorTemplates.periodLabel("未知"));
        assertEquals("此刻", StageBehaviorTemplates.periodLabel(null));
    }

    // ==================== 候选池覆盖 ====================

    @Test
    void candidatesCoverAllRestrictedStagesAndPeriods() {
        String[] periods = {StageBehaviorTemplates.PERIOD_DAWN, StageBehaviorTemplates.PERIOD_DAY,
                StageBehaviorTemplates.PERIOD_DUSK, StageBehaviorTemplates.PERIOD_NIGHT};
        for (GrowthStage stage : StageBehaviorTemplates.restrictedStages()) {
            for (String period : periods) {
                List<StageBehaviorTemplates.BehaviorOption> list =
                        StageBehaviorTemplates.candidatesOf(stage, period);
                assertFalse(list.isEmpty(), stage.name() + "×" + period + " 候选非空");
                for (StageBehaviorTemplates.BehaviorOption o : list) {
                    assertNotNull(o.text(), "候选文本非 null");
                    assertFalse(o.text().isBlank(), stage.name() + "×" + period + " 候选文本非空");
                    assertTrue(isLegalActionType(o.actionType()),
                            stage.name() + " 候选 actionType 合法：" + o.actionType());
                }
            }
        }
    }

    @Test
    void candidatesEmptyForUnrestrictedOrUnknown() {
        assertTrue(StageBehaviorTemplates.candidatesOf(GrowthStage.INFANT,
                StageBehaviorTemplates.PERIOD_DAY).isEmpty(), "INFANT 无候选（链外状态机）");
        assertTrue(StageBehaviorTemplates.candidatesOf(GrowthStage.YOUTH,
                StageBehaviorTemplates.PERIOD_DAY).isEmpty(), "成年无候选（能力全开放）");
        assertTrue(StageBehaviorTemplates.candidatesOf(null, StageBehaviorTemplates.PERIOD_DAY).isEmpty(),
                "null 阶段空清单");
        assertTrue(StageBehaviorTemplates.candidatesOf(GrowthStage.CHILD, null).isEmpty(),
                "null 时段空清单");
        assertTrue(StageBehaviorTemplates.candidatesOf(GrowthStage.CHILD, "unknown").isEmpty(),
                "未知时段空清单");
    }

    // ==================== 随机选取 ====================

    @Test
    void pickReturnsCandidateWithStageReason() {
        Random rng = new Random(42L);
        StageBehaviorTemplates.BehaviorOption o =
                StageBehaviorTemplates.pick(GrowthStage.CHILD, 14, rng);
        assertNotNull(o, "CHILD×昼 有候选");
        assertFalse(o.text().isBlank());
        assertTrue(o.reason().contains("发育阶段行为"), "reason 标注来源：" + o.reason());
        assertTrue(o.reason().contains("学龄期"), "reason 含阶段中文名");
        assertTrue(o.reason().contains("白天"), "reason 含时段中文名");
    }

    @Test
    void pickDeterministicWithSameSeed() {
        Random r1 = new Random(7L);
        Random r2 = new Random(7L);
        StageBehaviorTemplates.BehaviorOption a = StageBehaviorTemplates.pick(GrowthStage.ELDER, 10, r1);
        StageBehaviorTemplates.BehaviorOption b = StageBehaviorTemplates.pick(GrowthStage.ELDER, 10, r2);
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(a.text(), b.text(), "同 seed 同结果（确定性）");
    }

    @Test
    void pickNullForUnrestrictedStage() {
        assertNull(StageBehaviorTemplates.pick(GrowthStage.PRIME, 12, new Random()),
                "成年阶段无候选 → null（链继续）");
        assertNull(StageBehaviorTemplates.pick(GrowthStage.INFANT, 12, new Random()),
                "INFANT 无候选 → null（链外状态机）");
    }

    @Test
    void pickToleratesNullRng() {
        StageBehaviorTemplates.BehaviorOption o = StageBehaviorTemplates.pick(GrowthStage.TODDLER, 8, null);
        assertNotNull(o, "null 随机源内部兜底新建");
    }

    /** actionType 合法键（对齐普通 NPC 链既有键集：""=杂务/其他、play/rest/chat）。 */
    private static boolean isLegalActionType(String actionType) {
        return actionType == null || actionType.isEmpty() || "play".equals(actionType)
                || "rest".equals(actionType) || "chat".equals(actionType);
    }
}
