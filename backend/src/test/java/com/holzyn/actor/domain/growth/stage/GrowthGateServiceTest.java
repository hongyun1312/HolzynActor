package com.holzyn.actor.domain.growth.stage;

import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.persona.PersonaDims;
import com.holzyn.actor.domain.growth.persona.PersonaProfile;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.repository.ActorNpcStateRepository;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 阶段硬门控唯一策略源单元测试（O6 轨道 G2）。
 * <p>职责：机能曲线端点与修正派生、INFANT/ELDER 门控语义（能力允许集 + HARD 禁止 + 软约束）、
 * 道德底线/体质弱点进 forbidden、无阶段宽松兜底、允许能力表优先 + 代码兜底。</p>
 */
@ExtendWith(MockitoExtension.class)
class GrowthGateServiceTest {

    @Mock
    private GrowthStageGateRepository gateRepository;
    @Mock
    private GrowthStageConstraintRepository constraintRepository;
    @Mock
    private ActorCharacterRepository characterRepository;
    @Mock
    private ActorNpcStateRepository stateRepository;

    private GrowthProperties properties;
    private GrowthGateService service;

    @BeforeEach
    void setUp() {
        properties = new GrowthProperties();
        service = new GrowthGateService(gateRepository, constraintRepository,
                characterRepository, stateRepository, properties);
    }

    private ActorCharacter ch(Long id, String name, String stage, Integer age) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setName(name);
        c.setProjectId(1L);
        c.setStage(stage);
        c.setAge(age);
        return c;
    }

    // ==================== 机能曲线 ====================

    @Test
    @DisplayName("机能曲线：峰值 30 岁 1.0，幼年/老年递减，90+ 保底 0.10")
    void capacityCurve() {
        assertEquals(0.15, service.capacityOf(0), 1e-9);
        assertEquals(0.50, service.capacityOf(12), 1e-9);
        assertEquals(0.75, service.capacityOf(18), 1e-9);
        assertEquals(1.00, service.capacityOf(30), 1e-9);
        assertEquals(0.95, service.capacityOf(45), 1e-9);
        assertEquals(0.78, service.capacityOf(60), 1e-9);
        assertEquals(0.50, service.capacityOf(75), 1e-9);
        assertEquals(0.25, service.capacityOf(90), 1e-9);
        assertTrue(service.capacityOf(120) <= 0.10 + 1e-9, "高龄保底 0.10");
        assertEquals(service.capacityOf(0), service.capacityOf(-5), "负数按 0");
    }

    @Test
    @DisplayName("年龄修正：峰值 1.0 时全倍率≈1.0；耄耋 0.25 时恢复/精力降、感官升、疾病风险升")
    void ageModifiers() {
        Map<String, Double> peak = service.ageModifiers(1.0);
        assertEquals(1.3, peak.get("recoverScale"), 1e-9);
        assertEquals(0.6, peak.get("energyScale"), 1e-9);
        assertEquals(1.0, peak.get("diseaseRisk"), 1e-9);

        Map<String, Double> old = service.ageModifiers(0.25);
        assertEquals(0.7, old.get("recoverScale"), 1e-9);
        assertEquals(1.2375, old.get("energyScale"), 1e-9);
        assertEquals(1.45, old.get("coldSens"), 1e-9);
        assertEquals(1.9, old.get("diseaseRisk"), 1e-9);
        assertTrue(old.get("energyScale") > peak.get("energyScale"), "年老精力耗得慢（需求低）");
        assertTrue(old.get("coldSens") > peak.get("coldSens"), "年老畏寒");
        assertTrue(old.get("diseaseRisk") > peak.get("diseaseRisk"), "年老易病");
    }

    // ==================== 硬门控语义 ====================

    @Test
    @DisplayName("INFANT：无语言/无行走能力，禁止项含婴儿硬约束，无对话能力")
    void infantGate() {
        ActorCharacter ch = ch(1L, "小宝", "INFANT", 1);
        when(gateRepository.findByStageOrderBySortOrderAsc("INFANT"))
                .thenReturn(List.of(row("INFANT", "DIALOGUE_OUTPUT", 1),
                        row("INFANT", "WALK", 1)));
        when(constraintRepository.findByStageOrderByKindAscSortOrderAsc("INFANT"))
                .thenReturn(List.of(constraint("INFANT", GrowthStageConstraint.KIND_HARD,
                        "独自出门", "婴儿不能独自出门"),
                        constraint("INFANT", GrowthStageConstraint.KIND_SOFT,
                        "需全程照护", "需全程照护")));
        HardGateResult gate = service.gate(ch, null, null);
        assertEquals("INFANT", gate.stage());
        assertTrue(gate.allows("DIALOGUE_OUTPUT"));
        assertTrue(gate.allows("WALK"));
        assertTrue(gate.denies("HEAVY_LABOR"), "INFANT 缺省禁止重体力");
        assertTrue(gate.denies("FINANCIAL_CONTROL"), "INFANT 缺省禁止理财");
        assertTrue(gate.forbids("独自出门"));
        assertEquals(1, gate.softConstraints().size());
    }

    @Test
    @DisplayName("ELDER：禁止重体力（HARD 约束）+ 健康告急追加卧床 + 软约束注入")
    void elderGateWithHealth() {
        ActorCharacter ch = ch(2L, "老张", "ELDER", 70);
        when(gateRepository.findByStageOrderBySortOrderAsc("ELDER"))
                .thenReturn(List.of(row("ELDER", "WALK", 1), row("ELDER", "TALK", 1)));
        when(constraintRepository.findByStageOrderByKindAscSortOrderAsc("ELDER"))
                .thenReturn(List.of(constraint("ELDER", GrowthStageConstraint.KIND_HARD,
                        "重体力劳作（挑担/垦荒/伐木）", "老年期不宜重体力"),
                        constraint("ELDER", GrowthStageConstraint.KIND_SOFT,
                        "夜间视物不清，不宜赶夜路", "感官退化")));
        ActorNpcState state = new ActorNpcState();
        state.setHealth(25);
        HardGateResult gate = service.gate(ch, state, null);
        assertTrue(gate.forbids("重体力劳作（挑担/垦荒/伐木）"));
        assertTrue(gate.forbids("强撑病体外出劳作"), "健康告急追加卧床禁止");
        assertEquals(1, gate.softConstraints().size());
        assertTrue(gate.allows("WALK"));
    }

    @Test
    @DisplayName("道德底线（⑤ 维）与体质弱点（② 维）进 forbidden")
    void moralBottomLineAndPhysioWeakness() {
        ActorCharacter ch = ch(3L, "沈砚秋", "PRIME", 35);
        when(gateRepository.findByStageOrderBySortOrderAsc("PRIME")).thenReturn(List.of());
        when(constraintRepository.findByStageOrderByKindAscSortOrderAsc("PRIME")).thenReturn(List.of());
        PersonaProfile persona = PersonaProfile.empty(1L, 3L);
        PersonaDims.Values values = new PersonaDims.Values();
        values.setMoralBottomLines(List.of("绝不做伤天害理之事"));
        persona.getDims().setValues(values);
        PersonaDims.Appearance app = new PersonaDims.Appearance();
        app.setConstitutionWeaknesses(List.of("左腿有旧伤，阴雨天酸痛"));
        persona.getDims().setAppearance(app);
        HardGateResult gate = service.gate(ch, null, persona);
        assertTrue(gate.forbids("绝不做伤天害理之事"), "道德底线必须进硬门控");
        assertTrue(gate.forbidden().stream().anyMatch(f -> f.what().contains("久站远行")),
                "体质弱点映射为具体越界项");
        // 表空 → 代码默认兜底（PRIME 全能力）
        assertTrue(gate.allows("HEAVY_LABOR"));
        assertTrue(gate.allows("LEGACY_TRANSFER"));
    }

    @Test
    @DisplayName("无阶段/无角色：宽松兜底（不禁止任何行为，机能 1.0）")
    void lenientFallback() {
        when(characterRepository.findById(99L)).thenReturn(Optional.empty());
        HardGateResult gate = service.gate(1L, "npc:99");
        assertTrue(gate.allows("HEAVY_LABOR"), "宽松兜底允许一切");
        assertEquals(1.0, gate.capacity(), 1e-9);
        assertTrue(gate.forbidden().isEmpty());
    }

    @Test
    @DisplayName("允许能力表优先：表有行用表，表空用代码默认")
    void allowedAbilitiesTablePriority() {
        when(gateRepository.findByStageOrderBySortOrderAsc("YOUTH"))
                .thenReturn(List.of(row("YOUTH", "HEAVY_LABOR", 1)));
        List<String> fromTable = service.resolveAllowedAbilities(GrowthStage.YOUTH);
        assertEquals(List.of("HEAVY_LABOR"), fromTable, "表优先");

        when(gateRepository.findByStageOrderBySortOrderAsc("PRIME")).thenReturn(List.of());
        List<String> fallback = service.resolveAllowedAbilities(GrowthStage.PRIME);
        assertFalse(fallback.isEmpty(), "代码默认兜底非空");
        assertTrue(fallback.contains("HEAVY_LABOR"));
    }

    @Test
    @DisplayName("硬门控注入文案：阶段/能力清单/禁止受限齐全且不超上限")
    void renderStageBlock() {
        ActorCharacter ch = ch(4L, "老李", "ELDER", 72);
        when(characterRepository.findById(4L)).thenReturn(Optional.of(ch));
        when(gateRepository.findByStageOrderBySortOrderAsc("ELDER"))
                .thenReturn(List.of(row("ELDER", "WALK", 1), row("ELDER", "TALK", 1)));
        when(constraintRepository.findByStageOrderByKindAscSortOrderAsc("ELDER"))
                .thenReturn(List.of(constraint("ELDER", GrowthStageConstraint.KIND_HARD,
                        "重体力劳作", "老年期不宜")));
        when(stateRepository.findByProjectIdAndNpcRef(1L, "npc:4")).thenReturn(Optional.empty());
        String block = service.renderStageBlock(1L, "npc:4");
        assertNotNull(block);
        assertTrue(block.contains("老年期"));
        assertTrue(block.contains("你能做"));
        assertTrue(block.contains("硬性禁止"));
        assertTrue(block.length() <= service.constraintMaxChars() + 200);
        lenient(); // 保持 mockito 严格模式无未用桩
    }

    private static GrowthStageGate row(String stage, String ability, int allowed) {
        GrowthStageGate g = new GrowthStageGate();
        g.setStage(stage);
        g.setAbility(ability);
        g.setAllowed(allowed);
        return g;
    }

    private static GrowthStageConstraint constraint(String stage, String kind, String content, String promptText) {
        GrowthStageConstraint c = new GrowthStageConstraint();
        c.setStage(stage);
        c.setKind(kind);
        c.setContent(content);
        c.setPromptText(promptText);
        return c;
    }
}
