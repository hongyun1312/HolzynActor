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

package com.holzyn.actor.domain.worldmap.model;

import lombok.Data;

/**
 * 世界地图生成 · 建筑摆放（地点内的具体建筑/设施；坐标 0..scene_size 地图坐标）。
 * <p>web/桌面端不读取资产包数据（用户约束），assetId 仅作 Unity 后续同步的可选引用；
 * 无资产包时靠 name + category 表达（渲染层画示意块）。</p>
 */
@Data
public class WorldMapPlacement {

    /** 摆放名（「沈记粮铺」） */
    private String name;

    /** 类别（store/residence/temple/field/landmark…；渲染分类用） */
    private String category;

    /** 资产稳定标识（可选；对齐 actor_scene_placement.asset_id，如 building_inn_01） */
    private String assetId;

    /** 摆放 X（0..scene_size） */
    private double posX;

    /** 摆放 Z（0..scene_size） */
    private double posZ;

    /** 朝向（弧度） */
    private double rotY;

    /** 所属地点 ID（actor_world_location.id，可空） */
    private Long locationId;
}
