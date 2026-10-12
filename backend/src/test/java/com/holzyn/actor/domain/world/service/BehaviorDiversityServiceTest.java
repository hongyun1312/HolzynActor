package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.agent.runtime.AiIntensityGate;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.world.config.BehaviorDiversityProperties;
import com.holzyn.actor.domain.world.entity.ActorMacroDiversity;
import com.holzyn.actor.domain.world.entity.ActorMesoGroup;
import com.holzyn.actor.domain.world.entity.ActorMesoMembership;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroDelta;
import com.holzyn.actor.domain.world.repository.ActorMacroDiversityRepository;
import com.holzyn.actor.domain.world.repository.ActorMesoGroupRepository;
import com.holzyn.actor.domain.world.repository.ActorMesoMembershipRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldMacroDeltaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * 行为多样性注入器单元测试（宏观社会演化系统 M6.5b，domain/world/service，同包）。
 * <p>职责：验证三类噪声（OCEAN 偏好波动/情绪干扰/社会参照）、人格稳定度钳制、档位联动
 * （programmatic 关闭/light 弱幅）、衰减、注入段组装、记录落库、读接口。</p>
 */
class BehaviorDiversityServiceTest {

    private BehaviorDiversityProperties props;
    private ActorMacroDiversityRepository diversityRepository;
    private ActorMesoMembershipRepository membershipRepository;
    private ActorMesoGroupRepository groupRepository;
    private ActorWorldMacroDeltaRepository deltaRepository;
    private WorldClockService worldClockService;
    private NpcStateService npcStateService;
    private AiIntensityGate aiIntensityGate;
    private BehaviorDiversityService service;

    @BeforeEach
    void setUp() {
        props = new BehaviorDiversityProperties();
        diversityRepository = Mockito.mock(ActorMacroDiversityRepository.class);
        membershipRepository = Mockito.mock(ActorMesoMembershipRepository.class);
        groupRepository = Mockito.mock(ActorMesoGroupRepository.class);
        deltaRepository = Mockito.mock(ActorWorldMacroDeltaRepository.class);
        worldClockService = Mockito.mock(WorldClockService.class);
        service = new BehaviorDiversityService(props, diversityRepository, membershipRepository,
                groupRepository, deltaRepository, worldClockService, new ObjectMapper());
        npcStateService = Mockito.mock(NpcStateService.class);
        aiIntensityGate = Mockito.mock(AiIntensityGate.class);
        ReflectionTestUtils.setField(service, "npcStateService", npcStateService);
        ReflectionTestUtils.setField(service, "aiIntensityGate", aiIntensityGate);
        when(aiIntensityGate.intensityOf(anyLong())).thenReturn(AiIntensityGate.FULL);
        when(diversityRepository.save(any(ActorMacroDiversity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** 人格基线：actorRef 确定性派生（同一角色每次一致）；显式 traits 覆盖。 */
    @Test
    void normalizeTraitsStableAndOverridable() {
        Map<String, Double> a1 = BehaviorDiversityService.normalizeTraits(null, "special:1");
        Map<String, Double> a2 = BehaviorDiversityService.normalizeTraits(null, "special:1");
        assertEquals(a1, a2, "同一角色基线稳定");
        Map<String, Double> overridden = BehaviorDiversityService.normalizeTraits(
                Map.of("openness", 0.9, "conscientiousness", 0.8), "special:1");
        assertEquals(0.9, overridden.get("openness"), 0.001, "显式覆盖");
        assertEquals(0.8, overridden.get("conscientiousness"), 0.001);
        assertTrue(overridden.get("neuroticism") >= 0.0 && overridden.get("neuroticism") <= 1.0);
    }

    /** 偏好波动强度：基线×稳定度钳制×档位系数；高尽责性 → 波动小（不破坏长期一致性）。 */
    @Test
    void preferenceStrengthScalesWithConscientiousness() {
        Map<String, Double> low = Map.of("conscientiousness", 0.1);
        Map<String, Double> high = Map.of("conscientiousness", 0.9);
        double sLow = BehaviorDiversityService.calcPreferenceStrength(low, 0.5, 1.0);
        double sHigh = BehaviorDiversityService.calcPreferenceStrength(high, 0.5, 1.0);
        assertTrue(sLow > sHigh, "高尽责性角色波动应更小");
        assertEquals(0.5 * (1 - 0.1 * 0.7), sLow, 0.001);
        // light 档弱幅度
        double sLight = BehaviorDiversityService.calcPreferenceStrength(low, 0.5, 0.5);
        assertEquals(sLow * 0.5, sLight, 0.001);
    }

    /** 偏好方向：同一角色确定性一致（稳定基线）。 */
    @Test
    void preferenceBiasDeterministic() {
        Map<String, Double> t = BehaviorDiversityService.normalizeTraits(null, "special:7");
        assertEquals(BehaviorDiversityService.preferenceBias("special:7", t),
                BehaviorDiversityService.preferenceBias("special:7", t));
        assertNotNull(BehaviorDiversityService.preferenceBias("special:7", t));
    }

    /** 总开关关闭 → 零注入。 */
    @Test
    void disabledReturnsNull() {
        props.setEnabled(false);
        assertNull(service.inject(100L, "special:1", 1000L, null));
        verify(diversityRepository, never()).save(any(ActorMacroDiversity.class));
    }

    /** programmatic 档 → 零注入（纯程序化决策路径不注入噪声）。 */
    @Test
    void programmaticIntensityBlocked() {
        when(aiIntensityGate.intensityOf(anyLong())).thenReturn(AiIntensityGate.PROGRAMMATIC);
        assertNull(service.inject(100L, "special:1", 1000L, null));
        verify(diversityRepository, never()).save(any(ActorMacroDiversity.class));
    }

    /** 偏好通道：无状态无参照 → 仅偏好波动注入【状态涟漪】段 + 记录落库（channel=preference）。 */
    @Test
    void preferenceChannelInjectedAndRecorded() {
        when(npcStateService.stateFor(anyLong(), anyString())).thenReturn(null);
        when(membershipRepository.findByProjectIdAndActorRefAndStatus(anyLong(), anyString(), anyString()))
                .thenReturn(List.of());
        when(deltaRepository.findByProjectIdAndGameDayBetweenOrderByGameDayAsc(anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of());
        String seg = service.inject(100L, "special:1", 6L * 3600L, null);
        assertNotNull(seg, "应有偏好波动注入");
        assertTrue(seg.startsWith("【状态涟漪】"));
        var captor = org.mockito.ArgumentCaptor.forClass(ActorMacroDiversity.class);
        verify(diversityRepository).save(captor.capture());
        assertEquals("special:1", captor.getValue().getActorRef());
        assertEquals("preference", captor.getValue().getChannel());
    }

    /** 情绪通道：PAD 愉悦显著偏离基线 + 高情绪强度 → 情绪干扰注入。 */
    @Test
    void emotionChannelFromState() {
        ActorNpcState st = new ActorNpcState();
        st.setEmotionPleasure(-0.6);
        st.setEmotionIntensity(80);
        when(npcStateService.stateFor(anyLong(), anyString())).thenReturn(st);
        when(membershipRepository.findByProjectIdAndActorRefAndStatus(anyLong(), anyString(), anyString()))
                .thenReturn(List.of());
        when(deltaRepository.findByProjectIdAndGameDayBetweenOrderByGameDayAsc(anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of());
        String seg = service.inject(100L, "special:1", 6L * 3600L, null);
        assertNotNull(seg);
        assertTrue(seg.contains("情绪"), "段应含情绪干扰描述");
        assertTrue(seg.contains("保守"), "负愉悦 → 偏保守");
    }

    /** 社会参照：同组 2 人以上窗口内同类行为 → 从众文本；不足 2 人 → null。 */
    @Test
    void socialReferenceConformity() {
        // 本角色所属组 + 组内两名组员窗口内囤积
        ActorMesoMembership me = new ActorMesoMembership();
        me.setGroupId(5L);
        when(membershipRepository.findByProjectIdAndActorRefAndStatus(100L, "special:1", "active"))
                .thenReturn(List.of(me));
        ActorMesoGroup g = new ActorMesoGroup();
        g.setId(5L);
        g.setStatus("active");
        g.setName("王氏宗族");
        when(groupRepository.findById(5L)).thenReturn(Optional.of(g));
        ActorMesoMembership m2 = new ActorMesoMembership();
        m2.setActorRef("special:2");
        ActorMesoMembership m3 = new ActorMesoMembership();
        m3.setActorRef("special:3");
        when(membershipRepository.findByGroupIdAndStatusOrderByIdAsc(5L, "active"))
                .thenReturn(List.of(me, m2, m3));
        ActorWorldMacroDelta d2 = new ActorWorldMacroDelta();
        d2.setNpcRef("special:2");
        d2.setActionText("把粮囤起来");
        ActorWorldMacroDelta d3 = new ActorWorldMacroDelta();
        d3.setNpcRef("special:3");
        d3.setActionText("抢购存粮");
        when(deltaRepository.findByProjectIdAndGameDayBetweenOrderByGameDayAsc(anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of(d2, d3));
        String ref = service.socialReference(100L, "special:1", 10L * 86400L);
        assertNotNull(ref, "同组 2 人囤积应产生从众参照");
        assertTrue(ref.contains("囤积"), "从众方向=囤积");
        // 不足 socialMinActors → null
        when(deltaRepository.findByProjectIdAndGameDayBetweenOrderByGameDayAsc(anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of(d2));
        assertNull(service.socialReference(100L, "special:1", 10L * 86400L), "1 人同类行为不构成从众");
    }

    /** 读接口：按项目/角色过滤 + 上限。 */
    @Test
    void listVOsFiltersAndLimits() {
        when(diversityRepository.findByProjectIdOrderByIdDesc(100L)).thenReturn(List.of(new ActorMacroDiversity()));
        assertEquals(1, service.listVOs(100L, null, null).size());
        when(diversityRepository.findByProjectIdAndActorRefOrderByIdDesc(100L, "special:1"))
                .thenReturn(List.of(new ActorMacroDiversity(), new ActorMacroDiversity()));
        assertEquals(1, service.listVOs(100L, "special:1", 1).size(), "limit 截断");
        assertEquals(0, service.listVOs(null, null, null).size());
    }

    /** 注入记录：字段完整（channel/strength/source/expires/trace）。 */
    @Test
    void recordFieldsComplete() {
        when(npcStateService.stateFor(anyLong(), anyString())).thenReturn(null);
        when(membershipRepository.findByProjectIdAndActorRefAndStatus(anyLong(), anyString(), anyString()))
                .thenReturn(List.of());
        when(deltaRepository.findByProjectIdAndGameDayBetweenOrderByGameDayAsc(anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of());
        service.inject(100L, "special:9", 6L * 3600L, null);
        var captor = org.mockito.ArgumentCaptor.forClass(ActorMacroDiversity.class);
        verify(diversityRepository).save(captor.capture());
        ActorMacroDiversity d = captor.getValue();
        assertEquals("special:9", d.getActorRef());
        assertEquals("preference", d.getChannel());
        assertTrue(d.getStrength() > 0.0 && d.getStrength() <= 1.0);
        assertTrue(d.getExpiresGameSecond() > 6L * 3600L, "到期=注入时刻+半衰期窗口");
        assertNotNull(d.getContextJson());
    }
}
