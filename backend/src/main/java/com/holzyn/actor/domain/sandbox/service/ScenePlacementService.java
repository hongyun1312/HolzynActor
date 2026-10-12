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

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxScene;
import com.holzyn.actor.domain.sandbox.entity.ActorSceneAsset;
import com.holzyn.actor.domain.sandbox.entity.ActorScenePlacement;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxSceneRepository;
import com.holzyn.actor.domain.sandbox.repository.ActorSceneAssetRepository;
import com.holzyn.actor.domain.sandbox.repository.ActorScenePlacementRepository;
import com.holzyn.actor.domain.sandbox.vo.ScenePlacementVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 沙盘资产摆放服务（世界沙盘 S3，domain/sandbox/service）。
 * <p>职责：资产摆放记录（actor_scene_placement）的 CRUD 与资产登记表信息解析：
 * 按场景列表 / 创建 / 更新 / 删除 / 按项目聚合（overview 用）。
 * 场景归属校验（场景属于项目）+ 资产信息解析（asset_id → 资产名/分类/GLB 路径）在此统一完成。</p>
 */
@Service
@RequiredArgsConstructor
public class ScenePlacementService {

    private final ActorScenePlacementRepository placementRepository;
    private final ActorSandboxSceneRepository sceneRepository;
    private final ActorSceneAssetRepository assetRepository;

    /**
     * 按场景查询摆放列表（sort_order 升序 + id 升序；校验场景存在且属于项目）。
     *
     * @param projectId 项目 ID
     *
     * @param sceneId 场景 ID
     *
     * @return 摆放 VO 列表
     */
    public List<ScenePlacementVO> list(Long projectId, Long sceneId) {
        requireScene(projectId, sceneId);
        return placementRepository.findBySceneIdOrderBySortOrderAscIdAsc(sceneId)
                .stream().map(this::toVO).toList();
    }

    /**
     * 创建一条摆放记录（asset_id 必填；其余字段可缺省，默认地面高度/单位缩放/零朝向）。
     *
     * @param projectId 项目 ID
     *
     * @param sceneId 场景 ID
     *
     * @return 新建摆放 VO
     */
    public ScenePlacementVO create(Long projectId, Long sceneId, Map<String, Object> body) {
        requireScene(projectId, sceneId);
        String assetId = str(body, "assetId");
        if (assetId == null) {
            throw new BizException(400, "assetId 不能为空（资产稳定标识，如 building_inn_01）");
        }
        ActorScenePlacement p = new ActorScenePlacement();
        p.setProjectId(projectId);
        p.setSceneId(sceneId);
        p.setAssetId(assetId);
        apply(p, body);
        return toVO(placementRepository.save(p));
    }

    /**
     * 一条摆放记录（仅覆盖非空字段）。
     *
     * @param projectId 项目 ID
     *
     * @param placementId 摆放记录 ID
     *
     * @return 更新后摆放 VO
     */
    public ScenePlacementVO update(Long projectId, Long placementId, Map<String, Object> body) {
        ActorScenePlacement p = requirePlacement(projectId, placementId);
        apply(p, body);
        return toVO(placementRepository.save(p));
    }

    /**
     * 删除一条摆放记录。
     *
     * @param projectId 项目 ID
     *
     * @param placementId 摆放记录 ID
     */
    public void delete(Long projectId, Long placementId) {
        ActorScenePlacement p = requirePlacement(projectId, placementId);
        placementRepository.delete(p);
    }

    /**
     * 按项目聚合全部摆放（overview 用；跨场景，场景内 sort_order 升序）。
     *
     * @param projectId 项目 ID
     *
     * @return 摆放 VO 列表
     */
    public List<ScenePlacementVO> listByProject(Long projectId) {
        return placementRepository.findByProjectIdOrderBySceneIdAscSortOrderAscIdAsc(projectId)
                .stream().map(this::toVO).toList();
    }

    // ==================== 内部 ====================

    /** 场景归属校验：场景存在且属于项目 → 返回场景实体 */
    private ActorSandboxScene requireScene(Long projectId, Long sceneId) {
        return sceneRepository.findById(sceneId)
                .filter(s -> projectId.equals(s.getProjectId()))
                .orElseThrow(() -> new BizException(404, "场景不存在或不属于该项目"));
    }

    /** 摆放记录校验：存在且属于项目 → 返回摆放实体 */
    private ActorScenePlacement requirePlacement(Long projectId, Long placementId) {
        return placementRepository.findById(placementId)
                .filter(p -> projectId.equals(p.getProjectId()))
                .orElseThrow(() -> new BizException(404, "摆放记录不存在或不属于该项目"));
    }

    /** 应用 body 非空字段到实体（create/update 共用） */
    private void apply(ActorScenePlacement p, Map<String, Object> body) {
        if (body == null) {
            return;
        }
        String assetId = str(body, "assetId");
        if (assetId != null) {
            p.setAssetId(assetId);
        }
        String name = str(body, "name");
        if (name != null) {
            p.setName(name);
        }
        Double d;
        if ((d = dbl(body, "posX")) != null) p.setPosX(d);
        if ((d = dbl(body, "posY")) != null) p.setPosY(d);
        if ((d = dbl(body, "posZ")) != null) p.setPosZ(d);
        if ((d = dbl(body, "scaleX")) != null) p.setScaleX(d);
        if ((d = dbl(body, "scaleY")) != null) p.setScaleY(d);
        if ((d = dbl(body, "scaleZ")) != null) p.setScaleZ(d);
        if ((d = dbl(body, "rotX")) != null) p.setRotX(d);
        if ((d = dbl(body, "rotY")) != null) p.setRotY(d);
        if ((d = dbl(body, "rotZ")) != null) p.setRotZ(d);
        Integer s = int_(body, "sortOrder");
        if (s != null) {
            p.setSortOrder(s);
        }
    }

    /** 实体 → VO（附资产登记表解析：资产名/分类/GLB 路径；无登记时回退摆放名） */
    private ScenePlacementVO toVO(ActorScenePlacement p) {
        Optional<ActorSceneAsset> asset = p.getAssetId() == null
                ? Optional.empty()
                : assetRepository.findFirstByProjectIdInAndAssetId(
                        List.of(p.getProjectId(), 0L), p.getAssetId());
        String assetName = asset.map(ActorSceneAsset::getName).orElse(null);
        String category = asset.map(ActorSceneAsset::getCategory).orElse(null);
        String filePath = asset.map(ActorSceneAsset::getFilePath).orElse(null);
        return ScenePlacementVO.of(p, assetName, category, filePath);
    }

    // ==================== 工具 ====================

    /** Map 取字符串（null/空白 → null）。 */
    private String str(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return null;
        }
        String s = String.valueOf(body.get(key)).trim();
        return s.isEmpty() ? null : s;
    }

    /** Map 取 Double（null/非法 → null）。 */
    private Double dbl(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return null;
        }
        Object v = body.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** Map 取 Integer（null/非法 → null）。 */
    private Integer int_(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return null;
        }
        Object v = body.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }
}
