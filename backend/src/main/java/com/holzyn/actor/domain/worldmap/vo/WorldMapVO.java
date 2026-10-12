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
 * 世界地图只读 VO（GET /api/projects/{id}/world-map）。
 * <p>数据源：actor_sandbox_scene.layout_json（regions/roads/placements）+ actor_npc_home/
 * actor_home_member（住所与住户）+ actor_world_location（地点坐标区）。供新首页 2D 地图 / 图谱消费。</p>
 */
@Data
public class WorldMapVO {

    /** 项目 ID */
    private Long projectId;

    /** 场景 ID */
    private Long sceneId;

    /** 场景边长 */
    private Integer sceneSize;

    /** 风格 */
    private String style;

    /** 布局来源（template / ai_generated / mechanism） */
    private String source;

    /** 区域（地点落地） */
    private List<RegionVO> regions = new ArrayList<>();

    /** 道路 */
    private List<RoadVO> roads = new ArrayList<>();

    /** 建筑摆放 */
    private List<PlacementVO> placements = new ArrayList<>();

    /** 住所（同居共宅） */
    private List<HomeVO> homes = new ArrayList<>();

    /** 区域 VO */
    @Data
    public static class RegionVO {
        private Long locationId;
        private String name;
        private double centerX;
        private double centerZ;
        private double radius;
        private String shape;
        private String regionDesc;
    }

    /** 道路 VO */
    @Data
    public static class RoadVO {
        private String name;
        private List<double[]> path = new ArrayList<>();
        private double width;
    }

    /** 摆放 VO */
    @Data
    public static class PlacementVO {
        private Long id;
        private String name;
        private String category;
        private String assetId;
        private double posX;
        private double posZ;
        private double rotY;
        private Long locationId;
    }

    /** 住所 VO（含住户 members） */
    @Data
    public static class HomeVO {
        private Long id;
        private String name;
        private Long locationId;
        private String locationName;
        private double posX;
        private double posZ;
        private String roomLabel;
        private String region;
        private String note;
        private String status;
        private Integer decorationLevel;
        private List<MemberVO> members = new ArrayList<>();
    }

    /** 住户 VO */
    @Data
    public static class MemberVO {
        private String characterRef;
        private String characterName;
        private Integer isOwner;
        private String role;
        private String roomLabel;
    }
}
