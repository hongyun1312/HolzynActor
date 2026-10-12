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

package com.holzyn.actor.domain.world.controller;

import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.world.service.WorldGraphQueryService;
import com.holzyn.actor.domain.world.vo.WorldGraphVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 世界状态图谱查询控制器（只读，调研报告 ③-1）。
 * <p>职责：统一暴露图谱查询层的六维单域 / 三种聚合视角 / 全局概览端点——感知/决策/对话/
 * 宏观/调试前端可复用；纯只读（零 AI、零写入）。</p>
 * <ul>
 * <li>GET /overview —— 全局概览（时间+六维计数+宏观物资+最近事件+最近编年史）；</li>
 * <li>GET /locations —— 地点列表（六维单域）；</li>
 * <li>GET /time —— 时间节点（权威引擎位置）；</li>
 * <li>GET /characters —— 角色列表（type/locationId 过滤）；</li>
 * <li>GET /events —— 事件列表（kind/characterId/limit 过滤）；</li>
 * <li>GET /relations —— 关系列表（characterId 过滤）；</li>
 * <li>GET /factions —— 势力列表（正式组织，含成员数/关系分）；</li>
 * <li>GET /macro —— 宏观物资节点（人口/粮价/库存/民怨/季节/收成）；</li>
 * <li>GET /characters/{id}/view —— 角色视角聚合；</li>
 * <li>GET /locations/{id}/view —— 地点视角聚合；</li>
 * <li>GET /factions/{id}/view —— 势力视角聚合。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/world-graph")
@RequiredArgsConstructor
public class WorldGraphController {

    private final WorldGraphQueryService graphQueryService;

    /** 全局概览。 */
    @GetMapping("/overview")
    public R<WorldGraphVO.Overview> overview(@PathVariable("projectId") Long projectId) {
        return R.ok(graphQueryService.overview(projectId));
    }

    /** 地点列表（六维单域）。 */
    @GetMapping("/locations")
    public R<List<WorldGraphVO.LocationNode>> locations(@PathVariable("projectId") Long projectId) {
        return R.ok(graphQueryService.locations(projectId));
    }

    /** 时间节点（权威引擎位置）。 */
    @GetMapping("/time")
    public R<WorldGraphVO.TimeNode> time(@PathVariable("projectId") Long projectId) {
        return R.ok(graphQueryService.time(projectId));
    }

    /** 角色列表（type=special/common、locationId 过滤，可空）。 */
    @GetMapping("/characters")
    public R<List<WorldGraphVO.CharacterNode>> characters(
            @PathVariable("projectId") Long projectId,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "locationId", required = false) Long locationId) {
        return R.ok(graphQueryService.characters(projectId, type, locationId));
    }

    /** 事件列表（kind=event/action/crowd/world/evolution…、characterId 参与过滤、limit 上限，可空）。 */
    @GetMapping("/events")
    public R<List<WorldGraphVO.EventNode>> events(
            @PathVariable("projectId") Long projectId,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "characterId", required = false) Long characterId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return R.ok(graphQueryService.events(projectId, kind, characterId, limit));
    }

    /** 关系列表（characterId 参与过滤，可空）。 */
    @GetMapping("/relations")
    public R<List<WorldGraphVO.RelationEdge>> relations(
            @PathVariable("projectId") Long projectId,
            @RequestParam(value = "characterId", required = false) Long characterId) {
        return R.ok(graphQueryService.relations(projectId, characterId));
    }

    /** 势力列表（正式组织 type≠adhoc，含活跃成员数与组织间关系分）。 */
    @GetMapping("/factions")
    public R<List<WorldGraphVO.FactionNode>> factions(@PathVariable("projectId") Long projectId) {
        return R.ok(graphQueryService.factions(projectId));
    }

    /** 宏观物资节点（人口/粮价/库存/民怨/季节/收成/势力 top/议题 top）。 */
    @GetMapping("/macro")
    public R<WorldGraphVO.MacroNode> macro(@PathVariable("projectId") Long projectId) {
        return R.ok(graphQueryService.macro(projectId));
    }

    /** 角色视角聚合（角色+位置+关系+所属组织+参与事件）。 */
    @GetMapping("/characters/{id}/view")
    public R<WorldGraphVO.CharacterView> characterView(
            @PathVariable("projectId") Long projectId,
            @PathVariable("id") Long id) {
        return R.ok(graphQueryService.characterView(projectId, id));
    }

    /** 地点视角聚合（地点+在场角色+近期事件+关联实体状态）。 */
    @GetMapping("/locations/{id}/view")
    public R<WorldGraphVO.LocationView> locationView(
            @PathVariable("projectId") Long projectId,
            @PathVariable("id") Long id) {
        return R.ok(graphQueryService.locationView(projectId, id));
    }

    /** 势力视角聚合（势力+成员+组织间关系分）。 */
    @GetMapping("/factions/{id}/view")
    public R<WorldGraphVO.FactionView> factionView(
            @PathVariable("projectId") Long projectId,
            @PathVariable("id") Long id) {
        return R.ok(graphQueryService.factionView(projectId, id));
    }
}
