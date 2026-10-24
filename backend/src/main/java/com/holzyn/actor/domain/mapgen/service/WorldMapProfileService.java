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

import com.holzyn.actor.domain.mapgen.algo.TerrainParams;
import com.holzyn.actor.domain.mapgen.config.MapGenProperties;
import com.holzyn.actor.domain.mapgen.model.MapProfile;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * S0 世界档案：把世界观与地理设定翻译成「一组可解释的地理量 + 地标 + 禁忌清单」。
 *
 * <p><b>为什么这一阶段值得花一次 AI 调用</b>：地形本身是纯数学（噪声 + 侵蚀 + 水系），
 * 但如果「岛多狭长、山脊朝哪、海岸多碎、河有几条」全靠写死，每个世界观生成出来的都是同一座岛，
 * 换题材就穿帮。S0 做的事情是<b>把文字里的地理性格变成数字</b>，让同一套算法能长出
 * 「多雾多风的破碎海岛」也能长出「四周环山的河谷村」。</p>
 *
 * <p><b>兜底纪律（AI 挂掉也得出图）</b>：{@link MapGenProperties#isAiEnabled()} 关闭、AI 调用异常、
 * 输出非法 JSON、世界观缺失，四条路径全部回落到 {@link MapProfile#fallback}——它是确定性的
 * （同 seed 同参数），所以「AI 失败」不会让用户看到一张随机图，而是看到题材档的标准图。
 * 只有<b>真正解析成功</b>的 AI 输出才会进入 {@code aiGenerated=true} 分支。</p>
 *
 * <p><b>事务纪律</b>：本方法含秒级 AI 调用，<b>不得</b>带 {@code @Transactional}
 * （见 {@code AiCallOutsideTransactionTest}）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldMapProfileService {

    /** AI 场景名（用量统计/调试面板着色用） */
    static final String SCENE = "world_map_profile";


    private final MapGenAiCaller ai;
    private final PromptTemplateService promptTemplateService;
    private final ActorWorldSettingRepository worldSettingRepository;
    private final WorldLocationRepository locationRepository;
    private final MapGenProperties props;

    /**
     * 生成世界档案（S0）。
     *
     * @param projectId 项目 ID
     * @param seed      种子（决定论：同种子同图）
     * @return 世界档案（AI 失败/关闭时为确定性兜底档案）
     */
    public MapProfile build(Long projectId, long seed) {
        int size = props.getSize();
        if (!props.isAiEnabled()) {
            log.info("[mapgen] S0 跳过 AI（mapgen.ai-enabled=false），使用海岛兜底档案");
            return MapProfile.fallback(seed, size);
        }
        String worldSetting = worldSettingText(projectId);
        String locations = locationsText(projectId);
        if (worldSetting.isBlank()) {
            log.info("[mapgen] S0 项目 {} 无世界观设定，使用海岛兜底档案", projectId);
            return MapProfile.fallback(seed, size);
        }
        try {
            long start = System.currentTimeMillis();
            String content = ai.content(projectId, prompt(projectId, worldSetting, locations, size));
            MapProfile parsed = parse(content, seed, size);
            log.info("[mapgen] S0 世界档案：map={}, genre={}, {}ms",
                    parsed.mapName(), parsed.genreProfile(), System.currentTimeMillis() - start);
            return parsed;
        } catch (Exception e) {
            log.warn("[mapgen] S0 世界档案生成失败，回落确定性兜底档案：{}", e.getMessage());
            return MapProfile.fallback(seed, size);
        }
    }

    // ==================== AI 调用 ====================

    /**
     * 拼提示词（模板渲染 + 占位符填充），重试与记账交给 {@link MapGenAiCaller}。
     */
    private MapGenAiCaller.Prompt prompt(Long projectId, String worldSetting, String locations, int size) {
        Long userId = ai.currentUserId();
        var vars = new java.util.HashMap<String, Object>();
        vars.put("world_setting", worldSetting);
        vars.put("locations", locations.isBlank() ? "（未设定具体地点）" : locations);
        vars.put("genre_hint", "暖色调治愈系海岛小镇；请按世界观本身判断题材，世界观若已写明以它为准");
        vars.put("size_meters", String.valueOf((int) Math.round(size * props.getMetersPerCell())));
        String systemPrompt = promptTemplateService.systemMessage(userId, projectId,
                PromptTemplateService.CODE_WORLD_MAP_PROFILE, vars);
        String prompt = promptTemplateService.render(userId, projectId,
                PromptTemplateService.CODE_WORLD_MAP_PROFILE, vars);
        return new MapGenAiCaller.Prompt(SCENE, systemPrompt, prompt,
                props.getTemperature(), props.getProfileMaxTokens());
    }

    // ==================== JSON → 档案 ====================

    /**
     * 解析 AI 输出为档案；任何字段缺失/越界都按「题材档默认值 + 夹取」处理，绝不抛异常。
     *
     * @param content AI 原文
     * @param seed    种子
     * @param size    边长
     * @return 档案（{@code aiGenerated=true}）
     * @throws Exception JSON 完全不可解析（由调用方回落兜底）
     */
    MapProfile parse(String content, long seed, int size) throws Exception {
        JsonNode root = MapGenAiJson.read(content, null);
        String json = root.toString();
        TerrainParams p = TerrainParams.island(seed, size);
        p.metersPerCell = props.getMetersPerCell();
        p.genreProfile = text(root, "genreProfile", p.genreProfile);
        // 海陆
        p.islandRadiusRatio = num(root, "islandRadiusRatio", 0.18, 1.05, p.islandRadiusRatio);
        p.elongation = num(root, "elongation", 0.70, 2.0, p.elongation);
        p.rotationDeg = num(root, "rotationDeg", 0, 360, p.rotationDeg);
        p.coastRoughness = num(root, "coastRoughness", 0, 1, p.coastRoughness);
        p.bays = (int) Math.round(num(root, "bays", 0, 5, p.bays));
        p.coastShelf = num(root, "coastShelf", 10, 120, p.coastShelf);
        // 地形
        p.reliefStrength = num(root, "reliefStrength", 0.2, 1.4, p.reliefStrength);
        p.reliefHeight = num(root, "reliefHeight", 0.12, 0.62, p.reliefHeight);
        p.elevationMeters = num(root, "elevationMeters", 30, 900, p.elevationMeters);
        p.peakCount = (int) Math.round(num(root, "peakCount", 0, 5, p.peakCount));
        p.peakHeight = num(root, "peakHeight", 0.05, 0.55, p.peakHeight);
        p.ridgeBearingDeg = num(root, "ridgeBearingDeg", 0, 360, p.ridgeBearingDeg);
        p.plainFraction = num(root, "plainFraction", 0, 0.9, p.plainFraction);
        p.plainRadius = num(root, "plainRadius", 0.10, 0.60, p.plainRadius);
        p.plainCenterX = num(root, "plainCenterX", 0, 1, p.plainCenterX);
        p.plainCenterZ = num(root, "plainCenterZ", 0, 1, p.plainCenterZ);
        // 聚落（S4 用 settleX/settleZ + townRadius；AI 只给「聚落在平原上」）
        p.settlementX = p.plainCenterX;
        p.settlementZ = p.plainCenterZ;
        p.settlementRadius = num(root, "settlementRadius", 0.08, 0.6, p.settlementRadius);
        // 水系
        p.riverCount = (int) Math.round(num(root, "riverCount", 0, 4, p.riverCount));
        p.lakeCount = (int) Math.round(num(root, "lakeCount", 0, 3, p.lakeCount));
        // 气候/植被
        p.temperature = num(root, "temperature", 0, 1, p.temperature);
        p.moisture = num(root, "moisture", 0, 1, p.moisture);
        p.forestBias = num(root, "forestBias", 0.3, 1.4, p.forestBias);
        p.beachWidth = num(root, "beachWidth", 1, 12, p.beachWidth);
        p.erosionStrength = num(root, "erosionStrength", 0, 0.5, p.erosionStrength);

        List<MapProfile.Landmark> marks = new ArrayList<>();
        JsonNode arr = root.path("landmarks");
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                String name = text(n, "name", null);
                if (name == null || name.isBlank()) {
                    continue;
                }
                marks.add(new MapProfile.Landmark(name, text(n, "kind", "landmark"),
                        text(n, "sit", "聚落边缘"), text(n, "note", "")));
            }
        }
        List<String> forbidden = strings(root.path("forbidden"));
        if (forbidden.isEmpty()) {
            forbidden = MapProfile.DEFAULT_FORBIDDEN;
        }
        List<String> categories = strings(root.path("allowedCategories"));
        if (categories.isEmpty()) {
            categories = MapProfile.DEFAULT_CATEGORIES;
        }
        return new MapProfile(text(root, "mapName", "未命名之地"),
                text(root, "genreProfile", p.genreProfile), text(root, "climate", null),
                text(root, "narrative", null), p, marks, forbidden, categories, json, true);
    }

    // ==================== 上下文（世界观 + 地点） ====================

    /**
     * 世界观正文（与旧地图管线同一数据源，保证「地图」和「世界观」说的是同一件事）。
     */
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

    /**
     * 已设定的地点清单（地名 + 类型 + 简介；不含坐标——坐标这阶段还没定）。
     */
    private String locationsText(Long projectId) {
        List<ActorWorldLocation> locations =
                locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(projectId);
        if (locations.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ActorWorldLocation loc : locations) {
            sb.append("- ").append(nvl(loc.getName()))
                    .append("（类型：").append(nvl(loc.getType())).append("）\n");
            String intro = nvl(loc.getIntro());
            if (!intro.isBlank()) {
                sb.append("  ").append(intro.replace('\n', ' ')).append('\n');
            }
        }
        return sb.toString().trim();
    }

    // ==================== 小工具 ====================

    /** 取字符串（缺失/空白 → 默认值）。 */
    private static String text(JsonNode n, String key, String def) {
        JsonNode v = n.path(key);
        if (v.isMissingNode() || v.isNull()) {
            return def;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? def : s.trim();
    }

    /** 取数值并夹到 [lo,hi]（缺失/非数值 → 默认值）。 */
    private static double num(JsonNode n, String key, double lo, double hi, double def) {
        JsonNode v = n.path(key);
        double d = v.isNumber() ? v.asDouble() : def;
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            d = def;
        }
        return Math.max(lo, Math.min(hi, d));
    }

    /** 取字符串数组。 */
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

    /** 空值兜底。 */
    private static String nvl(String s) {
        return s == null ? "" : s.trim();
    }
}
