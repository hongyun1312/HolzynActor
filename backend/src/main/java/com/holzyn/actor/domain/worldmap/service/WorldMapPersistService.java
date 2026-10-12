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
import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxScene;
import com.holzyn.actor.domain.sandbox.entity.ActorScenePlacement;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxSceneRepository;
import com.holzyn.actor.domain.sandbox.repository.ActorScenePlacementRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.worldmap.entity.ActorHomeMember;
import com.holzyn.actor.domain.worldmap.entity.ActorNpcHome;
import com.holzyn.actor.domain.worldmap.model.WorldMapHome;
import com.holzyn.actor.domain.worldmap.model.WorldMapHomeMember;
import com.holzyn.actor.domain.worldmap.model.WorldMapLayout;
import com.holzyn.actor.domain.worldmap.model.WorldMapPlacement;
import com.holzyn.actor.domain.worldmap.model.WorldMapRegion;
import com.holzyn.actor.domain.worldmap.model.WorldMapRoad;
import com.holzyn.actor.domain.worldmap.repository.ActorHomeMemberRepository;
import com.holzyn.actor.domain.worldmap.repository.ActorNpcHomeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界地图生成 · 落库服务（短事务：layout_json v2 + 摆放重建 + 住所/住户 + 地点坐标回填 + 事件）。
 * <p>⚠️ 含写库多表操作，必须短事务；AI 调用在 {@link WorldMapGenerationService}（无事务）完成后再进入本服务。</p>
 * <p>幂等：摆放先清后建、住所/住户按 regenerateHomes 全量重建或补缺、坐标回填按 region 覆盖——
 * 同布局重跑结果一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldMapPersistService {

    /** 落库结果摘要。 */
    public record WorldMapPersistResult(long sceneId, int sceneVersion, String source,
                                        int regionCount, int roadCount, int placementCount,
                                        int homeCount, int memberCount) {
    }

    private final ActorSandboxSceneRepository sceneRepository;
    private final ActorScenePlacementRepository placementRepository;
    private final WorldLocationRepository locationRepository;
    private final ActorNpcHomeRepository homeRepository;
    private final ActorHomeMemberRepository memberRepository;
    private final WorldEventBus worldEventBus;
    private final ObjectMapper objectMapper;

    /**
     * 落库布局（短事务，含事件发布）。
     *
     * @param userId 归属用户 ID（事件发布归属）
     *
     * @param projectId 项目 ID
     *
     * @param layout 布局（AI 或机制兜底）
     *
     * @param regenerateHomes true=全量重建住所（先清后建）/ false=保留既有住所、仅补缺
     *
     * @param publishEvent true=发布 actor_event（手动端点）/ false=静默（初始化流程）
     *
     * @return 落库摘要
     */
    @Transactional
    public WorldMapPersistResult persist(Long userId, Long projectId, WorldMapLayout layout,
                                         boolean regenerateHomes, boolean publishEvent) {
        // ==================== ① 场景（无则创建） ====================
        ActorSandboxScene scene = sceneRepository.findFirstByProjectIdOrderByIdAsc(projectId).orElse(null);
        if (scene == null) {
            scene = new ActorSandboxScene();
            scene.setProjectId(projectId);
            scene.setName("主场景");
            scene.setSceneSize(layout.getSceneSize() > 0 ? layout.getSceneSize() : 500);
            scene.setStyleCode(layout.getStyle() == null ? "plain" : layout.getStyle());
            scene.setTemplateCode("village");
            scene.setSeed(layout.getSeed());
        }
        scene.setSceneSize(layout.getSceneSize() > 0 ? layout.getSceneSize() : scene.getSceneSize());
        if (layout.getStyle() != null && !layout.getStyle().isBlank()) {
            scene.setStyleCode(layout.getStyle());
        }
        scene.setSeed(layout.getSeed());
        scene.setSource("ai".equals(layout.getSource()) ? "ai_generated" : "mechanism");
        scene.setVersion(scene.getVersion() == null ? 1 : scene.getVersion() + 1);
        // ⚠️ 先落库拿到 id（新场景），后续摆放重建/坐标回填/layout_json 均依赖 scene id
        scene = sceneRepository.save(scene);

        // ==================== ② 摆放表重建（先清后建，幂等；记录 id 供 layout_json） ====================
        placementRepository.deleteBySceneId(scene.getId());
        List<WorldMapPlacement> placements = layout.getPlacements() == null ? List.of() : layout.getPlacements();
        List<ActorScenePlacement> rows = new ArrayList<>();
        List<Map<String, Object>> placementNodes = new ArrayList<>();
        int order = 0;
        for (WorldMapPlacement p : placements) {
            ActorScenePlacement row = new ActorScenePlacement();
            row.setProjectId(projectId);
            row.setSceneId(scene.getId());
            row.setAssetId(p.getAssetId());
            row.setName(p.getName());
            row.setPosX(p.getPosX());
            row.setPosY(0d);
            row.setPosZ(p.getPosZ());
            row.setRotY(p.getRotY());
            row.setSortOrder(order);
            row = placementRepository.save(row);
            rows.add(row);

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", row.getId());
            node.put("name", p.getName());
            if (p.getCategory() != null) {
                node.put("category", p.getCategory());
            }
            if (p.getAssetId() != null) {
                node.put("assetId", p.getAssetId());
            }
            node.put("posX", p.getPosX());
            node.put("posZ", p.getPosZ());
            node.put("rotY", p.getRotY());
            node.put("scale", 1.0);
            if (p.getLocationId() != null) {
                node.put("locationId", p.getLocationId());
            }
            placementNodes.add(node);
            order++;
        }

        // ==================== ③ 地点坐标回填 + anchor 映射 ====================
        List<Map<String, Object>> regionNodes = new ArrayList<>();
        List<Map<String, Object>> anchorNodes = new ArrayList<>();
        Map<Long, ActorWorldLocation> locationById = new LinkedHashMap<>();
        for (ActorWorldLocation loc : locationRepository.findByProjectId(projectId)) {
            locationById.put(loc.getId(), loc);
        }
        for (WorldMapRegion r : layout.getRegions() == null ? List.<WorldMapRegion>of() : layout.getRegions()) {
            ActorWorldLocation loc = locationById.get(r.getLocationId());
            if (loc != null) {
                loc.setCenterX(r.getCenterX());
                loc.setCenterZ(r.getCenterZ());
                loc.setRadius(r.getRadius());
                loc.setRegionDesc(r.getRegionDesc());
                locationRepository.save(loc);
            }
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("locationId", r.getLocationId());
            node.put("name", r.getName());
            node.put("centerX", r.getCenterX());
            node.put("centerZ", r.getCenterZ());
            node.put("radius", r.getRadius());
            node.put("shape", r.getShape() == null ? "circle" : r.getShape());
            if (r.getRegionDesc() != null) {
                node.put("regionDesc", r.getRegionDesc());
            }
            regionNodes.add(node);
            anchorNodes.add(Map.of("locationId", r.getLocationId(), "locationName",
                    r.getName() == null ? "" : r.getName(), "x", r.getCenterX(), "y", 0d, "z", r.getCenterZ()));
        }

        // ==================== ④ 河流保留（既有布局） ====================
        List<Map<String, Object>> riverNodes = extractRivers(scene.getLayoutJson());

        // ==================== ⑤ 道路 ====================
        List<Map<String, Object>> roadNodes = new ArrayList<>();
        for (WorldMapRoad road : layout.getRoads() == null ? List.<WorldMapRoad>of() : layout.getRoads()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("name", road.getName());
            List<double[]> pts = new ArrayList<>();
            for (double[] pt : road.getPath()) {
                pts.add(new double[]{pt[0], pt[1]});
            }
            node.put("path", pts);
            node.put("width", road.getWidth());
            roadNodes.add(node);
        }

        // ==================== ⑥ layout_json v2（regions 升级） ====================
        Map<String, Object> layoutJson = new LinkedHashMap<>();
        Map<String, Object> sceneMeta = new LinkedHashMap<>();
        sceneMeta.put("size", scene.getSceneSize());
        sceneMeta.put("style", scene.getStyleCode());
        sceneMeta.put("template", scene.getTemplateCode());
        sceneMeta.put("seed", scene.getSeed());
        layoutJson.put("scene", sceneMeta);
        layoutJson.put("regions", regionNodes);
        layoutJson.put("rivers", riverNodes);
        layoutJson.put("roads", roadNodes);
        layoutJson.put("placements", placementNodes);
        scene.setLayoutJson(writeJson(layoutJson));
        scene.setAnchorJson(writeJson(anchorNodes));
        sceneRepository.save(scene);

        // ==================== ⑦ 住所 + 住户（同居共宅） ====================
        int homeCount = 0;
        int memberCount = 0;
        // 已覆盖角色（有住所的）：regenerateHomes=true → 全量重建（清空）；false → 保留既有住所
        java.util.Set<String> covered = new java.util.HashSet<>();
        if (regenerateHomes) {
            memberRepository.deleteByProjectId(projectId);
            homeRepository.deleteByProjectId(projectId);
        } else {
            for (ActorHomeMember existing : memberRepository.findByProjectIdOrderByHomeIdAscIdAsc(projectId)) {
                covered.add(existing.getCharacterRef());
            }
        }
        for (WorldMapHome h : layout.getHomes() == null ? List.<WorldMapHome>of() : layout.getHomes()) {
            ActorNpcHome home = new ActorNpcHome();
            home.setProjectId(projectId);
            home.setName(truncate(h.getName(), 100));
            home.setLocationId(h.getLocationId());
            home.setPosX(h.getPosX());
            home.setPosZ(h.getPosZ());
            home.setRoomLabel(truncate(h.getRoomLabel(), 100));
            home.setRegion(truncate(h.getRegion(), 50));
            home.setNote(truncate(h.getNote(), 200));
            // regenerateHomes=false：该住所全部住户已有住所 → 跳过（保留既有）
            boolean allCovered = !h.getMembers().isEmpty()
                    && h.getMembers().stream().allMatch(m -> covered.contains(m.getCharacterRef()));
            if (!regenerateHomes && allCovered) {
                continue;
            }
            home = homeRepository.save(home);
            homeCount++;
            for (WorldMapHomeMember m : h.getMembers()) {
                if (!regenerateHomes && covered.contains(m.getCharacterRef())) {
                    continue; // 该角色已有住所，保留既有
                }
                ActorHomeMember member = new ActorHomeMember();
                member.setProjectId(projectId);
                member.setHomeId(home.getId());
                member.setCharacterRef(m.getCharacterRef());
                member.setIsOwner(m.isOwner() ? ActorHomeMember.OWNER_YES : ActorHomeMember.OWNER_NO);
                member.setRole(truncate(m.getRole() == null ? "同住" : m.getRole(), 30));
                member.setRoomLabel(truncate(m.getRoomLabel(), 100));
                memberRepository.save(member);
                covered.add(m.getCharacterRef());
                memberCount++;
            }
        }

        // ==================== ⑧ 事件（world_map；幂等 sourceKey=场景版本） ====================
        if (publishEvent) {
            try {
                worldEventBus.publish(userId, projectId, EventSpec.builder()
                        .kind("world_map")
                        .source("ai".equals(layout.getSource()) ? "ai" : "simulation")
                        .visibility("global")
                        .title("世界地图" + ("ai".equals(layout.getSource()) ? "（AI 生成）" : "（机制生成）"))
                        .content(String.format("地图「%s」已生成：%d 个地点区域 / %d 条道路 / %d 个建筑摆放 / %d 间住所（%d 人入住）",
                                layout.getMapName(), regionNodes.size(), roadNodes.size(), placementNodes.size(),
                                homeCount, memberCount))
                        .importance(2)
                        .sourceKey("world_map:" + projectId + ":v" + scene.getVersion())
                        .build());
            } catch (Exception e) {
                log.warn("[世界地图] 事件发布失败（不影响落库）: project={} : {}", projectId, e.getMessage());
            }
        }

        log.info("[世界地图] 落库完成：project={} scene={} source={} regions={} roads={} placements={} homes={} members={}",
                projectId, scene.getId(), layout.getSource(), regionNodes.size(), roadNodes.size(),
                placementNodes.size(), homeCount, memberCount);
        return new WorldMapPersistResult(scene.getId(), scene.getVersion(), layout.getSource(),
                regionNodes.size(), roadNodes.size(), placementNodes.size(), homeCount, memberCount);
    }

    /** 提取既有 layout_json 的河流（v1/v2 兼容；缺失返回空）。 */
    private List<Map<String, Object>> extractRivers(String layoutJson) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (layoutJson == null || layoutJson.isBlank()) {
            return out;
        }
        try {
            JsonNode root = objectMapper.readTree(layoutJson);
            JsonNode rivers = root.path("rivers");
            if (rivers.isArray()) {
                for (JsonNode r : rivers) {
                    Map<String, Object> node = new LinkedHashMap<>();
                    node.put("name", r.path("name").asText("河流"));
                    List<double[]> pts = new ArrayList<>();
                    for (JsonNode pt : r.path("path")) {
                        if (pt.isArray() && pt.size() >= 2) {
                            pts.add(new double[]{pt.get(0).asDouble(), pt.get(1).asDouble()});
                        }
                    }
                    node.put("path", pts);
                    node.put("width", r.path("width").asDouble(8.0));
                    out.add(node);
                }
            }
        } catch (Exception e) {
            log.warn("[世界地图] 既有布局河流提取失败（忽略）: {}", e.getMessage());
        }
        return out;
    }

    /** 文本截断（字段长度保护，null 安全）。 */
    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 序列化 JSON（失败 → 500）。 */
    private String writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new BizException(500, "JSON 序列化失败：" + e.getMessage());
        }
    }
}
