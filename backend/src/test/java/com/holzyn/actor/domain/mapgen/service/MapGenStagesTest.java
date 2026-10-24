/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.holzyn.actor.domain.mapgen.service;

import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.mapgen.algo.RoadGrade;
import com.holzyn.actor.domain.mapgen.config.MapGenProperties;
import com.holzyn.actor.domain.mapgen.model.MapAssetCatalog;
import com.holzyn.actor.domain.mapgen.model.MapAssetType;
import com.holzyn.actor.domain.mapgen.model.MapProfile;
import com.holzyn.actor.domain.mapgen.model.RoadTopology;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * S0/S2/S3 的<b>解析闸门</b>单测——AI 输出永远是不可信输入，这里守住「坏输入不会污染地图」。
 *
 * <p><b>为什么这些断言值得写</b>：模型很爱做三件事——把数字写到离谱（面宽 100 米的农舍）、
 * 把类别写成世界观不该有的（兵营、神殿）、给出互相矛盾的拓扑（一条边指向不存在的节点）。
 * 这三种输入如果直接进算法层，结果不是报错，而是<b>一张看起来像地图的废图</b>
 * （房子压满整条街、路从地图外面绕过来）——比崩溃更难查。所以每个 parse 都要有测试钉住：</p>
 * <ul>
 *   <li>数值越界 → 夹到合法区间，绝不放行；</li>
 *   <li>非白名单大类 / 命中禁忌清单 → 丢弃并补齐必要类型；</li>
 *   <li>非法拓扑边（未知节点、自环、非法等级）→ 丢弃或归一，绝不让它进寻路。</li>
 * </ul>
 */
class MapGenStagesTest {

    /** 测试用种子 */
    private static final long SEED = 20261016L;

    /** 测试用边长 */
    private static final int SIZE = 512;

    // ==================== S0 档案 ====================

    /**
     * 越界数值必须被夹住；缺失字段必须用题材档默认值；地标必须有空间语义。
     */
    @Test
    @DisplayName("S0 解析：越界数值夹取、缺失字段回落默认、地标保留空间语义")
    void profileParseClampsOutOfRangeNumbers() throws Exception {
        WorldMapProfileService svc = newProfileService();
        String json = """
                {
                  "mapName": "青屿镇",
                  "genreProfile": "island",
                  "reliefHeight": 9.9,
                  "islandRadiusRatio": 0.02,
                  "riverCount": 17,
                  "plainFraction": -3,
                  "moisture": 0.61,
                  "landmarks": [
                    { "name": "老灯塔", "kind": "lighthouse", "sit": "岬角最东端的高处", "note": "灯长年不灭" },
                    { "name": "", "kind": "cape", "sit": "北岸" }
                  ],
                  "forbidden": ["武器铺", "监牢"],
                  "allowedCategories": ["民居", "商铺"]
                }
                """;
        MapProfile p = svc.parse(json, SEED, SIZE);
        assertTrue(p.aiGenerated());
        assertEquals("青屿镇", p.mapName());
        // 越界值被夹到边界
        assertTrue(p.params().reliefHeight <= 0.62, "reliefHeight 必须夹到 ≤0.62");
        assertTrue(p.params().islandRadiusRatio >= 0.18, "islandRadiusRatio 必须夹到 ≥0.18");
        assertEquals(4, p.params().riverCount, "riverCount 必须夹到 ≤4");
        assertEquals(0.0, p.params().plainFraction, "plainFraction 必须夹到 ≥0");
        // 合法值原样保留
        assertEquals(0.61, p.params().moisture, 1e-9);
        // 空名地标被丢弃（没有名字的地标在 UI 上是一个空标签，不能留）
        assertEquals(1, p.landmarks().size());
        assertEquals("岬角最东端的高处", p.landmarks().get(0).sit());
        assertEquals(List.of("武器铺", "监牢"), p.forbidden());
        // 尺度来自配置，不是 AI（AI 只说地貌，不说分辨率）
        assertEquals(SIZE, p.params().size);
        assertEquals(SEED, p.params().seed);
    }

    /**
     * 世界观完全没给禁忌/大类时，必须落到默认清单（否则 S2 的三道闸门会少一道）。
     */
    @Test
    @DisplayName("S0 解析：缺禁忌清单时回落默认清单")
    void profileParseFallsBackToDefaultForbidden() throws Exception {
        MapProfile p = newProfileService().parse("{\"mapName\":\"某地\"}", SEED, SIZE);
        assertFalse(p.forbidden().isEmpty());
        assertEquals(MapProfile.DEFAULT_FORBIDDEN, p.forbidden());
        assertEquals(MapProfile.DEFAULT_CATEGORIES, p.allowedCategories());
    }

    /**
     * AI 把 JSON 包在 Markdown 代码块里也必须能解析（这是最常见的格式漂移）。
     */
    @Test
    @DisplayName("S0 解析：容忍 Markdown 代码块包裹")
    void profileParseToleratesMarkdownFence() throws Exception {
        String wrapped = "```json\n{\"mapName\":\"雾岛\",\"reliefHeight\":0.3}\n```";
        MapProfile p = newProfileService().parse(wrapped, SEED, SIZE);
        assertEquals("雾岛", p.mapName());
    }

    // ==================== S2 资产 ====================

    /**
     * 三道闸门：非白名单大类丢弃、禁忌命中丢弃、必要类型补齐；离谱尺寸夹取。
     */
    @Test
    @DisplayName("S2 解析：大类白名单 + 禁忌过滤 + 必要类型补齐 + 尺寸夹取")
    void assetParseAppliesThreeGates() throws Exception {
        MapAssetPolicyService svc = newAssetService();
        MapProfile profile = MapProfile.fallback(SEED, SIZE);
        String json = """
                {
                  "assetTypes": [
                    { "code": "house_a", "name": "渔家小院", "category": "民居", "kind": "building",
                      "baseW": 9.0, "baseD": 7.5, "floorsMin": 1, "floorsMax": 2,
                      "wallColor": "#d9c9a8", "roofColor": "#8a5a3c", "roofShape": "gable",
                      "material": "wood", "styleTags": ["坡屋顶"], "weight": 14, "description": "住人的" },
                    { "code": "weapon_shop", "name": "武器铺", "category": "商铺", "kind": "building",
                      "baseW": 8.0, "baseD": 7.0, "weight": 5, "description": "卖刀剑" },
                    { "code": "army_camp", "name": "兵营", "category": "军事", "kind": "building",
                      "baseW": 10.0, "baseD": 8.0, "weight": 5, "description": "驻军" },
                    { "code": "shrine", "name": "神殿", "category": "公共", "kind": "building",
                      "baseW": 12.0, "baseD": 10.0, "weight": 2, "description": "祭祀" },
                    { "code": "huge_house", "name": "巨宅", "category": "民居", "kind": "building",
                      "baseW": 100.0, "baseD": 0.5, "floorsMin": 9, "floorsMax": 2,
                      "roofShape": "dragon", "wallColor": "red", "weight": 99, "description": "离谱" }
                  ],
                  "districts": [ { "code": "old_street", "name": "老街", "character": "早市最热闹" } ]
                }
                """;
        MapAssetCatalog c = svc.parse(json, profile);
        assertTrue(c.aiGenerated());
        List<String> codes = c.types().stream().map(MapAssetType::code).toList();
        // 闸门①：军事大类不在白名单
        assertFalse(codes.contains("army_camp"), "非白名单大类必须丢弃");
        // 闸门②：禁忌清单（武器铺按名字、神殿按默认禁忌）
        assertFalse(codes.contains("weapon_shop"), "禁忌类型必须丢弃");
        assertFalse(codes.contains("shrine"), "禁忌「神殿」必须丢弃");
        // 合法类型保留
        assertTrue(codes.contains("house_a"));
        assertTrue(codes.contains("huge_house"), "尺寸离谱但大类合法 → 保留并夹取，而不是整条丢弃");
        // 闸门③：必要大类补齐（商铺/生产/自然/水工）
        List<String> cats = c.types().stream().map(MapAssetType::category).distinct().toList();
        for (String need : List.of("民居", "商铺", "生产", "自然", "水工")) {
            assertTrue(cats.contains(need), "必要大类缺失：" + need);
        }
        // 夹取生效
        MapAssetType huge = c.types().stream().filter(t -> "huge_house".equals(t.code())).findFirst()
                .orElseThrow();
        assertTrue(huge.baseW() <= 40.0 && huge.baseD() >= 3.0, "尺寸必须被夹到真实量纲");
        assertTrue(huge.floorsMin() <= huge.floorsMax(), "层数区间必须归正");
        assertTrue(huge.weight() <= 20, "权重必须夹到 ≤20");
        assertEquals("gable", huge.roofShape(), "非法屋顶形制必须归一");
        assertTrue(huge.wallColor().startsWith("#"), "非法颜色必须回落默认色");
        // 街区性格保留
        assertEquals(1, c.districts().size());
        assertEquals("老街", c.districts().get(0).name());
    }

    /**
     * AI 全盘产出为空时不许返回空目录（否则 S4 无房可放）。
     */
    @Test
    @DisplayName("S2 解析：AI 产出为空时回落兜底资产库")
    void assetParseFallsBackWhenEmpty() throws Exception {
        MapAssetCatalog c = newAssetService().parse("{\"assetTypes\": []}", MapProfile.fallback(SEED, SIZE));
        assertFalse(c.aiGenerated(), "空产出应按兜底处理");
        assertFalse(c.types().isEmpty());
    }

    // ==================== S3 拓扑 ====================

    /**
     * 非法边必须丢弃/归一，绝不让它进寻路。
     */
    @Test
    @DisplayName("S3 解析：未知节点/自环丢弃，非法等级与类型归一")
    void roadParseDropsInvalidEdges() throws Exception {
        MapRoadPlanService svc = newRoadService();
        String json = """
                {
                  "nodes": [
                    { "code": "plaza_center", "name": "镇中心", "role": "core", "prefer": "settlement-center" },
                    { "code": "harbor_gate", "name": "码头口", "role": "gate", "prefer": "coast" },
                    { "code": "weird", "name": "怪节点", "role": "castle", "prefer": "moon" }
                  ],
                  "roads": [
                    { "code": "main_street", "name": "正街", "kind": "street", "level": "major",
                      "width": 5.0, "surface": "stone", "from": "harbor_gate", "to": "plaza_center",
                      "bend": 0.4, "priority": 10 },
                    { "code": "ghost", "name": "幽灵路", "kind": "lane", "level": "minor",
                      "width": 3.0, "surface": "dirt", "from": "harbor_gate", "to": "nowhere" },
                    { "code": "self_loop", "name": "自环", "kind": "lane", "level": "minor",
                      "width": 3.0, "surface": "dirt", "from": "harbor_gate", "to": "harbor_gate" },
                    { "code": "odd", "name": "怪路", "kind": "rollercoaster", "level": "super",
                      "width": 9.0, "surface": "lava", "from": "weird", "to": "plaza_center" }
                  ]
                }
                """;
        RoadTopology t = svc.parse(json);
        assertTrue(t.aiGenerated());
        List<String> nodeRoles = t.nodes().stream().map(RoadTopology.Node::role).toList();
        assertTrue(nodeRoles.contains("core"));
        assertTrue(nodeRoles.contains("residential"), "非法 role 必须归一成 residential");
        List<String> edgeCodes = t.roads().stream().map(RoadTopology.Edge::code).toList();
        assertTrue(edgeCodes.contains("main_street"));
        assertFalse(edgeCodes.contains("ghost"), "指向未知节点的边必须丢弃");
        assertFalse(edgeCodes.contains("self_loop"), "自环必须丢弃");
        RoadTopology.Edge odd = t.roads().stream().filter(e -> "odd".equals(e.code())).findFirst()
                .orElseThrow();
        assertEquals("lane", odd.kind(), "非法 kind 必须归一");
        assertEquals("minor", odd.level(), "非法 level 必须归一");
    }

    /**
     * 截断抢救：S2 的类型数组写到一半被截断时，已闭合的类型必须留下（真机跑撞到过的场景）。
     */
    @Test
    @DisplayName("S2 截断抢救：数组被切断时保留已闭合的类型")
    void assetParseSalvagesTruncatedArray() throws Exception {
        MapAssetPolicyService svc = newAssetService();
        String json = """
                {
                  "assetTypes": [
                    { "code": "house_fisher", "name": "渔家石屋", "category": "民居", "kind": "building",
                      "baseW": 8.0, "baseD": 7.0, "floorsMin": 1, "floorsMax": 2,
                      "wallColor": "#e0d6c2", "roofColor": "#7d5236", "roofShape": "gable",
                      "material": "stone", "styleTags": ["石墙", "小窗"], "weight": 12, "description": "渔民自住" },
                    { "code": "shop_grocery", "name": "杂货铺", "category": "商铺", "kind": "building",
                      "baseW": 9.0, "baseD": 8.0, "floorsMin": 1, "floorsMax": 2,
                      "wallColor": "#dccfae", "roofColor": "#8a5a3c", "roofShape": "gable",
                      "material": "wood", "styleTags": ["临街"], "weight": 8, "description": "卖米面油盐" },
                    { "code": "truncated_one", "name": "被切断的铺子", "category": "商铺",
                      "baseW": 9.0,
                """;
        MapAssetCatalog catalog = svc.parse(json, MapProfile.fallback(SEED, SIZE));
        assertTrue(catalog.aiGenerated(), "抢救成功仍算 AI 产出");
        List<String> codes = catalog.types().stream().map(MapAssetType::code).toList();
        assertTrue(codes.contains("house_fisher"));
        assertTrue(codes.contains("shop_grocery"));
        assertFalse(codes.contains("truncated_one"), "没写完的对象必须丢掉");
    }

    /**
     * 截断抢救：S3 的两个数组一起捞——只捞 roads 会让边全部指向不存在的节点。
     */
    @Test
    @DisplayName("S3 截断抢救：nodes 与 roads 同时保留，边仍可解析")
    void roadParseSalvagesTruncatedArrays() throws Exception {
        MapRoadPlanService svc = newRoadService();
        String json = """
                {
                  "nodes": [
                    { "code": "plaza_center", "name": "镇中心", "role": "core", "prefer": "settlement-center" },
                    { "code": "harbor_gate", "name": "码头口", "role": "gate", "prefer": "coast" }
                  ],
                  "roads": [
                    { "code": "main_street", "name": "正街", "kind": "street", "level": "major",
                      "width": 5.0, "surface": "stone", "from": "harbor_gate", "to": "plaza_center",
                      "bend": 0.4, "priority": 10 },
                    { "code": "half", "name": "没写完的路", "kind": "lane", "level": "mi
                """;
        RoadTopology t = svc.parse(json);
        assertTrue(t.aiGenerated());
        assertEquals(2, t.nodes().size(), "nodes 必须一起被捞出来");
        assertEquals(1, t.roads().size(), "只有闭合的边可用");
        assertEquals("main_street", t.roads().get(0).code());
    }

    /**
     * 等级 → 宽度/材质的硬约束：土路不能铺石板，田埂不能 5 米宽。
     */
    @Test
    @DisplayName("S3 等级映射：主街 2 级、村庄 1 级、生产路 0 级；宽度按等级夹取")
    void roadGradeMappingAndWidthClamp() {
        assertEquals(RoadGrade.STREET, MapRoadPlanService.gradeOf(edge("major")));
        assertEquals(RoadGrade.VILLAGE, MapRoadPlanService.gradeOf(edge("minor")));
        assertEquals(RoadGrade.TRACK, MapRoadPlanService.gradeOf(edge("production")));
        // 生产路一律土路（AI 说 stone 也不听）
        assertEquals("dirt", MapRoadPlanService.surfaceOf(edge("production", "stone"), RoadGrade.TRACK));
        assertEquals("stone", MapRoadPlanService.surfaceOf(edge("major", "stone"), RoadGrade.STREET));
        // 高等级路允许 AI 指定材质，非法值回落该等级默认材质
        assertEquals("dirt", MapRoadPlanService.surfaceOf(edge("major", "dirt"), RoadGrade.STREET));
        assertEquals("stone", MapRoadPlanService.surfaceOf(edge("major", "lava"), RoadGrade.STREET));
        // 宽度夹取
        assertEquals(2.4, RoadGrade.clampWidth(RoadGrade.TRACK, 5.0), 1e-9);
        assertEquals(1.6, RoadGrade.clampWidth(RoadGrade.TRACK, 0.4), 1e-9);
        assertEquals(4.0, RoadGrade.clampWidth(RoadGrade.STREET, 2.0), 1e-9);
        assertEquals(5.5, RoadGrade.clampWidth(RoadGrade.STREET, 9.9), 1e-9);
        assertEquals(2.8, RoadGrade.clampWidth(RoadGrade.VILLAGE, 2.8), 1e-9);
        assertEquals(RoadGrade.defaultWidth(RoadGrade.TRUNK), RoadGrade.clampWidth(RoadGrade.TRUNK, -1), 1e-9);
    }

    /**
     * 兜底拓扑必须自洽：每条边引用的节点都存在、等级合法、码头/主街都在。
     */
    @Test
    @DisplayName("S3 兜底拓扑：边引用的节点都存在，含主街与生产路")
    void fallbackTopologyIsConsistent() {
        RoadTopology t = RoadTopology.fallback();
        assertFalse(t.aiGenerated());
        List<String> codes = t.nodes().stream().map(RoadTopology.Node::code).toList();
        for (RoadTopology.Edge e : t.roads()) {
            assertTrue(codes.contains(e.from()), "兜底拓扑边引用了不存在的节点：" + e.from());
            assertTrue(codes.contains(e.to()), "兜底拓扑边引用了不存在的节点：" + e.to());
            assertTrue(RoadTopology.LEVELS.contains(e.level()), "兜底拓扑等级非法：" + e.level());
            assertTrue(RoadTopology.EDGE_KINDS.contains(e.kind()), "兜底拓扑类型非法：" + e.kind());
        }
        assertTrue(t.roads().stream().anyMatch(e -> "major".equals(e.level())), "兜底拓扑必须有主干路");
        assertTrue(t.roads().stream().anyMatch(e -> "production".equals(e.level())), "兜底拓扑必须有生产路");
        assertTrue(t.nodes().stream().anyMatch(n -> "core".equals(n.role())), "兜底拓扑必须有核心节点");
    }

    // ==================== 脚手架 ====================

    private static RoadTopology.Edge edge(String level) {
        return edge(level, "dirt");
    }

    private static RoadTopology.Edge edge(String level, String surface) {
        return new RoadTopology.Edge("e", "路", "lane", level, 3.0, surface, "a", "b", 0.5, 5);
    }

    private static WorldMapProfileService newProfileService() {
        MapGenProperties props = props();
        return new WorldMapProfileService(newAiCaller(props), mock(PromptTemplateService.class),
                mock(ActorWorldSettingRepository.class), mock(WorldLocationRepository.class), props);
    }

    private static MapAssetPolicyService newAssetService() {
        MapGenProperties props = props();
        return new MapAssetPolicyService(newAiCaller(props),
                mock(PromptTemplateService.class), props);
    }

    private static MapRoadPlanService newRoadService() {
        MapGenProperties props = props();
        return new MapRoadPlanService(newAiCaller(props),
                mock(PromptTemplateService.class), props);
    }

    /** AI 出口（本测试只跑纯解析路径，网络调用不会被触发）。 */
    private static MapGenAiCaller newAiCaller(MapGenProperties props) {
        return new MapGenAiCaller(mock(AiSpringChatGateway.class), mock(UsageLogService.class),
                mock(CurrentUserProvider.class), props);
    }

    private static MapGenProperties props() {
        MapGenProperties p = new MapGenProperties();
        p.setSize(SIZE);
        p.setMetersPerCell(1.0);
        return p;
    }

    /**
     * 兜底档案必须是一份「能用的通用档案」，而不是某个具体世界的拷贝。
     *
     * <p>旧断言钉的是「青屿镇」——但兜底档案一旦写死某个世界的镇名，就等于把世界观硬编码进了
     * 代码：任何不是青屿镇的项目，AI 失败时都会掉进别人的世界。所以这里只校验结构，
     * 不校验具体地名（地名必须由世界数据/AI 提供）。</p>
     */
    @Test
    @DisplayName("档案摘要：必含地图名/尺度/禁忌，供 S2/S3 复用")
    void profileSummaryContainsRequiredSections() {
        MapProfile p = MapProfile.fallback(SEED, SIZE);
        String s = p.summary();
        assertNotNull(s);
        assertNotNull(p.mapName());
        assertTrue(!p.mapName().isBlank(), "兜底档案也必须给出一个地名");
        assertTrue(s.contains(p.mapName()), "摘要里必须出现地图名");
        assertTrue(s.contains("尺度"));
        assertTrue(s.contains("禁忌"));
        assertTrue(s.contains("地标"));
        assertNull(p.rawJson(), "兜底档案不应带 AI 原文");
    }
}
