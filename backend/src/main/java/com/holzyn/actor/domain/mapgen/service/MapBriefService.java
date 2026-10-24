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

import com.holzyn.actor.domain.mapgen.algo.MapBriefValidator;
import com.holzyn.actor.domain.mapgen.config.MapGenProperties;
import com.holzyn.actor.domain.mapgen.model.WorldMapBrief;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.worldmap.entity.ActorNpcHome;
import com.holzyn.actor.domain.worldmap.repository.ActorNpcHomeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * S0b 世界→地图契约：把「世界观 + 地点 + 住所」翻译成 {@link WorldMapBrief}。
 *
 * <p><b>AI 的职责边界（铁律）</b>：只判断「哪些是设施、是什么类型、大致多少尺寸、
 * 在岛的哪个方位、区域怎么分」，<b>绝不输出任何坐标</b>。坐标与几何全部由程序求。</p>
 *
 * <p><b>人口不交给 AI</b>：户数直接数住所表，户均人口取
 * {@link WorldMapBrief#DEFAULT_HOUSEHOLD_SIZE}。这是「12 户 → 十几栋屋」的唯一权威，
 * 避免 AI 把「小镇」想象成 500 栋房。</p>
 *
 * <p><b>兜底纪律</b>：AI 关闭 / 调用失败 / 输出非法 / 校验致命 → 一律回落
 * {@link WorldMapBrief#genericFallback(int)}（只有户数的通用契约，不含任何世界观内容）。</p>
 *
 * <p><b>事务纪律</b>：含秒级 AI 调用，<b>不得</b>带 {@code @Transactional}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MapBriefService {

    /** AI 场景名。 */
    static final String SCENE = "world_map_brief";

    private final MapGenAiCaller ai;
    private final PromptTemplateService promptTemplateService;
    private final ActorWorldSettingRepository worldSettingRepository;
    private final WorldLocationRepository locationRepository;
    private final ActorNpcHomeRepository homeRepository;
    private final MapGenProperties props;

    /**
     * 构建世界→地图契约。
     *
     * @param projectId 项目 ID
     * @param seed      种子（只进提示词做「口味」，不参与几何）
     * @return 契约（永不为 null）
     */
    public WorldMapBrief build(Long projectId, long seed) {
        List<ActorNpcHome> homes = homes(projectId);
        WorldMapBrief fallback = WorldMapBrief.genericFallback(Math.max(1, homes.size()));
        if (!props.isAiEnabled()) {
            log.info("[mapgen] S0b 跳过 AI（mapgen.ai-enabled=false），契约只用住所推导：{} 户", homes.size());
            return withDwellings(fallback, homes, locations(projectId));
        }
        String worldSetting = worldSettingText(projectId);
        List<ActorWorldLocation> locations = locations(projectId);
        if (worldSetting.isBlank() && locations.isEmpty()) {
            log.info("[mapgen] S0b 项目 {} 无世界观/地点数据，契约只用住所推导：{} 户", projectId, homes.size());
            return withDwellings(fallback, homes, locations);
        }
        try {
            long start = System.currentTimeMillis();
            String content = ai.content(projectId,
                    prompt(projectId, worldSetting, locationsText(locations), homesText(homes), seed));
            WorldMapBrief brief = parse(content, homes, locations);
            List<String> problems = MapBriefValidator.validate(brief);
            if (!problems.isEmpty()) {
                log.warn("[mapgen] S0b 契约校验发现 {} 个问题：{}", problems.size(), problems);
            }
            if (MapBriefValidator.fatal(problems)) {
                log.warn("[mapgen] S0b 契约有致命问题，回落通用兜底（{} 户）", homes.size());
                return withDwellings(fallback, homes, locations);
            }
            log.info("[mapgen] S0b 契约：{} 个区域 / {} 个设施 / {} 户 / 预算 {} 栋，{}ms",
                    brief.regions().size(), brief.facilities().size(),
                    brief.demography().households(), brief.buildingBudget(),
                    System.currentTimeMillis() - start);
            return brief;
        } catch (Exception e) {
            log.warn("[mapgen] S0b 契约生成失败，回落通用兜底：{}", e.getMessage());
            return withDwellings(fallback, homes, locations);
        }
    }

    // ==================== AI 调用 ====================

    private MapGenAiCaller.Prompt prompt(Long projectId, String worldSetting, String locations,
                                         String homes, long seed) {
        Long userId = ai.currentUserId();
        Map<String, Object> vars = new HashMap<>();
        vars.put("world_setting", worldSetting.isBlank() ? "（未设定）" : worldSetting);
        vars.put("locations", locations.isBlank() ? "（未设定具体地点）" : locations);
        vars.put("homes", homes.isBlank() ? "（未设定住所）" : homes);
        vars.put("seed_hint", String.valueOf(seed));
        String systemPrompt = promptTemplateService.systemMessage(userId, projectId,
                PromptTemplateService.CODE_WORLD_MAP_BRIEF, vars);
        String prompt = promptTemplateService.render(userId, projectId,
                PromptTemplateService.CODE_WORLD_MAP_BRIEF, vars);
        return new MapGenAiCaller.Prompt(SCENE, systemPrompt, prompt,
                props.getTemperature(), 2048);
    }

    // ==================== JSON → 契约 ====================

    /**
     * 解析 AI 输出；人口学与住宅一律来自住所表（不被 AI 影响）。
     *
     * @param content AI 原文
     * @param homes   住所
     * @param locs    世界地点（校验锚点用）
     * @return 契约
     * @throws Exception JSON 完全不可解析
     */
    WorldMapBrief parse(String content, List<ActorNpcHome> homes, List<ActorWorldLocation> locs)
            throws Exception {
        JsonNode root = MapGenAiJson.read(content, "regions");
        String raw = root.toString();
        String family = text(root, "terrainFamily", "ISLAND").toUpperCase(Locale.ROOT);
        List<WorldMapBrief.Region> regions = new ArrayList<>();
        JsonNode rarr = root.path("regions");
        if (rarr.isArray()) {
            for (JsonNode n : rarr) {
                String name = text(n, "name", null);
                if (name == null || name.isBlank()) {
                    continue;
                }
                regions.add(new WorldMapBrief.Region(
                        text(n, "code", "region_" + regions.size()),
                        name,
                        text(n, "kind", "GENERIC").toUpperCase(Locale.ROOT),
                        clamp(num(n, "bearingDeg", 0), 0, 360),
                        clamp(num(n, "radialFrom", 0.1), 0, 1),
                        clamp(num(n, "radialTo", 0.8), 0, 1),
                        clamp(num(n, "elevationBias", 0), -1, 1),
                        clamp(num(n, "treeDensity", 0.3), 0, 1),
                        clamp(num(n, "moistureBias", 0), -1, 1)));
            }
        }
        WorldMapBrief.Settlement settlement = null;
        JsonNode sn = root.path("settlement");
        if (sn.isObject()) {
            settlement = new WorldMapBrief.Settlement(
                    clamp(num(sn, "centerBearingDeg", 180), 0, 360),
                    clamp(num(sn, "radiusM", 160), 20, 600),
                    clamp(num(sn, "slopeMax", 0.18), 0.05, 0.6));
        }
        List<WorldMapBrief.Facility> facilities = new ArrayList<>();
        JsonNode farr = root.path("facilities");
        if (farr.isArray()) {
            Set<String> locationNames = new HashSet<>();
            for (ActorWorldLocation l : locs) {
                if (l.getName() != null) {
                    locationNames.add(l.getName().trim());
                }
            }
            for (JsonNode n : farr) {
                String name = text(n, "name", null);
                if (name == null || name.isBlank()) {
                    continue;
                }
                String anchorRaw = text(n, "anchorLocation", "");
                String anchor = anchorRaw;
                // 锚点必须是真的世界地点（AI 爱编一个不存在的名字）
                if (anchorRaw != null && !anchorRaw.isBlank() && !locationNames.isEmpty()) {
                    String probe = anchorRaw;
                    boolean known = locationNames.contains(probe)
                            || locationNames.stream().anyMatch(l -> l.contains(probe) || probe.contains(l));
                    if (!known) {
                        anchor = "";
                    }
                }
                facilities.add(new WorldMapBrief.Facility(
                        text(n, "code", "facility_" + facilities.size()),
                        name,
                        text(n, "kind", "building").toLowerCase(Locale.ROOT),
                        anchor,
                        text(n, "role", "公共"),
                        clamp(num(n, "frontageM", 9), 2, 60),
                        clamp(num(n, "depthM", 8), 2, 60),
                        (int) Math.round(clamp(num(n, "floors", 1), 1, 4))));
            }
        }
        int householdSize = (int) Math.round(clamp(num(root, "householdSize",
                WorldMapBrief.DEFAULT_HOUSEHOLD_SIZE), 1, 8));
        WorldMapBrief.Demography demo = new WorldMapBrief.Demography(
                Math.max(1, homes.size()), householdSize);
        List<WorldMapBrief.Dwelling> dwellings = dwellings(homes, locs);
        List<String> forbidden = strings(root.path("forbidden"));
        List<String> categories = strings(root.path("allowedCategories"));
        return new WorldMapBrief(family, regions, settlement, demo, facilities, dwellings,
                forbidden, categories, raw, true);
    }

    // ==================== 上下文 ====================

    private List<ActorNpcHome> homes(Long projectId) {
        try {
            return homeRepository.findByProjectIdOrderByIdAsc(projectId);
        } catch (Exception e) {
            log.warn("[mapgen] S0b 读取住所失败：{}", e.getMessage());
            return List.of();
        }
    }

    private List<ActorWorldLocation> locations(Long projectId) {
        try {
            return locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(projectId);
        } catch (Exception e) {
            log.warn("[mapgen] S0b 读取地点失败：{}", e.getMessage());
            return List.of();
        }
    }

    /** 世界观正文（与 S0 同源，保证地图与世界观说的是同一件事）。 */
    private String worldSettingText(Long projectId) {
        ActorWorldSetting ws = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .orElse(null);
        if (ws == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("名称：").append(nvl(ws.getName())).append('\n');
        sb.append("题材：").append(nvl(ws.getGenre())).append('\n');
        sb.append("时代：").append(nvl(ws.getEra())).append('\n');
        String body = nvl(ws.getHistory());
        if (body.isBlank()) {
            body = nvl(ws.getFreeText());
        }
        if (!body.isBlank()) {
            sb.append("概要：").append(body.replace('\n', ' '));
        }
        return sb.toString().trim();
    }

    private String locationsText(List<ActorWorldLocation> locs) {
        if (locs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ActorWorldLocation loc : locs) {
            sb.append("- ").append(nvl(loc.getName()))
                    .append("（类型：").append(nvl(loc.getType())).append("）\n");
            String intro = nvl(loc.getIntro());
            if (!intro.isBlank()) {
                sb.append("  ").append(intro.replace('\n', ' ')).append('\n');
            }
        }
        return sb.toString().trim();
    }

    /**
     * 住所清单（只给名字与所属地点，<b>不给坐标</b>——坐标是地图的产出，不是输入）。
     */
    private String homesText(List<ActorNpcHome> homes) {
        if (homes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ActorNpcHome h : homes) {
            sb.append("- ").append(nvl(h.getName()))
                    .append("（所在地点：").append(nvl(h.getRegion())).append("）\n");
        }
        return sb.toString().trim();
    }

    private List<WorldMapBrief.Dwelling> dwellings(List<ActorNpcHome> homes,
                                                   List<ActorWorldLocation> locs) {
        Map<Long, String> locName = new LinkedHashMap<>();
        for (ActorWorldLocation l : locs) {
            locName.put(l.getId(), l.getName());
        }
        List<WorldMapBrief.Dwelling> out = new ArrayList<>();
        for (ActorNpcHome h : homes) {
            String anchor = h.getLocationId() == null ? "" : nvl(locName.get(h.getLocationId()));
            out.add(new WorldMapBrief.Dwelling(String.valueOf(h.getId()), nvl(h.getName()),
                    anchor, List.of()));
        }
        return out;
    }

    private WorldMapBrief withDwellings(WorldMapBrief base, List<ActorNpcHome> homes,
                                        List<ActorWorldLocation> locs) {
        return new WorldMapBrief(base.terrainFamily(), base.regions(), base.settlement(),
                base.demography(), base.facilities(), dwellings(homes, locs),
                base.forbidden(), base.allowedCategories(), null, false);
    }

    // ==================== 小工具 ====================

    private static String text(JsonNode n, String key, String def) {
        JsonNode v = n.path(key);
        if (v.isMissingNode() || v.isNull()) {
            return def;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? def : s.trim();
    }

    private static double num(JsonNode n, String key, double def) {
        JsonNode v = n.path(key);
        double d = v.isNumber() ? v.asDouble() : def;
        return Double.isNaN(d) || Double.isInfinite(d) ? def : d;
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                String s = n.asText();
                if (s != null && !s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String nvl(String s) {
        return s == null ? "" : s.trim();
    }
}
