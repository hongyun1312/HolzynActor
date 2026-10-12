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

package com.holzyn.actor.domain.worldmap.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.domain.worldmap.model.WorldMapHome;
import com.holzyn.actor.domain.worldmap.model.WorldMapHomeMember;
import com.holzyn.actor.domain.worldmap.model.WorldMapLayout;
import com.holzyn.actor.domain.worldmap.model.WorldMapPlacement;
import com.holzyn.actor.domain.worldmap.model.WorldMapRegion;
import com.holzyn.actor.domain.worldmap.model.WorldMapRoad;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 世界地图生成 · AI 输出解析器（world_map_generate 场景 SCHEMA_JSON 输出 → {@link WorldMapLayout}）。
 * <p>容错口径：剥离 Markdown 代码块（JsonUtil.extractJson）、数值宽松解析、缺失数组按空处理、
 * 字段缺失回退默认——解析只负责「结构还原」，合法性由 {@link WorldMapValidator} 把关。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorldMapParser {

    private final ObjectMapper objectMapper;

    /** 文本截断上限（字段级保护） */
    private static final int FIELD_MAX = 200;

    /**
     * 解析 AI 输出为布局模型。
     *
     * @param content AI 输出文本
     *
     * @return 布局模型（结构合法但未校验语义）
     */
    public WorldMapLayout parse(String content) {
        String json = JsonUtil.extractJson(content);
        if (json == null) {
            throw new BizException("AI 未返回有效 JSON");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new BizException("AI 世界地图输出 JSON 解析失败：" + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new BizException("AI 世界地图输出应为 JSON 对象");
        }
        WorldMapLayout layout = new WorldMapLayout();
        layout.setMapName(text(root.get("mapName"), "世界地图"));
        layout.setRegions(parseRegions(root.path("regions")));
        layout.setRoads(parseRoads(root.path("roads")));
        layout.setPlacements(parsePlacements(root.path("placements")));
        layout.setHomes(parseHomes(root.path("homes")));
        return layout;
    }

    // ==================== 分项解析 ====================

    private List<WorldMapRegion> parseRegions(JsonNode arr) {
        List<WorldMapRegion> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            if (n == null || !n.isObject()) {
                continue;
            }
            WorldMapRegion r = new WorldMapRegion();
            r.setLocationId(num(n.get("locationId")));
            r.setName(text(n.get("name"), null));
            r.setCenterX(dbl(n.get("centerX")));
            r.setCenterZ(dbl(n.get("centerZ")));
            r.setRadius(dbl(n.get("radius")));
            r.setShape(text(n.get("shape"), "circle"));
            r.setRegionDesc(truncate(text(n.get("regionDesc"), null)));
            if (r.getLocationId() != null && r.getRadius() > 0) {
                out.add(r);
            }
        }
        return out;
    }

    private List<WorldMapRoad> parseRoads(JsonNode arr) {
        List<WorldMapRoad> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            if (n == null || !n.isObject()) {
                continue;
            }
            WorldMapRoad road = new WorldMapRoad();
            road.setName(text(n.get("name"), null));
            road.setWidth(dbl(n.get("width")) > 0 ? dbl(n.get("width")) : 3.0);
            JsonNode path = n.get("path");
            if (path != null && path.isArray()) {
                for (JsonNode pt : path) {
                    if (pt != null && pt.isArray() && pt.size() >= 2
                            && pt.get(0).isNumber() && pt.get(1).isNumber()) {
                        road.getPath().add(new double[]{pt.get(0).asDouble(), pt.get(1).asDouble()});
                    }
                }
            }
            if (road.getPath().size() >= 2) {
                out.add(road);
            }
        }
        return out;
    }

    private List<WorldMapPlacement> parsePlacements(JsonNode arr) {
        List<WorldMapPlacement> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            if (n == null || !n.isObject()) {
                continue;
            }
            WorldMapPlacement p = new WorldMapPlacement();
            p.setName(text(n.get("name"), null));
            p.setCategory(text(n.get("category"), null));
            p.setAssetId(text(n.get("assetId"), null));
            p.setPosX(dbl(n.get("posX")));
            p.setPosZ(dbl(n.get("posZ")));
            p.setRotY(dbl(n.get("rotY")));
            p.setLocationId(num(n.get("locationId")));
            out.add(p);
        }
        return out;
    }

    private List<WorldMapHome> parseHomes(JsonNode arr) {
        List<WorldMapHome> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            if (n == null || !n.isObject()) {
                continue;
            }
            WorldMapHome h = new WorldMapHome();
            h.setName(text(n.get("name"), null));
            h.setLocationId(num(n.get("locationId")));
            h.setPosX(dbl(n.get("posX")));
            h.setPosZ(dbl(n.get("posZ")));
            h.setRoomLabel(text(n.get("roomLabel"), null));
            h.setRegion(text(n.get("region"), null));
            h.setNote(truncate(text(n.get("note"), null)));
            JsonNode members = n.get("members");
            if (members != null && members.isArray()) {
                for (JsonNode m : members) {
                    if (m == null || !m.isObject()) {
                        continue;
                    }
                    WorldMapHomeMember mm = new WorldMapHomeMember();
                    mm.setCharacterRef(text(m.get("characterRef"), null));
                    mm.setRole(text(m.get("role"), null));
                    mm.setRoomLabel(text(m.get("roomLabel"), null));
                    mm.setOwner("户主".equals(mm.getRole()));
                    if (mm.getCharacterRef() != null && !mm.getCharacterRef().isBlank()) {
                        h.getMembers().add(mm);
                    }
                }
            }
            if (!h.getMembers().isEmpty()) {
                out.add(h);
            }
        }
        return out;
    }

    // ==================== 工具 ====================

    /** 文本节点（缺失/空 → def）。 */
    private String text(JsonNode n, String def) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return def;
        }
        String s = n.asText().trim();
        return s.isEmpty() ? def : s;
    }

    /** 数值节点 → double（缺失/非法 → 0）。 */
    private double dbl(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull() || !n.isNumber()) {
            return 0d;
        }
        return n.asDouble();
    }

    /** 数值节点 → long（缺失/非法 → null）。 */
    private Long num(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull() || !n.isNumber()) {
            return null;
        }
        return n.asLong();
    }

    /** 截断超长文本。 */
    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= FIELD_MAX ? s : s.substring(0, FIELD_MAX);
    }
}
