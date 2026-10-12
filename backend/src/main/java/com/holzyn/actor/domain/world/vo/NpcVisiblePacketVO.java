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

import com.holzyn.actor.domain.world.service.InfoPerceptionService.PerceivedPacket;
import lombok.Data;

/**
 * NPC 可见信息包预览 VO。
 * <p>职责：三层过滤（空间→社会→认知）结果调试预览——指定 NPC 当前可感知的信息包列表，
 * 含到达区域/传播节点/到达时刻/原始内容/失真后文本/可信度/距离（公里）——与感知注入
 * 【世界动态】段同一过滤路径（失真文本随注入生成，缓存收敛成本）。</p>
 */
@Data
public class NpcVisiblePacketVO {

    /** 信息包 ID */
    private Long packetId;

    /** 信息包编号（如 I-12-001-1） */
    private String infoId;

    /** 信息发生地 */
    private String origin;

    /** 该 NPC 感知到的到达区域 */
    private String region;

    /** 信息原文 */
    private String content;

    /** 失真后文本（该 NPC 听到的版本，AI 改写/规则兜底） */
    private String perceivedText;

    /** 可信度 0~1 */
    private Double reliability;

    /** 传播节点（商队/信使…） */
    private String relayedBy;

    /** 到达游戏时刻（秒） */
    private Long reachedGameSecond;

    /** 距事发地距离（公里） */
    private Double distanceKm;

    /** 感知结果 → VO。 */
    public static NpcVisiblePacketVO from(PerceivedPacket p) {
        NpcVisiblePacketVO vo = new NpcVisiblePacketVO();
        vo.setPacketId(p.packetId());
        vo.setInfoId(p.infoId());
        vo.setOrigin(p.origin());
        vo.setRegion(p.region());
        vo.setContent(p.content());
        vo.setPerceivedText(p.perceivedText());
        vo.setReliability(p.reliability());
        vo.setRelayedBy(p.relayedBy());
        vo.setReachedGameSecond(p.reachedGameSecond());
        vo.setDistanceKm(p.distanceKm());
        return vo;
    }
}
