package com.holzyn.actor.domain.npc.state.service;

import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N3 判定类落地单测：crowd 程序化候选集（NpcStateService.buildNanoJevCrowdCandidates 纯静态）。
 *
 * <p>覆盖：通用三项恒在（继续当前/歇一歇/找人说说话）、需求驱动项按阈值进入（饥饿/口渴/
 * 疲惫/闷/无聊/不自在）、上限 8、rest 描述随疲惫动态、低权重项（歇/聊）永在=真人式放空。</p>
 *
 * <p>所属模块：domain/npc/state/service（N 轨道 N3 crowd 对接）</p>
 */
class NanoJevCrowdCandidatesTest {

    private static ActorNpcState state() {
        ActorNpcState st = new ActorNpcState();
        return st;
    }

    @Test
    @DisplayName("无状态/空状态 → 仅通用三项（含低权重歇/聊）")
    void noState() {
        Map<String, String> c = NpcStateService.buildNanoJevCrowdCandidates(null, null, List.of());
        assertNotNull(c);
        assertEquals(3, c.size());
        assertTrue(c.containsKey("continue_current"));
        assertTrue(c.containsKey("rest"));
        assertTrue(c.containsKey("chat"));
    }

    @Test
    @DisplayName("需求驱动项按阈值进入：饥饿/口渴/疲惫/闷/无聊/不自在")
    void needDrivenCandidates() {
        ActorNpcState st = state();
        st.setHunger(30);
        st.setThirst(50);
        st.setEnergy(20);
        st.setSocial(40);
        st.setFun(30);
        st.setHygiene(60);
        Map<String, String> c = NpcStateService.buildNanoJevCrowdCandidates(st, "在田里锄地", List.of());
        assertTrue(c.containsKey("eat"));
        assertTrue(c.containsKey("drink"));
        assertTrue(c.containsKey("rest"));
        assertTrue(c.containsKey("social"));
        assertTrue(c.containsKey("play"));
        assertTrue(c.containsKey("wash"));
        assertTrue(c.containsKey("continue_current"));
        // 上限 8
        assertTrue(c.size() <= 8);
    }

    @Test
    @DisplayName("需求高于阈值不进候选；rest 描述随疲惫动态")
    void needThresholdAndRestDynamic() {
        ActorNpcState st = state();
        st.setHunger(90); // 不饿
        st.setEnergy(95); // 不疲惫
        Map<String, String> c = NpcStateService.buildNanoJevCrowdCandidates(st, "在村口乘凉", List.of());
        assertFalse(c.containsKey("eat"));
        // rest 无疲惫 → 通用描述
        assertTrue(c.get("rest").contains("放空"));
        st.setEnergy(30);
        Map<String, String> c2 = NpcStateService.buildNanoJevCrowdCandidates(st, "在村口乘凉", List.of());
        assertTrue(c2.get("rest").contains("疲惫"));
    }

    @Test
    @DisplayName("候选描述自包含且键合法（NanoJev choice 契约 2-255 项）")
    void candidateContractValid() {
        ActorNpcState st = state();
        st.setHunger(30);
        st.setEnergy(20);
        Map<String, String> c = NpcStateService.buildNanoJevCrowdCandidates(st, "干活", List.of());
        assertTrue(c.size() >= 2);
        assertTrue(c.size() <= 255);
        c.forEach((k, v) -> {
            assertFalse(k.isBlank());
            assertFalse(v.isBlank());
        });
    }
}
