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

import java.util.ArrayList;
import java.util.List;

/**
 * 世界地图生成 · 区域（地点在空间中的落地：圆心 + 半径 + 口语描述）。
 * <p>坐标口径：0..scene_size 地图坐标（与沙盘 layout_json / Unity CoordinateTransformer 对齐）。</p>
 */
@Data
public class WorldMapRegion {

    /** 地点 ID（actor_world_location.id，必填） */
    private Long locationId;

    /** 地点名（冗余，校验/日志用） */
    private String name;

    /** 区域中心 X */
    private double centerX;

    /** 区域中心 Z */
    private double centerZ;

    /** 区域半径（格=米） */
    private double radius;

    /** 形状（circle；预留） */
    private String shape = "circle";

    /** 区域口语描述（「村西口，进村第一景」；感知/对话素材） */
    private String regionDesc;

    /** 供机制生成内部使用：期望环带比例（0~1，0=村心，1=村边） */
    private transient double band;

    /** 供机制生成内部使用：期望方位角（弧度） */
    private transient double angle;

    /** 静态工具：列表副本。 */
    public static List<WorldMapRegion> copyOf(List<WorldMapRegion> src) {
        if (src == null) {
            return new ArrayList<>();
        }
        List<WorldMapRegion> out = new ArrayList<>(src.size());
        for (WorldMapRegion r : src) {
            WorldMapRegion c = new WorldMapRegion();
            c.locationId = r.locationId;
            c.name = r.name;
            c.centerX = r.centerX;
            c.centerZ = r.centerZ;
            c.radius = r.radius;
            c.shape = r.shape;
            c.regionDesc = r.regionDesc;
            out.add(c);
        }
        return out;
    }
}
