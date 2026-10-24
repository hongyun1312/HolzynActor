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

package com.holzyn.actor.domain.mapgen.model;

import java.util.ArrayList;
import java.util.List;

/**
 * S3 路网拓扑——AI 只说「谁连谁 + 这条路是什么性格」，<b>不给坐标</b>（用户决策 #6）。
 *
 * <p>为什么这样分工：AI 擅长的是「镇上该有一条从码头通到正街的路，两边是商铺」这种
 * <b>聚落逻辑</b>；它不擅长（而且每次都不一样）的是「这条路在地图上第 517 格往左拐」。
 * 所以拓扑交给 AI，走线交给最小代价寻路（{@link com.holzyn.actor.domain.mapgen.algo.RoadRouter}）——
 * 坡度、过河、绕建筑都是代价，走出来的线自然贴着地形，而不是 AI 画的一条折线。</p>
 *
 * <p>{@link Node#prefer()} 是拓扑与地形之间唯一的接头：它是<b>空间语义</b>（coast/river/high-ground…），
 * 由 {@link com.holzyn.actor.domain.mapgen.algo.MapSites#pick} 在地形上换成真实米坐标。</p>
 *
 * @param nodes 节点（语义角色，无坐标）
 * @param roads 边（连接关系 + 路的性格）
 * @param rawJson AI 原文（排障/回放用；兜底拓扑为 {@code null}）
 * @param aiGenerated 是否 AI 产出
 */
public record RoadTopology(List<Node> nodes, List<Edge> roads, String rawJson, boolean aiGenerated) {

    /**
     * 一个路网节点（语义角色，无坐标）。
     *
     * @param code   节点编码（全局唯一）
     * @param name   中文名（「镇中心小广场」）
     * @param role   core / gate / waterfront / residential / workshop / farm / landmark
     * @param prefer 放置偏好（settlement-center / coast / river / settlement-edge / high-ground /
     *               farmland / forest），由 MapSites 换成坐标
     * @param note   一句话说明
     */
    public record Node(String code, String name, String role, String prefer, String note) {
    }

    /**
     * 一条路（连接关系 + 性格）。
     *
     * @param code     编码
     * @param name     中文名（「正街」）
     * @param kind     street / lane / path / bridge / stairs / pier
     * @param level    major（主干）/ minor（巷道）/ production（生产路）
     * @param width    期望路面宽（米；越界会被 {@link com.holzyn.actor.domain.mapgen.algo.RoadGrade} 夹到等级区间）
     * @param surface  stone / dirt / wood / gravel
     * @param from     起点节点 code
     * @param to       终点节点 code
     * @param bend     弯曲倾向 0~1（交给寻路的代价偏置）
     * @param priority 1~10（越大越优先保证走通）
     */
    public record Edge(String code, String name, String kind, String level, double width,
                       String surface, String from, String to, double bend, int priority) {
    }

    /** 允许的节点角色 */
    public static final List<String> ROLES =
            List.of("core", "gate", "waterfront", "residential", "workshop", "farm", "landmark");

    /** 允许的边类型 */
    public static final List<String> EDGE_KINDS =
            List.of("street", "lane", "path", "bridge", "stairs", "pier");

    /** 允许的等级（AI 口径） */
    public static final List<String> LEVELS = List.of("major", "minor", "production");

    /**
     * 确定性兜底拓扑（AI 关闭 / AI 失败时使用）：门户 → 镇中心 → 四方民居群 + 生产区 + 工坊区 + 埠头。
     * <p>与离线自检程序的 {@code buildRoads} 同一套连接关系——但这里<b>只有连接关系</b>，
     * 每条边仍由 A* 在真实地形上求线，所以兜底路网也是贴地形的，不是一条直线。</p>
     *
     * @return 兜底拓扑（{@code aiGenerated=false}）
     */
    public static RoadTopology fallback() {
        List<Node> nodes = new ArrayList<>(List.of(
                new Node("plaza_center", "镇中心小广场", "core", "settlement-center", "一口老井，镇上人都在这里碰面"),
                new Node("harbor_gate", "码头口", "gate", "coast", "渔船靠岸、货物上岸的地方"),
                new Node("farm_field", "生产区", "farm", "farmland", "田与晒场，出镇子的方向"),
                new Node("workshop_row", "工坊巷", "workshop", "settlement-edge", "铁匠与木工都在这条巷子里"),
                new Node("river_side", "河埠头", "waterfront", "river", "洗衣、取水、上船的地方"),
                new Node("res_n", "北居民居群", "residential", "settlement-edge", "北边的住家"),
                new Node("res_e", "东居民居群", "residential", "settlement-edge", "东边的住家"),
                new Node("res_s", "南居民居群", "residential", "settlement-edge", "南边的住家"),
                new Node("res_w", "西居民居群", "residential", "settlement-edge", "西边的住家")));
        List<Edge> edges = new ArrayList<>(List.of(
                new Edge("main_street", "正街", "street", "major", 5.0, "stone",
                        "harbor_gate", "plaza_center", 0.35, 10),
                new Edge("field_road", "田间大道", "path", "production", 2.6, "dirt",
                        "plaza_center", "farm_field", 0.8, 6),
                new Edge("lane_n", "北巷", "lane", "minor", 3.0, "dirt",
                        "plaza_center", "res_n", 0.6, 5),
                new Edge("lane_e", "东巷", "lane", "minor", 3.0, "dirt",
                        "plaza_center", "res_e", 0.6, 5),
                new Edge("lane_s", "南巷", "lane", "minor", 3.0, "dirt",
                        "plaza_center", "res_s", 0.6, 5),
                new Edge("lane_w", "西巷", "lane", "minor", 3.0, "dirt",
                        "plaza_center", "res_w", 0.6, 5),
                new Edge("lane_water", "埠头路", "lane", "minor", 2.6, "dirt",
                        "river_side", "plaza_center", 0.7, 4),
                new Edge("lane_shop", "工坊巷路", "lane", "minor", 3.0, "gravel",
                        "workshop_row", "plaza_center", 0.5, 4)));
        return new RoadTopology(nodes, edges, null, false);
    }
}
