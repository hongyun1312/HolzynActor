package com.holzyn.actor.domain.agent.memory;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.conversation.service.ConversationService;
import com.holzyn.actor.domain.memory.service.MemoryArchiveService;
import com.holzyn.actor.domain.memory.service.MemoryWorkingService;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationMemberRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorMessageRepository;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.service.WorldMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆抽取三重机制单元测试（记忆系统重构 M1，2026-09-12，domain/agent/memory）。
 * <p>职责：验证 extractAfterRound 的三重机制——重要度分级（1 丢 / 2 进 L1 草稿 / 3+ 直进 L2）、
 * 操作判定（update 版本链 supersedes + 旧条归档 / invalidate 旧条软删归档不写新条）、
 * 双通道路由（target=world → WorldMemoryService.routeFromExtraction）、按需 AI 复核触发。</p>
 * <p>设计依据：《记忆系统重构与世界时间季节系统_设计方案_V1.0》§3.2/§3.4/§4.2。</p>
 */
class AgentMemoryTripleMechanismTest {

    private ActorMemoryRepository memoryRepository;
    private ActorMessageRepository messageRepository;
    private ActorConversationMemberRepository memberRepository;
    private ConversationService conversationService;
    private ActorCharacterRepository characterRepository;
    private PromptTemplateService promptTemplateService;
    private AiSpringChatGateway aiChatGateway;
    private UsageLogService usageLogService;
    private MemoryWorkingService workingService;
    private MemoryArchiveService archiveService;
    private WorldMemoryService worldMemoryService;
    private AgentMemoryService service;

    @BeforeEach
    void setUp() {
        memoryRepository = Mockito.mock(ActorMemoryRepository.class);
        messageRepository = Mockito.mock(ActorMessageRepository.class);
        memberRepository = Mockito.mock(ActorConversationMemberRepository.class);
        conversationService = Mockito.mock(ConversationService.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        promptTemplateService = Mockito.mock(PromptTemplateService.class);
        aiChatGateway = Mockito.mock(AiSpringChatGateway.class);
        usageLogService = Mockito.mock(UsageLogService.class);
        workingService = Mockito.mock(MemoryWorkingService.class);
        archiveService = Mockito.mock(MemoryArchiveService.class);
        worldMemoryService = Mockito.mock(WorldMemoryService.class);
        service = new AgentMemoryService(memoryRepository);
        ReflectionTestUtils.setField(service, "memoryEnabled", true);
        ReflectionTestUtils.setField(service, "gateEnabled", false); // 聚焦三重机制，跳过寒暄门控
        ReflectionTestUtils.setField(service, "extractDropBelow", 1);
        ReflectionTestUtils.setField(service, "extractDraftBelow", 3);
        ReflectionTestUtils.setField(service, "extractReviewThreshold", 3);
        ReflectionTestUtils.setField(service, "extractReviewEnabled", false); // 多数用例关闭复核调用
        ReflectionTestUtils.setField(service, "supersedeMatchThreshold", 0.4);
        ReflectionTestUtils.setField(service, "dedupThreshold", 0.6);
        ReflectionTestUtils.setField(service, "summaryInterval", 100);
        ReflectionTestUtils.setField(service, "budget", 50);
        ReflectionTestUtils.setField(service, "messageRepository", messageRepository);
        ReflectionTestUtils.setField(service, "memberRepository", memberRepository);
        ReflectionTestUtils.setField(service, "conversationService", conversationService);
        ReflectionTestUtils.setField(service, "characterRepository", characterRepository);
        ReflectionTestUtils.setField(service, "promptTemplateService", promptTemplateService);
        ReflectionTestUtils.setField(service, "aiChatGateway", aiChatGateway);
        ReflectionTestUtils.setField(service, "usageLogService", usageLogService);
        ReflectionTestUtils.setField(service, "workingService", workingService);
        ReflectionTestUtils.setField(service, "archiveService", archiveService);
        ReflectionTestUtils.setField(service, "worldMemoryService", worldMemoryService);

        ActorConversation conv = new ActorConversation();
        conv.setId(1L);
        conv.setProjectId(100L);
        conv.setMode("single");
        conv.setLastMessageAt(LocalDateTime.now());
        when(conversationService.requireOwned(1L, 1L)).thenReturn(conv);
        ActorCharacter ch = new ActorCharacter();
        ch.setId(10L);
        ch.setName("沈砚秋");
        when(characterRepository.findById(10L)).thenReturn(java.util.Optional.of(ch));
        ActorMessage userMsg = new ActorMessage();
        userMsg.setRole("user");
        userMsg.setContent("沈村长，村口的老槐树昨夜被雷劈断了大半，惊动了好些人。");
        ActorMessage asstMsg = new ActorMessage();
        asstMsg.setRole("assistant");
        asstMsg.setStatus("done");
        asstMsg.setCharacterId(10L);
        asstMsg.setContent("竟有此事？我这就去村口看看，怕是百年古树要就此折损了。");
        when(messageRepository.findByConversationIdOrderByIdAsc(1L)).thenReturn(List.of(userMsg, asstMsg));
        ActorConversationMember member = new ActorConversationMember();
        member.setConversationId(1L);
        member.setCharacterId(10L);
        member.setMemberType("special");
        when(memberRepository.findByConversationId(1L)).thenReturn(List.of(member));
        when(memoryRepository.findByProjectIdAndCharacterIdAndTypeInAndDeletedOrderByCreatedAtDesc(
                anyLong(), anyLong(), anyList(), eq(0))).thenReturn(List.of());
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("prompt");
        when(workingService.writeDraft(anyLong(), anyString(), anyString(), anyString(), any())).thenReturn(true);
        when(memoryRepository.save(any(ActorMemory.class))).thenAnswer(inv -> {
            ActorMemory m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId(99L);
            }
            return m;
        });
    }

    private void stubExtract(String aiJson) {
        when(aiChatGateway.chatCompletion(eq(1L), eq(100L), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult(aiJson, "test-model", 10, 10, 20, 1L));
    }

    /**
     * 重要度分级①：importance=1 直接丢弃（不写草稿不写 L2）。
     */
    @Test
    void importanceOneIsDropped() {
        stubExtract("[{\"content\":\"今天的天气不错\",\"importance\":1}]");
        service.extractAfterRound(1L, 1L);
        verify(workingService, never()).writeDraft(anyLong(), anyString(), anyString(), anyString(), any());
        verify(memoryRepository, never()).save(any(ActorMemory.class));
    }

    /**
     * 重要度分级②：importance=2 进 L1 草稿（会话结束巩固时复核提升）。
     */
    @Test
    void importanceTwoGoesToDraft() {
        stubExtract("[{\"content\":\"沈村长说今年秋收后要修村口的石桥\",\"importance\":2}]");
        service.extractAfterRound(1L, 1L);
        verify(workingService).writeDraft(eq(100L), eq("npc:10"), eq("1"),
                eq("沈村长说今年秋收后要修村口的石桥"), eq(2));
        verify(memoryRepository, never()).save(any(ActorMemory.class));
    }

    /**
     * 重要度分级③：importance=3 直进 L2（semantic，不再先落草稿）。
     */
    @Test
    void importanceThreeGoesDirectToL2() {
        stubExtract("[{\"content\":\"村口老槐树被雷劈断了大半\",\"importance\":4}]");
        service.extractAfterRound(1L, 1L);
        verify(workingService, never()).writeDraft(anyLong(), anyString(), anyString(), anyString(), any());
        verify(memoryRepository).save(Mockito.argThat(m -> m instanceof ActorMemory
                && "semantic".equals(((ActorMemory) m).getType())
                && "村口老槐树被雷劈断了大半".equals(((ActorMemory) m).getContent())
                && ((ActorMemory) m).getImportance() == 4));
    }

    /**
     * 操作判定 update：匹配旧条 → 写新条（supersedes 旧条 + 版本 +1）+ 旧条软删归档。
     */
    @Test
    void updateWritesVersionChainAndArchivesOld() {
        ActorMemory old = new ActorMemory();
        old.setId(5L);
        old.setProjectId(100L);
        old.setCharacterId(10L);
        old.setCharacterRef("npc:10");
        old.setType("semantic");
        old.setContent("张屠户在村西卖肉");
        old.setDeleted(0);
        old.setVersion(1);
        when(memoryRepository.findByProjectIdAndCharacterIdAndTypeInAndDeletedOrderByCreatedAtDesc(
                anyLong(), anyLong(), anyList(), eq(0))).thenReturn(List.of(old));
        // update 内容自然包含旧事实主体（Dice ≥ supersede-match-threshold=0.4 → 命中旧条）
        stubExtract("[{\"operation\":\"update\","
                + "\"content\":\"张屠户在村西卖肉，如今铺子改成了卖布\",\"importance\":4}]");
        service.extractAfterRound(1L, 1L);
        // 新条写 L2：supersedes_id=5、version=2
        verify(memoryRepository).save(Mockito.argThat(m -> m instanceof ActorMemory
                && ((ActorMemory) m).getSupersedesId() != null && ((ActorMemory) m).getSupersedesId() == 5L
                && ((ActorMemory) m).getVersion() != null && ((ActorMemory) m).getVersion() == 2
                && "张屠户在村西卖肉，如今铺子改成了卖布".equals(((ActorMemory) m).getContent())));
        // 旧条软删 + 归档
        verify(memoryRepository).save(Mockito.argThat(m -> m instanceof ActorMemory
                && ((ActorMemory) m).getId() == 5L && ((ActorMemory) m).getDeleted() == 1));
        verify(archiveService).archiveL2Memory(old, "superseded");
    }

    /**
     * 操作判定 invalidate：匹配旧条 → 软删+归档，不写新条。
     */
    @Test
    void invalidateArchivesOldWithoutNewRow() {
        ActorMemory old = new ActorMemory();
        old.setId(6L);
        old.setProjectId(100L);
        old.setCharacterId(10L);
        old.setCharacterRef("npc:10");
        old.setType("semantic");
        old.setContent("沈村长与巫祝因祭典安排起了争执");
        old.setDeleted(0);
        old.setVersion(1);
        when(memoryRepository.findByProjectIdAndCharacterIdAndTypeInAndDeletedOrderByCreatedAtDesc(
                anyLong(), anyLong(), anyList(), eq(0))).thenReturn(List.of(old));
        stubExtract("[{\"operation\":\"invalidate\",\"content\":\"沈村长与巫祝的争执已和解\",\"importance\":3}]");
        service.extractAfterRound(1L, 1L);
        verify(memoryRepository).save(Mockito.argThat(m -> m instanceof ActorMemory
                && ((ActorMemory) m).getId() == 6L && ((ActorMemory) m).getDeleted() == 1));
        verify(archiveService).archiveL2Memory(old, "superseded");
        // 不写新条（除旧条软删外仅有一次 save）
        verify(memoryRepository, Mockito.times(1)).save(Mockito.argThat(m -> m instanceof ActorMemory));
    }

    /**
     * invalidate 无匹配旧条：丢弃（不写任何东西）。
     */
    @Test
    void invalidateWithoutMatchDrops() {
        stubExtract("[{\"operation\":\"invalidate\",\"content\":\"某个从未记过的旧约定作废了\",\"importance\":3}]");
        service.extractAfterRound(1L, 1L);
        verify(memoryRepository, never()).save(any(ActorMemory.class));
    }

    /**
     * 双通道路由：target=world 条目 → WorldMemoryService.routeFromExtraction（角色侧不写）。
     */
    @Test
    void worldTargetRoutesToWorldMemory() {
        stubExtract("[{\"target\":\"world\",\"kind\":\"event\","
                + "\"content\":\"村口古树遭雷击，乡邻议论纷纷\",\"importance\":4}]");
        service.extractAfterRound(1L, 1L);
        verify(worldMemoryService).routeFromExtraction(eq(1L), eq(100L), any(),
                Mockito.argThat(list -> list.size() == 1
                        && "event".equals(((WorldMemoryService.RouteCandidate) list.get(0)).kind())));
        verify(workingService, never()).writeDraft(anyLong(), anyString(), anyString(), anyString(), any());
    }

    /**
     * target=both：世界侧路由 + 角色侧记个人经历（episodic 直进 L2）。
     */
    @Test
    void bothTargetRoutesBothSides() {
        stubExtract("[{\"target\":\"both\",\"kind\":\"episodic\","
                + "\"content\":\"我和村长一起扑灭了粮仓的火\",\"importance\":4}]");
        service.extractAfterRound(1L, 1L);
        verify(worldMemoryService).routeFromExtraction(eq(1L), eq(100L), any(), anyList());
        verify(memoryRepository).save(Mockito.argThat(m -> m instanceof ActorMemory
                && "episodic".equals(((ActorMemory) m).getType())
                && "我和村长一起扑灭了粮仓的火".equals(((ActorMemory) m).getContent())));
    }

    /**
     * 按需复核：重要度≥3 候选触发 memory_extract_review 批量调用（复核 drop → 不写）。
     */
    @Test
    void reviewCandidatesInvokesReviewAndDrops() {
        ReflectionTestUtils.setField(service, "extractReviewEnabled", true);
        // 第一次调用=抽取返回候选；第二次调用=复核返回 drop
        when(aiChatGateway.chatCompletion(eq(1L), eq(100L), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult("[{\"content\":\"村口老槐树被雷劈断了大半\",\"importance\":4}]",
                                "test-model", 10, 10, 20, 1L),
                        new AiChatResult("[{\"index\":0,\"action\":\"drop\"}]", "test-model",
                                10, 10, 20, 1L));
        service.extractAfterRound(1L, 1L);
        verify(aiChatGateway, Mockito.times(2)).chatCompletion(eq(1L), eq(100L), any(), any(AiChatRequest.class));
        verify(memoryRepository, never()).save(Mockito.argThat(m -> m instanceof ActorMemory
                && "村口老槐树被雷劈断了大半".equals(((ActorMemory) m).getContent())));
    }

    /**
     * 按需复核失败静默回退：复核调用抛异常 → 仍按初判直进 L2（不阻断）。
     */
    @Test
    void reviewFailureFallsBackToInitialJudgment() {
        ReflectionTestUtils.setField(service, "extractReviewEnabled", true);
        when(aiChatGateway.chatCompletion(eq(1L), eq(100L), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult("[{\"content\":\"村口老槐树被雷劈断了大半\",\"importance\":4}]",
                                "test-model", 10, 10, 20, 1L))
                .thenThrow(new RuntimeException("复核服务不可用"));
        service.extractAfterRound(1L, 1L);
        verify(memoryRepository).save(Mockito.argThat(m -> m instanceof ActorMemory
                && "村口老槐树被雷劈断了大半".equals(((ActorMemory) m).getContent())));
    }

    /**
     * 批 4 记忆与反思链·段 2F 对齐：抽取（memory_extract）请求前置 memoryChainContext
     * （段1A 世界观 + 段1C 角色卡 + 段2D 世界动态 + 段2E 状态）。
     */
    @Test
    void extractPrependsMemoryChainContextToPrompt() {
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(injector.memoryChainContext(eq(100L), eq("npc:10"), any()))
                .thenReturn("【世界观】\n青禾村四面环山。\n\n【我的身份与处境】\n姓名：沈砚秋\n\n"
                        + "【当前世界动态】\n世界历 0420-05-15 06时00分 · 夏季\n\n【当前状态】\n- 需求：饥饿 68/100（略饿）");
        ReflectionTestUtils.setField(service, "aiContextInjector", injector);
        stubExtract("[{\"content\":\"村口老槐树被雷劈断了大半\",\"importance\":4}]");
        service.extractAfterRound(1L, 1L);
        // 捕获实际请求：user 消息应前置记忆链段位
        org.mockito.ArgumentCaptor<AiChatRequest> reqCaptor = org.mockito.ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(eq(1L), eq(100L), any(), reqCaptor.capture());
        String user = reqCaptor.getValue().messages().get(1).content();
        org.junit.jupiter.api.Assertions.assertTrue(user.contains("【世界观】"), "应含段1A世界观: " + user);
        org.junit.jupiter.api.Assertions.assertTrue(user.contains("【我的身份与处境】"), "应含段1C角色卡: " + user);
        org.junit.jupiter.api.Assertions.assertTrue(user.contains("【当前世界动态】"), "应含段2D世界动态: " + user);
        org.junit.jupiter.api.Assertions.assertTrue(user.contains("【当前状态】"), "应含段2E当前状态: " + user);
        org.junit.jupiter.api.Assertions.assertTrue(user.contains("prompt"), "模板仍在注入段之后: " + user);
    }

    /**
     * 批 4 记忆与反思链·段 2F 对齐：按需复核（memory_extract_review）请求前置 memoryChainContext。
     */
    @Test
    void reviewPrependsMemoryChainContextToPrompt() {
        ReflectionTestUtils.setField(service, "extractReviewEnabled", true);
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(injector.memoryChainContext(eq(100L), eq("npc:10"), any()))
                .thenReturn("【世界观】\n青禾村四面环山。\n\n【我的身份与处境】\n姓名：沈砚秋\n\n"
                        + "【当前世界动态】\n世界历 0420-05-15 06时00分 · 夏季\n\n【当前状态】\n- 需求：饥饿 68/100（略饿）");
        ReflectionTestUtils.setField(service, "aiContextInjector", injector);
        // 第一次调用=抽取返回候选；第二次调用=复核返回 drop
        when(aiChatGateway.chatCompletion(eq(1L), eq(100L), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult("[{\"content\":\"村口老槐树被雷劈断了大半\",\"importance\":4}]",
                                "test-model", 10, 10, 20, 1L),
                        new AiChatResult("[{\"index\":0,\"action\":\"drop\"}]", "test-model",
                                10, 10, 20, 1L));
        service.extractAfterRound(1L, 1L);
        org.mockito.ArgumentCaptor<AiChatRequest> reqCaptor = org.mockito.ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway, Mockito.times(2)).chatCompletion(eq(1L), eq(100L), any(), reqCaptor.capture());
        // 第二次调用 = 复核请求，user 消息应前置记忆链段位
        String user = reqCaptor.getAllValues().get(1).messages().get(1).content();
        org.junit.jupiter.api.Assertions.assertTrue(user.contains("【世界观】"), "应含段1A世界观: " + user);
        org.junit.jupiter.api.Assertions.assertTrue(user.contains("【我的身份与处境】"), "应含段1C角色卡: " + user);
        org.junit.jupiter.api.Assertions.assertTrue(user.contains("【当前世界动态】"), "应含段2D世界动态: " + user);
        org.junit.jupiter.api.Assertions.assertTrue(user.contains("【当前状态】"), "应含段2E当前状态: " + user);
    }
}
