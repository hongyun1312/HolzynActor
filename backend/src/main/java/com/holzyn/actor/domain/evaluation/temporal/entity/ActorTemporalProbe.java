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

package com.holzyn.actor.domain.evaluation.temporal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 时间性探针结果实体，对应表 actor_temporal_probe（时间性验证）。
 * <p><b>职责</b>：一次「探针 × 角色 × 变体」AI 询问的完整留档——问题、注入证据、原始回答、
 * 断言判定与延迟。这是审计层<b>唯一的写入口</b>；分布/衰减/因果报告都由现算生成，不落快照。</p>
 * <p><b>为什么问题与证据也落库</b>：判失败时必须能复现「当时给模型看了什么」，否则无法区分
 * 「模型不懂时间」与「注入层没给它时间信息」——这是本版本最需要避免的误判。</p>
 */
@Data
@Entity
@Table(name = "actor_temporal_probe", indexes = {
        @Index(name = "idx_temporal_probe_proj_gs", columnList = "project_id, game_second"),
        @Index(name = "idx_temporal_probe_type", columnList = "project_id, probe_type, verdict")
})
public class ActorTemporalProbe {

    /** 探针类型：否定状态-离开地点 */
    public static final String TYPE_STALE_PLACE = "stale_place";
    /** 探针类型：否定状态-实体失效 */
    public static final String TYPE_STALE_ENTITY = "stale_entity";
    /** 探针类型：时间回溯 */
    public static final String TYPE_TEMPORAL_RECALL = "temporal_recall";
    /** 探针类型：因果账本 */
    public static final String TYPE_CAUSAL_CHAIN = "causal_chain";

    /** 变体：完整注入 */
    public static final String VARIANT_FULL = "full";
    /** 变体：关闭实体/位置状态注入（对照组，制造可解释失败） */
    public static final String VARIANT_NO_STATE = "no_state";
    /** 变体：只给动作台账 */
    public static final String VARIANT_LEDGER = "ledger";
    /** 变体：篡改前序动作（配对反事实，检查探针敏感性） */
    public static final String VARIANT_LEDGER_MUTATED = "ledger_mutated";

    /** 判定：通过 */
    public static final String VERDICT_PASS = "PASS";
    /** 判定：失败 */
    public static final String VERDICT_FAIL = "FAIL";
    /** 判定：证据不足（无可用真值/无法解析） */
    public static final String VERDICT_INCONCLUSIVE = "INCONCLUSIVE";
    /** 判定：无候选（世界内没有该形态的失效实体/轨迹） */
    public static final String VERDICT_SKIP = "SKIP";
    /** 判定：调用/解析异常 */
    public static final String VERDICT_ERROR = "ERROR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "run_tag", length = 64)
    private String runTag;

    @Column(name = "probe_type", nullable = false, length = 24)
    private String probeType;

    @Column(nullable = false, length = 24)
    private String variant;

    @Column(name = "character_ref", length = 64)
    private String characterRef;

    @Column(name = "character_id")
    private Long characterId;

    @Column(name = "game_second")
    private Long gameSecond;

    @Column(name = "game_day")
    private Long gameDay;

    @Column(columnDefinition = "TEXT")
    private String question;

    @Column(name = "evidence_snapshot", columnDefinition = "TEXT")
    private String evidenceSnapshot;

    @Column(name = "expected_json", columnDefinition = "TEXT")
    private String expectedJson;

    @Column(name = "answer_raw", columnDefinition = "TEXT")
    private String answerRaw;

    @Column(name = "answer_json", columnDefinition = "TEXT")
    private String answerJson;

    @Column(length = 16)
    private String verdict;

    @Column(name = "assertion_detail", columnDefinition = "TEXT")
    private String assertionDetail;

    @Column(name = "prompt_len")
    private Integer promptLen;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
