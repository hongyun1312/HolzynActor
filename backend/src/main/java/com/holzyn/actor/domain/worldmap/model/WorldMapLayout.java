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
 * 世界地图生成 · 布局结果（AI 输出 / 机制兜底共用的统一结构，落库前形态）。
 * <p>source 标记来源：ai（AI 主通道成功）/ mechanism（AI 失败或未装配 → 机制兜底）。</p>
 */
@Data
public class WorldMapLayout {

    /** 地图名（「青禾村总图」） */
    private String mapName;

    /** 场景边长（0..scene_size） */
    private int sceneSize;

    /** 风格（scene.styleCode；styleOverride 覆盖） */
    private String style;

    /** 随机种子（可复现） */
    private long seed;

    /** 区域列表（每地点一个区域，唯一 locationId） */
    private List<WorldMapRegion> regions = new ArrayList<>();

    /** 道路列表 */
    private List<WorldMapRoad> roads = new ArrayList<>();

    /** 建筑摆放列表 */
    private List<WorldMapPlacement> placements = new ArrayList<>();

    /** 住所列表（同居共宅：一间住所多人 members） */
    private List<WorldMapHome> homes = new ArrayList<>();

    /** 生成来源：ai / mechanism */
    private String source;
}
