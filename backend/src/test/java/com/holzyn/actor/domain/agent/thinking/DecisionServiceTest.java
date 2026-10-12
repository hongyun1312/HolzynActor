package com.holzyn.actor.domain.agent.thinking;

import com.holzyn.actor.domain.npc.profile.entity.ActorAgentProfile;
import com.holzyn.actor.domain.agent.brain.service.BrainProfileService;
import com.holzyn.actor.domain.agent.perception.PerceptionService;
import com.holzyn.actor.domain.growth.persona.PersonaService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.character.service.PromptService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 思考组件（C2 收编 ActionEngine.generateDecision）单元测试（domain/agent/thinking，同包）。
 * <p>O4 Prompt 工程（2026-09-21）重写：决策输出契约改为 ReAct 新契约（&lt;decision&gt; 标签内
 * action_type 枚举 continue/start_action/move_to/speak_to/wait + reasoning/action_detail/priority/
 * expected_duration_minutes），程序化兜底语义=「继续当前行为」（用户决策：continue 作为兜底）。</p>
 * <p>职责：验证——决策 Schema 校验（静态，新契约）、程序化兜底（静态，continue 语义）、
 * brainProfile 门控（programmatic 档 0 AI 成本直接程序化）、full 档 AI 决策链（成功走 Schema 校验）、
 * AI 失败程序化兜底（continue）。</p>
 */
class DecisionServiceTest {

    private AiSpringChatGateway aiChatGateway;
    private PromptService promptService;
    private BrainProfileService brainProfileService;
    private DecisionService service;

    @BeforeEach
    void setUp() {
        aiChatGateway = Mockito.mock(AiSpringChatGateway.class);
        promptService = Mockito.mock(PromptService.class);
        UsageLogService usageLogService = Mockito.mock(UsageLogService.class);
        PerceptionService perceptionService = Mockito.mock(PerceptionService.class);
        WorldClockService worldClockService = Mockito.mock(WorldClockService.class);
        brainProfileService = Mockito.mock(BrainProfileService.class);
        // G1a：角色 facet 文本唯一来源是档案服务（@Autowired(required=false) 字段注入，单测手动回填）
        PersonaService personaService = Mockito.mock(PersonaService.class);
        // O6 G1a：角色卡仓库已删（单主表），构造器去掉 cardRepository
        service = new DecisionService(aiChatGateway, promptService, usageLogService,
                perceptionService, worldClockService, brainProfileService, new ObjectMapper());
        ReflectionTestUtils.setField(service, "personaService", personaService);
    }

    private ActorAgentProfile profile(String brainLevel) {
        ActorAgentProfile p = new ActorAgentProfile();
        p.setBrainLevel(brainLevel);
        return p;
    }

    private ActorCharacter character() {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setProjectId(100L);
        ch.setName("林安");
        ch.setImportance(4);
        ch.setCurrentActivity("在茶馆喝茶");
        return ch;
    }

    /** 构造新契约 JSON 节点（可自定义字段）。 */
    private ObjectNode decisionNode(String actionType, String actionDetail, String reasoning) {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("action_type", actionType);
        node.put("action_detail", actionDetail);
        node.put("reasoning", reasoning);
        node.put("priority", 4);
        node.put("expected_duration_minutes", 30);
        return node;
    }

    /**
     * 决策 Schema 校验（静态，新契约）：合法决策通过（action_type 枚举 + action_detail + reasoning）。
     */
    @Test
    void validateDecisionAcceptsValid() {
        ObjectNode node = decisionNode("move_to", "前往城东市场采购药材", "药材告急，需补货");
        node.put("target", "城东市场");
        Map<String, Object> d = DecisionService.validateDecision(node, new ObjectMapper());
        assertNotNull(d);
        assertEquals("move_to", d.get("actionType"));
        assertEquals("move", d.get("type"), "move_to 映射旧枚举 move");
        assertEquals(4, d.get("priority"));
        assertEquals(4, d.get("urgency"), "priority 映射旧键 urgency");
        assertEquals("前往城东市场采购药材", d.get("action"));
    }

    /**
     * 决策 Schema 校验（静态）：非法 action_type 枚举拒绝。
     */
    @Test
    void validateDecisionRejectsInvalidType() {
        ObjectNode node = decisionNode("teleport", "瞬移", "测试");
        assertNull(DecisionService.validateDecision(node, new ObjectMapper()));
    }

    /**
     * 决策 Schema 校验（静态，O4 continue 语义）：continue 也要求 action_detail 描述在继续什么。
     */
    @Test
    void validateDecisionRejectsBlankActionDetail() {
        ObjectNode node = decisionNode("start_action", "", "测试");
        assertNull(DecisionService.validateDecision(node, new ObjectMapper()));
    }

    /**
     * 决策 Schema 校验（静态，M4 时间感行动适配）：wait action_type / wait_minutes 正整数 →
     * 决策带 waitMinutes；非正整数/缺失 → 不携带（立即执行语义）。
     */
    @Test
    void validateDecisionParsesWaitMinutes() {
        ObjectNode node = decisionNode("wait", "等集市散场再回河畔", "在等人");
        node.put("target", "河畔");
        node.put("wait_minutes", 30);
        Map<String, Object> d = DecisionService.validateDecision(node, new ObjectMapper());
        assertNotNull(d);
        assertEquals(30, d.get("waitMinutes"), "wait_minutes 正整数 → waitMinutes 字段");
        assertEquals("rest", d.get("type"), "wait 映射旧枚举 rest（等待类计划）");
    }

    @Test
    void validateDecisionIgnoresInvalidWaitMinutes() {
        ObjectNode node = decisionNode("start_action", "去河边打水", "取水");
        node.put("target", "河畔");
        node.put("wait_minutes", 0); // 非正整数 → 忽略（立即执行）
        Map<String, Object> d = DecisionService.validateDecision(node, new ObjectMapper());
        assertNotNull(d);
        assertFalse(d.containsKey("waitMinutes"), "wait_minutes=0 不携带 waitMinutes");

        ObjectNode node2 = decisionNode("start_action", "去河边打水", "取水");
        node2.put("target", "河畔");
        Map<String, Object> d2 = DecisionService.validateDecision(node2, new ObjectMapper());
        assertFalse(d2.containsKey("waitMinutes"), "缺失 wait_minutes 不携带");
    }

    /**
     * O2 P1-1 前瞻候选捕获（静态，新契约适配）：candidates 数组 → 决策 Map 携带 foresightCandidates；
     * 缺失/空数组 → 不携带（ForesightService 记录 success=false 依据）。
     */
    @Test
    void validateDecisionCapturesForesightCandidates() {
        ObjectNode node = decisionNode("start_action", "去集市摊位买药", "备药");
        node.put("target", "集市摊位");
        var candidates = node.putArray("candidates");
        candidates.addObject().put("action", "先到集市摊位买药").put("consequence", "药到手能治病");
        candidates.addObject().put("action", "改去药庐").put("consequence", "更贵但稳妥");
        Map<String, Object> d = DecisionService.validateDecision(node, new ObjectMapper());
        assertNotNull(d);
        assertTrue(d.containsKey("foresightCandidates"));
        assertEquals(2, ((java.util.List<?>) d.get("foresightCandidates")).size());

        // 无候选 → 不携带
        ObjectNode node2 = decisionNode("start_action", "去集市摊位买药", "备药");
        node2.put("target", "集市摊位");
        Map<String, Object> d2 = DecisionService.validateDecision(node2, new ObjectMapper());
        assertNotNull(d2);
        assertFalse(d2.containsKey("foresightCandidates"), "无 candidates 不携带");
    }

    /**
     * 程序化兜底（静态，O4 continue 语义）：返回 actionType=continue 的「继续当前行为」决策。
     */
    @Test
    void programmaticFallbackProducesReasonableDecision() {
        Map<String, Object> d = DecisionService.programmaticFallback("张三", 5, "清晨巡视领地");
        assertEquals("continue", d.get("actionType"), "程序化兜底=continue（继续当前行为）");
        assertEquals("custom", d.get("type"));
        assertTrue(String.valueOf(d.get("action")).contains("继续"));
        assertNotNull(d.get("reason"));
    }

    /**
     * brainProfile 门控：programmatic 档（0 AI 成本）直接程序化决策（continue 语义），不调用 AI 路由。
     */
    @Test
    void programmaticBrainLevelSkipsAi() {
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(profile("programmatic"));

        Map<String, Object> d = service.generateDecision(character(), 1L, "当前情境");

        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
        assertEquals("continue", d.get("actionType"), "programmatic 档程序化兜底=continue 当前行为");
    }

    /**
     * full 档 AI 决策链：AI 返回 <decision> 标签合法 JSON → Schema 校验通过返回决策。
     */
    @Test
    void fullBrainLevelUsesAiAndValidates() {
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(profile("full"));
        when(promptService.buildActionGenPrompt(anyLong(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn("行动生成 Prompt");
        String okJson = "<decision>{\"reasoning\":\"药材告急，需补货\",\"action_type\":\"move_to\","
                + "\"action_detail\":\"前往城东市场采购药材\",\"target\":\"城东市场\",\"priority\":5,"
                + "\"expected_duration_minutes\":30}</decision>";
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(okJson, "test-model", 10, 5, 15, 1L));

        Map<String, Object> d = service.generateDecision(character(), 1L, "当前情境");

        assertNotNull(d);
        assertEquals("move_to", d.get("actionType"));
        assertEquals("move", d.get("type"));
        assertEquals(5, d.get("urgency"));
    }

    /**
     * full 档 AI 失败（返回非 JSON）→ 重试后用程序化兜底（continue 当前行为，功能永不中断）。
     */
    @Test
    void fullBrainLevelFallsBackToProgrammaticOnAiFailure() {
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(profile("full"));
        when(promptService.buildActionGenPrompt(anyLong(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn("行动生成 Prompt");
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult("不是JSON", "test-model", 5, 2, 7, 1L));

        Map<String, Object> d = service.generateDecision(character(), 1L, "当前情境");

        assertEquals("continue", d.get("actionType"), "AI 失败走程序化兜底=continue 当前行为");
        assertTrue(String.valueOf(d.get("action")).contains("继续"), "兜底动作=继续当前行为（在茶馆喝茶）");
    }

    // ==================== 生理需求危险区前置检查（2026-10-02 生理需求耦合轮） ====================

    /** 构造危险区程序化决策（模拟 PhysioNeedService.dangerDecision 产物）。 */
    private Map<String, Object> dangerDecisionMap() {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("actionType", "move_to");
        d.put("actionDetail", "前往云来茶馆用餐");
        d.put("reasoning", "饿得发慌了，必须马上找东西吃");
        d.put("priority", 5);
        d.put("expectedDurationMinutes", 30);
        d.put("target", "云来茶馆");
        d.put("type", "move");
        d.put("action", "前往云来茶馆用餐");
        d.put("reason", "饿得发慌了，必须马上找东西吃");
        d.put("urgency", 5);
        d.put("duration", 30);
        return d;
    }

    /**
     * 生理需求危险区前置检查：危险区命中 → 程序化强制（0 AI），
     * 先于脑力门控与 AI 决策链（快饿死的人不会因决策系统失效而继续发呆）。
     */
    @Test
    void physioDangerForcesDecisionBeforeBrainLevel() {
        com.holzyn.actor.domain.npc.physio.PhysioNeedService physio =
                Mockito.mock(com.holzyn.actor.domain.npc.physio.PhysioNeedService.class);
        when(physio.dangerDecision(anyLong(), any())).thenReturn(dangerDecisionMap());
        ReflectionTestUtils.setField(service, "physioNeedService", physio);
        // 即使 full 档（会走 AI），危险区命中也必须先返回程序化强制
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(profile("full"));

        Map<String, Object> d = service.generateDecision(character(), 1L, "当前情境");

        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
        assertEquals("move_to", d.get("actionType"));
        assertEquals("前往云来茶馆用餐", d.get("action"));
        assertEquals(5, d.get("priority"), "危险区 priority=5（健康告急优先）");
    }

    /**
     * 生理需求危险区：无危险 → 原决策链照常（AI 正常调用）。
     */
    @Test
    void physioDangerAbsentFallsThroughToAi() {
        com.holzyn.actor.domain.npc.physio.PhysioNeedService physio =
                Mockito.mock(com.holzyn.actor.domain.npc.physio.PhysioNeedService.class);
        when(physio.dangerDecision(anyLong(), any())).thenReturn(null);
        ReflectionTestUtils.setField(service, "physioNeedService", physio);
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(profile("full"));
        when(promptService.buildActionGenPrompt(anyLong(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn("行动生成 Prompt");
        String okJson = "<decision>{\"reasoning\":\"药材告急，需补货\",\"action_type\":\"move_to\","
                + "\"action_detail\":\"前往城东市场采购药材\",\"target\":\"城东市场\",\"priority\":5,"
                + "\"expected_duration_minutes\":30}</decision>";
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(okJson, "test-model", 10, 5, 15, 1L));

        Map<String, Object> d = service.generateDecision(character(), 1L, "当前情境");

        assertNotNull(d);
        assertEquals("move_to", d.get("actionType"), "无危险 → AI 决策链照常");
    }

    // ==================== BrainStrategy 可插拔决策策略链（P2-3） ====================

    /** 构造链产物（命中策略/优先级/是否后处理可配）。 */
    private com.holzyn.actor.domain.agent.brain.strategy.DecisionOutcome outcome(
            String action, String hitStrategy, int priority, boolean postProcess) {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("actionType", "start_action");
        d.put("action", action);
        d.put("reasoning", "链产物");
        return new com.holzyn.actor.domain.agent.brain.strategy.DecisionOutcome(
                d, hitStrategy, priority, postProcess, java.util.List.of(hitStrategy + "✓命中"));
    }

    /** router 装配 → 走策略链（legacy 内联管线不执行，AI 不被调用），命中后处理后返回。 */
    @Test
    void routerWiredRunsChainAndSkipsLegacy() {
        com.holzyn.actor.domain.agent.brain.strategy.BrainStrategyRouter router =
                Mockito.mock(com.holzyn.actor.domain.agent.brain.strategy.BrainStrategyRouter.class);
        when(router.decide(any())).thenReturn(outcome("去茶馆坐坐", "space_choice", 80, true));
        ReflectionTestUtils.setField(service, "brainStrategyRouter", router);
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(profile("full"));

        Map<String, Object> d = service.generateDecision(character(), 1L, "当前情境");

        assertEquals("去茶馆坐坐", d.get("action"), "链产物作为决策返回");
        verify(router).decide(any());
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    /** 链命中程序化兜底（postProcess=false）→ 直接返回，不走后处理。 */
    @Test
    void routerWiredProgrammaticOutcomeSkipsPostProcess() {
        com.holzyn.actor.domain.agent.brain.strategy.BrainStrategyRouter router =
                Mockito.mock(com.holzyn.actor.domain.agent.brain.strategy.BrainStrategyRouter.class);
        when(router.decide(any())).thenReturn(
                outcome("继续在茶馆喝茶", "programmatic_fallback", 0, false));
        ReflectionTestUtils.setField(service, "brainStrategyRouter", router);
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(profile("full"));

        Map<String, Object> d = service.generateDecision(character(), 1L, "当前情境");

        assertEquals("继续在茶馆喝茶", d.get("action"));
        assertEquals("start_action", d.get("actionType"), "兜底产物原样返回（无后处理改写）");
    }

    /** 链空 → 回退 legacy 内联管线（AI 正常调用）。 */
    @Test
    void routerChainEmptyFallsBackToLegacy() {
        com.holzyn.actor.domain.agent.brain.strategy.BrainStrategyRouter router =
                Mockito.mock(com.holzyn.actor.domain.agent.brain.strategy.BrainStrategyRouter.class);
        when(router.decide(any())).thenReturn(null);
        ReflectionTestUtils.setField(service, "brainStrategyRouter", router);
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(profile("full"));
        when(promptService.buildActionGenPrompt(anyLong(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn("行动生成 Prompt");
        String okJson = "<decision>{\"reasoning\":\"药材告急，需补货\",\"action_type\":\"move_to\","
                + "\"action_detail\":\"前往城东市场采购药材\",\"target\":\"城东市场\",\"priority\":5,"
                + "\"expected_duration_minutes\":30}</decision>";
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(okJson, "test-model", 10, 5, 15, 1L));

        Map<String, Object> d = service.generateDecision(character(), 1L, "当前情境");

        assertEquals("move_to", d.get("actionType"), "链空回退 legacy AI 决策链");
        verify(aiChatGateway).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    /**
     * 真实链集成（programmatic 档）：真实 Router + 真实 PhysioDangerStrategy +
     * ProgrammaticFallbackStrategy——无危险 → 兜底 continue（0 AI）；有危险 →
     * 链首危险强制（postProcess 后返回）。
     */
    @Test
    void realRouterProgrammaticChainDangerAndFallback() {
        com.holzyn.actor.domain.npc.physio.PhysioNeedService physio =
                Mockito.mock(com.holzyn.actor.domain.npc.physio.PhysioNeedService.class);
        when(physio.dangerDecision(anyLong(), any())).thenReturn(null);
        com.holzyn.actor.domain.agent.brain.strategy.PhysioDangerStrategy danger =
                new com.holzyn.actor.domain.agent.brain.strategy.PhysioDangerStrategy();
        ReflectionTestUtils.setField(danger, "physioNeedService", physio);
        com.holzyn.actor.domain.agent.brain.strategy.ProgrammaticFallbackStrategy fallback =
                new com.holzyn.actor.domain.agent.brain.strategy.ProgrammaticFallbackStrategy();
        com.holzyn.actor.domain.agent.brain.strategy.BrainStrategyRouter router =
                new com.holzyn.actor.domain.agent.brain.strategy.BrainStrategyRouter(
                        java.util.List.of(danger, fallback));
        ReflectionTestUtils.setField(service, "brainStrategyRouter", router);
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(profile("programmatic"));

        // 无危险 → 链尾程序化兜底（continue，0 AI）
        Map<String, Object> d = service.generateDecision(character(), 1L, "当前情境");
        assertEquals("continue", d.get("actionType"), "programmatic 档链尾兜底=continue 当前行为");
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());

        // 有危险 → 链首危险强制（postProcess 后返回，priority=5）
        when(physio.dangerDecision(anyLong(), any())).thenReturn(dangerDecisionMap());
        d = service.generateDecision(character(), 1L, "当前情境");
        assertEquals("move_to", d.get("actionType"), "危险区命中链首强制");
        assertEquals(5, d.get("priority"));
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    /**
     * 真实链集成（full 档）：真实 Router + 全策略（危险未命中→空间未装配未命中→
     * AI 深思命中）——链式责任按优先级依次尝试，AI 深思产出决策。
     */
    @Test
    void realRouterFullChainDeliberateWins() {
        com.holzyn.actor.domain.npc.physio.PhysioNeedService physio =
                Mockito.mock(com.holzyn.actor.domain.npc.physio.PhysioNeedService.class);
        when(physio.dangerDecision(anyLong(), any())).thenReturn(null);
        com.holzyn.actor.domain.agent.brain.strategy.PhysioDangerStrategy danger =
                new com.holzyn.actor.domain.agent.brain.strategy.PhysioDangerStrategy();
        ReflectionTestUtils.setField(danger, "physioNeedService", physio);
        com.holzyn.actor.domain.agent.brain.strategy.SpaceChoiceStrategy space =
                new com.holzyn.actor.domain.agent.brain.strategy.SpaceChoiceStrategy();
        ReflectionTestUtils.setField(space, "decisionService", service);
        com.holzyn.actor.domain.agent.brain.strategy.DeliberateStrategy deliberate =
                new com.holzyn.actor.domain.agent.brain.strategy.DeliberateStrategy();
        ReflectionTestUtils.setField(deliberate, "decisionService", service);
        com.holzyn.actor.domain.agent.brain.strategy.ProgrammaticFallbackStrategy fallback =
                new com.holzyn.actor.domain.agent.brain.strategy.ProgrammaticFallbackStrategy();
        com.holzyn.actor.domain.agent.brain.strategy.BrainStrategyRouter router =
                new com.holzyn.actor.domain.agent.brain.strategy.BrainStrategyRouter(
                        java.util.List.of(danger, space, deliberate, fallback));
        ReflectionTestUtils.setField(service, "brainStrategyRouter", router);
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(profile("full"));
        when(promptService.buildActionGenPrompt(anyLong(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn("行动生成 Prompt");
        String okJson = "<decision>{\"reasoning\":\"药材告急，需补货\",\"action_type\":\"move_to\","
                + "\"action_detail\":\"前往城东市场采购药材\",\"target\":\"城东市场\",\"priority\":5,"
                + "\"expected_duration_minutes\":30}</decision>";
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(okJson, "test-model", 10, 5, 15, 1L));

        Map<String, Object> d = service.generateDecision(character(), 1L, "当前情境");

        assertEquals("move_to", d.get("actionType"), "危险/空间未命中 → AI 深思产出");
        assertEquals("前往城东市场采购药材", d.get("action"));
        verify(aiChatGateway).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    /**
     * 真实链集成（O6 发育演化）：受限阶段（CHILD）+ 真实 stage_gating 策略 →
     * 空闲决策命中阶段行为接管（0 AI 程序化骨架——策略未装配润色依赖与 growthProperties，
     * 润色概率 0；产出仍走调用方后处理）。
     */
    @Test
    void realRouterChainStageGatingWinsForRestrictedStage() {
        com.holzyn.actor.domain.npc.physio.PhysioNeedService physio =
                Mockito.mock(com.holzyn.actor.domain.npc.physio.PhysioNeedService.class);
        when(physio.dangerDecision(anyLong(), any())).thenReturn(null);
        com.holzyn.actor.domain.agent.brain.strategy.PhysioDangerStrategy danger =
                new com.holzyn.actor.domain.agent.brain.strategy.PhysioDangerStrategy();
        ReflectionTestUtils.setField(danger, "physioNeedService", physio);
        com.holzyn.actor.domain.agent.brain.strategy.DevelopmentalGatingStrategy gating =
                new com.holzyn.actor.domain.agent.brain.strategy.DevelopmentalGatingStrategy();
        com.holzyn.actor.domain.agent.brain.strategy.ProgrammaticFallbackStrategy fallback =
                new com.holzyn.actor.domain.agent.brain.strategy.ProgrammaticFallbackStrategy();
        com.holzyn.actor.domain.agent.brain.strategy.BrainStrategyRouter router =
                new com.holzyn.actor.domain.agent.brain.strategy.BrainStrategyRouter(
                        java.util.List.of(danger, gating, fallback));
        ReflectionTestUtils.setField(service, "brainStrategyRouter", router);
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(profile("full"));
        ActorCharacter ch = character();
        ch.setStage("CHILD");

        Map<String, Object> d = service.generateDecision(ch, 1L, null);

        assertEquals("start_action", d.get("actionType"), "受限阶段空闲 → 阶段行为接管（非 AI 深思）");
        assertNotNull(d.get("actionDetail"), "阶段行为文本产出");
        assertTrue(String.valueOf(d.get("reason")).contains("发育阶段行为"), "reason 标注阶段行为来源");
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    /**
     * 真实链集成（O6 发育演化）：成年阶段（YOUTH）不受阶段门控影响——
     * stage_gating 不适用，链继续到 AI 深思（行为与 P2-3 前一致）。
     */
    @Test
    void realRouterChainStageGatingSkipsAdult() {
        com.holzyn.actor.domain.npc.physio.PhysioNeedService physio =
                Mockito.mock(com.holzyn.actor.domain.npc.physio.PhysioNeedService.class);
        when(physio.dangerDecision(anyLong(), any())).thenReturn(null);
        com.holzyn.actor.domain.agent.brain.strategy.PhysioDangerStrategy danger =
                new com.holzyn.actor.domain.agent.brain.strategy.PhysioDangerStrategy();
        ReflectionTestUtils.setField(danger, "physioNeedService", physio);
        com.holzyn.actor.domain.agent.brain.strategy.DevelopmentalGatingStrategy gating =
                new com.holzyn.actor.domain.agent.brain.strategy.DevelopmentalGatingStrategy();
        com.holzyn.actor.domain.agent.brain.strategy.DeliberateStrategy deliberate =
                new com.holzyn.actor.domain.agent.brain.strategy.DeliberateStrategy();
        ReflectionTestUtils.setField(deliberate, "decisionService", service);
        com.holzyn.actor.domain.agent.brain.strategy.ProgrammaticFallbackStrategy fallback =
                new com.holzyn.actor.domain.agent.brain.strategy.ProgrammaticFallbackStrategy();
        com.holzyn.actor.domain.agent.brain.strategy.BrainStrategyRouter router =
                new com.holzyn.actor.domain.agent.brain.strategy.BrainStrategyRouter(
                        java.util.List.of(danger, gating, deliberate, fallback));
        ReflectionTestUtils.setField(service, "brainStrategyRouter", router);
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(profile("full"));
        ActorCharacter ch = character();
        ch.setStage("YOUTH");
        when(promptService.buildActionGenPrompt(anyLong(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn("行动生成 Prompt");
        String okJson = "<decision>{\"reasoning\":\"日常巡看\",\"action_type\":\"start_action\","
                + "\"action_detail\":\"在村里巡看一圈\",\"target\":\"\",\"priority\":3,"
                + "\"expected_duration_minutes\":30}</decision>";
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(okJson, "test-model", 10, 5, 15, 1L));

        Map<String, Object> d = service.generateDecision(ch, 1L, null);

        assertEquals("start_action", d.get("actionType"));
        assertEquals("在村里巡看一圈", d.get("action"), "成年阶段走 AI 深思（不受阶段门控影响）");
        verify(aiChatGateway).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    // ==================== 主动记忆检索（P1 ②-1，2026-10-03） ====================

    /**
     * 决策全局自问：proactiveRetrievalService 命中 → 【主动想起】段经 AiCallContext 持有 →
     * AI 深思走 buildActionGenPrompt 6 参重载注入 {{proactive_memory}} → 决策结束清理。
     */
    @Test
    void deliberateInjectsProactiveSegmentIntoPrompt() throws Exception {
        com.holzyn.actor.domain.agent.memory.ProactiveRetrievalService pro =
                Mockito.mock(com.holzyn.actor.domain.agent.memory.ProactiveRetrievalService.class);
        when(pro.maybeRetrieve(eq(100L), eq("npc:3"), eq(1L), anyString()))
                .thenReturn(new com.holzyn.actor.domain.agent.memory.ProactiveRetrievalService.ProactiveResult(
                        "【主动想起】此刻你主动想起…", List.of(
                                new com.holzyn.actor.domain.agent.memory.ProactiveRetrievalService.Hit("mem", 11L, "上回交易的事"))));
        ReflectionTestUtils.setField(service, "proactiveRetrievalService", pro);
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(profile("full"));
        when(promptService.buildActionGenPrompt(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("行动生成 Prompt（含主动想起段）");
        String okJson = "<decision>{\"reasoning\":\"想起上次交易\",\"action_type\":\"start_action\","
                + "\"action_detail\":\"再去张屠户铺子看看\",\"target\":\"张屠户铺子\",\"priority\":3,"
                + "\"expected_duration_minutes\":30}</decision>";
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(okJson, "test-model", 10, 5, 15, 1L));

        Map<String, Object> d = service.generateDecision(character(), 1L, "准备去谈米价");

        assertNotNull(d);
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(promptService).buildActionGenPrompt(anyLong(), anyLong(), anyString(), anyString(), anyString(),
                captor.capture());
        assertEquals("【主动想起】此刻你主动想起…", captor.getValue(), "主动段注入 {{proactive_memory}}");
        // 决策结束：AiCallContext 主动段已清理（防虚拟线程复用泄漏）
        assertNull(com.holzyn.actor.domain.debug.service.AiCallContext.readProactiveSegment(),
                "generateDecision finally 清理主动段");
    }

    /**
     * 决策结束兜底清理：即使自问未命中（无段持有），generateDecision finally 也清除
     * 可能残留的主动段（防线程复用泄漏）。
     */
    @Test
    void generateDecisionClearsProactiveSegmentInFinally() {
        // 预置残留主动段（模拟上一轮决策异常残留）
        com.holzyn.actor.domain.debug.service.AiCallContext.holdProactiveSegment("【主动想起】残留");
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(profile("programmatic"));

        Map<String, Object> d = service.generateDecision(character(), 1L, "当前情境");

        assertEquals("continue", d.get("actionType"));
        assertNull(com.holzyn.actor.domain.debug.service.AiCallContext.readProactiveSegment(),
                "finally 清理残留主动段");
    }

    // ==================== MUSE 策略切换（P1 ①-3，2026-10-02） ====================

    /**
     * 策略链内 FamiliarityStrategy 命中 → 【策略偏好】段经 AiCallContext 持有 →
     * AI 深思走 buildActionGenPrompt 7 参重载注入 {{strategy_preference}} → 决策结束清理。
     */
    @Test
    void deliberateInjectsStrategyPreferenceIntoPrompt() throws Exception {
        com.holzyn.actor.domain.debug.service.AiCallContext.holdStrategyPreference(
                "【策略偏好】面对「农事耕作」这类事你自认把握不大，更倾向谨慎行事");
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(profile("full"));
        when(promptService.buildActionGenPrompt(anyLong(), anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn("行动生成 Prompt（含策略偏好段）");
        String okJson = "<decision>{\"reasoning\":\"没把握先稳一稳\",\"action_type\":\"start_action\","
                + "\"action_detail\":\"先到田头仔细看看再说\",\"target\":\"\",\"priority\":2,"
                + "\"expected_duration_minutes\":30}</decision>";
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(okJson, "test-model", 10, 5, 15, 1L));

        Map<String, Object> d = service.generateDecision(character(), 1L, "田里的庄稼遭了虫害");

        assertNotNull(d);
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(promptService).buildActionGenPrompt(anyLong(), anyLong(), anyString(), anyString(), anyString(),
                anyString(), captor.capture());
        assertEquals("【策略偏好】面对「农事耕作」这类事你自认把握不大，更倾向谨慎行事",
                captor.getValue(), "策略偏好段注入 {{strategy_preference}}");
        // 决策结束：AiCallContext 策略偏好段已清理（防虚拟线程复用泄漏）
        assertNull(com.holzyn.actor.domain.debug.service.AiCallContext.readStrategyPreference(),
                "generateDecision finally 清理策略偏好段");
    }

    /**
     * 决策结束兜底清理：即使无 MUSE 段持有，generateDecision finally 也清除可能残留的
     * 策略偏好段（防线程复用泄漏）。
     */
    @Test
    void generateDecisionClearsStrategyPreferenceInFinally() {
        // 预置残留策略偏好段（模拟上一轮决策异常残留）
        com.holzyn.actor.domain.debug.service.AiCallContext.holdStrategyPreference("【策略偏好】残留");
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(profile("programmatic"));

        Map<String, Object> d = service.generateDecision(character(), 1L, "当前情境");

        assertEquals("continue", d.get("actionType"));
        assertNull(com.holzyn.actor.domain.debug.service.AiCallContext.readStrategyPreference(),
                "finally 清理残留策略偏好段");
    }

    /**
     * programmatic 档 0 AI 语义：自问环节不执行（maybeRetrieve 不被调用）。
     */
    @Test
    void programmaticSkipsProactiveRetrieval() {
        com.holzyn.actor.domain.agent.memory.ProactiveRetrievalService pro =
                Mockito.mock(com.holzyn.actor.domain.agent.memory.ProactiveRetrievalService.class);
        ReflectionTestUtils.setField(service, "proactiveRetrievalService", pro);
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(profile("programmatic"));

        service.generateDecision(character(), 1L, "当前情境");

        Mockito.verify(pro, never()).maybeRetrieve(anyLong(), anyString(), anyLong(), anyString());
        Mockito.verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    // ==================== 潜在世界预测注入（P1 ③-2，2026-10-03） ====================

    /**
     * WorldOutlookService 命中 → 【未来一天世界走向】段经 action_gen 8 参重载
     * {{world_outlook}} 注入 AI 深思。
     */
    @Test
    void deliberateInjectsWorldOutlookIntoPrompt() throws Exception {
        com.holzyn.actor.domain.world.service.WorldOutlookService outlook =
                Mockito.mock(com.holzyn.actor.domain.world.service.WorldOutlookService.class);
        when(outlook.outlookSegment(100L)).thenReturn("【未来一天世界走向】明日粮价恐再涨，集市或有人抢购");
        ReflectionTestUtils.setField(service, "worldOutlookService", outlook);
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(profile("full"));
        when(promptService.buildActionGenPrompt(anyLong(), anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn("行动生成 Prompt（含世界走向段）");
        String okJson = "<decision>{\"reasoning\":\"粮价要涨，先备粮\",\"action_type\":\"start_action\","
                + "\"action_detail\":\"去粮铺多买些米\",\"target\":\"粮铺\",\"priority\":3,"
                + "\"expected_duration_minutes\":30}</decision>";
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(okJson, "test-model", 10, 5, 15, 1L));

        Map<String, Object> d = service.generateDecision(character(), 1L, "集市上米价又涨了");

        assertNotNull(d);
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(promptService).buildActionGenPrompt(anyLong(), anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString(), captor.capture());
        assertEquals("【未来一天世界走向】明日粮价恐再涨，集市或有人抢购", captor.getValue(),
                "世界走向段注入 {{world_outlook}}");
    }

    /**
     * 世界走向服务未装配（null）→ 走既有旧签名（行为零变化，不抛异常）。
     */
    @Test
    void deliberateWithoutWorldOutlookFallsBackToOldSignature() throws Exception {
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(profile("full"));
        when(promptService.buildActionGenPrompt(anyLong(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn("行动生成 Prompt");
        String okJson = "<decision>{\"reasoning\":\"正常决策\",\"action_type\":\"continue\","
                + "\"action_detail\":\"继续手头的事\",\"target\":\"\",\"priority\":1,"
                + "\"expected_duration_minutes\":30}</decision>";
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(okJson, "test-model", 10, 5, 15, 1L));

        Map<String, Object> d = service.generateDecision(character(), 1L, "普通情境");

        assertNotNull(d);
        verify(promptService).buildActionGenPrompt(anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    /**
     * WorldOutlookService 异常 → fail-open 零阻断（决策照常走旧签名）。
     */
    @Test
    void deliberateWorldOutlookFailureIsFailOpen() throws Exception {
        com.holzyn.actor.domain.world.service.WorldOutlookService outlook =
                Mockito.mock(com.holzyn.actor.domain.world.service.WorldOutlookService.class);
        when(outlook.outlookSegment(100L)).thenThrow(new RuntimeException("世界走向服务故障"));
        ReflectionTestUtils.setField(service, "worldOutlookService", outlook);
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(profile("full"));
        when(promptService.buildActionGenPrompt(anyLong(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn("行动生成 Prompt");
        String okJson = "<decision>{\"reasoning\":\"正常决策\",\"action_type\":\"continue\","
                + "\"action_detail\":\"继续手头的事\",\"target\":\"\",\"priority\":1,"
                + "\"expected_duration_minutes\":30}</decision>";
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(okJson, "test-model", 10, 5, 15, 1L));

        Map<String, Object> d = service.generateDecision(character(), 1L, "普通情境");

        assertNotNull(d, "世界走向故障不阻断决策");
        verify(promptService).buildActionGenPrompt(anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    // ==================== 批3 决策与感知：段2D/2E 前置（2026-10-08） ====================

    /**
     * AI 深思情境缺【当前状态】段（STIMULUS 个性刺激情境）→ 前置 段2D 当前世界动态 +
     * 段2E 当前状态（注入器 decisionStatePrefix），action_gen 情境含两段。
     */
    @Test
    void deliberateEnrichesStimulusSituationWithSegment2DAnd2E() throws Exception {
        com.holzyn.actor.domain.agent.context.AiContextInjector inj =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(inj.decisionStatePrefix(eq(100L), eq("npc:3"), any()))
                .thenReturn("【当前世界动态】青禾村历 0420年05月15日 06时00分 · 夏季\n"
                        + "【当前状态】\n- 需求：饥饿 68/100（略饿）");
        ReflectionTestUtils.setField(service, "aiContextInjector", inj);
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(profile("full"));
        when(promptService.buildActionGenPrompt(anyLong(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn("行动生成 Prompt");
        String okJson = "<decision>{\"reasoning\":\"刺激判断\",\"action_type\":\"start_action\","
                + "\"action_detail\":\"去看看发生了什么\",\"target\":\"村口\",\"priority\":3,"
                + "\"expected_duration_minutes\":15}</decision>";
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(okJson, "test-model", 10, 5, 15, 1L));

        Map<String, Object> d = service.generateDecision(character(), 1L, "【角色个性】沉稳守礼\n【本次刺激】村口传来骚动", true);

        assertNotNull(d);
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(promptService).buildActionGenPrompt(anyLong(), anyLong(), anyString(),
                captor.capture(), anyString());
        String situation = captor.getValue();
        assertTrue(situation.contains("【当前世界动态】"), "应前置段2D: " + situation);
        assertTrue(situation.contains("【当前状态】"), "应前置段2E: " + situation);
        assertTrue(situation.contains("【本次刺激】"), "原刺激情境保留: " + situation);
        assertTrue(situation.indexOf("【当前世界动态】") < situation.indexOf("【本次刺激】"),
                "段2D 应在刺激之前");
    }

    /**
     * 周期感知情境已含【当前状态】段 → 不重复注入（段2D/2E 防双份，token 保护）。
     */
    @Test
    void deliberateSkipsEnrichmentWhenSituationAlreadyHasState() throws Exception {
        com.holzyn.actor.domain.agent.context.AiContextInjector inj =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(inj.decisionStatePrefix(anyLong(), anyString(), any()))
                .thenReturn("【当前世界动态】青禾村历 0420年05月15日 06时00分 · 夏季\n【当前状态】\n- 需求");
        ReflectionTestUtils.setField(service, "aiContextInjector", inj);
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(profile("full"));
        when(promptService.buildActionGenPrompt(anyLong(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn("行动生成 Prompt");
        String okJson = "<decision>{\"reasoning\":\"正常决策\",\"action_type\":\"continue\","
                + "\"action_detail\":\"继续手头的事\",\"target\":\"\",\"priority\":1,"
                + "\"expected_duration_minutes\":30}</decision>";
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(okJson, "test-model", 10, 5, 15, 1L));

        Map<String, Object> d = service.generateDecision(character(), 1L,
                "【当前所见】现在是青禾村历 0420年05月15日 06时00分，你在茶馆，正在喝茶。\n【当前状态】\n- 需求：饥饿 68/100");

        assertNotNull(d);
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(promptService).buildActionGenPrompt(anyLong(), anyLong(), anyString(),
                captor.capture(), anyString());
        String situation = captor.getValue();
        // 情境已含【当前状态】→ 不前置（原样透传，仅含原一段【当前状态】）
        int stateCount = situation.split("【当前状态】", -1).length - 1;
        assertEquals(1, stateCount, "已含状态段的情境不应重复注入: " + situation);
    }

    /**
     * 注入器未装配（null）→ 情境原样透传（行为与批3 前一致，零阻断）。
     */
    @Test
    void deliberateWithoutInjectorPassesSituationThrough() throws Exception {
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(profile("full"));
        when(promptService.buildActionGenPrompt(anyLong(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn("行动生成 Prompt");
        String okJson = "<decision>{\"reasoning\":\"正常决策\",\"action_type\":\"continue\","
                + "\"action_detail\":\"继续手头的事\",\"target\":\"\",\"priority\":1,"
                + "\"expected_duration_minutes\":30}</decision>";
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(okJson, "test-model", 10, 5, 15, 1L));

        Map<String, Object> d = service.generateDecision(character(), 1L, "【本次刺激】普通刺激", true);

        assertNotNull(d);
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(promptService).buildActionGenPrompt(anyLong(), anyLong(), anyString(),
                captor.capture(), anyString());
        assertEquals("【本次刺激】普通刺激", captor.getValue(), "无注入器 → 情境原样");
    }
}
