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

import com.holzyn.actor.domain.world.entity.ActorInfoPacket;
import lombok.Data;

/**
 * 信息包 VO。
 * <p>职责：信息包查询展示——把 actor_info_packet 行转调试可读结构：扩散参数/失真规则/传播渠道/
 * 社会层条件均保留原始 JSON 文本。
 * 查询方（InfoDiffusionService.queryPackets）按 region/expired 过滤后逐行映射。</p>
 */
@Data
public class InfoPacketVO {

    /** 信息包 ID */
    private Long id;

    /** 所属世界帧 ID */
    private Long frameId;

    /** 所属项目 ID */
    private Long projectId;

    /** 信息包编号（如 I-12-001-1） */
    private String infoId;

    /** 信息发生地 */
    private String origin;

    /** 信息内容（原始） */
    private String content;

    /** 可信度 0~1 */
    private Double reliability;

    /** 传播渠道 JSON（商队/信使…） */
    private String spreadChannelsJson;

    /** 传播速度（天/公里，越小越快） */
    private Double spreadSpeedDaysPerKm;

    /** 有效期（游戏日序号） */
    private Long validUntilDay;

    /** 失真规则 JSON 列表 */
    private String distortionRulesJson;

    /** 社会层过滤条件 JSON（profession/affiliation/location） */
    private String visibilityConditionJson;

    /** 状态：active / expired */
    private String status;

    /** 实体 → VO。 */
    public static InfoPacketVO from(ActorInfoPacket p) {
        InfoPacketVO vo = new InfoPacketVO();
        vo.setId(p.getId());
        vo.setFrameId(p.getFrameId());
        vo.setProjectId(p.getProjectId());
        vo.setInfoId(p.getInfoId());
        vo.setOrigin(p.getOrigin());
        vo.setContent(p.getContent());
        vo.setReliability(p.getReliability());
        vo.setSpreadChannelsJson(p.getSpreadChannelsJson());
        vo.setSpreadSpeedDaysPerKm(p.getSpreadSpeedDaysPerKm());
        vo.setValidUntilDay(p.getValidUntilDay());
        vo.setDistortionRulesJson(p.getDistortionRulesJson());
        vo.setVisibilityConditionJson(p.getVisibilityConditionJson());
        vo.setStatus(p.getStatus());
        return vo;
    }
}
