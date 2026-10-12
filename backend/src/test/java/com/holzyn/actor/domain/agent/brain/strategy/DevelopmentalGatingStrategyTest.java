package com.holzyn.actor.domain.agent.brain.strategy;

import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.stage.GrowthGateService;
import com.holzyn.actor.domain.growth.stage.GrowthStage;
import com.holzyn.actor.domain.growth.stage.HardGateResult;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.state.service.CrowdDecisionParse;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DevelopmentalGatingStrategy 发育阶段门控策略单元测试（O6 发育演化）。
 * <p>验证：applies 受限阶段过滤（未成年+老年适用、成年/婴儿/已故不适用、开关关停）、
 * 空闲接管·刺激让路（特殊链情境非空/普通链环境刺激非空 → 链继续）、双链契约产出
 * （SPECIAL ReAct Map / COMMON CrowdAction Map）、AI 润色（概率命中采纳/失败回退/
 * 越界回退/概率 0 不调用）、时段经 ctx.gameSecond 或引擎时钟解析。</p>
 */
class DevelopmentalGatingStrategyTest {

    private GrowthProperties growthProperties;
    private GrowthGateService growthGateService;
    private WorldClockService worldClockService;
    private PromptTemplateService promptTemplateService;
    private AiSpringChatGateway aiChatGateway;
    private UsageLogService usageLogService;
    private DevelopmentalGatingStrategy strategy;

    @BeforeEach
    void setUp() {
        growthProperties = new GrowthProperties();
        growthProperties.getStageGating().setPolishRateSpecial(0.4);
        growthProperties.getStageGating().setPolishRateCommon(0.2);
        growthGateService = Mockito.mock(GrowthGateService.class);
        when(growthGateService.gate(any(), any())).thenReturn(HardGateResult.lenient("npc:3"));
        worldClockService = Mockito.mock(WorldClockService.class);
        when(worldClockService.currentEngineGameSecond(any())).thenReturn(14L * 3600);
        promptTemplateService = Mockito.mock(PromptTemplateService.class);
        when(promptTemplateService.render(any(), any(), anyString(), any())).thenReturn("润色 Prompt");
        when(promptTemplateService.systemMessage(any(), any(), anyString(), any())).thenReturn("");
        aiChatGateway = Mockito.mock(AiSpringChatGateway.class);
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult("蹲在门槛上逗蚂蚁，捡起一片落叶", "test-model", 10, 5, 15, 1L));
        usageLogService = Mockito.mock(UsageLogService.class);
        strategy = new DevelopmentalGatingStrategy();
        ReflectionTestUtils.setField(strategy, "growthProperties", growthProperties);
        ReflectionTestUtils.setField(strategy, "growthGateService", growthGateService);
        ReflectionTestUtils.setField(strategy, "worldClockService", worldClockService);
        ReflectionTestUtils.setField(strategy, "promptTemplateService", promptTemplateService);
        ReflectionTestUtils.setField(strategy, "aiChatGateway", aiChatGateway);
        ReflectionTestUtils.setField(strategy, "usageLogService", usageLogService);
    }

    private ActorCharacter character(String stage) {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setProjectId(100L);
        ch.setName("测试娃");
        ch.setType("special");
        ch.setStage(stage);
        ch.setAge(8);
        ch.setLocation("村里");
        ch.setCurrentActivity("在院子里玩");
        return ch;
    }

    private DecisionContext ctx(NpcKind kind, String stage, String situation, String environmentText, Long gs) {
        return new DecisionContext(100L, character(stage), 1L, kind, "full", situation, gs, environmentText, false);
    }

    private DecisionContext ctxStimulus(NpcKind kind, String stage, String situation, Long gs) {
        return new DecisionContext(100L, character(stage), 1L, kind, "full", situation, gs, null, true);
    }

    // ==================== applies 过滤 ====================

    @Test
    void appliesRestrictedStagesOnly() {
        for (GrowthStage s : new GrowthStage[]{GrowthStage.TODDLER, GrowthStage.CHILD,
                GrowthStage.ADOLESCENT, GrowthStage.ELDER, GrowthStage.ANCIENT}) {
            assertTrue(strategy.applies(ctx(NpcKind.SPECIAL, s.name(), null, null, null)),
                    s.name() + " 适用");
        }
        assertFalse(strategy.applies(ctx(NpcKind.SPECIAL, GrowthStage.INFANT.name(), null, null, null)),
                "INFANT 不适用（链外状态机）");
        assertFalse(strategy.applies(ctx(NpcKind.SPECIAL, GrowthStage.YOUTH.name(), null, null, null)),
                "青年不适用");
        assertFalse(strategy.applies(ctx(NpcKind.SPECIAL, GrowthStage.PRIME.name(), null, null, null)),
                "壮年不适用");
        assertFalse(strategy.applies(ctx(NpcKind.SPECIAL, GrowthStage.MIDDLE.name(), null, null, null)),
                "中年不适用");
        assertFalse(strategy.applies(ctx(NpcKind.SPECIAL, GrowthStage.DECEASED.name(), null, null, null)),
                "已故不适用");
        assertFalse(strategy.applies(ctx(NpcKind.SPECIAL, null, null, null, null)),
                "null 阶段不适用");
        assertFalse(strategy.applies(null), "null 上下文不适用");
    }

    @Test
    void appliesDisabledByConfig() {
        growthProperties.setEnabled(false);
        assertFalse(strategy.applies(ctx(NpcKind.SPECIAL, GrowthStage.CHILD.name(), null, null, null)),
                "growth.enabled=false 时策略整体不适用（受限阶段回到既有链）");
    }

    @Test
    void appliesWorksForCommonKind() {
        assertTrue(strategy.applies(ctx(NpcKind.COMMON, GrowthStage.ELDER.name(), null, null, 1000L)),
                "普通链同样适用（双链接入）");
    }

    // ==================== 空闲接管 · 刺激让路 ====================

    @Test
    void decideSpecialIdleProducesStageBehaviorMap() {
        Optional<Map<String, Object>> d = strategy.decide(ctx(NpcKind.SPECIAL, GrowthStage.CHILD.name(),
                null, null, 14L * 3600));
        assertTrue(d.isPresent(), "特殊链空闲 → 阶段行为接管");
        Map<String, Object> m = d.get();
        assertEquals("start_action", m.get("actionType"), "ReAct 规范 actionType");
        assertNotNull(m.get("actionDetail"), "actionDetail 非空");
        assertFalse(String.valueOf(m.get("actionDetail")).isBlank());
        assertEquals(m.get("actionDetail"), m.get("action"), "旧键 action 与新键 actionDetail 一致");
        assertEquals(2, m.get("urgency"));
        assertTrue(String.valueOf(m.get("reason")).contains("发育阶段行为"), "reason 标注来源");
        assertNotNull(m.get("type"), "旧键 type 推断非空");
    }

    @Test
    void decideSpecialStimulusYieldsToChain() {
        // STIMULUS 即时通道（hasStimulus=true）→ 阶段门控让路，链继续（孩子也响应事件）
        Optional<Map<String, Object>> d = strategy.decide(ctxStimulus(NpcKind.SPECIAL, GrowthStage.CHILD.name(),
                "母亲在喊他回家吃饭", 14L * 3600));
        assertFalse(d.isPresent(), "特殊链有刺激（hasStimulus=true）→ 让路链继续");
    }

    @Test
    void decideSpecialPerceptionTextWithoutStimulusIsIdle() {
        // 引擎主决策：situation=感知组装文本（【当前所见】等）+ 行为多样性涟漪，但 hasStimulus=false
        // （感知不是刺激）→ 阶段门控接管——这是冒烟命中的关键语义
        Optional<Map<String, Object>> d = strategy.decide(ctx(NpcKind.SPECIAL, GrowthStage.CHILD.name(),
                "【当前所见】村口人来人往…\n【状态涟漪】\n· 今日心绪略偏「好奇探索」（意愿 62%）",
                null, 14L * 3600));
        assertTrue(d.isPresent(), "引擎主决策（感知文本但无刺激标记）→ 视为空闲，阶段行为接管");
    }

    @Test
    void decideCommonIdleProducesCrowdActionMap() {
        Optional<Map<String, Object>> d = strategy.decide(ctx(NpcKind.COMMON, GrowthStage.ELDER.name(),
                null, null, 10L * 3600));
        assertTrue(d.isPresent(), "普通链空闲 → 阶段行为接管");
        Map<String, Object> m = d.get();
        assertNotNull(m.get("actionText"), "CrowdAction 契约 actionText");
        assertFalse(String.valueOf(m.get("actionText")).isBlank());
        assertTrue(String.valueOf(m.get("reason")).contains("发育阶段行为"));
        CrowdDecisionParse.CrowdAction a = CrowdActionCodec.fromMap(m);
        assertNotNull(a, "可解码回 CrowdAction");
        assertEquals(m.get("actionText"), a.action());
    }

    @Test
    void decideCommonEnvironmentYieldsToChain() {
        Optional<Map<String, Object>> d = strategy.decide(ctx(NpcKind.COMMON, GrowthStage.ELDER.name(),
                null, "天色骤变，大雨将至", 10L * 3600));
        assertFalse(d.isPresent(), "普通链有环境刺激 → 让路链继续（老人也要躲雨）");
    }

    // ==================== AI 润色 ====================

    @Test
    void decidePolishAdoptsAiText() {
        growthProperties.getStageGating().setPolishRateSpecial(1.0);
        Optional<Map<String, Object>> d = strategy.decide(ctx(NpcKind.SPECIAL, GrowthStage.CHILD.name(),
                null, null, 14L * 3600));
        assertTrue(d.isPresent());
        assertEquals("蹲在门槛上逗蚂蚁，捡起一片落叶", d.get().get("actionDetail"), "润色文本被采纳");
        assertTrue(String.valueOf(d.get().get("reason")).contains("AI 润色"), "reason 标注润色");
        verify(aiChatGateway).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    // ==================== 批5 世界与叙事：段1A+1C+2D+2E 前缀（2026-10-10） ====================

    /**
     * 发育阶段行为润色（growth_stage_behavior）前置 V2.0 memoryChainContext 前缀
     * （段1A 世界观 + 段1C 角色卡 + 段2D 当前世界动态 + 段2E 当前状态）——
     * AI 把行为骨架展开为个人化行动时知道世界与自身处境。
     */
    @Test
    void decidePolish_prependsMemoryChainContextPrefix() {
        growthProperties.getStageGating().setPolishRateSpecial(1.0);
        com.holzyn.actor.domain.agent.context.AiContextInjector inj =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(inj.memoryChainContext(100L, "npc:3", 14L * 3600))
                .thenReturn("【世界观】青禾村志\n【我的身份与处境】测试娃（8 岁）\n"
                        + "【当前世界动态】青禾村历 0420年 秋 · 天气晴\n【当前状态】\n- 需求：安闲");
        ReflectionTestUtils.setField(strategy, "aiContextInjector", inj);
        org.mockito.ArgumentCaptor<com.holzyn.actor.ai.AiChatRequest> reqCaptor =
                org.mockito.ArgumentCaptor.forClass(com.holzyn.actor.ai.AiChatRequest.class);

        Optional<Map<String, Object>> d = strategy.decide(ctx(NpcKind.SPECIAL, GrowthStage.CHILD.name(),
                null, null, 14L * 3600));
        assertTrue(d.isPresent());
        verify(aiChatGateway).chatCompletion(anyLong(), anyLong(), org.mockito.ArgumentMatchers.isNull(),
                reqCaptor.capture());
        String user = String.valueOf(reqCaptor.getValue().messages().get(1).content());
        assertTrue(user.contains("【世界观】"), "应含段1A 世界观: " + user);
        assertTrue(user.contains("【我的身份与处境】"), "应含段1C 角色卡: " + user);
        assertTrue(user.contains("【当前世界动态】"), "应含段2D: " + user);
        assertTrue(user.contains("【当前状态】"), "应含段2E: " + user);
        assertTrue(user.contains("润色 Prompt"), "模板 prompt 保留: " + user);
    }

    /** 注入器未装配 → 润色请求不注入前缀（零阻断，行为与批5 前一致）。 */
    @Test
    void decidePolish_skipsPrefixWhenInjectorMissing() {
        growthProperties.getStageGating().setPolishRateSpecial(1.0);
        ReflectionTestUtils.setField(strategy, "aiContextInjector", null);
        org.mockito.ArgumentCaptor<com.holzyn.actor.ai.AiChatRequest> reqCaptor =
                org.mockito.ArgumentCaptor.forClass(com.holzyn.actor.ai.AiChatRequest.class);

        Optional<Map<String, Object>> d = strategy.decide(ctx(NpcKind.SPECIAL, GrowthStage.CHILD.name(),
                null, null, 14L * 3600));
        assertTrue(d.isPresent());
        verify(aiChatGateway).chatCompletion(anyLong(), anyLong(), org.mockito.ArgumentMatchers.isNull(),
                reqCaptor.capture());
        String user = String.valueOf(reqCaptor.getValue().messages().get(1).content());
        assertTrue(!user.contains("【世界观】"), "未装配 → 不注入前缀: " + user);
        assertTrue(user.contains("润色 Prompt"), "模板 prompt 保留: " + user);
    }

    @Test
    void decidePolishFailureFallsBackToSkeleton() {
        growthProperties.getStageGating().setPolishRateSpecial(1.0);
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(null, "test-model", 10, 5, 15, 1L));
        Optional<Map<String, Object>> d = strategy.decide(ctx(NpcKind.SPECIAL, GrowthStage.CHILD.name(),
                null, null, 14L * 3600));
        assertTrue(d.isPresent(), "AI 失败不阻断（回退骨架）");
        assertTrue(String.valueOf(d.get().get("actionDetail")).length() > 0, "骨架原文产出");
        assertTrue(String.valueOf(d.get().get("reason")).contains("程序化骨架"), "reason 标注骨架");
    }

    @Test
    void decidePolishForbiddenConflictFallsBackToSkeleton() {
        growthProperties.getStageGating().setPolishRateSpecial(1.0);
        // 硬门控禁止「独自远行」；AI 润色文本命中 → 越界回退骨架
        HardGateResult gate = new HardGateResult("npc:3", GrowthStage.CHILD.name(), 8, 0.5,
                java.util.List.of("DIALOGUE_OUTPUT", "SEMANTIC_KNOWLEDGE_WRITE"),
                java.util.List.of(new HardGateResult.ForbiddenItem("不能独自远行", "阶段硬约束（学龄期）")),
                java.util.List.of(), Map.of());
        when(growthGateService.gate(any(), any())).thenReturn(gate);
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult("独自远行去山上摘果子", "test-model", 10, 5, 15, 1L));
        Optional<Map<String, Object>> d = strategy.decide(ctx(NpcKind.SPECIAL, GrowthStage.CHILD.name(),
                null, null, 14L * 3600));
        assertTrue(d.isPresent(), "越界回退骨架而非阻断");
        assertFalse(String.valueOf(d.get().get("actionDetail")).contains("独自远行"),
                "越界文本被丢弃，骨架产出");
    }

    @Test
    void decidePolishRateZeroNeverCallsAi() {
        growthProperties.getStageGating().setPolishRateSpecial(0.0);
        growthProperties.getStageGating().setPolishRateCommon(0.0);
        strategy.decide(ctx(NpcKind.SPECIAL, GrowthStage.CHILD.name(), null, null, 14L * 3600));
        strategy.decide(ctx(NpcKind.COMMON, GrowthStage.ELDER.name(), null, null, 10L * 3600));
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    @Test
    void decidePolishBlankTextFallsBack() {
        growthProperties.getStageGating().setPolishRateSpecial(1.0);
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult("   ", "test-model", 10, 5, 15, 1L));
        Optional<Map<String, Object>> d = strategy.decide(ctx(NpcKind.SPECIAL, GrowthStage.CHILD.name(),
                null, null, 14L * 3600));
        assertTrue(d.isPresent(), "空白润色回退骨架");
        assertTrue(String.valueOf(d.get().get("reason")).contains("程序化骨架"));
    }

    // ==================== 时段解析 ====================

    @Test
    void decideUsesCtxGameSecondForPeriod() {
        // 10 点=昼 → reason 含「白天」
        Optional<Map<String, Object>> d = strategy.decide(ctx(NpcKind.SPECIAL, GrowthStage.CHILD.name(),
                null, null, 10L * 3600));
        assertTrue(d.isPresent());
        assertTrue(String.valueOf(d.get().get("reason")).contains("白天"), "10 点=昼");
    }

    @Test
    void decideFallsBackToEngineClockWhenGameSecondNull() {
        when(worldClockService.currentEngineGameSecond(100L)).thenReturn(6L * 3600);
        Optional<Map<String, Object>> d = strategy.decide(ctx(NpcKind.SPECIAL, GrowthStage.CHILD.name(),
                null, null, null));
        assertTrue(d.isPresent(), "gameSecond null → 引擎时钟兜底（6 点=晨）");
        assertTrue(String.valueOf(d.get().get("reason")).contains("清晨"));
    }

    // ==================== 异常隔离 ====================

    @Test
    void decideToleratesMissingDependencies() {
        strategy = new DevelopmentalGatingStrategy(); // 不装配任何依赖
        Optional<Map<String, Object>> d = strategy.decide(ctx(NpcKind.SPECIAL, GrowthStage.CHILD.name(),
                null, null, 10L * 3600));
        assertTrue(d.isPresent(), "依赖全缺失 → 程序化骨架照常产出（零阻断）");
        assertTrue(String.valueOf(d.get().get("actionDetail")).length() > 0);
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
    }
}
