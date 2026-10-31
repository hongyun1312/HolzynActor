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

import com.holzyn.actor.domain.world.service.WorldFrameService;
import com.holzyn.actor.domain.world.vo.WorldFrameVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每日世界帧接口。
 * <p>接口集（主开发方案 §6.1， 交付读接口两个 + 紧急更新一个；world-info/packets 与
 * world-info/diffusion 读接口留 扩散物化后）：</p>
 * <ul>
 * <li>GET /world-frame —— 当前生效世界帧（<b>恒 200</b>；未生成 → {@code generated=false} + {@code hint}，
 *     见 UI V2.0 §8.4）；</li>
 * <li>GET /world-frame/list?limit= —— 世界帧历史（game_day 降序，默认 30 上限 100）；</li>
 * <li>POST /world-frame/emergency —— 紧急世界帧更新（body={eventType?, location?, summary?}；
 * ≤1 次/游戏日，超限 400；就地更新当前 active 帧）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/worlds/{id}/world-frame")
@RequiredArgsConstructor
public class WorldFrameController {

    private final WorldFrameService worldFrameService;

    /**
     * 当前生效世界帧。
     *
     * <p><b>契约（UI V2.0 §8.4）</b>：恒返回 200。首个游戏日 00:00 之前还没有世界帧，这是世界的
     * 正常状态而非错误——此时 body 的 {@code generated=false}、{@code hint} 说明原因、其余字段为空。
     * 改前此处 404，导致前端每次首访都在控制台留一条红色 4xx，且"未生成"与"接口失败"无从区分。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 帧 VO（未生成时 {@code generated=false}）
     */
    @GetMapping
    public WorldFrameVO current(@PathVariable("id") Long projectId) {
        return worldFrameService.getActiveFrame(projectId);
    }

    /**
     * 世界帧历史（game_day 降序）。
     *
     * @param projectId 项目 ID
     *
     * @param limit 条数上限（可空=30）
     *
     * @return 帧 VO 列表
     */
    @GetMapping("/list")
    public List<WorldFrameVO> list(@PathVariable("id") Long projectId,
                                   @RequestParam(required = false) Integer limit) {
        return worldFrameService.listFrames(projectId, limit);
    }

    /**
     * 紧急世界帧更新（只重生成受影响宏观事件与信息包，天气/环境保持；≤1 次/游戏日）。
     *
     * @param projectId 项目 ID
     *
     * @param body 紧急事件描述（eventType?/location?/summary?，均可空）
     *
     * @return 更新后的当前帧 VO
     */
    @PostMapping("/emergency")
    public WorldFrameVO emergency(@PathVariable("id") Long projectId,
                                  @RequestBody(required = false) Map<String, String> body) {
        Map<String, String> b = body == null ? new LinkedHashMap<>() : body;
        return worldFrameService.emergencyUpdate(projectId,
                b.get("eventType"), b.get("location"), b.get("summary"), null);
    }
}
