package com.holzyn.actor.domain.growth.mask;

import com.holzyn.actor.domain.growth.persona.PersonaDims;
import com.holzyn.actor.domain.growth.persona.PersonaJson;
import com.holzyn.actor.domain.growth.persona.PersonaProfile;
import com.holzyn.actor.domain.growth.stage.GrowthStage;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 面具破绽程序化检测单元测试（O6 轨道 G3 验收④）。
 *
 * <p>验收口径：触发物来源三路——档案 ⑪ 面具破绽条目（触发物 → 抑制降幅）、档案 ⑨ 创伤触发物
 * （默认 −0.3）、酒精状态（当前行动含饮酒词 → −0.3）；降幅求和钳制 [0, 0.8]；无命中=无破绽。</p>
 */
@ExtendWith(MockitoExtension.class)
class ProgrammaticMaskBreachDetectorTest {

    @Mock
    private ActorCharacterRepository characterRepository;

    private ProgrammaticMaskBreachDetector detector;

    @BeforeEach
    void setUp() {
        detector = new ProgrammaticMaskBreachDetector(characterRepository);
    }

    private ActorCharacter ch(Long id) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setName("测试角色");
        c.setProjectId(1L);
        c.setStage(GrowthStage.PRIME.name());
        return c;
    }

    /** 档案：⑪ 面具破绽（被提次子 −0.5）+ ⑨ 创伤（孩童咳嗽声）。 */
    private ActorCharacter chWithBreaches(Long id) {
        ActorCharacter c = ch(id);
        PersonaProfile p = PersonaProfile.empty(1L, id);
        PersonaDims.Mask m = new PersonaDims.Mask();
        PersonaDims.Breach b = new PersonaDims.Breach();
        b.setTrigger("被提次子");
        b.setSuppressionDelta(-0.5);
        m.getBreaches().add(b);
        p.getDims().setMask(m);
        PersonaDims.Trauma t = new PersonaDims.Trauma();
        t.setTriggers(List.of("孩童咳嗽声"));
        PersonaDims.Experience exp = new PersonaDims.Experience();
        exp.getTraumas().add(t);
        p.getDims().setExperience(exp);
        c.setPersonaJson(PersonaJson.write(p));
        return c;
    }

    @Test
    @DisplayName("验收④：档案 ⑪ 面具破绽触发物命中 → 命中，降幅取条目值 0.5")
    void maskBreachTriggerHits() {
        ActorCharacter c = chWithBreaches(1L);
        when(characterRepository.findById(1L)).thenReturn(java.util.Optional.of(c));
        var r = detector.detect(1L, "npc:1", "被提次子当众羞辱我", 1000L);
        assertTrue(r.triggered());
        assertEquals("被提次子", r.trigger());
        assertEquals(0.5, r.suppressionDelta(), 0.0001);
    }

    @Test
    @DisplayName("验收④：档案 ⑨ 创伤触发物命中 → 命中，默认降幅 0.3")
    void traumaTriggerHits() {
        ActorCharacter c = chWithBreaches(2L);
        when(characterRepository.findById(2L)).thenReturn(java.util.Optional.of(c));
        var r = detector.detect(1L, "npc:2", "门外传来孩童咳嗽声", 2000L);
        assertTrue(r.triggered());
        assertEquals("孩童咳嗽声", r.trigger());
        assertEquals(0.3, r.suppressionDelta(), 0.0001);
    }

    @Test
    @DisplayName("验收④：酒精状态命中（当前行动含饮酒词）→ 命中「酒意上头」降幅 0.3（无需文本）")
    void alcoholActivityHits() {
        ActorCharacter c = ch(3L);
        c.setCurrentActivity("在酒馆喝酒");
        when(characterRepository.findById(3L)).thenReturn(java.util.Optional.of(c));
        var r = detector.detect(1L, "npc:3", null, 3000L);
        assertTrue(r.triggered());
        assertEquals("酒意上头", r.trigger());
        assertEquals(0.3, r.suppressionDelta(), 0.0001);
    }

    @Test
    @DisplayName("验收④：无触发物命中 → 无破绽")
    void noTriggerNoBreach() {
        ActorCharacter c = chWithBreaches(4L);
        when(characterRepository.findById(4L)).thenReturn(java.util.Optional.of(c));
        var r = detector.detect(1L, "npc:4", "今天天气不错", 4000L);
        assertFalse(r.triggered());
    }

    @Test
    @DisplayName("验收④：多触发物降幅求和钳制 ≤0.8（0.5+0.6+0.3 → 0.8）")
    void breachDropCappedAt08() {
        ActorCharacter c = ch(5L);
        PersonaProfile p = PersonaProfile.empty(1L, 5L);
        PersonaDims.Mask m = new PersonaDims.Mask();
        PersonaDims.Breach b1 = new PersonaDims.Breach();
        b1.setTrigger("被提次子");
        b1.setSuppressionDelta(-0.5);
        PersonaDims.Breach b2 = new PersonaDims.Breach();
        b2.setTrigger("次子夭折");
        b2.setSuppressionDelta(-0.6);
        m.getBreaches().add(b1);
        m.getBreaches().add(b2);
        p.getDims().setMask(m);
        c.setPersonaJson(PersonaJson.write(p));
        c.setCurrentActivity("喝酒");
        when(characterRepository.findById(5L)).thenReturn(java.util.Optional.of(c));
        var r = detector.detect(1L, "npc:5", "被提次子夭折后", 5000L);
        assertTrue(r.triggered());
        assertEquals(0.8, r.suppressionDelta(), 0.0001);
    }

    @Test
    @DisplayName("验收④：档案缺失/角色缺失 → 无破绽（零阻断）")
    void missingDataNoBreach() {
        when(characterRepository.findById(6L)).thenReturn(java.util.Optional.empty());
        var r = detector.detect(1L, "npc:6", "被提次子", 6000L);
        assertFalse(r.triggered());
    }
}
