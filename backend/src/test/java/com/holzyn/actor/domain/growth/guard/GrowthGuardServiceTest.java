package com.holzyn.actor.domain.growth.guard;

import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.mask.MaskService;
import com.holzyn.actor.domain.growth.metrics.GrowthMetricsService;
import com.holzyn.actor.domain.growth.persona.PersonaDims;
import com.holzyn.actor.domain.growth.persona.PersonaJson;
import com.holzyn.actor.domain.growth.persona.PersonaProfile;
import com.holzyn.actor.domain.growth.space.GrowthSpaceService;
import com.holzyn.actor.domain.growth.stage.GrowthGateService;
import com.holzyn.actor.domain.growth.stage.GrowthStage;
import com.holzyn.actor.domain.growth.stage.HardGateResult;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G2 三条护栏单元测试（O6 轨道 G2）。
 * <p>职责：stage_compliance（INFANT 非 continue 拦截）、space_compliance（硬门控冲突拦截、
 * 硬约束开关下空间外拦截）、developmental_knowledge_bound（知识盲区拦截）、
 * continue 天然放行、开关关闭不拦截、对话阻断判定。</p>
 */
@ExtendWith(MockitoExtension.class)
class GrowthGuardServiceTest {

    @Mock
    private GrowthGateService growthGateService;
    @Mock
    private GrowthSpaceService growthSpaceService;
    @Mock
    private GrowthMetricsService growthMetrics;
    @Mock
    private MaskService maskService;

    private GrowthProperties properties;
    private GrowthGuardService service;

    @BeforeEach
    void setUp() {
        properties = new GrowthProperties();
        service = new GrowthGuardService(growthGateService, growthSpaceService, growthMetrics, properties,
                maskService);
    }

    private ActorCharacter ch(Long id, String stage) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setName("测试角色");
        c.setProjectId(1L);
        c.setStage(stage);
        c.setAge(5);
        return c;
    }

    private HardGateResult gate(String npcRef, List<HardGateResult.ForbiddenItem> forbidden) {
        return new HardGateResult(npcRef, "ELDER", 70, 0.5,
                List.of("WALK", "TALK"), forbidden, List.of(), java.util.Map.of());
    }

    @Test
    @DisplayName("stage_compliance：INFANT 非 continue 决策拦截")
    void infantNonContinueBlocked() {
        ActorCharacter ch = ch(1L, GrowthStage.INFANT.name());
        var r = service.validateDecision(1L, ch, "start_action", "在田里耕作", 1000L);
        assertTrue(r.blocked());
        assertTrue(r.reason().contains("stage_compliance"));
        verify(growthMetrics).recordStageComplianceIntercept();
    }

    @Test
    @DisplayName("continue 天然合规（不拦截、不计指标）")
    void continueAlwaysPasses() {
        ActorCharacter ch = ch(2L, GrowthStage.INFANT.name());
        var r = service.validateDecision(1L, ch, "continue", null, 1000L);
        assertFalse(r.blocked());
        verify(growthMetrics, never()).recordStageComplianceIntercept();
    }

    @Test
    @DisplayName("space_compliance：行动与硬门控禁止项冲突 → 拦截")
    void forbiddenConflictBlocked() {
        ActorCharacter ch = ch(3L, GrowthStage.ELDER.name());
        when(growthGateService.gate(1L, "npc:3")).thenReturn(
                gate("npc:3", List.of(new HardGateResult.ForbiddenItem("重体力劳作、挑担垦荒", "老年期"))));
        var r = service.validateDecision(1L, ch, "start_action", "去挑担垦荒", 1000L);
        assertTrue(r.blocked());
        assertTrue(r.reason().contains("space_compliance"));
        verify(growthMetrics).recordSpaceComplianceIntercept();
    }

    @Test
    @DisplayName("space_compliance：与禁止项无关的行动放行（软引导语义）")
    void unrelatedActionPasses() {
        ActorCharacter ch = ch(4L, GrowthStage.ELDER.name());
        when(growthGateService.gate(1L, "npc:4")).thenReturn(
                gate("npc:4", List.of(new HardGateResult.ForbiddenItem("重体力劳作、挑担垦荒", "老年期"))));
        var r = service.validateDecision(1L, ch, "start_action", "去村口晒太阳", 1000L);
        assertFalse(r.blocked());
    }

    @Test
    @DisplayName("space_compliance 硬约束开关：开启时空间外行动拦截，关闭（默认）放行")
    void hardConstraintToggle() {
        ActorCharacter ch = ch(5L, GrowthStage.PRIME.name());
        when(growthGateService.gate(1L, "npc:5")).thenReturn(
                new HardGateResult("npc:5", "PRIME", 35, 1.0,
                        List.of("WALK", "TALK"), List.of(), List.of(), java.util.Map.of()));
        // 默认关：空间外行动放行（反常行为真实）
        var soft = service.validateDecision(1L, ch, "start_action", "去悬崖边摘花", 1000L);
        assertFalse(soft.blocked());
        // 硬约束开：空间外拦截
        properties.getSpace().setHardConstraint(true);
        when(growthSpaceService.isActionInSpace(1L, "npc:5", "去悬崖边摘花")).thenReturn(false);
        var hard = service.validateDecision(1L, ch, "start_action", "去悬崖边摘花", 1000L);
        assertTrue(hard.blocked());
        assertTrue(hard.reason().contains("硬约束"));
        // 空间内放行
        when(growthSpaceService.isActionInSpace(1L, "npc:5", "在村里走走")).thenReturn(true);
        var inside = service.validateDecision(1L, ch, "start_action", "在村里走走", 1000L);
        assertFalse(inside.blocked());
    }

    @Test
    @DisplayName("developmental_knowledge_bound：行动命中知识盲区 → 拦截")
    void knowledgeBlindSpotBlocked() {
        ActorCharacter ch = ch(6L, GrowthStage.YOUTH.name());
        PersonaProfile persona = PersonaProfile.empty(1L, 6L);
        PersonaDims.Ability ability = new PersonaDims.Ability();
        ability.setKnowledgeBlindSpots(List.of("炼丹术、符箓、机关术"));
        persona.getDims().setAbility(ability);
        ch.setPersonaJson(PersonaJson.mapper().writeValueAsString(persona));
        when(growthGateService.gate(1L, "npc:6")).thenReturn(
                new HardGateResult("npc:6", "YOUTH", 25, 1.0,
                        List.of("WALK", "TALK"), List.of(), List.of(), java.util.Map.of()));
        // 行动含盲区词「炼丹术」（分段包含匹配；「炼制丹药」不含「炼丹术」子串，不命中）
        var r = service.validateDecision(1L, ch, "start_action", "钻研炼丹术", 1000L);
        assertTrue(r.blocked());
        assertTrue(r.reason().contains("developmental_knowledge_bound"));
        verify(growthMetrics).recordKnowledgeBoundIntercept();
    }

    @Test
    @DisplayName("盲区分段工具：顿号切段、单字段丢弃")
    void segmentsOfHelper() {
        assertEquals(List.of("炼丹术", "符箓", "机关术"), GrowthGuardService.segmentsOf("炼丹术、符箓、机关术"));
        assertTrue(GrowthGuardService.segmentsOf("a、bc").contains("bc"));
        assertFalse(GrowthGuardService.segmentsOf("a、b").contains("a"));
        assertTrue(GrowthGuardService.segmentsOf(null).isEmpty());
    }

    @Test
    @DisplayName("开关全关：三条护栏全部放行")
    void allGuardsDisabled() {
        properties.getGuardrails().setStageComplianceEnabled(false);
        properties.getGuardrails().setSpaceComplianceEnabled(false);
        properties.getGuardrails().setKnowledgeBoundEnabled(false);
        ActorCharacter ch = ch(7L, GrowthStage.INFANT.name());
        var r = service.validateDecision(1L, ch, "start_action", "去挑担垦荒", 1000L);
        assertFalse(r.blocked());
    }

    @Test
    @DisplayName("对话阻断：INFANT 无语言输出能力 → 返回阻断原因；PRIME 放行")
    void dialogueBlockReason() {
        ActorCharacter infant = ch(8L, GrowthStage.INFANT.name());
        when(growthGateService.gate(1L, "npc:8")).thenReturn(
                new HardGateResult("npc:8", "INFANT", 1, 0.2,
                        List.of("CRY"), List.of(), List.of(), java.util.Map.of()));
        String reason = service.dialogueBlockReason(1L, infant);
        assertTrue(reason != null && reason.contains("无语言输出能力"));

        ActorCharacter adult = ch(9L, GrowthStage.PRIME.name());
        when(growthGateService.gate(1L, "npc:9")).thenReturn(
                new HardGateResult("npc:9", "PRIME", 35, 1.0,
                        List.of("DIALOGUE_OUTPUT"), List.of(), List.of(), java.util.Map.of()));
        assertNull(service.dialogueBlockReason(1L, adult));
    }

    @Test
    @DisplayName("mask_compliance（O6 G3）：决策行动命中当前情境禁止表达 → 拦截；独处/私人场所不设防 → 放行")
    void maskComplianceDecision() {
        ActorCharacter ch = ch(10L, GrowthStage.PRIME.name());
        when(growthGateService.gate(1L, "npc:10")).thenReturn(
                new HardGateResult("npc:10", "PRIME", 35, 1.0,
                        List.of("WALK", "TALK"), List.of(), List.of(), java.util.Map.of()));
        java.util.List<String> authority = List.of(com.holzyn.actor.domain.growth.mask.MaskContextCode.BEFORE_AUTHORITY);
        when(maskService.resolveContexts(1L, "npc:10", null, 1000L)).thenReturn(authority);
        when(maskService.isUndefended(authority)).thenReturn(false);
        when(maskService.forbiddenOf(1L, "npc:10", authority)).thenReturn(List.of("顶撞上官、当面反驳"));
        var r = service.validateDecision(1L, ch, "start_action", "当面顶撞上官", 1000L);
        assertTrue(r.blocked());
        assertTrue(r.reason().contains("mask_compliance"));
        verify(growthMetrics).recordMaskComplianceIntercept();

        // 独处（私人场所）：不设防 → 放行（人在独处时不需要面具）
        java.util.List<String> privatePlace = List.of(com.holzyn.actor.domain.growth.mask.MaskContextCode.PRIVATE_PLACE);
        when(maskService.resolveContexts(1L, "npc:10", null, 1001L)).thenReturn(privatePlace);
        when(maskService.isUndefended(privatePlace)).thenReturn(true);
        var alone = service.validateDecision(1L, ch, "start_action", "当面顶撞上官", 1001L);
        assertFalse(alone.blocked());
    }

    @Test
    @DisplayName("mask_compliance 对话软判定：回复命中禁止表达 → 返回违规说明；私人场所/无禁止 → 放行")
    void maskComplianceDialogue() {
        ActorCharacter ch = ch(11L, GrowthStage.PRIME.name());
        java.util.List<String> authority = List.of(com.holzyn.actor.domain.growth.mask.MaskContextCode.BEFORE_AUTHORITY);
        when(maskService.resolveContexts(1L, "npc:11", java.util.List.of(), 2000L)).thenReturn(authority);
        when(maskService.isUndefended(authority)).thenReturn(false);
        when(maskService.forbiddenOf(1L, "npc:11", authority)).thenReturn(List.of("咒骂上官、狗官"));
        String violation = service.dialogueViolationReason(1L, ch, java.util.List.of(), 2000L, "你这狗官！");
        assertTrue(violation != null && violation.contains("mask_compliance"));

        // 放行文本
        assertNull(service.dialogueViolationReason(1L, ch, java.util.List.of(), 2001L, "遵命，大人。"));
    }
}
