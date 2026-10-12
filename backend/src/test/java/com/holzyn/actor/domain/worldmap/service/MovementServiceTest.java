package com.holzyn.actor.domain.worldmap.service;

import com.holzyn.actor.domain.sandbox.entity.ActorSandboxScene;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxState;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxSceneRepository;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxStateRepository;
import com.holzyn.actor.domain.sandbox.service.WorldSandboxSseHub;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.worldmap.config.WorldMapProperties;
import com.holzyn.actor.domain.worldmap.entity.ActorMovement;
import com.holzyn.actor.domain.worldmap.repository.ActorMovementRepository;
import com.holzyn.actor.domain.worldmap.vo.MovementVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MovementService 单元测试（位置移动与地图生成系统 · M2，仓库全 mock）。
 * <p>职责：路程换算/插值/进度静态纯逻辑；移动发起（walking + SSE + 沙盘 moveState）；
 * 已在目标直接到达；tick 未到点插值 / 到点 arrived 落位 + 到达挂钩 + moveState 复位；
 * 打断 cancelled 坐标停打断点；在途判定；列表；就寝回家（goHome 已在家不发/离家发起）。</p>
 */
class MovementServiceTest {

    private final WorldMapProperties properties = new WorldMapProperties();

    private final ActorMovementRepository movementRepository = mock(ActorMovementRepository.class);
    private final ActorSandboxStateRepository sandboxStateRepository = mock(ActorSandboxStateRepository.class);
    private final ActorSandboxSceneRepository sceneRepository = mock(ActorSandboxSceneRepository.class);
    private final WorldLocationRepository locationRepository = mock(WorldLocationRepository.class);
    private final WorldSandboxSseHub sandboxHub = mock(WorldSandboxSseHub.class);

    private MovementService service;

    private final ActorSandboxScene scene = scene(500);

    @BeforeEach
    void setUp() {
        service = new MovementService(movementRepository, sandboxStateRepository, sceneRepository,
                locationRepository, properties);
        ReflectionTestUtils.setField(service, "sandboxHub", sandboxHub);
        when(sceneRepository.findFirstByProjectIdOrderByIdAsc(anyLong())).thenReturn(Optional.of(scene));
    }

    // ==================== 静态纯逻辑 ====================

    @Test
    void travelGameSeconds_distTimesGridDivSpeed() {
        // 100 格 × 0.2m/格 ÷ 0.05m/游戏秒 = 400 游戏秒
        assertEquals(400L, MovementService.travelGameSeconds(100, 0.05));
        // 速度非法回退 0.05
        assertEquals(400L, MovementService.travelGameSeconds(100, 0));
        // 距离 0 → 至少 1 秒
        assertEquals(1L, MovementService.travelGameSeconds(0, 0.05));
    }

    @Test
    void interpolate_clampsAndLinearlyBlends() {
        double[] mid = MovementService.interpolate(new double[]{0, 0}, new double[]{100, 0}, 0.5);
        assertEquals(50, mid[0], 1e-9);
        assertEquals(0, mid[1], 1e-9);
        double[] over = MovementService.interpolate(new double[]{0, 0}, new double[]{100, 0}, 2.0);
        assertEquals(100, over[0], 1e-9);
        double[] under = MovementService.interpolate(new double[]{0, 0}, new double[]{100, 0}, -1.0);
        assertEquals(0, under[0], 1e-9);
    }

    @Test
    void headingTo_pointsFromTo() {
        assertEquals(Math.PI / 2, MovementService.headingTo(0, 0, 0, 10), 1e-9);
        assertEquals(Math.PI, MovementService.headingTo(0, 0, -10, 0), 1e-9);
    }

    @Test
    void mapToSandbox_centersMapCoord() {
        assertEquals(0d, MovementService.mapToSandbox(250, 500), 1e-9);
        assertEquals(-210d, MovementService.mapToSandbox(40, 500), 1e-9);
    }

    // ==================== 移动发起 ====================

    @Test
    void startMove_writesWalkingAndBroadcasts() {
        ActorSandboxState st = state(1L, "npc:1", 0d, 0d);
        when(sandboxStateRepository.findByProjectIdAndNpcRef(1L, "npc:1")).thenReturn(Optional.of(st));
        ActorWorldLocation loc = loc(5L, "医馆", 260d, 260d); // 地图域 → 中心化 (10,10)，距离 ~14 > ε
        when(locationRepository.findById(5L)).thenReturn(Optional.of(loc));
        ActorMovement saved = new ActorMovement();
        saved.setId(1L);
        when(movementRepository.save(any(ActorMovement.class))).thenAnswer(inv -> {
            ActorMovement m = inv.getArgument(0);
            m.setId(1L);
            return m;
        });

        ActorMovement m = service.startMove(1L, "npc:1", "special", ActorMovement.REASON_DECISION,
                5L, "医馆", 1000L);

        assertNotNull(m);
        assertEquals(ActorMovement.STATUS_WALKING, m.getStatus());
        assertEquals(ActorMovement.REASON_DECISION, m.getReason());
        assertEquals(5L, m.getTargetLocationId());
        verify(sandboxHub).publishNpcWalking(eq(1L), eq("npc:1"), any(), any(), eq(0d), anyLong(),
                eq(ActorMovement.REASON_DECISION));
        verify(sandboxStateRepository).save(any(ActorSandboxState.class));
    }

    @Test
    void startMove_nearTargetArrivesDirectly() {
        ActorSandboxState st = state(1L, "npc:1", 0d, 0d);
        when(sandboxStateRepository.findByProjectIdAndNpcRef(1L, "npc:1")).thenReturn(Optional.of(st));
        ActorWorldLocation loc = loc(5L, "医馆", 250.2d, 250d); // 中心化 (0.2,0)，距离 0.2 ≤ ε → 直接到达
        when(locationRepository.findById(5L)).thenReturn(Optional.of(loc));

        ActorMovement m = service.startMove(1L, "npc:1", "special", ActorMovement.REASON_DECISION,
                5L, "医馆", 1000L);

        assertEquals(null, m, "已在目标不写移动行");
        verify(movementRepository, never()).save(any(ActorMovement.class));
        verify(sandboxHub).publishNpcArrived(eq(1L), eq("npc:1"), any(), anyDouble());
        assertEquals(0.2d, st.getPosX(), 1e-9, "直接到达落位");
        assertEquals("idle", st.getMoveState());
    }

    @Test
    void startMove_noRegionFallsBackToDerivePosition() {
        ActorSandboxState st = state(1L, "npc:1", 0d, 0d);
        when(sandboxStateRepository.findByProjectIdAndNpcRef(1L, "npc:1")).thenReturn(Optional.of(st));
        when(locationRepository.findById(anyLong())).thenReturn(Optional.empty()); // 无区域
        ActorMovement saved = new ActorMovement();
        saved.setId(1L);
        when(movementRepository.save(any(ActorMovement.class))).thenAnswer(inv -> {
            ActorMovement m = inv.getArgument(0);
            m.setId(1L);
            return m;
        });

        ActorMovement m = service.startMove(1L, "npc:1", "special", ActorMovement.REASON_PLAN,
                null, "王都酒馆", 100L);

        assertNotNull(m);
        assertEquals(ActorMovement.STATUS_WALKING, m.getStatus());
        assertEquals("王都酒馆", m.getTargetLocationName());
    }

    // ==================== tick 插值 / 到点 ====================

    @Test
    void tick_midwayInterpolatesSandboxPos() {
        ActorMovement m = new ActorMovement();
        m.setId(1L);
        m.setProjectId(1L);
        m.setCharacterRef("npc:1");
        m.setFromX(0d); m.setFromZ(0d);
        m.setToX(100d); m.setToZ(0d);
        m.setFromGameSecond(0L); m.setArriveGameSecond(1000L);
        m.setSpeed(0.05);
        m.setStatus(ActorMovement.STATUS_WALKING);
        when(movementRepository.findByProjectIdAndStatusOrderByIdAsc(1L, ActorMovement.STATUS_WALKING))
                .thenReturn(List.of(m));
        ActorSandboxState st = state(1L, "npc:1", 0d, 0d);
        st.setMoveState("walking"); // startMove 已置 walking，tick 保持
        when(sandboxStateRepository.findByProjectIdAndNpcRef(1L, "npc:1")).thenReturn(Optional.of(st));

        service.tick(1L, 500L);

        assertEquals(50d, st.getPosX(), 1e-9, "tick 过半应插值到 50");
        assertEquals(0d, st.getPosZ(), 1e-9);
        assertEquals("walking", st.getMoveState());
        verify(sandboxHub).publishNpcWalking(eq(1L), eq("npc:1"), any(), any(), eq(0.5), eq(1000L), any());
        verify(movementRepository, never()).save(eq(m));
    }

    @Test
    void tick_arrivesSettlesAndHooks() {
        ActorMovement m = new ActorMovement();
        m.setId(1L);
        m.setProjectId(1L);
        m.setCharacterRef("npc:1");
        m.setFromX(0d); m.setFromZ(0d);
        m.setToX(100d); m.setToZ(0d);
        m.setFromGameSecond(0L); m.setArriveGameSecond(1000L);
        m.setSpeed(0.05);
        m.setStatus(ActorMovement.STATUS_WALKING);
        m.setTargetLocationId(5L);
        m.setTargetLocationName("医馆");
        when(movementRepository.findByProjectIdAndStatusOrderByIdAsc(1L, ActorMovement.STATUS_WALKING))
                .thenReturn(List.of(m));
        ActorSandboxState st = state(1L, "npc:1", 0d, 0d);
        when(sandboxStateRepository.findByProjectIdAndNpcRef(1L, "npc:1")).thenReturn(Optional.of(st));
        com.holzyn.actor.domain.agent.runtime.RealTimeDecisionService rtds =
                mock(com.holzyn.actor.domain.agent.runtime.RealTimeDecisionService.class);
        ReflectionTestUtils.setField(service, "realtimeDecisionService", rtds);

        service.tick(1L, 1000L);

        assertEquals(ActorMovement.STATUS_ARRIVED, m.getStatus(), "到点置 arrived");
        assertEquals(100d, st.getPosX(), 1e-9, "落位到终点");
        assertEquals("idle", st.getMoveState(), "到点 moveState 复位 idle");
        verify(movementRepository).save(eq(m));
        verify(sandboxHub).publishNpcArrived(eq(1L), eq("npc:1"), any(), anyDouble());
        verify(rtds).onNpcApproach(eq(1L), eq("npc:1"), eq("医馆"), eq(1000L));
    }

    // ==================== 打断 / 取消 ====================

    @Test
    void cancelMove_marksCancelledAndStopsAtInterruptPoint() {
        ActorMovement m = new ActorMovement();
        m.setId(1L);
        m.setProjectId(1L);
        m.setCharacterRef("npc:1");
        m.setFromX(0d); m.setFromZ(0d);
        m.setToX(100d); m.setToZ(0d);
        m.setFromGameSecond(0L); m.setArriveGameSecond(1000L);
        m.setStatus(ActorMovement.STATUS_WALKING);
        when(movementRepository.findFirstByProjectIdAndCharacterRefAndStatusOrderByIdDesc(
                1L, "npc:1", ActorMovement.STATUS_WALKING)).thenReturn(Optional.of(m));
        ActorSandboxState st = state(1L, "npc:1", 0d, 0d);
        when(sandboxStateRepository.findByProjectIdAndNpcRef(1L, "npc:1")).thenReturn(Optional.of(st));

        service.cancelMove(1L, "npc:1", 500L);

        assertEquals(ActorMovement.STATUS_CANCELLED, m.getStatus());
        assertEquals(50d, st.getPosX(), 1e-9, "打断点=插值位置");
        assertEquals("idle", st.getMoveState());
        verify(movementRepository).save(eq(m));
    }

    @Test
    void startMoveTo_reusesExistingWalkingWhenInTransit() {
        // 已有 walking 行 → 新行动复用更新（重定向），不新建第二条（打断与恢复：同角色至多一条在途）
        ActorMovement existing = new ActorMovement();
        existing.setId(1L);
        existing.setProjectId(1L);
        existing.setCharacterRef("npc:1");
        existing.setStatus(ActorMovement.STATUS_WALKING);
        when(movementRepository.findFirstByProjectIdAndCharacterRefAndStatusOrderByIdDesc(
                1L, "npc:1", ActorMovement.STATUS_WALKING)).thenReturn(Optional.of(existing));
        ActorSandboxState st = state(1L, "npc:1", 0d, 0d);
        when(sandboxStateRepository.findByProjectIdAndNpcRef(1L, "npc:1")).thenReturn(Optional.of(st));
        when(movementRepository.save(any(ActorMovement.class))).thenAnswer(inv -> inv.getArgument(0));

        ActorMovement m = service.startMoveTo(1L, "npc:1", new double[]{0, 0}, new double[]{100, 0},
                ActorMovement.REASON_DECISION, 5L, "医馆", 1000L);

        assertNotNull(m);
        assertEquals(1L, m.getId(), "复用既有 walking 行（不新建）");
        assertEquals(100d, m.getToX(), 1e-9, "目标重定向");
        assertEquals(1000L, m.getFromGameSecond());
        verify(movementRepository).save(eq(existing));
    }

    @Test
    void cancelMove_noWalkingNoop() {
        when(movementRepository.findFirstByProjectIdAndCharacterRefAndStatusOrderByIdDesc(
                1L, "npc:1", ActorMovement.STATUS_WALKING)).thenReturn(Optional.empty());
        service.cancelMove(1L, "npc:1", 100L);
        verify(movementRepository, never()).save(any(ActorMovement.class));
    }

    // ==================== 在途判定 / 列表 ====================

    @Test
    void isInTransit_queriesWalking() {
        when(movementRepository.existsByProjectIdAndCharacterRefAndStatus(1L, "npc:1", ActorMovement.STATUS_WALKING))
                .thenReturn(true);
        assertTrue(service.isInTransit(1L, "npc:1"));
        assertFalse(service.isInTransit(1L, "npc:9"));
    }

    @Test
    void listMovements_buildsVo() {
        ActorMovement m = new ActorMovement();
        m.setId(1L);
        m.setProjectId(1L);
        m.setCharacterRef("npc:1");
        m.setFromX(0d); m.setFromZ(0d);
        m.setToX(100d); m.setToZ(0d);
        m.setFromGameSecond(0L); m.setArriveGameSecond(1000L);
        m.setSpeed(0.05);
        m.setStatus(ActorMovement.STATUS_WALKING);
        m.setReason(ActorMovement.REASON_PLAN);
        when(movementRepository.findByProjectIdAndStatusOrderByIdAsc(1L, ActorMovement.STATUS_WALKING))
                .thenReturn(List.of(m));

        List<MovementVO> vos = service.listMovements(1L);

        assertEquals(1, vos.size());
        assertEquals("npc:1", vos.get(0).getNpcRef());
        assertEquals(ActorMovement.REASON_PLAN, vos.get(0).getReason());
    }

    // ==================== 偶遇/途经事件在场当事人（2026-10-08：相关度只评估在场 NPC） ====================

    @Test
    void publishEncounterEvent_recordsBothPartiesAsPresent() {
        com.holzyn.actor.domain.agent.event.WorldEventBus bus =
                mock(com.holzyn.actor.domain.agent.event.WorldEventBus.class);
        ReflectionTestUtils.setField(service, "worldEventBus", bus);

        service.publishEncounterEvent(1L, "npc:1", "npc:7", "王阿婆", "早啊", true, 1000L);

        org.mockito.ArgumentCaptor<com.holzyn.actor.domain.agent.event.EventSpec> captor =
                org.mockito.ArgumentCaptor.forClass(com.holzyn.actor.domain.agent.event.EventSpec.class);
        verify(bus).publish(eq(1L), eq(1L), captor.capture());
        com.holzyn.actor.domain.agent.event.EventSpec spec = captor.getValue();
        assertEquals(java.util.List.of("npc:1", "npc:7"), spec.getCharacterRefs(),
                "偶遇双方当事人都记录为在场（此前只记录移动者）");
        assertEquals("move_encounter", spec.getKind());
        assertEquals("location", spec.getVisibility());
    }

    @Test
    void publishPassBy_recordsBothPartiesAsPresent() {
        com.holzyn.actor.domain.agent.event.WorldEventBus bus =
                mock(com.holzyn.actor.domain.agent.event.WorldEventBus.class);
        ReflectionTestUtils.setField(service, "worldEventBus", bus);

        service.publishPassBy(1L, "npc:1", 5L, "npc:7", "王阿婆", 1000L);

        org.mockito.ArgumentCaptor<com.holzyn.actor.domain.agent.event.EventSpec> captor =
                org.mockito.ArgumentCaptor.forClass(com.holzyn.actor.domain.agent.event.EventSpec.class);
        verify(bus).publish(eq(1L), eq(1L), captor.capture());
        com.holzyn.actor.domain.agent.event.EventSpec spec = captor.getValue();
        assertEquals(java.util.List.of("npc:1", "npc:7"), spec.getCharacterRefs(),
                "迎面相遇双方当事人都记录为在场（此前只记录移动者）");
        assertEquals("pass_by", spec.getKind());
        assertEquals("location", spec.getVisibility());
    }

    // ==================== 工具 ====================

    private ActorSandboxScene scene(int size) {
        ActorSandboxScene s = new ActorSandboxScene();
        s.setId(33L);
        s.setSceneSize(size);
        return s;
    }

    private ActorSandboxState state(Long projectId, String ref, double x, double z) {
        ActorSandboxState st = new ActorSandboxState();
        st.setId(1L);
        st.setProjectId(projectId);
        st.setNpcRef(ref);
        st.setPosX(x);
        st.setPosZ(z);
        st.setMoveState("idle");
        return st;
    }

    private ActorWorldLocation loc(Long id, String name, double cx, double cz) {
        ActorWorldLocation l = new ActorWorldLocation();
        l.setId(id);
        l.setProjectId(1L);
        l.setName(name);
        l.setCenterX(cx);
        l.setCenterZ(cz);
        return l;
    }
}
