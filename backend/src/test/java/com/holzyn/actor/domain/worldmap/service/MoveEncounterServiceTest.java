package com.holzyn.actor.domain.worldmap.service;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.sandbox.service.WorldSandboxSseHub;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.worldmap.config.WorldMapProperties;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MoveEncounterService 单元测试（位置移动与地图生成系统 · M3 §4.6，仓库/AI 全 mock）。
 * <p>职责：途中遭遇触发（附近角色 + 冷却检查 + 异步判定）；冷却（每角色每游戏小时 ≤N，
 * 冷却期内跳过）；失败 fail-open（AI 异常不阻断）；stop=true 打断移动；relationHint 关系提示；
 * move_encounter 模板/PromptSpec 登记。</p>
 */
class MoveEncounterServiceTest {

    private WorldMapProperties properties;
    private MovementService movementService;
    private WorldMapQueryService worldMapQueryService;
    private PromptTemplateService promptTemplateService;
    private AiSpringChatGateway aiChatGateway;
    private UsageLogService usageLogService;
    private CurrentUserProvider currentUserProvider;
    private ActorCharacterRepository characterRepository;
    private ActorCharacterRelationRepository relationRepository;
    private WorldSandboxSseHub sandboxHub;
    private MoveEncounterService service;

    @BeforeEach
    void setUp() {
        properties = new WorldMapProperties();
        movementService = mock(MovementService.class);
        worldMapQueryService = mock(WorldMapQueryService.class);
        promptTemplateService = mock(PromptTemplateService.class);
        aiChatGateway = mock(AiSpringChatGateway.class);
        usageLogService = mock(UsageLogService.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        characterRepository = mock(ActorCharacterRepository.class);
        relationRepository = mock(ActorCharacterRelationRepository.class);
        service = new MoveEncounterService(properties, movementService, worldMapQueryService,
                promptTemplateService, aiChatGateway, usageLogService, currentUserProvider,
                characterRepository, relationRepository, new ObjectMapper());
        sandboxHub = mock(WorldSandboxSseHub.class);
        ReflectionTestUtils.setField(service, "sandboxHub", sandboxHub);
        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), org.mockito.ArgumentMatchers.isNull(),
                any(AiChatRequest.class)))
                .thenReturn(aiResult("{\"greet\":\"张掌柜，赶路呢？\",\"stop\":false}"));
    }

    // ==================== 途中遭遇触发 ====================

    @Test
    void maybeEncounter_noNearbySkips() {
        when(worldMapQueryService.nearbyRefs(1L, 0d, 0d, "npc:1", 30)).thenReturn(List.of());
        service.maybeEncounter(1L, "npc:1", 0d, 0d, 1000L);
        verify(promptTemplateService, never()).render(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void maybeEncounter_disabledSkips() {
        properties.setEncounterEnabled(false);
        service.maybeEncounter(1L, "npc:1", 0d, 0d, 1000L);
        verify(worldMapQueryService, never()).nearbyRefs(anyLong(), anyDouble(), anyDouble(), anyString(), anyDouble());
    }

    @Test
    void maybeEncounter_withNearbyRendersAndJudges() {
        ActorCharacter mover = character(1L, "沈砚秋", "正赶往医馆");
        when(characterRepository.findById(1L)).thenReturn(Optional.of(mover));
        ActorCharacter other = character(2L, "李掌柜", "在铺子忙");
        when(characterRepository.findById(2L)).thenReturn(Optional.of(other));
        when(worldMapQueryService.nearbyRefs(1L, 0d, 0d, "npc:1", 30)).thenReturn(List.of("npc:2"));
        when(relationRepository.findByProjectId(1L)).thenReturn(List.of());
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("prompt");

        service.maybeEncounter(1L, "npc:1", 0d, 0d, 1000L);

        // 异步执行（虚拟线程）——先等异步任务完成再验证
        awaitIdle();
        verify(promptTemplateService).render(eq(1L), eq(1L), eq(PromptTemplateService.CODE_MOVE_ENCOUNTER), any());
        verify(aiChatGateway).chatCompletion(anyLong(), anyLong(), org.mockito.ArgumentMatchers.isNull(),
                any(AiChatRequest.class));
        verify(sandboxHub).publish(anyLong(), anyString(), any());
        verify(movementService).publishEncounterEvent(anyLong(), anyString(), anyString(), anyString(),
                anyString(), any(Boolean.class), anyLong());
        // stop=false → 不打断移动
        verify(movementService, never()).cancelMove(anyLong(), anyString(), any());
    }

    @Test
    void maybeEncounter_stopTrueInterruptsMove() {
        ActorCharacter mover = character(1L, "沈砚秋", "正赶往医馆");
        when(characterRepository.findById(1L)).thenReturn(Optional.of(mover));
        ActorCharacter other = character(2L, "李掌柜", "在铺子忙");
        when(characterRepository.findById(2L)).thenReturn(Optional.of(other));
        when(worldMapQueryService.nearbyRefs(1L, 0d, 0d, "npc:1", 30)).thenReturn(List.of("npc:2"));
        when(relationRepository.findByProjectId(1L)).thenReturn(List.of());
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("prompt");
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), org.mockito.ArgumentMatchers.isNull(),
                any(AiChatRequest.class)))
                .thenReturn(aiResult("{\"greet\":\"李掌柜，正好有事问你\",\"stop\":true}"));

        service.maybeEncounter(1L, "npc:1", 0d, 0d, 1000L);

        awaitIdle();
        verify(movementService).cancelMove(1L, "npc:1", 1000L);
    }

    // ==================== 批3 决策与感知：段2D/2E 注入（2026-10-08） ====================

    /**
     * 途中交互注入器装配：compose（段1A 世界观+段1C 角色卡）+ decisionStatePrefix
     * （段2D 当前世界动态 + 段2E 当前状态）都在模板 prompt 之前进 AI 请求。
     */
    @Test
    void maybeEncounter_injectsDecisionStatePrefixBeforePrompt() {
        ActorCharacter mover = character(1L, "沈砚秋", "正赶往医馆");
        when(characterRepository.findById(1L)).thenReturn(Optional.of(mover));
        ActorCharacter other = character(2L, "李掌柜", "在铺子忙");
        when(characterRepository.findById(2L)).thenReturn(Optional.of(other));
        when(worldMapQueryService.nearbyRefs(1L, 0d, 0d, "npc:1", 30)).thenReturn(List.of("npc:2"));
        when(relationRepository.findByProjectId(1L)).thenReturn(List.of());
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("prompt");
        com.holzyn.actor.domain.agent.context.AiContextInjector inj =
                mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(inj.compose(1L, "npc:1")).thenReturn("【世界观】青禾村志\n【我的身份与处境】沈砚秋");
        when(inj.decisionStatePrefix(1L, "npc:1", 1000L))
                .thenReturn("【当前世界动态】青禾村历 0420年05月15日 06时00分 · 夏季\n"
                        + "【当前状态】\n- 需求：饥饿 68/100（略饿）");
        ReflectionTestUtils.setField(service, "aiContextInjector", inj);

        service.maybeEncounter(1L, "npc:1", 0d, 0d, 1000L);

        awaitIdle();
        org.mockito.ArgumentCaptor<AiChatRequest> reqCaptor =
                org.mockito.ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(anyLong(), anyLong(), org.mockito.ArgumentMatchers.isNull(),
                reqCaptor.capture());
        String user = String.valueOf(reqCaptor.getValue().messages().get(1).content());
        // 段1A 世界观 + 段1C 角色卡 + 段2D + 段2E + 模板（段2H 途中遭遇）
        assertTrue(user.contains("【世界观】"), "应含段1A 世界观: " + user);
        assertTrue(user.contains("【我的身份与处境】"), "应含段1C 角色卡: " + user);
        assertTrue(user.contains("【当前世界动态】"), "应含段2D: " + user);
        assertTrue(user.contains("【当前状态】"), "应含段2E: " + user);
        assertTrue(user.contains("prompt"), "模板 prompt 保留（段2H 途中遭遇）: " + user);
        // 顺序：段2D/2E 在 段1 后、模板前
        assertTrue(user.indexOf("【当前世界动态】") > user.indexOf("【世界观】"),
                "段2D 在段1A 后");
        assertTrue(user.indexOf("prompt") > user.indexOf("【当前状态】"), "模板在段2E 后");
    }

    /**
     * 途中交互注入器装配但段2D/2E 无数据 → 仅保留 compose + 模板（零阻断降级）。
     */
    @Test
    void maybeEncounter_injectorBlankDecisionPrefixDegrades() {
        ActorCharacter mover = character(1L, "沈砚秋", "正赶往医馆");
        when(characterRepository.findById(1L)).thenReturn(Optional.of(mover));
        ActorCharacter other = character(2L, "李掌柜", "在铺子忙");
        when(characterRepository.findById(2L)).thenReturn(Optional.of(other));
        when(worldMapQueryService.nearbyRefs(1L, 0d, 0d, "npc:1", 30)).thenReturn(List.of("npc:2"));
        when(relationRepository.findByProjectId(1L)).thenReturn(List.of());
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("prompt");
        com.holzyn.actor.domain.agent.context.AiContextInjector inj =
                mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(inj.compose(1L, "npc:1")).thenReturn("【世界观】青禾村志\n【我的身份与处境】沈砚秋");
        when(inj.decisionStatePrefix(1L, "npc:1", 1000L)).thenReturn("");
        ReflectionTestUtils.setField(service, "aiContextInjector", inj);

        service.maybeEncounter(1L, "npc:1", 0d, 0d, 1000L);

        awaitIdle();
        org.mockito.ArgumentCaptor<AiChatRequest> reqCaptor =
                org.mockito.ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(anyLong(), anyLong(), org.mockito.ArgumentMatchers.isNull(),
                reqCaptor.capture());
        String user = String.valueOf(reqCaptor.getValue().messages().get(1).content());
        assertTrue(user.contains("【世界观】"));
        assertTrue(user.contains("prompt"));
        assertFalse(user.contains("【当前世界动态】"), "段2D 无数据 → 不注入: " + user);
    }

    // ==================== 冷却 ====================

    @Test
    void maybeEncounter_cooldownSkipsSecond() {
        ActorCharacter mover = character(1L, "沈砚秋", "正赶往医馆");
        when(characterRepository.findById(1L)).thenReturn(Optional.of(mover));
        ActorCharacter other = character(2L, "李掌柜", "在铺子忙");
        when(characterRepository.findById(2L)).thenReturn(Optional.of(other));
        when(worldMapQueryService.nearbyRefs(1L, 0d, 0d, "npc:1", 30)).thenReturn(List.of("npc:2"));
        when(relationRepository.findByProjectId(1L)).thenReturn(List.of());
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("prompt");

        // 同一游戏小时（3600 内）第二次触发 → 冷却期内跳过
        service.maybeEncounter(1L, "npc:1", 0d, 0d, 1000L);
        awaitIdle();
        verify(aiChatGateway).chatCompletion(anyLong(), anyLong(), org.mockito.ArgumentMatchers.isNull(),
                any(AiChatRequest.class));

        // 清 mock 计数后第二次（仍在 0-3599 秒内=同一游戏小时）→ 应跳过
        org.mockito.Mockito.clearInvocations(aiChatGateway);
        service.maybeEncounter(1L, "npc:1", 0d, 0d, 2000L);
        awaitIdle();
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    @Test
    void maybeEncounter_cooldownPassesNextHour() {
        ActorCharacter mover = character(1L, "沈砚秋", "正赶往医馆");
        when(characterRepository.findById(1L)).thenReturn(Optional.of(mover));
        ActorCharacter other = character(2L, "李掌柜", "在铺子忙");
        when(characterRepository.findById(2L)).thenReturn(Optional.of(other));
        when(worldMapQueryService.nearbyRefs(1L, 0d, 0d, "npc:1", 30)).thenReturn(List.of("npc:2"));
        when(relationRepository.findByProjectId(1L)).thenReturn(List.of());
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("prompt");

        service.maybeEncounter(1L, "npc:1", 0d, 0d, 1000L);
        awaitIdle();
        org.mockito.Mockito.clearInvocations(aiChatGateway);
        // 下一个游戏小时（3600+）→ 冷却已过，重新触发
        service.maybeEncounter(1L, "npc:1", 0d, 0d, 5000L);
        awaitIdle();
        verify(aiChatGateway).chatCompletion(anyLong(), anyLong(), org.mockito.ArgumentMatchers.isNull(),
                any(AiChatRequest.class));
    }

    // ==================== 失败隔离（fail-open） ====================

    @Test
    void maybeEncounter_aiFailureFailOpen() {
        ActorCharacter mover = character(1L, "沈砚秋", "正赶往医馆");
        when(characterRepository.findById(1L)).thenReturn(Optional.of(mover));
        ActorCharacter other = character(2L, "李掌柜", "在铺子忙");
        when(characterRepository.findById(2L)).thenReturn(Optional.of(other));
        when(worldMapQueryService.nearbyRefs(1L, 0d, 0d, "npc:1", 30)).thenReturn(List.of("npc:2"));
        when(relationRepository.findByProjectId(1L)).thenReturn(List.of());
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("prompt");
        // AI 抛异常 → fail-open：不打断、不落事件、不广播
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), org.mockito.ArgumentMatchers.isNull(),
                any(AiChatRequest.class)))
                .thenThrow(new RuntimeException("AI 不可用"));

        service.maybeEncounter(1L, "npc:1", 0d, 0d, 1000L);

        awaitIdle();
        verify(movementService, never()).cancelMove(anyLong(), anyString(), any());
        verify(movementService, never()).publishEncounterEvent(anyLong(), anyString(), anyString(),
                anyString(), anyString(), any(Boolean.class), anyLong());
    }

    @Test
    void maybeEncounter_invalidJsonFailOpen() {
        ActorCharacter mover = character(1L, "沈砚秋", "正赶往医馆");
        when(characterRepository.findById(1L)).thenReturn(Optional.of(mover));
        ActorCharacter other = character(2L, "李掌柜", "在铺子忙");
        when(characterRepository.findById(2L)).thenReturn(Optional.of(other));
        when(worldMapQueryService.nearbyRefs(1L, 0d, 0d, "npc:1", 30)).thenReturn(List.of("npc:2"));
        when(relationRepository.findByProjectId(1L)).thenReturn(List.of());
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("prompt");
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), org.mockito.ArgumentMatchers.isNull(),
                any(AiChatRequest.class)))
                .thenReturn(aiResult("不是 JSON"));

        service.maybeEncounter(1L, "npc:1", 0d, 0d, 1000L);

        awaitIdle();
        verify(movementService, never()).cancelMove(anyLong(), anyString(), any());
    }

    // ==================== 静态/登记 ====================

    @Test
    void promptSpecRegistered() {
        var spec = PromptSpecRegistry.spec(MoveEncounterService.SCENE_MOVE_ENCOUNTER);
        assertEquals("move_encounter", spec.code());
        assertTrue(spec.maxOutputTokens() > 0);
    }

    @Test
    void cooldownKeyFormat() {
        assertEquals("1:npc:1", MoveEncounterService.cooldownKey(1L, "npc:1"));
    }

    @Test
    void relationHint_knownRelationReturnsHint() {
        ActorCharacterRelation rel = new ActorCharacterRelation();
        rel.setFromCharacterId(1L);
        rel.setToCharacterId(2L);
        rel.setRelationType("主顾");
        rel.setDescription("常来铺子买粮");
        when(relationRepository.findByProjectId(1L)).thenReturn(List.of(rel));
        ActorCharacter other = character(2L, "李掌柜", null);
        String hint = service.relationHint(1L, 1L, other);
        assertTrue(hint.contains("主顾"), "应返回关系类型: " + hint);
    }

    @Test
    void relationHint_unknownReturnsEmpty() {
        when(relationRepository.findByProjectId(1L)).thenReturn(List.of());
        ActorCharacter other = character(2L, "路人甲", null);
        assertEquals("", service.relationHint(1L, 1L, other));
    }

    // ==================== 工具 ====================

    private void awaitIdle() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private AiChatResult aiResult(String content) {
        return new AiChatResult(content, "test-model", 10, 5, 15, 0, 0, 1L);
    }

    private ActorCharacter character(long id, String name, String activity) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setName(name);
        c.setCurrentActivity(activity);
        return c;
    }
}
