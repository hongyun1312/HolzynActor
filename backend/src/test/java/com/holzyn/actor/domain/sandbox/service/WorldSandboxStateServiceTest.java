package com.holzyn.actor.domain.sandbox.service;

import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxScene;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxState;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxSceneRepository;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 世界沙盘状态服务单元测试（S1，domain/sandbox/service，同包）。
 * <p>职责：验证——地点名确定性派生坐标/朝向（同输入同输出、边界内、y=0）、状态行惰性初始化
 * （特殊 + 普通人群都建、幂等不重复）、move 行动挂钩更新（坐标/朝向/移动状态/动作/游戏时刻落库）、
 * 主场景解析。</p>
 * <p>设计依据：《HolzynActor_世界沙盘_主开发方案_V1.0》§5.3/§10.1 S1。</p>
 */
class WorldSandboxStateServiceTest {

    private ActorSandboxStateRepository stateRepository;
    private ActorCharacterRepository characterRepository;
    private ActorCharacterRepository ordinaryNpcRepository;
    private ActorSandboxSceneRepository sceneRepository;
    private com.holzyn.actor.domain.npc.state.service.NpcActivityStateService npcActivityStateService;
    private WorldSandboxStateService service;

    /** 内存状态行仓库模拟：写库后可读，模拟真实 DB 语义 */
    private final List<ActorSandboxState> stateStore = new ArrayList<>();

    @BeforeEach
    void setUp() {
        stateRepository = Mockito.mock(ActorSandboxStateRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        ordinaryNpcRepository = Mockito.mock(ActorCharacterRepository.class);
        sceneRepository = Mockito.mock(ActorSandboxSceneRepository.class);
        service = new WorldSandboxStateService(stateRepository, characterRepository, ordinaryNpcRepository, sceneRepository);
        // G1a：npc:{id} 不再编码类型、作息运行态迁入 actor_state —— 类型解析器与运行态访问器为字段注入
        org.springframework.test.util.ReflectionTestUtils.setField(service, "npcTypeResolver",
                new com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver(characterRepository));
        npcActivityStateService = Mockito.mock(com.holzyn.actor.domain.npc.state.service.NpcActivityStateService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "npcActivityStateService", npcActivityStateService);

        when(stateRepository.findByProjectIdOrderByIdAsc(anyLong()))
                .thenAnswer(inv -> stateStore.stream().filter(s -> s.getProjectId().equals(inv.getArgument(0))).toList());
        when(stateRepository.findByProjectIdAndNpcRef(anyLong(), any()))
                .thenAnswer(inv -> stateStore.stream()
                        .filter(s -> s.getProjectId().equals(inv.getArgument(0)) && s.getNpcRef().equals(inv.getArgument(1)))
                        .findFirst());
        when(stateRepository.save(any(ActorSandboxState.class))).thenAnswer(inv -> {
            ActorSandboxState st = inv.getArgument(0);
            if (st.getId() == null) {
                st.setId((long) (stateStore.size() + 1));
            }
            stateStore.removeIf(x -> x.getId().equals(st.getId()));
            stateStore.add(st);
            return st;
        });
        // 删除需真实移除内存行（leave 挂钩移除状态行语义）
        Mockito.doAnswer(inv -> {
            stateStore.remove(inv.getArgument(0));
            return null;
        }).when(stateRepository).delete(any(ActorSandboxState.class));
        when(sceneRepository.findFirstByProjectIdOrderByIdAsc(anyLong())).thenReturn(Optional.empty());
    }

    // ==================== 坐标派生（确定性） ====================

    @Test
    void derivePosition_isDeterministic_andWithinBounds() {
        double[] p1 = WorldSandboxStateService.derivePosition("王都酒馆", 500);
        double[] p2 = WorldSandboxStateService.derivePosition("王都酒馆", 500);
        assertEquals(p1[0], p2[0], 0.0001, "同一地点名坐标必须一致（可复现）");
        assertEquals(p1[2], p2[2], 0.0001);
        // 中心化边界内（500 → 场景中心(0,0)，留白 40 → [-210, 210]）
        assertTrue(p1[0] >= -210 && p1[0] < 210, "x 必须在中心化边界内 [-210, 210)");
        assertTrue(p1[2] >= -210 && p1[2] < 210, "z 必须在中心化边界内 [-210, 210)");
        assertEquals(0d, p1[1], "y 前期恒 0");
        // 中心化语义：x + size/2 落在原 [留白, size-留白] 区间
        assertTrue(p1[0] + 250 >= 40 && p1[0] + 250 < 460, "x+size/2 应落在 [40, 460)");
    }

    @Test
    void derivePosition_emptyLocationFallsBackToStation() {
        double[] p1 = WorldSandboxStateService.derivePosition(null, 500);
        double[] p2 = WorldSandboxStateService.derivePosition("", 500);
        double[] p3 = WorldSandboxStateService.derivePosition("驻地", 500);
        assertEquals(p1[0], p2[0], 0.0001);
        assertEquals(p1[0], p3[0], 0.0001, "空地点名回退「驻地」与显式驻地一致");
    }

    @Test
    void derivePosition_sizeScalesSpan() {
        double[] p500 = WorldSandboxStateService.derivePosition("青岩山", 500);
        double[] p1000 = WorldSandboxStateService.derivePosition("青岩山", 1000);
        // 中心化：任意尺寸下归一化位置一致（x+size/2-留白 占 size-2*留白 的比例相同）
        double u500 = (p500[0] + 250 - 40) / (500 - 80);
        double u1000 = (p1000[0] + 500 - 40) / (1000 - 80);
        assertEquals(u500, u1000, 0.0001, "同一地点在任意尺寸下归一化位置一致");
        // 更大场景坐标更远离中心（绝对值更大）
        assertTrue(Math.abs(p1000[0]) >= Math.abs(p500[0]), "更大场景坐标应更远离中心");
    }

    @Test
    void deriveHeading_isDeterministic_andInRange() {
        double h1 = WorldSandboxStateService.deriveHeading("雾都");
        double h2 = WorldSandboxStateService.deriveHeading("雾都");
        assertEquals(h1, h2, 0.0001);
        assertTrue(h1 >= 0 && h1 < Math.PI * 2, "朝向必须在 [0, 2π)");
    }

    @Test
    void normalizeKey_fallsBackToStation() {
        assertEquals("驻地", WorldSandboxStateService.normalizeKey(null));
        assertEquals("驻地", WorldSandboxStateService.normalizeKey("  "));
        assertEquals("王都", WorldSandboxStateService.normalizeKey(" 王都 "));
    }

    // ==================== 状态行惰性初始化 ====================

    @Test
    void syncSpecialStates_createsRowsForAllCharacters() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenAnswer(inv -> List.of(charEntity(10L, 1L, "罗小黑", "青岩村", "在青岩村巡逻")));
        when(ordinaryNpcRepository.findByProjectIdOrderByIdAsc(1L)).thenReturn(List.of());

        service.syncSpecialStates(1L);

        assertEquals(1, stateStore.size(), "特殊 NPC 状态行应被创建");
        ActorSandboxState st = stateStore.get(0);
        assertEquals("special", st.getNpcType());
        assertEquals("npc:10", st.getNpcRef());
        assertEquals("在青岩村巡逻", st.getActionText());
        assertEquals("idle", st.getMoveState(), "惰性初始化默认空闲态（move 行动时才 walking）");
        assertNotNull(st.getPosX());
        assertNotNull(st.getPosZ());
        assertEquals(0d, st.getPosY());
    }

    @Test
    void syncSpecialStates_isIdempotent() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenAnswer(inv -> List.of(charEntity(10L, 1L, "罗小黑", "青岩村", "在青岩村巡逻")));
        when(ordinaryNpcRepository.findByProjectIdOrderByIdAsc(1L)).thenReturn(List.of());

        service.syncSpecialStates(1L);
        service.syncSpecialStates(1L);

        assertEquals(1, stateStore.size(), "重复同步不应重复建行");
    }

    @Test
    void syncCrowdStates_createsRowsForAllNpcs() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(2L), eq(0))).thenReturn(List.of());
        when(ordinaryNpcRepository.findByProjectIdOrderByIdAsc(2L))
                .thenAnswer(inv -> List.of(crowdEntity(5L, 2L, "阿福", "集市", "walk", "正在走向摊位")));

        service.syncCrowdStates(2L, 3600L);

        assertEquals(1, stateStore.size(), "普通人群状态行应被创建");
        ActorSandboxState st = stateStore.get(0);
        // G1a：DB 列 npc_type 写 common（历史 crowd 由 NpcRefs.normalizeType 归一）；
        // 对外事件契约值仍是 crowd（OrdinaryNpcService.CONTRACT_TYPE_CROWD），两者不可混用
        assertEquals("common", st.getNpcType());
        assertEquals("npc:5", st.getNpcRef());
        assertEquals("正在走向摊位", st.getActionText());
        assertEquals(3600L, st.getGameSecond());
    }

    @Test
    void syncProjectStates_coversSpecialAndCrowd() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(3L), eq(0)))
                .thenAnswer(inv -> List.of(charEntity(1L, 3L, "罗小黑", "青岩村", "在青岩村巡逻")));
        when(ordinaryNpcRepository.findByProjectIdOrderByIdAsc(3L))
                .thenAnswer(inv -> List.of(crowdEntity(2L, 3L, "阿福", "集市", "walk", "正在走向摊位")));

        service.syncProjectStates(3L);

        assertEquals(2, stateStore.size(), "特殊 + 普通人群都要建行");
    }

    // ==================== move 行动挂钩 ====================

    @Test
    void onNpcMoved_updatesPositionAndState() {
        service.onNpcMoved(1L, "npc:10", "special", "王都酒馆", "前往王都酒馆", 7200L);

        assertEquals(1, stateStore.size());
        ActorSandboxState st = stateStore.get(0);
        assertEquals("walking", st.getMoveState(), "move 行动后为移动态");
        assertEquals("前往王都酒馆", st.getActionText());
        assertEquals(7200L, st.getGameSecond());
        assertEquals(0d, st.getPosY());
        double[] expect = WorldSandboxStateService.derivePosition("王都酒馆", 500);
        assertEquals(expect[0], st.getPosX(), 0.0001, "坐标=目标地点派生坐标");
        assertEquals(expect[2], st.getPosZ(), 0.0001);
        assertEquals(WorldSandboxStateService.deriveHeading("王都酒馆"), st.getHeading(), 0.0001);
    }

    @Test
    void onNpcMoved_updatesExistingRow() {
        service.onNpcMoved(1L, "npc:10", "special", "青岩村", "在青岩村巡逻", 100L);
        service.onNpcMoved(1L, "npc:10", "special", "王都酒馆", "前往王都酒馆", 7200L);

        assertEquals(1, stateStore.size(), "同 NPC 重复 move 不新建行");
        ActorSandboxState st = stateStore.get(0);
        double[] expect = WorldSandboxStateService.derivePosition("王都酒馆", 500);
        assertEquals(expect[0], st.getPosX(), 0.0001, "坐标应更新为最新目标地点");
    }

    @Test
    void onNpcMoved_ignoresBlankRef() {
        service.onNpcMoved(1L, " ", "special", "王都酒馆", "行动", null);
        assertEquals(0, stateStore.size(), "空 NPC 引用应忽略");
    }

    // ==================== 实时行为决策系统 M1：刺激③他角色靠近挂钩（onNpcMoved） ====================

    @Test
    void onNpcMoved_hooksRealtimeDecisionApproach() throws Exception {
        com.holzyn.actor.domain.agent.runtime.RealTimeDecisionService rt =
                Mockito.mock(com.holzyn.actor.domain.agent.runtime.RealTimeDecisionService.class);
        java.lang.reflect.Field f = WorldSandboxStateService.class.getDeclaredField("realtimeDecisionService");
        f.setAccessible(true);
        f.set(service, rt);

        service.onNpcMoved(1L, "npc:10", "special", "王都酒馆", "前往王都酒馆", 7200L);
        // 到达后通知同地点其他角色即时决策（异步执行，不阻塞移动落库）
        Mockito.verify(rt).onNpcApproach(1L, "npc:10", "王都酒馆", 7200L);
    }

    @Test
    void onNpcMoved_realtimeHookAbsentSkips() {
        // 未装配（null）→ 位置更新照常，无异常
        service.onNpcMoved(1L, "npc:10", "special", "王都酒馆", "前往王都酒馆", 7200L);
        assertEquals(1, stateStore.size(), "未装配即时决策时移动落库不受影响");
    }

    // ==================== 主场景解析 ====================

    @Test
    void sceneSize_usesDefaultWhenNoScene() {
        double[] pos = WorldSandboxStateService.derivePosition("青岩村", 0);
        double[] expect = WorldSandboxStateService.derivePosition("青岩村", 500);
        assertEquals(expect[0], pos[0], 0.0001, "无场景边长时用默认 500");
    }

    @Test
    void mainScene_returnsFirstScene() {
        ActorSandboxScene scene = new ActorSandboxScene();
        scene.setId(7L);
        scene.setProjectId(1L);
        scene.setSceneSize(500);
        when(sceneRepository.findFirstByProjectIdOrderByIdAsc(1L)).thenReturn(Optional.of(scene));

        ActorSandboxScene found = service.mainScene(1L);
        assertNotNull(found);
        assertEquals(7L, found.getId());
    }

    // ==================== S2：想法广播（去重） ====================

    @Test
    void publishThought_broadcastsAndPersists() throws Exception {
        WorldSandboxSseHub hub = injectHub();

        service.publishThought(1L, "npc:10", "我闻到了一股危险的气息。", "perception", 100L);

        // 状态行惰性创建 + 想法落库
        assertEquals(1, stateStore.size());
        assertEquals("我闻到了一股危险的气息。", stateStore.get(0).getThoughtText());
        assertEquals("perception", stateStore.get(0).getThoughtSource());
        verify(hub).publishNpcThought(eq(1L), eq("npc:10"), eq("我闻到了一股危险的气息。"),
                eq("perception"), eq(100L));
    }

    @Test
    void publishThought_dedupSkipsSameContent() throws Exception {
        WorldSandboxSseHub hub = injectHub();
        service.publishThought(1L, "npc:10", "同一个想法。", "derived", 100L);

        service.publishThought(1L, "npc:10", "同一个想法。", "derived", 200L);

        verify(hub).publishNpcThought(eq(1L), eq("npc:10"), eq("同一个想法。"), eq("derived"), eq(100L));
        // 同内容第二次不重复广播（verify 仅 1 次）
    }

    @Test
    void publishThought_ignoresBlankText() throws Exception {
        WorldSandboxSseHub hub = injectHub();
        service.publishThought(1L, "npc:10", "   ", "derived", 100L);
        verify(hub, never()).publishNpcThought(anyLong(), any(), any(), any(), any());
    }

    // ==================== S2：join / leave 广播 + 状态行生命周期 ====================

    @Test
    void publishJoin_createsRowAndBroadcasts() throws Exception {
        WorldSandboxSseHub hub = injectHub();

        service.publishJoin(1L, "npc:6", "crowd", "阿福");

        assertEquals(1, stateStore.size(), "join 应惰性建状态行");
        assertEquals("npc:6", stateStore.get(0).getNpcRef());
        verify(hub).publishNpcJoin(eq(1L), eq("npc:6"), eq("crowd"), eq("阿福"));
    }

    @Test
    void publishLeave_removesRowAndBroadcasts() throws Exception {
        WorldSandboxSseHub hub = injectHub();
        service.publishJoin(1L, "npc:6", "crowd", "阿福");

        service.publishLeave(1L, "npc:6", "crowd", "阿福");

        assertEquals(0, stateStore.size(), "leave 应移除状态行（避免幽灵行）");
        verify(hub).publishNpcLeave(eq(1L), eq("npc:6"), eq("crowd"), eq("阿福"));
    }

    // ==================== S2：overview 聚合 ====================

    @Test
    void overview_aggregatesScenesAndStatesWithNames() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenAnswer(inv -> List.of(charEntity(10L, 1L, "罗小黑", "青岩村", "在青岩村巡逻")));
        when(ordinaryNpcRepository.findByProjectIdOrderByIdAsc(1L))
                .thenAnswer(inv -> List.of(crowdEntity(5L, 1L, "阿福", "集市", "walk", "正在走向摊位")));

        var vo = service.overview(1L);

        assertEquals(2, vo.getStateCount(), "特殊 + 普通人群都聚合");
        assertEquals(2, vo.getStates().size());
        Map<String, String> names = new java.util.LinkedHashMap<>();
        vo.getStates().forEach(s -> names.put(s.getNpcRef(), s.getNpcName()));
        assertEquals("罗小黑", names.get("npc:10"), "特殊 NPC 名称索引");
        assertEquals("阿福", names.get("npc:5"), "普通人群名称索引");
        assertEquals(0, vo.getSceneCount(), "无场景时场景数=0");
    }

    // ==================== S2：状态签名（去重比较） ====================

    @Test
    void stateSignature_changesWhenFieldsChange() {
        ActorSandboxState a = new ActorSandboxState();
        a.setNpcRef("npc:10");
        a.setPosX(1d);
        a.setMoveState("idle");
        a.setActionText("巡逻");
        ActorSandboxState b = new ActorSandboxState();
        b.setNpcRef("npc:10");
        b.setPosX(1d);
        b.setMoveState("idle");
        b.setActionText("巡逻");
        assertEquals(WorldSandboxStateService.stateSignature(a), WorldSandboxStateService.stateSignature(b),
                "相同内容签名一致（去重命中）");

        b.setActionText("前往酒馆");
        assertTrue(!WorldSandboxStateService.stateSignature(a).equals(WorldSandboxStateService.stateSignature(b)),
                "动作变化签名不同（去重不命中）");
    }

    /** 反射注入沙盘 Hub mock（字段注入，不改构造器）。 */
    private WorldSandboxSseHub injectHub() throws Exception {
        WorldSandboxSseHub hub = Mockito.mock(WorldSandboxSseHub.class);
        Field f = WorldSandboxStateService.class.getDeclaredField("sandboxHub");
        f.setAccessible(true);
        f.set(service, hub);
        return hub;
    }

    // ==================== 治理批次 0 · 卡3：moveState 卡死修复 ====================

    /**
     * G-MOVE-RESET-01（卡3 修复断言，起始 FAIL）：移动后动作结算 → moveState 复位 idle。
     * <p>现状错误值：onNpcMoved 只写 "walking" 永不复位（无复位路径）。复位点=卡3-方案 A：
     * 引擎每小时 dispatch 中非移动类当前行动 → setMoveState(idle)（本测试直接调服务方法，
     * 引擎挂钩在 WorldEngine.settleMoveState，行为共用同一映射表）。</p>
     */
    @Test
    void moveState_resetAfterNonMoveActionSettlement() {
        service.ensureState(1L, 33L, "special", "npc:5", "村口", null, null);
        service.onNpcMoved(1L, "npc:5", "special", "集市", "前往集市", 1000L);
        ActorSandboxState st = stateStore.get(0);
        assertEquals("walking", st.getMoveState(), "移动挂钩后 moveState=walking（现状正确）");
        // 动作结算（非移动类当前行动）→ 写侧复位
        service.setMoveState(1L, "npc:5", "idle");
        assertEquals("idle", st.getMoveState(), "移动后动作结算 → moveState 复位 idle");
    }

    /**
     * G-MOVE-RESET-02（卡3 修复断言，起始 FAIL）：存量 walking 卡死行经 syncProjectStates 惰性收敛 idle。
     * <p>现状错误值：syncProjectStates 只同步坐标/动作，不触碰 moveState → sync 后仍 "walking"。</p>
     */
    @Test
    void moveState_lazyConvergesViaSyncProjectStates() {
        // 存量卡死行：移动挂钩写 walking 后无复位路径（模拟沙盘行）
        service.ensureState(1L, 33L, "special", "npc:5", "村口", null, null);
        service.onNpcMoved(1L, "npc:5", "special", "集市", "前往集市", 1000L);
        ActorSandboxState st = stateStore.get(0);
        assertEquals("walking", st.getMoveState(), "移动挂钩后 moveState=walking（现状正确）");
        // 当前行动已是非移动（动作结算完成：在集市摆摊）→ sync 应惰性修正
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenAnswer(inv -> List.of(charEntity(5L, 1L, "赵铁柱", "集市", "在集市摆摊")));
        when(characterRepository.findById(5L))
                .thenReturn(java.util.Optional.of(charEntity(5L, 1L, "赵铁柱", "集市", "在集市摆摊")));
        when(ordinaryNpcRepository.findByProjectIdOrderByIdAsc(1L)).thenReturn(List.of());

        service.syncProjectStates(1L);

        assertEquals("idle", st.getMoveState(), "syncProjectStates 对「当前行动非移动」的 walking 卡死行惰性收敛 idle");
    }

    // ==================== 测试构造 ====================

    private ActorCharacter charEntity(long id, long projectId, String name, String location, String activity) {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(id);
        ch.setProjectId(projectId);
        ch.setName(name);
        ch.setLocation(location);
        ch.setCurrentActivity(activity);
        ch.setDeleted(0);
        return ch;
    }

    /** 普通型 NPC 夹具（G1a：显式 type=common；state/lastAction 唯一家在 actor_state，不在此设置） */
    private ActorCharacter crowdEntity(long id, long projectId, String name, String location, String state, String lastAction) {
        ActorCharacter n = new ActorCharacter();
        n.setId(id);
        n.setProjectId(projectId);
        n.setName(name);
        n.setType("common");
        n.setLocation(location);
        if (state != null || lastAction != null) {
            when(npcActivityStateService.activityByRef(projectId)).thenReturn(java.util.Map.of(
                    com.holzyn.actor.common.NpcRefs.of(id),
                    new com.holzyn.actor.domain.npc.state.service.NpcActivityStateService.ActivityUpdate(state, lastAction)));
        }
        return n;
    }
}
