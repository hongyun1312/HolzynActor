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

package com.holzyn.actor.ai.nanojev.ab.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * NanoJev A/B 质量评估 · 单条样本表。
 * <p>职责：承载一次 A/B 批跑内的<b>单条判定样本</b>——从青禾村真实运行记录
 * （actor_ai_call_log）抽取真实情境，按目标场景构造 NanoJev 三题型判定题
 * （choice/boolean/score），然后分别记录 NanoJev 判定（完整概率分布 + argmax + 熵）、
 * Qwen 同题型重问（选中项）、两者 top-1 是否一致（match）、Judge 四维合理性评分。</p>
 * <p>三类目标场景：</p>
 * <ul>
 * <li><b>emotion_eval 情绪评估</b>：score 档位题（此刻情绪档位，自包含档位描述）；
 * 真实情境来源=npc_emotion_eval 历史调用（user_prompt 情境）；</li>
 * <li><b>crowd_decision 候选选择</b>：choice 候选集题（按样本动态提取：历史 action
 * 输出提炼动作摘要 + 通用项「继续当前行动/其他」，5~8 项）；真实情境来源=action
 * 历史调用（user_prompt 情境 + response 决策）；</li>
 * <li><b>social_willingness 社交意愿</b>：boolean 意愿题（此刻是否愿意回应/接话）；
 * 真实情境来源=dialog / group_reply / autonomous_chat_beat 历史调用的发言前情境。</li>
 * </ul>
 * <p><b>match 口径</b>：NanoJev 的 top-1（argmax）与 Qwen 同题型重问
 * 的选中项一致才算 match——同一道题、同一种输出格式下的可复现对照。</p>
 */
@Data
@Entity
@Table(name = "actor_nanojev_ab_sample")
public class ActorNanoJevAbSample {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属 A/B 批跑 */
    @Column(name = "ab_run_id", nullable = false)
    private Long abRunId;

    /** 批次内序号（1..N） */
    @Column(name = "seq")
    private Integer seq = 0;

    /** 目标场景：emotion_eval / crowd_decision / social_willingness */
    @Column(name = "scene", length = 30)
    private String scene;

    /** 题型：score / choice / boolean */
    @Column(name = "question_type", length = 20)
    private String questionType;

    /** 真实情境来源 scene（actor_ai_call_log.scene：npc_emotion_eval/action/dialog/…） */
    @Column(name = "source_scene", length = 48)
    private String sourceScene;

    /** 原 trace_id（回查 actor_ai_call_log 链路用；构造样本无则空） */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    /** 角色引用（npc:{id}；无归属为 null） */
    @Column(name = "npc_ref", length = 40)
    private String npcRef;

    /** 角色名（无归属为 null） */
    @Column(name = "npc_name", length = 100)
    private String npcName;

    /** 情境文本（NanoJev state 共享上下文；judge 输入主体） */
    @Column(name = "state_text", columnDefinition = "LONGTEXT")
    private String stateText;

    /** 判定题面快照 JSON（instructions/criteria/scoreCriteria——模板版本与自包含档位） */
    @Column(name = "question_json", columnDefinition = "LONGTEXT")
    private String questionJson;

    /** NanoJev 响应 JSON（完整概率分布 / argmax / p_true / 期望 / level / 熵） */
    @Column(name = "nanojev_response_json", columnDefinition = "LONGTEXT")
    private String nanojevResponseJson;

    /** NanoJev 调用耗时（毫秒） */
    @Column(name = "nanojev_latency_ms")
    private Long nanojevLatencyMs;

    /** NanoJev 调用状态：success / failed（fail-closed 回退时） */
    @Column(name = "nanojev_status", length = 20)
    private String nanojevStatus;

    /** Qwen 重问输出 JSON（原文 + 解析选中项 answer/top1） */
    @Column(name = "qwen_response_json", columnDefinition = "LONGTEXT")
    private String qwenResponseJson;

    /** Qwen 重问耗时（毫秒） */
    @Column(name = "qwen_latency_ms")
    private Long qwenLatencyMs;

    /** Qwen 重问状态：success / failed */
    @Column(name = "qwen_status", length = 20)
    private String qwenStatus;

    /** 是否一致：NanoJev top-1 == Qwen 选中项（0/1；任一侧失败置 0） */
    @Column(name = "match_flag")
    private Integer matchFlag = 0;

    /** Judge 四维合理性评分 JSON（{scores:{...},reasons:{...},summary}） */
    @Column(name = "judge_scores_json", columnDefinition = "LONGTEXT")
    private String judgeScoresJson;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调：填充时间与默认值。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (seq == null) seq = 0;
        if (matchFlag == null) matchFlag = 0;
    }
}
