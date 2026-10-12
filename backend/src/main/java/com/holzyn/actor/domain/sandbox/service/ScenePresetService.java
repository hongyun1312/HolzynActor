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

package com.holzyn.actor.domain.sandbox.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxScene;
import com.holzyn.actor.domain.sandbox.entity.ActorScenePlacement;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxSceneRepository;
import com.holzyn.actor.domain.sandbox.repository.ActorScenePlacementRepository;
import com.holzyn.actor.domain.sandbox.vo.ScenePresetApplyVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 沙盘场景预设服务（世界沙盘 S3 补全，domain/sandbox/service）。
 * <p>职责：把<b>内置预设布局 JSON</b>（classpath:scene-presets/*.json，打进 jar 开箱即用）
 * 应用到某个场景——校验后写回 {@code actor_sandbox_scene.layout_json}（scene 元信息 + rivers/roads
 * 河路数据 + placements 坐标图）并<b>重建</b>{@code actor_sandbox_placement} 摆放表
 * （先清空再插入，幂等可重跑）。</p>
 * <p>与主方案关系：预设 = SC5「示例村庄一键创建入口」的数据形态；后期 S5/SC3 AI 布局生成
 * （SceneGenService）输出同一 schema（layout_json + actor_sandbox_placement），
 * 与预设应用是并存的两条入口，渲染层零改动——本服务不会被废弃。</p>
 * <p>坐标口径：布局坐标 x/z ∈ [0, size)（主方案 §8.1），y=0 无高度；rotationY 弧度。</p>
 */
@Slf4j
@Service
public class ScenePresetService {

    /** 预设资源目录（classpath 根下） */
    public static final String PRESET_ROOT = "scene-presets";

    /**
     * 场景边长约束：最小 100 且 100 的整数倍（主方案 §8.1 默认 500；
     * 本轮按用户要求放宽到 100m 测试规模，500 仍合法——支持 100/200/…/500/…）
     */
    private static final int MIN_SCENE_SIZE = 100;
    private static final int SCENE_SIZE_STEP = 100;

    private final ActorSandboxSceneRepository sceneRepository;
    private final ActorScenePlacementRepository placementRepository;
    private final ObjectMapper objectMapper;
    private final String presetDir;

    public ScenePresetService(ActorSandboxSceneRepository sceneRepository,
                              ActorScenePlacementRepository placementRepository,
                              ObjectMapper objectMapper,
                              @Value("${holzyn.actor.scene-preset-dir:" + PRESET_ROOT + "}") String presetDir) {
        this.sceneRepository = sceneRepository;
        this.placementRepository = placementRepository;
        this.objectMapper = objectMapper;
        this.presetDir = presetDir;
    }

    // ==================== 预设查询 ====================

    /**
     * 列出全部内置预设（classpath:scene-presets/*.json）。
     *
     * @return [{name: 文件名（不含扩展名）, title: 预设显示名, description?}]
     */
    public List<Map<String, Object>> listPresets() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:" + presetDir + "/*.json");
            for (Resource r : resources) {
                String fn = r.getFilename();
                if (fn == null || !fn.endsWith(".json")) {
                    continue;
                }
                String name = fn.substring(0, fn.length() - 5);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", name);
                try (InputStream in = r.getInputStream()) {
                    JsonNode root = objectMapper.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    item.put("title", root.path("name").asText(name));
                    item.put("description", root.path("description").asText(null));
                } catch (Exception e) {
                    item.put("title", name);
                }
                out.add(item);
            }
        } catch (IOException e) {
            log.warn("[sandbox-preset] 扫描预设目录失败：{}", e.getMessage());
        }
        out.sort((a, b) -> String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name"))));
        return out;
    }

    // ==================== 预设应用 ====================

    /**
     * 应用预设到场景（校验 → 写 layout_json → 重建摆放表，幂等）。
     * <p>⚠️ 必须事务内执行：摆放表「先清空再插入」的派生 deleteBySceneId 依赖活动
     * EntityManager（open-in-view=false 下无事务会抛
     * "No EntityManager with actual transaction available"）。</p>
     *
     * @param projectId 项目 ID（场景归属校验）
     *
     * @param sceneId 场景 ID
     *
     * @param presetName 预设名（scene-presets/{presetName}.json）
     *
     * @return 应用结果 VO
     */
    @Transactional
    public ScenePresetApplyVO applyPreset(Long projectId, Long sceneId, String presetName) {
        if (presetName == null || presetName.isBlank() || presetName.contains("..") || presetName.contains("/")
                || presetName.contains("\\")) {
            throw new BizException(400, "预设名非法：" + presetName);
        }
        ActorSandboxScene scene = sceneRepository.findById(sceneId)
                .filter(s -> projectId.equals(s.getProjectId()))
                .orElseThrow(() -> new BizException(404, "场景不存在或不属于该项目"));

        // 1) 加载预设 JSON
        JsonNode root = loadPreset(presetName);

        // 2) 解析并校验
        JsonNode sceneNode = root.path("scene");
        int size = sceneNode.path("size").asInt(0);
        if (size < MIN_SCENE_SIZE || size % SCENE_SIZE_STEP != 0) {
            throw new BizException(400, "预设 scene.size 非法（最小 " + MIN_SCENE_SIZE + " 且 " + SCENE_SIZE_STEP + " 的整数倍）：" + size);
        }
        String styleCode = sceneNode.path("styleCode").asText("plain");
        String templateCode = sceneNode.path("templateCode").asText("custom");
        long seed = sceneNode.path("seed").asLong(0);

        List<JsonNode> placements = new ArrayList<>();
        JsonNode placementsNode = root.path("placements");
        if (!placementsNode.isArray() || placementsNode.isEmpty()) {
            throw new BizException(400, "预设 placements 为空数组，无法应用");
        }
        Set<String> assetIds = new LinkedHashSet<>();
        for (JsonNode p : placementsNode) {
            String assetId = p.path("assetId").asText("");
            if (assetId.isBlank()) {
                throw new BizException(400, "预设 placements 存在缺失 assetId 的行");
            }
            double posX = p.path("posX").asDouble(Double.NaN);
            double posZ = p.path("posZ").asDouble(Double.NaN);
            if (Double.isNaN(posX) || Double.isNaN(posZ) || posX < 0 || posX > size || posZ < 0 || posZ > size) {
                throw new BizException(400, "预设摆放越界（0.." + size + "）：assetId=" + assetId);
            }
            double scale = p.path("scale").asDouble(1.0);
            if (scale <= 0 || scale > 100) {
                throw new BizException(400, "预设摆放 scale 非法（0<scale≤100）：assetId=" + assetId);
            }
            assetIds.add(assetId);
            placements.add(p);
        }
        List<JsonNode> rivers = checkPathList(root, "rivers", size, "河流");
        List<JsonNode> roads = checkPathList(root, "roads", size, "道路");

        // 3) 组装 layout_json（scene 元信息 + 河路 + 摆放坐标图，主方案 §8.1 结构）
        Map<String, Object> layout = new LinkedHashMap<>();
        Map<String, Object> sceneMeta = new LinkedHashMap<>();
        sceneMeta.put("size", size);
        sceneMeta.put("style", styleCode);
        sceneMeta.put("template", templateCode);
        sceneMeta.put("seed", seed);
        layout.put("scene", sceneMeta);
        layout.put("rivers", rivers.stream().map(this::pathNodeToMap).toList());
        layout.put("roads", roads.stream().map(this::pathNodeToMap).toList());
        layout.put("placements", placements.stream().map(this::placementNodeToMap).toList());

        // 4) 写回场景（布局权威 + 元信息 + 版本 +1）
        scene.setSceneSize(size);
        scene.setStyleCode(styleCode);
        scene.setTemplateCode(templateCode);
        scene.setSeed(seed);
        scene.setLayoutJson(writeJson(layout));
        scene.setSource("template");
        scene.setVersion(scene.getVersion() == null ? 1 : scene.getVersion() + 1);
        sceneRepository.save(scene);

        // 5) 重建摆放表（先清空再插入——幂等重跑不重复；sort_order 按预设顺序）
        placementRepository.deleteBySceneId(sceneId);
        int order = 0;
        List<ActorScenePlacement> rows = new ArrayList<>();
        for (JsonNode p : placements) {
            ActorScenePlacement row = new ActorScenePlacement();
            row.setProjectId(projectId);
            row.setSceneId(sceneId);
            row.setAssetId(p.path("assetId").asText());
            row.setName(textOrNull(p.path("name")));
            row.setPosX(p.path("posX").asDouble());
            row.setPosZ(p.path("posZ").asDouble());
            row.setPosY(0d);
            double scale = p.path("scale").asDouble(1.0);
            row.setScaleX(scale);
            row.setScaleY(scale);
            row.setScaleZ(scale);
            double rotY = p.path("rotY").asDouble(0.0);
            row.setRotY(rotY);
            row.setSortOrder(order++);
            rows.add(row);
        }
        placementRepository.saveAll(rows);

        // 6) 结果摘要
        ScenePresetApplyVO vo = new ScenePresetApplyVO();
        vo.setPresetName(presetName);
        vo.setName(root.path("name").asText(presetName));
        vo.setSceneId(sceneId);
        vo.setSceneName(scene.getName());
        vo.setSceneSize(size);
        vo.setStyleCode(styleCode);
        vo.setSeed(seed);
        vo.setPlacementCount(rows.size());
        vo.setRiverCount(rivers.size());
        vo.setRoadCount(roads.size());
        vo.setAssetIds(List.copyOf(assetIds));
        vo.setMessage("预设已应用：layout_json 与摆放表已重建（幂等）");
        log.info("[sandbox-preset] 应用预设 preset={} sceneId={} 完成：placements={}, rivers={}, roads={}",
                presetName, sceneId, rows.size(), rivers.size(), roads.size());
        return vo;
    }

    // ==================== 内部 ====================

    /** 加载并解析预设 JSON（不存在 → 404） */
    private JsonNode loadPreset(String presetName) {
        Resource r = new PathMatchingResourcePatternResolver()
                .getResource("classpath:" + presetDir + "/" + presetName + ".json");
        if (!r.exists()) {
            throw new BizException(404, "内置预设不存在：" + presetName);
        }
        try (InputStream in = r.getInputStream()) {
            return objectMapper.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new BizException(400, "预设 JSON 解析失败：" + e.getMessage());
        }
    }

    /**
     * 校验河/路数组：path 为 [[x,z],…] 坐标对序列，全部在 [0,size] 内；width 为正数。
     *
     * @return 校验通过的原数组节点（供后续转 Map）
     */
    private List<JsonNode> checkPathList(JsonNode root, String field, int size, String label) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode arr = root.path(field);
        if (arr.isMissingNode() || arr.isNull()) {
            return out;
        }
        if (!arr.isArray()) {
            throw new BizException(400, "预设 " + field + " 应为数组：" + label + "数据格式错误");
        }
        for (JsonNode item : arr) {
            JsonNode path = item.path("path");
            if (!path.isArray() || path.isEmpty()) {
                throw new BizException(400, "预设 " + field + " 存在空 path");
            }
            for (JsonNode pt : path) {
                if (!pt.isArray() || pt.size() < 2 || !pt.get(0).isNumber() || !pt.get(1).isNumber()) {
                    throw new BizException(400, "预设 " + field + " path 坐标应为 [x,z] 数值对");
                }
                double x = pt.get(0).asDouble();
                double z = pt.get(1).asDouble();
                if (x < 0 || x > size || z < 0 || z > size) {
                    throw new BizException(400, "预设 " + field + " path 越界（0.." + size + "）：(" + x + "," + z + ")");
                }
            }
            double width = item.path("width").asDouble(1.0);
            if (width <= 0) {
                throw new BizException(400, "预设 " + field + " width 应为正数");
            }
            out.add(item);
        }
        return out;
    }

    /** path 节点 → Map（{path, width} 保留原始语义） */
    private Map<String, Object> pathNodeToMap(JsonNode item) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<double[]> pts = new ArrayList<>();
        for (JsonNode pt : item.path("path")) {
            pts.add(new double[]{pt.get(0).asDouble(), pt.get(1).asDouble()});
        }
        m.put("path", pts);
        m.put("width", item.path("width").asDouble(1.0));
        return m;
    }

    /** 摆放节点 → Map（{assetId, name?, posX, posZ, rotY?, scale?}） */
    private Map<String, Object> placementNodeToMap(JsonNode p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("assetId", p.path("assetId").asText());
        String name = textOrNull(p.path("name"));
        if (name != null) {
            m.put("name", name);
        }
        m.put("posX", p.path("posX").asDouble());
        m.put("posZ", p.path("posZ").asDouble());
        m.put("rotY", p.path("rotY").asDouble(0.0));
        m.put("scale", p.path("scale").asDouble(1.0));
        return m;
    }

    /** 序列化 layout_json（失败 → 400） */
    private String writeJson(Map<String, Object> layout) {
        try {
            return objectMapper.writeValueAsString(layout);
        } catch (Exception e) {
            throw new BizException(500, "layout_json 序列化失败：" + e.getMessage());
        }
    }

    /** 文本节点取字符串（缺失/空 → null） */
    private String textOrNull(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        String s = n.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
