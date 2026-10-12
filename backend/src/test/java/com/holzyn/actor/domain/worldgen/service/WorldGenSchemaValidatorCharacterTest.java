package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorldGenSchemaValidator character 工作流校验（M4 适配轮 2026-09-29_05）单元测试。
 * <p>职责：验证——阵容校验（包裹/裸数组归一、name/role 必填）、
 * 角色深度校验（4 段结构、skeleton.name 必填、beliefs 数组、包裹归一）、读取角色数组。</p>
 */
class WorldGenSchemaValidatorCharacterTest {

    private WorldGenSchemaValidator validator;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        validator = new WorldGenSchemaValidator();
    }

    @Test
    @DisplayName("阵容校验：包裹形态通过 + 规范化")
    void validateRosterWrapsAndPasses() throws Exception {
        String json = """
                {"characters":[
                  {"name":"沈砚秋","role":"村长","importance":"主角"},
                  {"name":"孙二娘","role":"掌柜","importance":"重要","age":"38"}
                ]}
                """;
        String out = validator.validateRoster(json);
        assertEquals(2, MAPPER.readTree(out).path("characters").size());
    }

    @Test
    @DisplayName("阵容校验：裸数组自动补包裹")
    void validateRosterAcceptsBareArray() throws Exception {
        String json = """
                [ {"name":"甲","role":"猎人"}, {"name":"乙","role":"渔夫"} ]
                """;
        String out = validator.validateRoster(json);
        assertTrue(MAPPER.readTree(out).has("characters"));
        assertEquals(2, MAPPER.readTree(out).path("characters").size());
    }

    @Test
    @DisplayName("阵容校验：缺 name/role / 空阵容 / 非 JSON → 拒绝")
    void validateRosterRejectsMissingFields() {
        assertThrows(BizException.class, () -> validator.validateRoster(
                "{\"characters\":[{\"role\":\"村长\"}]}"), "缺 name 应拒绝");
        assertThrows(BizException.class, () -> validator.validateRoster(
                "{\"characters\":[{\"name\":\"沈砚秋\"}]}"), "缺 role 应拒绝");
        assertThrows(BizException.class, () -> validator.validateRoster(
                "{\"characters\":[]}"), "空阵容应拒绝");
        assertThrows(BizException.class, () -> validator.validateRoster("不是 JSON"));
    }

    @Test
    @DisplayName("角色深度校验：4 段结构通过 + 包裹归一")
    void validateCharacterDepthPasses() throws Exception {
        String json = """
                {"character_depth":{
                  "skeleton":{"name":"沈砚秋","title":"村长","gender":"男","race":"人族","age":"56","origin":"青禾村","background":"族长","appearance":"花白胡须"},
                  "beliefs":{"core_values":["守规矩"],"desires":["安宁"],"fears":["败落"],"beliefs":["规矩立村"]},
                  "deep":{"secret":"藏私","trauma":"丧父","moral_boundary":"不害无辜","growth_arc":"放手"},
                  "voice":{"speech_style":"官腔","catchphrases":["这个嘛"],"tone":"庄重","language_habits":"自称老朽"}
                }}
                """;
        String out = validator.validateCharacterDepth(json);
        assertTrue(MAPPER.readTree(out).has("character_depth"));
    }

    @Test
    @DisplayName("角色深度校验：skeleton.name 必填 / 结构非法 / beliefs 类型错 → 拒绝")
    void validateCharacterDepthRejectsInvalid() {
        assertThrows(BizException.class, () -> validator.validateCharacterDepth(
                "{\"character_depth\":{\"skeleton\":{\"title\":\"无名字\"}}}"), "缺 skeleton.name 应拒绝");
        assertThrows(BizException.class, () -> validator.validateCharacterDepth(
                "{\"character_depth\":{\"beliefs\":{}}}"), "缺 skeleton 段应拒绝");
        assertThrows(BizException.class, () -> validator.validateCharacterDepth(
                "{\"character_depth\":{\"skeleton\":{\"name\":\"甲\"},\"beliefs\":{\"core_values\":\"非数组\"}}}"),
                "beliefs.core_values 非数组应拒绝");
        assertThrows(BizException.class, () -> validator.validateCharacterDepth("{\"foo\":1}"));
    }

    @Test
    @DisplayName("读取角色数组：容错返回空列表")
    void readRosterToleratesInvalid() {
        assertEquals(0, validator.readRoster(null).size());
        assertEquals(0, validator.readRoster("坏数据").size());
        assertEquals(2, validator.readRoster("{\"characters\":[{},{}]}").size());
    }
}
