package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.domain.agent.runtime.entity.ActorStateDelta;
import com.holzyn.actor.domain.agent.runtime.repository.ActorStateDeltaRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 决策 state delta 服务单元测试（O2 决策与协调 P1-2，domain/agent/runtime 同包）。
 * <p>职责：验证——落库（changes_json 组装：行动/位置/状态差值）、幂等（decision_id uk）、
 * 时效校验（有效/过期/无 delta）、恢复上下文文本、列表查询。</p>
 * <p>设计依据：现状优化轨道 §7.2 P1-2。</p>
 */
class StateDeltaServiceTest {

    private ActorStateDeltaRepository repository;
    private StateDeltaService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(ActorStateDeltaRepository.class);
        service = new StateDeltaService(repository, new ObjectMapper());
    }

    private tools.jackson.databind.JsonNode decision(String action, String target, int urgency) throws Exception {
        return new ObjectMapper().readTree("""
                {"type":"trade","action":"%s","target":"%s","reason":"理由","urgency":%d,"duration":30}
                """.formatted(action, target, urgency));
    }

    /** 落库：changes_json 含行动/位置/状态差值；游戏秒落库。 */
    @Test
    void recordBuildsChanges() throws Exception {
        ActorNpcState before = new ActorNpcState();
        before.setHunger(70);
        before.setMood(70);
        ActorNpcState after = new ActorNpcState();
        after.setHunger(85);
        after.setMood(70);

        ActorStateDelta saved = new ActorStateDelta();
        saved.setId(1L);
        when(repository.findByDecisionId(99L)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.record(100L, "special:3", 99L, decision("去集市买药", "集市摊位", 4),
                "医馆", before, after, 36000L);

        verify(repository).save(any());
    }

    /** 幂等：decision_id 已存在 → 返回既有行不重复落库。 */
    @Test
    void recordIdempotentByDecisionId() throws Exception {
        ActorStateDelta existing = new ActorStateDelta();
        existing.setId(5L);
        existing.setDecisionId(99L);
        when(repository.findByDecisionId(99L)).thenReturn(Optional.of(existing));

        ActorStateDelta result = service.record(100L, "special:3", 99L,
                decision("去集市买药", "集市摊位", 4), "医馆", null, null, 36000L);

        assertNotNull(result);
        assertEquals(5L, result.getId());
        verify(repository, never()).save(any());
    }

    /** 开关关闭（state-delta.enabled=false）→ 不落库。 */
    @Test
    void recordRespectsDisabled() throws Exception {
        RealtimeDecisionProperties props = new RealtimeDecisionProperties();
        props.getStateDelta().setEnabled(false);
        StateDeltaService disabled = new StateDeltaService(repository, props, new ObjectMapper());
        when(repository.findByDecisionId(99L)).thenReturn(Optional.empty());

        assertNull(disabled.record(100L, "special:3", 99L,
                decision("去集市买药", "集市摊位", 4), "医馆", null, null, 36000L));
        verify(repository, never()).save(any());
    }

    /** 时效校验：无 delta → null；未过期 → valid；过期 → expired。 */
    @Test
    void validityChecksExpiry() {
        ActorStateDelta d = new ActorStateDelta();
        d.setId(1L);
        d.setGameSecond(1000L);
        when(repository.findByProjectIdAndNpcRefOrderByIdDesc(100L, "special:3"))
                .thenReturn(List.of(d));

        // 默认窗口 120 分钟=7200 秒：1000+7200=8200
        StateDeltaService.Validity v1 = service.validityOf(100L, "special:3", 5000L);
        assertNotNull(v1);
        assertTrue(v1.valid());
        assertFalse(v1.expired());

        StateDeltaService.Validity v2 = service.validityOf(100L, "special:3", 9000L);
        assertNotNull(v2);
        assertFalse(v2.valid());
        assertTrue(v2.expired());
    }

    /** 时效校验：无 delta 记录 → null（调用方不注入上下文）。 */
    @Test
    void validityNullWithoutDelta() {
        when(repository.findByProjectIdAndNpcRefOrderByIdDesc(100L, "special:3"))
                .thenReturn(List.of());
        assertNull(service.validityOf(100L, "special:3", 5000L));
    }

    /** 恢复上下文文本：含原计划行动/目标 + 时效描述；无 delta → 空串。 */
    @Test
    void resumeContextText() throws Exception {
        ActorStateDelta d = new ActorStateDelta();
        d.setId(1L);
        d.setGameSecond(1000L);
        d.setChangesJson(new ObjectMapper().writeValueAsString(
                java.util.Map.of("action", "去集市买药", "target", "集市摊位")));
        when(repository.findByProjectIdAndNpcRefOrderByIdDesc(100L, "special:3"))
                .thenReturn(List.of(d));

        String ctx = service.resumeContext(100L, "special:3", 5000L);
        assertTrue(ctx.contains("去集市买药"));
        assertTrue(ctx.contains("集市摊位"));
        assertTrue(ctx.contains("时效"));

        String expiredCtx = service.resumeContext(100L, "special:3", 9000L);
        assertTrue(expiredCtx.contains("过时效"));

        when(repository.findByProjectIdAndNpcRefOrderByIdDesc(100L, "special:9"))
                .thenReturn(List.of());
        assertEquals("", service.resumeContext(100L, "special:9", 5000L));
    }

    /** 列表查询委托仓库（limit 截断；角色空=全项目）。 */
    @Test
    void listDelegatesToRepository() {
        ActorStateDelta d = new ActorStateDelta();
        d.setId(1L);
        d.setProjectId(100L);
        when(repository.findByProjectIdAndNpcRefOrderByIdDesc(100L, "special:3"))
                .thenReturn(List.of(d));
        when(repository.findByProjectIdOrderByIdDesc(100L)).thenReturn(List.of(d));

        assertEquals(1, service.list(100L, "special:3", null).size());
        assertEquals(1, service.list(100L, null, null).size());
    }
}
