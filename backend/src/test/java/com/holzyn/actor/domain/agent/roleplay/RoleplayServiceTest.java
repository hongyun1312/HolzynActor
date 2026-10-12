package com.holzyn.actor.domain.agent.roleplay;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.account.service.LocalAccountService;
import com.holzyn.actor.domain.agent.context.AiContextInjector;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.agent.perception.PerceptionService;
import com.holzyn.actor.domain.agent.runtime.AgentRuntime;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.repository.ActorNpcStateRepository;
import com.holzyn.actor.domain.npc.state.service.NpcActivityStateService;
import com.holzyn.actor.domain.character.service.PromptService;
import com.holzyn.actor.domain.growth.persona.PersonaService;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationMemberRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorMessageRepository;
import com.holzyn.actor.domain.conversation.service.ConversationContextService;
import com.holzyn.actor.domain.conversation.service.ConversationService;
import com.holzyn.actor.domain.conversation.service.GroupChatConfigService;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.repository.ActorEvolutionTurnRepository;
import com.holzyn.actor.domain.world.repository.ActorSceneRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.AiUsage;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 角色扮演组件（C2 收编 ChatService/GroupChatService/WorldEvolutionService 编排）单元测试
 * （domain/agent/roleplay，同包）。
 * <p>职责：验证——消息映射（toAiMessage 静态，事件→system/用户→user/助手 done→assistant）、
 * 单聊消息组装（buildReplyMessages：system_prompt + 场景 + 历史事件注入）、crowd 成员确认
 * （ensureCrowdMember）、演化编排解析（parsePlan 静态）。</p>
 */
class RoleplayServiceTest {

    private ActorConversationMemberRepository memberRepository;
    private ActorCharacterRepository characterRepository;
    private ActorMessageRepository messageRepository;
    private ActorCharacterRepository npcRepository;
    private ActorNpcStateRepository stateRepository;
    private NpcTypeResolver npcTypeResolver;
    private ConversationContextService conversationContextService;
    private AgentMemoryService agentMemoryService;
    private WorldClockService worldClockService;
    private RoleplayContextService roleplayContextService;
    private LocalAccountService localAccountService;
    private GroupChatConfigService groupChatConfigService;
    private AiSpringChatGateway aiChatGateway;
    private UsageLogService usageLogService;
    private PromptService promptService;
    private RoleplayService service;

    /** 会话消息桩（stubGroupRound 默认置空 = 开场轮；见 2026-09-25 说话意愿修复 C 层） */
    private List<ActorMessage> groupHistory = List.of();

    /** N3 社交意愿门控（@Autowired(required=false) 字段注入，单测手动回填 mock） */
    private SocialWillingnessGate socialWillingnessGate;

    @BeforeEach
    void setUp() {
        memberRepository = Mockito.mock(ActorConversationMemberRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        npcRepository = Mockito.mock(ActorCharacterRepository.class);
        stateRepository = Mockito.mock(ActorNpcStateRepository.class);
        messageRepository = Mockito.mock(ActorMessageRepository.class);
        conversationContextService = Mockito.mock(ConversationContextService.class);
        agentMemoryService = Mockito.mock(AgentMemoryService.class);
        worldClockService = Mockito.mock(WorldClockService.class);
        roleplayContextService = Mockito.mock(RoleplayContextService.class);
        localAccountService = Mockito.mock(LocalAccountService.class);
        groupChatConfigService = Mockito.mock(GroupChatConfigService.class);
        aiChatGateway = Mockito.mock(AiSpringChatGateway.class);
        usageLogService = Mockito.mock(UsageLogService.class);
        promptService = Mockito.mock(PromptService.class);
        when(localAccountService.renderNpcProfile()).thenReturn("");
        // O6 G1a：npc:{id} 不再编码类型 → 真实 NpcTypeResolver（类型查 actor_profile.type，查不到回退 special）；
        // 普通型作息运行态改由 NpcActivityStateService 读 actor_state.activity_state（主表 state 列已删）
        npcTypeResolver = new NpcTypeResolver(characterRepository);
        service = new RoleplayService(
                Mockito.mock(ConversationService.class),
                Mockito.mock(ActorConversationRepository.class),
                memberRepository,
                characterRepository,
                npcRepository,
                messageRepository,
                groupChatConfigService,
                aiChatGateway,
                promptService,
                usageLogService,
                Mockito.mock(AgentRuntime.class),
                conversationContextService,
                agentMemoryService,
                localAccountService,
                Mockito.mock(PerceptionService.class),
                worldClockService,
                roleplayContextService,
                Mockito.mock(CurrentUserProvider.class),
                Mockito.mock(PromptTemplateService.class),
                Mockito.mock(ActorWorldSettingRepository.class),
                Mockito.mock(ActorSceneRepository.class),
                Mockito.mock(ActorEvolutionTurnRepository.class),
                new ObjectMapper(),
                npcTypeResolver,
                new NpcActivityStateService(stateRepository));
        // @Value 字段在单元测试中不注入，统一回填生产默认值
        ReflectionTestUtils.setField(service, "perceptionEnabled", true);
        // G1a：档案读取唯一来源是 PersonaService（@Autowired(required=false) 字段注入，单测手动回填 mock）
        ReflectionTestUtils.setField(service, "personaService", Mockito.mock(PersonaService.class));
        // N3 社交意愿门控（字段注入）：默认未桩 → evaluateBatch 返回空 Map = 全员放行（fail-open）
        socialWillingnessGate = Mockito.mock(SocialWillingnessGate.class);
        ReflectionTestUtils.setField(service, "socialWillingnessGate", socialWillingnessGate);
        // 2026-09-25 说话意愿修复 B 层：@Value 阈值字段在单测中不注入，回填生产默认值 3
        ReflectionTestUtils.setField(service, "desireThreshold", 3);
    }

    /**
     * 会话消息桩（本场已有实质发言 = 非开场轮）：逐条给出 sessionHistory 供开场轮判定与上下文组装。
     *
     * @param messages 会话消息（按 id 升序）
     */
    private void stubGroupHistory(ActorMessage... messages) {
        groupHistory = List.of(messages);
    }

    /** 已完成的角色发言消息（非开场轮判定用） */
    private ActorMessage speakerMessage(Long characterId, String name, String content) {
        ActorMessage m = new ActorMessage();
        m.setId(98L);
        m.setConversationId(9L);
        m.setCharacterId(characterId);
        m.setRole("assistant");
        m.setType("text");
        m.setStatus("done");
        m.setContent(content);
        return m;
    }

    /** 全员「不愿接话」的门控桩（键随实现口径 npc:{id} 动态生成，避免键格式漂移导致用例失真） */
    private void stubAllUnwilling() {
        when(socialWillingnessGate.evaluateBatch(anyLong(), any())).thenAnswer(inv -> {
            Map<String, Object> items = inv.getArgument(1);
            Map<String, Boolean> out = new java.util.LinkedHashMap<>();
            for (String key : items.keySet()) {
                out.put(key, false);
            }
            return out;
        });
    }

    private ActorMessage msg(String role, String type, String status, String content) {
        ActorMessage m = new ActorMessage();
        m.setId(99L);
        m.setRole(role);
        m.setType(type);
        m.setStatus(status);
        m.setContent(content);
        return m;
    }

    // ==================== 消息映射（toAiMessage 静态，C2 收编 ChatService.toAiMessage） ====================

    /** 世界事件消息 → system「【世界事件】+内容」 */
    @Test
    void worldEventMapsToSystemMessage() {
        AiChatRequest.ChatMessage ai = RoleplayService.toAiMessage(msg("system", "event", "done", "城东市场发生骚乱"));
        assertNotNull(ai);
        assertEquals("system", ai.role());
        assertEquals("【世界事件】城东市场发生骚乱", ai.content());
    }

    /** 用户消息 → user；助手 done → assistant；streaming 占位跳过 */
    @Test
    void messageMappingRules() {
        assertEquals("user", RoleplayService.toAiMessage(msg("user", "text", "done", "你好")).role());
        assertEquals("assistant", RoleplayService.toAiMessage(msg("assistant", "text", "done", "你好呀")).role());
        assertNull(RoleplayService.toAiMessage(msg("assistant", "text", "streaming", "")), "streaming 占位跳过");
    }

    // ==================== 单聊消息组装（buildReplyMessages，C2 收编 ChatService.buildMessages） ====================

    /**
     * 单聊组装：system_prompt（角色卡）+ 场景 + 感知【当前所见】+ 历史（事件→system 注入）。
     */
    @Test
    void buildReplyMessagesAssemblesSystemSceneAndHistory() {
        ActorConversation conv = new ActorConversation();
        conv.setId(200L);
        conv.setProjectId(100L);
        conv.setLocation("城东茶馆");
        ActorConversationMember member = new ActorConversationMember();
        member.setCharacterId(3L);
        member.setMemberType("special");
        when(memberRepository.findByConversationId(200L)).thenReturn(List.of(member));
        when(roleplayContextService.resolveCharacterPrompt(100L, "special", 3L)).thenReturn("你是茶馆掌柜玄铁。");
        when(worldClockService.currentGameHour(100L)).thenReturn(100L);
        when(worldClockService.gameTimeTextOf(100L, 100L)).thenReturn("第三日午时");
        when(conversationContextService.buildContext(anyLong(), anyLong(), any(), anyString())).thenReturn("");
        // D2 C-16：新签名（含 excludeTexts 列表）也 stub 为空串
        when(conversationContextService.buildContext(anyLong(), anyLong(), any(), anyString(), any())).thenReturn("");
        when(agentMemoryService.conversationSummaryContext(anyLong(), any())).thenReturn("");
        when(messageRepository.findByConversationIdOrderByIdAsc(200L)).thenReturn(List.of(
                msg("user", "text", "done", "掌柜，来壶茶"),
                msg("system", "event", "done", "城东市场发生骚乱")));
        when(characterRepository.findById(3L)).thenReturn(Optional.of(character()));

        ActorMessage pending = new ActorMessage();
        pending.setId(100L);
        pending.setCharacterId(3L);
        List<AiChatRequest.ChatMessage> messages = service.buildReplyMessages(conv, pending, 1L);

        assertNotNull(messages);
        assertTrue(messages.stream().anyMatch(m -> "system".equals(m.role()) && m.content().contains("你是茶馆掌柜玄铁。")),
                "应含角色卡 system_prompt（normalizeForLlm 合并 system 段后按包含匹配）");
        assertTrue(messages.stream().anyMatch(m -> "user".equals(m.role()) && m.content().contains("第三日午时")),
                "应含场景注入（O4 分层：场景变化段在 user 触发消息中，随时钟刷新）");
        assertTrue(messages.stream().anyMatch(m -> "user".equals(m.role()) && "掌柜，来壶茶".equals(m.content())));
        assertTrue(messages.stream().anyMatch(m -> "user".equals(m.role()) && m.content().contains("【世界事件】城东市场发生骚乱")),
                "历史世界事件（O4 分层：事件→user 角色，避免 system 合并污染稳定前缀）");
        assertTrue(messages.stream().anyMatch(m -> "user".equals(m.role()) && m.content().contains("—— TASK ——")),
                "应含分层 TASK 段（O4 六层结构）");
        assertTrue(messages.stream().anyMatch(m -> "user".equals(m.role()) && m.content().contains("—— OUTPUT_FORMAT ——")),
                "应含分层 OUTPUT_FORMAT 段（O4 六层结构）");
    }

    /**
     * 批 2 对话链（2026-10-08）：单聊装配注入 V2.0 段位——段 1A 世界观 system 前缀 +
     * 段 2D 当前世界动态 + 段 2F few-shot 真实历史 + 段 3I Post-History 行为护栏。
     */
    @Test
    void buildReplyMessagesWithInjector_injectsV2DialogueSegments() {
        ActorConversation conv = new ActorConversation();
        conv.setId(200L);
        conv.setProjectId(100L);
        conv.setLocation("城东茶馆");
        ActorConversationMember member = new ActorConversationMember();
        member.setCharacterId(3L);
        member.setMemberType("special");
        when(memberRepository.findByConversationId(200L)).thenReturn(List.of(member));
        when(roleplayContextService.resolveCharacterPrompt(100L, "special", 3L)).thenReturn("你是茶馆掌柜玄铁。");
        when(worldClockService.currentGameHour(100L)).thenReturn(100L);
        when(worldClockService.gameTimeTextOf(100L, 100L)).thenReturn("第三日午时");
        when(conversationContextService.buildContext(anyLong(), anyLong(), any(), anyString())).thenReturn("");
        when(conversationContextService.buildContext(anyLong(), anyLong(), any(), anyString(), any())).thenReturn("");
        when(agentMemoryService.conversationSummaryContext(anyLong(), any())).thenReturn("");
        when(messageRepository.findByConversationIdOrderByIdAsc(200L)).thenReturn(List.of(
                msg("user", "text", "done", "掌柜，来壶茶")));
        when(characterRepository.findById(3L)).thenReturn(Optional.of(character()));

        AiContextInjector inj = Mockito.mock(AiContextInjector.class);
        when(inj.worldBrief(100L)).thenReturn("青禾村是大雍朝青岳山东麓的仙凡混居村落。");
        when(inj.worldDynamicsLine(100L)).thenReturn("青禾村历 0420年05月15日 06时00分 · 夏季");
        when(inj.dialogueFewShot(100L, "npc:3"))
                .thenReturn("【示例对话】\n对方（钱串子）：集市今天散得早。\n你（玄铁）：可不是，我正收拾摊子。");
        ReflectionTestUtils.setField(service, "aiContextInjector", inj);

        ActorMessage pending = new ActorMessage();
        pending.setId(100L);
        pending.setCharacterId(3L);
        List<AiChatRequest.ChatMessage> messages = service.buildReplyMessages(conv, pending, 1L);

        assertTrue(messages.stream().anyMatch(m -> "system".equals(m.role())
                        && m.content().contains("【世界观】") && m.content().contains("青禾村是大雍朝")),
                "段 1A 世界观（common_knowledge 精炼常识）应拼入 system 前缀（批 2 对话链）");
        String trigger = messages.stream().filter(m -> "user".equals(m.role()))
                .map(AiChatRequest.ChatMessage::content).collect(Collectors.joining("\n"));
        assertTrue(trigger.contains("【当前世界动态】"), "段 2D 世界动态应注入触发消息");
        assertTrue(trigger.contains("青禾村历 0420年05月15日"), "段 2D 应含精简行内容");
        assertTrue(trigger.contains("【示例对话】"), "段 2F few-shot 应注入触发消息");
        assertTrue(trigger.contains("你（玄铁）：可不是"), "few-shot 应含该角色真实发言");
        assertTrue(trigger.contains("—— POST_HISTORY_GUARD ——"), "段 3I 行为护栏应在触发消息中（Post-History 位）");
        assertTrue(trigger.contains("以【玄铁】的第一人称说话"), "段 3I 护栏文本应注入");
        assertTrue(trigger.indexOf("—— POST_HISTORY_GUARD ——") > trigger.indexOf("—— TASK ——"),
                "段 3I 应在 TASK（段 2H）之后（历史之后高权重位）");
    }

    // ==================== crowd 成员确认（ensureCrowdMember，B5 F4 桥接） ====================

    /**
     * 会话内存在 member_type=common（历史 crowd）的该成员 → true；否则 false。
     */
    @Test
    void ensureCrowdMemberChecksMembership() {
        ActorConversationMember crowd = new ActorConversationMember();
        crowd.setCharacterId(38L);
        crowd.setMemberType("common");
        when(memberRepository.findByConversationId(9L)).thenReturn(List.of(crowd));
        // G1a：是否普通型查 actor_profile.type（引用不再编码类型）；38=common、3=special
        when(characterRepository.findById(38L)).thenReturn(Optional.of(npc(38L, "common")));
        when(characterRepository.findById(3L)).thenReturn(Optional.of(character()));

        assertTrue(service.ensureCrowdMember(9L, "npc:38"));
        assertFalse(service.ensureCrowdMember(9L, "npc:39"), "非在会成员 false");
        assertFalse(service.ensureCrowdMember(9L, "npc:3"), "special 成员不算普通型（common）");
        assertFalse(service.ensureCrowdMember(null, "npc:38"));
    }

    // ==================== 演化编排解析（parsePlan 静态，C2 收编 WorldEvolutionService.parsePlan） ====================

    /** 合法编排 JSON 解析：只保留在场角色消息 + joins/leaves/shouldFinish 透传。 */
    @Test
    void parsePlanKeepsActiveMessagesOnly() {
        String json = "{\"messages\":[{\"characterId\":1,\"type\":\"text\",\"content\":\"来了\"},"
                + "{\"characterId\":2,\"type\":\"action\",\"content\":\"（离开）\"}],"
                + "\"sceneEvent\":\"起风了\",\"joins\":[],\"leaves\":[],"
                + "\"shouldFinish\":false,\"finishReason\":\"\",\"summary\":\"\"}";
        Map<String, Object> plan = RoleplayService.parsePlan(json, List.of(1L));
        assertNotNull(plan);
        assertEquals(1, ((List<?>) plan.get("messages")).size(), "仅保留在场角色 1 的消息");
        assertEquals("起风了", plan.get("sceneEvent"));
    }

    /** 无效 JSON → null（兜底由调用方处理）。 */
    @Test
    void parsePlanReturnsNullOnInvalidJson() {
        assertNull(RoleplayService.parsePlan("这不是JSON", List.of(1L)));
        assertNull(RoleplayService.parsePlan(null, List.of(1L)));
    }

    // ==================== llama.cpp 兼容归一化（normalizeForLlm，2026-09-12 修复多 system 空响应） ====================

    private static AiChatRequest.ChatMessage cm(String role, String content) {
        return new AiChatRequest.ChatMessage(role, content);
    }

    /** 群聊真实失败结构：多条连续 system + assistant 历史 + 末尾「当前轮到」system → 合并为单 system + 末尾 user。 */
    @Test
    void normalizeMergesConsecutiveSystemsAndConvertsTrailingInstructionToUser() {
        List<AiChatRequest.ChatMessage> in = List.of(
                cm("system", "你是钱串子，走南闯北的货郎。"),
                cm("system", "【核心记忆】常跑青岳山一线。"),
                cm("system", "【当前状态】在青禾村客栈躲雨。"),
                cm("assistant", "李姐：这雨下得邪乎。"),
                cm("assistant", "王婆：可不是。"),
                cm("system", "当前轮到【钱串子】发言。请接话。"));
        List<AiChatRequest.ChatMessage> out = RoleplayService.normalizeForLlm(in);
        assertEquals(4, out.size(), "4 system + 2 assistant + 1 指示 → 合并后 1 system + 2 assistant + 1 user");
        assertEquals("system", out.get(0).role());
        assertTrue(out.get(0).content().contains("你是钱串子"), "身份保留");
        assertTrue(out.get(0).content().contains("【核心记忆】"), "记忆保留");
        assertTrue(out.get(0).content().contains("【当前状态】"), "状态保留");
        assertTrue(out.get(0).content().contains("\n\n"), "system 以空行拼接");
        assertEquals("assistant", out.get(1).role());
        assertEquals("assistant", out.get(2).role());
        assertEquals("user", out.get(3).role(), "末尾「当前轮到」指示应转为 user 触发生成");
        assertEquals("当前轮到【钱串子】发言。请接话。", out.get(3).content());
    }

    /** 演化拍结构：全部为 system（人设+上下文+记忆+逐拍历史+指示）→ 合并为一整段 system + 末尾 user。 */
    @Test
    void normalizeEvolutionBeatShape() {
        List<AiChatRequest.ChatMessage> in = List.of(
                cm("system", "你是茶馆掌柜。"),
                cm("system", "【世界观】小镇设定。"),
                cm("system", "【记忆】昨天的事。"),
                cm("system", "钱串子：明儿个赶集。"),
                cm("system", "当前轮到【你】。这一拍是【对话】。"));
        List<AiChatRequest.ChatMessage> out = RoleplayService.normalizeForLlm(in);
        assertEquals(2, out.size());
        assertEquals("system", out.get(0).role());
        assertTrue(out.get(0).content().contains("你是茶馆掌柜") && out.get(0).content().contains("钱串子：明儿个赶集"));
        assertEquals("user", out.get(1).role());
        assertTrue(out.get(1).content().contains("这一拍是【对话】"));
    }

    /** 末尾指示前已是 user（玩家刚发言）→ 指示作为独立 user 消息追加（连续 user 实测兼容）。 */
    @Test
    void normalizeAppendsTrailingInstructionAsSeparateUser() {
        List<AiChatRequest.ChatMessage> in = List.of(
                cm("system", "身份段。"),
                cm("user", "掌柜，来壶茶"),
                cm("system", "当前轮到【掌柜】发言。请回应。"));
        List<AiChatRequest.ChatMessage> out = RoleplayService.normalizeForLlm(in);
        assertEquals(3, out.size());
        assertEquals("system", out.get(0).role());
        assertEquals("user", out.get(1).role());
        assertEquals("掌柜，来壶茶", out.get(1).content(), "玩家消息原样保留");
        assertEquals("user", out.get(2).role(), "末尾指示转为独立 user 触发消息");
        assertEquals("当前轮到【掌柜】发言。请回应。", out.get(2).content());
    }

    /** 已是单 system + 单 user（如 JSON 评估请求）→ 结构保持不变。 */
    @Test
    void normalizeKeepsSingleSystemUserShape() {
        List<AiChatRequest.ChatMessage> in = List.of(
                cm("system", "你只输出严格的 JSON。"),
                cm("user", "评估各成员发言欲望。"));
        List<AiChatRequest.ChatMessage> out = RoleplayService.normalizeForLlm(in);
        assertEquals(2, out.size());
        assertEquals("system", out.get(0).role());
        assertEquals("user", out.get(1).role());
        assertEquals("你只输出严格的 JSON。", out.get(0).content());
    }

    /** 历史中途的世界事件（system 夹在 user/assistant 之间，末尾为 user）→ 收集进唯一前置 system 段。 */
    @Test
    void normalizeCollectsMidHistorySystemsToTop() {
        List<AiChatRequest.ChatMessage> in = List.of(
                cm("system", "身份段。"),
                cm("user", "掌柜，来壶茶"),
                cm("assistant", "客官稍等。"),
                cm("system", "【世界事件】城东市场发生骚乱"),
                cm("user", "再上盘点心。"));
        List<AiChatRequest.ChatMessage> out = RoleplayService.normalizeForLlm(in);
        assertEquals(4, out.size());
        assertEquals("system", out.get(0).role());
        assertTrue(out.get(0).content().contains("身份段") && out.get(0).content().contains("【世界事件】城东市场发生骚乱"),
                "中途事件应收集进唯一前置 system（llama.cpp 禁止非开头 system）");
        assertEquals("user", out.get(1).role());
        assertEquals("assistant", out.get(2).role());
        assertEquals("user", out.get(3).role(), "末尾 user 保持原位");
    }

    /** 空/单元素输入原样返回。 */
    @Test
    void normalizeHandlesEmptyAndSingle() {
        assertNull(RoleplayService.normalizeForLlm(null));
        assertTrue(RoleplayService.normalizeForLlm(List.of()).isEmpty());
        List<AiChatRequest.ChatMessage> one = List.of(cm("system", "单独一段。"));
        List<AiChatRequest.ChatMessage> out = RoleplayService.normalizeForLlm(one);
        assertEquals(1, out.size());
        assertEquals("user", out.get(0).role(), "仅一段 system → 转 user 保证模型产出");
    }

    // ==================== 辅助 ====================

    private ActorCharacter character() {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setProjectId(100L);
        ch.setName("玄铁");
        return ch;
    }

    /** 普通型 NPC 夹具（G1a：单主表下必须显式 setType("common")，实体默认值是 special） */
    private ActorCharacter npc(Long id, String type) {
        ActorCharacter n = new ActorCharacter();
        n.setId(id);
        n.setProjectId(100L);
        n.setName("摊贩甲");
        n.setType(type);
        return n;
    }

    // ==================== 2026-09-17 长跑 P0 修复：无玩家群聊失控循环防护 ====================

    private ActorConversationMember gmember(long id, String type, Long characterId, String name) {
        ActorConversationMember m = new ActorConversationMember();
        m.setId(id);
        m.setConversationId(9L);
        m.setMemberType(type);
        m.setCharacterId(characterId);
        m.setMemberName(name);
        return m;
    }

    private ActorConversation gconv() {
        ActorConversation conv = new ActorConversation();
        conv.setId(9L);
        conv.setProjectId(100L);
        conv.setLocation("城东茶馆");
        return conv;
    }

    private ActorCharacter characterWithActivity(String currentActivity) {
        ActorCharacter ch = character();
        ch.setCurrentActivity(currentActivity);
        return ch;
    }

    /**
     * 群聊轮通用打桩：白天（12 时）全员清醒 + 发言人评估 AI + 回复生成 AI + 成员档案。
     *
     * @param members    会话成员
     * @param decideJson 发言人评估 AI 输出（可空=评估失败）
     * @param replyText  回复生成文本（可空=不生成回复）
     */
    private void stubGroupRound(List<ActorConversationMember> members, String decideJson, String replyText) {
        when(memberRepository.findByConversationId(9L)).thenReturn(members);
        when(groupChatConfigService.getMaxReplies(1L)).thenReturn(5);
        // 轮级清醒过滤以「引擎实际推进位置」为基准（P0 修复：不用流动时钟）
        when(worldClockService.currentEngineGameHour(100L)).thenReturn(12L);
        // 会话消息：默认空 = 开场轮（2026-09-25 说话意愿修复 C 层）；
        // 需要「本场已有人发言」的用例用 stubGroupHistory(...) 另设
        groupHistory = List.of();
        when(messageRepository.findByConversationIdOrderByIdAsc(9L)).thenAnswer(inv -> groupHistory);
        // 模拟 JPA 主键回填：save 返回入参并赋 id（Map.of 不允许 null 值；pending.getId() 需可用）
        when(messageRepository.save(any(com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage.class)))
                .thenAnswer(inv -> {
                    com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage m = inv.getArgument(0);
                    if (m.getId() == null) {
                        m.setId(99L);
                    }
                    return m;
                });
        when(characterRepository.findById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return Optional.of(characterWithActivity(null));
        });
        when(promptService.buildGroupOrchestratorPrompt(anyLong(), anyLong(), anyString(), anyString(), any()))
                .thenReturn("评估各成员发言欲望，输出 JSON。");
        when(roleplayContextService.resolveCharacterPrompt(100L, "special", 3L)).thenReturn("你是茶馆掌柜玄铁。");
        when(roleplayContextService.resolveCharacterPrompt(100L, "special", 4L)).thenReturn("你是货郎钱串子。");
        // 第 4 参 lastUserText 可能为 null（空历史）——必须用 any()（anyString() 不匹配 null）
        when(conversationContextService.buildContext(anyLong(), anyLong(), any(), any())).thenReturn("");
        when(conversationContextService.buildContext(anyLong(), anyLong(), any(), anyString(), any())).thenReturn("");
        when(agentMemoryService.conversationSummaryContext(anyLong(), any())).thenReturn("");
        // O4 Prompt 工程（2026-09-21）：群聊评估与回复生成都走非流式 chatCompletion——
        // 按请求内容区分：回复请求含 OUTPUT_FORMAT 强制句 → 返回 DIALOG_JSON 契约；
        // 否则为发言人评估请求 → 返回 decideJson
        String replyJson = replyText == null ? null
                : "{\"dialogue\":\"" + replyText + "\",\"action\":\"\","
                + "\"emotion_update\":{\"emotion\":\"平淡\",\"intensity\":1},\"internal_thought\":\"\"}";
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any(AiChatRequest.class)))
                .thenAnswer(inv -> {
                    AiChatRequest req = inv.getArgument(3);
                    boolean replyRequest = req != null && req.messages() != null
                            && req.messages().stream().anyMatch(m -> m.content() != null
                            && m.content().contains("DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON"));
                    if (replyRequest) {
                        return replyJson == null ? new AiChatResult("不是JSON", "test-model", 10, 5, 0, 1L)
                                : new AiChatResult(replyJson, "test-model", 10, 5, 0, 1L);
                    }
                    return decideJson == null ? new AiChatResult("不是JSON", "test-model", 10, 5, 0, 1L)
                            : new AiChatResult(decideJson, "test-model", 10, 5, 0, 1L);
                });
    }

    /**
     * P0 核心修复①：AI 明确「无人想接话」（desire=2 < 阈值 3）→ 本轮结束（返回 0 条回复），
     * 不再落入轮询兜底强拉第一位成员发言（v1 长跑死循环根因，回归锁定）。
     * <p>2026-09-25 说话意愿修复 C 层：本用例必须是「本场已有人发言」（非开场轮）——
     * 开场轮的 desire 只作排序、不再当闸门（见 autonomousKickoffRoundStillGetsFirstSpeaker）。</p>
     */
    @Test
    void autonomousRoundEndsWhenNobodyWantsToSpeak() {
        stubGroupRound(List.of(gmember(1L, "special", 3L, "玄铁"), gmember(2L, "special", 4L, "钱串子")),
                "{\"memberIndex\":1,\"desire\":2,\"reason\":\"困了，不想接话\"}", null);
        stubGroupHistory(speakerMessage(4L, "钱串子", "天色不早了，我先收摊。"));

        int replies = service.runAutonomousGroupRound(gconv(), 1L, new AtomicBoolean(true));

        assertEquals(0, replies, "无人想接话必须结束本轮（本场已有人发言）");
        verify(aiChatGateway, never()).chatCompletionStream(anyLong(), anyLong(), any(), any(AiChatRequest.class), any(), any());
    }

    // ==================== N6 LOD×群聊参与度双向联动（2026-09-27） ====================

    /**
     * N6 双向联动·对话侧加成：<b>非开场轮</b> AI 判 desire=2（< 阈值 3），但该成员参与度档 2
     * （近 6 游戏小时多次发言）→ 程序化 +1 → desire=3 ≥ 阈值 → 照常发言（真人正在聊的人更愿接话）。
     * 只做加分，不改变 desire 解析与阈值语义（上轮说话意愿 B/C 层不受影响）。
     */
    @Test
    void desireParticipationBoostCrossesThreshold() {
        stubGroupRound(List.of(gmember(1L, "special", 3L, "玄铁"), gmember(2L, "special", 4L, "钱串子")),
                "{\"memberIndex\":1,\"desire\":2,\"reason\":\"有点累，不太想开口\"}", "既然都在这儿，我来说两句。");
        stubGroupHistory(speakerMessage(4L, "钱串子", "今天集市上人真多。"));
        com.holzyn.actor.domain.growth.attention.AttentionLevelProvider provider =
                Mockito.mock(com.holzyn.actor.domain.growth.attention.AttentionLevelProvider.class);
        when(provider.tierOf(anyLong(), anyString()))
                .thenReturn(com.holzyn.actor.domain.growth.lod.BrainTierService.TIER_AMBIENT);
        when(provider.participationLevel(anyLong(), anyString())).thenReturn(2);
        ReflectionTestUtils.setField(service, "attentionLevelProvider", provider);

        int replies = service.runAutonomousGroupRound(gconv(), 1L, new AtomicBoolean(true));

        assertTrue(replies >= 1, "参与度档 2 → desire 2+1=3 ≥ 阈值 3 → 应发言（非开场轮）");
    }

    /**
     * N6 双向联动·无信号回退：<b>非开场轮</b> AI desire=2，但参与度 0 档 / 非 focus → 不加分 →
     * 保持「无人想接话」自然收束（既有语义严格不变）。
     */
    @Test
    void desireBoostNoSignalKeepsOldBehavior() {
        stubGroupRound(List.of(gmember(1L, "special", 3L, "玄铁"), gmember(2L, "special", 4L, "钱串子")),
                "{\"memberIndex\":1,\"desire\":2,\"reason\":\"困了\"}", null);
        stubGroupHistory(speakerMessage(4L, "钱串子", "我先收摊了。"));
        com.holzyn.actor.domain.growth.attention.AttentionLevelProvider provider =
                Mockito.mock(com.holzyn.actor.domain.growth.attention.AttentionLevelProvider.class);
        when(provider.tierOf(anyLong(), anyString()))
                .thenReturn(com.holzyn.actor.domain.growth.lod.BrainTierService.TIER_AMBIENT);
        when(provider.participationLevel(anyLong(), anyString())).thenReturn(0);
        ReflectionTestUtils.setField(service, "attentionLevelProvider", provider);

        int replies = service.runAutonomousGroupRound(gconv(), 1L, new AtomicBoolean(true));

        assertEquals(0, replies, "无参与度信号时 desire 不加分 → 保持无人想接话收束");
    }

    /**
     * N6 双向联动·信号源：群聊成功发言后——发言者记录 spoke、在场其他成员记录 attended
     * （参与度回灌注意力的对话侧入口；纯内存零副作用）。
     */
    @Test
    void groupReplyRecordsParticipation() {
        stubGroupRound(List.of(gmember(1L, "special", 3L, "玄铁"), gmember(2L, "special", 4L, "钱串子")),
                "{\"memberIndex\":1,\"desire\":5,\"reason\":\"想聊\"}", "今晚的月色真不错。");
        stubGroupHistory(speakerMessage(4L, "钱串子", "集市今天散得早。"));
        when(worldClockService.currentEngineGameSecond(100L)).thenReturn(1_000_000L);
        com.holzyn.actor.domain.growth.attention.AttentionLevelProvider provider =
                Mockito.mock(com.holzyn.actor.domain.growth.attention.AttentionLevelProvider.class);
        when(provider.tierOf(anyLong(), anyString()))
                .thenReturn(com.holzyn.actor.domain.growth.lod.BrainTierService.TIER_AMBIENT);
        when(provider.participationLevel(anyLong(), anyString())).thenReturn(0);
        ReflectionTestUtils.setField(service, "attentionLevelProvider", provider);

        service.runAutonomousGroupRound(gconv(), 1L, new AtomicBoolean(true));

        // 发言者 spoke 至少 1 次 + 在场其他成员 attended 至少 1 次（一轮内可能多拍，
        // 用 atLeastOnce 锁定「信号源确实写入」，ref 格式任其自然 anyString 匹配）
        verify(provider, atLeastOnce()).recordParticipation(eq(100L), anyString(),
                eq(com.holzyn.actor.domain.growth.attention.AttentionLevelProvider.PARTICIPATION_SPOKE),
                eq(1_000_000L));
        verify(provider, atLeastOnce()).recordParticipation(eq(100L), anyString(),
                eq(com.holzyn.actor.domain.growth.attention.AttentionLevelProvider.PARTICIPATION_ATTENDED),
                eq(1_000_000L));
    }

    /**
     * 批 2 对话链（2026-10-08）：群聊逐角色回复（含无玩家自主对话）装配注入 V2.0 段位——
     * 段 1A 世界观 system 前缀 + 段 2D 当前世界动态 + 段 2F few-shot + 段 3I Post-History 行为护栏。
     * 自主对话收尾复用同一链路（generateGroupMemberSink → buildCharacterMessages）。
     */
    @Test
    void groupReplyWithInjector_injectsV2DialogueSegments() {
        stubGroupRound(List.of(gmember(1L, "special", 3L, "玄铁"), gmember(2L, "special", 4L, "钱串子")),
                "{\"memberIndex\":1,\"desire\":5,\"reason\":\"想聊\"}", "今晚的月色真不错。");
        stubGroupHistory(speakerMessage(4L, "钱串子", "集市今天散得早。"));
        AiContextInjector inj = Mockito.mock(AiContextInjector.class);
        when(inj.worldBrief(100L)).thenReturn("青禾村是大雍朝青岳山东麓的仙凡混居村落。");
        when(inj.worldDynamicsLine(100L)).thenReturn("青禾村历 0420年05月15日 06时00分 · 夏季");
        when(inj.dialogueFewShot(100L, "npc:3"))
                .thenReturn("【示例对话】\n对方（钱串子）：集市今天散得早。\n你（玄铁）：可不是。");
        ReflectionTestUtils.setField(service, "aiContextInjector", inj);

        int replies = service.runAutonomousGroupRound(gconv(), 1L, new AtomicBoolean(true));

        assertTrue(replies >= 1, "开场轮应产生回复（V2.0 段位注入不阻断生成）");
        ArgumentCaptor<AiChatRequest> captor = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway, atLeastOnce()).chatCompletion(anyLong(), anyLong(), any(), captor.capture());
        AiChatRequest replyReq = captor.getAllValues().stream()
                .filter(r -> r.messages() != null && r.messages().stream()
                        .anyMatch(m -> m.content() != null
                                && m.content().contains("DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON")))
                .findFirst().orElse(null);
        assertNotNull(replyReq, "应捕获群聊逐角色回复请求（DIALOG_JSON 契约）");
        String all = replyReq.messages().stream()
                .map(m -> m.role() + "|" + (m.content() == null ? "" : m.content()))
                .collect(Collectors.joining("\n"));
        assertTrue(all.contains("【世界观】"), "群聊 system 前缀应含段 1A 世界观");
        assertTrue(all.contains("【当前世界动态】"), "群聊触发消息应含段 2D 世界动态");
        assertTrue(all.contains("【示例对话】"), "群聊触发消息应含段 2F few-shot");
        assertTrue(all.contains("—— POST_HISTORY_GUARD ——"), "群聊触发消息应含段 3I 行为护栏（Post-History 位）");
        assertTrue(all.contains("以【玄铁】的第一人称说话"), "段 3I 护栏文本应注入");
    }

    /**
     * 2026-09-25 说话意愿修复 C 层（核心）：<b>开场轮</b>（本场尚无实质发言）即便模型给出
     * desire=2（低于阈值 3）也必须有人起头——否则整场胎死腹中（0 条消息），
     * 正是长跑/冒烟「事件回应轮 6 场中 4 场 0~1 条消息」的直接机制。
     */
    @Test
    void autonomousKickoffRoundStillGetsFirstSpeaker() {
        stubGroupRound(List.of(gmember(1L, "special", 3L, "玄铁"), gmember(2L, "special", 4L, "钱串子")),
                "{\"memberIndex\":1,\"desire\":2,\"reason\":\"还没什么想说的\"}", "这天色看着要变，怕是要落雨。");
        // 开场轮：会话为空（stubGroupRound 默认）——无实质发言

        int replies = service.runAutonomousGroupRound(gconv(), 1L, new AtomicBoolean(true));

        assertTrue(replies >= 1, "开场轮必须有人起头（desire 仅排序，不作开门闸门）");
        verify(messageRepository, atLeastOnce()).save(argThat((ActorMessage m) ->
                "done".equals(m.getStatus()) && m.getCharacterId() != null && m.getCharacterId() == 3L));
    }

    /**
     * 2026-09-25 说话意愿修复 A 层：本场背景（触发本次交谈的缘由）必须注入发言人选评估
     * （此前开场轮上下文为空、模型无据可依 → 只能答「无人想接话」）。
     */
    @Test
    void autonomousRoundPassesChatBackgroundToOrchestrator() {
        stubGroupRound(List.of(gmember(1L, "special", 3L, "玄铁"), gmember(2L, "special", 4L, "钱串子")),
                "{\"memberIndex\":1,\"desire\":4,\"reason\":\"有事可议\"}", null);

        service.runAutonomousGroupRound(gconv(), 1L, new AtomicBoolean(true),
                "世界事件后的自发回应——村口老槐树被雷劈了（此刻在城东茶馆）");

        // 本场背景必须注入发言人选评估（2026-10 后空回复会触发「跳过+重选」，评估可能多次，用 atLeastOnce）
        verify(promptService, atLeastOnce()).buildGroupOrchestratorPrompt(anyLong(), anyLong(), anyString(), anyString(),
                argThat((String bg) -> bg != null && bg.contains("村口老槐树被雷劈了")));
    }

    /**
     * 2026-09-25 说话意愿修复 C 层（第二道闸门）：开场轮 N3 社交意愿门控「全员不愿接话」
     * 同样不得让整场胎死腹中——保留候选由最相关成员起头；非开场轮保持「自然收束」语义。
     */
    @Test
    void autonomousKickoffRoundSurvivesAllUnwillingGate() {
        stubGroupRound(List.of(gmember(1L, "special", 3L, "玄铁"), gmember(2L, "special", 4L, "钱串子")),
                "{\"memberIndex\":1,\"desire\":2,\"reason\":\"大家都不想说话\"}", "既然都闷着，我先开个口。");
        stubAllUnwilling();
        // 开场轮：会话为空

        int replies = service.runAutonomousGroupRound(gconv(), 1L, new AtomicBoolean(true));

        assertTrue(replies >= 1, "开场轮全员不愿接话也必须有人起头");
    }

    /**
     * 非开场轮对照：本场已有人发言 + 全员社交意愿拒绝 → 本轮自然收束（0 条，语义不变）。
     */
    @Test
    void nonKickoffRoundEndsWhenAllUnwilling() {
        stubGroupRound(List.of(gmember(1L, "special", 3L, "玄铁"), gmember(2L, "special", 4L, "钱串子")),
                "{\"memberIndex\":1,\"desire\":5,\"reason\":\"想接话\"}", null);
        stubGroupHistory(speakerMessage(4L, "钱串子", "我先说一句。"));
        stubAllUnwilling();

        int replies = service.runAutonomousGroupRound(gconv(), 1L, new AtomicBoolean(true));

        assertEquals(0, replies, "非开场轮全员不愿接话 → 自然收束");
    }

    /**
     * 2026-09-25 说话意愿修复 C 层（事件路由命中 NPC 内群的边界）：会话里只有一条
     * <b>世界事件消息</b>（type=event，事件路由注入的情境）而无人开口 → 仍属开场轮，
     * 必须有人起头（否则「事件注入 → 无人接话 → 0 条消息回收」的老问题复现）。
     */
    @Test
    void eventContextOnlyConversationStillCountsAsKickoff() {
        stubGroupRound(List.of(gmember(1L, "special", 3L, "玄铁"), gmember(2L, "special", 4L, "钱串子")),
                "{\"memberIndex\":2,\"desire\":1,\"reason\":\"还没想好\"}", "听说村口那棵树被雷劈了？");
        ActorMessage eventMsg = new ActorMessage();
        eventMsg.setId(97L);
        eventMsg.setConversationId(9L);
        eventMsg.setRole("system");
        eventMsg.setType("event");
        eventMsg.setStatus("done");
        eventMsg.setContent("村口老槐树被雷劈了");
        stubGroupHistory(eventMsg);

        int replies = service.runAutonomousGroupRound(gconv(), 1L, new AtomicBoolean(true));

        assertTrue(replies >= 1, "只有事件情境、无人开口 → 仍须保证开场首个发言者");
    }

    /**
     * 2026-09-25 说话意愿修复 B 层：desire 收敛 1~5（编排模板旧文案「1到10」，与按 1~5 标定的
     * 阈值错位——模型按十分制给 9 分时旧代码会直接放行）。阈值调到量表上限之上（8）时，
     * 模型给 9 必须被收敛为 5 &lt; 8 → 非开场轮照常收束。
     */
    @Test
    void desireIsClampedToFivePointScale() {
        ReflectionTestUtils.setField(service, "desireThreshold", 8);
        stubGroupRound(List.of(gmember(1L, "special", 3L, "玄铁"), gmember(2L, "special", 4L, "钱串子")),
                "{\"memberIndex\":1,\"desire\":9,\"reason\":\"按十分制给分\"}", null);
        stubGroupHistory(speakerMessage(4L, "钱串子", "我先说一句。"));

        int replies = service.runAutonomousGroupRound(gconv(), 1L, new AtomicBoolean(true));

        assertEquals(0, replies, "desire=9 收敛为 5 < 阈值 8 → 非开场轮收束");
    }

    /**
     * P0 核心修复②：发言人评估 AI 输出为空/解析失败（本地模型不稳定）→ 无玩家轮结束本轮，
     * 不再轮询兜底强拉人发言（v1 长跑死循环根因，回归锁定）。
     */
    @Test
    void autonomousRoundEndsOnEvalFailure() {
        stubGroupRound(List.of(gmember(1L, "special", 3L, "玄铁"), gmember(2L, "special", 4L, "钱串子")),
                "不是合法 JSON", null);

        int replies = service.runAutonomousGroupRound(gconv(), 1L, new AtomicBoolean(true));

        assertEquals(0, replies, "评估失败必须结束本轮");
        verify(aiChatGateway, never()).chatCompletionStream(anyLong(), anyLong(), any(), any(AiChatRequest.class), any(), any());
    }

    /**
     * 入睡/深夜成员调度抑制：特殊 NPC current_activity 命中入睡关键词 → 从发言候选剔除；
     * 清醒成员正常发言（1 条回复）。
     */
    @Test
    void autonomousRoundSkipsSleepingMembers() {
        // 成员 1（玄铁）入睡：current_activity 命中「歇息就寝」；成员 2（钱串子）清醒
        stubGroupRound(List.of(gmember(1L, "special", 3L, "玄铁"), gmember(2L, "special", 4L, "钱串子")),
                "{\"memberIndex\":1,\"desire\":5,\"reason\":\"精神好\"}", "今晚月色真好");
        // 覆盖通用档案桩：玄铁入睡（后注册者胜出）
        when(characterRepository.findById(3L)).thenReturn(Optional.of(characterWithActivity("深夜在驻地歇息就寝")));

        int replies = service.runAutonomousGroupRound(gconv(), 1L, new AtomicBoolean(true));

        // 候选仅剩清醒的钱串子（序号 1）→ 至少 1 条回复，且全部回复来自清醒成员；
        // 入睡的玄铁（id=3）绝不被调度发言（argThat 在调用时点求值，规避同实例二次 save 的计数陷阱）
        assertTrue(replies >= 1, "清醒成员应至少发言一次");
        verify(messageRepository, atLeastOnce()).save(argThat((ActorMessage m) ->
                "done".equals(m.getStatus()) && m.getCharacterId() != null && m.getCharacterId() == 4L));
        verify(messageRepository, never()).save(argThat((ActorMessage m) ->
                "done".equals(m.getStatus()) && m.getCharacterId() != null && m.getCharacterId() == 3L));
    }

    /**
     * 全员入睡（深夜时段 23-6 点）→ 本轮直接跳过（0 条回复，不发起任何 AI 评估）。
     */
    @Test
    void autonomousRoundSkipsWhenAllAsleepInDeepNight() {
        stubGroupRound(List.of(gmember(1L, "special", 3L, "玄铁"), gmember(2L, "special", 4L, "钱串子")),
                null, null);
        // 覆盖白天桩：引擎位置 23 时（深夜，后注册者胜出）
        when(worldClockService.currentEngineGameHour(100L)).thenReturn(23L);

        int replies = service.runAutonomousGroupRound(gconv(), 1L, new AtomicBoolean(true));

        assertEquals(0, replies, "深夜全员休息，本轮跳过");
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any(AiChatRequest.class));
        verify(aiChatGateway, never()).chatCompletionStream(anyLong(), anyLong(), any(), any(AiChatRequest.class), any(), any());
    }

    /**
     * 单轮现实时长上限：逐条生成超限即提前结束（防长剧集独占线程阻塞世界）。
     */
    @Test
    void autonomousRoundEndsOnWallClockCap() throws Exception {
        ReflectionTestUtils.setField(service, "roundMaxWallMs", 50L);
        stubGroupRound(List.of(gmember(1L, "special", 3L, "玄铁"), gmember(2L, "special", 4L, "钱串子")),
                "{\"memberIndex\":1,\"desire\":5,\"reason\":\"继续聊\"}", null);
        // O4：群聊改非流式——评估立即返回；回复请求（含 OUTPUT_FORMAT 强制句）注入 60ms 延迟后返回
        Mockito.doAnswer(inv -> {
            AiChatRequest req = inv.getArgument(3);
            boolean replyRequest = req != null && req.messages() != null
                    && req.messages().stream().anyMatch(m -> m.content() != null
                    && m.content().contains("DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON"));
            if (replyRequest) {
                Thread.sleep(60);
                return new AiChatResult("{\"dialogue\":\"再来一句\",\"action\":\"\","
                        + "\"emotion_update\":{\"emotion\":\"平淡\",\"intensity\":1},\"internal_thought\":\"\"}",
                        "test-model", 10, 5, 0, 1L);
            }
            return new AiChatResult("{\"memberIndex\":1,\"desire\":5,\"reason\":\"继续聊\"}",
                    "test-model", 10, 5, 0, 1L);
        }).when(aiChatGateway).chatCompletion(anyLong(), anyLong(), any(), any(AiChatRequest.class));

        int replies = service.runAutonomousGroupRound(gconv(), 1L, new AtomicBoolean(true));

        // 上限 50ms + 首条生成 60ms → 第 2 次循环即超限收尾，仅 1 条回复（无上限时可达 5 条）
        assertEquals(1, replies);
    }

    /**
     * 无实质进展检测：连续 2 条与上一条近乎逐字相同（Dice≥0.9）→ 本轮提前结束
     * （v1 长跑「逐字重复生成」死循环的机制级防护）。
     */
    @Test
    void autonomousRoundEndsOnNoProgress() {
        stubGroupRound(List.of(gmember(1L, "special", 3L, "玄铁"), gmember(2L, "special", 4L, "钱串子")),
                "{\"memberIndex\":1,\"desire\":5,\"reason\":\"再说一遍\"}", "金叶子换烧刀子，值！");

        int replies = service.runAutonomousGroupRound(gconv(), 1L, new AtomicBoolean(true));

        // 首条正常 → 第 2、3 条逐字重复 → 连续 2 次无进展即收尾（共 3 条）
        assertEquals(3, replies);
    }

    /**
     * 玩家触发群聊：发言人评估失败仍走轮询兜底选第一位成员继续发言（体验优先，行为不变）——
     * 与无玩家轮「评估失败即收尾」形成对照（P0 修复仅改无玩家轮，玩家轮保持原行为）。
     */
    @Test
    void playerRoundFallsBackOnEvalFailure() {
        stubGroupRound(List.of(gmember(1L, "special", 3L, "玄铁"), gmember(2L, "special", 4L, "钱串子")),
                "不是合法 JSON", "客官稍等，这就来");

        service.runGroupRound(Mockito.mock(SseEmitter.class), gconv(), 1L, null, new AtomicBoolean(true));

        // 玩家轮评估失败应轮询兜底继续发言（体验优先）——兜底选第一位成员（玄铁 id=3）
        verify(messageRepository, atLeastOnce()).save(argThat((ActorMessage m) ->
                "done".equals(m.getStatus()) && m.getCharacterId() != null && m.getCharacterId() == 3L));
        verify(messageRepository, never()).save(argThat((ActorMessage m) ->
                "done".equals(m.getStatus()) && m.getCharacterId() != null && m.getCharacterId() == 4L));
    }

    /**
     * filterAwakeMembers 单测：深夜时段 → 全员视为休息；白天按成员状态过滤。
     */
    @Test
    void filterAwakeMembersRules() {
        ActorConversation conv = gconv();
        ActorConversationMember special = gmember(1L, "special", 3L, "玄铁");
        ActorConversationMember crowd = gmember(2L, "common", 10L, "摊贩甲");
        List<ActorConversationMember> members = List.of(special, crowd);

        // 深夜（23 时）→ 全员休息
        assertTrue(service.filterAwakeMembers(conv, members, 23L).isEmpty());
        // 深夜边界（6 时整）→ 天亮解禁
        assertFalse(service.filterAwakeMembers(conv, members, 6L).isEmpty());
        // 白天（12 时）+ 无状态行/档案查不到 → 全部清醒
        assertEquals(2, service.filterAwakeMembers(conv, members, 12L).size());

        // 白天 + 普通 NPC 作息运行态 rest（actor_state.activity_state）→ 剔除
        when(stateRepository.findByProjectIdAndNpcRef(100L, "npc:10"))
                .thenReturn(Optional.of(npcRest("rest")));
        List<ActorConversationMember> awake = service.filterAwakeMembers(conv, members, 12L);
        assertEquals(1, awake.size(), "state=rest 的普通 NPC 应被剔除");
        assertEquals("special", awake.get(0).getMemberType());

        // 白天 + 特殊 NPC 入睡活动 → 剔除（普通 NPC 同为 rest → 全员入睡 → 空）
        when(characterRepository.findById(3L)).thenReturn(Optional.of(characterWithActivity("在驻地歇息就寝")));
        assertTrue(service.filterAwakeMembers(conv, members, 12L).isEmpty(), "入睡特殊 NPC 应被剔除");

        // G1a 兼容：member_type 历史值 crowd 经 NpcRefs.normalizeType 归一后同口径生效
        ActorConversationMember legacy = gmember(3L, "crowd", 10L, "摊贩甲");
        List<ActorConversationMember> legacyAwake =
                service.filterAwakeMembers(conv, List.of(legacy), 12L);
        assertTrue(legacyAwake.isEmpty(), "历史 member_type=crowd 仍按普通型剔除");
    }

    /** 普通型 NPC 状态行夹具（G1a：作息运行态唯一家在 actor_state） */
    private ActorNpcState npcRest(String state) {
        ActorNpcState st = new ActorNpcState();
        st.setProjectId(100L);
        st.setNpcRef("npc:10");
        st.setNpcType("common");
        st.setActivityState(state);
        return st;
    }

    // ==================== 记忆检索 query 真实化（2026-10：修复 memory_tier 恒空查询） ====================

    /**
     * 单聊边界：历史只有已完成角色发言（无 user 消息）→ 记忆检索 query 取最后一条实质消息
     * （旧实现只认 role=user，此处必然为 null → memory_tier 恒「（无明确查询）」+ L2 相关性=0）。
     */
    @Test
    void buildReplyMessagesUsesLastSubstantiveTextAsMemoryQuery() {
        ActorConversation conv = new ActorConversation();
        conv.setId(201L);
        conv.setProjectId(100L);
        ActorConversationMember member = new ActorConversationMember();
        member.setCharacterId(3L);
        member.setMemberType("special");
        when(memberRepository.findByConversationId(201L)).thenReturn(List.of(member));
        when(roleplayContextService.resolveCharacterPrompt(100L, "special", 3L)).thenReturn("你是玄铁。");
        when(worldClockService.currentGameHour(100L)).thenReturn(100L);
        when(worldClockService.gameTimeTextOf(100L, 100L)).thenReturn("第三日午时");
        when(conversationContextService.buildContext(anyLong(), anyLong(), any(), anyString())).thenReturn("");
        when(conversationContextService.buildContext(anyLong(), anyLong(), any(), anyString(), any())).thenReturn("");
        when(agentMemoryService.conversationSummaryContext(anyLong(), any())).thenReturn("");
        // 流式占位（中断残留）+ 已完成发言 → query 取后者
        when(messageRepository.findByConversationIdOrderByIdAsc(201L)).thenReturn(List.of(
                msg("assistant", "text", "streaming", "（正在输入…）"),
                msg("assistant", "text", "done", "我刚刚聊到收成的事")));
        when(characterRepository.findById(3L)).thenReturn(Optional.of(character()));

        ActorMessage pending = new ActorMessage();
        pending.setId(100L);
        pending.setCharacterId(3L);
        service.buildReplyMessages(conv, pending, 1L);

        // 记忆检索 query = 最后一条实质消息（跳过 streaming 占位；assistant 也算实质）
        verify(conversationContextService).buildContext(eq(1L), eq(100L), eq(3L),
                eq("我刚刚聊到收成的事"), any());
    }

    /**
     * 群聊纯 NPC↔NPC 自治聊天：历史无 user 消息（只有 assistant/system）→ 记忆检索 query
     * 兜底本场背景（事件回应轮=「世界事件后的自发回应——<事件>…」，恒非空）——
     * 修复「自治聊天（长跑主力）memory_tier 恒空查询」缺陷。
     */
    @Test
    void autonomousGroupChatUsesChatBackgroundAsMemoryQueryWhenNoSubstantiveHistory() {
        stubGroupRound(List.of(gmember(1L, "special", 3L, "玄铁"), gmember(2L, "special", 4L, "钱串子")),
                "{\"memberIndex\":1,\"desire\":4,\"reason\":\"有事可议\"}", "听说了，正要去看看。");
        String background = "世界事件后的自发回应——村口老槐树被雷劈了（此刻在城东茶馆）";

        service.runAutonomousGroupRound(gconv(), 1L, new AtomicBoolean(true), background);

        // 纯 NPC 群聊历史无 user 消息 → 发言人的记忆检索 query 兜底为本场背景（非空）
        verify(conversationContextService, atLeastOnce())
                .buildContext(eq(1L), eq(100L), eq(3L), eq(background));
    }

    // ==================== 2026-10 长跑修复：群聊质量（失败不截断 / 防自言自语） ====================

    /**
     * 一条回复生成失败（模型空输出/解析失败）不得截断整轮——无玩家轮跳过该发言者继续下一拍，
     * 且失败占位消息被<b>删除</b>（不再留「只有角色名没有内容」的 failed/null 空消息）。
     * 修复长跑「AI 偶发空回复 → 对话 1~3 条即断」缺陷。
     */
    @Test
    void autonomousRoundSkipsFailedReplyAndContinues() {
        stubGroupRound(List.of(gmember(1L, "special", 3L, "玄铁"), gmember(2L, "special", 4L, "钱串子")),
                "{\"memberIndex\":1,\"desire\":5,\"reason\":\"先说\"}", "钱串子接上话。");
        // 覆盖通用桩：第 1 次回复生成返回「非 JSON」（解析失败=空回复，占位应被清理），
        // 其后正常；发言评估：第 1 次选玄铁(1)、第 2 次（失败后重选）选钱串子(2)、其后无人想接话收束
        AtomicInteger replyCall = new AtomicInteger(0);
        AtomicInteger decideCall = new AtomicInteger(0);
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any(AiChatRequest.class)))
                .thenAnswer(inv -> {
                    AiChatRequest req = inv.getArgument(3);
                    boolean replyRequest = req != null && req.messages() != null
                            && req.messages().stream().anyMatch(m -> m.content() != null
                            && m.content().contains("DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON"));
                    if (replyRequest) {
                        int n = replyCall.incrementAndGet();
                        if (n == 1) {
                            return new AiChatResult("不是JSON", "test-model", 10, 5, 0, 1L);
                        }
                        return new AiChatResult("{\"dialogue\":\"钱串子接上话。\",\"action\":\"\","
                                + "\"emotion_update\":{\"emotion\":\"平淡\",\"intensity\":1},\"internal_thought\":\"\"}",
                                "test-model", 10, 5, 0, 1L);
                    }
                    int d = decideCall.incrementAndGet();
                    if (d == 1) {
                        return new AiChatResult("{\"memberIndex\":1,\"desire\":5,\"reason\":\"先说\"}",
                                "test-model", 10, 5, 0, 1L);
                    }
                    if (d == 2) {
                        return new AiChatResult("{\"memberIndex\":2,\"desire\":5,\"reason\":\"失败后改选\"}",
                                "test-model", 10, 5, 0, 1L);
                    }
                    return new AiChatResult("{\"memberIndex\":2,\"desire\":1,\"reason\":\"说完了\"}",
                            "test-model", 10, 5, 0, 1L);
                });

        int replies = service.runAutonomousGroupRound(gconv(), 1L, new AtomicBoolean(true));

        // 首条回复失败 → 跳过继续，至少由钱串子(4)成功发言 1 条（整轮未被截断）
        assertTrue(replies >= 1, "一条失败回复不得截断整轮，应继续由其他成员发言");
        verify(messageRepository, atLeastOnce()).save(argThat((ActorMessage m) ->
                "done".equals(m.getStatus()) && m.getCharacterId() != null && m.getCharacterId() == 4L));
        // 失败占位被清理（删除），而非保存为 failed/null 空消息（前端「只有角色名」根因）
        verify(messageRepository, atLeastOnce()).delete(any(ActorMessage.class));
        verify(messageRepository, never()).save(argThat((ActorMessage m) ->
                "failed".equals(m.getStatus()) && m.getCharacterId() != null && m.getCharacterId() == 3L));
    }

    /**
     * 防自言自语（2026-10 长跑修复）：AI 评估再次选中「上一成功发言成员」时，
     * 带「调度注意」回避提示重评估一次，改选其他成员——同一人不再连续发言。
     */
    @Test
    void autonomousRoundAvoidsConsecutiveSameSpeaker() {
        stubGroupRound(List.of(
                        gmember(1L, "special", 3L, "玄铁"),
                        gmember(2L, "special", 4L, "钱串子"),
                        gmember(3L, "special", 5L, "王五")),
                "{\"memberIndex\":1,\"desire\":5,\"reason\":\"继续\"}", "王五接话。");
        // 本场已由玄铁(3)发过言（非开场轮）→ 调度应回避「玄铁连续再发言」
        stubGroupHistory(speakerMessage(3L, "玄铁", "我先说一句。"));
        AtomicInteger decideCall = new AtomicInteger(0);
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any(AiChatRequest.class)))
                .thenAnswer(inv -> {
                    AiChatRequest req = inv.getArgument(3);
                    boolean replyRequest = req != null && req.messages() != null
                            && req.messages().stream().anyMatch(m -> m.content() != null
                            && m.content().contains("DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON"));
                    if (replyRequest) {
                        return new AiChatResult("{\"dialogue\":\"王五接话。\",\"action\":\"\","
                                + "\"emotion_update\":{\"emotion\":\"平淡\",\"intensity\":1},\"internal_thought\":\"\"}",
                                "test-model", 10, 5, 0, 1L);
                    }
                    int d = decideCall.incrementAndGet();
                    if (d == 1) {
                        // 第一次评估仍指向上一发言者玄铁(1) → 触发回避重试
                        return new AiChatResult("{\"memberIndex\":1,\"desire\":5,\"reason\":\"还接话\"}",
                                "test-model", 10, 5, 0, 1L);
                    }
                    if (d == 2) {
                        return new AiChatResult("{\"memberIndex\":2,\"desire\":5,\"reason\":\"回避后改选\"}",
                                "test-model", 10, 5, 0, 1L);
                    }
                    return new AiChatResult("{\"memberIndex\":2,\"desire\":1,\"reason\":\"说完了\"}",
                            "test-model", 10, 5, 0, 1L);
                });

        int replies = service.runAutonomousGroupRound(gconv(), 1L, new AtomicBoolean(true));

        // 成功发言来自钱串子(4)（回避掉上一发言者玄铁(3)），而非玄铁连说
        assertTrue(replies >= 1, "上一发言者不应被连续选中发言");
        verify(messageRepository, atLeastOnce()).save(argThat((ActorMessage m) ->
                "done".equals(m.getStatus()) && m.getCharacterId() != null && m.getCharacterId() == 4L));
        verify(messageRepository, never()).save(argThat((ActorMessage m) ->
                "done".equals(m.getStatus()) && m.getCharacterId() != null && m.getCharacterId() == 3L));
        // 回避提示段确实注入了发言人评估 prompt（第二次评估含「调度注意」）
        ArgumentCaptor<AiChatRequest> reqCap = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway, atLeastOnce()).chatCompletion(anyLong(), anyLong(), any(), reqCap.capture());
        boolean sawAvoidNote = reqCap.getAllValues().stream().anyMatch(r -> r.messages() != null
                && r.messages().stream().anyMatch(m -> m.content() != null && m.content().contains("调度注意")));
        assertTrue(sawAvoidNote, "回避重试的评估 prompt 应包含「调度注意」段");
    }
}
