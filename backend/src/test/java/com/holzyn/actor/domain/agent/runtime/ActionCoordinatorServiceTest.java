package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.runtime.entity.ActorResourceOccupancy;
import com.holzyn.actor.domain.agent.runtime.repository.ActorConflictRecordRepository;
import com.holzyn.actor.domain.agent.runtime.repository.ActorResourceOccupancyRepository;
import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.npc.goal.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 意图冲突检测与协商服务单元测试（O2 决策与协调 P1-3，domain/agent/runtime 同包）。
 * <p>职责：验证——资源占用冲突/进行中计划占用/目标角色正忙三路检测、优先级排序
 * （高者强占）、协商（预算内 AI，失败回退确定性等待）、确定性兜底（等待+waitMinutes+防重入标记）、
 * 无冲突登记占用、配置关闭跳过。</p>
 * <p>设计依据：现状优化轨道 §7.2 P1-3（用户决策：混合=既有判定+资源登记）。</p>
 */
class ActionCoordinatorServiceTest {

    private ActorResourceOccupancyRepository occupancyRepository;
    private ActorConflictRecordRepository conflictRepository;
    private ActorActionPlanRepository planRepository;
    private ActorCharacterRepository characterRepository;
    private AiSpringChatGateway aiChatGateway;
    private PromptTemplateService promptTemplateService;
    private BudgetGuard budgetGuard;
    private RealtimeDecisionProperties props;
    private ActionCoordinatorService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        occupancyRepository = Mockito.mock(ActorResourceOccupancyRepository.class);
        conflictRepository = Mockito.mock(ActorConflictRecordRepository.class);
        planRepository = Mockito.mock(ActorActionPlanRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        aiChatGateway = Mockito.mock(AiSpringChatGateway.class);
        promptTemplateService = Mockito.mock(PromptTemplateService.class);
        UsageLogService usageLogService = Mockito.mock(UsageLogService.class);
        budgetGuard = Mockito.mock(BudgetGuard.class);
        CurrentUserProvider currentUserProvider = Mockito.mock(CurrentUserProvider.class);
        props = new RealtimeDecisionProperties();
        service = new ActionCoordinatorService(occupancyRepository, conflictRepository, planRepository,
                characterRepository, props, aiChatGateway, promptTemplateService, usageLogService,
                budgetGuard, currentUserProvider, mapper);
        when(currentUserProvider.currentUserId()).thenReturn(1L);
    }

    // ==================== 构造辅助 ====================

    private ObjectNode decision(String action, String target, int urgency) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "trade");
        node.put("action", action);
        node.put("target", target);
        node.put("reason", "理由");
        node.put("urgency", urgency);
        node.put("duration", 30);
        return node;
    }

    private ActorCharacter character(long id, String name, int importance, String activity) {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(id);
        ch.setName(name);
        ch.setImportance(importance);
        ch.setCurrentActivity(activity);
        ch.setProjectId(100L);
        return ch;
    }

    private ActorResourceOccupancy occupancy(String ref, String name, String key, long until) {
        ActorResourceOccupancy occ = new ActorResourceOccupancy();
        occ.setId(1L);
        occ.setProjectId(100L);
        occ.setResourceKey(key);
        occ.setResourceName(key);
        occ.setNpcRef(ref);
        occ.setNpcName(name);
        occ.setUntilGameSecond(until);
        occ.setStatus("active");
        return occ;
    }

    private void noOccupancy() {
        when(occupancyRepository.findByProjectIdAndResourceKeyAndStatusOrderByIdDesc(anyLong(), anyString(), anyString()))
                .thenReturn(List.of());
        when(occupancyRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(anyLong(), anyString(), anyString()))
                .thenReturn(List.of());
    }

    // ==================== 无冲突：登记占用 + 原样放行 ====================

    /** 无冲突且目标为地点性资源 → 登记占用 + 不调整。 */
    @Test
    void noConflictRegistersOccupancy() {
        noOccupancy();
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(100L, 0)).thenReturn(List.of());
        when(characterRepository.findById(3L)).thenReturn(java.util.Optional.of(character(3L, "沈砚秋", 4, null)));

        var adj = service.resolveConflict(100L, "special:3", "沈砚秋",
                decision("去集市摊位买药", "集市摊位", 4), 1000L, false);

        assertFalse(adj.changed());
        verify(occupancyRepository).save(any());
    }

    /** 无明确目标 → 不检测不登记。 */
    @Test
    void blankTargetUnchanged() {
        noOccupancy();
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(100L, 0)).thenReturn(List.of());
        var adj = service.resolveConflict(100L, "special:3", "沈砚秋",
                decision("原地休息", "", 2), 1000L, false);
        assertFalse(adj.changed());
        verify(occupancyRepository, never()).save(any());
    }

    /** 协调标记（_coordinated=true）→ 不再二次检测（防递归）。 */
    @Test
    void coordinatedMarkerSkipsDetection() {
        ObjectNode node = decision("见摊位有人用，稍候再去", "集市摊位", 2);
        node.put(ActionCoordinatorService.COORDINATED_MARKER, true);
        var adj = service.resolveConflict(100L, "special:3", "沈砚秋", node, 1000L, false);
        assertFalse(adj.changed());
        verify(occupancyRepository, never()).save(any());
    }

    // ==================== 冲突检测三路 ====================

    /** 资源占用冲突 + 我方优先级低 → 确定性兜底等待（含 waitMinutes + 防重入标记）。 */
    @Test
    void resourceConflictFallsBackToWait() {
        when(occupancyRepository.findByProjectIdAndResourceKeyAndStatusOrderByIdDesc(100L, "集市摊位", "active"))
                .thenReturn(List.of(occupancy("special:5", "陆沉舟", "集市摊位", 9000L)));
        when(occupancyRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(anyLong(), anyString(), anyString()))
                .thenReturn(List.of());
        // 对手陆沉舟重要度 5 × 3 = 15；我方沈砚秋 4 × 1(urgency=1) = 4 → 我方低 → 等待
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(100L, 0)).thenReturn(List.of());
        when(characterRepository.findById(3L)).thenReturn(java.util.Optional.of(character(3L, "沈砚秋", 4, null)));
        when(characterRepository.findById(5L)).thenReturn(java.util.Optional.of(character(5L, "陆沉舟", 5, null)));

        var adj = service.resolveConflict(100L, "special:3", "沈砚秋",
                decision("去集市摊位买药", "集市摊位", 1), 1000L, false);

        assertTrue(adj.changed());
        assertNotNull(adj.decision());
        assertEquals("rest", adj.decision().get("type"));
        assertTrue(String.valueOf(adj.decision().get("action")).contains("稍候"));
        assertTrue(adj.decision().containsKey("waitMinutes"));
        assertTrue(Boolean.TRUE.equals(adj.decision().get(ActionCoordinatorService.COORDINATED_MARKER)));
        // 冲突记录落库
        verify(conflictRepository).save(any());
    }

    /** 资源占用冲突 + 我方优先级高 → 强占（释放对方占用 + 登记自己）+ 不调整。 */
    @Test
    void resourceConflictTakeoverWhenHigherPriority() {
        ActorResourceOccupancy occ = occupancy("special:5", "陆沉舟", "集市摊位", 9000L);
        occ.setId(7L);
        when(occupancyRepository.findByProjectIdAndResourceKeyAndStatusOrderByIdDesc(100L, "集市摊位", "active"))
                .thenReturn(List.of(occ));
        when(occupancyRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(anyLong(), anyString(), anyString()))
                .thenReturn(List.of());
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(100L, 0)).thenReturn(List.of());
        when(characterRepository.findById(3L)).thenReturn(java.util.Optional.of(character(3L, "沈砚秋", 4, null)));
        when(characterRepository.findById(5L)).thenReturn(java.util.Optional.of(character(5L, "陆沉舟", 5, null)));

        var adj = service.resolveConflict(100L, "special:3", "沈砚秋",
                decision("去集市摊位买药", "集市摊位", 5), 1000L, false);

        assertFalse(adj.changed());
        // 对方占用释放 + 自己占用登记
        assertEquals("released", occ.getStatus());
        verify(occupancyRepository, Mockito.atLeastOnce()).save(any());
        verify(conflictRepository).save(any());
    }

    /** 既有判定：其他 NPC 进行中计划目标同地点 → 冲突。 */
    @Test
    void planOccupancyDetected() {
        noOccupancy();
        ActorCharacter other = character(5L, "陆沉舟", 5, null);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(100L, 0)).thenReturn(List.of(other));
        ActorActionPlan plan = new ActorActionPlan();
        plan.setId(11L);
        plan.setCharacterId(5L);
        plan.setStatus("executing");
        plan.setActionJson("{\"type\":\"trade\",\"action\":\"摆摊卖货\",\"target\":\"集市摊位\",\"urgency\":3}");
        when(planRepository.findByCharacterIdAndStatusInOrderByIdDesc(5L, List.of("planned", "executing")))
                .thenReturn(List.of(plan));
        when(characterRepository.findById(3L)).thenReturn(java.util.Optional.of(character(3L, "沈砚秋", 4, null)));
        when(characterRepository.findById(5L)).thenReturn(java.util.Optional.of(character(5L, "陆沉舟", 5, null)));

        var adj = service.resolveConflict(100L, "special:3", "沈砚秋",
                decision("去集市摊位买药", "集市摊位", 1), 1000L, false);

        assertTrue(adj.changed()); // 我方 4×1=4 < 对方 5×3=15 → 等待
    }

    /** 既有判定：目标角色正忙（target 命中角色名且其有当前活动）→ 冲突。 */
    @Test
    void targetCharacterBusyDetected() {
        noOccupancy();
        ActorCharacter busy = character(7L, "温叙", 4, "正在给病人诊脉");
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(100L, 0)).thenReturn(List.of(busy));
        when(characterRepository.findById(3L)).thenReturn(java.util.Optional.of(character(3L, "沈砚秋", 4, null)));
        when(characterRepository.findById(7L)).thenReturn(java.util.Optional.of(busy));

        var adj = service.resolveConflict(100L, "special:3", "沈砚秋",
                decision("去医馆找温叙请教药方", "温叙", 2), 1000L, false);

        assertTrue(adj.changed());
    }

    // ==================== AI 协商（预算内） ====================

    /** 协商启用+预算通过+AI 输出 wait → 调整后决策带 waitMinutes；失败回退确定性等待。 */
    @Test
    void negotiateAiWait() {
        when(occupancyRepository.findByProjectIdAndResourceKeyAndStatusOrderByIdDesc(100L, "集市摊位", "active"))
                .thenReturn(List.of(occupancy("special:5", "陆沉舟", "集市摊位", 9000L)));
        when(occupancyRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(anyLong(), anyString(), anyString()))
                .thenReturn(List.of());
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(100L, 0)).thenReturn(List.of());
        when(characterRepository.findById(3L)).thenReturn(java.util.Optional.of(character(3L, "沈砚秋", 4, null)));
        when(characterRepository.findById(5L)).thenReturn(java.util.Optional.of(character(5L, "陆沉舟", 5, null)));
        when(budgetGuard.tryConsume(100L)).thenReturn(true);
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("协商提示");
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any())).thenReturn(
                new AiChatResult("{\"decision\":\"wait\",\"action\":\"在摊位附近稍候片刻\",\"target\":\"集市摊位\",\"reason\":\"对方先用\"}",
                        "qwen", 100, 50, 150, 0, 0, 0L));

        var adj = service.resolveConflict(100L, "special:3", "沈砚秋",
                decision("去集市摊位买药", "集市摊位", 1), 1000L, true);

        assertTrue(adj.changed());
        assertEquals("rest", adj.decision().get("type"));
        assertTrue(adj.decision().containsKey("waitMinutes"));
        verify(aiChatGateway).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    /** 协商 AI 失败/非法输出 → 回退确定性等待（不抛错）。 */
    @Test
    void negotiateFailureFallsBackToDeterministic() {
        when(occupancyRepository.findByProjectIdAndResourceKeyAndStatusOrderByIdDesc(100L, "集市摊位", "active"))
                .thenReturn(List.of(occupancy("special:5", "陆沉舟", "集市摊位", 9000L)));
        when(occupancyRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(anyLong(), anyString(), anyString()))
                .thenReturn(List.of());
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(100L, 0)).thenReturn(List.of());
        when(characterRepository.findById(3L)).thenReturn(java.util.Optional.of(character(3L, "沈砚秋", 4, null)));
        when(characterRepository.findById(5L)).thenReturn(java.util.Optional.of(character(5L, "陆沉舟", 5, null)));
        when(budgetGuard.tryConsume(100L)).thenReturn(true);
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("提示");
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any())).thenThrow(new RuntimeException("AI 挂了"));

        var adj = service.resolveConflict(100L, "special:3", "沈砚秋",
                decision("去集市摊位买药", "集市摊位", 1), 1000L, true);

        assertTrue(adj.changed());
        assertTrue(String.valueOf(adj.decision().get("reason")).contains("确定性兜底"));
    }

    // ==================== 配置门控 ====================

    /** 冲突检测总开关关闭 → 不检测不登记不落冲突记录。 */
    @Test
    void disabledSkipsAll() {
        props.getCoordination().setConflictDetectEnabled(false);
        noOccupancy();
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(100L, 0)).thenReturn(List.of());
        var adj = service.resolveConflict(100L, "special:3", "沈砚秋",
                decision("去集市摊位买药", "集市摊位", 4), 1000L, false);
        assertFalse(adj.changed());
        verify(occupancyRepository, never()).save(any());
        verify(conflictRepository, never()).save(any());
    }

    /** 资源登记开关关闭 → 无冲突也不登记（仅检测）。 */
    @Test
    void occupancyDisabledStillDetects() {
        props.getCoordination().setOccupancyEnabled(false);
        noOccupancy();
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(100L, 0)).thenReturn(List.of());
        var adj = service.resolveConflict(100L, "special:3", "沈砚秋",
                decision("去集市摊位买药", "集市摊位", 4), 1000L, false);
        assertFalse(adj.changed());
        verify(occupancyRepository, never()).save(any());
    }

    // ==================== 资源键归一化（静态） ====================

    @Test
    void resourceKeyNormalizes() {
        assertEquals("集市摊位", ActionCoordinatorService.resourceKey(" 集市摊位 "));
        assertEquals("market", ActionCoordinatorService.resourceKey("MARKET"));
    }
}
