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

package com.holzyn.actor.domain.sandbox.vo;

import com.holzyn.actor.domain.sandbox.entity.ActorScenePlacement;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 沙盘资产摆放视图对象（世界沙盘 S3，摆放 CRUD / overview 聚合）。
 * <p>职责：向沙盘页展示一条摆放记录（资产标识/名称/位置/缩放/朝向/排序），
 * 并附资产登记表解析出的展示信息（资产名/分类/GLB 路径 URL，登记表无记录时为 null）。</p>
 */
@Data
public class ScenePlacementVO {

    /** 摆放记录 ID */
    private Long id;

    /** 所属项目 ID */
    private Long projectId;

    /** 所属场景 ID */
    private Long sceneId;

    /** 资产稳定标识（actor_scene_asset.asset_id） */
    private String assetId;

    /** 摆放名（缺省回退资产登记名） */
    private String name;

    /** 位置 X（米） */
    private Double posX;

    /** 位置 Y（米） */
    private Double posY;

    /** 位置 Z（米） */
    private Double posZ;

    /** 缩放 X/Y/Z */
    private Double scaleX;
    private Double scaleY;
    private Double scaleZ;

    /** 朝向（弧度）X/Y/Z */
    private Double rotX;
    private Double rotY;
    private Double rotZ;

    /** 排序 */
    private Integer sortOrder;

    /** ===== 资产登记表解析（可空） ===== */

    /** 资产登记名（无登记时回退摆放名） */
    private String assetName;

    /** 资产分类（10 类枚举） */
    private String category;

    /** GLB 文件路径（前端加载 URL；无登记时为 null） */
    private String filePath;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 时间 */
    private LocalDateTime updatedAt;

    /** 实体转 VO（含资产解析信息，可选） */
    public static ScenePlacementVO of(ActorScenePlacement e, String assetName, String category, String filePath) {
        if (e == null) {
            return null;
        }
        ScenePlacementVO vo = new ScenePlacementVO();
        vo.setId(e.getId());
        vo.setProjectId(e.getProjectId());
        vo.setSceneId(e.getSceneId());
        vo.setAssetId(e.getAssetId());
        vo.setName(e.getName());
        vo.setPosX(e.getPosX());
        vo.setPosY(e.getPosY());
        vo.setPosZ(e.getPosZ());
        vo.setScaleX(e.getScaleX());
        vo.setScaleY(e.getScaleY());
        vo.setScaleZ(e.getScaleZ());
        vo.setRotX(e.getRotX());
        vo.setRotY(e.getRotY());
        vo.setRotZ(e.getRotZ());
        vo.setSortOrder(e.getSortOrder());
        vo.setAssetName(assetName);
        vo.setCategory(category);
        vo.setFilePath(filePath);
        vo.setCreatedAt(e.getCreatedAt());
        vo.setUpdatedAt(e.getUpdatedAt());
        return vo;
    }
}
