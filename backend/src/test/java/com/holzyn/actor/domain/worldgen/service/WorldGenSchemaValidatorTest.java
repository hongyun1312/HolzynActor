package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.common.BizException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorldGenSchemaValidator（M2：维度 JSON Schema 校验 + 自动修复）单元测试。
 * <p>职责：验证——合法输出通过并规范化（包裹形态）、弯引号/尾随逗号自动修复、
 * 裸维度对象自动补包裹、缺字段/类型错误拒绝、essence 超长截断。</p>
 */
class WorldGenSchemaValidatorTest {

    private final WorldGenSchemaValidator validator = new WorldGenSchemaValidator();

    @Test
    void validCoreLawsPassesAndNormalizes() throws Exception {
        String raw = """
                ```json
                {
                  "core_laws": {
                    "essence": "灵气循雾脉流转，离脉则力竭",
                    "power_system": "灵力修炼体系：引气、聚灵、凝丹、化神四阶。",
                    "axioms": [
                      {"name": "雾脉守恒", "statement": "灵气总量守恒", "cost": "每次施法消耗灵力", "boundary": "禁区内无效", "enforcement": "由雾脉自行纠正"}
                    ],
                    "taboos": ["弑杀雾脉之灵"],
                    "power_manifestation": "施法时雾光环绕"
                  }
                }
                ```
                """;
        String validated = validator.validateAndRepair("core_laws", raw);
        JsonNode root = new ObjectMapper().readTree(validated);
        assertTrue(root.has("core_laws"));
        JsonNode dim = root.get("core_laws");
        assertEquals("灵气循雾脉流转，离脉则力竭", dim.get("essence").asText());
        assertEquals(1, dim.get("axioms").size());
        assertEquals(1, dim.get("taboos").size());
    }

    @Test
    void curlyQuotesAndTrailingCommasRepaired() throws Exception {
        // 弯引号 + 尾随逗号 → 自动修复后通过
        String raw = """
                {
                  "core_laws": {
                    "essence": "灵气循雾脉流转，离脉则力竭",
                    "power_system": "灵力修炼体系：引气、聚灵、凝丹、化神四阶。",
                    "axioms": [
                      {"name": "雾脉守恒", "statement": "灵气总量守恒", "cost": "每次施法消耗灵力", "boundary": "禁区内无效", "enforcement": "由雾脉自行纠正"},
                    ],
                    "taboos": ["弑杀雾脉之灵",],
                    "power_manifestation": "施法时雾光环绕"
                  },
                }
                """.replace('\u201C', '"').replace('\u201D', '"');
        String validated = validator.validateAndRepair("core_laws", raw);
        JsonNode root = new ObjectMapper().readTree(validated);
        assertNotNull(root.get("core_laws"));
        assertEquals(1, root.get("core_laws").get("axioms").size());
    }

    @Test
    void bareDimensionObjectAutoWrapped() throws Exception {
        String raw = """
                {
                  "essence": "消息沿脉走，顺潮三日",
                  "info_speed": "顺潮三日、逆潮六日",
                  "knowledge_medium": "玉简藏书阁",
                  "info_barriers": "宗门垄断高深典籍",
                  "rumor_and_truth": "谣言随潮汐失真"
                }
                """;
        String validated = validator.validateAndRepair("information", raw);
        JsonNode root = new ObjectMapper().readTree(validated);
        assertTrue(root.has("information"));
        assertEquals("消息沿脉走，顺潮三日", root.get("information").get("essence").asText());
    }

    @Test
    void missingRequiredFieldRejected() {
        String raw = """
                {
                  "space_time": {
                    "essence": "山海分层",
                    "world_layout": "九洲分立",
                    "key_locations": [],
                    "ecology": "灵兽栖息",
                    "era": "玄黄纪"
                  }
                }
                """;
        // 缺 environment_texture
        BizException e = assertThrows(BizException.class,
                () -> validator.validateAndRepair("space_time", raw));
        assertTrue(e.getMessage().contains("environment_texture"));
    }

    @Test
    void wrongTypeRejected() {
        // taboos 应为数组，给了字符串
        String raw = """
                {
                  "core_laws": {
                    "essence": "灵气循雾脉流转",
                    "power_system": "四阶修炼",
                    "axioms": [],
                    "taboos": "这是字符串不是数组",
                    "power_manifestation": "雾光环绕"
                  }
                }
                """;
        BizException e = assertThrows(BizException.class,
                () -> validator.validateAndRepair("core_laws", raw));
        assertTrue(e.getMessage().contains("taboos"));
    }

    @Test
    void emptyOutputRejected() {
        assertThrows(BizException.class, () -> validator.validateAndRepair("core_laws", ""));
        assertThrows(BizException.class, () -> validator.validateAndRepair("core_laws", "  "));
    }

    @Test
    void essenceTooLongTruncated() throws Exception {
        String longEssence = "长".repeat(200);
        String raw = """
                {
                  "existence": {
                    "essence": "%s",
                    "calendar": "一年十二月",
                    "lifespan": "人族百年",
                    "death": "魂魄归雾",
                    "disease_and_birth": "医馆行针"
                  }
                }
                """.formatted(longEssence);
        String validated = validator.validateAndRepair("existence", raw);
        JsonNode root = new ObjectMapper().readTree(validated);
        assertEquals(80, root.get("existence").get("essence").asText().length());
    }
}
