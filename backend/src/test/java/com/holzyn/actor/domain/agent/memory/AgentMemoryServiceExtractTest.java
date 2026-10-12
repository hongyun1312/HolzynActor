package com.holzyn.actor.domain.agent.memory;

import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationMemberRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorMessageRepository;
import com.holzyn.actor.domain.conversation.service.ConversationService;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

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
 * 记忆组件对话侧收编（C2：AgentMemoryService 吸收 MemoryService 注入/抽取）单元测试
 * （domain/agent/memory，同包）。
 * <p>职责：验证——memoryContext 注入（角色级 fact+observation + 项目级【世界大事记】）、
 * extractAfterRound 寒暄门控（无实质内容跳过提取，不落库不调 AI）。</p>
 */
class AgentMemoryServiceExtractTest {

    private ActorMemoryRepository memoryRepository;
    private ActorMessageRepository messageRepository;
    private ActorConversationMemberRepository memberRepository;
    private ConversationService conversationService;
    private com.holzyn.actor.domain.memory.service.MemoryWorkingService workingService;
    private com.holzyn.actor.domain.memory.service.CoreMemoryService coreService;
    private AgentMemoryService service;

    @BeforeEach
    void setUp() {
        memoryRepository = Mockito.mock(ActorMemoryRepository.class);
        messageRepository = Mockito.mock(ActorMessageRepository.class);
        memberRepository = Mockito.mock(ActorConversationMemberRepository.class);
        conversationService = Mockito.mock(ConversationService.class);
        workingService = Mockito.mock(com.holzyn.actor.domain.memory.service.MemoryWorkingService.class);
        coreService = Mockito.mock(com.holzyn.actor.domain.memory.service.CoreMemoryService.class);
        service = new AgentMemoryService(memoryRepository);
        // 对话侧字段注入（构造器单参不变；单元测试经反射回填）
        ReflectionTestUtils.setField(service, "messageRepository", messageRepository);
        ReflectionTestUtils.setField(service, "memberRepository", memberRepository);
        ReflectionTestUtils.setField(service, "conversationService", conversationService);
        ReflectionTestUtils.setField(service, "workingService", workingService);
        ReflectionTestUtils.setField(service, "coreService", coreService);
        ReflectionTestUtils.setField(service, "memoryEnabled", true);
        ReflectionTestUtils.setField(service, "topK", 8);
        ReflectionTestUtils.setField(service, "projectTopK", 4);
        ReflectionTestUtils.setField(service, "gateEnabled", true);
        ReflectionTestUtils.setField(service, "greetingMaxLen", 20);
        ReflectionTestUtils.setField(service, "groupGreetingMaxLen", 8);
        when(memoryRepository.save(any(ActorMemory.class))).thenAnswer(inv -> inv.getArgument(0));
        when(workingService.injectWorkingSegment(anyLong(), anyString())).thenReturn("");
        // M3 L3 核心记忆注入默认空（存量用例不受影响）
        when(coreService.renderCoreSegment(anyLong(), anyString())).thenReturn("");
    }

    /** 构造记忆（M1 起 type 取代 kind，type 可配） */
    private ActorMemory mem(long id, String type, int importance, LocalDateTime createdAt, String content) {
        ActorMemory m = new ActorMemory();
        m.setId(id);
        m.setProjectId(100L);
        m.setCharacterId(3L);
        m.setCharacterRef("npc:3");
        m.setType(type);
        m.setImportance(importance);
        m.setCreatedAt(createdAt);
        m.setDeleted(0);
        m.setContent(content);
        return m;
    }

    /**
     * memoryContext（收编后）：角色级 fact+observation 一并注入 + 项目级【世界大事记】注入。
     */
    @Test
    void memoryContextIncludesObservationFactAndProjectSection() {
        LocalDateTime t = LocalDateTime.of(2026, 8, 21, 10, 0);
        when(memoryRepository.findByProjectIdAndCharacterIdAndTypeInAndDeletedOrderByCreatedAtDesc(
                eq(100L), eq(3L), anyList(), eq(0)))
                .thenReturn(List.of(
                        mem(1L, "semantic", 5, t.minusHours(1), "师傅叫玄铁，隐居云雾山"),
                        mem(2L, "episodic", 4, t.minusMinutes(5), "前往城东市场采购药材（目标：城东市场）")));
        when(memoryRepository.findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(100L, 0))
                .thenReturn(List.of(mem(3L, "semantic", 5, t.minusDays(1), "世界树枯萎引发各国动荡")));

        String ctx = service.memoryContext(1L, 100L, 3L);

        assertTrue(ctx.contains("【长期记忆】"), "应含角色级记忆段");
        assertTrue(ctx.contains("前往城东市场采购药材"), "observation 行动经历应被注入（行动后可回忆）");
        assertTrue(ctx.contains("师傅叫玄铁"), "fact 关键事实仍注入");
        assertTrue(ctx.contains("【世界大事记】"), "应含项目级大事记段");
        assertTrue(ctx.contains("世界树枯萎引发各国动荡"));
    }

    /**
     * memoryContext（M2）：注入顺序含 L1【当前情境】热缓冲段（位于【长期记忆】之前）。
     */
    @Test
    void memoryContextIncludesWorkingSegment() {
        LocalDateTime t = LocalDateTime.of(2026, 8, 21, 10, 0);
        when(workingService.injectWorkingSegment(100L, "npc:3"))
                .thenReturn("【当前情境】以下是该角色当前进行中的关注项（工作记忆），回答时自然参考：\n- [任务] 当前行动：巡视会馆");
        when(memoryRepository.findByProjectIdAndCharacterIdAndTypeInAndDeletedOrderByCreatedAtDesc(
                eq(100L), eq(3L), anyList(), eq(0)))
                .thenReturn(List.of(mem(1L, "semantic", 5, t.minusHours(1), "师傅叫玄铁，隐居云雾山")));
        when(memoryRepository.findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(100L, 0))
                .thenReturn(List.of());

        String ctx = service.memoryContext(1L, 100L, 3L);

        assertTrue(ctx.contains("【当前情境】"), "M2 应注入 L1 工作记忆段");
        assertTrue(ctx.contains("当前行动：巡视会馆"));
        assertTrue(ctx.indexOf("【当前情境】") < ctx.indexOf("【长期记忆】"), "L1 段应在【长期记忆】之前");
    }

    /**
     * memoryContext（M3）：注入 L3【核心记忆】恒在段（位于【当前情境】L1 之前，注入组合首位）。
     */
    @Test
    void memoryContextIncludesCoreSegmentFirst() {
        when(coreService.renderCoreSegment(100L, "npc:3"))
                .thenReturn("【核心记忆】以下是该角色恒定在上下文的核心身份信息（静态基线 + 核心块），回答时始终以此为准：\n—— 身份基线 ——\n身份：阿明");
        when(workingService.injectWorkingSegment(100L, "npc:3"))
                .thenReturn("【当前情境】以下是该角色当前进行中的关注项（工作记忆），回答时自然参考：\n- [任务] 当前行动：巡视会馆");
        when(memoryRepository.findByProjectIdAndCharacterIdAndTypeInAndDeletedOrderByCreatedAtDesc(
                eq(100L), eq(3L), anyList(), eq(0)))
                .thenReturn(List.of(mem(1L, "semantic", 5, LocalDateTime.now().minusHours(1), "师傅叫玄铁，隐居云雾山")));
        when(memoryRepository.findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(100L, 0))
                .thenReturn(List.of());

        String ctx = service.memoryContext(1L, 100L, 3L);

        assertTrue(ctx.contains("【核心记忆】"), "M3 应注入 L3 核心记忆段");
        assertTrue(ctx.indexOf("【核心记忆】") < ctx.indexOf("【当前情境】"), "L3 恒在段应在 L1 之前（注入组合首位）");
        assertTrue(ctx.indexOf("【当前情境】") < ctx.indexOf("【长期记忆】"), "L1 段仍应在【长期记忆】之前");
    }

    /**
     * extractAfterRound 寒暄门控：最近一轮为寒暄（≤20 字含寒暄词）→ 跳过提取，不落新事实
     * （连 AI 都不调用，省 token）。
     */
    @Test
    void extractAfterRoundGreetingSkipsExtraction() {
        ActorConversation conv = new ActorConversation();
        conv.setId(9L);
        conv.setProjectId(100L);
        conv.setMode("single");
        when(conversationService.requireOwned(9L, 1L)).thenReturn(conv);
        ActorMessage user = new ActorMessage();
        user.setRole("user");
        user.setType("text");
        user.setStatus("done");
        user.setContent("好的");
        when(messageRepository.findByConversationIdOrderByIdAsc(9L)).thenReturn(List.of(user));
        // 会话首个成员（special:3，记忆归属解析用；角色级记忆抽取目标）
        com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember member =
                new com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember();
        member.setCharacterId(3L);
        member.setMemberType("special");
        when(memberRepository.findByConversationId(9L)).thenReturn(List.of(member));

        service.extractAfterRound(9L, 1L, false);

        // 寒暄跳过：不会走到 saveMemoryFor（新事实/摘要写入）路径（M1 起 type=semantic 取代 kind=fact）
        verify(memoryRepository, never()).save(Mockito.argThat(m -> m.getType() != null && "semantic".equals(m.getType())));
    }

    // ==================== M5.5 微层接线：AI 强度档位（记忆抽取） ====================

    /** 构造档位闸门（mock 引擎行，project=100）并注入服务。 */
    private com.holzyn.actor.domain.agent.runtime.AiIntensityGate gateOf(String level) {
        com.holzyn.actor.domain.agent.runtime.ActorWorldEngineRepository repo =
                Mockito.mock(com.holzyn.actor.domain.agent.runtime.ActorWorldEngineRepository.class);
        com.holzyn.actor.domain.agent.runtime.ActorWorldEngine engine =
                new com.holzyn.actor.domain.agent.runtime.ActorWorldEngine();
        engine.setProjectId(100L);
        engine.setAiIntensity(level);
        when(repo.findByProjectId(100L)).thenReturn(java.util.Optional.of(engine));
        com.holzyn.actor.domain.agent.runtime.AiIntensityGate gate =
                new com.holzyn.actor.domain.agent.runtime.AiIntensityGate(repo);
        ReflectionTestUtils.setField(service, "aiIntensityGate", gate);
        return gate;
    }

    /** 构造单聊会话（有实质内容，归属 special:3）。 */
    private void stubConversation() {
        ActorConversation conv = new ActorConversation();
        conv.setId(9L);
        conv.setProjectId(100L);
        conv.setMode("single");
        when(conversationService.requireOwned(9L, 1L)).thenReturn(conv);
        ActorMessage user = new ActorMessage();
        user.setRole("user");
        user.setType("text");
        user.setStatus("done");
        user.setContent("我今天把粮仓里的陈粮都盘了一遍，打算明日低价放给村里人。");
        when(messageRepository.findByConversationIdOrderByIdAsc(9L)).thenReturn(List.of(user));
        com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember member =
                new com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember();
        member.setCharacterId(3L);
        member.setMemberType("special");
        when(memberRepository.findByConversationId(9L)).thenReturn(List.of(member));
    }

    /**
     * programmatic 档：抽取整体跳过（零 AI）——不落新事实、不调 AI（机制兜底=不抽取）。
     */
    @Test
    void extractAfterRound_programmaticSkipsExtractionEntirely() {
        stubConversation();
        gateOf("programmatic");

        service.extractAfterRound(9L, 1L, false);

        verify(memoryRepository, never()).save(Mockito.argThat(m -> m.getType() != null && "semantic".equals(m.getType())));
    }

    /**
     * light 档：保留 AI 抽取、跳过「按需 AI 复核」——仅 1 次 AI 调用（抽取），复核零调用。
     */
    @Test
    void extractAfterRound_lightSkipsAiReview() {
        stubConversation();
        gateOf("light");
        com.holzyn.actor.ai.spring.AiSpringChatGateway aiChatGateway =
                Mockito.mock(com.holzyn.actor.ai.spring.AiSpringChatGateway.class);
        com.holzyn.actor.domain.settings.service.PromptTemplateService promptTemplateService =
                Mockito.mock(com.holzyn.actor.domain.settings.service.PromptTemplateService.class);
        com.holzyn.actor.domain.usage.service.UsageLogService usageLogService =
                Mockito.mock(com.holzyn.actor.domain.usage.service.UsageLogService.class);
        com.holzyn.actor.domain.account.CurrentUserProvider currentUserProvider =
                Mockito.mock(com.holzyn.actor.domain.account.CurrentUserProvider.class);
        ReflectionTestUtils.setField(service, "aiChatGateway", aiChatGateway);
        ReflectionTestUtils.setField(service, "promptTemplateService", promptTemplateService);
        ReflectionTestUtils.setField(service, "usageLogService", usageLogService);
        ReflectionTestUtils.setField(service, "currentUserProvider", currentUserProvider);
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("抽取 prompt");
        when(promptTemplateService.systemMessage(anyLong(), anyLong(), anyString(), any())).thenReturn(null);
        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any(com.holzyn.actor.ai.AiChatRequest.class)))
                .thenReturn(new com.holzyn.actor.ai.AiChatResult(
                        "{\"memories\":[{\"target\":\"character\",\"operation\":\"add\",\"kind\":\"fact\","
                                + "\"content\":\"甲计划明日开仓放粮\",\"importance\":4}]}",
                        "test-model", 10, 10, 20, 1L));

        service.extractAfterRound(9L, 1L, false);

        // 仅抽取 1 次 AI 调用；复核（memory_extract_review）零调用（light 档跳过）
        verify(aiChatGateway, Mockito.times(1))
                .chatCompletion(anyLong(), anyLong(), any(), any(com.holzyn.actor.ai.AiChatRequest.class));
    }
}
