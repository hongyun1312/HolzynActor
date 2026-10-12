package com.holzyn.actor.domain.growth.mask;

import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.evolution.GrowthShiftLogRepository;
import com.holzyn.actor.domain.growth.metrics.GrowthMetricsService;
import com.holzyn.actor.domain.growth.persona.PersonaDims;
import com.holzyn.actor.domain.growth.persona.PersonaJson;
import com.holzyn.actor.domain.growth.persona.PersonaProfile;
import com.holzyn.actor.domain.growth.stage.GrowthStage;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.repository.ActorNpcStateRepository;
import com.holzyn.actor.domain.sandbox.service.WorldSandboxSseHub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 社会面具服务单元测试（O6 轨道 G3 验收③/⑤）。
 *
 * <p>验收口径：</p>
 * <ul>
 *   <li><b>③ 多情境叠加取最大抑制</b>：对权威 + 公共场合同时在场 → 取两者高者；再受阶段上限
 *       （CHILD 0.3）与全局封顶（0.8）钳制；表优先 + 档案兜底；破绽降幅作用于生效抑制；</li>
 *   <li><b>⑤ 双段分离</b>：真实 PAD 与表达 PAD 同时注入且数值分离（真实×（1−抑制））；
 *       独处/私人场所=真实 PAD、不注入双段（零噪音）；表达 PAD 写回 actor_state.expressed_*。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MaskServiceTest {

    @Mock
    private MaskContextSource contextSource;
    @Mock
    private MaskBreachDecision breachDecision;
    @Mock
    private ActorCharacterRepository characterRepository;
    @Mock
    private ActorNpcStateRepository stateRepository;
    @Mock
    private ActorGrowthMaskRepository maskRepository;
    @Mock
    private GrowthShiftLogRepository shiftLogRepository;
    @Mock
    private GrowthMetricsService growthMetrics;
    @Mock
    private WorldSandboxSseHub sandboxSseHub;

    private GrowthProperties properties;
    private MaskService service;

    @BeforeEach
    void setUp() {
        properties = new GrowthProperties();
        service = new MaskService(contextSource, breachDecision, characterRepository, stateRepository,
                maskRepository, shiftLogRepository, properties, growthMetrics, sandboxSseHub);
    }

    private ActorCharacter ch(Long id, String stage) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setName("测试角色");
        c.setProjectId(1L);
        c.setStage(stage);
        c.setAge(30);
        return c;
    }

    /** 带 ⑪ 面具维的档案：对权威 0.8 / 公共场合 0.4 抑制；对权威禁止「顶撞上官」。 */
    private ActorCharacter chWithMaskPersona(Long id, String stage) {
        ActorCharacter c = ch(id, stage);
        PersonaProfile p = PersonaProfile.empty(1L, id);
        PersonaDims.Mask m = new PersonaDims.Mask();
        m.getContextSuppression().put(MaskContextCode.BEFORE_AUTHORITY, 0.8);
        m.getContextSuppression().put(MaskContextCode.PUBLIC_PLACE, 0.4);
        m.getForbiddenExpressions().put(MaskContextCode.BEFORE_AUTHORITY, List.of("顶撞上官、咒骂上官"));
        m.getAllowedExpressions().put(MaskContextCode.BEFORE_AUTHORITY, List.of("恭敬应答"));
        p.getDims().setMask(m);
        c.setPersonaJson(PersonaJson.write(p));
        return c;
    }

    private ActorNpcState state(Long id, double p, double a, double d) {
        ActorNpcState s = new ActorNpcState();
        s.setProjectId(1L);
        s.setNpcRef("npc:" + id);
        s.setEmotionPleasure(p);
        s.setEmotionArousal(a);
        s.setEmotionDominance(d);
        return s;
    }

    // ==================== 验收③：多情境叠加取最大抑制 ====================

    @Test
    @DisplayName("验收③：多情境取最大（对权威 0.6 + 公共场合 0.3 → 0.6），表优先")
    void effectiveSuppressionTakesMaxAcrossContexts() {
        ActorCharacter adult = ch(1L, GrowthStage.PRIME.name());
        ActorGrowthMask authority = new ActorGrowthMask();
        authority.setEffectiveSuppression(0.6);
        ActorGrowthMask publicPlace = new ActorGrowthMask();
        publicPlace.setEffectiveSuppression(0.3);
        when(maskRepository.findFirstByProjectIdAndNpcRefAndContextCode(1L, "npc:1",
                MaskContextCode.BEFORE_AUTHORITY)).thenReturn(Optional.of(authority));
        when(maskRepository.findFirstByProjectIdAndNpcRefAndContextCode(1L, "npc:1",
                MaskContextCode.PUBLIC_PLACE)).thenReturn(Optional.of(publicPlace));
        double sup = service.effectiveSuppression(1L, adult,
                List.of(MaskContextCode.BEFORE_AUTHORITY, MaskContextCode.PUBLIC_PLACE), 0);
        assertEquals(0.6, sup, 0.0001);
    }

    @Test
    @DisplayName("验收③：阶段上限钳制（CHILD 上限 0.3 → 0.8 抑制被压到 0.3）")
    void effectiveSuppressionStageCap() {
        ActorCharacter child = ch(2L, GrowthStage.CHILD.name());
        ActorGrowthMask authority = new ActorGrowthMask();
        authority.setEffectiveSuppression(0.8);
        when(maskRepository.findFirstByProjectIdAndNpcRefAndContextCode(1L, "npc:2",
                MaskContextCode.BEFORE_AUTHORITY)).thenReturn(Optional.of(authority));
        double sup = service.effectiveSuppression(1L, child,
                List.of(MaskContextCode.BEFORE_AUTHORITY), 0);
        assertEquals(0.3, sup, 0.0001);
    }

    @Test
    @DisplayName("验收③：全局封顶（maxSuppression=0.4 → 0.6 抑制被压到 0.4）")
    void effectiveSuppressionGlobalCap() {
        properties.getMask().setMaxSuppression(0.4);
        ActorCharacter adult = ch(3L, GrowthStage.PRIME.name());
        ActorGrowthMask authority = new ActorGrowthMask();
        authority.setEffectiveSuppression(0.6);
        when(maskRepository.findFirstByProjectIdAndNpcRefAndContextCode(1L, "npc:3",
                MaskContextCode.BEFORE_AUTHORITY)).thenReturn(Optional.of(authority));
        double sup = service.effectiveSuppression(1L, adult,
                List.of(MaskContextCode.BEFORE_AUTHORITY), 0);
        assertEquals(0.4, sup, 0.0001);
    }

    @Test
    @DisplayName("验收③：无运行态行 → 档案 ⑪ 维兜底；破绽降幅作用于生效抑制（0.5−0.2=0.3）")
    void suppressionFallsBackToPersonaAndBreachDrop() {
        ActorCharacter adult = chWithMaskPersona(4L, GrowthStage.PRIME.name());
        when(maskRepository.findFirstByProjectIdAndNpcRefAndContextCode(eq(1L), eq("npc:4"), anyString()))
                .thenReturn(Optional.empty());
        // 档案兜底：对陌生人 0.5（本档案未配置 → 0；单独建档验证兜底路径）
        double fallback = service.suppressionOf(1L, adult, MaskContextCode.BEFORE_STRANGER);
        assertEquals(0.0, fallback, 0.0001);
        // 档案兜底：对权威 0.8，破绽降 0.2 → 0.6
        double withBreach = service.effectiveSuppression(1L, adult,
                List.of(MaskContextCode.BEFORE_AUTHORITY), 0.2);
        assertEquals(0.6, withBreach, 0.0001);
    }

    @Test
    @DisplayName("验收③：未知情境码忽略（零抑制），INFANT 无面具")
    void unknownContextIgnoredInfantNoMask() {
        ActorCharacter infant = ch(5L, GrowthStage.INFANT.name());
        assertEquals(0.0, service.effectiveSuppression(1L, infant,
                List.of("not_a_context", MaskContextCode.BEFORE_AUTHORITY), 0), 0.0001);
    }

    // ==================== 验收⑤：双段分离（真实 vs 表达 PAD） ====================

    @Test
    @DisplayName("验收⑤：对权威 + 公共场合 → 双段注入，真实 PAD 与表达 PAD 数值分离，写回 expressed_*")
    void dualSegmentSeparatesRealAndExpressed() {
        ActorCharacter adult = chWithMaskPersona(6L, GrowthStage.PRIME.name());
        ActorNpcState st = state(6L, 0.8, 0.6, 0.5);
        when(characterRepository.findById(6L)).thenReturn(java.util.Optional.of(adult));
        when(stateRepository.findByProjectIdAndNpcRef(1L, "npc:6")).thenReturn(Optional.of(st));
        when(contextSource.resolve(1L, "npc:6", List.of("npc:7"), 1000L))
                .thenReturn(List.of(MaskContextCode.BEFORE_AUTHORITY, MaskContextCode.PUBLIC_PLACE));
        when(breachDecision.detect(1L, "npc:6", "对话文本", 1000L)).thenReturn(MaskBreachDecision.BreachResult.none());
        when(maskRepository.findFirstByProjectIdAndNpcRefAndContextCode(eq(1L), eq("npc:6"), anyString()))
                .thenReturn(Optional.empty());

        String seg = service.dualSegment(1L, "npc:6", 1000L, List.of("npc:7"), "对话文本");

        // 抑制 0.8 → 表达 PAD = 真实 × 0.2
        assertTrue(seg.contains("【内心与表达】"));
        assertTrue(seg.contains("内心真实情绪"));
        assertTrue(seg.contains("愉悦 +0.80"));
        assertTrue(seg.contains("表达情绪"));
        assertTrue(seg.contains("愉悦 +0.16"));
        assertTrue(seg.contains("唤醒 +0.12"));
        assertTrue(seg.contains("支配 +0.10"));
        assertTrue(seg.contains("抑制强度 80%"));
        assertTrue(seg.contains("禁止表达"));
        assertTrue(seg.contains("顶撞上官"));
        assertTrue(seg.contains("允许表达"));
        assertTrue(seg.contains("恭敬应答"));
        // 表达 PAD 写回 actor_state（用户决策：表达入口现算 + 写回持久化）
        verify(stateRepository).save(st);
        assertEquals(0.16, st.getExpressedPleasure(), 0.0001);
        assertEquals(0.12, st.getExpressedArousal(), 0.0001);
        assertEquals(0.10, st.getExpressedDominance(), 0.0001);
        assertTrue(st.getMaskSnapshotJson() != null && st.getMaskSnapshotJson().contains("suppression"));
    }

    @Test
    @DisplayName("验收⑤：独处（私人场所，无抑制）→ 表达=真实 PAD、不注入双段（零噪音）")
    void aloneNoMaskNoSegment() {
        ActorCharacter adult = chWithMaskPersona(7L, GrowthStage.PRIME.name());
        ActorNpcState st = state(7L, 0.8, 0.6, 0.5);
        when(characterRepository.findById(7L)).thenReturn(java.util.Optional.of(adult));
        when(stateRepository.findByProjectIdAndNpcRef(1L, "npc:7")).thenReturn(Optional.of(st));
        when(contextSource.resolve(1L, "npc:7", null, 2000L))
                .thenReturn(List.of(MaskContextCode.PRIVATE_PLACE));
        when(breachDecision.detect(1L, "npc:7", null, 2000L)).thenReturn(MaskBreachDecision.BreachResult.none());
        when(maskRepository.findFirstByProjectIdAndNpcRefAndContextCode(eq(1L), eq("npc:7"), anyString()))
                .thenReturn(Optional.empty());

        String seg = service.dualSegment(1L, "npc:7", 2000L, null, null);

        assertEquals("", seg);
        // 表达 PAD = 真实 PAD（面具放下）
        assertEquals(0.8, st.getExpressedPleasure(), 0.0001);
        assertEquals(0.6, st.getExpressedArousal(), 0.0001);
        assertEquals(0.5, st.getExpressedDominance(), 0.0001);
    }

    @Test
    @DisplayName("验收⑤：面具总开关关闭 → 双段不注入（表达=真实）")
    void maskDisabledNoSegment() {
        properties.getMask().setEnabled(false);
        ActorCharacter adult = chWithMaskPersona(8L, GrowthStage.PRIME.name());
        ActorNpcState st = state(8L, 0.8, 0.6, 0.5);
        // 面具关闭：computeExpressed 直接返回无面具结果，不触碰仓库（零阻断）
        String seg = service.dualSegment(1L, "npc:8", 3000L, List.of("npc:7"), "文本");
        assertEquals("", seg);
        assertFalse(st.getMaskSnapshotJson() != null);
    }
}
