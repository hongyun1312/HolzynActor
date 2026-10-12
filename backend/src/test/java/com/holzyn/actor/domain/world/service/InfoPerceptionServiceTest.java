package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.config.WorldInfoProperties;
import com.holzyn.actor.domain.world.entity.ActorInfoDiffusion;
import com.holzyn.actor.domain.world.entity.ActorInfoPacket;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.ActorInfoDiffusionRepository;
import com.holzyn.actor.domain.world.repository.ActorInfoPacketRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.world.service.InfoPerceptionService.PerceivedPacket;
import com.holzyn.actor.domain.world.service.InfoPerceptionService.VisibilityCondition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 信息感知三层过滤 + 【世界动态】段单元测试（世界演化与游戏时间系统 M5，domain/world/service）。
 * <p>职责：验证——① 双轨区域匹配（地点名精确 → affected_area 原文包含兜底）；② 社会层
 * 空则放行 + 或关系（职业/归属/地点/特殊头衔）；③ 空间层（未扩散到达不可见）+ 社会层
 * （不匹配不可见）；④ 认知层失真接入（失真文本随注入）；⑤ 排序（到达新→旧 + 可信度降序）与
 * 条数上限（≤inject-max-packets）；⑥ 【世界动态】段渲染（格式/截断/开关/预算）。</p>
 */
class InfoPerceptionServiceTest {

    private ActorCharacterRepository characterRepository;
    private ActorCharacterRepository npcRepository;
    private WorldLocationRepository locationRepository;
    private ActorInfoPacketRepository packetRepository;
    private ActorInfoDiffusionRepository diffusionRepository;
    private InfoDiffusionService diffusionService;
    private InfoDistortionService distortionService;
    private WorldInfoProperties props;
    private InfoPerceptionService service;

    @BeforeEach
    void setUp() {
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        npcRepository = Mockito.mock(ActorCharacterRepository.class);
        locationRepository = Mockito.mock(WorldLocationRepository.class);
        packetRepository = Mockito.mock(ActorInfoPacketRepository.class);
        diffusionRepository = Mockito.mock(ActorInfoDiffusionRepository.class);
        diffusionService = Mockito.mock(InfoDiffusionService.class);
        distortionService = Mockito.mock(InfoDistortionService.class);
        props = new WorldInfoProperties();
        service = new InfoPerceptionService(characterRepository, npcRepository, locationRepository,
                packetRepository, diffusionRepository, diffusionService, distortionService, props,
                new ObjectMapper());

        when(locationRepository.findByProjectId(1L)).thenReturn(List.of(
                location(1L, "集市"), location(2L, "渡口")));
        when(diffusionService.regionsOfProject(1L)).thenReturn(List.of("集市", "渡口"));
        when(diffusionService.coordinatesOf(1L)).thenReturn(Map.of(
                "集市", new double[]{0, 0}, "渡口", new double[]{500, 0}));
        when(diffusionService.parseStringList(any())).thenReturn(List.of("商队"));
        when(packetRepository.findByProjectIdAndStatus(any(), any())).thenReturn(List.of());
    }

    // ==================== 静态纯逻辑 ====================

    @Test
    void matchedRegions_doubleTrack() {
        Map<Long, String> idToName = Map.of(1L, "集市");
        // ① locationId 精确
        assertEquals(List.of("集市"),
                InfoPerceptionService.matchedRegions("集市", 1L, List.of("集市", "渡口"), idToName));
        // ① location 字符串精确
        assertEquals(List.of("渡口"),
                InfoPerceptionService.matchedRegions("渡口", null, List.of("集市", "渡口"), idToName));
        // ② 精确未命中 → 原文包含兜底
        assertEquals(List.of("集市"),
                InfoPerceptionService.matchedRegions("青禾村集市一带", null, List.of("集市", "渡口"), idToName));
        // 无匹配
        assertTrue(InfoPerceptionService.matchedRegions("未知村", null, List.of("集市"), idToName).isEmpty());
        assertTrue(InfoPerceptionService.matchedRegions(null, null, List.of("集市"), idToName).isEmpty());
    }

    @Test
    void socialPass_emptyOrEmptyFieldsPass() {
        assertTrue(InfoPerceptionService.socialPass(null, npc("特殊", "沈砚秋", "村长", null, null, "祠堂")));
        assertTrue(InfoPerceptionService.socialPass(
                new VisibilityCondition(List.of(), List.of(), List.of()), npc("特殊", "沈砚秋", "村长", null, null, "祠堂")));
    }

    @Test
    void socialPass_orRelationshipAndSpecialTitle() {
        // 普通职业命中
        assertTrue(InfoPerceptionService.socialPass(
                new VisibilityCondition(List.of("货郎"), List.of(), List.of()),
                npc("普通", "赵六", null, "货郎", "青禾村", "集市")));
        // 特殊头衔命中
        assertTrue(InfoPerceptionService.socialPass(
                new VisibilityCondition(List.of("村长"), List.of(), List.of()),
                npc("特殊", "沈砚秋", "村长", null, null, "祠堂")));
        // OR：职业未命中但地点命中
        assertTrue(InfoPerceptionService.socialPass(
                new VisibilityCondition(List.of("铁匠"), List.of(), List.of("集市")),
                npc("普通", "赵六", null, "货郎", null, "集市")));
        // 归属命中
        assertTrue(InfoPerceptionService.socialPass(
                new VisibilityCondition(List.of(), List.of("青禾村"), List.of()),
                npc("普通", "赵六", null, "货郎", "青禾村", "集市")));
        // 全未命中 → 拦截
        assertFalse(InfoPerceptionService.socialPass(
                new VisibilityCondition(List.of("铁匠"), List.of(), List.of("山神庙")),
                npc("普通", "赵六", null, "货郎", "青禾村", "集市")));
    }

    // ==================== 三层过滤 ====================

    /** 构造特殊 NPC（在集市）。 */
    private void stubSpecialAtMarket() {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(1L);
        ch.setName("沈砚秋");
        ch.setTitle("村长");
        ch.setLocation("集市");
        ch.setLocationId(1L);
        when(characterRepository.findById(1L)).thenReturn(Optional.of(ch));
    }

    /** 构造信息包（origin=集市，visible 默认放行）。 */
    private ActorInfoPacket packet(Long id, String origin, Long validUntilDay) {
        ActorInfoPacket p = new ActorInfoPacket();
        p.setId(id);
        p.setFrameId(1L);
        p.setProjectId(1L);
        p.setInfoId("I-1-00" + id + "-1");
        p.setOrigin(origin);
        p.setContent("集市粮价飞涨，有人开始囤粮");
        p.setReliability(0.8);
        p.setValidUntilDay(validUntilDay);
        p.setStatus("active");
        return p;
    }

    /** 构造扩散行。 */
    private ActorInfoDiffusion row(String region, long reachedAt, String relayed) {
        ActorInfoDiffusion d = new ActorInfoDiffusion();
        d.setRegion(region);
        d.setReachedGameSecond(reachedAt);
        d.setRelayedBy(relayed);
        return d;
    }

    @Test
    void visiblePackets_spatialSocialCognitivePipeline() {
        stubSpecialAtMarket();
        ActorInfoPacket p = packet(1L, "集市", 5L);
        when(packetRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(p));
        when(diffusionRepository.findByPacketId(1L)).thenReturn(List.of(row("集市", 90000L, "商队")));
        when(distortionService.distortForNpc(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("据说集市粮价飞涨，有人开始囤粮");

        List<PerceivedPacket> visible = service.visiblePackets(1L, "npc:1", 95000L);

        assertEquals(1, visible.size());
        PerceivedPacket v = visible.get(0);
        assertEquals("集市", v.region());
        assertEquals("据说集市粮价飞涨，有人开始囤粮", v.perceivedText(), "认知失真文本随注入");
        assertEquals("商队", v.relayedBy());
    }

    @Test
    void visiblePackets_spatialBlockWhenNotReached() {
        stubSpecialAtMarket();
        ActorInfoPacket p = packet(1L, "集市", 5L);
        when(packetRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(p));
        // 扩散只到渡口（NPC 在集市）→ 空间层拦截
        when(diffusionRepository.findByPacketId(1L)).thenReturn(List.of(row("渡口", 90000L, "信使")));

        assertTrue(service.visiblePackets(1L, "npc:1", 95000L).isEmpty(), "未扩散到所在地不可见");
    }

    @Test
    void visiblePackets_expiredPacketInvisible() {
        stubSpecialAtMarket();
        ActorInfoPacket p = packet(1L, "集市", 0L); // 第 0 日过期
        when(packetRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(p));
        when(diffusionRepository.findByPacketId(1L)).thenReturn(List.of(row("集市", 90000L, "商队")));

        assertTrue(service.visiblePackets(1L, "npc:1", 95000L).isEmpty(), "过期包不可感知");
    }

    @Test
    void visiblePackets_nullGameSecondSkipsExpiryFilter() {
        // ⚠️ 冒烟抓修：npc-visible 预览传 gameSecond=null → 曾因 day=Long.MAX 判全部包过期 → 恒空；
        // 修复：gameSecond 为空不做过期过滤（调试预览语义）
        stubSpecialAtMarket();
        ActorInfoPacket p = packet(1L, "集市", 0L); // 已过期
        when(packetRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(p));
        when(diffusionRepository.findByPacketId(1L)).thenReturn(List.of(row("集市", 90000L, "商队")));
        when(distortionService.distortForNpc(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("传闻");

        List<PerceivedPacket> visible = service.visiblePackets(1L, "npc:1", null);
        assertEquals(1, visible.size(), "gameSecond=null 不做过期过滤（预览应可见）");
    }

    @Test
    void visiblePackets_socialBlockWhenConditionNotMet() {
        stubSpecialAtMarket();
        ActorInfoPacket p = packet(1L, "集市", 5L);
        p.setVisibilityConditionJson("{\"profession\":[\"铁匠\"],\"affiliation\":[],\"location\":[]}");
        when(packetRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(p));
        when(diffusionRepository.findByPacketId(1L)).thenReturn(List.of(row("集市", 90000L, "商队")));

        assertTrue(service.visiblePackets(1L, "npc:1", 95000L).isEmpty(),
                "社会层：村长不匹配铁匠条件 → 不知晓");
    }

    @Test
    void visiblePackets_socialPassWhenProfessionMatchesCrowd() {
        ActorCharacter npc = new ActorCharacter();
        npc.setId(5L);
        npc.setName("赵六");
        npc.setOccupation("货郎");
        npc.setLocation("集市");
        npc.setLocationId(1L);
        // G1a：单表后身份解析只查 actor_profile（InfoPerceptionService.resolve 用 characterRepository）；
        // 行内 type=common 决定「职业/归属」社会层字段口径
        npc.setType("common");
        when(characterRepository.findById(5L)).thenReturn(Optional.of(npc));
        ActorInfoPacket p = packet(1L, "集市", 5L);
        p.setVisibilityConditionJson("{\"profession\":[\"货郎\"],\"affiliation\":[],\"location\":[]}");
        when(packetRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(p));
        when(diffusionRepository.findByPacketId(1L)).thenReturn(List.of(row("集市", 90000L, "商队")));
        when(distortionService.distortForNpc(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("货郎听到的版本");

        List<PerceivedPacket> visible = service.visiblePackets(1L, "npc:5", 95000L);
        assertEquals(1, visible.size(), "职业命中社会层放行");
        assertEquals("货郎听到的版本", visible.get(0).perceivedText());
    }

    @Test
    void visiblePackets_ordersByRecencyAndLimits() {
        stubSpecialAtMarket();
        when(packetRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(
                packet(1L, "集市", 9L), packet(2L, "集市", 9L), packet(3L, "集市", 9L), packet(4L, "集市", 9L)));
        when(diffusionRepository.findByPacketId(1L)).thenReturn(List.of(row("集市", 100000L, "商队")));
        when(diffusionRepository.findByPacketId(2L)).thenReturn(List.of(row("集市", 95000L, "信使")));
        when(diffusionRepository.findByPacketId(3L)).thenReturn(List.of(row("集市", 90000L, "行脚商贩")));
        when(diffusionRepository.findByPacketId(4L)).thenReturn(List.of(row("集市", 120000L, "驿卒")));
        when(distortionService.distortForNpc(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("传闻");

        List<PerceivedPacket> visible = service.visiblePackets(1L, "npc:1", 130000L);
        assertEquals(3, visible.size(), "超过上限截断到 inject-max-packets=3");
        assertEquals(Long.valueOf(120000L), visible.get(0).reachedGameSecond(), "最近到达优先");
        assertEquals(Long.valueOf(100000L), visible.get(1).reachedGameSecond());
    }

    // ==================== 【世界动态】段 ====================

    @Test
    void worldDynamicsSegment_formatsAndRespectsBudget() {
        stubSpecialAtMarket();
        ActorInfoPacket p = packet(1L, "集市", 5L);
        when(packetRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(p));
        when(diffusionRepository.findByPacketId(1L)).thenReturn(List.of(row("集市", 90000L, "商队")));
        when(distortionService.distortForNpc(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("据说集市粮价飞涨，有人开始囤粮");

        String seg = service.worldDynamicsSegment(1L, "npc:1", 95000L);
        assertTrue(seg.startsWith("【世界动态】"), "段头: " + seg);
        assertTrue(seg.contains("集市"), "含到达区域");
        assertTrue(seg.contains("经商队传来"), "含传播节点");
        assertTrue(seg.length() <= 300 + 20, "预算内");

        // 注入开关关 → 空段
        props.setInjectEnabled(false);
        assertEquals("", service.worldDynamicsSegment(1L, "npc:1", 95000L));
    }

    // ==================== 辅助构造 ====================

    private ActorWorldLocation location(Long id, String name) {
        ActorWorldLocation l = new ActorWorldLocation();
        l.setId(id);
        l.setProjectId(1L);
        l.setName(name);
        return l;
    }

    private InfoPerceptionService.NpcInfo npc(String type, String name, String title,
                                              String occupation, String affiliation, String location) {
        return new InfoPerceptionService.NpcInfo(type.equals("特殊") ? "npc:1" : "npc:5",
                name, title, occupation, affiliation, location, null);
    }
}
