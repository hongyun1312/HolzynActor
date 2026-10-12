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

import com.holzyn.actor.domain.sandbox.entity.ActorSceneAsset;
import lombok.Data;

/**
 * 沙盘 3D 资产视图对象（世界沙盘 S3 补全，资产清单/导入）。
 * <p>职责：向沙盘页/调试侧展示资产登记行（asset_id/名称/分类/规模/占地/风格/标签/GLB URL），
 * 供前端加载与后续编辑器使用。</p>
 */
@Data
public class SceneAssetVO {

    /** 登记行 ID */
    private Long id;

    /** 所属项目（0=内置包全局共享） */
    private Long projectId;

    /** 所属资产数据包 ID（manifest.pack.id） */
    private String packId;

    /** 稳定标识（building_inn_01 等） */
    private String assetId;

    /** 中文名 */
    private String name;

    /** 10 类类别（building_residential/public/commercial、plant、terrain、prop、decoration…） */
    private String category;

    /** 描述（含 AI 放置参考） */
    private String description;

    /** 规模 JSON：{length,width,height}（米） */
    private String sizeJson;

    /** 占地 JSON：{length,width}（米） */
    private String footprintJson;

    /** 风格标签 JSON 数组 */
    private String styleJson;

    /** 标签 JSON 数组 */
    private String tagsJson;

    /** GLB 文件 HTTP 路径（uploads/scene-assets/…；前端直接加载） */
    private String filePath;

    /** 是否 meshopt/Draco 压缩 */
    private Boolean compressed;

    /** 缩略图 HTTP 路径 */
    private String preview;

    /** 人工覆盖标记 */
    private Boolean manual;

    /** 实体转 VO */
    public static SceneAssetVO of(ActorSceneAsset e) {
        if (e == null) {
            return null;
        }
        SceneAssetVO vo = new SceneAssetVO();
        vo.setId(e.getId());
        vo.setProjectId(e.getProjectId());
        vo.setPackId(e.getPackId());
        vo.setAssetId(e.getAssetId());
        vo.setName(e.getName());
        vo.setCategory(e.getCategory());
        vo.setDescription(e.getDescription());
        vo.setSizeJson(e.getSizeJson());
        vo.setFootprintJson(e.getFootprintJson());
        vo.setStyleJson(e.getStyleJson());
        vo.setTagsJson(e.getTagsJson());
        vo.setFilePath(e.getFilePath());
        vo.setCompressed(e.getCompressed());
        vo.setPreview(e.getPreview());
        vo.setManual(e.getManual());
        return vo;
    }
}
