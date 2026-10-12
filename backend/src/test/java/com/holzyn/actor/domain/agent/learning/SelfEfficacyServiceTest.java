package com.holzyn.actor.domain.agent.learning;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.agent.context.AiContextInjector;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.debug.service.AiCallTrace;
import com.holzyn.actor.domain.growth.persona.PersonaDims;
import com.holzyn.actor.domain.growth.persona.PersonaJson;
import com.holzyn.actor.domain.growth.persona.PersonaProfile;
import com.holzyn.actor.domain.growth.persona.PersonaService;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SelfEfficacyService 能力自评卡（P1 ①-2）单元测试。
 * <p>验证：门控（开关/参数缺失/档案缺失）、AI 判定（changed true/false/解析失败/fail-open）、
 * 写回（合并更新 + savePersona source=self_efficacy + 版本留痕）、领域清洗（越界领域丢弃）、
 * trace 落点（stepType=self_efficacy + ref 前缀）。</p>
 */
class SelfEfficacyServiceTest {

    private final Consumer<AiCallTrace>[] capturedSink = new Consumer[1];
    private final List<AiCallTrace> traces = new ArrayList<>();

    private SelfEfficacyService service;
    private PromptTemplateService promptTemplateService;
    private AiSpringChatGateway aiChatGateway;
    private UsageLogService usageLogService;
    private PersonaService personaService;

    @BeforeEach
    void setUp() {
        service = new SelfEfficacyService();
        promptTemplateService = mock(PromptTemplateService.class);
        aiChatGateway = mock(AiSpringChatGateway.class);
        usageLogService = mock(UsageLogService.class);
        personaService = mock(PersonaService.class);
        ReflectionTestUtils.setField(service, "promptTemplateService", promptTemplateService);
        ReflectionTestUtils.setField(service, "aiChatGateway", aiChatGateway);
        ReflectionTestUtils.setField(service, "usageLogService", usageLogService);
        ReflectionTestUtils.setField(service, "personaService", personaService);
    }

    @AfterEach
    void tearDown() {
        AiCallContext.setSink(null);
    }

    private void captureTraces() {
        capturedSink[0] = traces::add;
        AiCallContext.setSink(capturedSink[0]);
    }

    /** 过滤出能力自评环节 trace（stepType=self_efficacy）。 */
    private List<AiCallTrace> selfEfficacyTraces() {
        return traces.stream().filter(t -> SelfEfficacyService.STEP_TYPE_SELF_EFFICACY.equals(t.getStepType())).toList();
    }

    /** 构造含自评/技能的档案 JSON（snake_case 键，对齐 PersonaJson.mapper）。 */
    private String personaJson(Map<String, Integer> selfEfficacy, String comment, Map<String, Double> skills) {
        PersonaProfile p = PersonaProfile.empty(1L, 100L);
        PersonaDims.Ability ab = new PersonaDims.Ability();
        ab.setSelfEfficacy(selfEfficacy == null ? new LinkedHashMap<>() : new LinkedHashMap<>(selfEfficacy));
        ab.setSelfComment(comment);
        ab.setSkills(skills == null ? new LinkedHashMap<>() : new LinkedHashMap<>(skills));
        p.getDims().setAbility(ab);
        return PersonaJson.write(p);
    }

    /** stub AI 判定网关：返回给定 JSON（render/system 已 stub）。 */
    private void stubVerdict(String json) throws Exception {
        when(promptTemplateService.render(anyLong(), eq(100L), eq(SelfEfficacyService.SCENE_SELF_EFFICACY_UPDATE), any()))
                .thenReturn("prompt");
        when(promptTemplateService.systemMessage(anyLong(), eq(100L), eq(SelfEfficacyService.SCENE_SELF_EFFICACY_UPDATE), any()))
                .thenReturn("你只输出严格的 JSON 对象，不输出任何其他文字。");
        when(aiChatGateway.chatCompletion(anyLong(), eq(100L), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult(json, "qwen", 100, 50, 150, 0, 100, 1L));
    }

    private Map<String, Integer> seOf(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), Integer.parseInt(String.valueOf(kv[i + 1])));
        }
        return m;
    }

    private Map<String, Double> skillsOf(Object... kv) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), Double.parseDouble(String.valueOf(kv[i + 1])));
        }
        return m;
    }

    // ==================== 门控 ====================

    @Test
    void disabledSkips() {
        ReflectionTestUtils.setField(service, "enabled", false);
        service.maybeAdjustFromReflection(100L, "npc:1", "反思上下文");
        verify(personaService, never()).latestPersonaJson(anyLong());
    }

    @Test
    void missingParamsSkip() {
        service.maybeAdjustFromReflection(null, "npc:1", "反思上下文");
        service.maybeAdjustFromReflection(100L, null, "反思上下文");
        service.maybeAdjustFromReflection(100L, "npc:1", null);
        service.maybeAdjustFromReflection(100L, "npc:1", "   ");
        verify(personaService, never()).latestPersonaJson(anyLong());
    }

    @Test
    void personaServiceNotWiredSkips() {
        ReflectionTestUtils.setField(service, "personaService", null);
        service.maybeAdjustFromReflection(100L, "npc:1", "反思上下文");
        // 无异常即通过（未装配降级）
    }

    @Test
    void noPersonaSkips() {
        when(personaService.latestPersonaJson(1L)).thenReturn(Optional.empty());
        service.maybeAdjustFromReflection(100L, "npc:1", "反思上下文");
        verify(personaService, never()).savePersona(anyLong(), any(), anyString());
    }

    // ==================== AI 判定 + 写回 ====================

    @Test
    void changedTrueWritesBackMerged() throws Exception {
        captureTraces();
        when(personaService.latestPersonaJson(1L))
                .thenReturn(Optional.of(personaJson(seOf("农事", 4, "算账", 3), "农活是把好手", skillsOf("农事", 8.0, "算账", 5.0))));
        // AI 判定：农事 4→3（最近在田里出了岔子）、算账维持 3、新增 skills 内领域「记账」=4
        stubVerdict("{\"self_efficacy\":{\"农事\":3,\"算账\":3,\"记账\":4},\"self_comment\":\"种地马虎，记性还行\","
                + "\"changed\":true,\"reason\":\"田里出岔子，记账被夸\"}");
        AtomicReference<PersonaProfile> saved = new AtomicReference<>();
        when(personaService.savePersona(eq(1L), any(PersonaProfile.class), eq(SelfEfficacyService.SOURCE_SELF_EFFICACY)))
                .thenAnswer(inv -> {
                    saved.set(inv.getArgument(1));
                    return 5;
                });

        service.maybeAdjustFromReflection(100L, "npc:1", "反思：近日田里走了神，被邻人指出农时误判");

        verify(personaService).savePersona(eq(1L), any(PersonaProfile.class), eq(SelfEfficacyService.SOURCE_SELF_EFFICACY));
        PersonaProfile savedProfile = saved.get();
        assertNotNull(savedProfile);
        PersonaDims.Ability ab = savedProfile.getDims().getAbility();
        // 合并语义：AI 给出的领域更新、其余保留；越界领域（不在当前自评/不在 skills）丢弃
        assertEquals(3, ab.getSelfEfficacy().get("农事"), "农事 4→3");
        assertEquals(3, ab.getSelfEfficacy().get("算账"), "算账维持 3");
        assertFalse(ab.getSelfEfficacy().containsKey("记账"), "记账不在当前自评也不在 skills——越界领域按铁律丢弃");
        assertEquals("种地马虎，记性还行", ab.getSelfComment(), "整体自评更新");
        // trace：已微调 + ref 前缀
        List<AiCallTrace> seTraces = selfEfficacyTraces();
        assertTrue(seTraces.stream().anyMatch(t -> t.getContent() != null
                        && t.getContent().startsWith("ref=npc:1 ") && t.getContent().contains("已微调")),
                "应写已微调 trace（ref 前缀）");
    }

    @Test
    void changedFalseKeepsCurrent() throws Exception {
        captureTraces();
        when(personaService.latestPersonaJson(1L))
                .thenReturn(Optional.of(personaJson(seOf("农事", 4), "农活是把好手", skillsOf("农事", 8.0))));
        stubVerdict("{\"self_efficacy\":{\"农事\":4},\"self_comment\":\"农活是把好手\","
                + "\"changed\":false,\"reason\":\"近期无相关经历\"}");

        service.maybeAdjustFromReflection(100L, "npc:1", "反思：无特别之事");

        verify(personaService, never()).savePersona(anyLong(), any(), anyString());
        List<AiCallTrace> seTraces = selfEfficacyTraces();
        assertTrue(seTraces.stream().anyMatch(t -> t.getContent() != null && t.getContent().contains("changed=false")),
                "应写 changed=false trace");
    }

    @Test
    void aiFailureFailsOpen() throws Exception {
        when(personaService.latestPersonaJson(1L))
                .thenReturn(Optional.of(personaJson(seOf("农事", 4), "农活是把好手", skillsOf("农事", 8.0))));
        when(promptTemplateService.render(anyLong(), eq(100L), eq(SelfEfficacyService.SCENE_SELF_EFFICACY_UPDATE), any()))
                .thenReturn("prompt");
        when(promptTemplateService.systemMessage(anyLong(), eq(100L), eq(SelfEfficacyService.SCENE_SELF_EFFICACY_UPDATE), any()))
                .thenReturn("sys");
        when(aiChatGateway.chatCompletion(anyLong(), eq(100L), any(), any(AiChatRequest.class)))
                .thenThrow(new RuntimeException("网关超时"));

        service.maybeAdjustFromReflection(100L, "npc:1", "反思上下文");

        verify(personaService, never()).savePersona(anyLong(), any(), anyString());
    }

    @Test
    void invalidVerdictFailsOpen() throws Exception {
        when(personaService.latestPersonaJson(1L))
                .thenReturn(Optional.of(personaJson(seOf("农事", 4), null, skillsOf("农事", 8.0))));
        stubVerdict("这不是 JSON");
        service.maybeAdjustFromReflection(100L, "npc:1", "反思上下文");
        verify(personaService, never()).savePersona(anyLong(), any(), anyString());
    }

    // ==================== 批6 收口：AI 判定请求前置记忆链段位（段1A+1C+2D+2E） ====================

    /** self_efficacy_update AI 判定：请求前置 memoryChainContext；原始提示保留在后。 */
    @Test
    void aiJudgeRequestPrependsMemoryChainContext() throws Exception {
        AiContextInjector injector = mock(AiContextInjector.class);
        when(injector.memoryChainContext(eq(100L), eq("npc:1"), any())).thenReturn("【世界观】青禾村【我的身份与处境】农妇");
        ReflectionTestUtils.setField(service, "aiContextInjector", injector);
        when(personaService.latestPersonaJson(1L))
                .thenReturn(Optional.of(personaJson(seOf("农事", 4), "农活是把好手", skillsOf("农事", 8.0))));
        stubVerdict("{\"self_efficacy\":{\"农事\":4},\"self_comment\":\"农活是把好手\","
                + "\"changed\":false,\"reason\":\"近期无相关经历\"}");

        service.maybeAdjustFromReflection(100L, "npc:1", "反思：近日田里走了神");

        ArgumentCaptor<AiChatRequest> cap = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(anyLong(), eq(100L), any(), cap.capture());
        String user = cap.getValue().messages().get(1).content();
        assertTrue(user.startsWith("【世界观】青禾村"), "前置记忆链段位 1A：" + user);
        assertTrue(user.endsWith("\n\nprompt"), "原始提示保留在后：" + user);
    }

    /** 未装配注入器（fail-open）→ AI 请求原样，不带段位前缀。 */
    @Test
    void aiJudgeRequestNoPrefixWhenInjectorMissing() throws Exception {
        when(personaService.latestPersonaJson(1L))
                .thenReturn(Optional.of(personaJson(seOf("农事", 4), "农活是把好手", skillsOf("农事", 8.0))));
        stubVerdict("{\"self_efficacy\":{\"农事\":4},\"changed\":false,\"reason\":\"无\"}");

        service.maybeAdjustFromReflection(100L, "npc:1", "反思：无特别之事");

        ArgumentCaptor<AiChatRequest> cap = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(anyLong(), eq(100L), any(), cap.capture());
        assertEquals("prompt", cap.getValue().messages().get(1).content(), "未装配注入器 → 原样");
    }

    @Test
    void writeBackFailureFailsOpen() throws Exception {
        when(personaService.latestPersonaJson(1L))
                .thenReturn(Optional.of(personaJson(seOf("农事", 4), null, skillsOf("农事", 8.0))));
        stubVerdict("{\"self_efficacy\":{\"农事\":3},\"self_comment\":\"种地马虎\","
                + "\"changed\":true,\"reason\":\"田里出岔子\"}");
        when(personaService.savePersona(anyLong(), any(), anyString())).thenThrow(new RuntimeException("写库失败"));

        service.maybeAdjustFromReflection(100L, "npc:1", "反思上下文");
        // 无异常即通过（fail-open）
    }

    @Test
    void changedTrueButEmptyUpdateSkipsWrite() throws Exception {
        captureTraces();
        when(personaService.latestPersonaJson(1L))
                .thenReturn(Optional.of(personaJson(seOf("农事", 4), null, skillsOf("农事", 8.0))));
        // changed=true 但自评/评论均为空（AI 空转）：跳过写回
        stubVerdict("{\"self_efficacy\":{},\"self_comment\":\"\",\"changed\":true,\"reason\":\"无\"}");

        service.maybeAdjustFromReflection(100L, "npc:1", "反思上下文");

        verify(personaService, never()).savePersona(anyLong(), any(), anyString());
    }

    // ==================== 纯逻辑 ====================

    @Test
    void parseVerdictNormal() {
        SelfEfficacyService.Verdict v = SelfEfficacyService.parseVerdict(
                "{\"self_efficacy\":{\"农事\":4,\"算账\":2},\"self_comment\":\"还行\","
                        + "\"changed\":true,\"reason\":\"田里顺手\"}");
        assertNotNull(v);
        assertTrue(v.changed());
        assertEquals(4, v.selfEfficacy().get("农事"));
        assertEquals(2, v.selfEfficacy().get("算账"));
        assertEquals("还行", v.selfComment());
        assertEquals("田里顺手", v.reason());
    }

    @Test
    void parseVerdictClampsScore() {
        SelfEfficacyService.Verdict v = SelfEfficacyService.parseVerdict(
                "{\"self_efficacy\":{\"农事\":9,\"算账\":0},\"changed\":true,\"reason\":\"x\"}");
        assertNotNull(v);
        assertEquals(5, v.selfEfficacy().get("农事"), "9 钳制到 5");
        assertEquals(1, v.selfEfficacy().get("算账"), "0 钳制到 1");
    }

    @Test
    void parseVerdictInvalidReturnsNull() {
        assertNull(SelfEfficacyService.parseVerdict(null));
        assertNull(SelfEfficacyService.parseVerdict("  "));
        assertNull(SelfEfficacyService.parseVerdict("abc"));
        // 合法对象但无实质内容：返回 Verdict（changed=false，不视为解析失败）
        SelfEfficacyService.Verdict empty = SelfEfficacyService.parseVerdict("{\"changed\":\"x\"}");
        assertNotNull(empty);
        assertFalse(empty.changed());
        assertTrue(empty.selfEfficacy().isEmpty());
    }

    @Test
    void sanitizeDropsUnknownDomains() {
        Map<String, Integer> updates = seOf("农事", 4, "新编领域", 5);
        Map<String, Integer> current = seOf("农事", 3);
        Map<String, Integer> out = SelfEfficacyService.sanitize(updates, current, java.util.Set.of("农事"));
        assertEquals(1, out.size());
        assertEquals(4, out.get("农事"));
        assertFalse(out.containsKey("新编领域"), "越界领域（非当前自评/非 skills）必须丢弃");
    }

    @Test
    void sanitizeAllowsSkillDomains() {
        Map<String, Integer> updates = seOf("记账", 4);
        Map<String, Integer> current = seOf("农事", 3);
        Map<String, Integer> out = SelfEfficacyService.sanitize(updates, current, java.util.Set.of("记账"));
        assertEquals(4, out.get("记账"), "skills 领域允许新增");
    }
}
