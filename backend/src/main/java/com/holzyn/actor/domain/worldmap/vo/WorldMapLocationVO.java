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

package com.holzyn.actor.domain.worldmap.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 地点地图详情 VO（GET /world-map/location/{locationId}）：区域 + 摆放 + 住所 + 在场角色。
 */
@Data
public class WorldMapLocationVO {

    /** 地点 ID */
    private Long locationId;

    /** 地点名 */
    private String name;

    /** 地点类型 */
    private String type;

    /** 地点简介 */
    private String intro;

    /** 区域中心 X */
    private Double centerX;

    /** 区域中心 Z */
    private Double centerZ;

    /** 区域半径 */
    private Double radius;

    /** 区域口语描述 */
    private String regionDesc;

    /** 区域形状（circle） */
    private String shape;

    /** 该地点内的建筑摆放 */
    private List<WorldMapVO.PlacementVO> placements = new ArrayList<>();

    /** 该地点内的住所（同居共宅） */
    private List<WorldMapVO.HomeVO> homes = new ArrayList<>();

    /** 在场角色名 */
    private List<String> characters = new ArrayList<>();
}
