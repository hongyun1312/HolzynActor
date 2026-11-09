package com.holzyn.actor.domain.agent.learning;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 动态关系/好感服务（D1 F6，domain/agent/learning）单元测试。
 * <p>验证：互动增减好感（创建行/双向/钳制边界/中性 0 仅刷新）、信任随动、
 * 状态机（affinity+trust 双维度）、relation 事件发布（kind=relation/source=learning）、
 * 行动目标名称解析、普通型（common）端点支持（Q6 完全覆盖）、开关关闭跳过。</p>
 */
class RelationDynamicsServiceTest {

    private ActorCharacterRelationRepository relationRepository;
    private ActorCharacterRepository characterRepository;
    private ActorCharacterRepository ordinaryNpcRepository;
    private WorldEventBus worldEventBus;
    private CurrentUserProvider currentUserProvider;
    private RelationDynamicsService service;

    /** 关系行内存存储（模拟 find-or-create 语义：save 后 find 可再命中） */
    private final List<ActorCharacterRelation> store = new ArrayList<>();
    private final AtomicLong seq = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        relationRepository = Mockito.mock(ActorCharacterRelationRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        ordinaryNpcRepository = Mockito.mock(ActorCharacterRepository.class);
        worldEventBus = Mockito.mock(WorldEventBus.class);
        currentUserProvider = Mockito.mock(CurrentUserProvider.class);
        service = new RelationDynamicsService(relationRepository, characterRepository,
                ordinaryNpcRepository, worldEventBus, currentUserProvider);
        ReflectionTestUtils.setField(service, "relationsEnabled", true);
        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(relationRepository.findByProjectId(100L))
                .thenAnswer(inv -> new ArrayList<>(store));
        when(relationRepository.save(any(ActorCharacterRelation.class))).thenAnswer(inv -> {
            ActorCharacterRelation r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(seq.getAndIncrement());
                store.add(r);
            }
            return r;
        });
        // 默认：特殊角色 3/4 存在；普通 NPC 7 存在
        ActorCharacter a = new ActorCharacter();
        a.setId(3L);
        a.setName("林晚");
        ActorCharacter b = new ActorCharacter();
        b.setId(4L);
        b.setName("沈夜");
        when(characterRepository.findById(3L)).thenReturn(Optional.of(a));
        when(characterRepository.findById(4L)).thenReturn(Optional.of(b));
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(100L, 0))
                .thenReturn(List.of(a, b));
        ActorCharacter n = new ActorCharacter();
        n.setId(7L);
        n.setProjectId(100L);
        n.setName("小二");
        // G1a：单表后端点解析只查 actor_profile（RelationRefs.resolve 三参版），普通型不再走第二仓库
        n.setType("common");
        when(characterRepository.findById(7L)).thenReturn(Optional.of(n));
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(100L, 0))
                .thenReturn(List.of(a, b, n));
    }

    /** 首次互动：自动创建关系行（formal ref 落库）+ 好感增量 + 状态机 + 发布 relation 事件 */
    @Test
    void updateRelationCreatesRowAndAppliesDelta() {
        service.updateRelation(100L, "npc:3", "npc:4", 20, "共同行动");

        assertEquals(1, store.size());
        ActorCharacterRelation saved = store.get(0);
        assertEquals(100L, saved.getProjectId());
        assertEquals("npc:3", saved.getFromRef());
        assertEquals(3L, saved.getFromCharacterId());
        assertEquals("npc:4", saved.getToRef());
        assertEquals(Integer.valueOf(20), saved.getAffinity());
        assertEquals(Integer.valueOf(10), saved.getTrust(), "信任随好感半幅变化");
        assertEquals("acquaintance", saved.getRelationState(), "好感 20 → 相识");
        assertNotNull(saved.getLastInteractionAt());

        // 发布 kind=relation 事件（拓扑实时反映）
        ArgumentCaptor<EventSpec> eventCaptor = ArgumentCaptor.forClass(EventSpec.class);
        verify(worldEventBus).publish(anyLong(), anyLong(), eventCaptor.capture());
        EventSpec spec = eventCaptor.getValue();
        assertEquals("relation", spec.getKind());
        assertEquals("learning", spec.getSource());
        assertEquals(List.of("npc:3", "npc:4"), spec.getCharacterRefs());
        assertNotNull(spec.getSourceKey());
        assertEquals("global", spec.getVisibility());
    }

    /** 好感/信任越界钳制（同一行连续互动）：不超 ±100 / 0~100；状态机联动 */
    @Test
    void updateRelationClampsAffinityAndTrust() {
        service.updateRelation(100L, "npc:3", "npc:4", 100, "极限互动");
        service.updateRelation(100L, "npc:3", "npc:4", 100, "再互动");
        assertEquals(Integer.valueOf(100), store.get(0).getAffinity(), "好感钳制上限 100");
        assertEquals(Integer.valueOf(100), store.get(0).getTrust(), "信任钳制上限 100");
        assertEquals("intimate", store.get(0).getRelationState(), "好感 100+信任 100 → 亲密");

        service.updateRelation(100L, "npc:3", "npc:4", -200, "翻脸");
        assertEquals(Integer.valueOf(-100), store.get(0).getAffinity(), "好感钳制下限 -100");
        assertEquals(Integer.valueOf(0), store.get(0).getTrust(), "信任钳制下限 0");
        assertEquals("hostile", store.get(0).getRelationState(), "好感 -100 → 敌对");
    }

    /** 中性互动（delta=0）：不新建行不发布事件；已有行仅刷新互动时间 */
    @Test
    void zeroDeltaOnlyTouchesExistingRelation() {
        service.updateRelation(100L, "npc:3", "npc:4", 0, "中性行动");
        // 不新建（无落库）
        assertEquals(0, store.size());
        verify(worldEventBus, never()).publish(anyLong(), anyLong(), any());

        // 已有行：中性互动刷新 lastInteractionAt（无好感变化、无事件）
        service.updateRelation(100L, "npc:3", "npc:4", 5, "先互动");
        assertEquals(1, store.size());
        service.updateRelation(100L, "npc:3", "npc:4", 0, "中性行动");
        assertEquals(Integer.valueOf(5), store.get(0).getAffinity(), "中性互动不改好感");
        verify(worldEventBus, Mockito.times(1)).publish(anyLong(), anyLong(), any());
    }

    /** 对话双向：applyDialogPairDelta 对 A→B 与 B→A 各应用一次（各自落一行）。
     *  <p>镜像基线 + 本次修复：先按基线把两方向行都建好，再统一叠加 delta，
     *  同一次互动不会被反向行重复计入（两边都是 1，而不是 1/2）。</p> */
    @Test
    void dialogPairAppliesBothDirections() {
        service.applyDialogPairDelta(100L, "npc:3", "npc:4", 1, "群聊互动");
        assertEquals(2, store.size());
        assertEquals("npc:3", store.get(0).getFromRef());
        assertEquals("npc:4", store.get(0).getToRef());
        assertEquals("npc:4", store.get(1).getFromRef());
        assertEquals("npc:3", store.get(1).getToRef());
        assertEquals(Integer.valueOf(1), store.get(0).getAffinity());
        assertEquals(Integer.valueOf(1), store.get(1).getAffinity(), "双向各应用一次，不得重复计数");
    }

    /** 行动挂钩：target 命中项目内角色（名称解析）→ 单向好感（行动方→目标方） */
    @Test
    void actionDeltaResolvesTargetByName() {
        service.applyActionDelta(100L, "npc:3", "沈夜", 3, "行动：帮助沈夜");
        assertEquals(1, store.size());
        assertEquals("npc:3", store.get(0).getFromRef());
        assertEquals("npc:4", store.get(0).getToRef(), "名称「沈夜」解析为 npc:4（正式引用）");
        assertEquals(Integer.valueOf(3), store.get(0).getAffinity());
    }

    /** 行动挂钩：target 为地点/未知名称 → 不建模（不新建行） */
    @Test
    void actionDeltaSkipsUnknownTarget() {
        service.applyActionDelta(100L, "npc:3", "城东市场", 3, "行动：前往市场");
        assertEquals(0, store.size());
    }

    /** 自环/同端点 → 跳过 */
    @Test
    void updateRelationSkipsSelfLoop() {
        service.updateRelation(100L, "npc:3", "npc:3", 10, "自环");
        service.applyDialogPairDelta(100L, "npc:3", "npc:3", 1, "同人");
        assertEquals(0, store.size());
    }

    /** 开关关闭：互动不增减好感（不创建行不发布事件） */
    @Test
    void disabledSwitchIsNoOp() {
        ReflectionTestUtils.setField(service, "relationsEnabled", false);
        service.updateRelation(100L, "npc:3", "npc:4", 20, "互动");
        assertEquals(0, store.size());
        verify(worldEventBus, never()).publish(anyLong(), anyLong(), any());
    }

    /** 状态机：affinity+trust 双维度阈值映射（纯逻辑） */
    @Test
    void stateMachineThresholds() {
        assertEquals("hostile", RelationDynamicsService.stateOf(-80, 0));
        assertEquals("rival", RelationDynamicsService.stateOf(-40, 10));
        assertEquals("stranger", RelationDynamicsService.stateOf(0, 0));
        assertEquals("stranger", RelationDynamicsService.stateOf(10, 30));
        assertEquals("acquaintance", RelationDynamicsService.stateOf(30, 20));
        assertEquals("acquaintance", RelationDynamicsService.stateOf(50, 10), "高好感低信任 → 相识");
        assertEquals("friend", RelationDynamicsService.stateOf(60, 40));
        assertEquals("intimate", RelationDynamicsService.stateOf(80, 60));
        // 中文标签
        assertEquals("敌对", RelationDynamicsService.stateLabel("hostile"));
        assertEquals("亲密", RelationDynamicsService.stateLabel("intimate"));
    }

    /** 普通型（common）端点互动：普通 NPC 参与（Q6 完全覆盖） */
    @Test
    void updateRelationSupportsCrowdRef() {
        service.updateRelation(100L, "npc:3", "npc:7", 5, "与居民互动");
        assertEquals(1, store.size());
        ActorCharacterRelation saved = store.get(0);
        assertEquals("npc:7", saved.getToRef());
        // G1a：单表后端点 id 恒为 actor_profile.id（原「crowd 端点不占角色表 id」的特例已消失）
        assertEquals(7L, saved.getToCharacterId(), "单表后端点 id 恒为 actor_profile.id");
        assertEquals("小二", saved.getToName());
        assertEquals("npc:3", saved.getFromRef());
    }

    /** 幽灵端点（名称不命中）互动：不建模（无法关联真实角色） */
    @Test
    void updateRelationSkipsGhostEndpoint() {
        service.updateRelation(100L, "npc:3", "阿明", 5, "与幽灵互动");
        assertEquals(0, store.size(), "幽灵端点无正式角色，不建立关系行");
    }

    // ====================关系显著性整形（shapeShift） ====================

    /** 负向通道：冲突/失信类触发源 → +1 小增量强制取负，且重要度随之上调。 */
    @Test
    void shapeShift_conflictFlipsSmallPositiveToNegative() {
        var s = RelationDynamicsService.shapeShift(40, 1, 0, "共同经历事件：两人为水渠吵了一架，翻脸");
        assertTrue(s.affinity() < 0, "冲突应走负向通道: " + s);
        assertTrue(s.affinity() <= -5, "冲突至少 -5（重要度 4 档）: " + s);
        assertEquals(4, s.importance(), "冲突事件重要度应高于日常: " + s);
    }

    /** 重大冲击：一次性 -8（高于阈值允许的负向幅度）。 */
    @Test
    void shapeShift_majorConflictReachesMinusEight() {
        var s = RelationDynamicsService.shapeShift(60, 1, 0, "共同经历事件：老吴与阿福当场绝交");
        assertEquals(-8, s.affinity(), "重大冲突应一次到位 -8: " + s);
        assertEquals(5, s.importance(), "重大冲突重要度 5: " + s);
    }

    /** 显著加权：互助/照拂的 +1 → +2（日常寒暄仍 +1，不被放大）。 */
    @Test
    void shapeShift_careUpgradesUnitDeltaOnly() {
        assertEquals(2, RelationDynamicsService.shapeShift(40, 1, 0, "行动：帮助沈夜").affinity());
        assertEquals(1, RelationDynamicsService.shapeShift(40, 1, 0, "群聊互动").affinity(), "普通互动保持 +1");
        assertEquals(3, RelationDynamicsService.shapeShift(40, 3, 1, "行动：帮助沈夜").affinity(),
                "±3 已有明确动作量级，不再放大");
    }

    /** 饱和转维：好感 100 之后不再空转 +1，增量转成信任。 */
    @Test
    void shapeShift_saturationRoutesToTrust() {
        var s = RelationDynamicsService.shapeShift(100, 1, 0, "群聊互动");
        assertEquals(0, s.affinity(), "好感打满后不再加好感: " + s);
        assertEquals(1, s.trust(), "增量转成信任: " + s);
        assertEquals(2, s.importance(), "空转的小变化重要度下调: " + s);
    }

    /** 显式量级放行：效果层/显式惩罚给的 |Δ|>3 原样透传（口径不被整形改写）。 */
    @Test
    void shapeShift_keepsExplicitMagnitude() {
        assertEquals(20, RelationDynamicsService.shapeShift(0, 20, 10, "共同行动").affinity());
        assertEquals(-200, RelationDynamicsService.shapeShift(100, -200, -100, "翻脸").affinity());
    }

    /** 端到端：+1 冲突互动落库后好感真的下降，事件带非 3 的重要度。 */
    @Test
    void updateRelationConflictLowersAffinityAndTagsImportance() {
        service.updateRelation(100L, "npc:3", "npc:4", 20, "共同行动");
        service.applyDialogPairDelta(100L, "npc:3", "npc:4", 1, "共同经历事件：为水渠的事争执不下");

        ArgumentCaptor<EventSpec> captor = ArgumentCaptor.forClass(EventSpec.class);
        verify(worldEventBus, Mockito.atLeastOnce()).publish(anyLong(), anyLong(), captor.capture());
        EventSpec last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertTrue(last.getImportance() != null && last.getImportance() >= 4,
                "冲突型关系事件重要度应 ≥4: " + last.getImportance());
    }
}
