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

package com.holzyn.actor.domain.agent.learning;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.character.service.RelationRefs;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 动态关系/好感演化服务（阶段 D，domain/agent/learning）——学习组件「关系演化」真实实现。
 * <p>职责：互动（对话/行动/事件）经本服务增减
 * 状态机（{@link #stateOf}）重算 relation_state，并发布 kind=relation 世界事件（拓扑/事件流
 * 实时反映）；同时为角色关系手动 CRUD 提供端点解析复用（{@link RelationRefs}，修）。</p>
 * <p>启发式规则：</p>
 * <ul>
 * <li><b>对话</b>（群聊轮内 NPC 两两互动）：每轮每对 +{@code dialog-delta}（默认 1）双向；
 * 单聊一方为玩家（非角色）不建模，不增减；</li>
 * <li><b>行动</b>（特殊角色决策 target 命中项目内角色）：按行动 type 增减
 * （help/interact/trade 正向、fight/flee 负向、move/schedule/custom 中性 0）；</li>
 * <li><b>事件</b>（世界事件带 ≥2 参与角色，排除 action/evolution/relation 防双算/自触发）：
 * 每对 +{@code event-delta}（默认 1）双向；</li>
 * <li><b>AI 精修</b>：手动端点触发（code=relation_refine 模板，见 CharacterRelationService.aiRefine），
 * 把「当前状态+数值+角色档案」交 AI 综合判断后落库。</li>
 * </ul>
 * <p>开关：{@code holzyn.actor.learning.relations.enabled}（默认 true）。</p>
 * <p>；依赖 character 域仓库与 agent/event 总线，R1 单向）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RelationDynamicsService {

    /** 关系状态机阈值常量（集中可调）：好感 ≤ 该值=敌对 */
    static final int HOSTILE_MAX = -60;
    /** 好感 ≤ 该值=对手（-60 ~ -30） */
    static final int RIVAL_MAX = -30;
    /** 好感 ≥ 该值=相识（15 起） */
    static final int STRANGER_MIN = 15;
    /** 好感 > 该值且信任达标=朋友 */
    static final int FRIEND_MIN = 45;
    /** 朋友所需最低信任 */
    static final int FRIEND_TRUST = 40;
    /** 好感 > 该值且信任达标=亲密 */
    static final int INTIMATE_MIN = 70;
    /** 亲密所需最低信任 */
    static final int INTIMATE_TRUST = 60;

    private final ActorCharacterRelationRepository relationRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRepository ordinaryNpcRepository;
    private final WorldEventBus worldEventBus;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 空间重算触发判定（关系跨档检测点——状态机重算后状态码变化 →
     * NanoJev boolean 确认触发空间重算 RELATION_JUMP；异步提交零阻断；字段注入保持构造器不变）
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.space.SpaceTriggerJudge spaceTriggerJudge;

    /** 动态关系总开关（默认 true；false=互动不增减好感/信任，手动 CRUD 仍可用） */
    @Value("${holzyn.actor.learning.relations.enabled:true}")
    private boolean relationsEnabled;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 好感/信任更新（LearningService.updateRelation 真实实现）：
     * 解析端点 → 查找或创建关系行 → 增减（越界钳制）→ 状态机重算 → 发布 relation 事件。
     *
     * @param projectId 项目 ID
     *
     * @param fromRef 发起方统一角色引用（special:{id}/crowd:{id}/名称）
     *
     * @param toRef 目标方统一角色引用
     *
     * @param delta 好感增量（-100~100；0=仅刷新互动时间，不新建行）
     *
     * @param reason 变更原因（对话/行动/事件摘要）
     */
    @Transactional
    public void updateRelation(Long projectId, String fromRef, String toRef, int delta, String reason) {
        if (!relationsEnabled || projectId == null) {
            return;
        }
        String from = trimRef(fromRef);
        String to = trimRef(toRef);
        if (from == null || to == null || from.equals(to)) {
            return;
        }
        Endpoint fromEp = resolveEndpoint(projectId, from);
        Endpoint toEp = resolveEndpoint(projectId, to);
        if (fromEp == null || toEp == null) {
            return;
        }
        // 幽灵端点（名称未命中任何真实角色）不参与动态关系建模——互动只作用于真实角色之间
        if (fromEp.ref() == null || toEp.ref() == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        ActorCharacterRelation rel = findRelation(projectId, fromEp, toEp);
        if (delta == 0) {
            // 中性互动：仅刷新最近互动时间（不新建行、不发布事件，避免噪声）
            if (rel != null) {
                rel.setLastInteractionAt(now);
                relationRepository.save(rel);
            }
            return;
        }
        if (rel == null) {
            rel = createRelation(projectId, fromEp, toEp, "未知关系", null);
        }
        int oldAffinity = rel.getAffinity() == null ? 0 : rel.getAffinity();
        int oldTrust = rel.getTrust() == null ? 0 : rel.getTrust();
        int newAffinity = clamp(oldAffinity + delta, -100, 100);
        // 信任随好感同向变化（幅度为好感一半，防信任过快饱和/崩塌）
        int newTrust = clamp(oldTrust + delta / 2, 0, 100);
        String oldState = rel.getRelationState();
        String newState = stateOf(newAffinity, newTrust);
        rel.setAffinity(newAffinity);
        rel.setTrust(newTrust);
        rel.setRelationState(newState);
        rel.setLastInteractionAt(now);
        relationRepository.save(rel);
        // 事件驱动重算触发：关系跨档检测点——状态机重算后状态码变化 →
        // 异步提交判定（本方法在事务内，AI 判定在虚拟线程执行，不占本事务连接；
        // NanoJev boolean 确认 + 程序化兜底 → 空间重算 RELATION_JUMP；零阻断）
        if (spaceTriggerJudge != null && oldState != null && !oldState.equals(newState)) {
            try {
                spaceTriggerJudge.onRelationJump(projectId, from, oldState, newState,
                        from + " 与 " + to + " 的关系从「" + oldState + "」变为「" + newState
                                + "」（好感 " + oldAffinity + "→" + newAffinity + "）", null);
            } catch (Exception e) {
                log.debug("[Learning] 关系跨档触发判定提交失败（跳过）: project={} : {}", projectId, e.getMessage());
            }
        }
        AiCallContext.recordStep(projectId, "agent_learning", "learning",
                "关系好感更新：" + from + "→" + to + " delta=" + delta + "（affinity " + oldAffinity + "→" + newAffinity
                        + " trust " + oldTrust + "→" + newTrust + " 状态=" + rel.getRelationState() + "）", 0);
        log.info("[Learning] 关系好感更新：project={} {}→{} delta={}（affinity {}→{} 状态={}）",
                projectId, from, to, delta, oldAffinity, newAffinity, rel.getRelationState());
        publishRelationEvent(projectId, rel, fromEp, toEp, delta, reason, oldAffinity, oldTrust);
    }

    /**
     * 群聊轮互动：一对角色双向增减好感。
     *
     * @param projectId 项目 ID
     *
     * @param refA 参与方 A ref
     *
     * @param refB 参与方 B ref
     *
     * @param delta 好感增量
     *
     * @param reason 变更原因
     */
    public void applyDialogPairDelta(Long projectId, String refA, String refB, int delta, String reason) {
        if (refA == null || refB == null || refA.equals(refB)) {
            return;
        }
        updateRelation(projectId, refA, refB, delta, reason);
        updateRelation(projectId, refB, refA, delta, reason);
    }

    /**
     * 行动挂钩：把「行动目标名称」解析为项目内角色 ref（特殊/普通 NPC；幽灵不建模返回 null），
     * 命中且非自身时应用单向好感增量（行动方 → 目标方）。
     *
     * @param projectId 项目 ID
     *
     * @param actorRef 行动方 ref
     *
     * @param targetName 决策 target 字段（角色名/地点名，仅角色名命中才建模）
     *
     * @param delta 按行动 type 映射的好感增量
     *
     * @param reason 变更原因
     */
    public void applyActionDelta(Long projectId, String actorRef, String targetName, int delta, String reason) {
        String targetRef = resolveTargetRef(projectId, targetName);
        if (targetRef == null || targetRef.equals(actorRef)) {
            return;
        }
        updateRelation(projectId, actorRef, targetRef, delta, reason);
    }

    /**
     * 把行动目标名称解析为项目内角色 ref（特殊/普通 NPC 名称匹配；不命中返回 null）。
     */
    public String resolveTargetRef(Long projectId, String targetName) {
        Endpoint ep = resolveEndpoint(projectId, targetName);
        return ep == null || ep.ref() == null ? null : ep.ref();
    }

    /**
     * 关系状态机（静态纯逻辑，可单测）：affinity+trust 双维度定状态。
     * <p>映射：</p>
     * <ul>
     * <li>hostile 敌对（affinity ≤ -60）；</li>
     * <li>stranger 陌生（affinity &lt; 15）；</li>
     * <li>acquaintance 相识（15 ≤ affinity ≤ 45，或高好感但信任不足）；</li>
     * <li>friend 朋友（affinity &gt; 45 且 trust ≥ 40）；</li>
     * <li>intimate 亲密（affinity &gt; 70 且 trust ≥ 60）。</li>
     * </ul>
     *
     * @param affinity 好感（-100~100）
     *
     * @param trust 信任（0~100）
     *
     * @return 关系状态键
     */
    public static String stateOf(int affinity, int trust) {
        if (affinity <= HOSTILE_MAX) {
            return "hostile";
        }
        if (affinity <= RIVAL_MAX) {
            return "rival";
        }
        if (affinity < STRANGER_MIN) {
            return "stranger";
        }
        if (affinity > INTIMATE_MIN && trust >= INTIMATE_TRUST) {
            return "intimate";
        }
        if (affinity > FRIEND_MIN && trust >= FRIEND_TRUST) {
            return "friend";
        }
        return "acquaintance";
    }

    /**
     * 关系状态中文标签（展示用；未知回退「未知」）。
     */
    public static String stateLabel(String state) {
        if (state == null) {
            return "未知";
        }
        return switch (state) {
            case "hostile" -> "敌对";
            case "rival" -> "对手";
            case "stranger" -> "陌生";
            case "acquaintance" -> "相识";
            case "friend" -> "朋友";
            case "intimate" -> "亲密";
            default -> state;
        };
    }

    // ==================== 私有辅助 ====================

    /** 端点解析（名称优先正式 ref，修）；：npc:{id} 不再编码类型，此处按意图 A 处理（单表仓库） */
    private Endpoint resolveEndpoint(Long projectId, String refOrName) {
        RelationRefs.Endpoint ep = RelationRefs.resolve(projectId, refOrName, characterRepository);
        return ep == null ? null : new Endpoint(ep.ref(), ep.id(), ep.name());
    }

    /** 查找项目内匹配 from→to 方向的关系行（正式 ref 优先精确匹配） */
    private ActorCharacterRelation findRelation(Long projectId, Endpoint from, Endpoint to) {
        for (ActorCharacterRelation r : relationRepository.findByProjectId(projectId)) {
            if (RelationRefs.endpointMatches(r.getFromRef(), r.getFromCharacterId(), r.getFromName(), toEndpoint(from))
                    && RelationRefs.endpointMatches(r.getToRef(), r.getToCharacterId(), r.getToName(), toEndpoint(to))) {
                return r;
            }
        }
        return null;
    }

    /** Endpoint → RelationRefs.Endpoint（统一类型） */
    private static RelationRefs.Endpoint toEndpoint(Endpoint e) {
        return new RelationRefs.Endpoint(e.ref(), e.id(), e.name());
    }

    /** 创建关系行（按端点存储：special 存 id+名+ref；crowd 存名+ref；幽灵存名） */
    private ActorCharacterRelation createRelation(Long projectId, Endpoint from, Endpoint to, String type, String desc) {
        ActorCharacterRelation r = new ActorCharacterRelation();
        r.setProjectId(projectId);
        RelationRefs.Store fs = RelationRefs.storeOf(toEndpoint(from));
        RelationRefs.Store ts = RelationRefs.storeOf(toEndpoint(to));
        r.setFromCharacterId(fs.characterId());
        r.setFromName(fs.name());
        r.setFromRef(fs.ref());
        r.setToCharacterId(ts.characterId());
        r.setToName(ts.name());
        r.setToRef(ts.ref());
        r.setRelationType(type == null || type.isBlank() ? "未知关系" : type);
        r.setDescription(desc);
        return relationRepository.save(r);
    }

    /** 发布关系变迁事件（kind=relation，源=learning；source_key 唯一防幂等折叠） */
    private void publishRelationEvent(Long projectId, ActorCharacterRelation rel, Endpoint from, Endpoint to,
                                      int delta, String reason, int oldAffinity, int oldTrust) {
        try {
            List<String> refs = new ArrayList<>();
            if (from.ref() != null) {
                refs.add(from.ref());
            }
            if (to.ref() != null) {
                refs.add(to.ref());
            }
            if (refs.isEmpty()) {
                return; // 两端均为幽灵（无正式角色），不发布参与事件
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("relationId", rel.getId());
            payload.put("fromRef", from.ref() == null ? from.name() : from.ref());
            payload.put("toRef", to.ref() == null ? to.name() : to.ref());
            payload.put("fromName", from.name());
            payload.put("toName", to.name());
            payload.put("delta", delta);
            payload.put("affinity", rel.getAffinity());
            payload.put("trust", rel.getTrust());
            payload.put("relationState", rel.getRelationState());
            payload.put("reason", reason == null ? "" : reason);
            worldEventBus.publish(currentUserProvider.currentUserId(), projectId, EventSpec.builder()
                    .kind("relation")
                    .source("learning")
                    .characterRefs(refs)
                    .visibility("global")
                    .title("关系变化：" + displayName(from) + " 对 " + displayName(to))
                    .content("好感 " + oldAffinity + "→" + rel.getAffinity() + "（Δ" + delta + "），信任 "
                            + oldTrust + "→" + rel.getTrust() + "，状态=" + stateLabel(rel.getRelationState())
                            + (reason == null || reason.isBlank() ? "" : "；" + reason))
                    .payloadJson(toJson(payload))
                    .sourceKey("learning:rel:" + rel.getId() + ":" + System.nanoTime())
                    .build());
        } catch (Exception e) {
            log.warn("[Learning] 关系变迁事件发布失败: relation={} : {}", rel.getId(), e.getMessage());
        }
    }

    /** 端点显示名（名称快照优先，null 回退「未知」） */
    private static String displayName(Endpoint e) {
        return e.name() == null || e.name().isBlank() ? "未知" : e.name();
    }

    /** 引用去空白（null/空白返回 null） */
    private static String trimRef(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    /** 数值钳制到 [min, max] */
    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** payload → JSON（失败返回空对象串，不阻断） */
    private static String toJson(Map<String, Object> payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 端点轻量封装（与 RelationRefs.Endpoint 对齐，供本服务内部使用） */
    private record Endpoint(String ref, Long id, String name) {
    }
}
