package com.holzyn.actor.domain.growth.space;

import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.growth.attention.AttentionLevelProvider;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.lod.BrainTierService;
import com.holzyn.actor.domain.growth.metrics.GrowthMetricsService;
import com.holzyn.actor.domain.growth.persona.PersonaService;
import com.holzyn.actor.domain.growth.stage.GrowthGateService;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.repository.ActorNpcStateRepository;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * N6 空间生成记忆条数配额单元测试（2026-09-27，domain/growth/space 同包）。
 *
 * <p>职责：验证——{@code renderMemories} 的检索条数按注意力档位配额
 * （focus 3 / ambient 2 / dormant 1；配置见 {@code space-memory-top-k-by-tier}），
 * 无注意力信号/开关关回退既有默认 3（零影响回滚）。</p>
 *
 * <p>测试纪律：构造器不变（{@code ReflectionTestUtils.setField} 注入注意力提供器）；私有同步体
 * 反射调用；注意力档位提供器全 mock。</p>
 */
class GrowthSpaceMemoryWeightingTest {

    private AgentMemoryService agentMemoryService;
    private AttentionLevelProvider attentionProvider;
    private GrowthSpaceService service;
    private GrowthProperties properties;

    @BeforeEach
    void setUp() {
        agentMemoryService = mock(AgentMemoryService.class);
        attentionProvider = mock(AttentionLevelProvider.class);
        properties = new GrowthProperties();
        service = new GrowthSpaceService(
                mock(GrowthSpaceRepository.class), mock(GrowthSpaceLogRepository.class),
                mock(ActorCharacterRepository.class), mock(ActorNpcStateRepository.class),
                properties, mock(GrowthGateService.class), mock(com.holzyn.actor.domain.growth.lod.BrainTierService.class),
                new GrowthMetricsService(), mock(PersonaService.class), mock(NpcStateService.class),
                agentMemoryService, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "attentionLevelProvider", attentionProvider);
        ActorMemory m = new ActorMemory();
        m.setId(1L);
        m.setContent("去年春旱，村里减了三成收成");
        when(agentMemoryService.retrieve(anyLong(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(m));
    }

    /** 反射调用私有 renderMemories（生产为空间生成 Prompt 组装内部件）。 */
    private String renderMemories(Long projectId, String npcRef) throws Exception {
        Method m = GrowthSpaceService.class.getDeclaredMethod("renderMemories", Long.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(service, projectId, npcRef);
    }

    @Test
    @DisplayName("N6 空间记忆配额：focus → retrieve top-3、ambient → top-2、dormant → top-1")
    void topKByAttentionTier() throws Exception {
        when(attentionProvider.tierOf(1L, "npc:1")).thenReturn(BrainTierService.TIER_FOCUS);
        assertNotNull(renderMemories(1L, "npc:1"));
        verify(agentMemoryService).retrieve(eq(1L), eq("npc:1"), eq(null), eq(3));

        when(attentionProvider.tierOf(1L, "npc:1")).thenReturn(BrainTierService.TIER_AMBIENT);
        renderMemories(1L, "npc:1");
        verify(agentMemoryService).retrieve(eq(1L), eq("npc:1"), eq(null), eq(2));

        when(attentionProvider.tierOf(1L, "npc:1")).thenReturn(BrainTierService.TIER_DORMANT);
        renderMemories(1L, "npc:1");
        verify(agentMemoryService).retrieve(eq(1L), eq("npc:1"), eq(null), eq(1));
    }

    @Test
    @DisplayName("N6 空间记忆配额回退：无提供器 / 开关关 → 既有默认 top-3（零影响）")
    void topKFallbackWithoutSignals() throws Exception {
        ReflectionTestUtils.setField(service, "attentionLevelProvider", null);
        renderMemories(1L, "npc:1");
        verify(agentMemoryService, atLeastOnce()).retrieve(eq(1L), eq("npc:1"), eq(null), eq(3));

        ReflectionTestUtils.setField(service, "attentionLevelProvider", attentionProvider);
        properties.getAttention().setMemoryWeightEnabled(false);
        when(attentionProvider.tierOf(1L, "npc:1")).thenReturn(BrainTierService.TIER_DORMANT);
        renderMemories(1L, "npc:1");
        verify(agentMemoryService, atLeastOnce()).retrieve(eq(1L), eq("npc:1"), eq(null), eq(3));
    }
}
