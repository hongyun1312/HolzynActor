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

package com.holzyn.actor.domain.npc.state.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * NPC 内部状态实体，对应表 actor_state。
 * <p>职责：承载每个 NPC 的<b>数值化内部状态</b>——生理四需（饥饿/口渴/精力/膀胱）+ 健康 + 心情 +
 * 情绪（PAD 三维 + 主导标签 + 强度）+ 清洁/社交/趣味 + 舒适/环境修正 + urgency 紧急状态。
 * <b>一行一 NPC</b>（project_id + npc_ref 唯一）；统一 0-100 刻度（PAD 三维 -1~1）；
 * 特殊 NPC 全量维护、人群轻量（仅 hunger/energy/mood/emotion 参与，其余列保持默认不衰减）。</p>
 * <p>职责分离（主开发方案 §4.2）：本表=内部状态权威；actor_sandbox_state=位置/移动权威（不重叠）；
 * 本表更新 = 游戏时间驱动衰减（NpcStateService.advance）+ 行动挂钩恢复（onNpcAction）+ 惰性补偿。</p>
 * <p>疲劳=100-energy 派生展示（不双写字段防漂移）；情绪字段 深化落地（PAD 锚点表
 * actor_emotion_anchor + 程序化情绪规则 + AI 评估写回 + 情绪衰减 + mood 情绪积分），
 * emotion_label_explicit 区分主导情绪来源（AI 显式标签 / 锚点最近者）。</p>
 * <p>设计依据：《NPC状态模块_主开发方案》§5（数据模型）/ §6（程序化更新）。
 * </p>
 */
@Data
@Entity
@Table(name = "actor_state", uniqueConstraints = {
        @UniqueConstraint(name = "uk_npc_state_ref", columnNames = {"project_id", "npc_ref"})
}, indexes = {
        @Index(name = "idx_npc_state_project_type", columnList = "project_id, npc_type")
})
public class ActorNpcState {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目归属（关联 actor_project.id，必填） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** NPC 类型：special 特殊 / common 普通 */
    @Column(name = "npc_type", length = 20)
    private String npcType;

    /** 统一角色引用：npc:{actor_profile.id} */
    @Column(name = "npc_ref", length = 50)
    private String npcRef;

    // ==================== 新增：档案联动列 ====================

    /**
     * per-NPC 生理参数覆盖（11 维档案 ③ 维的落库位置，JSON 文本）。
     * <p><b>这是"生理基线不独立于状态模块"的实现</b>：AI 建档产出的 ③ 生理基线（作息型/睡眠需求/
     * 饥饿周期/代谢快慢/耐痛/耐寒热/酒量…）写在这里，<b>不是</b>写进 persona_json；
     * {@code NpcStateService} 的衰减与阈值计算读取本列并经
     * {@code PhysioProfile} 换算为生效系数。</p>
     */
    @Column(name = "physio_json", columnDefinition = "TEXT")
    private String physioJson;

    /**
     * 生理生效系数（由 {@link #physioJson} 换算并缓存，JSON 短文本）。
     * <p>含 hungerScale/thirstScale/energyScale/bowelScale（代谢与餐制）、sleepNeedHours、
     * sleepStartHour/sleepEndHour（作息相位）、painFactor（耐痛→健康侵蚀）、coldSens/heatSens
     * （耐寒热→环境修正）、recoverScale（体质→恢复速率）。每 tick 衰减只读本列，不解析档案 JSON
     * （性能 + 可用 SQL 直接观测个体差异）。</p>
     */
    @Column(name = "physio_coef_json", length = 512)
    private String physioCoefJson;

    /**
     * 生理参数版本号：与 {@code actor_profile.persona_version} 对齐。
     * <p>档案修订或阶段跨越后版本号推进 → 触发系数重算（避免过期系数长期生效）。</p>
     */
    @Column(name = "physio_version")
    private Integer physioVersion = 0;

    // ==================== 新增：作息状态机（由 actor_profile_ordinary 迁入，运行态唯一家） ====================

    /** 作息/行动状态机：idle 空闲 / walk 行走 / stop 停留 / talk 交谈 / rest 休息 */
    @Column(name = "activity_state", length = 20)
    private String activityState = "idle";

    /** 最近行动描述（状态机推进时更新，供动态流展示） */
    @Column(name = "last_action", length = 255)
    private String lastAction;

    /** 当日计划（JSON）：{date_game_day, items:[{hour,state,action}]}；计划模块生成，事件打断时更新 */
    @Column(name = "daily_plan_json", columnDefinition = "TEXT")
    private String dailyPlanJson;

    /** 上次 tick 时所在发育阶段 */
    @Column(name = "stage_at_last_tick", length = 16)
    private String stageAtLastTick;

    // ==================== 生理需求（0-100，默认见主开发方案 §5） ====================

    /** 饥饿 0-100（默认 70；随游戏时间衰减 → 进食恢复） */
    private Integer hunger = 70;

    /** 口渴 0-100（默认 70；衰减 → 饮水恢复） */
    private Integer thirst = 70;

    /** 精力 0-100（默认 80；清醒活动衰减 → 睡眠/休息恢复；疲劳=100-energy 派生展示） */
    private Integer energy = 80;

    /** 膀胱/尿意 0-100（默认 50；随时间下降 → 如厕恢复；低值产生 urgency） */
    private Integer bladder = 50;

    // ==================== 健康（0-100） ====================

    /** 健康 0-100（默认 100；长期低需求侵蚀 → 休息/治疗恢复；health=0 仅卧床标记不死亡） */
    private Integer health = 100;

    // ==================== 心理（心情 + 情绪） ====================

    /** 心情 0-100 */
    private Integer mood = 70;

    /** 情绪 PAD-愉悦 */
    @Column(name = "emotion_pleasure")
    private Double emotionPleasure = 0.10d;

    /** 情绪 PAD-唤醒（-1.00~1.00，默认 -0.10 = 平静锚点） */
    @Column(name = "emotion_arousal")
    private Double emotionArousal = -0.10d;

    /** 情绪 PAD-支配（-1.00~1.00，默认 0.00 = 平静锚点） */
    @Column(name = "emotion_dominance")
    private Double emotionDominance = 0.00d;

    /** 主导情绪标签：calm/happy/angry/sad/fearful/surprised/disgusted/custom:*（默认 calm） */
    @Column(name = "dominant_emotion", length = 20)
    private String dominantEmotion = "calm";

    /** 当前情绪强度 0-100（PAD 向量模长归一，默认 0） */
    @Column(name = "emotion_intensity")
    private Integer emotionIntensity = 0;

    /** 主导情绪是否来自 AI 显式标签 */
    @Column(name = "emotion_label_explicit")
    private Integer emotionLabelExplicit = 0;

    /** 上次 AI 情绪评估写回的游戏时刻（秒，可空；AI 评估冷却基准——每 NPC 每游戏小时最多 1 次） */
    @Column(name = "last_emotion_eval_game_second")
    private Long lastEmotionEvalGameSecond;

    // ==================== 新增：表达 PAD 三列 + 面具快照 ====================

    /**
     * 表达 PAD-愉悦（-1.00~1.00，默认 null=未计算）。
     * <p><b>面具过滤后的「对外表达情绪」</b>（设计文档 §5.6：真实 PAD 经面具过滤写本列，真实 PAD
     * 保持不变）：{@code emotion_*} 三列是<b>内心真实情绪</b>，本列是<b>此刻情境下允许外露的情绪</b>；
     * 独处/私人场所 = 真实 PAD（面具放下）；由 {@code MaskService.computeExpressed} 在表达入口
     * 现算并写回（决策/对话/群聊/自主对话前），供 prompt 双段注入与前端/SSE 展示。</p>
     */
    @Column(name = "expressed_pleasure")
    private Double expressedPleasure;

    /** 表达 PAD-唤醒（-1.00~1.00；面具过滤后的对外唤醒）。 */
    @Column(name = "expressed_arousal")
    private Double expressedArousal;

    /** 表达 PAD-支配（-1.00~1.00；面具过滤后的对外支配）。 */
    @Column(name = "expressed_dominance")
    private Double expressedDominance;

    /**
     * 面具快照（JSON 短文本，调试/前端展示用）：
     * "breach_trigger":"被提次子","stage_cap":0.80,"algo":"..."}}——上次表达 PAD 计算时的情境、
     * 生效抑制与破绽状态；消费方与调试页读取，便于观察「此刻戴了什么面具、为什么」。
     */
    @Column(name = "mask_snapshot_json", length = 1024)
    private String maskSnapshotJson;

    // ==================== 新增：孕期/生育列（，婚育谱系） ====================

    /**
     * 孕期状态：胎儿父亲 npcRef（{@code npc:{id}}；null=未怀孕）。
     * <p>由 {@code PregnancyService} 在受孕/分娩时写；分娩后清空并记录 {@link #lastBirthGameSecond}。</p>
     */
    @Column(name = "pregnancy_from_ref", length = 50)
    private String pregnancyFromRef;

    /** 孕期开始时刻（游戏秒；预产期 = 开始 + 280 游戏日） */
    @Column(name = "pregnancy_start_game_second")
    private Long pregnancyStartGameSecond;

    /** 预产期时刻（游戏秒；每日检查 {@code gameSecond ≥ due} → 分娩） */
    @Column(name = "pregnancy_due_game_second")
    private Long pregnancyDueGameSecond;

    /**
     * 最近分娩时刻（游戏秒；生育冷却基准——冷却 180 游戏日内不再启动新孕期，参考文档 §9 风险对策）。
     */
    @Column(name = "last_birth_game_second")
    private Long lastBirthGameSecond;

    // ==================== 扩展需求（0-100） ====================

    /** 清洁 0-100（默认 90；洗浴恢复） */
    private Integer hygiene = 90;

    /** 社交 0-100（默认 60；交谈/共处恢复，独处衰减） */
    private Integer social = 60;

    /** 趣味 0-100（默认 60；娱乐活动恢复） */
    private Integer fun = 60;

    /** 舒适 0-100（默认 80；环境/设施即时影响，不随时间自衰减） */
    private Integer comfort = 80;

    // ==================== 派生/辅助 ====================

    /** 环境心情修正 -30~+30（地点/天气/在场者派生；S1 阶段默认 0） */
    @Column(name = "env_modifier")
    private Integer envModifier = 0;

    /** 当前最紧急需求描述（派生：如「非常饥饿，急需进食」；无紧急=空串） */
    @Column(name = "urgency_text", length = 120)
    private String urgencyText = "";

    /** 紧急级 0/1/2/3（0 无 / 1 轻度需求 / 2 强烈需求 / 3 健康告急） */
    @Column(name = "urgency_level")
    private Integer urgencyLevel = 0;

    /** 本行状态对应的游戏时刻（秒，衰减基准；统一单一时间源） */
    @Column(name = "game_second")
    private Long gameSecond;

    /** 最近进食游戏时刻（派生描述/作息分析用，可空） */
    @Column(name = "last_eat_game_second")
    private Long lastEatGameSecond;

    /** 最近睡眠游戏时刻（派生描述/作息分析用，可空） */
    @Column(name = "last_sleep_game_second")
    private Long lastSleepGameSecond;

    // ==================== 小数余额（状态循环修复） ====================

    /**
     * 各需求列的小数余额（JSON 串，如 {@code {"hunger":0.42,"energy":-0.31}}；空串/非法=无余额）。
     * <p>（24h 压测根因）：引擎每 5 游戏分钟 tick 微量衰减（hunger 1.2/时 × 0.083 时 ≈ 0.1 点），
     * 原 {@code Math.round} 直接回写整数列把小数部分永久吞掉 → 需求恒值停滞、状态循环失效。
     * 本列承载各列衰减/恢复的<b>双精度余额结转</b>：数值 = 整数列 + 本余额，展示列按四舍五入回写，
     * 余数继续结转（重启/持久化不丢），仅在触顶/触底钳制时清零。</p>
     * <p>键：hunger/thirst/energy/bladder/hygiene/social/fun/health（与需求/健康列一一对应）；
     * 由 {@code NpcStateService} 静态工具统一读写（纯算法，无 DB 依赖可单测）。</p>
     */
    @Column(name = "decay_frac", length = 512)
    private String decayFrac = "";

    // ==================== 审计 ====================

    /** 软删除标记：0正常/1已删 */
    private Integer deleted = 0;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 插入前回调：填充时间与默认值。
     */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (hunger == null) hunger = 70;
        if (thirst == null) thirst = 70;
        if (energy == null) energy = 80;
        if (bladder == null) bladder = 50;
        if (health == null) health = 100;
        if (mood == null) mood = 70;
        if (emotionPleasure == null) emotionPleasure = 0.10d;
        if (emotionArousal == null) emotionArousal = -0.10d;
        if (emotionDominance == null) emotionDominance = 0.00d;
        if (dominantEmotion == null || dominantEmotion.isBlank()) dominantEmotion = "calm";
        if (emotionIntensity == null) emotionIntensity = 0;
        if (emotionLabelExplicit == null) emotionLabelExplicit = 0;
        if (hygiene == null) hygiene = 90;
        if (social == null) social = 60;
        if (fun == null) fun = 60;
        if (comfort == null) comfort = 80;
        if (envModifier == null) envModifier = 0;
        if (urgencyText == null) urgencyText = "";
        if (urgencyLevel == null) urgencyLevel = 0;
        if (deleted == null) deleted = 0;
        if (decayFrac == null) decayFrac = "";
    }

    /**
     * 前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
