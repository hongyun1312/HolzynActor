package com.holzyn.actor.domain.growth.space;

import com.holzyn.actor.domain.growth.stage.GrowthStage;
import com.holzyn.actor.domain.growth.stage.HardGateResult;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 程序化兜底空间工厂单元测试（O6 轨道 G2，纯逻辑、零 AI）。
 * <p>职责：阶段行动池（INFANT 婴儿行动 / ELDER 老年行动 / DECEASED 空池）、状态驱动项
 * （饥饿/精力/社交/健康触发）、硬门控禁止项透传、无状态行宽松兜底。</p>
 */
class ProgrammaticSpaceFactoryTest {

    private ActorNpcState stateWith(int hunger, int energy, int social, int health) {
        ActorNpcState s = new ActorNpcState();
        s.setHunger(hunger);
        s.setEnergy(energy);
        s.setSocial(social);
        s.setHealth(health);
        return s;
    }

    @Test
    @DisplayName("INFANT 行动池：婴儿行为且不出现耕作类行动")
    void infantPool() {
        var doc = ProgrammaticSpaceFactory.build("小宝", GrowthStage.INFANT,
                HardGateResult.lenient("npc:1"), null, null);
        assertNotNull(doc);
        List<String> actions = doc.possibleActions().stream().map(a -> a.action()).toList();
        assertTrue(actions.contains("在襁褓里安睡"), "婴儿行动池含安睡");
        assertTrue(actions.contains("哭闹要奶"), "婴儿行动池含哭闹要奶");
        assertFalse(actions.stream().anyMatch(a -> a.contains("耕作") || a.contains("远行")),
                "婴儿行动池不得出现成人行为");
    }

    @Test
    @DisplayName("ELDER 行动池：老年行动且无重体力行为")
    void elderPool() {
        var doc = ProgrammaticSpaceFactory.build("老张", GrowthStage.ELDER,
                HardGateResult.lenient("npc:2"), null, null);
        List<String> actions = doc.possibleActions().stream().map(a -> a.action()).toList();
        assertTrue(actions.contains("坐在门口晒太阳"));
        assertFalse(actions.stream().anyMatch(a -> a.contains("挑担") || a.contains("垦荒")),
                "老年行动池不得出现重体力行为");
    }

    @Test
    @DisplayName("DECEASED 空行动池（不消费空间）")
    void deceasedPool() {
        var doc = ProgrammaticSpaceFactory.build("故人", GrowthStage.DECEASED,
                HardGateResult.lenient("npc:3"), null, null);
        assertTrue(doc.possibleActions().isEmpty());
    }

    @Test
    @DisplayName("状态驱动：饥饿/精力/社交/健康低于阈值追加对应行动")
    void stateDrivenActions() {
        var doc = ProgrammaticSpaceFactory.build("阿福", GrowthStage.PRIME,
                HardGateResult.lenient("npc:4"),
                stateWith(30, 20, 40, 45), "在铺面里记账");
        List<String> actions = doc.possibleActions().stream().map(a -> a.action()).toList();
        assertTrue(actions.contains("张罗吃食填肚子"));
        assertTrue(actions.contains("歇息一阵养精神"));
        assertTrue(actions.contains("去找相熟的人说说话"));
        assertTrue(actions.contains("躺着将养身子"));
        // 内心主导念头引用当前行动
        assertTrue(doc.inner().dominantThought().contains("记账"));
    }

    @Test
    @DisplayName("状态充足时无驱动项（仅阶段池）")
    void noStateDrivenWhenFine() {
        var doc = ProgrammaticSpaceFactory.build("阿福", GrowthStage.PRIME,
                HardGateResult.lenient("npc:5"),
                stateWith(80, 80, 80, 90), null);
        List<String> actions = doc.possibleActions().stream().map(a -> a.action()).toList();
        assertFalse(actions.contains("张罗吃食填肚子"));
        assertFalse(actions.contains("躺着将养身子"));
        assertEquals(4, actions.size(), "PRIME 阶段池 4 项");
    }

    @Test
    @DisplayName("硬门控禁止项透传进空间 forbidden")
    void forbiddenPassthrough() {
        HardGateResult gate = new HardGateResult("npc:6", "ELDER", 70, 0.5,
                List.of("walk", "talk"), List.of(
                new HardGateResult.ForbiddenItem("重体力劳作", "阶段硬约束（老年期）")),
                List.of("感官退化"), java.util.Map.of());
        var doc = ProgrammaticSpaceFactory.build("老李", GrowthStage.ELDER, gate, null, null);
        assertEquals(1, doc.forbidden().size());
        assertEquals("重体力劳作", doc.forbidden().get(0).what());
    }

    @Test
    @DisplayName("null 阶段用通用池（宽松兜底）")
    void nullStageGenericPool() {
        var doc = ProgrammaticSpaceFactory.build("无名", null, null, null, null);
        assertNotNull(doc);
        assertFalse(doc.possibleActions().isEmpty());
    }
}
