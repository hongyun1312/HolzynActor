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

package com.holzyn.actor.domain.worldmap.controller;

import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.worldmap.dto.WorldMapGenerateRequest;
import com.holzyn.actor.domain.worldmap.service.MovementService;
import com.holzyn.actor.domain.worldmap.service.WorldMapGenerationService;
import com.holzyn.actor.domain.worldmap.service.WorldMapQueryService;
import com.holzyn.actor.domain.worldmap.vo.MovementVO;
import com.holzyn.actor.domain.worldmap.vo.WorldMapGenerateVO;
import com.holzyn.actor.domain.worldmap.vo.WorldMapLocationVO;
import com.holzyn.actor.domain.worldmap.vo.WorldMapVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 世界地图控制器（位置移动与地图生成系统 · M1~M2）。
 * <p>端点：POST generate（AI 生成/重生成，幂等可重跑）/ GET world-map（只读概览）/
 * GET world-map/location/{id}（地点区域详情）/ GET world-map/npc/{ref}/home（单角色住所）/
 * GET movements（M2 当前在途移动列表）。供新首页 2D 世界地图 / 图谱消费。</p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/world-map")
@Slf4j
@RequiredArgsConstructor
public class WorldMapController {

    private final WorldMapGenerationService generationService;
    private final WorldMapQueryService queryService;
    private final CurrentUserProvider currentUserProvider;
    private final MovementService movementService;

    /** 生成/重生成世界地图（AI 主通道 + 机制兜底；幂等可重跑）。 */
    @PostMapping("/generate")
    public R<WorldMapGenerateVO> generate(@PathVariable("projectId") Long projectId,
                                          @RequestBody(required = false) WorldMapGenerateRequest request) {
        WorldMapGenerateVO vo = generationService.generate(currentUserProvider.currentUserId(), projectId, request);
        return R.ok(vo);
    }

    /** 世界地图只读概览（regions/roads/placements/homes）。 */
    @GetMapping
    public R<WorldMapVO> worldMap(@PathVariable("projectId") Long projectId) {
        return R.ok(queryService.worldMap(projectId));
    }

    /** 单地点区域详情（区域/摆放/住所/在场角色）。 */
    @GetMapping("/location/{locationId}")
    public R<WorldMapLocationVO> locationDetail(@PathVariable("projectId") Long projectId,
                                                @PathVariable("locationId") Long locationId) {
        return R.ok(queryService.locationDetail(projectId, locationId));
    }

    /** 单角色住所（无住所返回 null）。 */
    @GetMapping("/npc/{ref}/home")
    public R<WorldMapVO.HomeVO> npcHome(@PathVariable("projectId") Long projectId,
                                        @PathVariable("ref") String ref) {
        return R.ok(queryService.npcHome(projectId, ref));
    }

    /** 当前在途移动列表（M2 连续移动状态机；供沙盘/新首页渲染移动中角色与轨迹）。 */
    @GetMapping("/movements")
    public R<List<MovementVO>> movements(@PathVariable("projectId") Long projectId) {
        return R.ok(movementService.listMovements(projectId));
    }
}
