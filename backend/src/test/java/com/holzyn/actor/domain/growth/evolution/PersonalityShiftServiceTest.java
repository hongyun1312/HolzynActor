package com.holzyn.actor.domain.growth.evolution;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.nanojev.decision.NanoJevDecisionGate;
import com.holzyn.actor.ai.nanojev.decision.NanoJevDecisionScenes;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.context.AiContextInjector;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.mask.MaskService;
import com.holzyn.actor.domain.growth.metrics.GrowthMetricsService;
import com.holzyn.actor.domain.growth.persona.PersonaDims;
import com.holzyn.actor.domain.growth.persona.PersonaJson;
import com.holzyn.actor.domain.growth.persona.PersonaProfile;
import com.holzyn.actor.domain.growth.persona.PersonaRevisionService;
import com.holzyn.actor.domain.growth.stage.GrowthStage;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.sandbox.service.WorldSandboxSseHub;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 人格演化引擎单元测试（O6 轨道 G3 验收①/②/⑥）。
 *
 * <p>验收口径：</p>
 * <ul>
 *   <li><b>① 截断</b>：单次偏移按阶段上限硬截断（INFANT=0 → 青少年 0.20 → 成年 0.10），
 *       valueWeights ±0.05，生命周期累计 |ΣΔ| ≤ 0.30（按阶段递减）；</li>
 *   <li><b>② 证据链</b>：每次偏移落 actor_growth_shift_log，evidence_json 含触发本次评估的
 *       证据摘要（totalScore + 条目），ai_reason 为 AI 判定理由，可反查；</li>
 *   <li><b>⑥ 下行</b>：单条 |Δ| &gt; minorShiftAlert（默认 0.08）→ SSE npc:personality_shift
 *       下行；小幅偏移不广播。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PersonalityShiftServiceTest {

    @Mock
    private ActorCharacterRepository characterRepository;
    @Mock
    private GrowthShiftLogRepository shiftLogRepository;
    @Mock
    private ShiftEvidenceCollector evidenceCollector;
    @Mock
    private GrowthMetricsService growthMetrics;
    @Mock
    private MaskService maskService;
    @Mock
    private PromptTemplateService promptTemplateService;
    @Mock
    private AiSpringChatGateway aiChatGateway;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private UsageLogService usageLogService;
    @Mock
    private WorldClockService worldClockService;
    @Mock
    private PersonaRevisionService personaRevisionService;
    @Mock
    private WorldSandboxSseHub sandboxSseHub;

    private GrowthProperties properties;
    private PersonalityShiftService service;

    @BeforeEach
    void setUp() {
        properties = new GrowthProperties();
        service = new PersonalityShiftService(characterRepository, shiftLogRepository, evidenceCollector,
                properties, growthMetrics, maskService, promptTemplateService, aiChatGateway,
                currentUserProvider, usageLogService, worldClockService, personaRevisionService, sandboxSseHub);
    }

    private ActorCharacter ch(Long id, String stage) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setName("测试角色");
        c.setProjectId(1L);
        c.setStage(stage);
        c.setAge(30);
        return c;
    }

    /** 带 ④ OCEAN + ⑤ valueWeights 的档案（openness 0.50 / 宗族 0.60）。 */
    private ActorCharacter chWithPersona(Long id, String stage) {
        ActorCharacter c = ch(id, stage);
        PersonaProfile p = PersonaProfile.empty(1L, id);
        PersonaDims.Ocean ocean = new PersonaDims.Ocean();
        ocean.setOpenness(0.50);
        ocean.setConscientiousness(0.50);
        ocean.setExtraversion(0.50);
        ocean.setAgreeableness(0.50);
        ocean.setNeuroticism(0.50);
        p.getDims().setPersonality(new PersonaDims.Personality());
        p.getDims().getPersonality().setOcean(ocean);
        p.getDims().setValues(new PersonaDims.Values());
        p.getDims().getValues().getValueWeights().put("宗族", 0.60);
        c.setPersonaJson(PersonaJson.write(p));
        return c;
    }

    private ShiftEvidenceCollector.CollectResult sufficientEvidence() {
        return new ShiftEvidenceCollector.CollectResult(20.0,
                List.of(new ShiftEvidenceCollector.Evidence("l2:9", "event", "当众受辱后强作镇定", 4, 8.0)));
    }

    // ==================== 验收①：程序化截断 ====================

    @Test
    @DisplayName("验收①：INFANT 单次偏移上限 0（婴儿人格不变）")
    void truncateInfantZero() {
        ActorCharacter infant = ch(1L, GrowthStage.INFANT.name());
        assertEquals(0.0, service.truncate(1L, infant, "ocean", "openness", 0.3), 0.0001);
        assertEquals(0.0, service.truncate(1L, infant, "ocean", "openness", -0.3), 0.0001);
    }

    @Test
    @DisplayName("验收①：成年 OCEAN 单次上限 min(阶段 0.10, 配置 0.10)=0.10")
    void truncateAdultOceanCap() {
        ActorCharacter adult = chWithPersona(2L, GrowthStage.PRIME.name());
        assertEquals(0.10, service.truncate(1L, adult, "ocean", "openness", 0.5), 0.0001);
        assertEquals(-0.10, service.truncate(1L, adult, "ocean", "openness", -0.5), 0.0001);
    }

    @Test
    @DisplayName("验收①：valueWeights 单次 ±0.05（价值观更刚性，每次只微调）")
    void truncateValuesCap() {
        ActorCharacter adult = chWithPersona(3L, GrowthStage.PRIME.name());
        assertEquals(0.05, service.truncate(1L, adult, "values", "宗族", 0.3), 0.0001);
        assertEquals(-0.05, service.truncate(1L, adult, "values", "宗族", -0.3), 0.0001);
    }

    @Test
    @DisplayName("验收①：生命周期累计 |ΣΔ| ≤ 阶段累计上限（成年 0.30；已累计 0.25 → 本次最多 0.05）")
    void truncateCumulativeCap() {
        ActorCharacter adult = chWithPersona(4L, GrowthStage.PRIME.name());
        GrowthShiftLog l1 = new GrowthShiftLog();
        l1.setDim("ocean");
        l1.setField("openness");
        l1.setDelta(0.15);
        GrowthShiftLog l2 = new GrowthShiftLog();
        l2.setDim("ocean");
        l2.setField("openness");
        l2.setDelta(0.10);
        when(shiftLogRepository.findTop200ByProjectIdAndNpcRefOrderByIdDesc(1L, "npc:4"))
                .thenReturn(List.of(l2, l1));
        // 已累计 0.25，剩余 0.05
        assertEquals(0.05, service.truncate(1L, adult, "ocean", "openness", 0.5), 0.0001);
        // 已达上限（累计 0.30）→ 0
        GrowthShiftLog l3 = new GrowthShiftLog();
        l3.setDim("ocean");
        l3.setField("openness");
        l3.setDelta(0.05);
        when(shiftLogRepository.findTop200ByProjectIdAndNpcRefOrderByIdDesc(1L, "npc:4"))
                .thenReturn(List.of(l3, l2, l1));
        assertEquals(0.0, service.truncate(1L, adult, "ocean", "openness", 0.5), 0.0001);
    }

    @Test
    @DisplayName("验收①：不同字段的累计互不干扰（extraversion 未累计 → 满额 0.10）")
    void truncateCumulativePerField() {
        ActorCharacter adult = chWithPersona(5L, GrowthStage.PRIME.name());
        GrowthShiftLog l1 = new GrowthShiftLog();
        l1.setDim("ocean");
        l1.setField("openness");
        l1.setDelta(0.25);
        when(shiftLogRepository.findTop200ByProjectIdAndNpcRefOrderByIdDesc(1L, "npc:5"))
                .thenReturn(List.of(l1));
        assertEquals(0.10, service.truncate(1L, adult, "ocean", "extraversion", 0.5), 0.0001);
    }

    // ==================== 验收② + ⑥：全流程（证据 → AI → 截断 → 落账 → 下行） ====================

    @Test
    @DisplayName("验收②⑥：AI 判定偏移 0.20 → 截断 0.10 → 写回档案（版本+1）+ shift_log 证据链 + |Δ|>0.08 下行")
    void assessAppliesShiftAndPublishes() {
        ActorCharacter adult = chWithPersona(6L, GrowthStage.PRIME.name());
        when(characterRepository.findById(6L)).thenReturn(java.util.Optional.of(adult));
        when(shiftLogRepository.lastAssessedGameSecond(1L, "npc:6")).thenReturn(null);
        when(evidenceCollector.collect(eq(1L), eq("npc:6"), any(), eq(1_000_000L), eq(6.0), eq(20)))
                .thenReturn(sufficientEvidence());
        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(promptTemplateService.render(eq(1L), eq(1L), eq(PersonalityShiftService.SCENE_SHIFT_EVAL), anyMap()))
                .thenReturn("评估提示");
        when(promptTemplateService.systemMessage(eq(1L), eq(1L), eq(PersonalityShiftService.SCENE_SHIFT_EVAL), any()))
                .thenReturn("");
        when(aiChatGateway.chatCompletion(eq(1L), eq(1L), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult(
                        "{\"shifts\":[{\"dim\":\"ocean\",\"field\":\"openness\",\"delta\":0.2,"
                                + "\"why\":\"长期隐忍后学会掩饰情绪\"}],\"summary\":\"更内敛\"}",
                        "test-model", 10, 5, 15, 67L));

        Map<String, Object> result = service.assess(1L, "npc:6", GrowthShiftLog.TRIGGER_MANUAL, 1_000_000L);

        assertNotNull(result);
        List<?> shifts = (List<?>) result.get("shifts");
        assertEquals(1, shifts.size());
        Map<?, ?> first = (Map<?, ?>) shifts.get(0);
        assertEquals("openness", first.get("field"));
        assertEquals(0.10, ((Number) first.get("delta")).doubleValue(), 0.0001); // 截断后
        // 写回档案：openness 0.50 → 0.60，版本 +1（实体默认 1 → 2）
        verify(characterRepository).save(adult);
        assertEquals(2, adult.getPersonaVersion());
        PersonaProfile after = PersonaJson.readQuietly(adult.getPersonaJson());
        assertEquals(0.60, after.getDims().getPersonality().getOcean().getOpenness(), 0.0001);
        // 修订快照留痕
        verify(personaRevisionService).snapshot(eq(1L), eq(6L), eq(2), anyString(), eq("evolution"),
                any(), eq(PersonalityShiftService.SCENE_SHIFT_EVAL), anyString());
        // 验收②：shift_log 证据链（evidence_json 含 totalScore 与证据 id，ai_reason=AI 理由）
        ArgumentCaptor<GrowthShiftLog> captor = ArgumentCaptor.forClass(GrowthShiftLog.class);
        verify(shiftLogRepository).save(captor.capture());
        GrowthShiftLog log = captor.getValue();
        assertEquals("ocean", log.getDim());
        assertEquals("openness", log.getField());
        assertEquals(0.10, log.getDelta(), 0.0001);
        assertEquals("0.5000", log.getOldValue());
        assertEquals("0.6000", log.getNewValue());
        assertEquals("长期隐忍后学会掩饰情绪", log.getAiReason());
        assertEquals(GrowthShiftLog.SOURCE_AI, log.getSource());
        assertNotNull(log.getEvidenceJson());
        assertTrue(log.getEvidenceJson().contains("totalScore"));
        assertTrue(log.getEvidenceJson().contains("l2:9"));
        // 验收⑥：|Δ|=0.10 > 0.08 → 下行
        verify(sandboxSseHub).publish(eq(1L), eq(WorldSandboxSseHub.EVENT_NPC_PERSONALITY_SHIFT), anyMap());
        verify(growthMetrics).recordShiftAssessOk();
    }

    @Test
    @DisplayName("验收⑥：小幅偏移（|Δ|=0.05 ≤ 0.08）不下行")
    void smallShiftNoPublish() {
        ActorCharacter adult = chWithPersona(7L, GrowthStage.PRIME.name());
        when(characterRepository.findById(7L)).thenReturn(java.util.Optional.of(adult));
        when(shiftLogRepository.lastAssessedGameSecond(1L, "npc:7")).thenReturn(null);
        when(evidenceCollector.collect(eq(1L), eq("npc:7"), any(), eq(2_000_000L), eq(6.0), eq(20)))
                .thenReturn(sufficientEvidence());
        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(promptTemplateService.render(eq(1L), eq(1L), eq(PersonalityShiftService.SCENE_SHIFT_EVAL), anyMap()))
                .thenReturn("评估提示");
        when(promptTemplateService.systemMessage(eq(1L), eq(1L), eq(PersonalityShiftService.SCENE_SHIFT_EVAL), any()))
                .thenReturn("");
        when(aiChatGateway.chatCompletion(eq(1L), eq(1L), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult(
                        "{\"shifts\":[{\"dim\":\"ocean\",\"field\":\"agreeableness\",\"delta\":0.05,"
                                + "\"why\":\"温和了些\"}],\"summary\":\"略\"}",
                        "test-model", 10, 5, 15, 67L));

        Map<String, Object> result = service.assess(1L, "npc:7", GrowthShiftLog.TRIGGER_MANUAL, 2_000_000L);

        assertNotNull(result);
        Map<?, ?> first = (Map<?, ?>) ((List<?>) result.get("shifts")).get(0);
        assertEquals(0.05, ((Number) first.get("delta")).doubleValue(), 0.0001);
        verify(sandboxSseHub, never()).publish(eq(1L), eq(WorldSandboxSseHub.EVENT_NPC_PERSONALITY_SHIFT), anyMap());
    }

    @Test
    @DisplayName("证据不足（总分 < 6.0）：本轮跳过，零 AI 调用，零偏移")
    void insufficientEvidenceSkips() {
        ActorCharacter adult = chWithPersona(8L, GrowthStage.PRIME.name());
        when(characterRepository.findById(8L)).thenReturn(java.util.Optional.of(adult));
        when(shiftLogRepository.lastAssessedGameSecond(1L, "npc:8")).thenReturn(null);
        when(evidenceCollector.collect(eq(1L), eq("npc:8"), any(), eq(3_000_000L), eq(6.0), eq(20)))
                .thenReturn(new ShiftEvidenceCollector.CollectResult(2.0, List.of()));

        Map<String, Object> result = service.assess(1L, "npc:8", GrowthShiftLog.TRIGGER_MANUAL, 3_000_000L);

        assertNotNull(result);
        assertEquals("evidence_insufficient", result.get("skipped"));
        verify(aiChatGateway, never()).chatCompletion(any(), any(), any(), any());
        verify(shiftLogRepository, never()).save(any());
    }

    // ==================== N5 persona_shift 门控（2026-09-26：尖锐采纳否则升 Qwen） ====================

    /** 注入 NanoJev 判定门 mock（字段注入，构造器不变）。 */
    private NanoJevDecisionGate injectGate() {
        NanoJevDecisionGate gate = org.mockito.Mockito.mock(NanoJevDecisionGate.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "nanoJevDecisionGate", gate);
        return gate;
    }

    @Test
    @DisplayName("N5 persona_shift：NanoJev 尖锐采纳（强度 3 + 正向）→ 三维偏移落账，零 Qwen 调用")
    void nanoJevAdoptedAppliesShifts() {
        NanoJevDecisionGate gate = injectGate();
        when(gate.isEnabled(NanoJevDecisionScenes.PERSONA_SHIFT)).thenReturn(true);
        when(gate.mixedDecisions(eq(1L), eq(9L), eq(NanoJevDecisionScenes.PERSONA_SHIFT),
                any(), anyMap())).thenReturn(Map.of(
                PersonalityShiftService.QID_SHIFT_STRENGTH,
                new NanoJevDecisionGate.ScoreOutcome(
                        3, 2.6, true, NanoJevDecisionGate.DISP_ADOPTED, null, null, null, 0),
                PersonalityShiftService.QID_SHIFT_DIRECTION,
                new NanoJevDecisionGate.ChoiceOutcome(
                        "positive", true, NanoJevDecisionGate.DISP_ADOPTED, null, null, null, 0)));

        ActorCharacter adult = chWithPersona(9L, GrowthStage.PRIME.name());
        when(characterRepository.findById(9L)).thenReturn(java.util.Optional.of(adult));
        when(shiftLogRepository.lastAssessedGameSecond(1L, "npc:9")).thenReturn(null);
        when(evidenceCollector.collect(eq(1L), eq("npc:9"), any(), eq(1_000_000L), eq(6.0), eq(20)))
                .thenReturn(sufficientEvidence());

        Map<String, Object> result = service.assess(1L, "npc:9", GrowthShiftLog.TRIGGER_MANUAL, 1_000_000L);

        assertNotNull(result);
        List<?> shifts = (List<?>) result.get("shifts");
        assertEquals(3, shifts.size(), "强度 3 + 正向 → 外向/宜人 ↑、神经质 ↓ 三维偏移");
        verify(aiChatGateway, never()).chatCompletion(any(), any(), any(), any());
        // 三维写回：外向/宜人 0.50 → 0.55，神经质 0.50 → 0.45（幅度 0.05=SHIFT_MAG_LEVEL3）
        PersonaProfile after = PersonaJson.readQuietly(adult.getPersonaJson());
        assertEquals(0.55, after.getDims().getPersonality().getOcean().getExtraversion(), 0.0001);
        assertEquals(0.55, after.getDims().getPersonality().getOcean().getAgreeableness(), 0.0001);
        assertEquals(0.45, after.getDims().getPersonality().getOcean().getNeuroticism(), 0.0001);
        verify(growthMetrics).recordShiftAssessOk();
    }

    @Test
    @DisplayName("N5 persona_shift：NanoJev 采纳但证据弱（强度 1）→ 明确判定无偏移，不记失败指标、零 Qwen")
    void nanoJevAdoptedWeakEvidenceNoShift() {
        NanoJevDecisionGate gate = injectGate();
        when(gate.isEnabled(NanoJevDecisionScenes.PERSONA_SHIFT)).thenReturn(true);
        when(gate.mixedDecisions(eq(1L), eq(10L), eq(NanoJevDecisionScenes.PERSONA_SHIFT),
                any(), anyMap())).thenReturn(Map.of(
                PersonalityShiftService.QID_SHIFT_STRENGTH,
                new NanoJevDecisionGate.ScoreOutcome(
                        1, 0.8, true, NanoJevDecisionGate.DISP_ADOPTED, null, null, null, 0),
                PersonalityShiftService.QID_SHIFT_DIRECTION,
                new NanoJevDecisionGate.ChoiceOutcome(
                        "unclear", true, NanoJevDecisionGate.DISP_ADOPTED, null, null, null, 0)));

        ActorCharacter adult = chWithPersona(10L, GrowthStage.PRIME.name());
        when(characterRepository.findById(10L)).thenReturn(java.util.Optional.of(adult));
        when(shiftLogRepository.lastAssessedGameSecond(1L, "npc:10")).thenReturn(null);
        when(evidenceCollector.collect(eq(1L), eq("npc:10"), any(), eq(1_000_000L), eq(6.0), eq(20)))
                .thenReturn(sufficientEvidence());

        Map<String, Object> result = service.assess(1L, "npc:10", GrowthShiftLog.TRIGGER_MANUAL, 1_000_000L);

        assertNotNull(result);
        assertEquals("nanojev_no_shift", result.get("fallback"));
        assertEquals(List.of(), result.get("shifts"));
        verify(aiChatGateway, never()).chatCompletion(any(), any(), any(), any());
        verify(growthMetrics, never()).recordShiftAssessFail();
        // 零偏移留痕（_noop 行）
        org.mockito.ArgumentCaptor<GrowthShiftLog> captor =
                org.mockito.ArgumentCaptor.forClass(GrowthShiftLog.class);
        verify(shiftLogRepository).save(captor.capture());
        assertEquals("_noop", captor.getValue().getField());
    }

    @Test
    @DisplayName("N5 persona_shift：分布不尖锐（升级）→ 升 Qwen 深度判定（沿既有 AI 路径）")
    void nanoJevEscalatedFallsThroughToQwen() {
        NanoJevDecisionGate gate = injectGate();
        when(gate.isEnabled(NanoJevDecisionScenes.PERSONA_SHIFT)).thenReturn(true);
        when(gate.mixedDecisions(eq(1L), eq(11L), eq(NanoJevDecisionScenes.PERSONA_SHIFT),
                any(), anyMap())).thenReturn(Map.of(
                PersonalityShiftService.QID_SHIFT_STRENGTH,
                new NanoJevDecisionGate.ScoreOutcome(
                        -1, 0, false, NanoJevDecisionGate.DISP_ESCALATED, null, null, null, 0),
                PersonalityShiftService.QID_SHIFT_DIRECTION,
                new NanoJevDecisionGate.ChoiceOutcome(
                        null, false, NanoJevDecisionGate.DISP_ESCALATED, null, null, null, 0)));

        ActorCharacter adult = chWithPersona(11L, GrowthStage.PRIME.name());
        when(characterRepository.findById(11L)).thenReturn(java.util.Optional.of(adult));
        when(shiftLogRepository.lastAssessedGameSecond(1L, "npc:11")).thenReturn(null);
        when(evidenceCollector.collect(eq(1L), eq("npc:11"), any(), eq(1_000_000L), eq(6.0), eq(20)))
                .thenReturn(sufficientEvidence());
        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(promptTemplateService.render(eq(1L), eq(1L), eq(PersonalityShiftService.SCENE_SHIFT_EVAL), anyMap()))
                .thenReturn("评估提示");
        when(promptTemplateService.systemMessage(eq(1L), eq(1L), eq(PersonalityShiftService.SCENE_SHIFT_EVAL), any()))
                .thenReturn("");
        when(aiChatGateway.chatCompletion(eq(1L), eq(1L), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult(
                        "{\"shifts\":[{\"dim\":\"ocean\",\"field\":\"openness\",\"delta\":0.04,"
                                + "\"why\":\"升级重问后确认\"}],\"summary\":\"略\"}",
                        "test-model", 10, 5, 15, 67L));

        Map<String, Object> result = service.assess(1L, "npc:11", GrowthShiftLog.TRIGGER_MANUAL, 1_000_000L);

        assertNotNull(result);
        assertEquals(1, ((List<?>) result.get("shifts")).size());
        verify(aiChatGateway).chatCompletion(eq(1L), eq(1L), any(), any(AiChatRequest.class));
    }

    // ==================== 批6 收口：AI 偏移评估请求前置记忆链段位（段1A+1C+2D+2E） ====================

    /** growth_persona_shift_eval AI 评估：请求前置 memoryChainContext（角色引用+游戏时刻）；原始提示保留在后。 */
    @Test
    void aiAssessRequestPrependsMemoryChainContext() {
        AiContextInjector injector = org.mockito.Mockito.mock(AiContextInjector.class);
        when(injector.memoryChainContext(eq(1L), eq("npc:6"), any())).thenReturn("【世界观】青禾村【我的身份与处境】族长");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "aiContextInjector", injector);
        ActorCharacter adult = chWithPersona(6L, GrowthStage.PRIME.name());
        when(characterRepository.findById(6L)).thenReturn(java.util.Optional.of(adult));
        when(shiftLogRepository.lastAssessedGameSecond(1L, "npc:6")).thenReturn(null);
        when(evidenceCollector.collect(eq(1L), eq("npc:6"), any(), eq(1_000_000L), eq(6.0), eq(20)))
                .thenReturn(sufficientEvidence());
        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(promptTemplateService.render(eq(1L), eq(1L), eq(PersonalityShiftService.SCENE_SHIFT_EVAL), anyMap()))
                .thenReturn("评估提示");
        when(promptTemplateService.systemMessage(eq(1L), eq(1L), eq(PersonalityShiftService.SCENE_SHIFT_EVAL), any()))
                .thenReturn("");
        when(aiChatGateway.chatCompletion(eq(1L), eq(1L), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult("{\"shifts\":[],\"summary\":\"无偏移\"}", "test-model", 10, 5, 15, 67L));

        service.assess(1L, "npc:6", GrowthShiftLog.TRIGGER_MANUAL, 1_000_000L);

        ArgumentCaptor<AiChatRequest> cap = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(eq(1L), eq(1L), any(), cap.capture());
        String user = cap.getValue().messages().get(1).content();
        assertTrue(user.startsWith("【世界观】青禾村"), "前置记忆链段位 1A：" + user);
        assertTrue(user.endsWith("\n\n评估提示"), "原始提示保留在后：" + user);
    }
}
