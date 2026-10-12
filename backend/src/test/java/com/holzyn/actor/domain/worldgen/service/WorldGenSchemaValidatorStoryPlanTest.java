package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorldGenSchemaValidator 的 story-plan 校验（M4 story-plan 工作流，2026-09-29_06）单元测试。
 * <p>职责：验证——故事引擎三层结构校验（三段 + essence 必填 / 类型 / 裸对象包裹 / issues）、
 * 风格档案校验（tone/narrative_persona 必填 / 类型 / 裸对象包裹）、read 读取容错。</p>
 */
class WorldGenSchemaValidatorStoryPlanTest {

    private final WorldGenSchemaValidator validator = new WorldGenSchemaValidator();

    private static final String PLAN_OK = """
            {"story_plan": {
              "surface": {"essence": "雾隐仙村", "expanded_premise": "灵气循雾脉流转", "core_conflict": "宗门垄断修炼资源",
                "world_experience": "凡人在雾海中求生", "tone_reference": "冷眼旁观", "world_tags": ["雾隐", "仙村"]},
              "engine": {"essence": "局势自驱", "living_trap": "凡人无法离开雾海", "conflict_engine": "资源递减使矛盾必然升级",
                "conflict_layers": {"external": "宗门高压", "internal": "凡人绝望", "relational": "信任与利用并存"},
                "mystery_box": "雾脉的源头", "rhythm_cycle": "收成→粮价→民怨", "dual_story": {"foreground": "日常生计", "background": "雾脉暗流"}},
              "payoff": {"essence": "以弱问强", "tension_archetypes": ["集市冲突", "宗祠决议"],
                "dramatic_moments": "天象异变", "emotional_rhythm": "农忙低谷节庆高涨", "atmosphere_notes": "药香与山雨"},
              "issues": [{"type": "missing_info", "description": "缺少导师位角色"}]
            }}
            """;

    @Test
    @DisplayName("故事引擎：合法输出校验通过并包裹规范化")
    void validateStoryPlan_ok() {
        String validated = validator.validateStoryPlan(PLAN_OK);
        JsonNode root = JsonUtilNode(validated);
        assertTrue(root.has("story_plan"));
        JsonNode plan = root.get("story_plan");
        assertEquals("雾隐仙村", plan.path("surface").path("essence").asText());
        assertEquals(2, plan.path("payoff").path("tension_archetypes").size());
        assertEquals("missing_info", plan.path("issues").get(0).path("type").asText());
    }

    @Test
    @DisplayName("故事引擎：裸三层对象自动补包裹")
    void validateStoryPlan_bareObject() {
        String bare = """
                {"surface": {"essence": "A", "expanded_premise": "p", "core_conflict": "c", "world_experience": "w",
                   "tone_reference": "t", "world_tags": ["x"]},
                 "engine": {"essence": "E", "living_trap": "l", "conflict_engine": "ce", "conflict_layers": {"external": "a", "internal": "b", "relational": "c"},
                   "mystery_box": "m", "rhythm_cycle": "r", "dual_story": {"foreground": "f", "background": "b"}},
                 "payoff": {"essence": "P", "tension_archetypes": ["t1"], "dramatic_moments": "d", "emotional_rhythm": "e", "atmosphere_notes": "a"}}
                """;
        JsonNode plan = validator.readStoryPlan(validator.validateStoryPlan(bare));
        assertNotNull(plan);
        assertEquals("A", plan.path("surface").path("essence").asText());
    }

    @Test
    @DisplayName("故事引擎：缺段/缺 essence 抛错")
    void validateStoryPlan_missing() {
        assertThrows(BizException.class, () -> validator.validateStoryPlan(
                "{\"story_plan\":{\"surface\":{\"essence\":\"A\"},\"payoff\":{\"essence\":\"P\"}}}"),
                "缺 engine 段应抛错");
        assertThrows(BizException.class, () -> validator.validateStoryPlan(
                "{\"story_plan\":{\"surface\":{\"essence\":\"\"},\"engine\":{\"essence\":\"E\"},\"payoff\":{\"essence\":\"P\"}}}"),
                "surface essence 空应抛错");
    }

    @Test
    @DisplayName("故事引擎：类型错误抛错（world_tags 非数组）")
    void validateStoryPlan_typeError() {
        assertThrows(BizException.class, () -> validator.validateStoryPlan(
                "{\"story_plan\":{\"surface\":{\"essence\":\"A\",\"world_tags\":\"x\"},\"engine\":{\"essence\":\"E\"},\"payoff\":{\"essence\":\"P\"}}}"));
    }

    @Test
    @DisplayName("故事引擎：非法 issues.type 抛错；缺失 issues 放行")
    void validateStoryPlan_issues() {
        assertThrows(BizException.class, () -> validator.validateStoryPlan(
                "{\"story_plan\":{\"surface\":{\"essence\":\"A\"},\"engine\":{\"essence\":\"E\"},\"payoff\":{\"essence\":\"P\"},\"issues\":[{\"type\":\"bad\"}]}}"));
        // 无 issues 也放行
        assertNotNull(validator.readStoryPlan(validator.validateStoryPlan(
                "{\"story_plan\":{\"surface\":{\"essence\":\"A\"},\"engine\":{\"essence\":\"E\"},\"payoff\":{\"essence\":\"P\"}}}")));
    }

    private static final String STYLE_OK = """
            {"style_profile": {
              "tone": "冷调吐槽，外冷内热",
              "references": [{"work": "雾山五行", "tags": "仙侠群像", "learn": "群像冲突", "apply": "村中多线并行"}],
              "narrative_persona": "村口说书人",
              "commandments": ["禁止悲情独白", "禁止精确数字"],
              "genre_notes": "仙侠题材特有惯例"
            }}
            """;

    @Test
    @DisplayName("风格档案：合法输出校验通过")
    void validateStyleProfile_ok() {
        JsonNode profile = validator.readStyleProfile(validator.validateStyleProfile(STYLE_OK));
        assertNotNull(profile);
        assertEquals("冷调吐槽，外冷内热", profile.path("tone").asText());
        assertEquals("村口说书人", profile.path("narrative_persona").asText());
        assertEquals(2, profile.path("commandments").size());
    }

    @Test
    @DisplayName("风格档案：裸对象包裹 + 缺 tone/persona 抛错")
    void validateStyleProfile_missing() {
        JsonNode profile = validator.readStyleProfile(validator.validateStyleProfile(
                "{\"tone\":\"烟火气\",\"narrative_persona\":\"邻里闲话\"}"));
        assertNotNull(profile);
        assertThrows(BizException.class, () -> validator.validateStyleProfile("{\"tone\":\"x\"}"),
                "缺 narrative_persona 应抛错");
        assertThrows(BizException.class, () -> validator.validateStyleProfile("{\"narrative_persona\":\"x\"}"),
                "缺 tone 应抛错");
    }

    @Test
    @DisplayName("read 容错：非法 JSON 返回 null")
    void read_robust() {
        assertNull(validator.readStoryPlan("not-json"));
        assertNull(validator.readStyleProfile(""));
        assertNull(validator.readStoryPlan(null));
    }

    /** 字符串 → JsonNode（测试内工具）。 */
    private static JsonNode JsonUtilNode(String json) {
        try {
            return new tools.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
