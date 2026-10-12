package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.action.AgentActionService;
import com.holzyn.actor.domain.character.service.PromptService;
import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.npc.goal.entity.ActorInterruptRecord;
import com.holzyn.actor.domain.npc.goal.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.npc.goal.repository.ActorInterruptRecordRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 打断与恢复服务单元测试（实时行为决策系统 M2，domain/agent/runtime，同包）。
 * <p>职责：验证——① 打断记录创建（有当前活动才创建 / 已有 active 不覆盖 / 过期替换 /
 * crowd 忽略 / 开关关闭）；② AI 恢复决策三态执行（resume 回原地点继续原活动 / adjust 调整新任务 /
 * abandon 仅标记丢弃，均新建计划真执行）；③ P0-P3 共性框架确定性兜底（未过期+任务合理 → resume；
 * 过期/空任务 → abandon）；④ 静态逻辑（fallbackResumeDecision / expired / ResumeDecisionParse）。</p>
 * <p>设计依据：《HolzynActor_实时行为决策系统_开发路径_V1.0》§4.3 + M2 用户逐题决策
 * （2026-09-07：载体=新表 actor_interrupt_record / 恢复=响应行动后立即 AI 自行调整任务 /
 * 三态执行 / 同流同预算 / 基础上下文+轻量 L3 性格）。</p>
 */
class InterruptRecoveryServiceTest {

    private ActorInterruptRecordRepository interruptRepository;
    private ActorCharacterRepository characterRepository;
    private ActorActionPlanRepository planRepository;
    private AgentActionService agentActionService;
    private BudgetGuard budgetGuard;
    private RealtimeDecisionProperties props;
    private AiSpringChatGateway aiChatGateway;
    private PromptService promptService;
    private UsageLogService usageLogService;
    private CurrentUserProvider currentUserProvider;
    private InterruptRecoveryService service;

    /** 新计划落库快照（验证三态执行产物） */
    private final List<ActorActionPlan> savedPlans = new ArrayList<>();

    @BeforeEach
    void setUp() {
        interruptRepository = Mockito.mock(ActorInterruptRecordRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        planRepository = Mockito.mock(ActorActionPlanRepository.class);
        agentActionService = Mockito.mock(AgentActionService.class);
        budgetGuard = Mockito.mock(BudgetGuard.class);
        props = new RealtimeDecisionProperties();
        aiChatGateway = Mockito.mock(AiSpringChatGateway.class);
        promptService = Mockito.mock(PromptService.class);
        usageLogService = Mockito.mock(UsageLogService.class);
        currentUserProvider = Mockito.mock(CurrentUserProvider.class);

        // G1a：构造器第 3 参为 NpcTypeResolver（npc:{id} 不再编码类型，普通型判定查 actor_profile.type）
        service = new InterruptRecoveryService(interruptRepository, characterRepository,
                new NpcTypeResolver(characterRepository), planRepository,
                agentActionService, budgetGuard, props, aiChatGateway, promptService, usageLogService,
                currentUserProvider, new ObjectMapper());

        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(interruptRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(any(), any(), any()))
                .thenReturn(List.of());
        // AI 预算默认放行（tryConsume true）
        when(budgetGuard.tryConsume(any())).thenReturn(true);
        // 计划落库快照
        Mockito.doAnswer(inv -> {
            savedPlans.add(inv.getArgument(0));
            return inv.getArgument(0);
        }).when(planRepository).save(any(ActorActionPlan.class));
    }

    private ActorCharacter newChar(Long id, String name, String activity, String location) {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(id);
        ch.setProjectId(1L);
        ch.setName(name);
        ch.setCurrentActivity(activity);
        ch.setLocation(location);
        return ch;
    }

    /** 普通型 NPC 夹具（G1a：单主表下必须显式 setType("common")，实体默认值是 special；
     *  是否特殊型由 NpcTypeResolver 查 actor_profile.type 判定） */
    private ActorCharacter commonChar(Long id, String name, String activity, String location) {
        ActorCharacter ch = newChar(id, name, activity, location);
        ch.setType("common");
        return ch;
    }

    private ActorInterruptRecord newRecord(Long id, String activity, String location,
                                           Long interruptedAt, Long expireAt, String status) {
        ActorInterruptRecord rec = new ActorInterruptRecord();
        rec.setId(id);
        rec.setProjectId(1L);
        rec.setNpcType("special");
        rec.setNpcRef("npc:1");
        rec.setInterruptedActivity(activity);
        rec.setInterruptedLocation(location);
        rec.setInterruptedAtGameSecond(interruptedAt);
        rec.setExpireAtGameSecond(expireAt);
        rec.setStatus(status);
        return rec;
    }

    /** mock AI 恢复决策返回（aiChatGateway.chatCompletion → JSON 文本）。 */
    private void mockAiResume(String json) {
        AiChatResult result = Mockito.mock(AiChatResult.class);
        when(result.content()).thenReturn(json);
        when(result.providerId()).thenReturn(1L);
        when(result.model()).thenReturn("mock-model");
        when(aiChatGateway.chatCompletion(any(), any(), any(), any())).thenReturn(result);
    }

    // ==================== 打断记录创建（interrupt） ====================

    @Test
    void interrupt_createsRecordWhenCurrentActivityExists() {
        when(characterRepository.findById(1L)).thenReturn(Optional.of(newChar(1L, "甲", "在茶馆喝茶", "茶馆")));
        Long planId = service.interrupt(1L, "npc:1", 36000L);
        assertNull(planId, "无对应计划行 → planId null");
        Mockito.verify(interruptRepository).save(Mockito.argThat(rec ->
                "active".equals(rec.getStatus())
                        && "在茶馆喝茶".equals(rec.getInterruptedActivity())
                        && "茶馆".equals(rec.getInterruptedLocation())
                        && Long.valueOf(36000L).equals(rec.getInterruptedAtGameSecond())
                        && Long.valueOf(36000L + 3600L).equals(rec.getExpireAtGameSecond())
                        && "npc:1".equals(rec.getNpcRef())));
    }

    @Test
    void interrupt_skipsWhenNoCurrentActivity() {
        when(characterRepository.findById(1L)).thenReturn(Optional.of(newChar(1L, "甲", "", "茶馆")));
        Long planId = service.interrupt(1L, "npc:1", 36000L);
        assertNull(planId);
        verify(interruptRepository, never()).save(any(ActorInterruptRecord.class));
    }

    @Test
    void interrupt_skipsWhenActiveRecordExists() {
        when(characterRepository.findById(1L)).thenReturn(Optional.of(newChar(1L, "甲", "在茶馆喝茶", "茶馆")));
        ActorInterruptRecord existing = newRecord(9L, "在集市采购", "集市", 36000L, 39600L, "active");
        when(interruptRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(eq(1L), eq("npc:1"), eq("active")))
                .thenReturn(List.of(existing));
        Long planId = service.interrupt(1L, "npc:1", 37000L);
        assertNull(planId, "已有未过期 active 不覆盖（返回其 planId=null）");
        verify(interruptRepository, never()).save(any(ActorInterruptRecord.class));
    }

    @Test
    void interrupt_discardsExpiredActiveAndCreatesNew() {
        when(characterRepository.findById(1L)).thenReturn(Optional.of(newChar(1L, "甲", "在茶馆喝茶", "茶馆")));
        ActorInterruptRecord existing = newRecord(9L, "在集市采购", "集市", 10000L, 13600L, "active");
        when(interruptRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(eq(1L), eq("npc:1"), eq("active")))
                .thenReturn(List.of(existing));
        service.interrupt(1L, "npc:1", 20000L);
        // 旧记录标记 discarded，新记录创建
        verify(interruptRepository).save(Mockito.argThat(rec -> rec.getId() != null
                && "discarded".equals(rec.getStatus())));
        verify(interruptRepository).save(Mockito.argThat(rec -> rec.getId() == null
                && "active".equals(rec.getStatus())
                && "在茶馆喝茶".equals(rec.getInterruptedActivity())));
    }

    @Test
    void interrupt_crowdIgnored() {
        when(characterRepository.findById(1L)).thenReturn(Optional.of(commonChar(1L, "甲", "劳作", "田间")));
        Long planId = service.interrupt(1L, "npc:1", 36000L);
        assertNull(planId, "人群不打断（走分层概率化）");
        verify(interruptRepository, never()).save(any(ActorInterruptRecord.class));
    }

    @Test
    void interrupt_disabledSkips() {
        props.setInterruptEnabled(false);
        when(characterRepository.findById(1L)).thenReturn(Optional.of(newChar(1L, "甲", "在茶馆喝茶", "茶馆")));
        Long planId = service.interrupt(1L, "npc:1", 36000L);
        assertNull(planId);
        verify(interruptRepository, never()).save(any(ActorInterruptRecord.class));
    }

    // ==================== AI 恢复决策三态执行（maybeResume） ====================

    @Test
    void maybeResume_noActiveRecordNoop() {
        when(interruptRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(eq(1L), eq("npc:1"), eq("active")))
                .thenReturn(List.of());
        service.maybeResume(1L, "npc:1", 40000L, "粮仓失火");
        verify(aiChatGateway, never()).chatCompletion(any(), any(), any(), any());
        verify(planRepository, never()).save(any(ActorActionPlan.class));
    }

    @Test
    void maybeResume_aiResumeExecutesOriginalActivity() {
        ActorInterruptRecord rec = newRecord(9L, "在茶馆喝茶", "茶馆", 36000L, 39600L, "active");
        when(interruptRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(eq(1L), eq("npc:1"), eq("active")))
                .thenReturn(List.of(rec));
        when(characterRepository.findById(1L)).thenReturn(Optional.of(newChar(1L, "甲", "救火中", "粮仓")));
        mockAiResume("{\"decision\":\"resume\",\"action\":\"在茶馆喝茶\",\"target\":\"茶馆\",\"reason\":\"先把茶喝完\"}");

        service.maybeResume(1L, "npc:1", 38000L, "粮仓失火");

        // 记录标记 restored
        verify(interruptRepository).save(Mockito.argThat(r -> "restored".equals(r.getStatus())
                && Long.valueOf(38000L).equals(r.getConsumedAtGameSecond())));
        // 三态执行：新建计划（回原地点继续原活动）+ 真执行
        assertEquals(1, savedPlans.size());
        ActorActionPlan plan = savedPlans.get(0);
        assertEquals("stimulus_resume", plan.getTriggerType());
        assertTrue(plan.getActionJson().contains("在茶馆喝茶"));
        assertTrue(plan.getActionJson().contains("茶馆"));
        verify(agentActionService).execute(any(ActorActionPlan.class));
    }

    @Test
    void maybeResume_aiAdjustExecutesNewTask() {
        ActorInterruptRecord rec = newRecord(9L, "在茶馆喝茶", "茶馆", 36000L, 39600L, "active");
        when(interruptRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(eq(1L), eq("npc:1"), eq("active")))
                .thenReturn(List.of(rec));
        when(characterRepository.findById(1L)).thenReturn(Optional.of(newChar(1L, "甲", "救火中", "粮仓")));
        mockAiResume("{\"decision\":\"adjust\",\"action\":\"前往医馆帮忙包扎伤员\",\"target\":\"医馆\",\"reason\":\"救人要紧\"}");

        service.maybeResume(1L, "npc:1", 38000L, "粮仓失火");

        verify(interruptRepository).save(Mockito.argThat(r -> "adjusted".equals(r.getStatus())));
        assertEquals(1, savedPlans.size());
        ActorActionPlan plan = savedPlans.get(0);
        assertEquals("stimulus_resume", plan.getTriggerType());
        assertTrue(plan.getActionJson().contains("前往医馆帮忙包扎伤员"));
        assertTrue(plan.getActionJson().contains("医馆"));
        verify(agentActionService).execute(any(ActorActionPlan.class));
    }

    @Test
    void maybeResume_aiAbandonMarksDiscardedNoPlan() {
        ActorInterruptRecord rec = newRecord(9L, "在茶馆喝茶", "茶馆", 36000L, 39600L, "active");
        when(interruptRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(eq(1L), eq("npc:1"), eq("active")))
                .thenReturn(List.of(rec));
        when(characterRepository.findById(1L)).thenReturn(Optional.of(newChar(1L, "甲", "救火中", "粮仓")));
        mockAiResume("{\"decision\":\"abandon\",\"reason\":\"茶凉了不喝了\"}");

        service.maybeResume(1L, "npc:1", 38000L, "粮仓失火");

        verify(interruptRepository).save(Mockito.argThat(r -> "discarded".equals(r.getStatus())));
        assertEquals(0, savedPlans.size(), "abandon 不产生新计划");
        verify(agentActionService, never()).execute(any(ActorActionPlan.class));
    }

    @Test
    void maybeResume_aiUnavailableFallbackResumeWhenNotExpired() {
        ActorInterruptRecord rec = newRecord(9L, "在茶馆喝茶", "茶馆", 36000L, 39600L, "active");
        when(interruptRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(eq(1L), eq("npc:1"), eq("active")))
                .thenReturn(List.of(rec));
        when(characterRepository.findById(1L)).thenReturn(Optional.of(newChar(1L, "甲", "救火中", "粮仓")));
        // AI 预算不可用 → 共性框架兜底
        when(budgetGuard.tryConsume(any())).thenReturn(false);

        service.maybeResume(1L, "npc:1", 38000L, "粮仓失火");

        verify(interruptRepository).save(Mockito.argThat(r -> "restored".equals(r.getStatus())));
        assertEquals(1, savedPlans.size(), "共性框架兜底：未过期且任务合理 → resume");
        assertTrue(savedPlans.get(0).getActionJson().contains("在茶馆喝茶"));
    }

    @Test
    void maybeResume_aiUnavailableFallbackAbandonWhenExpired() {
        ActorInterruptRecord rec = newRecord(9L, "在茶馆喝茶", "茶馆", 10000L, 13600L, "active");
        when(interruptRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(eq(1L), eq("npc:1"), eq("active")))
                .thenReturn(List.of(rec));
        when(characterRepository.findById(1L)).thenReturn(Optional.of(newChar(1L, "甲", "救火中", "粮仓")));
        when(budgetGuard.tryConsume(any())).thenReturn(false);

        service.maybeResume(1L, "npc:1", 20000L, "粮仓失火");

        verify(interruptRepository).save(Mockito.argThat(r -> "discarded".equals(r.getStatus())));
        assertEquals(0, savedPlans.size(), "共性框架兜底：已过期 → abandon");
    }

    @Test
    void maybeResume_crowdIgnored() {
        service.maybeResume(1L, "npc:1", 38000L, "粮仓失火");
        verify(aiChatGateway, never()).chatCompletion(any(), any(), any(), any());
    }

    // ==================== 共性框架兜底（P0-P3 静态逻辑） ====================

    @Test
    void fallbackResumeDecision_notExpiredResumes() {
        ActorInterruptRecord rec = newRecord(9L, "在茶馆喝茶", "茶馆", 36000L, 39600L, "active");
        ResumeDecisionParse.ResumeDecision d = InterruptRecoveryService.fallbackResumeDecision(rec, 37000L);
        assertNotNull(d);
        assertEquals("resume", d.decision());
        assertEquals("在茶馆喝茶", d.action());
        assertEquals("茶馆", d.target());
    }

    @Test
    void fallbackResumeDecision_expiredAbandons() {
        ActorInterruptRecord rec = newRecord(9L, "在茶馆喝茶", "茶馆", 36000L, 39600L, "active");
        ResumeDecisionParse.ResumeDecision d = InterruptRecoveryService.fallbackResumeDecision(rec, 39600L);
        assertEquals("abandon", d.decision(), "过期（now >= expireAt）→ abandon");
    }

    @Test
    void fallbackResumeDecision_blankActivityAbandons() {
        ActorInterruptRecord rec = newRecord(9L, "", "茶馆", 36000L, 39600L, "active");
        ResumeDecisionParse.ResumeDecision d = InterruptRecoveryService.fallbackResumeDecision(rec, 37000L);
        assertEquals("abandon", d.decision());
    }

    @Test
    void expired_boundaryRules() {
        ActorInterruptRecord rec = newRecord(9L, "任务", "地点", 36000L, 39600L, "active");
        assertFalse(InterruptRecoveryService.expired(rec, 39599L), "未到过期时刻");
        assertTrue(InterruptRecoveryService.expired(rec, 39600L), "到达过期时刻即过期");
        assertFalse(InterruptRecoveryService.expired(null, 39600L), "空记录视为未过期");
        ActorInterruptRecord noExpire = newRecord(9L, "任务", "地点", 36000L, null, "active");
        assertFalse(InterruptRecoveryService.expired(noExpire, 99999L), "无过期时刻视为未过期");
    }

    // ==================== 恢复决策解析器（ResumeDecisionParse） ====================

    @Test
    void resumeDecisionParse_validDecisions() {
        ResumeDecisionParse.ResumeDecision r = ResumeDecisionParse.parse(
                "{\"decision\":\"resume\",\"action\":\"在茶馆喝茶\",\"target\":\"茶馆\",\"reason\":\"继续\"}");
        assertNotNull(r);
        assertEquals("resume", r.decision());
        assertEquals("茶馆", r.target());
        ResumeDecisionParse.ResumeDecision a = ResumeDecisionParse.parse(
                "{\"decision\":\"ADJUST\",\"action\":\"去医馆\",\"target\":\"医馆\"}");
        assertEquals("adjust", a.decision(), "大小写归一化");
        ResumeDecisionParse.ResumeDecision b = ResumeDecisionParse.parse(
                "{\"decision\":\"abandon\",\"reason\":\"算了\"}");
        assertEquals("abandon", b.decision());
    }

    @Test
    void resumeDecisionParse_invalidReturnsNull() {
        assertNull(ResumeDecisionParse.parse(null));
        assertNull(ResumeDecisionParse.parse("不是 JSON"));
        assertNull(ResumeDecisionParse.parse("{\"decision\":\"maybe\",\"action\":\"x\"}"), "非法三态 → null");
        assertNull(ResumeDecisionParse.parse("{\"action\":\"x\"}"), "缺 decision → null");
    }

    // ==================== M3 打断前 AI 探针（probeInterrupt） ====================

    @Test
    void probeInterrupt_aiSaysInterruptReturnsTrue() {
        when(characterRepository.findById(1L)).thenReturn(Optional.of(newChar(1L, "甲", "在茶馆喝茶", "茶馆")));
        mockAiResume("{\"interrupt\":true,\"reason\":\"火情要紧\"}");
        assertEquals(Boolean.TRUE, service.probeInterrupt(1L, "npc:1", 36000L, "粮仓失火", "我是甲，稳重"));
        verify(promptService).buildInterruptProbePrompt(eq(1L), eq(1L), eq("我是甲，稳重"), eq("在茶馆喝茶"), eq("粮仓失火"));
    }

    @Test
    void probeInterrupt_aiSaysContinueReturnsFalse() {
        when(characterRepository.findById(1L)).thenReturn(Optional.of(newChar(1L, "甲", "在茶馆喝茶", "茶馆")));
        mockAiResume("{\"interrupt\":false,\"reason\":\"与我无关\"}");
        assertEquals(Boolean.FALSE, service.probeInterrupt(1L, "npc:1", 36000L, "集市闲谈", null));
    }

    @Test
    void probeInterrupt_budgetUnavailableReturnsNull() {
        when(characterRepository.findById(1L)).thenReturn(Optional.of(newChar(1L, "甲", "在茶馆喝茶", "茶馆")));
        when(budgetGuard.tryConsume(any())).thenReturn(false);
        assertNull(service.probeInterrupt(1L, "npc:1", 36000L, "粮仓失火", null),
                "预算门控：tryConsume false → null（上层兜底默认打断）");
        verify(aiChatGateway, never()).chatCompletion(any(), any(), any(), any());
    }

    @Test
    void probeInterrupt_parseFailureReturnsNull() {
        when(characterRepository.findById(1L)).thenReturn(Optional.of(newChar(1L, "甲", "在茶馆喝茶", "茶馆")));
        mockAiResume("{\"interrupt\":\"maybe\"}"); // 非布尔 → 解析 null
        assertNull(service.probeInterrupt(1L, "npc:1", 36000L, "粮仓失火", null));
    }

    @Test
    void probeInterrupt_aiThrowReturnsNull() {
        when(characterRepository.findById(1L)).thenReturn(Optional.of(newChar(1L, "甲", "在茶馆喝茶", "茶馆")));
        when(aiChatGateway.chatCompletion(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("AI 宕机"));
        assertNull(service.probeInterrupt(1L, "npc:1", 36000L, "粮仓失火", null));
    }

    @Test
    void probeInterrupt_crowdOrDisabledReturnsNull() {
        when(characterRepository.findById(1L)).thenReturn(Optional.of(commonChar(1L, "甲", "劳作", "田间")));
        assertNull(service.probeInterrupt(1L, "npc:1", 36000L, "粮仓失火", null), "人群不探针");
        props.setInterruptProbeEnabled(false);
        assertNull(service.probeInterrupt(1L, "npc:1", 36000L, "粮仓失火", null), "开关关闭不探针");
        verify(aiChatGateway, never()).chatCompletion(any(), any(), any(), any());
    }

    @Test
    void interruptProbeParse_toleratesMarkdownAndNull() {
        assertTrue(InterruptProbeParse.parse("```json\n{\"interrupt\":true,\"reason\":\"救人\"}\n```").interrupt(),
                "Markdown 代码块容错");
        assertFalse(InterruptProbeParse.parse("{\"interrupt\":false}").interrupt(), "缺 reason 也可解析");
        assertNull(InterruptProbeParse.parse(null));
        assertNull(InterruptProbeParse.parse("不是 JSON"));
        assertNull(InterruptProbeParse.parse("{\"interrupt\":\"maybe\"}"), "非布尔 → null");
        assertNull(InterruptProbeParse.parse("{\"reason\":\"缺 interrupt\"}"), "缺 interrupt → null");
    }
}
