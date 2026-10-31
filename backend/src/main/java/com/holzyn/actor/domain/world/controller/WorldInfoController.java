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

import com.holzyn.actor.domain.world.entity.ActorInfoDiffusion;
import com.holzyn.actor.domain.world.entity.ActorInfoPacket;
import com.holzyn.actor.domain.world.service.InfoDiffusionService;
import com.holzyn.actor.domain.world.service.InfoPerceptionService;
import com.holzyn.actor.domain.world.vo.InfoDiffusionVO;
import com.holzyn.actor.domain.world.vo.InfoPacketVO;
import com.holzyn.actor.domain.world.vo.NpcVisiblePacketVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 信息扩散与感知接口。
 * <p>接口集：</p>
 * <ul>
 * <li>GET /world-info/packets?region=&expired= —— 信息包查询（按区域/状态过滤，调试用）；</li>
 * <li>GET /world-info/diffusion?packetId= —— 扩散状态查询（按包或按项目，回放扩散进程）；</li>
 * <li>GET /world-info/npc-visible?characterRef= —— NPC 可见信息包预览（三层过滤[空间→社会→认知]
 * 结果 + 失真后文本，与感知注入同路径，调试/冒烟验证用）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/worlds/{id}/world-info")
@RequiredArgsConstructor
public class WorldInfoController {

    private final InfoDiffusionService diffusionService;
    private final InfoPerceptionService perceptionService;

    /**
     * 信息包查询（按区域/状态过滤）。
     *
     * @param projectId 项目 ID
     *
     * @param region 区域名（可空=不过滤）
     *
     * @param expired true=仅过期 / false=仅 active / null=全部
     *
     * @return 信息包 VO 列表
     */
    @GetMapping("/packets")
    public List<InfoPacketVO> packets(@PathVariable("id") Long projectId,
                                      @RequestParam(required = false) String region,
                                      @RequestParam(required = false) Boolean expired) {
        List<ActorInfoPacket> packets = diffusionService.queryPackets(projectId, region, expired);
        return packets.stream().map(InfoPacketVO::from).toList();
    }

    /**
     * 扩散状态查询（按包或按项目，到达时刻升序）。
     *
     * @param projectId 项目 ID
     *
     * @param packetId 信息包 ID（可空=按项目全部）
     *
     * @return 扩散状态 VO 列表
     */
    @GetMapping("/diffusion")
    public List<InfoDiffusionVO> diffusion(@PathVariable("id") Long projectId,
                                           @RequestParam(required = false) Long packetId) {
        List<ActorInfoDiffusion> rows = diffusionService.queryDiffusion(projectId, packetId);
        return rows.stream().map(InfoDiffusionVO::from).toList();
    }

    /**
     * NPC 可见信息包预览（三层过滤结果 + 失真后文本）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用（special:{id} / crowd:{id}）
     *
     * @return 可见信息包 VO 列表
     */
    @GetMapping("/npc-visible")
    public List<NpcVisiblePacketVO> npcVisible(@PathVariable("id") Long projectId,
                                               @RequestParam String characterRef) {
        return perceptionService.visiblePackets(projectId, characterRef, null).stream()
                .map(NpcVisiblePacketVO::from)
                .toList();
    }
}
