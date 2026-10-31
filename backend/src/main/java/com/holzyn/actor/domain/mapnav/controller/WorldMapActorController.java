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

package com.holzyn.actor.domain.mapnav.controller;

import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.mapnav.service.NpcActorQueryService;
import com.holzyn.actor.domain.mapnav.vo.ActorSnapshotVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色快照接口（{@code GET /api/worlds/{projectId}/map/actors}）。
 *
 * <p><b>为什么单独开一个轻量接口，而不是让前端反复拉 bundle</b>：bundle 是「整张图的几何」
 * （要素/道路/院落 + 导航缩略，1 MB 级），只在打开页面或布局变化时拉；
 * 而角色位置是<b>每几秒都在变</b>的薄数据（40 个点）。两者混在一个接口里，
 * 要么地图页每 5 秒重拉 1 MB，要么角色点几十秒不动——都不对。</p>
 *
 * <p>接口返回里带 {@code clock}：前端据此判断「世界暂停了没有」。
 * 暂停时它以 {@code paused=true} 返回同一批坐标，前端停表——这比前端自己猜时间可靠。</p>
 */
@RestController
@RequestMapping("/api/worlds/{projectId}/map")
@RequiredArgsConstructor
public class WorldMapActorController {

    private final NpcActorQueryService actorQueryService;

    /**
     * 角色快照（含在途轨迹与世界时钟）。
     *
     * @param projectId 项目 ID
     * @return {@code {clock:{...}, actors:[...]}}
     */
    @GetMapping("/actors")
    public R<Map<String, Object>> actors(@PathVariable Long projectId) {
        List<ActorSnapshotVO> actors = actorQueryService.actors(projectId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clock", actorQueryService.clock(projectId));
        out.put("actors", actors);
        out.put("count", actors.size());
        return R.ok(out);
    }
}
