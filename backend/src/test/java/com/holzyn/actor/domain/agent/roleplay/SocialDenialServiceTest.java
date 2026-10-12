package com.holzyn.actor.domain.agent.roleplay;

import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.agent.learning.RelationDynamicsService;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.sandbox.service.WorldSandboxSseHub;
import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * N3 判定类落地单测：社交拒绝连锁（SocialDenialService）。
 *
 * <p>覆盖：拒绝后记忆 L1 写入 + 对 npc 搭话者好感微降 + 情绪异步评估 + 沙盘想法；
 * 搭话者为玩家（partnerRef null）→ 跳过好感；依赖缺失零副作用。</p>
 *
 * <p>所属模块：domain/agent/roleplay（N 轨道 N3 社交意愿门控）</p>
 */
class SocialDenialServiceTest {

    private SocialDenialService service;
    private AgentMemoryService agentMemoryService;
    private RelationDynamicsService relationDynamicsService;
    private NpcStateService npcStateService;
    private WorldSandboxSseHub sandboxSseHub;
    private WorldClockService worldClockService;

    @BeforeEach
    void setUp() {
        service = new SocialDenialService();
        agentMemoryService = mock(AgentMemoryService.class);
        relationDynamicsService = mock(RelationDynamicsService.class);
        npcStateService = mock(NpcStateService.class);
        sandboxSseHub = mock(WorldSandboxSseHub.class);
        worldClockService = mock(WorldClockService.class);
        ReflectionTestUtils.setField(service, "agentMemoryService", agentMemoryService);
        ReflectionTestUtils.setField(service, "relationDynamicsService", relationDynamicsService);
        ReflectionTestUtils.setField(service, "npcStateService", npcStateService);
        ReflectionTestUtils.setField(service, "sandboxSseHub", sandboxSseHub);
        ReflectionTestUtils.setField(service, "worldClockService", worldClockService);
    }

    @Test
    @DisplayName("拒绝连锁全链路：记忆 + 好感-2 + 情绪 + 沙盘想法")
    void onDenialFullChain() {
        service.onDenial(1L, "npc:10", "special", "npc:11", "柴大山", "（正忙，无暇应答。）", 1000L);
        // ① 记忆 L1
        verify(agentMemoryService).recordObservation(eq(1L), eq("npc:10"), anyString(), eq(3));
        // ② 好感 -2（npc:npc 方向）
        verify(relationDynamicsService).updateRelation(eq(1L), eq("npc:10"), eq("npc:11"),
                eq(SocialDenialService.AFFINITY_DROP), anyString());
        // ③ 情绪异步评估
        verify(npcStateService).evaluateEmotion(eq(1L), eq("npc:10"), eq("special"), eq("社交拒绝"),
                anyString(), eq(1000L));
        // ④ 沙盘想法
        verify(sandboxSseHub).publishNpcThought(eq(1L), eq("npc:10"), anyString(), eq("social_denial"), eq(1000L));
    }

    @Test
    @DisplayName("搭话者为玩家（partnerRef null）→ 跳过好感调整，其余连锁照常")
    void onDenialPlayerSkippedAffinity() {
        service.onDenial(1L, "npc:10", "special", null, "玩家", "（正忙，无暇应答。）", 1000L);
        verify(relationDynamicsService, never()).updateRelation(anyLong(), anyString(), anyString(), anyInt(), anyString());
        verify(agentMemoryService).recordObservation(eq(1L), eq("npc:10"), anyString(), eq(3));
        verify(npcStateService).evaluateEmotion(eq(1L), eq("npc:10"), eq("special"), eq("社交拒绝"), anyString(), eq(1000L));
    }

    @Test
    @DisplayName("依赖缺失（单测未装配）→ 零副作用不抛异常")
    void onDenialNoDeps() {
        SocialDenialService bare = new SocialDenialService();
        // 不注入任何依赖：不抛异常即通过
        bare.onDenial(1L, "npc:10", "special", "npc:11", "柴大山", "（正忙。）", 1000L);
        bare.onDenial(null, null, null, null, null, null, null);
    }
}
