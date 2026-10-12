package com.holzyn.actor.domain.worldgen.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorldGenRenderer（M2：6 维 JSON → world_foundation.md 渲染，移植 render_worldview.py 语义）单元测试。
 * <p>职责：验证——prose 段落 / table 表格 / list 列表渲染、essence 引用定调句、空字段跳过、
 * 维度包裹解析、缺维度文件跳过、输出 meta/world_foundation.md。</p>
 */
class WorldGenRendererTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LocalArchiveService archiveService;
    private WorldGenRenderer renderer;
    private Path dataDir;

    @BeforeEach
    void setUp() throws IOException {
        dataDir = Files.createTempDirectory("worldgen-render-test");
        archiveService = new LocalArchiveService();
        ReflectionTestUtils.setField(archiveService, "dataDir", dataDir.toString());
        renderer = new WorldGenRenderer(archiveService);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (dataDir != null && Files.exists(dataDir)) {
            try (var walk = Files.walk(dataDir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 忽略
                    }
                });
            }
        }
    }

    /** 解析 JSON 为节点（测试用）。 */
    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void renderSingleDimensionProducesMarkdown() {
        String json = """
                {
                  "core_laws": {
                    "essence": "灵气循雾脉流转，离脉则力竭",
                    "power_system": "四阶修炼体系。",
                    "axioms": [
                      {"name": "雾脉守恒", "statement": "灵气总量守恒", "cost": "施法耗灵", "boundary": "禁区无效", "enforcement": "雾脉自行纠正"}
                    ],
                    "taboos": ["弑杀雾脉之灵"],
                    "power_manifestation": "雾光环绕"
                  }
                }
                """;
        String md = renderer.renderDimension(parse(json), "core_laws");
        assertTrue(md.contains("## 一、核心法则"));
        assertTrue(md.contains("> 灵气循雾脉流转，离脉则力竭"), "essence 应为引用定调句");
        assertTrue(md.contains("### 力量体系"));
        assertTrue(md.contains("| 名称 | 陈述 | 代价 | 边界 | 执行 |"), "表格应有表头");
        assertTrue(md.contains("| 雾脉守恒 | 灵气总量守恒 |"));
        assertTrue(md.contains("- 弑杀雾脉之灵"), "list 应为无序列表");
    }

    @Test
    void emptyFieldsSkipped() {
        String json = """
                {
                  "existence": {
                    "essence": "万物有寿",
                    "calendar": "一年十二月",
                    "lifespan": "",
                    "death": "魂魄归雾",
                    "disease_and_birth": "医馆行针"
                  }
                }
                """;
        String md = renderer.renderDimension(parse(json), "existence");
        assertTrue(md.contains("### 历法"));
        assertTrue(!md.contains("### 寿命与衰老"), "空字段应跳过");
    }

    @Test
    void renderAllWritesWorldFoundationMd() throws IOException {
        Path ws = archiveService.taskWorkspace(5L, 7L);
        // 只写 2 个维度文件（缺其余维度 → 跳过）
        archiveService.writeJson(ws, "world/core_laws.json",
                "{\"core_laws\":{\"essence\":\"法则定调\",\"power_system\":\"四阶\",\"axioms\":[],\"taboos\":[],\"power_manifestation\":\"雾光\"}}");
        archiveService.writeJson(ws, "world/information.json",
                "{\"information\":{\"essence\":\"信息定调\",\"info_speed\":\"三日\",\"knowledge_medium\":\"玉简\",\"info_barriers\":\"垄断\",\"rumor_and_truth\":\"失真\"}}");

        WorldGenRenderer.RenderResult result = renderer.render(ws, "测试世界观");
        assertEquals(2, result.sections());
        assertEquals("meta/world_foundation.md", result.outputPath());
        Path out = ws.resolve("meta/world_foundation.md");
        assertTrue(Files.exists(out));
        String content = Files.readString(out);
        assertTrue(content.startsWith("# 测试世界观"));
        assertTrue(content.contains("## 一、核心法则"));
        assertTrue(content.contains("## 六、信息传播"));
        assertTrue(content.contains("---"));
    }
}
