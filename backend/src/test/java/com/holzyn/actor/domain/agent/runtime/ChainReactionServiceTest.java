package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.sandbox.service.WorldSandboxStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 链式反应服务单元测试（实时行为决策系统 M4，domain/agent/runtime，同包）。
 * <p>职责：验证——① 触发对象解析静态逻辑（同地点特殊 NPC / 关系密切异地相关人物[阈值+上限+降序] /
 * 链式刺激文本）；② onActionExecuted 全流程（开关关跳过 / 一跳防环[标记] / 同地点+关系网触发
 * CHAIN_REACTION / 重大行动人群围观零 AI）。</p>
 */
class ChainReactionServiceTest {

    private RealtimeDecisionProperties props;
    private ActorCharacterRepository characterRepository;
    private ActorCharacterRepository ordinaryNpcRepository;
    private ActorCharacterRelationRepository relationRepository;
    private RealTimeDecisionService realtimeDecisionService;
    private WorldSandboxStateService sandboxStateService;
    private com.holzyn.actor.domain.npc.state.service.NpcActivityStateService npcActivityStateService;
    private ChainReactionService service;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        props = new RealtimeDecisionProperties();
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        ordinaryNpcRepository = Mockito.mock(ActorCharacterRepository.class);
        relationRepository = Mockito.mock(ActorCharacterRelationRepository.class);
        realtimeDecisionService = Mockito.mock(RealTimeDecisionService.class);
        sandboxStateService = Mockito.mock(WorldSandboxStateService.class);
        // G1a：构造器末参为 NpcTypeResolver（npc:{id} 不再编码类型，是否特殊型查 actor_profile.type）
        service = new ChainReactionService(props, characterRepository, ordinaryNpcRepository,
                relationRepository, new NpcTypeResolver(characterRepository));
        java.lang.reflect.Field f = ChainReactionService.class.getDeclaredField("realtimeDecisionService");
        f.setAccessible(true);
        f.set(service, realtimeDecisionService);
        java.lang.reflect.Field f2 = ChainReactionService.class.getDeclaredField("sandboxStateService");
        f2.setAccessible(true);
        f2.set(service, sandboxStateService);
        // G1a：围观只刷新 last_action —— 运行态写回 actor_state（原 ordinaryNpcRepository.saveAll 已失效）
        npcActivityStateService = Mockito.mock(com.holzyn.actor.domain.npc.state.service.NpcActivityStateService.class);
        java.lang.reflect.Field f3 = ChainReactionService.class.getDeclaredField("npcActivityStateService");
        f3.setAccessible(true);
        f3.set(service, npcActivityStateService);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(any(), any())).thenReturn(List.of());
        // G1a：单表后「普通型 NPC」按 type=common 过滤查询
        when(ordinaryNpcRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(any(), any(), any()))
                .thenReturn(List.of());
        when(relationRepository.findByProjectId(any())).thenReturn(List.of());
    }

    private ActorCharacter charOf(Long id, String name, String location) {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(id);
        ch.setProjectId(1L);
        ch.setName(name);
        ch.setLocation(location);
        ch.setDeleted(0);
        return ch;
    }

    private ActorCharacterRelation relation(Long projectId, Long fromId, String fromRef, String fromName,
                                            Long toId, String toRef, String toName, int affinity, int trust) {
        ActorCharacterRelation r = new ActorCharacterRelation();
        r.setProjectId(projectId);
        r.setFromCharacterId(fromId);
        r.setFromRef(fromRef);
        r.setFromName(fromName);
        r.setToCharacterId(toId);
        r.setToRef(toRef);
        r.setToName(toName);
        r.setAffinity(affinity);
        r.setTrust(trust);
        return r;
    }

    // ==================== 静态解析 ====================

    /**
     * 触发对象解析静态逻辑（G1a：统一引用 {@code npc:{id}}）。
     */
    @Test
    void resolveLocationRefs_sameLocationOnlyExcludesSelf() {
        List<ActorCharacter> chars = List.of(
                charOf(1L, "甲", "村口"), charOf(2L, "乙", "村口"), charOf(3L, "丙", "茶馆"));
        List<String> refs = ChainReactionService.resolveLocationRefs(chars, "村口", 1L);
        assertEquals(List.of("npc:2"), refs, "同地点其他特殊 NPC，排除自身与异地点");
    }

    @Test
    void resolveRelatedRefs_thresholdMaxAndOrdering() {
        // G1a：类型不再由引用前缀编码——12 号 NPC 显式 type=common（查 actor_profile.type 判定排除）
        ActorCharacter crowd = new ActorCharacter();
        crowd.setId(12L);
        crowd.setProjectId(1L);
        crowd.setType("common");
        when(characterRepository.findById(12L)).thenReturn(java.util.Optional.of(crowd));
        List<ActorCharacterRelation> relations = List.of(
                relation(1L, 1L, "npc:1", "甲", 2L, "npc:2", "乙", 80, 70),   // 亲密度 80
                relation(1L, 1L, "npc:1", "甲", 3L, "npc:3", "丙", 50, 55),   // 亲密度 55 < 60 排除
                relation(1L, 4L, "npc:4", "丁", 1L, "npc:1", "甲", 90, 20),   // 亲密度 90（反方向）
                relation(1L, 1L, "npc:1", "甲", 12L, "npc:12", "伙计", 90, 90), // 普通型（common）排除
                relation(1L, 1L, "npc:1", "甲", 5L, "npc:5", "戊", 70, 60));  // 亲密度 70
        List<String> refs = service.resolveRelatedRefs(relations, 1L, 1L, 60, 3);
        assertEquals(List.of("npc:4", "npc:2", "npc:5"), refs,
                "按亲密度降序（90>80>70）取前 3，阈值 60 过滤，普通型/自身排除");
    }

    @Test
    void resolveRelatedRefs_maxLimitCaps() {
        List<ActorCharacterRelation> relations = List.of(
                relation(1L, 1L, "npc:1", "甲", 2L, "npc:2", "乙", 90, 90),
                relation(1L, 1L, "npc:1", "甲", 3L, "npc:3", "丙", 80, 80),
                relation(1L, 1L, "npc:1", "甲", 4L, "npc:4", "丁", 70, 70),
                relation(1L, 1L, "npc:1", "甲", 5L, "npc:5", "戊", 60, 60));
        List<String> refs = service.resolveRelatedRefs(relations, 1L, 1L, 60, 2);
        assertEquals(List.of("npc:2", "npc:3"), refs, "上限 2：取亲密度前 2");
    }

    @Test
    void chainStimulusText_format() {
        assertEquals("你看到沈砚秋在村口：召集村民清点粮仓",
                ChainReactionService.chainStimulusText("沈砚秋", "村口", "召集村民清点粮仓"));
        assertEquals("你看到有人：做着一件要紧事",
                ChainReactionService.chainStimulusText(null, null, null), "缺失字段回退");
    }

    // ==================== onActionExecuted 全流程 ====================

    @Test
    void onActionExecuted_disabledSkips() {
        props.setChainReactionEnabled(false);
        service.onActionExecuted(charOf(1L, "甲", "村口"), plan(), decisionJson("{\"type\":\"custom\",\"action\":\"清点粮仓\",\"urgency\":4}"));
        verify(realtimeDecisionService, never()).onChainReaction(any(), any(), any(), any(), any());
    }

    @Test
    void onActionExecuted_chainMarkerSkipsOneHop() {
        // 一跳防环：链式产出的响应行动（_chainReaction 标记）不再传播
        service.onActionExecuted(charOf(1L, "甲", "村口"), plan(),
                decisionJson("{\"type\":\"custom\",\"action\":\"去查看\",\"urgency\":2,\"_chainReaction\":true}"));
        verify(realtimeDecisionService, never()).onChainReaction(any(), any(), any(), any(), any());
    }

    @Test
    void onActionExecuted_triggersSameLocationAndRelatedNetwork() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(charOf(1L, "甲", "村口"), charOf(2L, "乙", "村口"), charOf(3L, "丙", "茶馆")));
        when(relationRepository.findByProjectId(eq(1L)))
                .thenReturn(List.of(relation(1L, 1L, "npc:1", "甲", 3L, "npc:3", "丙", 80, 80)));
        service.onActionExecuted(charOf(1L, "甲", "村口"), plan(),
                decisionJson("{\"type\":\"custom\",\"action\":\"召集村民清点粮仓\",\"urgency\":3}"));
        // 同地点乙 + 关系密切异地丙（好感 80 ≥ 60）
        verify(realtimeDecisionService).onChainReaction(eq(1L), eq("npc:2"),
                org.mockito.ArgumentMatchers.contains("你看到甲在村口"), any(), eq(List.of("甲")));
        verify(realtimeDecisionService).onChainReaction(eq(1L), eq("npc:3"),
                org.mockito.ArgumentMatchers.contains("你看到甲在村口"), any(), eq(List.of("甲")));
    }

    @Test
    void onActionExecuted_noTargetsNoTrigger() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(charOf(1L, "甲", "村口")));
        service.onActionExecuted(charOf(1L, "甲", "村口"), plan(),
                decisionJson("{\"type\":\"custom\",\"action\":\"独自做事\",\"urgency\":2}"));
        verify(realtimeDecisionService, never()).onChainReaction(any(), any(), any(), any(), any());
    }

    @Test
    void onActionExecuted_majorActionCrowdOnlook() {
        // 重大行动（urgency=5 ≥ 阈值 4）→ 同地点人群围观（零 AI，thought_text + SSE）
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(charOf(1L, "甲", "村口")));
        ActorCharacter crowd = new ActorCharacter();
        crowd.setId(12L);
        crowd.setProjectId(1L);
        crowd.setType("common");
        crowd.setLocation("村口");
        when(ordinaryNpcRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(eq(1L), eq("common"), eq(0)))
                .thenReturn(List.of(crowd));
        service.onActionExecuted(charOf(1L, "甲", "村口"), plan(),
                decisionJson("{\"type\":\"custom\",\"action\":\"救火\",\"urgency\":5}"));
        verify(sandboxStateService).publishThought(eq(1L), eq("npc:12"),
                org.mockito.ArgumentMatchers.contains("救火"), eq("m4_crowd"), any());
        // G1a：围观文本落到 actor_state.last_action（批量一次写回，state 传 null 表示不改作息状态）
        verify(npcActivityStateService).setActivities(eq(1L), any());
    }

    @Test
    void onActionExecuted_lowImportanceNoCrowdOnlook() {
        // 低重要度行动（urgency=2 < 阈值 4）→ 人群不围观
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(charOf(1L, "甲", "村口")));
        ActorCharacter crowd = new ActorCharacter();
        crowd.setId(12L);
        crowd.setProjectId(1L);
        crowd.setType("common");
        crowd.setLocation("村口");
        when(ordinaryNpcRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(eq(1L), eq("common"), eq(0)))
                .thenReturn(List.of(crowd));
        service.onActionExecuted(charOf(1L, "甲", "村口"), plan(),
                decisionJson("{\"type\":\"custom\",\"action\":\"日常散步\",\"urgency\":2}"));
        verify(sandboxStateService, never()).publishThought(any(), any(), any(), any(), any());
    }

    // ==================== 私有辅助 ====================

    private ActorActionPlan plan() {
        ActorActionPlan p = new ActorActionPlan();
        p.setId(1L);
        return p;
    }

    private tools.jackson.databind.JsonNode decisionJson(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
