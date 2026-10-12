package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxScene;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxState;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxSceneRepository;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxStateRepository;
import com.holzyn.actor.domain.world.config.WorldInfoProperties;
import com.holzyn.actor.domain.world.entity.ActorInfoDiffusion;
import com.holzyn.actor.domain.world.entity.ActorInfoPacket;
import com.holzyn.actor.domain.world.entity.ActorWorldFrame;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.ActorInfoDiffusionRepository;
import com.holzyn.actor.domain.world.repository.ActorInfoPacketRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldFrameRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 信息包扩散引擎单元测试（世界演化与游戏时间系统 M5，domain/world/service）。
 * <p>职责：验证——① 静态纯逻辑（到达时刻公式/欧氏距离/传播节点轮换/里数格式化）；
 * ② 每小时扩散（origin 即时到达 + 近区按距离延迟 + 远区未达 + 物化行字段）；
 * ③ 过期即停（包置 expired 不再扩散）；④ 每包每区域首达 1 条到达事件（幂等去重）；
 * ⑤ 扩散开关关零扩散；⑥ 读接口过滤（region/expired/packetId）。</p>
 */
class InfoDiffusionServiceTest {

    private ActorInfoPacketRepository packetRepository;
    private ActorInfoDiffusionRepository diffusionRepository;
    private ActorWorldFrameRepository frameRepository;
    private WorldLocationRepository locationRepository;
    private ActorSandboxStateRepository sandboxStateRepository;
    private ActorSandboxSceneRepository sceneRepository;
    private ActorCharacterRepository characterRepository;
    private ActorCharacterRepository npcRepository;
    private WorldEventBus worldEventBus;
    private InfoDistortionService distortionService;
    private WorldInfoProperties props;
    private InfoDiffusionService service;

    /** 已保存扩散行快照 */
    private final List<ActorInfoDiffusion> savedRows = new ArrayList<>();
    /** 已发布事件快照 */
    private final List<EventSpec> published = new ArrayList<>();

    @BeforeEach
    void setUp() {
        packetRepository = Mockito.mock(ActorInfoPacketRepository.class);
        diffusionRepository = Mockito.mock(ActorInfoDiffusionRepository.class);
        frameRepository = Mockito.mock(ActorWorldFrameRepository.class);
        locationRepository = Mockito.mock(WorldLocationRepository.class);
        sandboxStateRepository = Mockito.mock(ActorSandboxStateRepository.class);
        sceneRepository = Mockito.mock(ActorSandboxSceneRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        npcRepository = Mockito.mock(ActorCharacterRepository.class);
        worldEventBus = Mockito.mock(WorldEventBus.class);
        distortionService = Mockito.mock(InfoDistortionService.class);
        props = new WorldInfoProperties();
        service = new InfoDiffusionService(packetRepository, diffusionRepository, frameRepository,
                locationRepository, sandboxStateRepository, sceneRepository, characterRepository,
                npcRepository, worldEventBus, distortionService, props, new ObjectMapper());
        // 2026-09-27 P0 修复配套：到达事件改为「事务提交后异步发布」。单测无事务上下文（且此处不是
        // Spring 容器）⇒ 直接投递；注入同线程执行器使发布确定同步，保持下列断言语义不变
        // （真实异步/事务边界由 InfoDiffusionServiceArrivalAsyncTest 覆盖）。
        service.setArrivalPublishExecutor(Runnable::run);

        when(diffusionRepository.findByPacketId(any())).thenReturn(List.of());
        when(diffusionRepository.findByProjectId(any())).thenReturn(List.of());
        when(packetRepository.findByProjectIdAndStatus(any(), any())).thenReturn(List.of());
        when(packetRepository.findByProjectId(any())).thenReturn(List.of());
        when(locationRepository.findByProjectId(any())).thenReturn(List.of());
        when(sandboxStateRepository.findByProjectIdOrderByIdAsc(any())).thenReturn(List.of());
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(any(), any())).thenReturn(List.of());
        when(npcRepository.findByProjectIdOrderByIdAsc(any())).thenReturn(List.of());
        when(frameRepository.findByProjectIdAndStatus(any(), any())).thenReturn(List.of());
        when(sceneRepository.findByProjectIdOrderByCreatedAtAsc(any())).thenReturn(List.of());

        Mockito.doAnswer(inv -> {
            ActorInfoDiffusion d = inv.getArgument(0);
            if (d.getId() == null) {
                d.setId(1000L + savedRows.size());
            }
            savedRows.add(d);
            return d;
        }).when(diffusionRepository).save(any(ActorInfoDiffusion.class));
        Mockito.doAnswer(inv -> {
            published.add(inv.getArgument(2));
            return null;
        }).when(worldEventBus).publish(any(), any(), any(EventSpec.class));
    }

    // ==================== 静态纯逻辑 ====================

    @Test
    void arrivalSecond_math() {
        assertEquals(172800L, InfoDiffusionService.arrivalSecond(86400L, 1.0, 1.0),
                "1 天/公里 × 1 公里 = 1 天 = 86400 秒");
        assertEquals(129600L, InfoDiffusionService.arrivalSecond(86400L, 0.5, 1.0),
                "0.5 天/公里 × 1 公里 = 0.5 天");
        assertEquals(86400L, InfoDiffusionService.arrivalSecond(86400L, 1.0, 0.0),
                "origin 自身距离 0 → 帧生成时刻即到");
        assertEquals(259200L, InfoDiffusionService.arrivalSecond(86400L, -1.0, 2.0),
                "非法速度回退缺省 1 天/公里");
    }

    @Test
    void distanceKm_euclideanAndFallback() {
        assertEquals(0.5d, InfoDiffusionService.distanceKm(new double[]{0, 0}, new double[]{500, 0}, 1.0),
                0.0001, "500 单位=500 米=0.5 公里");
        assertEquals(1.0d, InfoDiffusionService.distanceKm(null, new double[]{1, 1}, 1.0),
                "坐标缺失回退兜底距离");
        assertEquals(0.7d, InfoDiffusionService.distanceKm(new double[]{0, 0}, null, 0.7));
        assertEquals(1.0d, InfoDiffusionService.distanceKm(null, null, 0), "非法兜底回退 1.0");
    }

    @Test
    void relayedBy_deterministic() {
        String a = InfoDiffusionService.relayedBy(1L, List.of("商队", "信使"), "集市");
        String b = InfoDiffusionService.relayedBy(1L, List.of("商队", "信使"), "集市");
        assertEquals(a, b, "同包同区域恒同节点");
        assertTrue(List.of("商队", "信使").contains(a));
        String tpl = InfoDiffusionService.relayedBy(1L, List.of(), "渡口");
        assertTrue(List.of("商队", "信使", "行脚商贩", "流民", "驿卒", "走方郎中").contains(tpl),
                "渠道为空走模板池");
    }

    @Test
    void formatLi_formatting() {
        assertEquals("事发地附近", InfoDiffusionService.formatLi(0.01));
        assertEquals("2.0 里", InfoDiffusionService.formatLi(1.0));
        assertEquals("20 里", InfoDiffusionService.formatLi(10.0));
    }

    // ==================== 每小时扩散 ====================

    /** 构造带 NPC 坐标的三地点项目（集市[0,0]/渡口[500,0]/山神庙[5000,0]）。 */
    private void stubThreeLocations() {
        when(locationRepository.findByProjectId(1L)).thenReturn(List.of(
                location(1L, "集市"), location(2L, "渡口"), location(3L, "山神庙")));
        ActorCharacter c1 = character(1L, "集市");
        ActorCharacter c2 = character(2L, "渡口");
        ActorCharacter c3 = character(3L, "山神庙");
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(c1, c2, c3));
        when(sandboxStateRepository.findByProjectIdOrderByIdAsc(1L)).thenReturn(List.of(
                state("npc:1", 0d, 0d), state("npc:2", 500d, 0d), state("npc:3", 5000d, 0d)));
        ActorSandboxScene scene = new ActorSandboxScene();
        scene.setSceneSize(500);
        when(sceneRepository.findByProjectIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(scene));
    }

    /** 构造帧（id=1，第 1 日，生成时刻 86400）与包（origin=集市，speed=1 天/公里，validUntil=第 5 日）。 */
    private ActorInfoPacket stubFrameAndPacket() {
        ActorWorldFrame frame = new ActorWorldFrame();
        frame.setId(1L);
        frame.setProjectId(1L);
        frame.setGameDay(1L);
        frame.setStatus("active");
        frame.setGeneratedAtGameSecond(86400L);
        frame.setMacroEventsJson("[]");
        when(frameRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(frame));
        when(frameRepository.findAllById(Set.of(1L))).thenReturn(List.of(frame));

        ActorInfoPacket packet = new ActorInfoPacket();
        packet.setId(1L);
        packet.setFrameId(1L);
        packet.setProjectId(1L);
        packet.setInfoId("I-1-001-1");
        packet.setOrigin("集市");
        packet.setContent("集市粮价飞涨，有人开始囤粮");
        packet.setReliability(0.8);
        packet.setSpreadChannelsJson("[\"商队\",\"信使\"]");
        packet.setSpreadSpeedDaysPerKm(1.0);
        packet.setValidUntilDay(5L);
        packet.setDistortionRulesJson("[\"距离越远越夸大\"]");
        packet.setStatus("active");
        when(packetRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(packet));
        return packet;
    }

    @Test
    void diffuseProject_originReachedNearDelayedFarNot() {
        stubThreeLocations();
        stubFrameAndPacket();
        when(distortionService.distortForRegion(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("据说集市粮价飞涨，有人开始囤粮");

        // 第 1 日 00:10（帧生成后 600 秒）：集市（距离 0）应到达；渡口（0.5km×1 天/km=0.5 天）未达；
        // 山神庙（5km×1 天/km=5 天）未达
        service.diffuseProject(1L, 86400L + 600L);

        assertEquals(1, savedRows.size(), "仅 origin 区域到达");
        ActorInfoDiffusion row = savedRows.get(0);
        assertEquals("集市", row.getRegion());
        assertEquals(Long.valueOf(86400L), row.getReachedGameSecond(), "origin 到达=帧生成时刻");
        assertTrue(row.getRelayedBy() != null && !row.getRelayedBy().isBlank(), "传播节点非空");
        assertEquals(1, published.size(), "仅 origin 首达发 1 条到达事件");
        EventSpec spec = published.get(0);
        assertEquals("world_frame", spec.getKind());
        assertEquals("engine", spec.getSource());
        assertEquals("global", spec.getVisibility());
        assertEquals(1, spec.getImportance());
        assertEquals("消息传到了集市", spec.getTitle());
        assertEquals("diffusion:1:1:1:集市", spec.getSourceKey());
        assertTrue(spec.getContent().contains("据说"), "到达事件内容=区域传闻版");
    }

    @Test
    void diffuseProject_nearRegionReachedAfterDelay() {
        stubThreeLocations();
        stubFrameAndPacket();
        when(distortionService.distortForRegion(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("渡口也听到了风声");

        // 第 2 日 12:00（86400+86400+43200）：集市+渡口（0.5 天延迟=43200 秒 → 到达 129600 ≤ 216000）到达，山神庙未达
        service.diffuseProject(1L, 216000L);

        assertEquals(2, savedRows.size(), "集市+渡口应到达");
        List<String> regions = savedRows.stream().map(ActorInfoDiffusion::getRegion).toList();
        assertTrue(regions.contains("集市") && regions.contains("渡口"), "双区域到达: " + regions);
        assertEquals(2, published.size(), "两个区域各 1 条到达事件");
    }

    @Test
    void diffuseProject_expiredPacketStopsSpreading() {
        stubThreeLocations();
        ActorInfoPacket packet = stubFrameAndPacket();
        packet.setValidUntilDay(0L); // 第 1 日即过期

        service.diffuseProject(1L, 86400L + 600L);

        assertEquals("expired", packet.getStatus(), "过期包应置 expired");
        verify(packetRepository).save(packet);
        assertEquals(0, savedRows.size(), "过期包不再新增扩散行");
        assertEquals(0, published.size(), "过期包不发布到达事件");
    }

    @Test
    void diffuseProject_idempotentPerRegionNoRepeatEvent() {
        stubThreeLocations();
        stubFrameAndPacket();
        // 集市已有扩散行 → 不重复物化/不重复发事件
        ActorInfoDiffusion existing = new ActorInfoDiffusion();
        existing.setId(9L);
        existing.setRegion("集市");
        existing.setReachedGameSecond(86400L);
        when(diffusionRepository.findByPacketId(1L)).thenReturn(List.of(existing));

        service.diffuseProject(1L, 86400L + 600L);

        assertEquals(0, savedRows.size(), "已到达区域不重复物化");
        assertEquals(0, published.size(), "已到达区域不重复发事件");
    }

    @Test
    void diffuseProject_originWithoutCoordsReachedAtFrameTime() {
        // ⚠️ 语义铁律：origin 自身距离恒为 0（帧生成时刻即到）——即使 origin 不在坐标表（无坐标）
        // 也不走兜底距离（冒烟抓修：origin=青禾村内 曾因无坐标走了 1 公里兜底 → 到达时间错误）
        stubThreeLocations();
        ActorInfoPacket packet = stubFrameAndPacket();
        packet.setOrigin("外村"); // 不在世界地点/坐标表内
        when(distortionService.distortForRegion(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("外村传来的消息");

        service.diffuseProject(1L, 86400L + 600L);

        assertEquals(1, savedRows.size(), "仅 origin 区域到达（其余走 1 公里兜底 → 1 天延迟未达）");
        assertEquals("外村", savedRows.get(0).getRegion());
        assertEquals(Long.valueOf(86400L), savedRows.get(0).getReachedGameSecond(),
                "origin 自身到达=帧生成时刻（不因无坐标走兜底距离）");
    }

    @Test
    void diffuseProject_disabledDoesNothing() {
        props.setDiffusionEnabled(false);
        stubThreeLocations();
        stubFrameAndPacket();

        service.diffuseProject(1L, 86400L + 600L);

        assertEquals(0, savedRows.size());
        assertEquals(0, published.size());
        verify(diffusionRepository, never()).save(any());
    }

    @Test
    void diffuseProject_arrivalEventDisabledStillMaterializes() {
        props.setArrivalEventEnabled(false);
        stubThreeLocations();
        stubFrameAndPacket();

        service.diffuseProject(1L, 86400L + 600L);

        assertEquals(1, savedRows.size(), "事件关不影响扩散物化");
        assertEquals(0, published.size(), "事件关不发布到达事件");
    }

    // ==================== 读接口 ====================

    @Test
    void queryPackets_filtersByRegionAndExpired() {
        ActorInfoPacket active = new ActorInfoPacket();
        active.setId(1L);
        active.setOrigin("集市");
        active.setStatus("active");
        ActorInfoPacket expired = new ActorInfoPacket();
        expired.setId(2L);
        expired.setOrigin("渡口");
        expired.setStatus("expired");
        when(packetRepository.findByProjectId(1L)).thenReturn(List.of(active, expired));
        when(packetRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(active));
        when(packetRepository.findByProjectIdAndStatus(1L, "expired")).thenReturn(List.of(expired));

        // 不过滤
        assertEquals(2, service.queryPackets(1L, null, null).size());
        // 仅 active
        List<ActorInfoPacket> actives = service.queryPackets(1L, null, false);
        assertEquals(1, actives.size());
        assertEquals("active", actives.get(0).getStatus());
        // 仅 expired
        assertEquals(1, service.queryPackets(1L, null, true).size());
        // 按区域（origin 精确）
        assertEquals(1, service.queryPackets(1L, "集市", null).size());
        assertEquals(0, service.queryPackets(1L, "不存在", null).size());
        // 按扩散到达区域
        ActorInfoDiffusion d = new ActorInfoDiffusion();
        d.setRegion("山神庙");
        when(diffusionRepository.findByPacketId(1L)).thenReturn(List.of(d));
        when(diffusionRepository.findByPacketId(2L)).thenReturn(List.of());
        assertEquals(1, service.queryPackets(1L, "山神庙", null).size());
    }

    @Test
    void queryDiffusion_byPacketOrProject() {
        ActorInfoDiffusion d1 = new ActorInfoDiffusion();
        d1.setId(1L);
        d1.setPacketId(1L);
        d1.setReachedGameSecond(86400L);
        ActorInfoDiffusion d2 = new ActorInfoDiffusion();
        d2.setId(2L);
        d2.setPacketId(2L);
        d2.setReachedGameSecond(129600L);
        when(diffusionRepository.findByPacketId(1L)).thenReturn(List.of(d1));
        when(diffusionRepository.findByProjectId(1L)).thenReturn(List.of(d2, d1));

        assertEquals(1, service.queryDiffusion(1L, 1L).size());
        assertEquals(2, service.queryDiffusion(1L, null).size());
        assertEquals(86400L, service.queryDiffusion(1L, null).get(0).getReachedGameSecond(),
                "按到达时刻升序");
    }

    // ==================== 辅助构造 ====================

    private ActorWorldLocation location(Long id, String name) {
        ActorWorldLocation l = new ActorWorldLocation();
        l.setId(id);
        l.setProjectId(1L);
        l.setName(name);
        return l;
    }

    private ActorCharacter character(Long id, String location) {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(id);
        ch.setProjectId(1L);
        ch.setName("角色" + id);
        ch.setLocation(location);
        return ch;
    }

    private ActorSandboxState state(String ref, Double x, Double z) {
        ActorSandboxState st = new ActorSandboxState();
        st.setProjectId(1L);
        st.setNpcRef(ref);
        st.setPosX(x);
        st.setPosZ(z);
        return st;
    }
}
