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

package com.holzyn.actor.domain.world.vo;

import com.holzyn.actor.domain.world.entity.ActorInfoDiffusion;
import lombok.Data;

/**
 * 信息扩散状态 VO。
 * <p>职责：扩散状态回放展示——actor_info_diffusion 行（每包每区域一条）转调试可读结构：
 * 到达区域/时刻/游戏日/传播节点/到达时应用的失真参数 JSON（调试/回放）。</p>
 */
@Data
public class InfoDiffusionVO {

    /** 扩散状态 ID */
    private Long id;

    /** 信息包 ID */
    private Long packetId;

    /** 所属项目 ID */
    private Long projectId;

    /** 到达区域（场景/地点名） */
    private String region;

    /** 到达游戏时刻（秒） */
    private Long reachedGameSecond;

    /** 到达游戏日 */
    private Long gameDay;

    /** 中间传播节点（商队/信使…） */
    private String relayedBy;

    /** 到达时应用的失真参数 JSON（调试/回放） */
    private String distortionAppliedJson;

    /** 实体 → VO。 */
    public static InfoDiffusionVO from(ActorInfoDiffusion d) {
        InfoDiffusionVO vo = new InfoDiffusionVO();
        vo.setId(d.getId());
        vo.setPacketId(d.getPacketId());
        vo.setProjectId(d.getProjectId());
        vo.setRegion(d.getRegion());
        vo.setReachedGameSecond(d.getReachedGameSecond());
        vo.setGameDay(d.getGameDay());
        vo.setRelayedBy(d.getRelayedBy());
        vo.setDistortionAppliedJson(d.getDistortionAppliedJson());
        return vo;
    }
}
