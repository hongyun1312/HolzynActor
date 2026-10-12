package com.holzyn.actor.domain.unity.service;

import com.holzyn.actor.domain.agent.runtime.RealTimeDecisionService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.agent.runtime.StimulusType;
import com.holzyn.actor.domain.unity.config.UnityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unity 感知刺激服务单元测试（U 轨道 U1，domain/unity/service）——UNITY_PERCEPTION 刺激源。
 * <p>职责：验证——目标导向触发（逐目标 onStimulus + UNITY_PERCEPTION 类型）、
 * 非特殊 NPC 过滤（普通型 common 忽略）、禁用/参数无效拒绝、广播级项目冷却（独立档防刷屏）、
 * 广播目标数上限截断。</p>
 */
class UnityPerceptionServiceTest {

    private UnityProperties props;
    private RealTimeDecisionService realTimeDecisionService;
    private ActorCharacterRepository characterRepository;
    private UnityPerceptionService service;

    @BeforeEach
    void setUp() {
        props = new UnityProperties();
        realTimeDecisionService = Mockito.mock(RealTimeDecisionService.class);
        service = new UnityPerceptionService(props, realTimeDecisionService);
        // G1a：npc:{id} 不再编码类型——非特殊型过滤改由 NpcTypeResolver 查 actor_profile.type
        //（@Autowired(required=false) 字段注入，单测手动回填）
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        ReflectionTestUtils.setField(service, "npcTypeResolver", new NpcTypeResolver(characterRepository));
    }

    @Test
    void disabled() {
        props.setEnabled(false);
        var r = service.onPerceptionReport(37L, List.of("npc:1"), "你好", 1000L, false);
        assertEquals("disabled", r.status());
        verify(realTimeDecisionService, never()).onStimulus(any(), any(), any(), any(), any());
    }

    @Test
    void invalidTargets() {
        var r = service.onPerceptionReport(37L, List.of(), "你好", 1000L, false);
        assertEquals("invalid", r.status());
        var r2 = service.onPerceptionReport(37L, null, "你好", 1000L, false);
        assertEquals("invalid", r2.status());
        verify(realTimeDecisionService, never()).onStimulus(any(), any(), any(), any(), any());
    }

    @Test
    void targetDrivenTriggersEachTarget() {
        // G1a：5 号 NPC 显式 type=common（同项目）→ 被过滤；110/111 查不到行 → 按特殊型放行
        ActorCharacter common = new ActorCharacter();
        common.setId(5L);
        common.setProjectId(37L);
        common.setType("common");
        Mockito.when(characterRepository.findById(5L)).thenReturn(java.util.Optional.of(common));
        var r = service.onPerceptionReport(37L,
                List.of("npc:110", "npc:111", "npc:5"), "玩家朝你走来", 1000L, false);
        assertTrue(r.wasTriggered());
        assertEquals(2, r.triggered(), "普通型（common）被过滤，只触发 2 个特殊 NPC");
        verify(realTimeDecisionService).onStimulus(eq(37L), eq("npc:110"),
                eq(StimulusType.UNITY_PERCEPTION), eq("玩家朝你走来"), eq(1000L));
        verify(realTimeDecisionService).onStimulus(eq(37L), eq("npc:111"),
                eq(StimulusType.UNITY_PERCEPTION), eq("玩家朝你走来"), eq(1000L));
        verify(realTimeDecisionService, never()).onStimulus(eq(37L), eq("npc:5"), any(), any(), any());
    }

    @Test
    void defaultStimulusTextWhenBlank() {
        service.onPerceptionReport(37L, List.of("npc:1"), "   ", 1000L, false);
        verify(realTimeDecisionService).onStimulus(eq(37L), eq("npc:1"),
                eq(StimulusType.UNITY_PERCEPTION), eq("你注意到有人朝你走来"), eq(1000L));
    }

    @Test
    void broadcastCooldownIndependent() {
        props.getPerception().setBroadcastCooldownGameMinutes(60); // 3600 游戏秒
        // 首次广播：触发
        var r1 = service.onPerceptionReport(37L, List.of("npc:1"), "有人大喊", 1000L, true);
        assertTrue(r1.wasTriggered());
        // 冷却期内（1000 → 2000 < 3600）：跳过
        var r2 = service.onPerceptionReport(37L, List.of("npc:1"), "又大喊", 2000L, true);
        assertEquals("skipped_broadcast", r2.status());
        verify(realTimeDecisionService, times(1)).onStimulus(any(), any(), any(), any(), any());
        // 冷却过后（5000 - 1000 > 3600）：再触发
        var r3 = service.onPerceptionReport(37L, List.of("npc:1"), "再喊", 5000L, true);
        assertTrue(r3.wasTriggered());
        verify(realTimeDecisionService, times(2)).onStimulus(any(), any(), any(), any(), any());
        // 目标导向不受广播冷却影响（不同档）
        var r4 = service.onPerceptionReport(37L, List.of("npc:1"), "单独找你", 2500L, false);
        assertTrue(r4.wasTriggered());
    }

    @Test
    void broadcastTargetCap() {
        props.getPerception().setBroadcastMaxTargets(2);
        var r = service.onPerceptionReport(37L,
                List.of("npc:1", "npc:2", "npc:3", "npc:4"), "全场注意", 1000L, true);
        assertTrue(r.wasTriggered());
        assertEquals(2, r.triggered(), "广播目标数截断到上限 2");
        verify(realTimeDecisionService, times(2)).onStimulus(any(), any(), any(), any(), any());
    }

    @Test
    void targetDrivenNoBroadcastCooldownEffect() {
        props.getPerception().setBroadcastCooldownGameMinutes(60);
        // 先触发一次广播（记录广播冷却）
        service.onPerceptionReport(37L, List.of("npc:1"), "广播", 1000L, true);
        // 目标导向立即再触发（不受广播档约束）
        var r = service.onPerceptionReport(37L, List.of("npc:2"), "单独互动", 1100L, false);
        assertTrue(r.wasTriggered());
        assertFalse("skipped_broadcast".equals(r.status()));
    }
}
