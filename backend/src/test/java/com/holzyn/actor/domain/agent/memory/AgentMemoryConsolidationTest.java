package com.holzyn.actor.domain.agent.memory;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationMemberRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorMessageRepository;
import com.holzyn.actor.domain.conversation.service.ConversationService;
import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.memory.service.MemoryWorkingService;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆 M2 双缓冲（L1 草稿 → 会话结束巩固 → L2）编排单元测试（domain/agent/memory，同包）。
 * <p>职责：验证——逐轮抽取产出 draft 写 L1（不直落 L2 事实）、会话结束巩固调用 L1 巩固 +
 * 强制摘要直写 L2（source=dialog + subtype=summary）、摘要间隔直写 L2 行为保留。</p>
 * <p>设计依据：《记忆系统分层重构 L1/L2/L3/归档 设计方案 V1.0》§5.2/§5.3/§6.2。</p>
 */
class AgentMemoryConsolidationTest {

    private ActorMemoryRepository memoryRepository;
    private ActorMessageRepository messageRepository;
    private ActorConversationMemberRepository memberRepository;
    private ActorCharacterRepository characterRepository;
    private ConversationService conversationService;
    private PromptTemplateService promptTemplateService;
    private AiSpringChatGateway aiChatGateway;
    private UsageLogService usageLogService;
    private MemoryWorkingService workingService;
    private AgentMemoryService service;

    @BeforeEach
    void setUp() {
        memoryRepository = Mockito.mock(ActorMemoryRepository.class);
        messageRepository = Mockito.mock(ActorMessageRepository.class);
        memberRepository = Mockito.mock(ActorConversationMemberRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        conversationService = Mockito.mock(ConversationService.class);
        promptTemplateService = Mockito.mock(PromptTemplateService.class);
        aiChatGateway = Mockito.mock(AiSpringChatGateway.class);
        usageLogService = Mockito.mock(UsageLogService.class);
        workingService = Mockito.mock(MemoryWorkingService.class);
        service = new AgentMemoryService(memoryRepository);
        ReflectionTestUtils.setField(service, "messageRepository", messageRepository);
        ReflectionTestUtils.setField(service, "memberRepository", memberRepository);
        ReflectionTestUtils.setField(service, "characterRepository", characterRepository);
        ReflectionTestUtils.setField(service, "conversationService", conversationService);
        ReflectionTestUtils.setField(service, "promptTemplateService", promptTemplateService);
        ReflectionTestUtils.setField(service, "aiChatGateway", aiChatGateway);
        ReflectionTestUtils.setField(service, "usageLogService", usageLogService);
        ReflectionTestUtils.setField(service, "workingService", workingService);
        ReflectionTestUtils.setField(service, "memoryEnabled", true);
        ReflectionTestUtils.setField(service, "topK", 8);
        ReflectionTestUtils.setField(service, "projectTopK", 4);
        ReflectionTestUtils.setField(service, "summaryInterval", 10);
        ReflectionTestUtils.setField(service, "gateEnabled", true);
        ReflectionTestUtils.setField(service, "greetingMaxLen", 20);
        ReflectionTestUtils.setField(service, "groupGreetingMaxLen", 8);
        when(memoryRepository.save(any(ActorMemory.class))).thenAnswer(inv -> inv.getArgument(0));
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("prompt");
    }

    private ActorConversation conv(long id, String mode) {
        ActorConversation c = new ActorConversation();
        c.setId(id);
        c.setProjectId(100L);
        c.setMode(mode);
        return c;
    }

    private ActorMessage msg(String role, String type, String status, Long characterId, String content) {
        ActorMessage m = new ActorMessage();
        m.setRole(role);
        m.setType(type);
        m.setStatus(status);
        m.setCharacterId(characterId);
        m.setContent(content);
        return m;
    }

    private void stubSingleChat(long convId, List<ActorMessage> history) {
        ActorConversation c = conv(convId, "single");
        when(conversationService.requireOwned(convId, 1L)).thenReturn(c);
        when(messageRepository.findByConversationIdOrderByIdAsc(convId)).thenReturn(history);
        ActorConversationMember member = new ActorConversationMember();
        member.setCharacterId(3L);
        member.setMemberType("special");
        when(memberRepository.findByConversationId(convId)).thenReturn(List.of(member));
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setName("玄铁");
        when(characterRepository.findById(3L)).thenReturn(Optional.of(ch));
    }

    /**
     * 逐轮抽取（M1 记忆系统重构三重机制）：AI 抽取重要度 4 的事实 → 直进 L2（semantic，
     * 不再先落草稿——设计 §3.2「3+ 直进 L2」；重要度 2 才走 L1 draft 双缓冲，见
     * AgentMemoryTripleMechanismTest.importanceTwoGoesToDraft）。
     */
    @Test
    void extractAfterRoundDirectsHighImportanceToL2() {
        stubSingleChat(9L, List.of(
                msg("user", "text", "done", null, "我们在找隐居云雾山的玄铁师傅"),
                msg("assistant", "text", "done", 3L, "玄铁师傅隐居云雾山多年，是位老铁匠")));
        when(memoryRepository.findByProjectIdAndCharacterIdAndTypeInAndDeletedOrderByCreatedAtDesc(
                eq(100L), eq(3L), anyList(), eq(0))).thenReturn(List.of());
        // AI 抽取返回一条重要度 4 的事实（M1 契约 kind=fact；3+ 直进 L2）
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult(
                        "[{\"kind\":\"fact\",\"content\":\"玄铁是隐居云雾山的铁匠\",\"importance\":4}]",
                        "test-model", 100, 50, 150, 1L));

        service.extractAfterRound(9L, 1L, false);

        // 重要度 4 直进 L2 semantic（不再写 L1 草稿）
        verify(memoryRepository).save(Mockito.argThat(m ->
                "semantic".equals(m.getType())
                        && "玄铁是隐居云雾山的铁匠".equals(m.getContent())
                        && !ActorMemoryTypes.isSummary(m.getType(), m.getContextJson())));
        verify(workingService, never()).writeDraft(anyLong(), anyString(), anyString(), anyString(), any());
    }

    /**
     * 会话结束巩固（forceSummary）：① 调用 L1 巩固（草稿复核提升）；② 强制会话摘要直写 L2
     * （type=semantic + context_json subtype=summary，source 走既有 dialog 摘要路径）。
     */
    @Test
    void consolidateByConversationForcesSummaryToL2() {
        stubSingleChat(9L, List.of(
                msg("user", "text", "done", null, "玄铁师傅，久仰大名"),
                msg("assistant", "text", "done", 3L, "不必多礼，我确实隐居在云雾山")));
        // 摘要 AI 返回 {content:...}
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult(
                        "{\"content\":\"与玄铁师傅交谈，得知他隐居云雾山多年\"}",
                        "test-model", 100, 50, 150, 1L));

        service.consolidateByConversation(9L, 1L, true);

        // ① L1 巩固被调用（草稿提升在 MemoryWorkingService 内部）
        verify(workingService).consolidateSession("9");
        // ② 强制摘要直写 L2（subtype=summary）
        ArgumentCaptor<ActorMemory> cap = ArgumentCaptor.forClass(ActorMemory.class);
        verify(memoryRepository).save(cap.capture());
        ActorMemory summary = cap.getValue();
        assertEquals("semantic", summary.getType());
        assertTrue(ActorMemoryTypes.isSummary(summary.getType(), summary.getContextJson()),
                "会话摘要应带 context_json subtype=summary");
        assertEquals(Long.valueOf(3L), summary.getCharacterId(), "特殊角色摘要按 character_id 双轨写");
        assertEquals("与玄铁师傅交谈，得知他隐居云雾山多年", summary.getContent());
        assertNotNull(summary.getImportance());
    }

    /**
     * 会话结束巩固（forceSummary=false，归档收尾场景）：仅巩固 L1，不生成强制摘要。
     */
    @Test
    void consolidateByConversationWithoutSummary() {
        stubSingleChat(9L, List.of(
                msg("user", "text", "done", null, "在集市上见到石鳞在叫卖")));

        service.consolidateByConversation(9L, 1L, false);

        verify(workingService).consolidateSession("9");
        verify(memoryRepository, never()).save(any(ActorMemory.class));
    }

    /**
     * 批 4 记忆与反思链·段 2F 对齐：会话摘要（memory_summarize）请求前置 memoryChainContext
     * （段1A 世界观 + 段1C 角色卡 + 段2D 世界动态 + 段2E 状态）。
     */
    @Test
    void consolidateByConversationPrependsMemoryChainContextToSummary() {
        stubSingleChat(9L, List.of(
                msg("user", "text", "done", null, "玄铁师傅，久仰大名"),
                msg("assistant", "text", "done", 3L, "不必多礼，我确实隐居在云雾山")));
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(injector.memoryChainContext(eq(100L), eq("npc:3"), any()))
                .thenReturn("【世界观】\n青禾村四面环山。\n\n【我的身份与处境】\n姓名：玄铁\n\n"
                        + "【当前世界动态】\n世界历 0420-05-15 06时00分 · 夏季\n\n【当前状态】\n- 需求：饥饿 68/100（略饿）");
        ReflectionTestUtils.setField(service, "aiContextInjector", injector);
        // 摘要 AI 返回 {content:...}
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult(
                        "{\"content\":\"与玄铁师傅交谈，得知他隐居云雾山多年\"}",
                        "test-model", 100, 50, 150, 1L));

        service.consolidateByConversation(9L, 1L, true);

        // 捕获实际请求：user 消息应前置记忆链段位（段1A 世界观 + 段1C 角色卡 + 段2D 世界动态 + 段2E 状态）
        org.mockito.ArgumentCaptor<AiChatRequest> reqCaptor =
                org.mockito.ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(anyLong(), anyLong(), any(), reqCaptor.capture());
        String user = reqCaptor.getValue().messages().get(1).content();
        assertTrue(user.contains("【世界观】"), "应含段1A世界观: " + user);
        assertTrue(user.contains("【我的身份与处境】"), "应含段1C角色卡: " + user);
        assertTrue(user.contains("【当前世界动态】"), "应含段2D世界动态: " + user);
        assertTrue(user.contains("【当前状态】"), "应含段2E当前状态: " + user);
        assertTrue(user.contains("prompt"), "模板仍在注入段之后: " + user);
    }

    /**
     * 手动补抽：先逐轮抽取（M1 起重要度 4 直进 L2）再立即巩固（L1 无草稿时巩固为空操作），
     * 新增计数=L2 事实数变化（mock 返回 0 变化）。
     */
    @Test
    void extractForCharacterConsolidatesAfterExtract() {
        stubSingleChat(9L, List.of(
                msg("user", "text", "done", null, "我们在找隐居云雾山的玄铁师傅"),
                msg("assistant", "text", "done", 3L, "玄铁师傅隐居云雾山多年")));
        when(memoryRepository.findByProjectIdAndCharacterIdAndTypeInAndDeletedOrderByCreatedAtDesc(
                eq(100L), eq(3L), anyList(), eq(0))).thenReturn(List.of());
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult(
                        "[{\"kind\":\"fact\",\"content\":\"玄铁是隐居云雾山的铁匠\",\"importance\":4}]",
                        "test-model", 100, 50, 150, 1L));
        when(conversationService.requireOwned(eq(9L), eq(1L))).thenReturn(conv(9L, "single"));
        when(conversationService.requireOwned(9L)).thenReturn(conv(9L, "single")); // 补抽目标会话单参重载
        when(memoryRepository.findByProjectIdAndCharacterIdAndTypeAndDeletedOrderByCreatedAtDesc(
                eq(100L), eq(3L), eq("semantic"), eq(0))).thenReturn(List.of());
        com.holzyn.actor.domain.account.CurrentUserProvider provider =
                Mockito.mock(com.holzyn.actor.domain.account.CurrentUserProvider.class);
        ReflectionTestUtils.setField(service, "currentUserProvider", provider);
        when(provider.currentUserId()).thenReturn(1L);
        // requireProject 依赖项目仓库
        com.holzyn.actor.domain.project.repository.ActorProjectRepository projectRepository =
                Mockito.mock(com.holzyn.actor.domain.project.repository.ActorProjectRepository.class);
        ReflectionTestUtils.setField(service, "projectRepository", projectRepository);
        com.holzyn.actor.domain.project.entity.ActorProject project = new com.holzyn.actor.domain.project.entity.ActorProject();
        project.setId(100L);
        project.setUserId(1L);
        when(projectRepository.findByIdAndUserIdAndDeleted(100L, 1L, 0)).thenReturn(Optional.of(project));
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setProjectId(100L);
        when(characterRepository.findById(3L)).thenReturn(Optional.of(ch));
        // 无参与会话成员的预检查走 members 空 → 跳过。为测通补抽，模拟有成员。
        when(memberRepository.findByCharacterId(3L))
                .thenReturn(List.of(memberOf(9L, 3L, "special")));

        int added = service.extractForCharacter(3L);

        assertEquals(0, added, "L2 无新增（mock 返回 0 变化）");
        // M1 起重要度 4 直进 L2 semantic；L1 巩固照常执行（无草稿时空操作）
        verify(memoryRepository).save(Mockito.argThat(m ->
                "semantic".equals(m.getType())
                        && "玄铁是隐居云雾山的铁匠".equals(m.getContent())));
        verify(workingService).consolidateSession("9");
    }

    private ActorConversationMember memberOf(long convId, long characterId, String type) {
        ActorConversationMember m = new ActorConversationMember();
        m.setConversationId(convId);
        m.setCharacterId(characterId);
        m.setMemberType(type);
        return m;
    }
}
