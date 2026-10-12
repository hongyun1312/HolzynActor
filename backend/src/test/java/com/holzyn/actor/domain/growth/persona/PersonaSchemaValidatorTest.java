package com.holzyn.actor.domain.growth.persona;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PersonaSchemaValidator 单元测试（O6 轨道 G1a：档案契约校验 + 11 维覆盖率）。
 * <p>职责：验证三件事——</p>
 * <ol>
 *   <li><b>契约校验</b>：基准夹具通过；OCEAN/权重/面具抑制越界被记为 error；未知情境码记为 warning；</li>
 *   <li><b>11 维覆盖率三态</b>：stored（档案已存）/ projected（模块可投影）/ missing（真空维）；</li>
 *   <li><b>零阻断</b>：校验失败不抛异常，仅返回结果供调用方降级。</li>
 * </ol>
 * <p>所属模块：domain/growth/persona（测试）。</p>
 */
class PersonaSchemaValidatorTest {

    /** 基准夹具类路径 */
    private static final String FIXTURE = "growth/persona_sample_shenyanqiu.json";

    /** 被校验对象 */
    private final PersonaSchemaValidator validator = new PersonaSchemaValidator();

    /** 读取基准夹具。 */
    private PersonaProfile fixture() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(FIXTURE)) {
            assertNotNull(in, "基准夹具缺失：" + FIXTURE);
            return PersonaJson.read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** 最小结构列投影（①/⑩ 可投影判定用）。 */
    private PersonaRenderContext minimalContext() {
        PersonaRenderContext c = new PersonaRenderContext();
        c.setName("沈砚秋");
        c.setStageLabel("老年期（ELDER）");
        c.setBirthLabel("青禾历前 62 年 3 月 11 日");
        return c;
    }

    /**
     * 基准夹具：契约校验通过、11 维全部 stored（覆盖率 100%）。
     */
    @Test
    void fixtureIsValidAndFullyCovered() throws IOException {
        PersonaValidationResult r = validator.validate(fixture(), minimalContext());
        assertTrue(r.isValid(), "基准夹具应通过校验，错误：" + r.getErrors());
        assertEquals(11, r.getCoveredDimCount(), "基准夹具 11 维应全部覆盖");
        assertEquals(11, r.getStoredDims().size(), "基准夹具 11 维应全部为 stored");
        assertTrue(r.getMissingDims().isEmpty(), "不应有空维");
        assertEquals(1.0d, r.getCoverageRatio(), 1e-9);
    }

    /**
     * 覆盖率三态：只落 PERSONA 维（模拟"档案缺 MODULE 维"）时，
     * 可由模块投影的维应记为 projected，而 ③ 生理基线（投影服务尚未接入）应记为 missing。
     */
    @Test
    void coverageTriStateWithPartialProfile() {
        PersonaProfile p = PersonaProfile.empty(1L, 1L);
        // 只填 PERSONA 维（② 外貌、④ 人格、⑦ 语言、⑪ 面具）
        p.getDims().setAppearance(new PersonaDims.Appearance());
        p.getDims().setPersonality(new PersonaDims.Personality());
        p.getDims().setVoice(new PersonaDims.Voice());
        p.getDims().setMask(new PersonaDims.Mask());
        PersonaRenderContext c = minimalContext();
        PersonaValidationResult r = validator.validate(p, c);

        assertEquals(PersonaValidationResult.STORED, r.getCoverage().get(PersonaSchema.Dims.APPEARANCE));
        assertEquals(PersonaValidationResult.STORED, r.getCoverage().get(PersonaSchema.Dims.MASK));
        // ① 身份：结构列存在 → 可投影
        assertEquals(PersonaValidationResult.PROJECTED, r.getCoverage().get(PersonaSchema.Dims.IDENTITY));
        // ⑩ 发育：阶段标签存在 → 可投影
        assertEquals(PersonaValidationResult.PROJECTED, r.getCoverage().get(PersonaSchema.Dims.DEVELOPMENT));
        // ③ 生理基线：权威在状态模块，但投影服务在阶段②接入 → 当前保守判 missing
        assertEquals(PersonaValidationResult.MISSING, r.getCoverage().get(PersonaSchema.Dims.PHYSIO));
        assertTrue(r.getMissingDims().contains(PersonaSchema.Dims.PHYSIO));
        assertFalse(r.getMissingDims().contains(PersonaSchema.Dims.IDENTITY), "可投影维不应算空维");
    }

    /**
     * 空档案：全部维 missing + 一条 error；不抛异常（零阻断）。
     */
    @Test
    void nullProfileIsMissingAllDims() {
        PersonaValidationResult r = validator.validate(null, null);
        assertFalse(r.isValid());
        assertEquals(11, r.getMissingDims().size(), "空档案应 11 维全 missing");
        assertEquals(1, r.getErrors().size());
        assertEquals(0.0d, r.getCoverageRatio(), 1e-9);
    }

    /**
     * OCEAN 越界 → error（契约违规）。
     */
    @Test
    void oceanOutOfRangeIsError() {
        PersonaProfile p = PersonaProfile.empty(1L, 1L);
        PersonaDims.Personality pe = new PersonaDims.Personality();
        PersonaDims.Ocean o = new PersonaDims.Ocean();
        o.setOpenness(1.4d);
        o.setConscientiousness(-0.2d);
        o.setExtraversion(0.5d);
        o.setAgreeableness(0.5d);
        o.setNeuroticism(0.5d);
        pe.setOcean(o);
        p.getDims().setPersonality(pe);

        PersonaValidationResult r = validator.validate(p);
        assertFalse(r.isValid());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("personality.ocean.openness")),
                "应报告 openness 越界：" + r.getErrors());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("personality.ocean.conscientiousness")));
    }

    /**
     * 技能/面具/价值权重越界 → error；未知情境码 → warning（不阻断）。
     */
    @Test
    void rangesAndWarnings() {
        PersonaProfile p = PersonaProfile.empty(1L, 1L);

        PersonaDims.Ability ab = new PersonaDims.Ability();
        ab.getSkills().put("村务治理", 12d);
        p.getDims().setAbility(ab);

        PersonaDims.Values v = new PersonaDims.Values();
        v.getValueWeights().put("宗族", 1.5d);
        p.getDims().setValues(v);

        PersonaDims.Mask m = new PersonaDims.Mask();
        m.getContextSuppression().put("before_authority", 0.72d);
        m.getContextSuppression().put("before_alien", 0.3d);
        PersonaDims.Breach b = new PersonaDims.Breach();
        b.setTrigger("饮酒");
        b.setSuppressionDelta(-1.5d);
        m.getBreaches().add(b);
        p.getDims().setMask(m);

        PersonaValidationResult r = validator.validate(p);
        assertFalse(r.isValid());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("ability.skills.村务治理")), r.getErrors().toString());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("values.valueWeights.宗族")));
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("mask.breaches.suppressionDelta")));
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("before_alien")),
                "未知情境码应为告警：" + r.getWarnings());
    }

    /**
     * 能力自评卡（P1 ①-2）：self_efficacy 取值必须 1-5（越界 → error；空自评不校验）。
     */
    @Test
    void selfEfficacyOutOfRangeIsError() {
        PersonaProfile p = PersonaProfile.empty(1L, 1L);
        PersonaDims.Ability ab = new PersonaDims.Ability();
        ab.getSelfEfficacy().put("农事耕作", 6);
        ab.getSelfEfficacy().put("记账算筹", 0);
        ab.getSelfEfficacy().put("说和调解", 3);
        p.getDims().setAbility(ab);

        PersonaValidationResult r = validator.validate(p);
        assertFalse(r.isValid());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("ability.selfEfficacy.农事耕作")), r.getErrors().toString());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("ability.selfEfficacy.记账算筹")), r.getErrors().toString());
        assertFalse(r.getErrors().stream().anyMatch(e -> e.contains("ability.selfEfficacy.说和调解")),
                "合法取值 3 不应报错：" + r.getErrors());
    }

    /**
     * 创伤触发物必须可追溯到事件（无事件的触发物 → error）。
     */
    @Test
    void traumaTriggerRequiresEvent() {        PersonaProfile p = PersonaProfile.empty(1L, 1L);
        PersonaDims.Experience e = new PersonaDims.Experience();
        PersonaDims.Trauma t = new PersonaDims.Trauma();
        t.setTriggers(List.of("药炉气味"));
        e.getTraumas().add(t);
        p.getDims().setExperience(e);

        PersonaValidationResult r = validator.validate(p);
        assertFalse(r.isValid());
        assertTrue(r.getErrors().stream().anyMatch(x -> x.contains("experience.traumas")), r.getErrors().toString());
    }

    /**
     * schema_version 不匹配 → error（防止旧契约数据被误当 v2 使用）。
     */
    @Test
    void schemaVersionMismatchIsError() {
        PersonaProfile p = PersonaProfile.empty(1L, 1L);
        p.setSchemaVersion(1);
        PersonaValidationResult r = validator.validate(p);
        assertFalse(r.isValid());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("schema_version")), r.getErrors().toString());
    }

    /**
     * JSON 编解码往返：snake_case 口径下 11 维内容无损（落库/读取的基本保障）。
     */
    @Test
    void jsonRoundTripPreservesDims() throws IOException {
        PersonaProfile p = fixture();
        String json = PersonaJson.write(p);
        assertNotNull(json);
        assertTrue(json.contains("\"value_weights\""), "序列化应为 snake_case：value_weights");
        assertTrue(json.contains("\"context_suppression\""), "序列化应为 snake_case：context_suppression");
        PersonaProfile back = PersonaJson.read(json);
        assertNotNull(back);
        assertEquals(11, back.getDims().byKey(PersonaSchema.Dims.MASK) == null ? 0 : 11);
        assertEquals(p.getDims().getPersonality().getOcean().getOpenness(),
                back.getDims().getPersonality().getOcean().getOpenness(), 1e-9);
        assertEquals(p.getDims().getValues().getValueWeights().get("宗族"),
                back.getDims().getValues().getValueWeights().get("宗族"), 1e-9);
        assertEquals(p.getDims().getExperience().getTraumas().get(0).getTriggers(),
                back.getDims().getExperience().getTraumas().get(0).getTriggers());
        assertEquals(p.getDims().getIdentity().getProperty().getLandMu(),
                back.getDims().getIdentity().getProperty().getLandMu(), 1e-9);
    }
}