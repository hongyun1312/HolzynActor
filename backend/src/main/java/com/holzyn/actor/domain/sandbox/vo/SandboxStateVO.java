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

package com.holzyn.actor.domain.sandbox.vo;

import com.holzyn.actor.domain.sandbox.entity.ActorSandboxState;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色沙盘状态视图对象（世界沙盘 S1，读接口）。
 * <p>职责：向沙盘页展示 NPC 在沙盘中的实时状态（坐标/朝向/移动状态/动作/状态摘要/想法/游戏时刻），
 * 附 NPC 名称（特殊 NPC=角色名，普通人群=居民名）供前端展示。</p>
 */
@Data
public class SandboxStateVO {

    /** 状态行 ID */
    private Long id;

    /** 所属场景 ID（无场景可空） */
    private Long sceneId;

    /** NPC 类型：special / crowd */
    private String npcType;

    /** NPC 引用：special:{id} / crowd:{id} */
    private String npcRef;

    /** NPC 名称（特殊=角色名，普通=居民名） */
    private String npcName;

    /** 场景坐标 X */
    private Double posX;

    /** 场景坐标 Y（前期恒 0） */
    private Double posY;

    /** 场景坐标 Z */
    private Double posZ;

    /** 朝向（弧度） */
    private Double heading;

    /** 移动状态：idle / walking / acting / talking / resting */
    private String moveState;

    /** 当前动作描述 */
    private String actionText;

    /** 状态摘要（心情/目标/健康等） */
    private String statusText;

    /** 内心想法（当前气泡内容） */
    private String thoughtText;

    /** 想法来源：derived / ai / reflection / perception */
    private String thoughtSource;

    /** 状态对应游戏时刻（秒） */
    private Long gameSecond;

    // ====================：内部状态（来自 actor_npc_state，展示/SSE 富化；无=null） ====================

    /** 饥饿 0-100 */
    private Integer hunger;
    /** 口渴 0-100 */
    private Integer thirst;
    /** 精力 0-100 */
    private Integer energy;
    /** 膀胱 0-100 */
    private Integer bladder;
    /** 健康 0-100 */
    private Integer health;
    /** 心情 0-100 */
    private Integer mood;
    /** 主导情绪标签（calm/happy/…/custom:*） */
    private String dominantEmotion;
    /** 情绪强度 0-100 */
    private Integer emotionIntensity;
    /** 情绪 P 轴 -1~1 */
    private Double emotionPleasure;
    /** 情绪 A 轴 -1~1 */
    private Double emotionArousal;
    /** 情绪 D 轴 -1~1 */
    private Double emotionDominance;
    /** urgency 分级 0-3 */
    private Integer urgencyLevel;
    /** urgency 描述（空=无紧急） */
    private String urgencyText;

    /** 时间 */
    private LocalDateTime updatedAt;

    /** 实体转 VO（npcName 由组装方填充） */
    public static SandboxStateVO of(ActorSandboxState e) {
        if (e == null) {
            return null;
        }
        SandboxStateVO vo = new SandboxStateVO();
        vo.setId(e.getId());
        vo.setSceneId(e.getSceneId());
        vo.setNpcType(e.getNpcType());
        vo.setNpcRef(e.getNpcRef());
        vo.setPosX(e.getPosX());
        vo.setPosY(e.getPosY());
        vo.setPosZ(e.getPosZ());
        vo.setHeading(e.getHeading());
        vo.setMoveState(e.getMoveState());
        vo.setActionText(e.getActionText());
        vo.setStatusText(e.getStatusText());
        vo.setThoughtText(e.getThoughtText());
        vo.setThoughtSource(e.getThoughtSource());
        vo.setGameSecond(e.getGameSecond());
        vo.setUpdatedAt(e.getUpdatedAt());
        return vo;
    }
}
