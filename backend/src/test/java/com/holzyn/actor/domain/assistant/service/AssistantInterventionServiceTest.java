package com.holzyn.actor.domain.assistant.service;

import com.holzyn.actor.domain.agent.roleplay.RoleplayService;
import com.holzyn.actor.domain.agent.runtime.AgentRuntime;
import com.holzyn.actor.domain.agent.runtime.WorldEngine;
import com.holzyn.actor.domain.assistant.entity.ActorAssistantIntervention;
import com.holzyn.actor.domain.assistant.repository.ActorAssistantInterventionRepository;
import com.holzyn.actor.domain.conversation.service.ConversationService;
import com.holzyn.actor.domain.conversation.vo.ConversationVO;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationMemberRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationRepository;
import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.sandbox.vo.WeatherVO;
import com.holzyn.actor.domain.world.service.TimelineService;
import com.holzyn.actor.domain.world.service.WeatherSwitchService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 助手世界干预服务单元测试（能力域 F，M4）。
 * <p>职责：验证——按确认粒度（strict/standard/trust）的待确认/立即执行判定、参数校验降级、
 * 确认执行（复用沙盘服务）与低风险放行集累积、取消。</p>
 */
class AssistantInterventionServiceTest {

    private ActorAssistantInterventionRepository interventionRepository;
    private AssistantSessionService sessionService;
    private ActorProjectRepository projectRepository;
    private ActorCharacterRepository characterRepository;
    private NpcTypeResolver npcTypeResolver;
    private ConversationService conversationService;
    private ActorConversationRepository conversationRepository;
    private ActorConversationMemberRepository conversationMemberRepository;
    private RoleplayService roleplayService;
    private AgentRuntime agentRuntime;
    private TimelineService timelineService;
    private WorldClockService worldClockService;
    private WorldEngine worldEngine;
    private WeatherSwitchService weatherSwitchService;
    private AssistantInterventionService service;

    private final List<ActorAssistantIntervention> plans = new ArrayList<>();

    private ActorCharacter shen;

    @BeforeEach
    void setUp() {
        interventionRepository = mock(ActorAssistantInterventionRepository.class);
        sessionService = mock(AssistantSessionService.class);
        projectRepository = mock(ActorProjectRepository.class);
        characterRepository = mock(ActorCharacterRepository.class);
        npcTypeResolver = mock(NpcTypeResolver.class);
        conversationService = mock(ConversationService.class);
        conversationRepository = mock(ActorConversationRepository.class);
        conversationMemberRepository = mock(ActorConversationMemberRepository.class);
        roleplayService = mock(RoleplayService.class);
        agentRuntime = mock(AgentRuntime.class);
        timelineService = mock(TimelineService.class);
        worldClockService = mock(WorldClockService.class);
        worldEngine = mock(WorldEngine.class);
        weatherSwitchService = mock(WeatherSwitchService.class);
        service = new AssistantInterventionService(interventionRepository, sessionService, projectRepository,
                characterRepository, npcTypeResolver, conversationService, conversationRepository,
                conversationMemberRepository, roleplayService, agentRuntime, timelineService,
                worldClockService, worldEngine, weatherSwitchService);

        when(interventionRepository.save(any(ActorAssistantIntervention.class))).thenAnswer(inv -> {
            ActorAssistantIntervention p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId((long) (plans.size() + 1));
            }
            plans.removeIf(x -> x.getId().equals(p.getId()));
            plans.add(p);
            return p;
        });
        when(interventionRepository.findTopBySessionIdAndStatusOrderByIdDesc(anyLong(), anyString()))
                .thenAnswer(inv -> plans.stream()
                        .filter(p -> inv.getArgument(0).equals(p.getSessionId())
                                && inv.getArgument(1).equals(p.getStatus()))
                        .reduce((a, b) -> b));
        when(interventionRepository.findByIdAndProjectIdAndSessionId(anyLong(), anyLong(), anyLong()))
                .thenAnswer(inv -> plans.stream()
                        .filter(p -> inv.getArgument(0).equals(p.getId())
                                && inv.getArgument(1).equals(p.getProjectId())
                                && inv.getArgument(2).equals(p.getSessionId()))
                        .findFirst());

        ActorProject project = new ActorProject();
        project.setId(1L);
        project.setName("青禾村");
        project.setDeleted(0);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        shen = new ActorCharacter();
        shen.setId(1L);
        shen.setProjectId(1L);
        shen.setName("沈守拙");
        shen.setDeleted(0);
        when(characterRepository.findById(1L)).thenReturn(Optional.of(shen));

        when(npcTypeResolver.isSpecial(1L, "npc:1")).thenReturn(true);
        when(npcTypeResolver.typeOf(anyLong(), anyString())).thenReturn("special");
    }

    // ==================== strict：每次确认 ====================

    @Test
    void strictModeTalkAlwaysPending() {
        when(sessionService.confirmModeOf(1L, 1L)).thenReturn("strict");
        var r = service.plan(1L, 1L, 1L, "talk",
                Map.of("npcRef", "npc:1", "content", "我们去云来茶馆"));
        assertTrue(r.needsConfirm());
        assertEquals("low", r.risk());
        assertTrue(r.detail().contains("沈守拙"));
        // 已持久化待确认计划
        assertEquals(1, plans.size());
        assertEquals("pending", plans.get(0).getStatus());
        verify(roleplayService, never()).sendMessage(any(), any(), any());
    }

    // ==================== trust：自动执行 ====================

    @Test
    void trustModeWeatherExecutesImmediately() {
        when(sessionService.confirmModeOf(1L, 1L)).thenReturn("trust");
        WeatherVO vo = new WeatherVO();
        vo.setCondition("晴");
        vo.setRegion("青禾村");
        when(weatherSwitchService.switchWeather(1L, "晴")).thenReturn(vo);
        var r = service.plan(1L, 1L, 1L, "weather", Map.of("condition", "晴"));
        assertFalse(r.needsConfirm());
        assertEquals("executed", r.status());
        assertTrue(r.resultText().contains("晴"));
        verify(weatherSwitchService).switchWeather(1L, "晴");
        // 立即执行同样全量留痕（executed）
        assertEquals(1, plans.size());
        assertEquals("executed", plans.get(0).getStatus());
        assertEquals("weather", plans.get(0).getOperation());
    }

    // ==================== standard：高风险每次确认 / 低风险首次确认后放行 ====================

    @Test
    void standardModeHighRiskEventAlwaysPending() {
        when(sessionService.confirmModeOf(1L, 1L)).thenReturn("standard");
        when(sessionService.isApprovedOp(1L, 1L, "event")).thenReturn(false);
        var r = service.plan(1L, 1L, 1L, "event", Map.of("content", "集市开张了"));
        assertTrue(r.needsConfirm());
        assertEquals("high", r.risk());
        assertEquals(1, plans.size());
        verify(timelineService, never()).createEvent(any(), any());
    }

    @Test
    void standardModeLowRiskFirstTimePendingThenApprovedExecutes() {
        when(sessionService.confirmModeOf(1L, 1L)).thenReturn("standard");
        // 首次：未放行 → 待确认
        when(sessionService.isApprovedOp(1L, 1L, "weather")).thenReturn(false);
        var first = service.plan(1L, 1L, 1L, "weather", Map.of("condition", "雨"));
        assertTrue(first.needsConfirm());
        assertEquals(1, plans.size());
        // 用户确认后：低风险 → 标记放行
        WeatherVO vo = new WeatherVO();
        vo.setCondition("雨");
        when(weatherSwitchService.switchWeather(1L, "雨")).thenReturn(vo);
        var confirmed = service.confirm(1L, 1L, plans.get(0).getId());
        assertEquals("executed", confirmed.status());
        verify(sessionService).markApprovedOp(1L, 1L, "weather");
        verify(weatherSwitchService).switchWeather(1L, "雨");
        // 再次请求同类低风险 → 已放行 → 直接执行
        when(sessionService.isApprovedOp(1L, 1L, "weather")).thenReturn(true);
        WeatherVO vo2 = new WeatherVO();
        vo2.setCondition("晴");
        when(weatherSwitchService.switchWeather(1L, "晴")).thenReturn(vo2);
        var second = service.plan(1L, 1L, 1L, "weather", Map.of("condition", "晴"));
        assertFalse(second.needsConfirm());
        assertEquals("executed", second.status());
        verify(weatherSwitchService).switchWeather(1L, "晴");
    }

    // ==================== 确认 / 取消 ====================

    @Test
    void cancelMarksCancelled() {
        when(sessionService.confirmModeOf(1L, 1L)).thenReturn("strict");
        var r = service.plan(1L, 1L, 1L, "talk",
                Map.of("npcRef", "npc:1", "content", "你好"));
        Long planId = r.planId();
        var cancelled = service.cancel(1L, 1L, planId);
        assertEquals("cancelled", cancelled.status());
        assertEquals("cancelled", plans.get(0).getStatus());
    }

    @Test
    void confirmNonPendingRejected() {
        when(sessionService.confirmModeOf(1L, 1L)).thenReturn("strict");
        var r = service.plan(1L, 1L, 1L, "talk",
                Map.of("npcRef", "npc:1", "content", "你好"));
        service.cancel(1L, 1L, r.planId());
        try {
            service.confirm(1L, 1L, r.planId());
            assertTrue(false, "应拒绝已处理计划");
        } catch (com.holzyn.actor.common.BizException e) {
            assertTrue(e.getMessage().contains("已处理"));
        }
    }

    // ==================== 参数校验降级 ====================

    @Test
    void invalidWeatherConditionFails() {
        when(sessionService.confirmModeOf(1L, 1L)).thenReturn("trust");
        var r = service.plan(1L, 1L, 1L, "weather", Map.of("condition", "台风"));
        assertEquals("failed", r.status());
        assertTrue(r.resultText().contains("不支持的天气条件"));
    }

    @Test
    void talkMissingNpcFails() {
        when(sessionService.confirmModeOf(1L, 1L)).thenReturn("strict");
        var r = service.plan(1L, 1L, 1L, "talk", Map.of("content", "你好"));
        assertEquals("failed", r.status());
        assertTrue(r.resultText().contains("npcRef"));
    }

    @Test
    void unknownOperationFails() {
        var r = service.plan(1L, 1L, 1L, "teleport", Map.of());
        assertEquals("failed", r.status());
        assertTrue(r.resultText().contains("不支持的操作类型"));
    }

    @Test
    void actionRequiresSpecialNpc() {
        when(sessionService.confirmModeOf(1L, 1L)).thenReturn("trust");
        when(npcTypeResolver.isSpecial(1L, "npc:1")).thenReturn(false);
        var r = service.plan(1L, 1L, 1L, "action", Map.of("npcRef", "npc:1", "reason", "看看他在干嘛"));
        assertEquals("failed", r.status());
        assertTrue(r.resultText().contains("仅支持特殊角色"));
    }

    @Test
    void talkExecutesViaConversationAndRoleplay() {
        when(sessionService.confirmModeOf(1L, 1L)).thenReturn("trust");
        ConversationVO conv = new ConversationVO(5L, 1L, 1L, "single", "t", null, null, null, null,
                List.of(1L), List.of(), null, null, null, "single", "active", null);
        when(conversationService.detail(5L)).thenReturn(conv);
        when(conversationService.create(anyLong(), any())).thenReturn(conv);
        when(conversationMemberRepository.findByCharacterId(1L)).thenReturn(List.of());
        when(roleplayService.sendMessage(5L, "你好", 1L)).thenReturn(Map.of("sent", true));

        var r = service.plan(1L, 1L, 1L, "talk", Map.of("npcRef", "npc:1", "content", "你好"));
        assertEquals("executed", r.status());
        verify(roleplayService).sendMessage(5L, "你好", 1L);
    }

    @Test
    void actionExecutesViaAgentRuntime() {
        when(sessionService.confirmModeOf(1L, 1L)).thenReturn("trust");
        when(npcTypeResolver.isSpecial(1L, "npc:1")).thenReturn(true);
        ActorActionPlan plan = new ActorActionPlan();
        plan.setId(11L);
        plan.setCharacterId(1L);
        plan.setStatus("done");
        plan.setTriggerType("manual");
        when(agentRuntime.triggerAction(1L, 1L, "manual", null, "测试理由", null, null)).thenReturn(plan);

        var r = service.plan(1L, 1L, 1L, "action",
                Map.of("npcRef", "npc:1", "reason", "测试理由"));
        assertEquals("executed", r.status());
        assertTrue(r.resultText().contains("行动"));
    }

    // ==================== M5：修改后执行（revise） ====================

    @Test
    void reviseWeatherExecutesWithNewCondition() {
        when(sessionService.confirmModeOf(1L, 1L)).thenReturn("strict");
        var p = service.plan(1L, 1L, 1L, "weather", Map.of("condition", "雨"));
        assertTrue(p.needsConfirm());
        Long planId = p.planId();
        // 用户把「雨」改为「晴」后执行
        WeatherVO vo = new WeatherVO();
        vo.setCondition("晴");
        when(weatherSwitchService.switchWeather(1L, "晴")).thenReturn(vo);
        var r = service.revise(1L, 1L, planId, Map.of("condition", "晴"));
        assertEquals("executed", r.status());
        verify(weatherSwitchService).switchWeather(1L, "晴");
        // 计划已更新参数并留痕 executed
        assertEquals("executed", plans.get(0).getStatus());
        assertTrue(plans.get(0).getParamsJson().contains("晴"));
        assertTrue(plans.get(0).getDetail().contains("晴"));
        // 原参数「雨」不再执行
        verify(weatherSwitchService, never()).switchWeather(1L, "雨");
    }

    @Test
    void reviseInvalidParamsKeepsPlanPending() {
        when(sessionService.confirmModeOf(1L, 1L)).thenReturn("strict");
        var p = service.plan(1L, 1L, 1L, "talk", Map.of("npcRef", "npc:1", "content", "你好"));
        Long planId = p.planId();
        // 修改后内容为空 → 参数不合法：failed 且计划保持 pending（可继续修改重试）
        var r = service.revise(1L, 1L, planId, Map.of("npcRef", "npc:1", "content", "  "));
        assertEquals("failed", r.status());
        assertTrue(r.resultText().contains("插话内容不能为空"));
        assertEquals("pending", plans.get(0).getStatus());
        verify(roleplayService, never()).sendMessage(any(), any(), any());
    }

    @Test
    void reviseLowRiskStandardMarksApproved() {
        when(sessionService.confirmModeOf(1L, 1L)).thenReturn("standard");
        when(sessionService.isApprovedOp(1L, 1L, "weather")).thenReturn(false);
        var p = service.plan(1L, 1L, 1L, "weather", Map.of("condition", "雨"));
        assertTrue(p.needsConfirm());
        WeatherVO vo = new WeatherVO();
        vo.setCondition("晴");
        when(weatherSwitchService.switchWeather(1L, "晴")).thenReturn(vo);
        var r = service.revise(1L, 1L, p.planId(), Map.of("condition", "晴"));
        assertEquals("executed", r.status());
        // 用户经「修改后执行」显式确认 → 标准模式低风险同样放行
        verify(sessionService).markApprovedOp(1L, 1L, "weather");
    }

    @Test
    void reviseNonPendingRejected() {
        when(sessionService.confirmModeOf(1L, 1L)).thenReturn("strict");
        var p = service.plan(1L, 1L, 1L, "talk", Map.of("npcRef", "npc:1", "content", "你好"));
        service.cancel(1L, 1L, p.planId());
        try {
            service.revise(1L, 1L, p.planId(), Map.of("npcRef", "npc:1", "content", "再见"));
            assertTrue(false, "应拒绝已处理计划");
        } catch (com.holzyn.actor.common.BizException e) {
            assertTrue(e.getMessage().contains("已处理"));
        }
    }
}
