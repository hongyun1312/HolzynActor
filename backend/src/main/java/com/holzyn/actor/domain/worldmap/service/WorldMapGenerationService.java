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

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxScene;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxSceneRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.worldmap.config.WorldMapProperties;
import com.holzyn.actor.domain.worldmap.dto.WorldMapGenerateRequest;
import com.holzyn.actor.domain.worldmap.model.MapCharacter;
import com.holzyn.actor.domain.worldmap.model.WorldMapLayout;
import com.holzyn.actor.domain.worldmap.vo.WorldMapGenerateVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界地图生成服务（位置移动与地图生成系统 · M1）——编排器：AI 主通道 + 规则校验 + 机制兜底。
 * <p>流程：构建上下文（地点/世界观/角色/关系/既有布局种子）→ <b>AI 生成</b>
 * （scene=world_map_generate，SCHEMA_JSON）→ 解析 → 校验（{@link WorldMapValidator}，
 * 非法行丢弃归一）→ 校验通过 → 落库（AI 来源）；<b>AI 失败/校验失败/未装配</b> →
 * 机制兜底（{@link WorldMapMechanismGenerator}，现实聚落分布规律 + 同居共宅）→ 落库（机制来源）。
 * 世界永不中断（fail-open）。</p>
 * <p>⚠️ 架构不变量：本类含 AI 调用，<b>无 @Transactional</b>；落库由
 * {@link WorldMapPersistService#persist} 短事务承担（AiCallOutsideTransactionTest 守护）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldMapGenerationService {

    /** AI 场景码（PromptSpecRegistry 登记 + PromptTemplateService 内置模板 + AiCallTrace scene） */
    public static final String SCENE_WORLD_MAP_GENERATE = "world_map_generate";

    /** 场景边长缺省 */
    private static final int DEFAULT_SCENE_SIZE = 500;

    private final WorldMapProperties properties;
    private final WorldMapParser parser;
    private final WorldMapValidator validator;
    private final WorldMapMechanismGenerator mechanismGenerator;
    private final WorldMapPersistService persistService;

    private final WorldLocationRepository locationRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRelationRepository relationRepository;
    private final ActorSandboxSceneRepository sceneRepository;
    private final ActorWorldSettingRepository worldSettingRepository;

    private final PromptTemplateService templateService;
    private final AiSpringChatGateway aiChatGateway;
    private final UsageLogService usageLogService;
    private final ObjectMapper objectMapper;

    // ==================== 公开入口 ====================

    /**
     * 生成/重生成世界地图（手动端点：POST /world-map/generate）。
     *
     * @param userId 归属用户 ID（AI 用量归属）
     *
     * @param projectId 项目 ID
     *
     * @param request 生成请求（seed/styleOverride/regenerateHomes；可为 null）
     *
     * @return 生成结果 VO
     */
    public WorldMapGenerateVO generate(Long userId, Long projectId, WorldMapGenerateRequest request) {
        boolean regenerateHomes = request == null || request.getRegenerateHomes() == null
                || Boolean.TRUE.equals(request.getRegenerateHomes());
        long seed = request != null && request.getSeed() != null ? request.getSeed() : defaultSeed(projectId);
        String styleOverride = request == null ? null : request.getStyleOverride();
        return doGenerate(userId, projectId, seed, styleOverride, regenerateHomes, true);
    }

    /**
     * 新项目初始化自动生成（WorldInitService 第 5 步；静默：不发布事件）。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID
     *
     * @return 生成结果 VO
     */
    public WorldMapGenerateVO generateForInit(Long userId, Long projectId) {
        return doGenerate(userId, projectId, defaultSeed(projectId), null, true, false);
    }

    // ==================== 编排 ====================

    private WorldMapGenerateVO doGenerate(Long userId, Long projectId, long seed, String styleOverride,
                                          boolean regenerateHomes, boolean publishEvent) {
        Context ctx = loadContext(projectId, styleOverride);
        if (ctx.locations().isEmpty()) {
            log.warn("[世界地图] 项目 {} 暂无地点，跳过地图生成", projectId);
            WorldMapGenerateVO vo = new WorldMapGenerateVO();
            vo.setProjectId(projectId);
            vo.setSource("skipped");
            vo.setMessage("项目暂无地点，跳过地图生成（请先完成世界观地点提取）");
            return vo;
        }

        // ===== AI 主通道 =====
        if (properties.isEnabled()) {
            try {
                WorldMapLayout aiLayout = tryAiGenerate(userId, projectId, ctx, seed, styleOverride);
                WorldMapValidator.Result check = validator.validate(aiLayout, ctx.locationById(),
                        ctx.characterByRef(), ctx.rivers(), properties, ctx.sceneSize());
                if (!check.valid()) {
                    // 空间修复：AI 精确数值易出错（大半径区域重叠/住所落路/过近）——
                    // 保留 AI 创意分布，规则级修正几何冲突后复校验，仍失败才回退机制。
                    int repaired = validator.repairSpatial(aiLayout, properties, ctx.sceneSize());
                    if (repaired > 0) {
                        check = validator.validate(aiLayout, ctx.locationById(),
                                ctx.characterByRef(), ctx.rivers(), properties, ctx.sceneSize());
                        log.info("[世界地图] AI 布局空间修复 {} 处，复校验 valid={}", repaired, check.valid());
                    }
                }
                if (check.valid()) {
                    log.info("[世界地图] AI 生成成功：project={} regions={} roads={} homes={}",
                            projectId, aiLayout.getRegions().size(), aiLayout.getRoads().size(),
                            aiLayout.getHomes().size());
                    return toVO(projectId, aiLayout,
                            persistService.persist(userId, projectId, aiLayout, regenerateHomes, publishEvent));
                }
                log.warn("[世界地图] AI 输出校验失败（回退机制兜底）：project={} issues={}",
                        projectId, check.issues());
            } catch (Exception e) {
                log.warn("[世界地图] AI 生成失败（回退机制兜底）：project={} : {}", projectId, e.getMessage());
            }
        }

        // ===== 机制兜底 =====
        WorldMapLayout mechLayout = mechanismGenerator.generate(ctx.locations(), ctx.characters(),
                ctx.relations(), ctx.sceneSize(), seed, ctx.style(), properties, ctx.rivers());
        log.info("[世界地图] 机制兜底生成：project={} regions={} homes={}",
                projectId, mechLayout.getRegions().size(), mechLayout.getHomes().size());
        return toVO(projectId, mechLayout,
                persistService.persist(userId, projectId, mechLayout, regenerateHomes, publishEvent));
    }

    // ==================== AI 通道 ====================

    /** AI 生成（渲染模板 → 调用 → 解析；失败抛异常由调用方兜底）。 */
    private WorldMapLayout tryAiGenerate(Long userId, Long projectId, Context ctx, long seed, String styleOverride) {
        long start = System.currentTimeMillis();
        Map<String, Object> ph = new LinkedHashMap<>();
        ph.put("world_setting", ctx.worldSettingText());
        ph.put("locations", ctx.locationsText());
        ph.put("characters", ctx.charactersText());
        ph.put("preset_layout", ctx.presetText());
        ph.put("scene_size", String.valueOf(ctx.sceneSize()));
        String prompt = templateService.render(userId, projectId, SCENE_WORLD_MAP_GENERATE, ph);
        String system = templateService.systemMessage(userId, projectId, SCENE_WORLD_MAP_GENERATE, ph);
        int maxTokens = PromptSpecRegistry.maxOutputTokensOf(SCENE_WORLD_MAP_GENERATE);
        double temperature = PromptSpecRegistry.temperatureOf(SCENE_WORLD_MAP_GENERATE);
        AiChatRequest req = new AiChatRequest(null,
                List.of(new AiChatRequest.ChatMessage("system", system),
                        new AiChatRequest.ChatMessage("user", prompt)),
                temperature, maxTokens, true);
        AiChatResult result = AiCallContext.run(SCENE_WORLD_MAP_GENERATE, null, () ->
                aiChatGateway.chatCompletion(userId, projectId, null, req));
        try {
            usageLogService.record(userId, projectId, null, result.providerId(), result.model(),
                    SCENE_WORLD_MAP_GENERATE, result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(),
                    (int) (System.currentTimeMillis() - start));
        } catch (Exception e) {
            log.warn("[世界地图] AI 用量记录失败（不影响生成）: {}", e.getMessage());
        }
        WorldMapLayout layout = parser.parse(result.content());
        layout.setSceneSize(ctx.sceneSize());
        layout.setStyle(styleOverride == null || styleOverride.isBlank() ? ctx.style() : styleOverride);
        layout.setSeed(seed);
        layout.setSource("ai");
        layout.setMapName(layout.getMapName() == null ? "世界地图（AI 生成）" : layout.getMapName());
        return layout;
    }

    // ==================== 上下文 ====================

    private Context loadContext(Long projectId, String styleOverride) {
        List<ActorWorldLocation> locations =
                locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(projectId);
        List<ActorCharacter> chars =
                characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        List<ActorCharacterRelation> relations = relationRepository.findByProjectId(projectId);
        ActorSandboxScene scene = sceneRepository.findFirstByProjectIdOrderByIdAsc(projectId).orElse(null);

        int sceneSize = scene != null && scene.getSceneSize() != null && scene.getSceneSize() > 0
                ? scene.getSceneSize() : DEFAULT_SCENE_SIZE;
        String style = styleOverride != null && !styleOverride.isBlank()
                ? styleOverride
                : (scene != null && scene.getStyleCode() != null ? scene.getStyleCode() : "village");

        // 角色上下文（特殊 + 普通全量，供同居分组/住所归属）
        List<MapCharacter> characters = new ArrayList<>();
        Map<String, MapCharacter> characterByRef = new LinkedHashMap<>();
        for (ActorCharacter ch : chars) {
            MapCharacter mc = new MapCharacter();
            mc.setRef(NpcRefs.of(ch.getId()));
            mc.setName(ch.getName());
            mc.setType(NpcRefs.normalizeType(ch.getType()));
            mc.setOccupation(ch.getOccupation());
            mc.setAffiliation(ch.getAffiliation());
            mc.setLocation(ch.getLocation());
            mc.setLocationId(ch.getLocationId());
            mc.setImportance(ch.getImportance() == null ? 1 : ch.getImportance());
            mc.setOwnerCandidate("special".equals(mc.getType()));
            if (mc.getRef() != null) {
                characters.add(mc);
                characterByRef.put(mc.getRef(), mc);
            }
        }

        Map<Long, ActorWorldLocation> locationById = new LinkedHashMap<>();
        for (ActorWorldLocation loc : locations) {
            locationById.put(loc.getId(), loc);
        }

        // 世界观上下文
        ActorWorldSetting ws = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .orElse(null);
        String worldSettingText = buildWorldSettingText(ws);

        // 既有布局种子（河/路/摆放概览；供 AI 沿用方向）
        String presetText = buildPresetText(scene);
        List<List<double[]>> rivers = extractRiverPolylines(scene);

        return new Context(sceneSize, style, worldSettingText, buildLocationsText(locations),
                buildCharactersText(characters), presetText, locations, characters, relations,
                locationById, characterByRef, rivers);
    }

    private String buildWorldSettingText(ActorWorldSetting ws) {
        if (ws == null) {
            return "（无世界观设定）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("名称：").append(nvl(ws.getName())).append("\n");
        sb.append("题材：").append(nvl(ws.getGenre())).append("\n");
        sb.append("时代：").append(nvl(ws.getEra())).append("\n");
        String body = nvl(ws.getHistory());
        if (body.isBlank()) {
            body = nvl(ws.getFreeText());
        }
        if (!body.isBlank()) {
            sb.append("概要：").append(body.replace('\n', ' '));
        }
        return sb.toString();
    }

    private String buildLocationsText(List<ActorWorldLocation> locations) {
        StringBuilder sb = new StringBuilder();
        for (ActorWorldLocation loc : locations) {
            sb.append("- [").append(loc.getId()).append("] ").append(nvl(loc.getName()))
                    .append("（类型：").append(nvl(loc.getType()))
                    .append("，重要度：").append(loc.getImportance() == null ? 3 : loc.getImportance())
                    .append("）\n  简介：").append(nvl(loc.getIntro())).append("\n");
        }
        return sb.toString();
    }

    private String buildCharactersText(List<MapCharacter> characters) {
        StringBuilder sb = new StringBuilder();
        for (MapCharacter c : characters) {
            sb.append("- ").append(c.getRef()).append(" ").append(nvl(c.getName()))
                    .append("（").append("special".equals(c.getType()) ? "特殊" : "普通")
                    .append("/").append(nvl(c.getOccupation()))
                    .append("/").append(nvl(c.getAffiliation()))
                    .append("，现居：").append(nvl(c.getLocation())).append("）\n");
        }
        return sb.toString();
    }

    private String buildPresetText(ActorSandboxScene scene) {
        if (scene == null || scene.getLayoutJson() == null || scene.getLayoutJson().isBlank()) {
            return "（无既有布局）";
        }
        try {
            JsonNode root = objectMapper.readTree(scene.getLayoutJson());
            StringBuilder sb = new StringBuilder();
            sb.append("尺寸：").append(scene.getSceneSize() == null ? 500 : scene.getSceneSize()).append("\n");
            JsonNode rivers = root.path("rivers");
            if (rivers.isArray() && !rivers.isEmpty()) {
                sb.append("河流 ").append(rivers.size()).append(" 条：");
                for (JsonNode r : rivers) {
                    sb.append(r.path("name").asText("河流")).append("、");
                }
                sb.setLength(sb.length() - 1);
                sb.append("\n");
            }
            JsonNode roads = root.path("roads");
            if (roads.isArray() && !roads.isEmpty()) {
                sb.append("道路 ").append(roads.size()).append(" 条：");
                for (JsonNode r : roads) {
                    sb.append(r.path("name").asText("道路")).append("、");
                }
                sb.setLength(sb.length() - 1);
                sb.append("\n");
            }
            JsonNode placements = root.path("placements");
            if (placements.isArray() && !placements.isEmpty()) {
                sb.append("摆放 ").append(placements.size()).append(" 条：");
                int n = 0;
                for (JsonNode p : placements) {
                    if (n++ >= 12) {
                        break;
                    }
                    sb.append(p.path("name").asText(p.path("assetId").asText("摆放"))).append("、");
                }
                sb.setLength(sb.length() - 1);
                sb.append("…（河道路径方向可沿用）");
            }
            return sb.toString();
        } catch (Exception e) {
            return "（既有布局解析失败）";
        }
    }

    /** 既有布局河流 → 折线（校验/避让用）。 */
    private List<List<double[]>> extractRiverPolylines(ActorSandboxScene scene) {
        List<List<double[]>> out = new ArrayList<>();
        if (scene == null || scene.getLayoutJson() == null || scene.getLayoutJson().isBlank()) {
            return out;
        }
        try {
            JsonNode root = objectMapper.readTree(scene.getLayoutJson());
            JsonNode rivers = root.path("rivers");
            if (rivers.isArray()) {
                for (JsonNode r : rivers) {
                    List<double[]> poly = new ArrayList<>();
                    for (JsonNode pt : r.path("path")) {
                        if (pt.isArray() && pt.size() >= 2) {
                            poly.add(new double[]{pt.get(0).asDouble(), pt.get(1).asDouble()});
                        }
                    }
                    if (poly.size() >= 2) {
                        out.add(poly);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[世界地图] 既有河流提取失败（忽略）: {}", e.getMessage());
        }
        return out;
    }

    // ==================== 结果组装 ====================

    private WorldMapGenerateVO toVO(Long projectId, WorldMapLayout layout,
                                    WorldMapPersistService.WorldMapPersistResult r) {
        WorldMapGenerateVO vo = new WorldMapGenerateVO();
        vo.setProjectId(projectId);
        vo.setSceneId(r.sceneId());
        vo.setSceneVersion(r.sceneVersion());
        vo.setSource(r.source());
        vo.setMapName(layout.getMapName());
        vo.setRegionCount(r.regionCount());
        vo.setRoadCount(r.roadCount());
        vo.setPlacementCount(r.placementCount());
        vo.setHomeCount(r.homeCount());
        vo.setMemberCount(r.memberCount());
        vo.setMessage("世界地图" + ("ai".equals(r.source()) ? "（AI 生成）" : "（机制生成）") + "完成："
                + r.regionCount() + " 个地点区域 / " + r.roadCount() + " 条道路 / "
                + r.placementCount() + " 个建筑摆放 / " + r.homeCount() + " 间住所（" + r.memberCount() + " 人入住）");
        return vo;
    }

    private long defaultSeed(Long projectId) {
        return 20261006L + (projectId == null ? 0 : projectId) * 7L;
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }

    /** 生成上下文（不可变快照）。 */
    private record Context(int sceneSize, String style, String worldSettingText, String locationsText,
                           String charactersText, String presetText, List<ActorWorldLocation> locations,
                           List<MapCharacter> characters, List<ActorCharacterRelation> relations,
                           Map<Long, ActorWorldLocation> locationById,
                           Map<String, MapCharacter> characterByRef,
                           List<List<double[]>> rivers) {
    }
}
