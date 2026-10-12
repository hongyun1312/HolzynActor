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

package com.holzyn.actor.domain.agent.brain.service;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.npc.profile.entity.ActorAgentProfile;
import com.holzyn.actor.domain.npc.profile.repository.ActorAgentProfileRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 脑力配置服务（阶段 C BrainProfile，domain/agent/brain/service）。
 * <p>职责：把「每个智能体的脑力配置」做成<b>幂等可查的权威数据源</b>——
 * 供启动补行（存量角色/普通 NPC 默认建行）、新建接入（角色/普通 NPC 创建后自动建行）、
 * 以及六组件统一入口（resolveByRef：{@code npc:{actor_profile.id}} 字符串 → 脑力配置行）复用。</p>
 * <p>核心语义：</p>
 * <ul>
 * <li><b>幂等</b>：ensureProfile 已有行直接返回、缺失按默认值补行（unique 键 agent_type+agent_ref_id
 * 兜底并发，H2 ddl-auto 建 uk_agent_profile_ref）；不建行不阻断业务（失败仅告警）。</li>
 * <li><b>默认值</b>：特殊→full/auto(premium)/realtime
 * （planning/reflection 开）；普通→light/auto(standard)/per_interval（关）；
 * thinking 恒 1；memory_budget / ai_budget_per_tick 默认 null（继承全局/项目）。</li>
 * <li><b>语义分离</b>：getProfile 缺失返回 null 不自动建（只读查询）；ensureProfile/resolveByRef 自动建。</li>
 * </ul>
 * <p>默认值解析抽静态纯逻辑 {@link #defaultsFor(String)}（可单测）；行遍历 {@link #backfillExisting()}
 * 沿用 OldCrowdTableCleaner 思路——逐条 try 隔离，失败不阻断启动。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrainProfileService {

    /** 特殊型智能体类型值 */
    public static final String TYPE_SPECIAL = "special";
    /** 普通型智能体类型值（=actor_profile.id） */
    public static final String TYPE_ORDINARY = "ordinary";

    private final ActorAgentProfileRepository profileRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRepository npcRepository;

    /**
     * NPC 类型解析器（注入：npc:{id} 不再编码类型——special/ordinary 是「类型」不是前缀，
     * 必须查 actor_profile.type；字段注入保持构造器三参不变[存量单测零改动]，纯单测直构时为 null
     * → 按特殊型处理[与 NpcTypeResolver 解析失败回退 special 同口径]）
     */
    @Autowired(required = false)
    private NpcTypeResolver npcTypeResolver;

    /**
     * 默认脑力配置（defaultsFor 返回值，纯数据载体）。
     *
     * @param brainLevel 脑力档位（full/light/programmatic）
     *
     * @param modelTier 模型档位（premium/standard/auto）
     *
     * @param decisionFrequency 决策频率（realtime/per_day/per_interval）
     *
     * @param thinkingEnabled 思考开关（0/1）
     *
     * @param planningEnabled 规划开关（0/1）
     *
     * @param reflectionEnabled 反思开关（0/1）
     */
    public record DefaultBrain(String brainLevel, String modelTier, String decisionFrequency,
                               int thinkingEnabled, int planningEnabled, int reflectionEnabled) {
    }

    /**
     * 静态默认值解析（可单测）：按智能体类型返回默认脑力配置。
     * <p>权威默认值：</p>
     * <ul>
     * <li>special → full / auto（解析默认 premium）/ realtime，planning/reflection 开（1）；</li>
     * <li>ordinary → light / auto（解析默认 standard）/ per_interval，planning/reflection 关（0）；</li>
     * <li>非法/未知类型 → programmatic（纯程序化，AI 0 成本）——未知类型不花 AI 预算的最安全回退。</li>
     * </ul>
     *
     * @param agentType 智能体类型（special/ordinary；其余视为非法回退）
     *
     * @return 默认脑力配置
     */
    static DefaultBrain defaultsFor(String agentType) {
        if (TYPE_SPECIAL.equals(agentType)) {
            return new DefaultBrain("full", "auto", "realtime", 1, 1, 1);
        }
        if (TYPE_ORDINARY.equals(agentType)) {
            return new DefaultBrain("light", "auto", "per_interval", 1, 0, 0);
        }
        // 非法/未知类型：programmatic 纯程序化回退（0 AI 成本）
        return new DefaultBrain("programmatic", "auto", "per_interval", 0, 0, 0);
    }

    /**
     * 幂等建行（供启动补行与新建角色共用）：已有返回、缺失按默认值补行。
     * <p>不建行不阻断业务：入参非法返回 null；保存失败由调用方按需处理（新建接入处仅告警）。</p>
     *
     * @param agentType 智能体类型（special/ordinary）
     *
     * @param refId 智能体引用 ID（特殊=角色 id / 普通=普通 NPC id）
     *
     * @return 脑力配置行（新建或已有；入参非法返回 null）
     */
    @Transactional
    public ActorAgentProfile ensureProfile(String agentType, Long refId) {
        return ensureProfileInternal(agentType, refId);
    }

    /**
     * 查询脑力配置；缺失返回 null（不自动建，语义分离）。
     *
     * @param agentType 智能体类型（special/ordinary）
     *
     * @param refId 智能体引用 ID
     *
     * @return 脑力配置行（可空）
     */
    @Transactional(readOnly = true)
    public ActorAgentProfile getProfile(String agentType, Long refId) {
        if (agentType == null || agentType.isBlank() || refId == null) {
            return null;
        }
        return profileRepository.findByAgentTypeAndAgentRefId(agentType, refId).orElse(null);
    }

    /**
     * 统一角色引用解析（六组件统一入口）：{@code npc:{actor_profile.id}} → 查 actor_profile.type 得
     * special/ordinary，解析后 ensureProfile 并返回（引用非法返回 null）。
     *
     * @param characterRef 统一角色引用（{@code npc:{actor_profile.id}}）
     *
     * @return 脑力配置行（可空）
     */
    @Transactional
    public ActorAgentProfile resolveByRef(String characterRef) {
        ParsedRef ref = parseRef(characterRef);
        if (ref == null) {
            return null;
        }
        return ensureProfileInternal(ref.type(), ref.id());
    }

    /**
     * 保存脑力配置（/R4 反思节奏计数更新用：reflect_count_today/last_reflect_game_day 变更后落库）。
     *
     * @param profile 脑力配置行（须已存在）
     *
     * @return 保存后的脑力配置行
     */
    @Transactional
    public ActorAgentProfile save(ActorAgentProfile profile) {
        if (profile == null || profile.getId() == null) {
            return profile;
        }
        return profileRepository.save(profile);
    }

    /**
     * 启动补行：遍历未删除特殊型角色 → ensureProfile("special", id)；遍历普通型 NPC →
     * ensureProfile("ordinary", id)。只增不删；逐条 try 隔离，失败不阻断启动（对齐
     * OldCrowdTableCleaner 思路）。
     * <p>npc:{id} 不再编码类型，且 special/common 已合并进同一张 actor_profile 表——
     * 两个循环必须按行自带的 type 列过滤（意图 B），否则同一行会被补出 special/ordinary 两套档位。</p>
     *
     * @return 已确保补行的智能体数量（含已有行）
     */
    public int backfillExisting() {
        int ensured = 0;
        // 特殊角色：仅未删除（软删除角色不补行）
        List<ActorCharacter> characters = characterRepository.findByDeletedOrderByIdAsc(0);
        for (ActorCharacter ch : characters) {
            // ：类型不再由前缀编码——按行自带 type 列过滤（isCommonType 内部 normalizeType 归一：
            // 历史 crowd → common，空值回退 special），取代原「两张表各自遍历」隐含的类型划分
            if (NpcRefs.isCommonType(ch.getType())) {
                continue;
            }
            try {
                ensureProfileInternal(TYPE_SPECIAL, ch.getId());
                ensured++;
            } catch (Exception e) {
                // 单条补行失败不阻断后续（幂等，下次启动重试）
                log.warn("[脑力配置] 特殊角色补行失败（跳过，不影响启动）: char={} : {}", ch.getId(), e.getMessage());
            }
        }
        // 普通 NPC：单表合并后按 type=common 过滤（原 actor_ordinary_npc 无软删除列）
        List<ActorCharacter> npcs = npcRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
        for (ActorCharacter npc : npcs) {
            // ：同上——普通型判定按行自带 type 列（NpcRefs.normalizeType 归一）
            if (!NpcRefs.isCommonType(npc.getType())) {
                continue;
            }
            try {
                ensureProfileInternal(TYPE_ORDINARY, npc.getId());
                ensured++;
            } catch (Exception e) {
                // 单条补行失败不阻断后续（幂等，下次启动重试）
                log.warn("[脑力配置] 普通 NPC 补行失败（跳过，不影响启动）: npc={} : {}", npc.getId(), e.getMessage());
            }
        }
        log.info("[脑力配置] 存量补行完成：{} 个智能体已确保 actor_agent_profile 行（特殊→full/普通→light）",
                ensured);
        return ensured;
    }

    // ==================== 内部实现 ====================

    /**
     * 解析结果（agentType + refId 对）。
     *
     * @param type 智能体类型（special/ordinary）
     *
     * @param id 引用 ID
     */
    record ParsedRef(String type, Long id) {
    }

    /**
     * 解析统一角色引用：{@code npc:{actor_profile.id}} → special / ordinary（类型查 actor_profile.type）；
     * 非法返回 null。实例方法。
     *
     * @param characterRef 统一角色引用（{@code npc:{actor_profile.id}}）
     *
     * @return 解析结果（可空）
     */
    ParsedRef parseRef(String characterRef) {
        // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
        // （保持原有非法/空 → null 的处理）
        Long id = NpcRefs.idOf(characterRef);
        if (id == null) {
            return null;
        }
        // ：special/ordinary 是「类型」不是前缀，此处按意图 B 处理——查 actor_profile.type
        // （本方法上游无 projectId，按「不做项目校验」口径传 null；解析器未装配时按特殊型回退）
        boolean special = npcTypeResolver == null || npcTypeResolver.isSpecial(null, characterRef);
        return new ParsedRef(special ? TYPE_SPECIAL : TYPE_ORDINARY, id);
    }

    /**
     * 幂等建行内部实现（无事务注解：backfillExisting 逐条 try 隔离时各 repository 调用各自成事务，
     * 避免共享事务被单条失败标记 rollback-only 拖累整批）。
     *
     * @param agentType 智能体类型（special/ordinary）
     *
     * @param refId 智能体引用 ID
     *
     * @return 脑力配置行（已有或新建；入参非法返回 null）
     */
    private ActorAgentProfile ensureProfileInternal(String agentType, Long refId) {
        if (agentType == null || agentType.isBlank() || refId == null) {
            return null;
        }
        return profileRepository.findByAgentTypeAndAgentRefId(agentType, refId).orElseGet(() -> {
            DefaultBrain d = defaultsFor(agentType);
            ActorAgentProfile p = new ActorAgentProfile();
            p.setAgentType(agentType);
            p.setAgentRefId(refId);
            p.setBrainLevel(d.brainLevel());
            p.setModelTier(d.modelTier());
            p.setDecisionFrequency(d.decisionFrequency());
            p.setThinkingEnabled(d.thinkingEnabled());
            p.setPlanningEnabled(d.planningEnabled());
            p.setReflectionEnabled(d.reflectionEnabled());
            // memory_budget / ai_budget_per_tick 默认 null（继承全局/项目，不显式设置）
            return profileRepository.save(p);
        });
    }
}
