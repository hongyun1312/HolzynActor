package com.holzyn.actor.domain.worldmap.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.worldmap.model.WorldMapLayout;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorldMapParser 单元测试（位置移动与地图生成系统 M1，V1.3 第六层：纯静态构造断言）。
 * <p>职责：AI world_map_generate 输出（SCHEMA_JSON）→ WorldMapLayout 的结构还原容错——
 * Markdown 代码块剥离、缺失数组为空、数值宽松、住所 members 同居解析。</p>
 */
class WorldMapParserTest {

    private final WorldMapParser parser = new WorldMapParser(new ObjectMapper());

    @Test
    void parsesValidLayout() {
        String json = """
                {
                  "mapName": "青禾村总图",
                  "regions": [
                    { "locationId": 1, "name": "村口石牌坊", "centerX": 250, "centerZ": 260, "radius": 12,
                      "shape": "circle", "regionDesc": "村西口，进村第一景" }
                  ],
                  "roads": [
                    { "name": "村中主道", "path": [[245, 255], [290, 300]], "width": 3 }
                  ],
                  "placements": [
                    { "name": "沈记粮铺", "category": "store", "posX": 310, "posZ": 220, "rotY": 0, "locationId": 6 }
                  ],
                  "homes": [
                    { "name": "沈家大宅", "locationId": 6, "posX": 315, "posZ": 215, "roomLabel": "正房",
                      "members": [
                        { "characterRef": "npc:1", "role": "户主", "roomLabel": "正房东间" },
                        { "characterRef": "npc:7", "role": "配偶", "roomLabel": "东厢" }
                      ] }
                  ]
                }
                """;
        WorldMapLayout layout = parser.parse(json);
        assertEquals("青禾村总图", layout.getMapName());
        assertEquals(1, layout.getRegions().size());
        assertEquals(1L, layout.getRegions().get(0).getLocationId());
        assertEquals(250.0, layout.getRegions().get(0).getCenterX());
        assertEquals(1, layout.getRoads().size());
        assertEquals(2, layout.getRoads().get(0).getPath().size());
        assertEquals(1, layout.getPlacements().size());
        assertEquals("store", layout.getPlacements().get(0).getCategory());
        assertEquals(1, layout.getHomes().size());
        assertEquals(2, layout.getHomes().get(0).getMembers().size());
        assertTrue(layout.getHomes().get(0).getMembers().get(0).isOwner(), "户主应标记 owner");
    }

    @Test
    void parsesMarkdownFenced() {
        String json = "```json\n{\"mapName\":\"X\",\"regions\":[],\"homes\":[]}\n```";
        WorldMapLayout layout = parser.parse(json);
        assertEquals("X", layout.getMapName());
    }

    @Test
    void missingArraysAreEmpty() {
        WorldMapLayout layout = parser.parse("{\"mapName\":\"仅名称\"}");
        assertNotNull(layout.getRegions());
        assertNotNull(layout.getRoads());
        assertNotNull(layout.getPlacements());
        assertNotNull(layout.getHomes());
        assertEquals(0, layout.getRegions().size());
    }

    @Test
    void invalidJsonThrows() {
        assertThrows(BizException.class, () -> parser.parse("不是 JSON"));
        assertThrows(BizException.class, () -> parser.parse("{\"broken\""));
    }
}
