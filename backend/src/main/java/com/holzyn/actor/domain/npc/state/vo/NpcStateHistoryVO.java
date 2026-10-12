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

package com.holzyn.actor.domain.npc.state.vo;

import com.holzyn.actor.domain.npc.state.entity.ActorStateHistory;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * NPC 状态历史快照视图对象。
 * <p>职责：向调试前端状态页 / 主前端角色页历史曲线提供快照全字段（需求/心情/情绪/urgency），
 * 按 game_second 升序返回，前端据此渲染需求/情绪随时间曲线。</p>
 */
@Data
public class NpcStateHistoryVO {

    /** 快照行 ID */
    private Long id;

    /** 项目 ID */
    private Long projectId;

    /** NPC 类型：special / crowd */
    private String npcType;

    /** NPC 引用：special:{id} / crowd:{id} */
    private String npcRef;

    // ==================== 需求（0-100） ====================

    private Integer hunger;
    private Integer thirst;
    private Integer energy;
    private Integer bladder;
    private Integer health;
    private Integer mood;

    // ==================== 情绪（PAD + 主导 + 强度） ====================

    private Double emotionPleasure;
    private Double emotionArousal;
    private Double emotionDominance;
    private String dominantEmotion;
    private Integer emotionIntensity;

    // ==================== 扩展需求 ====================

    private Integer hygiene;
    private Integer social;
    private Integer fun;
    private Integer comfort;
    private Integer envModifier;

    //

    // ==================== urgency ====================
    private Integer urgencyLevel;
    private String urgencyText;

    /** 快照对应游戏时刻（秒，曲线 x 轴） */
    private Long gameSecond;

    /** 采样落库时间 */
    private LocalDateTime createdAt;

    /** 实体转 VO */
    public static NpcStateHistoryVO of(ActorStateHistory e) {
        if (e == null) {
            return null;
        }
        NpcStateHistoryVO vo = new NpcStateHistoryVO();
        vo.setId(e.getId());
        vo.setProjectId(e.getProjectId());
        vo.setNpcType(e.getNpcType());
        vo.setNpcRef(e.getNpcRef());
        vo.setHunger(e.getHunger());
        vo.setThirst(e.getThirst());
        vo.setEnergy(e.getEnergy());
        vo.setBladder(e.getBladder());
        vo.setHealth(e.getHealth());
        vo.setMood(e.getMood());
        vo.setEmotionPleasure(e.getEmotionPleasure());
        vo.setEmotionArousal(e.getEmotionArousal());
        vo.setEmotionDominance(e.getEmotionDominance());
        vo.setDominantEmotion(e.getDominantEmotion());
        vo.setEmotionIntensity(e.getEmotionIntensity());
        vo.setHygiene(e.getHygiene());
        vo.setSocial(e.getSocial());
        vo.setFun(e.getFun());
        vo.setComfort(e.getComfort());
        vo.setEnvModifier(e.getEnvModifier());
        vo.setUrgencyLevel(e.getUrgencyLevel());
        vo.setUrgencyText(e.getUrgencyText());
        vo.setGameSecond(e.getGameSecond());
        vo.setCreatedAt(e.getCreatedAt());
        return vo;
    }
}
