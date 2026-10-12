package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.domain.agent.runtime.entity.ActorForesightRecord;
import com.holzyn.actor.domain.agent.runtime.repository.ActorForesightRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 前瞻推演服务单元测试（O2 决策与协调 P1-1，domain/agent/runtime 同包）。
 * <p>职责：验证——触发判定规则（事件重要度/紧急度/场景关键词）、前瞻段组装（含宏观注入占位）、
 * 会话记录（open→recordIfOpen→close：成功/未输出候选/会话不匹配零成本跳过）。</p>
 * <p>设计依据：现状优化轨道 §7.2 P1-1。</p>
 */
class ForesightServiceTest {

    private ActorForesightRecordRepository repository;
    private ForesightService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(ActorForesightRecordRepository.class);
        service = new ForesightService(repository, new ObjectMapper(), new RealtimeDecisionProperties());
    }

    // ==================== 触发判定（确定性规则） ====================

    /** 事件重要度 ≥ 阈值（4）→ 触发。 */
    @Test
    void shouldTriggerOnImportance() {
        var props = new RealtimeDecisionProperties.Foresight();
        assertTrue(ForesightService.shouldTrigger(StimulusType.WORLD_EVENT, 5, false, "集市有人争执", props));
        assertTrue(ForesightService.shouldTrigger(StimulusType.WORLD_EVENT, 4, false, "普通消息", props));
        assertFalse(ForesightService.shouldTrigger(StimulusType.WORLD_EVENT, 3, false, "普通消息", props));
    }

    /** 紧急度 ≥ 2 → 触发。 */
    @Test
    void shouldTriggerOnUrgency() {
        var props = new RealtimeDecisionProperties.Foresight();
        assertTrue(ForesightService.shouldTrigger(StimulusType.STATE_URGENCY, null, true, "状态告急", props));
        assertFalse(ForesightService.shouldTrigger(StimulusType.STATE_URGENCY, null, false, "状态告急", props));
    }

    /** 场景关键词（资源/关系/位置）→ 触发。 */
    @Test
    void shouldTriggerOnScenarioKeywords() {
        var props = new RealtimeDecisionProperties.Foresight();
        // 资源类：摊位/买/争
        assertTrue(ForesightService.shouldTrigger(StimulusType.NPC_ACTION, null, false, "有人在争摊位", props));
        assertTrue(ForesightService.shouldTrigger(StimulusType.NPC_ACTION, null, false, "集市药材便宜，想买一些", props));
        // 关系类：信任/结盟
        assertTrue(ForesightService.shouldTrigger(StimulusType.NPC_ACTION, null, false, "他是否值得信任", props));
        // 位置类：离开/搬迁
        assertTrue(ForesightService.shouldTrigger(StimulusType.NPC_ACTION, null, false, "打算离开村子远行", props));
        // 无关文本 → 不触发
        assertFalse(ForesightService.shouldTrigger(StimulusType.NPC_ACTION, null, false, "今天的天气不错", props));
        assertFalse(ForesightService.shouldTrigger(StimulusType.NPC_ACTION, null, false, "", props));
    }

    /** 总开关关闭 → 不触发。 */
    @Test
    void shouldTriggerRespectsEnabled() {
        var props = new RealtimeDecisionProperties.Foresight();
        props.setEnabled(false);
        assertFalse(ForesightService.shouldTrigger(StimulusType.WORLD_EVENT, 5, true, "争摊位", props));
    }

    // ==================== 前瞻段组装 ====================

    /** 段包含候选要求 + 宏观注入占位（无宏观服务=省略宏观段）。 */
    @Test
    void buildSectionContainsCandidatesAndOptionalMacro() {
        String section = service.buildSection(100L, "special:3", 36000L);
        assertNotNull(section);
        assertTrue(section.contains("candidates"));
        assertTrue(section.contains("候选行动"));
        assertTrue(section.contains("action"));
        // 未装配 MacroStateService → 无【当下时局】段（零阻断）
        assertFalse(section.contains("【当下时局】"));
    }

    // ==================== 会话记录（open → recordIfOpen → close） ====================

    /** 成功路径：AI 输出候选 → success=true 落库。 */
    @Test
    void recordIfOpenRecordsWithCandidates() {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("action", "去集市摊位买药");
        decision.put("foresightCandidates", List.of(
                Map.of("action", "先到集市摊位买药", "consequence", "药到手能治病"),
                Map.of("action", "等一会再去", "consequence", "可能错过")));

        service.open(100L, "special:3", "沈砚秋", 36000L, "事件重要度=5",
                "有人在争摊位", "—— 前瞻推演（本轮要求） ——\n请先设想 3 个候选行动…");
        service.recordIfOpen(100L, "special:3", decision);

        ArgumentCaptor<ActorForesightRecord> captor = ArgumentCaptor.forClass(ActorForesightRecord.class);
        verify(repository).save(captor.capture());
        ActorForesightRecord rec = captor.getValue();
        assertEquals(100L, rec.getProjectId());
        assertEquals("special:3", rec.getNpcRef());
        assertEquals("沈砚秋", rec.getNpcName());
        assertEquals("事件重要度=5", rec.getTriggerReason());
        assertTrue(rec.getSuccess());
        assertTrue(rec.getCandidatesJson().contains("先到集市摊位买药"));
        assertEquals("去集市摊位买药", rec.getFinalAction());
        // 会话已消费：再次 recordIfOpen 不再落库
        service.recordIfOpen(100L, "special:3", decision);
        verify(repository, Mockito.times(1)).save(any());
    }

    /** AI 未输出候选 → success=false 记录（失败可观测）。 */
    @Test
    void recordIfOpenMarksFailureWithoutCandidates() {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("action", "去集市摊位买药");

        service.open(100L, "special:3", null, 36000L, "事件重要度=5", "有人在争摊位", "段");
        service.recordIfOpen(100L, "special:3", decision);

        ArgumentCaptor<ActorForesightRecord> captor = ArgumentCaptor.forClass(ActorForesightRecord.class);
        verify(repository).save(captor.capture());
        assertFalse(captor.getValue().getSuccess());
        assertEquals("[]", captor.getValue().getCandidatesJson());
    }

    /** 无会话（引擎 tick/手动路径）→ 零成本跳过不落库。 */
    @Test
    void recordIfOpenSkipsWithoutSession() {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("action", "日常活动");
        service.recordIfOpen(100L, "special:3", decision);
        verify(repository, never()).save(any());
    }

    /** 会话不匹配（另一角色）→ 不落库并保留会话由 close 清理。 */
    @Test
    void recordIfOpenSkipsOnRefMismatch() {
        service.open(100L, "special:3", null, 36000L, "r", "s", "段");
        service.recordIfOpen(100L, "special:9", Map.of("action", "x"));
        verify(repository, never()).save(any());
        service.close();
    }

    /** 列表查询委托仓库（limit 截断）。 */
    @Test
    void listDelegatesToRepository() {
        var rec = new ActorForesightRecord();
        rec.setId(1L);
        rec.setProjectId(100L);
        rec.setNpcRef("special:3");
        when(repository.findByProjectIdAndNpcRefOrderByIdDesc(100L, "special:3"))
                .thenReturn(List.of(rec));
        assertEquals(1, service.list(100L, "special:3", null).size());
        assertEquals(1, service.list(100L, "special:3", 10).size());
        // limit=1 且只有 1 条 → 不截断
        assertEquals(1, service.list(100L, "special:3", 1).size());
    }
}
