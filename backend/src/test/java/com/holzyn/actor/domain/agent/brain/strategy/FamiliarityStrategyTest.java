package com.holzyn.actor.domain.agent.brain.strategy;

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
import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FamiliarityStrategy MUSE 式策略切换（P1 ①-3）单元测试。
 * <p>验证：调制器语义（永不产出决策）、门控（特殊 NPC/非 programmatic）、熟悉度三源
 * （领域识别/经历卷/自评信心[自评卡优先+兄弟领域+技能兜底]）、确定性档位规则
 * （陌生→保守/低信心→保守/高信心+高经历→激进/其余→均衡）、AI 档位复核
 * （维持/调整/失败 fail-open 回退）、【策略偏好】段注入 holder 双通道、trace
 * （stepType=muse_strategy + ref 前缀）、均衡不注入。</p>
 */
class FamiliarityStrategyTest {

    private final Consumer<AiCallTrace>[] capturedSink = new Consumer[1];
    private final List<AiCallTrace> traces = new ArrayList<>();

    private FamiliarityStrategy strategy;
    private PersonaService personaService;
    private ActorMemoryRepository memoryRepository;
    private ActorEventRepository eventRepository;
    private PromptTemplateService promptTemplateService;
    private AiSpringChatGateway aiChatGateway;
    private UsageLogService usageLogService;

    @BeforeEach
    void setUp() {
        strategy = new FamiliarityStrategy();
        personaService = mock(PersonaService.class);
        memoryRepository = mock(ActorMemoryRepository.class);
        eventRepository = mock(ActorEventRepository.class);
        promptTemplateService = mock(PromptTemplateService.class);
        aiChatGateway = mock(AiSpringChatGateway.class);
        usageLogService = mock(UsageLogService.class);
        ReflectionTestUtils.setField(strategy, "personaService", personaService);
        ReflectionTestUtils.setField(strategy, "memoryRepository", memoryRepository);
        ReflectionTestUtils.setField(strategy, "eventRepository", eventRepository);
        ReflectionTestUtils.setField(strategy, "promptTemplateService", promptTemplateService);
        ReflectionTestUtils.setField(strategy, "aiChatGateway", aiChatGateway);
        ReflectionTestUtils.setField(strategy, "usageLogService", usageLogService);
        // 测试确定性：抽样率 0（默认不调 AI，纯程序化路径；AI 复核用例单独开 1.0）
        ReflectionTestUtils.setField(strategy, "reviewSampleRate", 0.0);
        // 经历卷计数默认 0（LOW）
        when(memoryRepository.countByProjectIdAndCharacterIdAndTypeAndDeleted(
                100L, 3L, ActorMemoryTypes.TYPE_EPISODIC, 0)).thenReturn(0L);
        when(eventRepository.countByProjectIdAndCharacterRef(100L, 3L, "npc:3")).thenReturn(0L);
    }

    @AfterEach
    void tearDown() {
        AiCallContext.setSink(null);
        AiCallContext.clearStrategyPreference();
    }

    private void captureTraces() {
        capturedSink[0] = traces::add;
        AiCallContext.setSink(capturedSink[0]);
    }

    /** 过滤出 MUSE 策略切换环节 trace（stepType=muse_strategy）。 */
    private List<AiCallTrace> museTraces() {
        return traces.stream().filter(t -> FamiliarityStrategy.STEP_TYPE_MUSE_STRATEGY.equals(t.getStepType())).toList();
    }

    private ActorCharacter character() {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setProjectId(100L);
        ch.setName("林安");
        ch.setType("special");
        return ch;
    }

    private DecisionContext ctx(NpcKind kind, String brainLevel, String situation) {
        return new DecisionContext(100L, character(), 1L, kind, brainLevel, situation, null, null, false);
    }

    /** 构造含自评/技能的档案 JSON（snake_case 键，对齐 PersonaJson.mapper）。 */
    private String personaJson(Map<String, Integer> selfEfficacy, Map<String, Double> skills) {
        PersonaProfile p = PersonaProfile.empty(1L, 100L);
        PersonaDims.Ability ab = new PersonaDims.Ability();
        ab.setSelfEfficacy(selfEfficacy == null ? new LinkedHashMap<>() : new LinkedHashMap<>(selfEfficacy));
        ab.setSkills(skills == null ? new LinkedHashMap<>() : new LinkedHashMap<>(skills));
        p.getDims().setAbility(ab);
        return PersonaJson.write(p);
    }

    /** 默认档案：沈砚秋式技能集 + 可选自评（农事耕作/记账算筹）。 */
    private void stubPersona(Map<String, Integer> se, Map<String, Double> skills) {
        when(personaService.latestPersonaJson(3L))
                .thenReturn(Optional.of(personaJson(se, skills)));
    }

    /** stub AI 档位复核网关：返回给定 JSON（render/system 已 stub）。 */
    private void stubReview(String json) throws Exception {
        when(promptTemplateService.render(anyLong(), eq(100L),
                eq(FamiliarityStrategy.SCENE_MUSE_STRATEGY_CHECK), any())).thenReturn("prompt");
        when(promptTemplateService.systemMessage(anyLong(), eq(100L),
                eq(FamiliarityStrategy.SCENE_MUSE_STRATEGY_CHECK), any()))
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

    /** 沈砚秋式技能集（世界现状口径）。 */
    private Map<String, Double> defaultSkills() {
        return skillsOf("村务管理", 9.0, "宗族礼法", 9.0, "农事估产", 7.0, "古铜器鉴赏", 8.0,
                "基础医术", 5.0, "书法", 6.0);
    }

    // ==================== 调制器语义 + 门控 ====================

    /** 调制器：永不产出决策（恒 empty，链继续）。 */
    @Test
    void neverProducesDecision() {
        stubPersona(seOf("农事耕作", 2), defaultSkills());
        assertTrue(strategy.decide(ctx(NpcKind.SPECIAL, "full", "田里的庄稼遭了虫害，农事耕作是你的本行"))
                .isEmpty(), "调制器恒不产出决策");
        assertEquals("muse_familiarity", strategy.name());
        assertEquals(85, strategy.priority(), "priority 介于 Deliberate(60) 与 PhysioDanger(100) 之间");
    }

    /** 仅特殊 NPC + 非 programmatic 档。 */
    @Test
    void appliesOnlySpecialNonProgrammatic() {
        assertTrue(strategy.applies(ctx(NpcKind.SPECIAL, "full", "情境")));
        assertTrue(strategy.applies(ctx(NpcKind.SPECIAL, "light", "情境")));
        assertFalse(strategy.applies(ctx(NpcKind.COMMON, "full", "情境")), "普通 NPC 不评估");
        assertFalse(strategy.applies(ctx(NpcKind.SPECIAL, "programmatic", "情境")), "programmatic 档不评估");
        assertFalse(strategy.applies(null));
    }

    /** 总开关关闭 → 跳过（零 trace 零注入）。 */
    @Test
    void disabledSkips() {
        captureTraces();
        ReflectionTestUtils.setField(strategy, "enabled", false);
        stubPersona(seOf("农事耕作", 2), defaultSkills());
        strategy.decide(ctx(NpcKind.SPECIAL, "full", "田里的庄稼遭了虫害"));
        assertTrue(museTraces().isEmpty(), "关闭时不 trace");
        assertNull(AiCallContext.readStrategyPreference(), "关闭时不注入");
    }

    /** 无档案 → 跳过。 */
    @Test
    void noPersonaSkips() {
        captureTraces();
        when(personaService.latestPersonaJson(3L)).thenReturn(Optional.empty());
        strategy.decide(ctx(NpcKind.SPECIAL, "full", "田里的庄稼遭了虫害"));
        assertTrue(museTraces().isEmpty(), "无档案不 trace");
    }

    /** 无自评无技能（无能力领域可识别）→ 跳过。 */
    @Test
    void noAbilitySkips() {
        captureTraces();
        stubPersona(null, null);
        strategy.decide(ctx(NpcKind.SPECIAL, "full", "田里的庄稼遭了虫害"));
        assertTrue(museTraces().isEmpty(), "无能力领域不 trace");
    }

    /** 情境空白 → 跳过（无从评估）。 */
    @Test
    void blankSituationSkips() {
        captureTraces();
        stubPersona(seOf("农事耕作", 2), defaultSkills());
        strategy.decide(ctx(NpcKind.SPECIAL, "full", "   "));
        assertTrue(museTraces().isEmpty(), "空白情境不 trace");
    }

    // ==================== 档位规则 + 注入 ====================

    /** 陌生情境（领域未识别）→ 保守 + 注入【策略偏好】段 + trace。 */
    @Test
    void unfamiliarSituationConservative() {
        captureTraces();
        stubPersona(seOf("农事耕作", 4), defaultSkills());
        strategy.decide(ctx(NpcKind.SPECIAL, "full", "窗外雨声渐密，屋檐滴水不停"));
        String seg = AiCallContext.readStrategyPreference();
        assertNotNull(seg, "陌生情境注入保守偏好");
        assertTrue(seg.contains("【策略偏好】") && seg.contains("谨慎行事"), seg);
        assertEquals(1, museTraces().size());
        String content = museTraces().get(0).getContent();
        assertTrue(content.startsWith("ref=npc:3"), "content 带角色 ref 前缀");
        assertTrue(content.contains("策略档位=保守") && content.contains("领域=未识别"), content);
    }

    /** 低信心 + 领域识别 → 保守（自评卡 2/5 直接驱动）。 */
    @Test
    void lowConfidenceKnownDomainConservative() {
        captureTraces();
        stubPersona(seOf("农事耕作", 2), defaultSkills());
        strategy.decide(ctx(NpcKind.SPECIAL, "full",
                "田里的庄稼遭了虫害，佃户们围在田头等你拿主意——农事耕作是你的本行"));
        String seg = AiCallContext.readStrategyPreference();
        assertNotNull(seg, "低信心注入保守偏好");
        assertTrue(seg.contains("农事耕作") && seg.contains("自评 2/5"), seg);
        assertTrue(museTraces().get(0).getContent().contains("策略档位=保守"), museTraces().get(0).getContent());
    }

    /** 高信心 + 经历卷高 → 激进（自评卡 5/5 + 见多识广）。 */
    @Test
    void highConfidenceHighExperienceAggressive() {
        captureTraces();
        when(memoryRepository.countByProjectIdAndCharacterIdAndTypeAndDeleted(
                100L, 3L, ActorMemoryTypes.TYPE_EPISODIC, 0)).thenReturn(10L);
        when(eventRepository.countByProjectIdAndCharacterRef(100L, 3L, "npc:3")).thenReturn(15L);
        stubPersona(seOf("农事耕作", 5), defaultSkills());
        strategy.decide(ctx(NpcKind.SPECIAL, "full",
                "田里的庄稼遭了虫害，佃户们围在田头等你拿主意——农事耕作是你的本行"));
        String seg = AiCallContext.readStrategyPreference();
        assertNotNull(seg, "高信心+高经历注入激进偏好");
        assertTrue(seg.contains("胸有成竹") && seg.contains("自评 5/5"), seg);
        assertTrue(museTraces().get(0).getContent().contains("策略档位=激进"), museTraces().get(0).getContent());
        assertTrue(museTraces().get(0).getContent().contains("经历卷=丰富(25)"), museTraces().get(0).getContent());
    }

    /** 高信心但经历卷低 → 均衡（自信但没见过世面，不激进）→ 不注入但 trace。 */
    @Test
    void highConfidenceLowExperienceBalanced() {
        captureTraces();
        stubPersona(seOf("农事耕作", 5), defaultSkills());
        strategy.decide(ctx(NpcKind.SPECIAL, "full",
                "田里的庄稼遭了虫害，佃户们围在田头等你拿主意——农事耕作是你的本行"));
        assertNull(AiCallContext.readStrategyPreference(), "均衡不注入");
        assertEquals(1, museTraces().size());
        assertTrue(museTraces().get(0).getContent().contains("策略档位=均衡"), museTraces().get(0).getContent());
    }

    /** 中等信心（3/5）→ 均衡（不注入）。 */
    @Test
    void moderateConfidenceBalanced() {
        captureTraces();
        stubPersona(seOf("农事耕作", 3), defaultSkills());
        strategy.decide(ctx(NpcKind.SPECIAL, "full",
                "田里的庄稼遭了虫害，佃户们围在田头等你拿主意——农事耕作是你的本行"));
        assertNull(AiCallContext.readStrategyPreference());
        assertTrue(museTraces().get(0).getContent().contains("策略档位=均衡"), museTraces().get(0).getContent());
    }

    /** 无自评回退客观技能折算（技能 7/10 → 信心 4）；经历卷高 → 激进（自评卡缺失也工作）。 */
    @Test
    void skillsFallbackConfidence() {
        captureTraces();
        when(memoryRepository.countByProjectIdAndCharacterIdAndTypeAndDeleted(
                100L, 3L, ActorMemoryTypes.TYPE_EPISODIC, 0)).thenReturn(10L);
        when(eventRepository.countByProjectIdAndCharacterRef(100L, 3L, "npc:3")).thenReturn(15L);
        stubPersona(null, defaultSkills());
        strategy.decide(ctx(NpcKind.SPECIAL, "full",
                "田里的庄稼遭了虫害，佃户们围在田头等你拿主意——农事估产是你的本行"));
        String seg = AiCallContext.readStrategyPreference();
        assertNotNull(seg, "技能兜底信心（7/2→4）+ 高经历 → 激进");
        assertTrue(seg.contains("胸有成竹"), seg);
        assertTrue(museTraces().get(0).getContent().contains("信心=4/5"), museTraces().get(0).getContent());
    }

    // ==================== AI 档位复核（规则+AI 复核，用户 Q4 确认） ====================

    /** 抽样命中（sample-rate=1.0）：AI 复核维持初始档位 → 最终=初始。 */
    @Test
    void aiReviewKeepsPosture() throws Exception {
        captureTraces();
        ReflectionTestUtils.setField(strategy, "reviewSampleRate", 1.0);
        stubPersona(seOf("农事耕作", 2), defaultSkills());
        stubReview("{\"posture\": \"conservative\", \"reason\": \"初来乍到，稳妥为上\"}");
        strategy.decide(ctx(NpcKind.SPECIAL, "full",
                "田里的庄稼遭了虫害，佃户们围在田头等你拿主意——农事耕作是你的本行"));
        String seg = AiCallContext.readStrategyPreference();
        assertNotNull(seg, "复核维持保守 → 仍注入");
        String content = museTraces().get(0).getContent();
        assertTrue(content.contains("策略档位=保守（初始=保守）"), content);
        verify(aiChatGateway).chatCompletion(anyLong(), eq(100L), any(), any(AiChatRequest.class));
    }

    /** AI 复核调整档位（激进 → 均衡）→ 最终=均衡，不注入，trace 记「AI 复核调整」。 */
    @Test
    void aiReviewAdjustsPosture() throws Exception {
        captureTraces();
        ReflectionTestUtils.setField(strategy, "reviewSampleRate", 1.0);
        when(memoryRepository.countByProjectIdAndCharacterIdAndTypeAndDeleted(
                100L, 3L, ActorMemoryTypes.TYPE_EPISODIC, 0)).thenReturn(10L);
        when(eventRepository.countByProjectIdAndCharacterRef(100L, 3L, "npc:3")).thenReturn(15L);
        stubPersona(seOf("农事耕作", 5), defaultSkills());
        stubReview("{\"posture\": \"balanced\", \"reason\": \"虽是本行但今日不宜张扬\"}");
        strategy.decide(ctx(NpcKind.SPECIAL, "full",
                "田里的庄稼遭了虫害，佃户们围在田头等你拿主意——农事耕作是你的本行"));
        assertNull(AiCallContext.readStrategyPreference(), "复核调为均衡 → 不注入");
        assertTrue(museTraces().get(0).getContent().contains("策略档位=均衡（初始=激进，AI 复核调整）"),
                museTraces().get(0).getContent());
    }

    // ==================== 批6 收口：AI 档位复核请求前置记忆链段位（段1A+1C+2D+2E） ====================

    /** muse_strategy_check AI 复核：请求前置 memoryChainContext；原始提示保留在后。 */
    @Test
    void aiReviewRequestPrependsMemoryChainContext() throws Exception {
        captureTraces();
        ReflectionTestUtils.setField(strategy, "reviewSampleRate", 1.0);
        AiContextInjector injector = mock(AiContextInjector.class);
        when(injector.memoryChainContext(eq(100L), eq("npc:3"), any()))
                .thenReturn("【世界观】青禾村【我的身份与处境】族长【当前世界动态】春日【当前状态】议事堂");
        ReflectionTestUtils.setField(strategy, "aiContextInjector", injector);
        stubPersona(seOf("农事耕作", 2), defaultSkills());
        stubReview("{\"posture\": \"conservative\", \"reason\": \"初来乍到，稳妥为上\"}");

        strategy.decide(ctx(NpcKind.SPECIAL, "full",
                "田里的庄稼遭了虫害，佃户们围在田头等你拿主意——农事耕作是你的本行"));

        ArgumentCaptor<AiChatRequest> cap = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(anyLong(), eq(100L), any(), cap.capture());
        String user = cap.getValue().messages().get(1).content();
        assertTrue(user.startsWith("【世界观】青禾村"), "前置记忆链段位 1A：" + user);
        assertTrue(user.endsWith("\n\nprompt"), "原始提示保留在后：" + user);
    }

    /** AI 复核失败（网关异常）→ fail-open 回退初始档位（白盒保底），注入不受影响。 */
    @Test
    void aiReviewFailureFallsBack() throws Exception {
        captureTraces();
        ReflectionTestUtils.setField(strategy, "reviewSampleRate", 1.0);
        stubPersona(seOf("农事耕作", 2), defaultSkills());
        when(promptTemplateService.render(anyLong(), eq(100L),
                eq(FamiliarityStrategy.SCENE_MUSE_STRATEGY_CHECK), any())).thenReturn("prompt");
        when(promptTemplateService.systemMessage(anyLong(), eq(100L),
                eq(FamiliarityStrategy.SCENE_MUSE_STRATEGY_CHECK), any()))
                .thenReturn("你只输出严格的 JSON 对象，不输出任何其他文字。");
        when(aiChatGateway.chatCompletion(anyLong(), eq(100L), any(), any(AiChatRequest.class)))
                .thenThrow(new RuntimeException("AI 超时"));
        strategy.decide(ctx(NpcKind.SPECIAL, "full",
                "田里的庄稼遭了虫害，佃户们围在田头等你拿主意——农事耕作是你的本行"));
        assertNotNull(AiCallContext.readStrategyPreference(), "复核失败回退初始保守 → 仍注入");
        assertTrue(museTraces().get(0).getContent().contains("策略档位=保守（初始=保守）"),
                museTraces().get(0).getContent());
    }

    /** AI 复核解析失败（非法输出）→ fail-open 回退初始档位。 */
    @Test
    void aiReviewInvalidFallsBack() throws Exception {
        captureTraces();
        ReflectionTestUtils.setField(strategy, "reviewSampleRate", 1.0);
        stubPersona(seOf("农事耕作", 2), defaultSkills());
        stubReview("{\"foo\": \"bar\"}");
        strategy.decide(ctx(NpcKind.SPECIAL, "full",
                "田里的庄稼遭了虫害，佃户们围在田头等你拿主意——农事耕作是你的本行"));
        assertNotNull(AiCallContext.readStrategyPreference(), "解析失败回退初始保守 → 仍注入");
        assertTrue(museTraces().get(0).getContent().contains("策略档位=保守（初始=保守）"),
                museTraces().get(0).getContent());
    }

    /** 抽样未命中（sample-rate=0.0）→ 零 AI 调用，纯程序化档位直接生效。 */
    @Test
    void noReviewWhenSampleNotHit() {
        captureTraces();
        stubPersona(seOf("农事耕作", 2), defaultSkills());
        strategy.decide(ctx(NpcKind.SPECIAL, "full",
                "田里的庄稼遭了虫害，佃户们围在田头等你拿主意——农事耕作是你的本行"));
        verify(aiChatGateway, never()).chatCompletion(anyLong(), eq(100L), any(), any(AiChatRequest.class));
        assertNotNull(AiCallContext.readStrategyPreference(), "程序化保守档位直接注入");
    }

    /** 经历卷来源：episodic 记忆 + 事件参与（两源都查）。 */
    @Test
    void experienceVolumeFromBothSources() {
        captureTraces();
        when(memoryRepository.countByProjectIdAndCharacterIdAndTypeAndDeleted(
                100L, 3L, ActorMemoryTypes.TYPE_EPISODIC, 0)).thenReturn(2L);
        when(eventRepository.countByProjectIdAndCharacterRef(100L, 3L, "npc:3")).thenReturn(3L);
        stubPersona(seOf("农事耕作", 5), defaultSkills());
        strategy.decide(ctx(NpcKind.SPECIAL, "full",
                "田里的庄稼遭了虫害，佃户们围在田头等你拿主意——农事耕作是你的本行"));
        // 卷=5 → 中（MEDIUM）→ 高信心 + 中经历 → 均衡（不注入）
        assertNull(AiCallContext.readStrategyPreference());
        assertTrue(museTraces().get(0).getContent().contains("经历卷=一般(5)"), museTraces().get(0).getContent());
    }

    /** 仓库未装配（null）→ 经历卷 0（低），不抛异常。 */
    @Test
    void reposNotWiredFallsBackToZeroVolume() {
        captureTraces();
        ReflectionTestUtils.setField(strategy, "memoryRepository", null);
        ReflectionTestUtils.setField(strategy, "eventRepository", null);
        stubPersona(seOf("农事耕作", 2), defaultSkills());
        strategy.decide(ctx(NpcKind.SPECIAL, "full",
                "田里的庄稼遭了虫害，佃户们围在田头等你拿主意——农事耕作是你的本行"));
        assertNotNull(AiCallContext.readStrategyPreference(), "仓库未装配仍按程序化保守注入");
        assertTrue(museTraces().get(0).getContent().contains("经历卷=尚浅(0)"), museTraces().get(0).getContent());
    }

    // ==================== 纯逻辑（静态函数） ====================

    /** 领域识别：直接包含领域名（+4）。 */
    @Test
    void recognizeDomainDirect() {
        FamiliarityStrategy.DomainMatch m = FamiliarityStrategy.recognizeDomain(
                "农事耕作是你的本行", java.util.Set.of("农事耕作", "书法"));
        assertNotNull(m);
        assertEquals("农事耕作", m.domain());
    }

    /** 领域识别：二元组命中（领域名未完整出现，相邻两字出现）。 */
    @Test
    void recognizeDomainBigram() {
        FamiliarityStrategy.DomainMatch m = FamiliarityStrategy.recognizeDomain(
                "有人来问村务上的事", java.util.Set.of("村务管理", "书法"));
        assertNotNull(m);
        assertEquals("村务管理", m.domain(), "「村务」二元组命中");
    }

    /** 领域识别：主题关键词命中（领域名与情境散文间的词汇桥）。 */
    @Test
    void recognizeDomainThemeKeyword() {
        FamiliarityStrategy.DomainMatch m = FamiliarityStrategy.recognizeDomain(
                "田里的庄稼遭了虫害", java.util.Set.of("农事估产", "书法"));
        assertNotNull(m);
        assertEquals("农事估产", m.domain(), "「田/庄稼/虫害」主题关键词命中");
    }

    /** 领域识别：无命中 → null（陌生情境）。 */
    @Test
    void recognizeDomainNone() {
        assertNull(FamiliarityStrategy.recognizeDomain("窗外雨声渐密", java.util.Set.of("村务管理", "书法")));
        assertNull(FamiliarityStrategy.recognizeDomain(null, java.util.Set.of("书法")));
        assertNull(FamiliarityStrategy.recognizeDomain("情境", java.util.Set.of()));
    }

    /** 主题归属：农事类领域共享主题（兄弟领域信心互用的依据）。 */
    @Test
    void shareThemeWorks() {
        assertTrue(FamiliarityStrategy.shareTheme("农事耕作", "农事估产"), "同属农事主题");
        assertTrue(FamiliarityStrategy.shareTheme("记账算筹", "商路经营"), "同属商事主题");
        assertFalse(FamiliarityStrategy.shareTheme("记账算筹", "古铜器鉴赏"), "商事与鉴赏不同主题");
        assertFalse(FamiliarityStrategy.shareTheme("书法", "农事估产"), "书文与农事不同主题");
        assertFalse(FamiliarityStrategy.shareTheme(null, "农事估产"));
    }

    /** 二元组切分。 */
    @Test
    void bigramsSplit() {
        assertEquals(List.of("农事", "事耕", "耕作"), FamiliarityStrategy.bigrams("农事耕作"));
        assertEquals(List.of(), FamiliarityStrategy.bigrams("农"));
        assertEquals(List.of(), FamiliarityStrategy.bigrams(null));
    }

    /** 自评信心：自评卡优先。 */
    @Test
    void confidencePrefersSelfEfficacy() {
        FamiliarityStrategy.DomainMatch m = new FamiliarityStrategy.DomainMatch("农事耕作", 4);
        assertEquals(Integer.valueOf(4), FamiliarityStrategy.confidenceOf(m,
                seOf("农事耕作", 4), skillsOf("农事耕作", 9.0)));
    }

    /** 自评信心：兄弟领域（同主题）自评兜底。 */
    @Test
    void confidenceSiblingFallback() {
        FamiliarityStrategy.DomainMatch m = new FamiliarityStrategy.DomainMatch("农事估产", 4);
        assertEquals(Integer.valueOf(5), FamiliarityStrategy.confidenceOf(m,
                seOf("农事耕作", 5), skillsOf("农事估产", 7.0)), "兄弟领域自评（同主题）优先于技能");
    }

    /** 自评信心：客观技能折算（/2 四舍五入钳制 1-5）。 */
    @Test
    void confidenceSkillsFallback() {
        FamiliarityStrategy.DomainMatch m = new FamiliarityStrategy.DomainMatch("书法", 2);
        assertEquals(Integer.valueOf(3), FamiliarityStrategy.confidenceOf(m,
                seOf("农事耕作", 4), skillsOf("书法", 6.0)), "6/2→3");
        assertEquals(Integer.valueOf(1), FamiliarityStrategy.confidenceOf(m,
                seOf("农事耕作", 4), skillsOf("书法", 1.0)), "1/2→1 钳制下限");
        assertEquals(Integer.valueOf(5), FamiliarityStrategy.confidenceOf(m,
                seOf("农事耕作", 4), skillsOf("书法", 9.0)), "9/2→5 钳制上限");
    }

    /** 自评信心：全无 → null。 */
    @Test
    void confidenceNone() {
        FamiliarityStrategy.DomainMatch m = new FamiliarityStrategy.DomainMatch("书法", 2);
        assertNull(FamiliarityStrategy.confidenceOf(m, Map.of(), Map.of()));
        assertNull(FamiliarityStrategy.confidenceOf(null, Map.of(), Map.of()));
    }

    /** 初始档位规则：陌生→保守 / 低信心→保守 / 高信心+高经历→激进 / 其余→均衡。 */
    @Test
    void initialPostureRules() {
        FamiliarityStrategy.DomainMatch unknown = null;
        FamiliarityStrategy.DomainMatch farm = new FamiliarityStrategy.DomainMatch("农事耕作", 6);
        // 陌生 → 保守
        assertEquals(FamiliarityStrategy.Posture.CONSERVATIVE,
                FamiliarityStrategy.initialPosture(unknown, Map.of(), Map.of(),
                        FamiliarityStrategy.ExperienceLevel.HIGH));
        // 低信心（2/5）→ 保守
        assertEquals(FamiliarityStrategy.Posture.CONSERVATIVE,
                FamiliarityStrategy.initialPosture(farm, seOf("农事耕作", 2), Map.of(),
                        FamiliarityStrategy.ExperienceLevel.HIGH));
        // 高信心 + 高经历 → 激进
        assertEquals(FamiliarityStrategy.Posture.AGGRESSIVE,
                FamiliarityStrategy.initialPosture(farm, seOf("农事耕作", 4), Map.of(),
                        FamiliarityStrategy.ExperienceLevel.HIGH));
        // 高信心 + 中经历 → 均衡
        assertEquals(FamiliarityStrategy.Posture.BALANCED,
                FamiliarityStrategy.initialPosture(farm, seOf("农事耕作", 4), Map.of(),
                        FamiliarityStrategy.ExperienceLevel.MEDIUM));
        // 中等信心（3/5）+ 高经历 → 均衡
        assertEquals(FamiliarityStrategy.Posture.BALANCED,
                FamiliarityStrategy.initialPosture(farm, seOf("农事耕作", 3), Map.of(),
                        FamiliarityStrategy.ExperienceLevel.HIGH));
        // 有领域但无任何能力依据 → 保守
        assertEquals(FamiliarityStrategy.Posture.CONSERVATIVE,
                FamiliarityStrategy.initialPosture(farm, Map.of(), Map.of(),
                        FamiliarityStrategy.ExperienceLevel.HIGH));
    }

    /** 经历卷分档：<5 低 / 5-19 中 / ≥20 高。 */
    @Test
    void experienceLevelThresholds() {
        assertEquals(FamiliarityStrategy.ExperienceLevel.LOW,
                FamiliarityStrategy.ExperienceLevel.of(0));
        assertEquals(FamiliarityStrategy.ExperienceLevel.LOW,
                FamiliarityStrategy.ExperienceLevel.of(4));
        assertEquals(FamiliarityStrategy.ExperienceLevel.MEDIUM,
                FamiliarityStrategy.ExperienceLevel.of(5));
        assertEquals(FamiliarityStrategy.ExperienceLevel.MEDIUM,
                FamiliarityStrategy.ExperienceLevel.of(19));
        assertEquals(FamiliarityStrategy.ExperienceLevel.HIGH,
                FamiliarityStrategy.ExperienceLevel.of(20));
    }

    /** 复核解析：三档位合法值。 */
    @Test
    void parseReviewValid() {
        assertEquals(FamiliarityStrategy.Posture.CONSERVATIVE,
                FamiliarityStrategy.parseReview("{\"posture\": \"conservative\", \"reason\": \"稳\"}"));
        assertEquals(FamiliarityStrategy.Posture.BALANCED,
                FamiliarityStrategy.parseReview("{\"posture\": \"balanced\"}"));
        assertEquals(FamiliarityStrategy.Posture.AGGRESSIVE,
                FamiliarityStrategy.parseReview("{\"posture\": \"aggressive\"}"));
    }

    /** 复核解析：非法 → null（fail-open）。 */
    @Test
    void parseReviewInvalid() {
        assertNull(FamiliarityStrategy.parseReview(null));
        assertNull(FamiliarityStrategy.parseReview("  "));
        assertNull(FamiliarityStrategy.parseReview("不是 JSON"));
        assertNull(FamiliarityStrategy.parseReview("{\"foo\": 1}"));
        assertNull(FamiliarityStrategy.parseReview("{\"posture\": \"wild\"}"));
    }

    /** 档位值解析（Posture.fromValue）。 */
    @Test
    void postureFromValue() {
        assertEquals(FamiliarityStrategy.Posture.CONSERVATIVE,
                FamiliarityStrategy.Posture.fromValue("conservative"));
        assertEquals(FamiliarityStrategy.Posture.AGGRESSIVE,
                FamiliarityStrategy.Posture.fromValue("AGGRESSIVE"));
        assertNull(FamiliarityStrategy.Posture.fromValue("x"));
        assertNull(FamiliarityStrategy.Posture.fromValue(null));
    }

    /** 段组装：陌生情境保守（无领域）。 */
    @Test
    void buildSegmentUnfamiliarConservative() {
        String seg = FamiliarityStrategy.buildSegment(character(),
                FamiliarityStrategy.Posture.CONSERVATIVE, null,
                Map.of(), Map.of(), FamiliarityStrategy.ExperienceLevel.LOW);
        assertTrue(seg.contains("【策略偏好】") && seg.contains("从没经历过") && seg.contains("谨慎行事"), seg);
    }

    /** 段组装：领域保守（含自评与经历卷依据）。 */
    @Test
    void buildSegmentDomainConservative() {
        String seg = FamiliarityStrategy.buildSegment(character(),
                FamiliarityStrategy.Posture.CONSERVATIVE,
                new FamiliarityStrategy.DomainMatch("农事耕作", 6),
                seOf("农事耕作", 2), Map.of(), FamiliarityStrategy.ExperienceLevel.MEDIUM);
        assertTrue(seg.contains("农事耕作") && seg.contains("自评 2/5") && seg.contains("经历一般"), seg);
    }

    /** 段组装：激进（胸有成竹）。 */
    @Test
    void buildSegmentAggressive() {
        String seg = FamiliarityStrategy.buildSegment(character(),
                FamiliarityStrategy.Posture.AGGRESSIVE,
                new FamiliarityStrategy.DomainMatch("农事耕作", 6),
                seOf("农事耕作", 5), Map.of(), FamiliarityStrategy.ExperienceLevel.HIGH);
        assertTrue(seg.contains("胸有成竹") && seg.contains("自评 5/5") && seg.contains("经历丰富"), seg);
    }

    /** 段组装：均衡 → 空串（不注入）。 */
    @Test
    void buildSegmentBalancedEmpty() {
        assertEquals("", FamiliarityStrategy.buildSegment(character(),
                FamiliarityStrategy.Posture.BALANCED,
                new FamiliarityStrategy.DomainMatch("农事耕作", 6),
                Map.of(), Map.of(), FamiliarityStrategy.ExperienceLevel.HIGH));
    }

    /** 字符串截断。 */
    @Test
    void abbreviateTrims() {
        assertEquals("abc", FamiliarityStrategy.abbreviate("abc", 10));
        assertEquals("abcd…", FamiliarityStrategy.abbreviate("abcdefg", 4));
        assertNull(FamiliarityStrategy.abbreviate(null, 4));
        assertNull(FamiliarityStrategy.abbreviate("  ", 4));
    }
}
