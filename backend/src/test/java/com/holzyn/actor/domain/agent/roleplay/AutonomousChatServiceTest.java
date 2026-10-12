package com.holzyn.actor.domain.agent.roleplay;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.runtime.BudgetGuard;
import com.holzyn.actor.domain.agent.runtime.RealtimeDecisionActivationService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationMemberRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorMessageRepository;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AutonomousChatService（D4 F12 NPC 间自主对话）单元测试（与实现同包，domain/agent/roleplay）。
 * <p>职责：验证无玩家群聊轮核心编排——NPC 内群惰性建/复用、同地点成员刷新合并、
 * 不足 2 人跳过、概率闲聊每日每地点上限（A 层 2026-09-24 可配，默认 3）、多节拍推进
 * （预算门控 + AI 退场编排/规则离场）、收尾归档（status=archived + 总括事件）。</p>
 */
class AutonomousChatServiceTest {

    private ActorConversationRepository conversationRepository;
    private ActorConversationMemberRepository memberRepository;
    private ActorMessageRepository messageRepository;
    private ActorCharacterRepository characterRepository;
    private ActorCharacterRepository npcRepository;
    private WorldLocationRepository locationRepository;
    private WorldEventBus worldEventBus;
    private BudgetGuard budgetGuard;
    private AiSpringChatGateway aiChatGateway;
    private PromptTemplateService promptTemplateService;
    private UsageLogService usageLogService;
    private WorldClockService worldClockService;
    private RoleplayService roleplayService;
    private AutonomousChatService service;

    @BeforeEach
    void setUp() {
        conversationRepository = Mockito.mock(ActorConversationRepository.class);
        memberRepository = Mockito.mock(ActorConversationMemberRepository.class);
        messageRepository = Mockito.mock(ActorMessageRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        npcRepository = Mockito.mock(ActorCharacterRepository.class);
        locationRepository = Mockito.mock(WorldLocationRepository.class);
        worldEventBus = Mockito.mock(WorldEventBus.class);
        budgetGuard = new BudgetGuard();
        aiChatGateway = Mockito.mock(AiSpringChatGateway.class);
        promptTemplateService = Mockito.mock(PromptTemplateService.class);
        usageLogService = Mockito.mock(UsageLogService.class);
        worldClockService = Mockito.mock(WorldClockService.class);
        roleplayService = Mockito.mock(RoleplayService.class);
        service = new AutonomousChatService(conversationRepository, memberRepository, messageRepository,
                characterRepository, npcRepository, locationRepository, worldEventBus, budgetGuard,
                aiChatGateway, promptTemplateService, usageLogService, worldClockService,
                roleplayService, new ObjectMapper(), new NpcTypeResolver(characterRepository));
        // @Value 字段在单元测试中不注入，统一回填生产默认值
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "probability", 1.0);
        ReflectionTestUtils.setField(service, "dailyCapPerLocation", 3);
        ReflectionTestUtils.setField(service, "maxBeats", 20);
        ReflectionTestUtils.setField(service, "orchestrateInterval", 3);
        ReflectionTestUtils.setField(service, "eventResponseEnabled", true);
        // 2026-09-25 长跑第 3 轮抓修适配：建会话前的清醒预检默认放行（RoleplayService 为 mock，
        // boolean 默认 false 会让所有剧集用例在预检处被跳过）；个别测试按需覆盖为 false。
        lenient().when(roleplayService.isAwakeCharacter(any(ActorCharacter.class), any()))
                .thenReturn(true);
    }

    /** 构造项目（id=1，user=1） */
    private ActorProject project() {
        ActorProject p = new ActorProject();
        p.setId(1L);
        p.setUserId(1L);
        p.setDeleted(0);
        return p;
    }

    /** 构造地点（id=1） */
    private ActorWorldLocation location() {
        ActorWorldLocation loc = new ActorWorldLocation();
        loc.setId(1L);
        loc.setProjectId(1L);
        loc.setName("城东市场");
        return loc;
    }

    /** 构造同地点特殊角色（locationId=1） */
    private ActorCharacter character(long id, String name, int importance) {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(id);
        ch.setProjectId(1L);
        ch.setName(name);
        ch.setImportance(importance);
        ch.setLocationId(1L);
        ch.setLocation("城东市场");
        ch.setDeleted(0);
        return ch;
    }

    /** 构造同地点普通 NPC（locationId=1） */
    private ActorCharacter npc(long id, String name) {
        ActorCharacter n = new ActorCharacter();
        n.setId(id);
        n.setProjectId(1L);
        n.setName(name);
        n.setLocationId(1L);
        n.setLocation("城东市场");
        // G1a：单主表下普通型必须显式 setType("common")（实体默认值是 special）
        n.setType("common");
        return n;
    }

    /** 构造成员（special/common；历史 crowd 由 NpcRefs.normalizeType 兼容） */
    private ActorConversationMember member(long id, String type, long characterId) {
        ActorConversationMember m = new ActorConversationMember();
        m.setId(id);
        m.setConversationId(9L);
        m.setMemberType(type);
        m.setCharacterId(characterId);
        m.setMemberName(com.holzyn.actor.common.NpcRefs.isCommonType(type) ? "居民" + characterId : null);
        return m;
    }

    /**
     * 通用前置 mock：同地点 2 名特殊角色 + 1 名普通 NPC；成员刷新后返回 2 名成员（特殊×2）。
     */
    private void stubTwoMembers() {
        when(characterRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(1L, "special", 0))
                .thenReturn(List.of(character(1, "罗小黑", 4), character(2, "鹿野", 3)));
        when(npcRepository.findByProjectIdAndTypeOrderByIdAsc(1L, "common"))
                .thenReturn(List.of(npc(10, "摊贩甲")));
        // 初始会话无成员 → 刷新后补入特殊×2 + 普通×1（均同地点）；findByConversationId 稳定返回 3 名
        when(memberRepository.findByConversationId(anyLong())).thenReturn(List.of(
                member(1L, "special", 1L), member(2L, "special", 2L), member(3L, "common", 10L)));
        when(conversationRepository.save(any(ActorConversation.class)))
                .thenAnswer(inv -> {
                    ActorConversation c = inv.getArgument(0);
                    // 模拟 JPA 生成主键：无 ID 时赋 99，保证 runEpisode 互斥键/成员查询可用
                    if (c.getId() == null) {
                        c.setId(99L);
                    }
                    return c;
                });
        when(memberRepository.save(any(ActorConversationMember.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any(com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.findByConversationIdOrderByIdAsc(anyLong())).thenReturn(List.of());
        when(conversationRepository.findByProjectIdAndConversationTypeAndStatus(eq(1L), anyString(), anyString()))
                .thenReturn(List.of());
        when(roleplayService.runAutonomousGroupRound(any(), any(), any(), any())).thenReturn(2);
        // 2026-09-17 P0 修复适配：清醒成员过滤默认放行全部（同地点 3 名均清醒）
        when(roleplayService.filterAwakeMembers(any(ActorConversation.class), any(), any()))
                .thenReturn(List.of(member(1L, "special", 1L), member(2L, "special", 2L), member(3L, "common", 10L)));
        // 2026-09-17 P0 修复适配：深夜判定默认非深夜（个别测试按需覆盖为 true）
        when(roleplayService.isDeepNightHour(anyLong())).thenReturn(false);
    }

    /**
     * 不足 2 名同地点 NPC → 跳过（status=skipped/insufficient-members），不推进群聊轮。
     */
    @Test
    void runEpisodeSkipsWhenInsufficientMembers() {
        when(characterRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(1L, "special", 0))
                .thenReturn(List.of(character(1, "罗小黑", 4)));
        when(npcRepository.findByProjectIdAndTypeOrderByIdAsc(1L, "common")).thenReturn(List.of());
        when(memberRepository.findByConversationId(anyLong())).thenReturn(List.of(
                member(1L, "special", 1L)));
        when(conversationRepository.save(any(ActorConversation.class))).thenAnswer(inv -> {
            ActorConversation c = inv.getArgument(0);
            if (c.getId() == null) {
                c.setId(99L);
            }
            return c;
        });
        when(conversationRepository.findByProjectIdAndConversationTypeAndStatus(eq(1L), anyString(), anyString()))
                .thenReturn(List.of());

        Map<String, Object> r = service.runEpisode(1L, 1L, 1L, "城东市场", 100L, "event", "事件");

        assertEquals("skipped", r.get("status"));
        assertEquals("insufficient-members", r.get("skipReason"));
        verify(roleplayService, never()).runAutonomousGroupRound(any(), any(), any(), any());
        // 冒烟抓修：不足 2 名不得创建空 NPC 内群会话（预检在惰性建会话之前）
        verify(conversationRepository, never()).save(any(ActorConversation.class));
    }

    /**
     * 多节拍推进（无 AI 编排依赖，预算耗尽后自然收尾）：
     * 每节拍群聊轮 + 逐轮 roleplay 事件；收尾归档会话 status=archived + 总括事件。
     */
    @Test
    void runEpisodeRoundsThenArchives() {
        stubTwoMembers();

        Map<String, Object> r = service.runEpisode(1L, 1L, 1L, "城东市场", 100L, "probability", null);

        assertEquals("finished", r.get("status"));
        assertTrue((Integer) r.get("beats") >= 1, "至少推进 1 个节拍");
        // 逐轮事件（每节拍 ≥1 条）+ 收尾总括事件
        verify(worldEventBus, atLeastOnce()).publish(anyLong(), eq(1L), any(EventSpec.class));
        ArgumentCaptor<EventSpec> specCap = ArgumentCaptor.forClass(EventSpec.class);
        verify(worldEventBus, atLeastOnce()).publish(anyLong(), eq(1L), specCap.capture());
        assertTrue(specCap.getAllValues().stream().anyMatch(s -> "roleplay".equals(s.getKind())),
                "逐轮/收尾事件均为 kind=roleplay");
        // 群聊轮推进 ≥1 次
        verify(roleplayService, atLeastOnce()).runAutonomousGroupRound(any(), any(), any(), any());
        // 归档：最后一次保存的会话 status=archived
        ArgumentCaptor<ActorConversation> cap = ArgumentCaptor.forClass(ActorConversation.class);
        verify(conversationRepository, atLeastOnce()).save(cap.capture());
        List<ActorConversation> saved = cap.getAllValues();
        assertEquals("archived", saved.get(saved.size() - 1).getStatus(), "收尾归档关闭");
        assertEquals("npc_group", saved.get(0).getConversationType(), "NPC 内群类型标记");
    }

    /**
     * AI 退场/收尾编排（预算充足，每 orchestrate-interval 节拍编排一次）：
     * AI 返回 shouldFinish=true → 本场收尾；退场成员被移除并写系统消息。
     */
    @Test
    void runEpisodeAiOrchestrationFinishes() {
        ReflectionTestUtils.setField(service, "orchestrateInterval", 1);
        stubTwoMembers();
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any()))
                .thenReturn("编排 prompt");
        when(promptTemplateService.systemMessage(anyLong(), anyLong(), anyString(), any()))
                .thenReturn("你只输出严格的 JSON，不输出任何其他文字。");
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult(
                        "{\"leaves\":[{\"memberIndex\":2,\"reason\":\"有事离开\"}],\"shouldFinish\":true,\"finishReason\":\"天色已晚\"}",
                        "test-model", 10, 20, 30, 1L));

        Map<String, Object> r = service.runEpisode(1L, 1L, 1L, "城东市场", 100L, "event", "异动");

        assertEquals("finished", r.get("status"));
        assertEquals("天色已晚", r.get("finishReason"));
        // AI 退场：成员 2（鹿野）被移除（memberRepository.delete 至少一次）
        verify(memberRepository, atLeastOnce()).delete(any(ActorConversationMember.class));
        // 收尾归档
        ArgumentCaptor<ActorConversation> cap = ArgumentCaptor.forClass(ActorConversation.class);
        verify(conversationRepository, atLeastOnce()).save(cap.capture());
        List<ActorConversation> saved = cap.getAllValues();
        assertEquals("archived", saved.get(saved.size() - 1).getStatus());
    }

    /**
     * 概率闲聊每日每地点上限（社会对话层修复 A 层 2026-09-24：1→{@code daily-cap-per-location} 可配）：
     * 上限 3 → 同日第 1/2/3 次触发各推进一轮（每次 建会话+归档 = 2 次 save），第 4 次被上限跳过。
     * （触发小时 130/131/132/133 → 第 5 游戏日白天，深夜时段概率闲聊整体跳过）
     */
    @Test
    void probabilityCappedPerGameDayPerLocation() {
        ReflectionTestUtils.setField(service, "dailyCapPerLocation", 3);
        stubTwoMembers();
        when(locationRepository.findByProjectId(1L)).thenReturn(List.of(location()));

        service.maybeTriggerProbability(project(), 130L);
        service.maybeTriggerProbability(project(), 131L);
        service.maybeTriggerProbability(project(), 132L);
        service.maybeTriggerProbability(project(), 133L); // 第 4 次 → 当日上限（3）跳过

        // 3 轮 × 2 次 save（建会话 + 归档）= 6；第 4 次调用不再建会话（当日上限 3）
        verify(conversationRepository, times(6)).save(any(ActorConversation.class));
    }

    /**
     * 上限=1 时保持修复前「每日每地点限 1 轮」语义（回归锁定：可配化不改变小上限行为）。
     */
    @Test
    void probabilityCapOneKeepsSingleRoundPerDay() {
        ReflectionTestUtils.setField(service, "dailyCapPerLocation", 1);
        stubTwoMembers();
        when(locationRepository.findByProjectId(1L)).thenReturn(List.of(location()));

        service.maybeTriggerProbability(project(), 130L); // 第 1 轮成功
        service.maybeTriggerProbability(project(), 131L); // 同日第 2 次 → 上限 1 跳过

        // 仅 1 轮开轮（2 次 save）；第 2 次调用未建会话
        verify(conversationRepository, times(2)).save(any(ActorConversation.class));
    }

    /**
     * 跳过（不足 2 名同地点 NPC）的剧集不占用当日上限：
     * 上午空地点跳过 → 下午补齐 2 人后仍可正常开轮（A 层「仅成功开轮计数」设计锁定）。
     */
    @Test
    void skippedEpisodeDoesNotConsumeDailyCap() {
        ReflectionTestUtils.setField(service, "dailyCapPerLocation", 1);
        stubTwoMembers();
        when(locationRepository.findByProjectId(1L)).thenReturn(List.of(location()));
        // 第一次：同地点仅 1 名特殊角色 → skipped（insufficient-members）
        when(characterRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(1L, "special", 0))
                .thenReturn(List.of(character(1, "罗小黑", 4)));
        when(npcRepository.findByProjectIdAndTypeOrderByIdAsc(1L, "common")).thenReturn(List.of());
        when(memberRepository.findByConversationId(anyLong())).thenReturn(List.of(
                member(1L, "special", 1L)));

        service.maybeTriggerProbability(project(), 130L);
        verify(conversationRepository, never()).save(any(ActorConversation.class));

        // 第二次：补齐第 2 人 → 上限未被空地点烧掉，仍可开轮
        stubTwoMembers();
        service.maybeTriggerProbability(project(), 131L);

        verify(conversationRepository, times(2)).save(any(ActorConversation.class));
    }

    /**
     * 2026-09-17 P0 修复适配：概率闲聊不再受引擎 tick 预算门控——
     * 引擎预算耗尽（剩余 1）时概率闲聊仍以<b>独立剧集预算窗口</b>正常推进（不饿死群聊）。
     */
    @Test
    void probabilityProceedsWithIndependentBudgetWhenEngineBudgetLow() {
        stubTwoMembers();
        when(locationRepository.findByProjectId(1L)).thenReturn(List.of(location()));
        // 引擎 tick 预算窗口仅剩 1（已被引擎决策耗尽）——不应拦截概率闲聊
        budgetGuard.beginTick(1L, 1);

        service.maybeTriggerProbability(project(), 130L);

        // 概率闲聊正常推进：ensure 创建 1 次 + 归档 1 次 = 2 次 save
        verify(conversationRepository, times(2)).save(any(ActorConversation.class));
    }

    // ==================== 2026-09-17 长跑 P0 修复新增 ====================

    /**
     * 深夜时段（23-6 点）：概率闲聊整体跳过（居民入睡）——不查地点、不建会话、不推进。
     */
    @Test
    void probabilitySkippedInDeepNight() {
        stubTwoMembers();
        when(locationRepository.findByProjectId(1L)).thenReturn(List.of(location()));
        budgetGuard.beginTick(1L, 5);
        when(roleplayService.isDeepNightHour(anyLong())).thenReturn(true); // 深夜时段（后注册者胜出）

        service.maybeTriggerProbability(project(), 25L); // 第 1 游戏日 1 时（深夜）

        verify(conversationRepository, never()).save(any(ActorConversation.class));
        verify(roleplayService, never()).runAutonomousGroupRound(any(), any(), any(), any());
        verify(locationRepository, never()).findByProjectId(1L);
    }

    /**
     * 清醒成员不足 2 名（入睡/深夜）：剧集跳过开轮，不推进群聊轮、不发布事件。
     */
    @Test
    void runEpisodeSkipsWhenAwakeMembersInsufficient() {
        stubTwoMembers();
        // 清醒仅 1 名（模拟其余入睡）
        when(roleplayService.filterAwakeMembers(any(ActorConversation.class), any(), any()))
                .thenReturn(List.of(member(1L, "special", 1L)));

        Map<String, Object> r = service.runEpisode(1L, 1L, 1L, "城东市场", 130L, "event", "事件");

        assertEquals("skipped", r.get("status"));
        assertEquals("members-asleep", r.get("skipReason"));
        verify(roleplayService, never()).runAutonomousGroupRound(any(), any(), any(), any());
    }

    /**
     * 2026-09-25 长跑第 3 轮抓修（空转会话泄漏，验收项⑤）：同地点清醒 NPC 不足 2 名时
     * <b>在建会话之前</b>跳过——深夜事件回应不再留下 0 消息会话（长跑实证：云来茶馆/青禾集市/
     * 后山竹林 3 场会话全 0 消息，全部来自「先建会话 → 后判清醒跳过」路径）。
     */
    @Test
    void awakePrecheckSkipsWithoutCreatingConversation() {
        stubTwoMembers();
        // 同地点 3 名 NPC 全部入睡/深夜（清醒预检不放行）
        when(roleplayService.isAwakeCharacter(any(ActorCharacter.class), any())).thenReturn(false);

        Map<String, Object> r = service.runEpisode(1L, 1L, 1L, "城东市场", 130L, "event", "事件");

        assertEquals("skipped", r.get("status"));
        assertEquals("members-asleep", r.get("skipReason"));
        verify(conversationRepository, never()).save(any(ActorConversation.class));
        verify(roleplayService, never()).runAutonomousGroupRound(any(), any(), any(), any());
    }

    /**
     * 2026-09-25 长跑第 3 轮抓修（兜底口径）：预检通过、会话已建之后成员清醒不足 → 跳过并
     * **回收**这个 0 消息会话（与 D 层同一删除口径；成员关系 + 会话行）。
     */
    @Test
    void asleepSkipAfterCreationRecyclesEmptyConversation() {
        stubTwoMembers();
        when(roleplayService.filterAwakeMembers(any(ActorConversation.class), any(), any()))
                .thenReturn(List.of(member(1L, "special", 1L)));

        Map<String, Object> r = service.runEpisode(1L, 1L, 1L, "城东市场", 130L, "event", "事件");

        assertEquals("skipped", r.get("status"));
        assertEquals("members-asleep", r.get("skipReason"));
        verify(conversationRepository, atLeastOnce()).delete(any(ActorConversation.class));
        verify(memberRepository, atLeastOnce()).deleteAll(anyList());
        verify(roleplayService, never()).runAutonomousGroupRound(any(), any(), any(), any());
    }

    /**
     * 2026-09-25 长跑第 3 轮抓修（数据丢失风险回归锁定）：清醒不足跳过 + 会话**已有历史消息**
     * （跨剧集复用）时绝不删除——只有 0 消息的空会话才回收。
     */
    @Test
    void asleepSkipKeepsConversationWithHistory() {
        stubTwoMembers();
        when(roleplayService.filterAwakeMembers(any(ActorConversation.class), any(), any()))
                .thenReturn(List.of(member(1L, "special", 1L)));
        com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage history =
                new com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage();
        history.setId(60L);
        history.setConversationId(99L);
        history.setRole("assistant");
        history.setType("text");
        history.setStatus("done");
        history.setContent("上一场留下的对话");
        when(messageRepository.findByConversationIdOrderByIdAsc(anyLong())).thenReturn(List.of(history));

        Map<String, Object> r = service.runEpisode(1L, 1L, 1L, "城东市场", 130L, "event", "事件");

        assertEquals("skipped", r.get("status"));
        assertEquals("members-asleep", r.get("skipReason"));
        verify(conversationRepository, never()).delete(any(ActorConversation.class));
    }

    /**
     * 剧集现实时长上限：超限即收尾（与轮级上限构成双重护栏，防长剧集独占线程阻塞世界）。
     */
    @Test
    void runEpisodeFinishesOnWallClockCap() throws Exception {
        stubTwoMembers();
        ReflectionTestUtils.setField(service, "episodeMaxWallMs", 50L);
        when(roleplayService.runAutonomousGroupRound(any(), any(), any(), any())).thenAnswer(inv -> {
            Thread.sleep(60);
            return 2;
        });

        Map<String, Object> r = service.runEpisode(1L, 1L, 1L, "城东市场", 130L, "probability", null);

        assertEquals("finished", r.get("status"));
        assertTrue(String.valueOf(r.get("finishReason")).contains("现实时长上限"));
        assertEquals(1, r.get("beats"), "首个节拍后超限收尾，不再续拍");
    }

    /**
     * 世界停止（沙盘关闭/手动关闭/未激活）：在途剧集随之收束（群聊实时随世界运行）。
     */
    @Test
    void runEpisodeFinishesWhenWorldStopped() {
        stubTwoMembers();
        RealtimeDecisionActivationService gate = Mockito.mock(RealtimeDecisionActivationService.class);
        when(gate.isActive(1L)).thenReturn(false);
        ReflectionTestUtils.setField(service, "runtimeGate", gate);

        Map<String, Object> r = service.runEpisode(1L, 1L, 1L, "城东市场", 130L, "probability", null);

        assertEquals("finished", r.get("status"));
        assertTrue(String.valueOf(r.get("finishReason")).contains("世界已停止"));
        verify(roleplayService, never()).runAutonomousGroupRound(any(), any(), any(), any());
    }

    /**
     * 一轮无人想接话/零回复 → 剧集自然收束（不再无限续轮）。
     */
    @Test
    void runEpisodeFinishesWhenNoRepliesInRound() {
        stubTwoMembers();
        when(roleplayService.runAutonomousGroupRound(any(), any(), any(), any())).thenReturn(0);

        Map<String, Object> r = service.runEpisode(1L, 1L, 1L, "城东市场", 130L, "probability", null);

        assertEquals("finished", r.get("status"));
        assertTrue(String.valueOf(r.get("finishReason")).contains("无人想接话"));
        verify(roleplayService, times(1)).runAutonomousGroupRound(any(), any(), any(), any());
    }

    /**
     * 2026-09-25 说话意愿修复 D 层：空转剧集（全场 0 条发言）<b>不归档</b>——回收会话
     * （成员关系 + 会话行），不发布收尾 roleplay 事件，避免 0 消息的 npc_group 会话堆在会话列表里
     * （冒烟实证 conv 1/65/67/68/69 均为此类：占列表且无任何信息量）。
     */
    @Test
    void emptyEpisodeIsRecycledNotArchived() {
        stubTwoMembers();
        when(roleplayService.runAutonomousGroupRound(any(), any(), any(), any())).thenReturn(0);

        Map<String, Object> r = service.runEpisode(1L, 1L, 1L, "城东市场", 130L, "probability", null);

        assertEquals("finished", r.get("status"));
        assertEquals("no-messages", r.get("skipReason"), "空转剧集以 no-messages 收尾");
        assertNull(r.get("eventId"), "空转剧集不发布收尾事件");
        // 会话被回收（delete），且从未以 archived 状态保存
        verify(conversationRepository, atLeastOnce()).delete(any(ActorConversation.class));
        ArgumentCaptor<ActorConversation> cap = ArgumentCaptor.forClass(ActorConversation.class);
        verify(conversationRepository, atLeastOnce()).save(cap.capture());
        assertTrue(cap.getAllValues().stream().noneMatch(c -> "archived".equals(c.getStatus())),
                "空转剧集不得归档");
        verify(memberRepository, atLeastOnce()).deleteAll(anyList());
        verify(worldEventBus, never()).publish(anyLong(), eq(1L), any(EventSpec.class));
    }

    /**
     * 2026-09-25 说话意愿修复 D 层（自审抓出的数据丢失风险回归锁定）：同地点 NPC 内群会**跨剧集复用**——
     * 本场 0 回复但会话已有历史消息时，**必须照常归档，绝不回收删除**（否则复用会话的历史对话被抹掉）。
     */
    @Test
    void reusedConversationWithHistoryIsNotRecycled() {
        stubTwoMembers();
        when(roleplayService.runAutonomousGroupRound(any(), any(), any(), any())).thenReturn(0);
        // 会话已有历史消息（复用场景）：0 回复 ≠ 空会话
        com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage history =
                new com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage();
        history.setId(50L);
        history.setConversationId(99L);
        history.setRole("assistant");
        history.setType("text");
        history.setStatus("done");
        history.setContent("上一场留下的对话");
        when(messageRepository.findByConversationIdOrderByIdAsc(anyLong())).thenReturn(List.of(history));

        Map<String, Object> r = service.runEpisode(1L, 1L, 1L, "城东市场", 130L, "probability", null);

        assertEquals("finished", r.get("status"));
        assertNull(r.get("skipReason"), "有历史消息的会话不得走空转回收口径");
        verify(conversationRepository, never()).delete(any(ActorConversation.class));
        ArgumentCaptor<ActorConversation> cap = ArgumentCaptor.forClass(ActorConversation.class);
        verify(conversationRepository, atLeastOnce()).save(cap.capture());
        assertEquals("archived", cap.getAllValues().get(cap.getAllValues().size() - 1).getStatus(),
                "复用会话必须照常归档");
    }

    /**
     * 剧集预算独立命名空间：异步剧集自开窗口，不覆盖、不消耗引擎 tick 预算——
     * 异步剧集与引擎并发推进时，引擎 AI 决策不被饿死（覆盖式 beginTick 的回归锁定）。
     */
    @Test
    void episodeBudgetIsolatedFromEngineTickWindow() {
        stubTwoMembers();
        // 引擎 tick 窗口开启（预算 5）
        budgetGuard.beginTick(1L, 5);

        service.runEpisode(1L, 1L, 1L, "城东市场", 130L, "probability", null);

        // 引擎窗口未被覆盖/删除：剩余仍为 5
        assertEquals(5, budgetGuard.remaining(1L), "引擎 tick 预算不受剧集影响");
        // 剧集窗口已关闭（finally endEpisode）
        assertEquals(0, budgetGuard.remainingEpisode(1L), "剧集窗口应已关闭");
    }

    /**
     * 事件回应轮（triggerEventResponse）：预算充足且同地点 ≥2 NPC → 推进一轮并归档；
     * 总开关关闭 → 跳过（不发布事件）。
     */
    @Test
    void eventResponseTriggeredOrDisabled() {
        stubTwoMembers();
        budgetGuard.beginTick(1L, 5);

        service.triggerEventResponse(1L, 1L, 1L, "城东市场", 100L, "妖灵会馆异动");

        ArgumentCaptor<EventSpec> cap = ArgumentCaptor.forClass(EventSpec.class);
        verify(worldEventBus, atLeastOnce()).publish(anyLong(), eq(1L), cap.capture());
        assertTrue(cap.getAllValues().stream().anyMatch(s -> "roleplay".equals(s.getKind())),
                "逐轮/收尾事件均为 kind=roleplay");

        // 总开关关闭 → 跳过（不建会话、不发布）
        Mockito.clearInvocations(worldEventBus);
        ReflectionTestUtils.setField(service, "enabled", false);
        service.triggerEventResponse(1L, 1L, 1L, "城东市场", 100L, "x");
        verify(worldEventBus, never()).publish(anyLong(), eq(1L), any(EventSpec.class));
    }

    /**
     * 事件回应轮（独立异步路径，无预算窗口）不因「无窗口 remaining=0」被预检拦截：
     * 手动注入事件（HTTP 线程外异步）没有引擎 tick 窗口，预检应放行，
     * 由 runEpisode 自行开启 ASYNC_EPISODE_BUDGET 小窗口推进（冒烟抓修的真实缺陷）。
     */
    @Test
    void eventResponseWithoutBudgetWindowProceeds() {
        stubTwoMembers();
        // 不 beginTick：模拟手动注入事件的独立异步路径（无引擎 tick 窗口）

        service.triggerEventResponse(1L, 1L, 1L, "城东市场", 100L, "异动");

        ArgumentCaptor<EventSpec> cap = ArgumentCaptor.forClass(EventSpec.class);
        verify(worldEventBus, atLeastOnce()).publish(anyLong(), eq(1L), cap.capture());
        assertTrue(cap.getAllValues().stream().anyMatch(s -> "roleplay".equals(s.getKind())),
                "无窗口异步路径应正常推进并发布 roleplay 事件");
        verify(roleplayService, atLeastOnce()).runAutonomousGroupRound(any(), any(), any(), any());
        // 自开窗口的预算最终被清空（endTick 移除）
        assertEquals(-1, budgetGuard.budgetTotal(1L), "runEpisode 结束后应关闭自开窗口");
    }

    /**
     * 会话复用：同地点已有 active 的 NPC 内群 → 不再新建（惰性复用），仅归档时保存一次。
     */
    @Test
    void runEpisodeReusesExistingNpcGroupConversation() {
        when(characterRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(1L, "special", 0))
                .thenReturn(List.of(character(1, "罗小黑", 4), character(2, "鹿野", 3)));
        when(npcRepository.findByProjectIdAndTypeOrderByIdAsc(1L, "common")).thenReturn(List.of());
        when(memberRepository.findByConversationId(anyLong())).thenReturn(List.of(
                member(1L, "special", 1L), member(2L, "special", 2L)));
        when(memberRepository.save(any(ActorConversationMember.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any(com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.findByConversationIdOrderByIdAsc(anyLong())).thenReturn(List.of());
        when(conversationRepository.save(any(ActorConversation.class))).thenAnswer(inv -> {
            ActorConversation c = inv.getArgument(0);
            if (c.getId() == null) {
                c.setId(99L);
            }
            return c;
        });
        when(roleplayService.runAutonomousGroupRound(any(), any(), any(), any())).thenReturn(2);
        when(roleplayService.filterAwakeMembers(any(ActorConversation.class), any(), any()))
                .thenReturn(List.of(member(1L, "special", 1L), member(2L, "special", 2L)));
        // 已存在 active NPC 内群（同地点 id=1）
        ActorConversation existing = new ActorConversation();
        existing.setId(9L);
        existing.setProjectId(1L);
        existing.setUserId(1L);
        existing.setMode("group");
        existing.setConversationType("npc_group");
        existing.setStatus("active");
        existing.setLocationId(1L);
        existing.setLocation("城东市场");
        when(conversationRepository.findByProjectIdAndConversationTypeAndStatus(eq(1L), anyString(), anyString()))
                .thenReturn(List.of(existing));

        Map<String, Object> r = service.runEpisode(1L, 1L, 1L, "城东市场", 100L, "event", "事件");

        assertEquals(9L, r.get("conversationId"), "复用既有 NPC 内群");
        // 创建不重复：仅归档一次 save
        ArgumentCaptor<ActorConversation> cap = ArgumentCaptor.forClass(ActorConversation.class);
        verify(conversationRepository, times(1)).save(cap.capture());
        assertEquals("archived", cap.getValue().getStatus(), "复用会话收尾归档");
    }

    /**
     * B 层 2026-09-25 冒烟修复：事件回应轮前置检查（只读、不建会话、不扣额度）——
     * 同地点合格 NPC ≥2 → true；仅 1 名 / 开关关闭 → false。
     */
    @Test
    void canTriggerEventResponseChecksSwitchesAndHeadcount() {
        // ① 同地点 2 名特殊角色 → 可开轮
        when(characterRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(1L, "special", 0))
                .thenReturn(List.of(character(1, "罗小黑", 4), character(2, "鹿野", 3)));
        when(npcRepository.findByProjectIdAndTypeOrderByIdAsc(1L, "common")).thenReturn(List.of());
        assertTrue(service.canTriggerEventResponse(1L, 1L, "城东市场"), "同地点 2 名合格 NPC 可开轮");

        // ② 仅 1 名同行 → 不可开轮（本轮本就不会开，订阅者据此不扣当日额度）
        when(characterRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(1L, "special", 0))
                .thenReturn(List.of(character(1, "罗小黑", 4)));
        assertFalse(service.canTriggerEventResponse(1L, 1L, "城东市场"), "同地点仅 1 名不可开轮");

        // ③ 事件回应轮开关关闭 → 不可开轮
        ReflectionTestUtils.setField(service, "eventResponseEnabled", false);
        assertFalse(service.canTriggerEventResponse(1L, 1L, "城东市场"), "开关关闭不可开轮");

        // 只读语义：全程不得创建/保存任何会话
        verify(conversationRepository, never()).save(any(ActorConversation.class));
    }

    /**
     * A 层 2026-09-25 冒烟调序：概率闲聊先判「同地点合格 NPC ≥2」再掷骰——
     * 空地点（仅 1 人）不消耗随机数、不建会话（修复前先掷骰后跳过，日志刷「触发→跳过」噪音）。
     * 断言口径：两个地点中仅有人地点被掷骰（nextDouble 调用次数=1）。
     */
    @Test
    void probabilityRollsOnlyForLocationsWithEnoughNpcs() {
        // 地点 1=城东市场（仅 1 名 NPC，应先被跳过、不掷骰）；地点 2=城西茶棚（2 名同地点 NPC，应掷骰）
        ActorWorldLocation empty = location();
        empty.setId(1L);
        empty.setName("城东市场");
        ActorWorldLocation busy = location();
        busy.setId(2L);
        busy.setName("城西茶棚");
        when(locationRepository.findByProjectId(1L)).thenReturn(List.of(empty, busy));
        ActorCharacter a = character(1, "罗小黑", 4);
        a.setLocationId(2L);
        a.setLocation("城西茶棚");
        ActorCharacter b = character(2, "鹿野", 3);
        b.setLocationId(2L);
        b.setLocation("城西茶棚");
        when(characterRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(1L, "special", 0))
                .thenReturn(List.of(a, b));
        when(npcRepository.findByProjectIdAndTypeOrderByIdAsc(1L, "common")).thenReturn(List.of());
        when(memberRepository.findByConversationId(anyLong())).thenReturn(List.of());
        when(memberRepository.save(any(ActorConversationMember.class))).thenAnswer(inv -> inv.getArgument(0));
        when(conversationRepository.save(any(ActorConversation.class))).thenAnswer(inv -> {
            ActorConversation c = inv.getArgument(0);
            if (c.getId() == null) {
                c.setId(99L);
            }
            return c;
        });
        when(conversationRepository.findByProjectIdAndConversationTypeAndStatus(eq(1L), anyString(), anyString()))
                .thenReturn(List.of());
        // 掷骰计数源（probability=1.0 时 nextDouble 必被调用）
        java.util.concurrent.atomic.AtomicInteger rolls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.Random counting = new java.util.Random() {
            @Override
            public double nextDouble() {
                rolls.incrementAndGet();
                return 0.0; // < probability(1.0) → 必触发
            }
        };
        ReflectionTestUtils.setField(service, "random", ThreadLocal.withInitial(() -> counting));
        // 有人地点开轮后即因清醒成员不足 2 名收束（避免进入规则离场再消耗随机数）
        when(roleplayService.filterAwakeMembers(any(ActorConversation.class), any(), any()))
                .thenReturn(List.of(member(1L, "special", 1L)));

        service.maybeTriggerProbability(project(), 100L);

        assertEquals(1, rolls.get(), "只有同地点 ≥2 名 NPC 的地点才掷骰（空地点不掷、不刷日志）");
        // 空地点不得建 NPC 内群（地点名快照=城东市场）
        ArgumentCaptor<ActorConversation> cap = ArgumentCaptor.forClass(ActorConversation.class);
        verify(conversationRepository, atLeastOnce()).save(cap.capture());
        assertTrue(cap.getAllValues().stream().noneMatch(c -> "城东市场".equals(c.getLocation())),
                "同地点仅 1 名的地点不得创建空 NPC 内群");
    }

    // ==================== 2026-10 长跑修复：自然收尾 / 空占位清理 ====================

    /**
     * 结尾不自然修复：本场有实质对话 → 收尾时在聊天内写入「自然收尾叙述」
     * （系统消息，以「（」开头，技术性收尾理由不外露）；收尾总括事件内容同样用自然叙述
     * （时间线不再出现「AI 预算用尽，本场闲聊就此收束」之类的技术文案）。
     */
    @Test
    void runEpisodeWritesNaturalClosingMessage() {
        stubTwoMembers();
        // mock 轮每节拍返回 2 条回复 → 跑满 maxBeats=20 达「剧情上限」收尾（预算 mock 不消耗）
        Map<String, Object> r = service.runEpisode(1L, 1L, 1L, "城东市场", 100L, "probability", null);

        assertEquals("finished", r.get("status"));
        // 聊天内出现自然收尾叙述（系统消息，以「（」开头，不含技术性理由）
        verify(messageRepository, atLeastOnce()).save(argThat(
                (com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage m) ->
                        "system".equals(m.getRole()) && m.getContent() != null
                                && m.getContent().startsWith("（")
                                && !m.getContent().contains("AI 预算")
                                && !m.getContent().contains("现实时长上限")));
        // 收尾总括事件内容同样为世界内自然叙述
        ArgumentCaptor<EventSpec> specCap = ArgumentCaptor.forClass(EventSpec.class);
        verify(worldEventBus, atLeastOnce()).publish(anyLong(), eq(1L), specCap.capture());
        boolean hasFinishEvent = specCap.getAllValues().stream()
                .anyMatch(s -> s.getSourceKey() != null && s.getSourceKey().contains("finish"));
        assertTrue(hasFinishEvent, "应收尾总括事件");
        boolean finishNatural = specCap.getAllValues().stream()
                .filter(s -> s.getSourceKey() != null && s.getSourceKey().contains("finish"))
                .allMatch(s -> s.getContent() != null && s.getContent().contains("（")
                        && !s.getContent().contains("AI 预算") && !s.getContent().contains("现实时长上限"));
        assertTrue(finishNatural, "收尾总括事件内容应使用世界内自然叙述");
    }

    /**
     * 突然离开/突兀叙述修复：规则性离场（预算不足兜底）时——
     * <b>未发言成员静默离场</b>（不写「【X】有事离开现场。」类系统消息，背景板自然消失）。
     */
    @Test
    void ruleBasedLeaveSilentForNeverSpokenMember() {
        stubTwoMembers(); // 成员：special1(罗小黑) / special2(鹿野) / common10(摊贩甲)
        // 控制重要度：罗小黑 imp2、鹿野 imp1 → 最低重要度 = 鹿野(special2)
        when(characterRepository.findById(1L)).thenReturn(Optional.of(character(1, "罗小黑", 2)));
        when(characterRepository.findById(2L)).thenReturn(Optional.of(character(2, "鹿野", 1)));
        // 消息史只有成员 1（罗小黑）发过言 → 被移除的鹿野（未发言）应静默离场
        com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage spoke =
                new com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage();
        spoke.setCharacterId(1L);
        spoke.setRole("assistant");
        spoke.setStatus("done");
        spoke.setContent("我说过话");
        when(messageRepository.findByConversationIdOrderByIdAsc(anyLong())).thenReturn(List.of(spoke));
        ActorConversation conv = new ActorConversation();
        conv.setId(9L);
        conv.setProjectId(1L);
        java.util.Random rng = new java.util.Random() {
            @Override
            public double nextDouble() {
                return 0.1; // 触发 50% 离场分支
            }
        };

        ReflectionTestUtils.invokeMethod(service, "ruleBasedLeave", conv, rng);

        // 未发言成员静默离场：不新增任何系统消息
        verify(messageRepository, never()).save(argThat(
                (com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage m) -> "system".equals(m.getRole())));
        // 但成员确实被移除
        verify(memberRepository, atLeastOnce()).delete(any(ActorConversationMember.class));
    }

    /**
     * 突然离开/突兀叙述修复（对照）：<b>已发言</b>成员离场仍写自然措辞的系统消息
     * （不再是千篇一律的「有事离开现场」）。
     */
    @Test
    void ruleBasedLeaveWritesMessageForSpokenMember() {
        stubTwoMembers();
        when(characterRepository.findById(1L)).thenReturn(Optional.of(character(1, "罗小黑", 2)));
        when(characterRepository.findById(2L)).thenReturn(Optional.of(character(2, "鹿野", 1)));
        // 消息史：鹿野(special2) 也发过言 → 离场应写系统叙述
        com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage spoke =
                new com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage();
        spoke.setCharacterId(2L);
        spoke.setRole("assistant");
        spoke.setStatus("done");
        spoke.setContent("鹿野也说了两句");
        when(messageRepository.findByConversationIdOrderByIdAsc(anyLong())).thenReturn(List.of(spoke));
        ActorConversation conv = new ActorConversation();
        conv.setId(9L);
        conv.setProjectId(1L);
        java.util.Random rng = new java.util.Random() {
            @Override
            public double nextDouble() {
                return 0.1;
            }
        };

        ReflectionTestUtils.invokeMethod(service, "ruleBasedLeave", conv, rng);

        // 已发言成员离场 → 有系统叙述（不再「有事离开现场」千篇一律）
        verify(messageRepository, atLeastOnce()).save(argThat(
                (com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage m) ->
                        "system".equals(m.getRole()) && m.getContent() != null
                                && m.getContent().contains("【鹿野】")
                                && !m.getContent().contains("有事离开现场")));
        verify(memberRepository, atLeastOnce()).delete(any(ActorConversationMember.class));
    }

    /**
     * 空转回收口径修复：会话只有 failed/streaming <b>空占位行</b>（content 为空）时
     * 视为「空转」→ 回收会话并清理占位行（此前空占位行也算「有消息」，空会话堆在列表 +
     * 前端显示「只有角色名没有内容」）。
     */
    @Test
    void emptyEpisodeWithOnlyBlankRowsIsRecycled() {
        stubTwoMembers();
        when(roleplayService.runAutonomousGroupRound(any(), any(), any(), any())).thenReturn(0);
        com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage blankFailed =
                new com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage();
        blankFailed.setRole("assistant");
        blankFailed.setType("text");
        blankFailed.setStatus("failed");
        blankFailed.setContent(null);
        com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage blankStreaming =
                new com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage();
        blankStreaming.setRole("assistant");
        blankStreaming.setType("text");
        blankStreaming.setStatus("streaming");
        blankStreaming.setContent("");
        when(messageRepository.findByConversationIdOrderByIdAsc(anyLong()))
                .thenReturn(List.of(blankFailed, blankStreaming));

        Map<String, Object> r = service.runEpisode(1L, 1L, 1L, "城东市场", 130L, "probability", null);

        assertEquals("finished", r.get("status"));
        assertEquals("no-messages", r.get("skipReason"), "仅含空占位行的会话应回收而非归档");
        verify(conversationRepository, atLeastOnce()).delete(any(ActorConversation.class));
        // 空占位行被显式清理（deleteBlankMessageRows：content 为空的消息行删除）
        verify(messageRepository, atLeastOnce()).delete(argThat(
                (com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage m) ->
                        m.getContent() == null || m.getContent().isBlank()));
        verify(worldEventBus, never()).publish(anyLong(), eq(1L), any(EventSpec.class));
    }

    // ==================== 编排注入上下文（2026-10-07 根治） ====================

    /** 编排注入段 = 世界观简报 + 各成员角色核心卡（≤3 人）。 */
    @Test
    void buildOrchestrateInjectedContext_combinesWorldAndMemberCards() throws Exception {
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        ReflectionTestUtils.setField(service, "aiContextInjector", injector);
        when(injector.worldBrief(1L)).thenReturn("青禾村四面环山，灵脉沉寂。");
        when(injector.characterCard(eq(1L), eq("npc:11"))).thenReturn("姓名：钱串子（集市商人）");
        when(injector.characterCard(eq(1L), eq("npc:12"))).thenReturn("姓名：沈砚秋（青禾村村长）");

        ActorConversation conv = new ActorConversation();
        conv.setId(1L);
        conv.setProjectId(1L);
        ActorConversationMember m1 = new ActorConversationMember();
        m1.setCharacterId(11L);
        ActorConversationMember m2 = new ActorConversationMember();
        m2.setCharacterId(12L);

        String ctx = invokeBuildOrchestrateInjectedContext(conv, List.of(m1, m2));
        assertTrue(ctx.contains("【世界观】"));
        assertTrue(ctx.contains("青禾村四面环山"));
        assertTrue(ctx.contains("【成员1】"));
        assertTrue(ctx.contains("钱串子"));
        assertTrue(ctx.contains("【成员2】"));
        assertTrue(ctx.contains("沈砚秋"));
        // 世界观在成员卡之前
        assertTrue(ctx.indexOf("【世界观】") < ctx.indexOf("【成员1】"));
    }

    /** 批 2 对话链（2026-10-08）：收尾编排注入段 2D 当前世界动态（退场/收尾判断有世界时刻依据）。 */
    @Test
    void buildOrchestrateInjectedContext_injectsWorldDynamics() throws Exception {
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        ReflectionTestUtils.setField(service, "aiContextInjector", injector);
        when(injector.worldBrief(1L)).thenReturn("青禾村四面环山，灵脉沉寂。");
        when(injector.worldDynamicsLine(1L)).thenReturn("青禾村历 0420年05月15日 22时00分 · 夏季");
        when(injector.characterCard(eq(1L), eq("npc:11"))).thenReturn("姓名：钱串子（集市商人）");

        ActorConversation conv = new ActorConversation();
        conv.setId(1L);
        conv.setProjectId(1L);
        ActorConversationMember m1 = new ActorConversationMember();
        m1.setCharacterId(11L);

        String ctx = invokeBuildOrchestrateInjectedContext(conv, List.of(m1));
        assertTrue(ctx.contains("【当前世界动态】"));
        assertTrue(ctx.contains("青禾村历 0420年05月15日 22时00分"), "收尾编排应注入世界历时刻（退场/收尾依据）");
        // 世界动态在成员卡之前、世界观之后
        assertTrue(ctx.indexOf("【当前世界动态】") > ctx.indexOf("【世界观】"));
    }

    /** 注入器未装配 → 空串（零阻断，保既有行为）。 */
    @Test
    void buildOrchestrateInjectedContext_emptyWhenInjectorMissing() throws Exception {
        ReflectionTestUtils.setField(service, "aiContextInjector", null);
        ActorConversation conv = new ActorConversation();
        conv.setProjectId(1L);
        ActorConversationMember m1 = new ActorConversationMember();
        m1.setCharacterId(11L);
        assertEquals("", invokeBuildOrchestrateInjectedContext(conv, List.of(m1)));
    }

    /** 无成员/空项目 → 空串。 */
    @Test
    void buildOrchestrateInjectedContext_emptyWhenNoMembers() throws Exception {
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        ReflectionTestUtils.setField(service, "aiContextInjector", injector);
        ActorConversation conv = new ActorConversation();
        conv.setProjectId(1L);
        assertEquals("", invokeBuildOrchestrateInjectedContext(conv, List.of()));
    }

    private String invokeBuildOrchestrateInjectedContext(ActorConversation conv,
                                                         List<ActorConversationMember> members) throws Exception {
        java.lang.reflect.Method m = AutonomousChatService.class.getDeclaredMethod(
                "buildOrchestrateInjectedContext", ActorConversation.class, List.class);
        m.setAccessible(true);
        return (String) m.invoke(service, conv, members);
    }
}
