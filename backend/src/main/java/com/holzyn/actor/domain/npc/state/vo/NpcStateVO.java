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

import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NPC 内部状态视图对象。
 * <p>职责：向角色页「状态」Tab / 调试页展示 NPC 内部状态全字段（需求/心情/情绪/健康/urgency），
 * 附疲劳=100-energy 与需求派生描述（needLabels，复用 {@link NpcStateService#needDescriptor}），
 * 前端据此渲染需求进度条 / PAD 三维条 / 紧急预警。</p>
 */
@Data
public class NpcStateVO {

    /** 状态行 ID */
    private Long id;

    /** 项目 ID */
    private Long projectId;

    /** NPC 类型：special / crowd */
    private String npcType;

    /** NPC 引用：special:{id} / crowd:{id} */
    private String npcRef;

    // ==================== 生理四需（0-100） ====================

    /** 饥饿 */
    private Integer hunger;
    /** 口渴 */
    private Integer thirst;
    /** 精力 */
    private Integer energy;
    /** 膀胱 */
    private Integer bladder;

    /** 健康 0-100 */
    private Integer health;

    /** 心情 0-100 */
    private Integer mood;

    // ==================== 情绪（PAD + 主导 + 强度） ====================

    /** 情绪 P 轴 -1~1 */
    private Double emotionPleasure;
    /** 情绪 A 轴 -1~1 */
    private Double emotionArousal;
    /** 情绪 D 轴 -1~1 */
    private Double emotionDominance;
    /** 主导情绪标签（calm/happy/…/custom:*） */
    private String dominantEmotion;
    /** 情绪强度 0-100 */
    private Integer emotionIntensity;
    /** 主导情绪来源（1=AI 显式标签 / 0=锚点最近者） */
    private Integer emotionLabelExplicit;

    // ==================== 扩展需求 ====================

    /** 清洁 */
    private Integer hygiene;
    /** 社交 */
    private Integer social;
    /** 趣味 */
    private Integer fun;
    /** 舒适 */
    private Integer comfort;
    /** 环境心情修正 -30~+30 */
    private Integer envModifier;

    //

    // ==================== urgency ====================
    /** urgency 分级 0-3 */
    private Integer urgencyLevel;
    /** urgency 描述（空=无紧急） */
    private String urgencyText;

    // ==================== 时间 ====================

    /** 状态对应游戏时刻（秒） */
    private Long gameSecond;
    /** 最近进食游戏时刻 */
    private Long lastEatGameSecond;
    /** 最近睡眠游戏时刻 */
    private Long lastSleepGameSecond;
    /** 时间 */
    private LocalDateTime updatedAt;

    // ==================== 派生展示 ====================

    /** 疲劳 = 100 - energy（派生展示，不双写字段） */
    private Integer fatigue;

    /** 需求派生描述（Map：需求名 → 描述，如 饥饿→略饿；前端展示双轨） */
    private Map<String, String> needLabels;

    /** 实体转 VO（needLabels/fatigue 由组装方填充或本方法补默认） */
    public static NpcStateVO of(ActorNpcState e) {
        if (e == null) {
            return null;
        }
        NpcStateVO vo = new NpcStateVO();
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
        vo.setEmotionLabelExplicit(e.getEmotionLabelExplicit());
        vo.setHygiene(e.getHygiene());
        vo.setSocial(e.getSocial());
        vo.setFun(e.getFun());
        vo.setComfort(e.getComfort());
        vo.setEnvModifier(e.getEnvModifier());
        vo.setUrgencyLevel(e.getUrgencyLevel());
        vo.setUrgencyText(e.getUrgencyText());
        vo.setGameSecond(e.getGameSecond());
        vo.setLastEatGameSecond(e.getLastEatGameSecond());
        vo.setLastSleepGameSecond(e.getLastSleepGameSecond());
        vo.setUpdatedAt(e.getUpdatedAt());
        Integer energy = e.getEnergy();
        vo.setFatigue(energy == null ? 20 : Math.max(0, Math.min(100, 100 - energy)));
        vo.setNeedLabels(buildLabels(e));
        return vo;
    }

    /** 需求派生描述表（复用 NpcStateService.needDescriptor，纯静态） */
    private static Map<String, String> buildLabels(ActorNpcState e) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("饥饿", NpcStateService.needDescriptor("饥饿", e.getHunger() == null ? 70 : e.getHunger()));
        labels.put("口渴", NpcStateService.needDescriptor("口渴", e.getThirst() == null ? 70 : e.getThirst()));
        labels.put("精力", NpcStateService.needDescriptor("精力", e.getEnergy() == null ? 80 : e.getEnergy()));
        labels.put("膀胱", NpcStateService.needDescriptor("膀胱", e.getBladder() == null ? 50 : e.getBladder()));
        labels.put("健康", NpcStateService.needDescriptor("健康", e.getHealth() == null ? 100 : e.getHealth()));
        labels.put("清洁", NpcStateService.needDescriptor("清洁", e.getHygiene() == null ? 90 : e.getHygiene()));
        labels.put("社交", NpcStateService.needDescriptor("社交", e.getSocial() == null ? 60 : e.getSocial()));
        labels.put("趣味", NpcStateService.needDescriptor("趣味", e.getFun() == null ? 60 : e.getFun()));
        labels.put("舒适", NpcStateService.needDescriptor("舒适", e.getComfort() == null ? 80 : e.getComfort()));
        return labels;
    }
}
