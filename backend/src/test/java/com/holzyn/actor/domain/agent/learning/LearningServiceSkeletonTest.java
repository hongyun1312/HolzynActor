package com.holzyn.actor.domain.agent.learning;

import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.debug.service.AiCallTrace;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 学习组件骨架（C2：接口 + 空实现占位）单元测试（domain/agent/learning，同包）。
 * <p>职责：验证 C2 骨架五方法全部可调用且不抛异常（空实现 + trace 记录）；
 * 真实实现留阶段 D（D1 F6 动态关系 / D2 F7 反思）。
 * <b>P0-2 遗留根治①（2026-09-29）</b>：scheduleConsolidation 守卫修正——gameHour=null
 * （AgentRuntime 决策链占位调用）必须早退，不再触发全员反思尝试；周期调用（真实 gameHour）
 * 前后开/关反思独立预算窗口（根治②）。</p>
 */
class LearningServiceSkeletonTest {

    private LearningService service;

    @BeforeEach
    void setUp() {
        service = new LearningServiceSkeleton();
    }

    /** C2 骨架：反思/关系/偏好/生态/周期巩固均可调用（空实现不抛异常，D 阶段填充）。 */
    @Test
    void skeletonMethodsAreCallableWithoutException() {
        List<AiCallTrace> traces = new ArrayList<>();
        AiCallContext.setSink(traces::add);
        try {
            assertDoesNotThrow(() -> service.reflect("special:1", 100L));
            assertDoesNotThrow(() -> service.updateRelation(100L, "special:1", "special:2", 5, "共同行动"));
            assertDoesNotThrow(() -> service.updatePreference("special:1", "move", 1));
            assertDoesNotThrow(() -> service.snapshotEcosystem(100L, 100L));
            assertDoesNotThrow(() -> service.scheduleConsolidation(100L, 100L));
            // 骨架各方法经 AiCallContext.recordStep 记 trace（决策链路可见性，F19）
            assertEquals(5, traces.size(), "五个骨架调用各记一条 learning trace");
        } finally {
            AiCallContext.setSink(null);
        }
    }

    /**
     * P0-2 遗留根治①（2026-09-29）：决策链占位调用（gameHour=null）必须早退——
     * 不查角色仓库、不开预算窗口、不触发任何反思。这是「每轮决策链都触发全员反思尝试
     * → 反思预算被 tick 饿死」的直接回归守护（旧守卫查 projectId==null 挡不住占位调用）。
     */
    @Test
    void scheduleConsolidationSkipsWhenGameHourNull() {
        LearningServiceSkeleton skeleton = new LearningServiceSkeleton();
        ReflectionService reflection = Mockito.mock(ReflectionService.class);
        ActorCharacterRepository repo = Mockito.mock(ActorCharacterRepository.class);
        ReflectionTestUtils.setField(skeleton, "reflectionService", reflection);
        ReflectionTestUtils.setField(skeleton, "characterRepository", repo);

        skeleton.scheduleConsolidation(100L, null);

        verify(repo, never()).findByProjectIdAndDeletedOrderByIdAsc(anyLong(), anyInt());
        verify(reflection, never()).openCycleBudget(anyLong());
        verify(reflection, never()).reflect(anyLong(), anyString(), Mockito.isNull());
    }

    /**
     * P0-2 遗留根治①②（2026-09-29）：周期调用（真实 gameHour）前后必须开/关
     * 反思独立预算窗口（浮动预算由 ReflectionService 计算），循环内逐特殊 NPC 反思。
     */
    @Test
    void scheduleConsolidationWithGameHourOpensAndClosesCycleBudget() {
        LearningServiceSkeleton skeleton = new LearningServiceSkeleton();
        ReflectionService reflection = Mockito.mock(ReflectionService.class);
        ActorCharacterRepository repo = Mockito.mock(ActorCharacterRepository.class);
        ActorCharacter special = new ActorCharacter();
        special.setId(1L);
        special.setType("special");
        ActorCharacter common = new ActorCharacter();
        common.setId(2L);
        common.setType("common");
        when(repo.findByProjectIdAndDeletedOrderByIdAsc(100L, 0)).thenReturn(List.of(special, common));
        when(reflection.reflect(100L, "npc:1", 50L)).thenReturn(true);
        ReflectionTestUtils.setField(skeleton, "reflectionService", reflection);
        ReflectionTestUtils.setField(skeleton, "characterRepository", repo);

        skeleton.scheduleConsolidation(100L, 50L);

        verify(reflection).openCycleBudget(100L);
        verify(reflection).closeCycleBudget(100L);
        verify(reflection).reflect(100L, "npc:1", 50L);
        verify(reflection, never()).reflect(100L, "npc:2", 50L); // 普通型跳过
    }
}
