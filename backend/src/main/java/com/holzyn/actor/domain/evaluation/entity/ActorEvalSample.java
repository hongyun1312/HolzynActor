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

package com.holzyn.actor.domain.evaluation.entity;

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
 * 行为质量评估·评估样本表。
 * <p>职责：承载一次评估批次内的<b>单条决策样本</b>——从 {@code actor_ai_call_log}（DB 采样）
 * 或外部 {@code ai-calls.jsonl}（JSONL 导入）取出的真实 AI 决策调用快照：
 * 角色/场景/游戏时刻/刺激文本（user_prompt 摘录）/决策上下文（system_prompt 摘录）/
 * AI 输出（response）。judge 仅依据样本快照打分（离线、可回放、与实时世界解耦）。</p>
 * <p>样本与 {@link ActorEvalScore} 1:1（一次成功评分一条）；评分失败样本 scored=false 保留待查。</p>
 */
@Data
@Entity
@Table(name = "actor_eval_sample")
public class ActorEvalSample {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属评估批次 */
    @Column(name = "eval_run_id", nullable = false)
    private Long evalRunId;

    /** 批次内序号（1..N） */
    @Column(name = "seq")
    private Integer seq = 0;

    /** 样本来源：db / jsonl */
    @Column(name = "source", length = 20)
    private String source = "db";

    /** 角色引用（special:{id} / crowd:{id}；无归属为 null） */
    @Column(name = "npc_ref", length = 40)
    private String npcRef;

    /** 角色名（无归属为 null） */
    @Column(name = "npc_name", length = 100)
    private String npcName;

    /** 调用场景（决策类 scene：action_gen/interrupt_resume/dialog/autonomous_chat_beat/negotiate） */
    @Column(name = "scene", length = 30)
    private String scene;

    /** 调用状态（原 actor_ai_call_log.status：success/degraded/failed） */
    @Column(name = "call_status", length = 20)
    private String callStatus = "success";

    /** 游戏秒位置（来自 detail_json 摘录；不可得为 null） */
    @Column(name = "game_second")
    private Long gameSecond;

    /** 刺激/情境文本（user_prompt 摘录，judge 输入主体） */
    @Column(name = "stimulus_text", columnDefinition = "LONGTEXT")
    private String stimulusText;

    /** 决策上下文（system_prompt 摘录：角色个性/环境/记忆注入段） */
    @Column(name = "context_text", columnDefinition = "LONGTEXT")
    private String contextText;

    /** AI 决策输出（response 全文） */
    @Column(name = "response_text", columnDefinition = "LONGTEXT")
    private String responseText;

    /** 原 trace_id（回查 actor_ai_call_log 链路用） */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调：填充时间与默认值。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (seq == null) seq = 0;
        if (source == null || source.isBlank()) source = "db";
        if (callStatus == null || callStatus.isBlank()) callStatus = "success";
    }
}
