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

package com.holzyn.actor.domain.memory.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.npc.profile.entity.ActorAgentProfile;
import com.holzyn.actor.domain.npc.profile.repository.ActorAgentProfileRepository;
import com.holzyn.actor.domain.npc.goal.entity.ActorCharacterGoal;
import com.holzyn.actor.domain.npc.goal.repository.ActorCharacterGoalRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.npc.memory.entity.ActorCoreMemory;
import com.holzyn.actor.domain.npc.memory.repository.ActorCoreMemoryRepository;
import com.holzyn.actor.domain.memory.vo.CoreBlockVO;
import com.holzyn.actor.domain.memory.vo.CoreMemoryVO;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * L3 核心记忆服务。
 * <p>职责：落地设计 §7（L3 核心记忆，Letta core memory blocks 风格）——<b>静态基线组合渲染</b>
 * （角色卡 persona + brainProfile 摘要 + active 目标 + 关键关系四源组合，普通 NPC 走档案轻量基线）、
 * <b>动态核心块</b>（actor_core_memory：CRUD + agent 自编辑 + 版本链 supersedes_id + 只读控制）、
 * <b>恒在上下文注入</b>（【核心记忆】段：静态基线 + 核心块 top-N，有界 token 预算）。</p>
 * <p>关键语义：静态基线<b>不落库</b>（权威源=角色卡/脑力/目标/关系，避免身份数据双源）；
 * 动态核心块表<b>空起步</b>——只有 agent 自编辑（core_memory_update 模板，对话+反思双挂钩）或用户手动 CRUD 才建块；
 * 自编辑升级旧块 version+1 + deleted 软删 + supersedes_id 版本链，<b>归档表写入留</b>；</p>
 * <p>有界约束（对齐设计 §7.2/§9.5）：每块 ≤ {@code max-chars-per-block}（默认 400）字、每角色 ≤
 * {@code max-blocks-per-character}（默认 8）块、恒定注入 ≤ {@code inject-token-budget}（默认 800）字；
 * {@code editable_by_agent=0} 的块 agent 只读（自编辑跳过）。</p>
 * <p>设计依据：《记忆系统分层重构 L1/L2/L3/归档 设计方案》§4.3/§7/§9.1~9.3/§10。
 * 2026 联网调研对齐：Letta core memory blocks（label/limit/agent+developer 双轨编辑/只读控制/custom 块）。</p>
 */
@Slf4j
@Service
public class CoreMemoryService {

    // ==================== 标准块键（对齐设计 §4.3 block_key 枚举） ====================

    /** 身份（来自角色卡 identity 基线，agent 可演进） */
    public static final String KEY_IDENTITY = "identity";
    /** 性格（personality） */
    public static final String KEY_PERSONALITY = "personality";
    /** 核心信念（beliefs） */
    public static final String KEY_BELIEFS = "beliefs";
    /** 价值观（values） */
    public static final String KEY_VALUES = "values";
    /** 长期目标摘要（goals） */
    public static final String KEY_GOALS = "goals";
    /** 关键关系（relations） */
    public static final String KEY_RELATIONS = "relations";
    /** 自定义块前缀（custom:*） */
    public static final String KEY_CUSTOM_PREFIX = "custom:";

    /** 标准块显示名映射（自编辑 create 未给 block_name 时兜底） */
    private static final Map<String, String> STANDARD_BLOCK_NAMES = Map.of(
            KEY_IDENTITY, "身份",
            KEY_PERSONALITY, "性格",
            KEY_BELIEFS, "核心信念",
            KEY_VALUES, "价值观",
            KEY_GOALS, "长期目标",
            KEY_RELATIONS, "关键关系");

    /** 软删除标记：正常 */
    private static final int NOT_DELETED = 0;
    /** 软删除标记：已删 */
    private static final int DELETED = 1;

    // ==================== 配置（application.yml，代码默认值对齐） ====================

    /** L3 核心记忆总开关 */
    @Value("${holzyn.actor.memory.core.enabled:true}")
    private boolean enabled;

    /** 每角色核心块上限（默认 8，设计 §7.2 有界约束） */
    @Value("${holzyn.actor.memory.core.max-blocks-per-character:8}")
    private int maxBlocksPerCharacter;

    /** 每块内容上限（字，默认 400，设计 §7.2 有界约束） */
    @Value("${holzyn.actor.memory.core.max-chars-per-block:400}")
    private int maxCharsPerBlock;

    /** 恒在注入块数上限（默认 8，实际受 ≤max-blocks 约束） */
    @Value("${holzyn.actor.memory.core.inject-top-k:8}")
    private int injectTopK;

    /** 恒在注入字符预算（默认 800，对齐设计 §9.5 L3 ≤800 token；中文按 1 字≈1 token 保守折算） */
    @Value("${holzyn.actor.memory.core.inject-token-budget:800}")
    private int injectTokenBudget;

    /** agent 自编辑总开关（对话/反思挂钩；false=仅用户手动 CRUD） */
    @Value("${holzyn.actor.memory.core.self-edit-enabled:true}")
    private boolean selfEditEnabled;

    /** 自编辑 AI 最大输出 token（对齐抽取口径） */
    @Value("${holzyn.actor.memory.core.self-edit-max-tokens:1024}")
    private int selfEditMaxTokens;

    /** 基线渲染：active 目标 top-N（默认 3） */
    @Value("${holzyn.actor.memory.core.baseline-goal-top-n:3}")
    private int baselineGoalTopN;

    /** 基线渲染：关键关系 top-N（默认 5） */
    @Value("${holzyn.actor.memory.core.baseline-relation-top-n:5}")
    private int baselineRelationTopN;

    /** 普通 NPC（crowd）轻量基线开关（默认 true；false=普通 NPC 不渲染基线） */
    @Value("${holzyn.actor.memory.core.baseline-crowd-enabled:true}")
    private boolean baselineCrowdEnabled;

    /** AI 自编辑温度（对齐抽取口径） */
    private static final double SELF_EDIT_TEMPERATURE = 0.3;

    // ==================== 依赖（构造器=纯数据仓库，AI 依赖字段注入——保持构造器可单测） ====================

    /** 核心块仓库 */
    private final ActorCoreMemoryRepository coreRepository;
    /** 角色卡仓库（静态基线 persona 权威源） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.persona.PersonaService personaService;
    /** 角色仓库（special 档案/归属校验） */
    private final ActorCharacterRepository characterRepository;
    /** 目标仓库（基线 active 目标） */
    private final ActorCharacterGoalRepository goalRepository;
    /** 关系仓库（基线关键关系） */
    private final ActorCharacterRelationRepository relationRepository;
    /** 普通 NPC 仓库（crowd 轻量基线） */
    private final ActorCharacterRepository npcRepository;
    /** 脑力配置仓库（基线 brainProfile 摘要） */
    private final ActorAgentProfileRepository profileRepository;
    /** 项目仓库（归属校验） */
    private final ActorProjectRepository projectRepository;

    /**
     * NPC 类型解析器（注入：npc:{id} 不再编码 special/crowd——基线渲染「普通型走轻量基线/
     * 特殊型走四源基线」、自编辑「仅特殊型」、归属校验分流都必须查 actor_profile.type；
     * 字段注入保持构造器可单测，纯单测直构时 null → 按特殊型处理
     * [与 NpcTypeResolver 解析失败回退 special 同口径]）
     */
    @Autowired(required = false)
    private NpcTypeResolver npcTypeResolver;

    // 自编辑 AI 依赖（字段注入，纯数据测试可置空跳过）
    @Autowired
    private PromptTemplateService promptTemplateService;
    @Autowired
    private AiSpringChatGateway aiChatGateway;
    @Autowired
    private UsageLogService usageLogService;
    @Autowired
    private CurrentUserProvider currentUserProvider;
    @Autowired
    private WorldClockService worldClockService;

    /**
     * 统一上下文注入器（V2.0 段位装配；字段注入保持构造器不变；@Lazy 破「本类↔注入器」
     * 的 Bean 环——注入器渲染角色核心卡时同样依赖本类 renderCoreSegment）。
     * <p>批 6 收口（core_memory_update 属批4 记忆链·角色视角）：自编辑请求前置
     * 段1A【世界观】+段1C【角色核心卡】+段2D【当前世界动态】+段2E【当前状态】
     * （{@code memoryChainContext}）；null=未装配 → 原样渲染（fail-open 零阻断）。</p>
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    /** 归档服务 */
    @Autowired(required = false)
    private MemoryArchiveService archiveService;

    /** Jackson 解析器（persona_json / 自编辑输入解析，线程安全静态复用） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 构造器（纯数据仓库依赖，供 CRUD/基线渲染/自编辑执行单测直接构造）。
     */
    public CoreMemoryService(ActorCoreMemoryRepository coreRepository,
                             ActorCharacterRepository characterRepository,
                             ActorCharacterGoalRepository goalRepository,
                             ActorCharacterRelationRepository relationRepository,
                             ActorCharacterRepository npcRepository,
                             ActorAgentProfileRepository profileRepository,
                             ActorProjectRepository projectRepository) {
        this.coreRepository = coreRepository;
        this.characterRepository = characterRepository;
        this.goalRepository = goalRepository;
        this.relationRepository = relationRepository;
        this.npcRepository = npcRepository;
        this.profileRepository = profileRepository;
        this.projectRepository = projectRepository;
    }

    // ==================== 静态基线组合渲染（read：四源组合，不落库，设计 §7.1） ====================

    /**
     * 静态基线文本（【核心记忆】段的基础部分）：特殊型 {@code npc:{id}} 四源组合（角色卡 persona +
     * brainProfile 摘要 + active 目标 top-N + 关键关系 top-N）；普通型 {@code npc:{id}} 档案轻量基线。
     * <p>纯读不落库（避免身份数据双源，设计 §4.3）；无任何源可渲染/开关关返回空串。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用（{@code npc:{id}}）
     *
     * @return 基线文本（多行，无内容返回空串）
     */
    public String renderBaselineText(Long projectId, String characterRef) {
        if (!enabled || projectId == null || characterRef == null || characterRef.isBlank()) {
            return "";
        }
        String ref = characterRef.trim();
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（普通型走轻量基线，查 actor_profile.type）
        if (isCommonNpc(projectId, ref)) {
            return baselineCrowdEnabled ? renderCrowdBaseline(projectId, ref) : "";
        }
        // 特殊型走四源基线（全量口径：含 persona 档案分片与关键关系）——前端「核心记忆」详情展示用
        return renderSpecialBaseline(projectId, ref, true);
    }

    /**
     * AI 注入口径基线（2026-10-10 身份重复注入根治）：与段 1C【我的身份与处境】/对话
     * system prompt 分工——persona 档案分片（身份锚定/人格价值观/体质/习惯/面具）与关键关系
     * 已由段 1C（characterCard 的档案分片 + 【关键关系】）与对话 system prompt
     * （RoleplayContextService 分片渲染）提供，基线只保留<b>不重复的恒定事实</b>：
     * 脑力档位 + active 目标。避免同一身份文本在【我的身份与处境】与 —— 身份基线 ——
     * 重复注入（用户 2026-10-10 报障：请求输入中身份内容重复出现两次）。
     */
    private String renderBaselineForPrompt(Long projectId, String characterRef) {
        if (!enabled || projectId == null || characterRef == null || characterRef.isBlank()) {
            return "";
        }
        String ref = characterRef.trim();
        if (isCommonNpc(projectId, ref)) {
            return baselineCrowdEnabled ? renderCrowdBaseline(projectId, ref) : "";
        }
        return renderSpecialBaseline(projectId, ref, false);
    }

    /**
     * 特殊 NPC 基线渲染：角色卡 persona（身份/性格/背景）+ brainProfile 摘要 +
     * active 目标 top-N + 关键关系 top-N；每行有界（截断防注入膨胀）。
     * <p>双口径：<b>全量</b>（{@code includePersonaAndRelations=true}，前端详情展示）含
     * persona 档案分片与关键关系；<b>AI 注入</b>（false，{@code renderCoreSegment}/
     * 自编辑等 Prompt 用）跳过 persona 分片与关键关系——二者分别由段 1C【我的身份与处境】
     * 与对话 system prompt 渲染，重复注入同一份身份文本会让模型困惑（2026-10-10 报障根治）。</p>
     */
    private String renderSpecialBaseline(Long projectId, String characterRef, boolean includePersonaAndRelations) {
        // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
        Long characterId = NpcRefs.idOf(characterRef);
        if (characterId == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        // ① 角色卡 persona（权威源 actor_character_card）
        // ：档案权威源由 actor_profile_card.persona_json 改为 actor_profile.persona_json（11 维 v2）。
        // 说明：下方各段的旧键路径（personality.traits / personality.values 等）是 v1 角色卡口径，
        // 在 v2 档案下不再命中——这是"档案结构升级"的预期结果；core memory 的自省叙述不再维护两套人格文本。
        // 2026-10-10 收口：仅全量口径渲染档案分片；AI 注入口径跳过（段 1C/对话 system prompt 已渲染同一分片）
        if (includePersonaAndRelations) {
            String personaJson = personaService == null ? null : personaService.latestPersonaJson(characterId).orElse(null);
            if (personaJson != null && !personaJson.isBlank()) {
                try {
                    // ：这里原先逐条解析 v1 平铺键（personality.traits / personality.values /
                    // background.history / background.goals）来自省叙述——v2 档案是 dims.*，那些键全部不再命中，
                    // 会得到"段标题在、内容全空"的静默退化。
                    // 现改为**直接取档案分片渲染**（决策场景 S1 身份锚定 / S2 人格与价值观 / S4 身体与生理 /
                    // S6 习惯与日常 / S8 社会面具）：既消灭了"再维护一套人格文本"，也保证核心记忆与 Prompt
                    // 注入看到的是同一份表述（单一来源）。
                    String shards = personaService == null ? "" : personaService.renderShardsForScene(characterId, "action_gen");
                    if (shards != null && !shards.isBlank()) {
                        appendLine(sb, shards);
                    }
                } catch (Exception e) {
                    log.warn("[L3 核心] 档案分片渲染失败，基线跳过该源: char={} : {}", characterId, e.getMessage());
                }
            }
        }
        // ② brainProfile 摘要（权威源 actor_agent_profile）
        ActorAgentProfile profile = profileRepository
                .findByAgentTypeAndAgentRefId("special", characterId).orElse(null);
        if (profile != null) {
            appendLine(sb, "脑力：档位 " + (profile.getBrainLevel() == null ? "full" : profile.getBrainLevel())
                    + " / 模型 " + (profile.getModelTier() == null ? "auto" : profile.getModelTier()));
        }
        // ③ active 目标 top-N（权威源 actor_character_goal）
        List<ActorCharacterGoal> activeGoals = new ArrayList<>(goalRepository
                .findByProjectIdAndAgentRefAndStatusAndDeletedOrderByCreatedAtDesc(
                        projectId, characterRef, "active", NOT_DELETED));
        if (!activeGoals.isEmpty()) {
            activeGoals.sort(Comparator.comparingInt((ActorCharacterGoal g) -> g.getPriority() == null ? 3 : g.getPriority())
                    .reversed());
            List<String> goalLines = new ArrayList<>();
            for (ActorCharacterGoal g : activeGoals) {
                if (goalLines.size() >= Math.max(1, baselineGoalTopN)) {
                    break;
                }
                goalLines.add(g.getTitle() + (g.getDescription() == null || g.getDescription().isBlank()
                        ? "" : "（" + cap(g.getDescription(), 60) + "）"));
            }
            appendLine(sb, "长期目标：" + String.join("；", goalLines));
        }
        // ④ 关键关系 top-N（权威源 actor_character_relation，intimate/高好感优先）
        // 2026-10-10 收口：仅全量口径渲染；AI 注入口径由段 1C【关键关系】（characterCard）
        // 与对话感知【当前所见】关系摘要承担（同一关系不再两处注入）
        if (includePersonaAndRelations) {
            List<String> relationLines = keyRelations(projectId, characterRef, characterId);
            if (!relationLines.isEmpty()) {
                appendLine(sb, "关键关系：" + String.join("；", relationLines));
            }
        }
        return sb.toString().trim();
    }

    /**
     * 普通型 NPC 档案轻量基线（{@code npc:{id}}）：姓名/种族/职业/归属/位置/详情摘要。
     */
    private String renderCrowdBaseline(Long projectId, String characterRef) {
        // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
        Long npcId = NpcRefs.idOf(characterRef);
        if (npcId == null) {
            return "";
        }
        ActorCharacter npc = npcRepository.findById(npcId).orElse(null);
        if (npc == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        List<String> bits = new ArrayList<>();
        bits.add(npc.getName() == null || npc.getName().isBlank() ? "无名居民" : npc.getName());
        if (npc.getRace() != null && !npc.getRace().isBlank()) {
            bits.add(npc.getRace());
        }
        if (npc.getOccupation() != null && !npc.getOccupation().isBlank()) {
            bits.add(npc.getOccupation());
        }
        if (npc.getAffiliation() != null && !npc.getAffiliation().isBlank()) {
            bits.add("归属" + npc.getAffiliation());
        }
        if (npc.getLocation() != null && !npc.getLocation().isBlank()) {
            bits.add("常在" + npc.getLocation());
        }
        sb.append("身份：").append(String.join("，", bits));
        if (npc.getDetail() != null && !npc.getDetail().isBlank()) {
            appendLine(sb, "档案：" + cap(npc.getDetail(), 160));
        }
        return sb.toString().trim();
    }

    /**
     * 提取关键关系（双向匹配，intimate 状态优先 + 好感 desc，top-N）。
     */
    private List<String> keyRelations(Long projectId, String characterRef, Long characterId) {
        try {
            List<ActorCharacterRelation> all = relationRepository.findByProjectId(projectId);
            if (all.isEmpty()) {
                return List.of();
            }
            List<ActorCharacterRelation> involved = new ArrayList<>();
            for (ActorCharacterRelation r : all) {                boolean matches = (r.getFromCharacterId() != null && characterId.equals(r.getFromCharacterId()))
                        || (r.getToCharacterId() != null && characterId.equals(r.getToCharacterId()))
                        || (characterRef.equals(r.getFromRef()) || characterRef.equals(r.getToRef()));
                if (matches) {
                    involved.add(r);
                }
            }
            involved.sort(Comparator
                    .comparingInt((ActorCharacterRelation r) -> isIntimate(r) ? 0 : 1)
                    .thenComparingInt(r -> -(r.getAffinity() == null ? 0 : r.getAffinity())));
            List<String> out = new ArrayList<>();
            for (ActorCharacterRelation r : involved) {
                if (out.size() >= Math.max(1, baselineRelationTopN)) {
                    break;
                }
                String other = otherName(r, characterId);
                String line = other + "（" + (r.getRelationType() == null || r.getRelationType().isBlank()
                        ? "相识" : r.getRelationType());
                if (r.getAffinity() != null) {
                    line += "/好感" + r.getAffinity();
                }
                line += "）";
                out.add(line);
            }
            return out;
        } catch (Exception e) {
            log.warn("[L3 核心] 关键关系渲染失败（跳过该源）: project={} ref={} : {}", projectId, characterRef, e.getMessage());
            return List.of();
        }
    }

    /** 关系状态是否 intimate（亲密） */
    private static boolean isIntimate(ActorCharacterRelation r) {
        return "intimate".equals(r.getRelationState());
    }

    /** 关系的「另一方」名称（与当前角色不同的端点） */
    private static String otherName(ActorCharacterRelation r, Long characterId) {
        if (characterId != null && characterId.equals(r.getFromCharacterId())) {
            return r.getToName() == null || r.getToName().isBlank() ? "对方" : r.getToName();
        }
        return r.getFromName() == null || r.getFromName().isBlank() ? "对方" : r.getFromName();
    }

    // ==================== 核心块渲染（恒在上下文，设计 §7.3/§9.1~9.3） ====================

    /**
     * 生成【核心记忆】恒在上下文段：静态基线 + 动态核心块（重要度 desc top-N），有界 token 预算。
     * <p>格式：<pre>
     * 【核心记忆】以下是该角色恒定在上下文的核心身份信息（静态基线 + 核心块），回答时始终以此为准：
     * —— 身份基线 ——
     * {基线行...}
     * —— 核心块（可随经历演进）——
     * [核心信念] {content}
     * </pre>
     * 无任何内容/开关关返回空串（调用方跳过注入，保持旧行为）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用
     *
     * @return 【核心记忆】段文本（无内容返回空串）
     */
    public String renderCoreSegment(Long projectId, String characterRef) {
        if (!enabled || projectId == null || characterRef == null || characterRef.isBlank()) {
            return "";
        }
        String ref = characterRef.trim();
        // 2026-10-10 身份去重：AI 注入口径基线（脑力档位+active 目标），不含 persona 分片与
        // 关键关系（段 1C【我的身份与处境】/对话 system prompt 已渲染，避免重复注入）
        String baseline = renderBaselineForPrompt(projectId, ref);
        List<ActorCoreMemory> blocks = activeBlocks(projectId, ref);
        if (baseline.isBlank() && blocks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【核心记忆】以下是该角色恒定在上下文的核心身份信息（静态基线 + 核心块），回答时始终以此为准：");
        if (!baseline.isBlank()) {
            sb.append("\n—— 身份基线 ——\n").append(baseline);
        }
        if (!blocks.isEmpty()) {
            sb.append("\n—— 核心块（可随经历演进）——");
            for (ActorCoreMemory b : blocks) {
                // 2026-10-10 决策：不设字符/token 预算硬边界，全量注入核心块
                // （条数上限由 activeBlocks 的 injectTopK 约束，字符预算不再截断）
                sb.append("\n[").append(blockDisplayName(b)).append("] ").append(b.getContent());
            }
        }
        return sb.toString().trim();
    }

    /**
     * 活动核心块列表（重要度 desc + 较新优先，top-N；渲染/详情共用）。
     */
    private List<ActorCoreMemory> activeBlocks(Long projectId, String characterRef) {
        List<ActorCoreMemory> all = coreRepository
                .findByProjectIdAndCharacterRefAndDeletedOrderByImportanceDesc(
                        projectId, characterRef.trim(), NOT_DELETED);
        if (all.size() <= injectTopK) {
            return all;
        }
        return new ArrayList<>(all.subList(0, injectTopK));
    }

    /** 块显示名（block_name 兜底标准名/块键） */
    private static String blockDisplayName(ActorCoreMemory b) {
        if (b.getBlockName() != null && !b.getBlockName().isBlank()) {
            return b.getBlockName();
        }
        return STANDARD_BLOCK_NAMES.getOrDefault(b.getBlockKey(), b.getBlockKey());
    }

    // ==================== 核心块 CRUD（开发者/用户编辑，设计 §7.2） ====================

    /**
     * 核心记忆详情 VO（前端「核心记忆」分组）：静态基线只读渲染 + 动态核心块列表（含版本/可编辑标识）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用
     *
     * @return 详情 VO
     */
    public CoreMemoryVO detail(Long projectId, String characterRef) {
        if (projectId == null || characterRef == null || characterRef.isBlank()) {
            return CoreMemoryVO.empty(characterRef);
        }
        String ref = characterRef.trim();
        List<ActorCoreMemory> blocks = coreRepository
                .findByProjectIdAndCharacterRefAndDeletedOrderByImportanceDesc(projectId, ref, NOT_DELETED);
        List<CoreBlockVO> blockVos = blocks.stream().map(CoreBlockVO::of).toList();
        return new CoreMemoryVO(ref, renderBaselineText(projectId, ref), blockVos);
    }

    /**
     * 新建核心块（用户手动）。
     * <p>校验：块键合法（标准键或 custom:*）、内容非空且有界（≤{@code max-chars-per-block}）、
     * 每角色块数 < {@code max-blocks-per-character}；同键已存在时按更新处理（版本链）。</p>
     *
     * @return 新建/更新的块 VO
     */
    @Transactional
    public CoreBlockVO createBlock(Long projectId, String characterRef, String blockKey, String blockName,
                                   String content, Integer importance, Integer editableByAgent) {
        requireProject(projectId);
        requireRefInProject(projectId, characterRef);
        String ref = characterRef.trim();
        String key = normalizeBlockKey(blockKey);
        if (key == null) {
            throw new BizException(400, "核心块键不合法：需为标准键（identity/personality/beliefs/values/goals/relations）或 custom:* 前缀");
        }
        String text = normalizeContent(content);
        if (text.isBlank()) {
            throw new BizException(400, "核心块内容不能为空");
        }
        // 同键已有活动块 → 走更新（版本链）
        ActorCoreMemory existing = coreRepository
                .findByProjectIdAndCharacterRefAndBlockKeyAndDeleted(projectId, ref, key, NOT_DELETED)
                .stream().findFirst().orElse(null);
        if (existing != null) {
            return updateBlockInternal(projectId, existing, blockName, text, importance, editableByAgent);
        }
        // 块数上限校验（≤8）
        long count = coreRepository.countByProjectIdAndCharacterRefAndDeleted(projectId, ref, NOT_DELETED);
        if (count >= Math.max(1, maxBlocksPerCharacter)) {
            throw new BizException(400, "核心块已达上限（" + maxBlocksPerCharacter + " 块），请先删除或合并后再新增");
        }
        ActorCoreMemory b = new ActorCoreMemory();
        b.setProjectId(projectId);
        b.setCharacterRef(ref);
        b.setBlockKey(key);
        b.setBlockName(resolveBlockName(key, blockName));
        b.setContent(text);
        b.setImportance(clampImportance(importance));
        b.setEditableByAgent(Integer.valueOf(0).equals(editableByAgent) ? 0 : 1);
        b.setVersion(1);
        coreRepository.save(b);
        log.info("[L3 核心] 新建核心块：project={} ref={} key={} 内容={}字", projectId, ref, key, text.length());
        return CoreBlockVO.of(b);
    }

    /**
     * 核心块（用户手动，版本链：旧块 deleted=1 + supersedes_id 指向新块，新块 version=旧.version+1）。
     */
    @Transactional
    public CoreBlockVO updateBlock(Long projectId, Long blockId, String blockName, String content,
                                   Integer importance, Integer editableByAgent) {
        requireProject(projectId);
        ActorCoreMemory existing = coreRepository.findById(blockId)
                .filter(b -> projectId.equals(b.getProjectId()))
                .filter(b -> NOT_DELETED == (b.getDeleted() == null ? NOT_DELETED : b.getDeleted()))
                .orElseThrow(() -> new BizException(404, "核心块不存在或无权访问"));
        return updateBlockInternal(projectId, existing, blockName, content, importance, editableByAgent);
    }

    /**
     * 核心块核心实现：校验内容有界 → 旧块软删 + 新块（version+1、supersedes_id=旧 id）。
     */
    private CoreBlockVO updateBlockInternal(Long projectId, ActorCoreMemory old,
                                            String blockName, String content, Integer importance,
                                            Integer editableByAgent) {
        String text = normalizeContent(content);
        if (text.isBlank()) {
            throw new BizException(400, "核心块内容不能为空");
        }
        ActorCoreMemory b = new ActorCoreMemory();
        b.setProjectId(projectId);
        b.setCharacterRef(old.getCharacterRef());
        b.setBlockKey(old.getBlockKey());
        b.setBlockName(blockName != null && !blockName.isBlank() ? blockName.trim()
                : (old.getBlockName() == null || old.getBlockName().isBlank() ? old.getBlockKey() : old.getBlockName()));
        b.setContent(text);
        b.setImportance(clampImportance(importance == null ? old.getImportance() : importance));
        b.setEditableByAgent(Integer.valueOf(0).equals(editableByAgent)
                ? 0 : (old.getEditableByAgent() == null ? 1 : old.getEditableByAgent()));
        b.setVersion((old.getVersion() == null ? 1 : old.getVersion()) + 1);
        b.setSupersedesId(old.getId());
        coreRepository.save(b);
        // 旧块软删 + 归档
        old.setDeleted(DELETED);
        old.setUpdatedAt(java.time.LocalDateTime.now());
        coreRepository.save(old);
        if (archiveService != null) {
            try {
                archiveService.archiveCoreBlock(old, MemoryArchiveService.REASON_SUPERSEDED);
            } catch (Exception e) {
                log.warn("[L3 核心] 旧块归档失败（不阻断版本链）: key={} : {}", old.getBlockKey(), e.getMessage());
            }
        }
        log.info("[L3 核心] 更新核心块：project={} ref={} key={} 新版本={}", projectId, old.getCharacterRef(),
                old.getBlockKey(), b.getVersion());
        return CoreBlockVO.of(b);
    }

    /**
     * 删除核心块（软删，用户手动）。
     */
    @Transactional
    public void deleteBlock(Long projectId, Long blockId) {
        requireProject(projectId);
        ActorCoreMemory b = coreRepository.findById(blockId)
                .filter(x -> projectId.equals(x.getProjectId()))
                .filter(x -> NOT_DELETED == (x.getDeleted() == null ? NOT_DELETED : x.getDeleted()))
                .orElseThrow(() -> new BizException(404, "核心块不存在或无权访问"));
        b.setDeleted(DELETED);
        b.setUpdatedAt(java.time.LocalDateTime.now());
        coreRepository.save(b);
        log.info("[L3 核心] 删除核心块：project={} ref={} key={}", projectId, b.getCharacterRef(), b.getBlockKey());
    }

    /**
     * 手动归档核心块：写归档（原文冷存保真）+ 软删——前端「归档」按钮用；
     * 与 {@link #deleteBlock} 的区别：归档保留原文可检索/还原，删除仅软删不落归档表。
     *
     * @return 是否执行成功（false=块不存在/越权）
     */
    @Transactional
    public boolean archiveBlockManual(Long projectId, Long blockId) {
        requireProject(projectId);
        ActorCoreMemory b = coreRepository.findById(blockId)
                .filter(x -> projectId.equals(x.getProjectId()))
                .filter(x -> NOT_DELETED == (x.getDeleted() == null ? NOT_DELETED : x.getDeleted()))
                .orElse(null);
        if (b == null) {
            return false;
        }
        b.setDeleted(DELETED);
        b.setUpdatedAt(java.time.LocalDateTime.now());
        coreRepository.save(b);
        if (archiveService != null) {
            try {
                archiveService.archiveCoreBlock(b, MemoryArchiveService.REASON_MANUAL);
            } catch (Exception e) {
                log.warn("[L3 核心] 手动归档块失败（不阻断软删）: key={} : {}", b.getBlockKey(), e.getMessage());
            }
        }
        log.info("[L3 核心] 手动归档核心块：project={} ref={} key={}", projectId, b.getCharacterRef(), b.getBlockKey());
        return true;
    }

    // ==================== agent 自编辑（core_memory_update 模板，对话 + 反思双挂钩，设计 §7.2） ====================

    /**
     * 对话挂钩入口：本轮对话抽取新增草稿后触发——把当前核心块 + 最近对话交给 AI
     * 评估是否需要更新核心块（对齐 Letta「对话中 agent 自编辑」）；失败静默不阻断对话主流程。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用
     *
     * @param dialogText 最近对话文本（抽取输入同源）
     */
    public void maybeSelfEditFromDialog(Long userId, Long projectId, String characterRef, String dialogText) {
        if (!selfEditEnabled || !enabled || projectId == null || characterRef == null
                || characterRef.isBlank() || dialogText == null || dialogText.isBlank()) {
            return;
        }
        String ref = characterRef.trim();
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（是否特殊型查 actor_profile.type）
        if (!isSpecialNpc(projectId, ref)) {
            return; // 普通型只读/手动，不自编辑
        }
        try {
            int applied = selfEditFromContext(userId, projectId, ref, "近期对话", dialogText);
            if (applied > 0) {
                log.info("[L3 核心自编辑] 对话挂钩完成：ref={} 应用更新 {} 条", ref, applied);
            }
        } catch (Exception e) {
            log.warn("[L3 核心自编辑] 对话挂钩失败（静默跳过）: ref={} : {}", ref, e.getMessage());
        }
    }

    /**
     * 反思挂钩入口：反思完成后触发——把当前核心块 + 反思洞察/依据交给 AI 评估
     * 是否需要更新核心块；反思节奏已受上限约束（每角色每游戏日 ≤2 次），成本天然有界。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用（仅特殊型 NPC）
     *
     * @param reflectionContext 反思上下文（证据记忆 + 洞察摘要）
     */
    public void maybeSelfEditFromReflection(Long userId, Long projectId, String characterRef, String reflectionContext) {
        if (!selfEditEnabled || !enabled || projectId == null || characterRef == null
                || characterRef.isBlank() || reflectionContext == null || reflectionContext.isBlank()) {
            return;
        }
        String ref = characterRef.trim();
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（是否特殊型查 actor_profile.type）
        if (!isSpecialNpc(projectId, ref)) {
            return;
        }
        try {
            int applied = selfEditFromContext(userId, projectId, ref, "反思洞察", reflectionContext);
            if (applied > 0) {
                log.info("[L3 核心自编辑] 反思挂钩完成：ref={} 应用更新 {} 条", ref, applied);
            }
        } catch (Exception e) {
            log.warn("[L3 核心自编辑] 反思挂钩失败（静默跳过）: ref={} : {}", ref, e.getMessage());
        }
    }

    /**
     * 自编辑统一执行：渲染 core_memory_update 模板 → json_object AI 调用 → 解析更新指令 → 应用。
     * <p>AI 失败/输出非法一律静默返回 0（自编辑尽力而为，不重试不阻断）；trace/用量
     * scene=memory 记账（对齐抽取/摘要口径）。</p>
     *
     * @return 实际应用的更新条数
     */
    private int selfEditFromContext(Long userId, Long projectId, String characterRef, String contextKind, String contextText) {
        if (promptTemplateService == null || aiChatGateway == null || usageLogService == null) {
            return 0;
        }
        String baseline = renderBaselineForPrompt(projectId, characterRef);
        String blocksJson = currentBlocksJson(projectId, characterRef);
        if (baseline.isBlank() && (blocksJson == null || blocksJson.isBlank())) {
            return 0; // 无可供评估的身份信息（特殊 NPC 无角色卡时跳过）
        }
        String gameTime = "";
        if (worldClockService != null) {
            try {
                Long gh = worldClockService.currentGameHour(projectId);
                if (gh != null) {
                    gameTime = worldClockService.gameTimeTextOf(projectId, gh);
                }
            } catch (Exception ignored) {
                // 游戏时间取不到不阻断
            }
        }
        String prompt = promptTemplateService.render(userId, projectId, PromptTemplateService.CODE_CORE_MEMORY_UPDATE,
                Map.of("character_info", baseline.isBlank() ? "（暂无基线，仅依据核心块与情境）" : baseline,
                        "current_core_blocks", blocksJson == null || blocksJson.isBlank() ? "[]" : blocksJson,
                        "context_kind", contextKind,
                        "context", contextText,
                        "game_time", gameTime.isBlank() ? "未知" : gameTime));
        // 批6 收口（core_memory_update 属批4 记忆链·角色视角）：前置 段1A+1C+2D+2E——
        // 自编辑「什么值得写进 L3 核心」先知道世界/身份/此刻处境；未装配 → 原样（fail-open）
        final String finalPrompt;
        if (aiContextInjector != null) {
            String ctx = aiContextInjector.memoryChainContext(projectId, characterRef, null);
            finalPrompt = (ctx == null || ctx.isBlank()) ? prompt : ctx + "\n\n" + prompt;
        } else {
            finalPrompt = prompt;
        }
        Long charId = NpcRefs.idOf(characterRef);
        AiChatResult result = AiCallContext.run("memory", charId, () ->
                aiChatGateway.chatCompletion(userId, projectId, null,
                        new AiChatRequest(null, List.of(
                                new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                                new AiChatRequest.ChatMessage("user", finalPrompt)),
                                SELF_EDIT_TEMPERATURE, selfEditMaxTokens, true)));
        usageLogService.record(userId, projectId, charId, result.providerId(), result.model(), "memory",
                result.promptTokens(), result.completionTokens(),
                result.cacheHitTokens(), result.cacheMissTokens(), 0);
        List<CoreMemoryUpdateParse.CoreMemoryUpdate> updates = CoreMemoryUpdateParse.parse(result.content());
        if (updates.isEmpty()) {
            return 0;
        }
        return applySelfEdit(projectId, characterRef, updates);
    }

    /**
     * 应用自编辑更新指令（纯数据，单测可直测）：逐条校验（action 枚举/块键/只读/字数/块数）后执行
     * update/create/delete；不满足约束的条目跳过并计数（不抛异常不阻断后续条目）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用
     *
     * @param updates 解析后的更新指令列表
     *
     * @return 实际应用的条数
     */
    @Transactional
    public int applySelfEdit(Long projectId, String characterRef, List<CoreMemoryUpdateParse.CoreMemoryUpdate> updates) {
        if (!enabled || projectId == null || characterRef == null || characterRef.isBlank()
                || updates == null || updates.isEmpty()) {
            return 0;
        }
        String ref = characterRef.trim();
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（是否特殊型查 actor_profile.type）
        if (!isSpecialNpc(projectId, ref)) {
            return 0; // 普通型不自编辑
        }
        int applied = 0;
        for (CoreMemoryUpdateParse.CoreMemoryUpdate u : updates) {
            try {
                if (applyOneSelfEdit(projectId, ref, u)) {
                    applied++;
                }
            } catch (Exception e) {
                log.warn("[L3 核心自编辑] 单条更新失败（跳过）: ref={} action={} key={} : {}",
                        ref, u.action(), u.blockKey(), e.getMessage());
            }
        }
        return applied;
    }

    /**
     * 单条自编辑应用：action=update（含同键不存在时降级 create）/create/delete；返回是否真正执行。
     */
    private boolean applyOneSelfEdit(Long projectId, String characterRef, CoreMemoryUpdateParse.CoreMemoryUpdate u) {
        String action = u.action() == null ? "" : u.action().trim();
        String key = normalizeBlockKey(u.blockKey());
        if (key == null) {
            return false; // 非法块键
        }
        ActorCoreMemory existing = coreRepository
                .findByProjectIdAndCharacterRefAndBlockKeyAndDeleted(projectId, characterRef, key, NOT_DELETED)
                .stream().findFirst().orElse(null);
        if ("delete".equals(action)) {
            if (existing == null) {
                return false;
            }
            if (Integer.valueOf(0).equals(existing.getEditableByAgent())) {
                log.debug("[L3 核心自编辑] 只读块拒绝删除：ref={} key={}", characterRef, key);
                return false;
            }
            existing.setDeleted(DELETED);
            existing.setUpdatedAt(java.time.LocalDateTime.now());
            coreRepository.save(existing);
            // 归档：agent 删除的旧块原文写归档（reason=manual，内容冷存保真）
            if (archiveService != null) {
                try {
                    archiveService.archiveCoreBlock(existing, MemoryArchiveService.REASON_MANUAL);
                } catch (Exception e) {
                    log.warn("[L3 核心自编辑] 删除块归档失败（不阻断）: key={} : {}", key, e.getMessage());
                }
            }
            return true;
        }
        if ("create".equals(action) || "update".equals(action)) {
            String content = normalizeContent(u.content());
            if (content.isBlank()) {
                return false;
            }
            if (existing != null && Integer.valueOf(0).equals(existing.getEditableByAgent())) {
                log.debug("[L3 核心自编辑] 只读块拒绝修改：ref={} key={}", characterRef, key);
                return false;
            }
            if (existing == null) {
                // create：块数上限校验
                long count = coreRepository.countByProjectIdAndCharacterRefAndDeleted(projectId, characterRef, NOT_DELETED);
                if (count >= Math.max(1, maxBlocksPerCharacter)) {
                    log.debug("[L3 核心自编辑] 块数已达上限，跳过新建：ref={} key={}", characterRef, key);
                    return false;
                }
                ActorCoreMemory b = new ActorCoreMemory();
                b.setProjectId(projectId);
                b.setCharacterRef(characterRef);
                b.setBlockKey(key);
                b.setBlockName(resolveBlockName(key, u.blockName()));
                b.setContent(content);
                b.setImportance(clampImportance(u.importance()));
                b.setEditableByAgent(1); // agent 自建块默认可再编辑
                b.setVersion(1);
                coreRepository.save(b);
                return true;
            }
            // update：版本链（旧块软删 + 新块 version+1 + supersedes_id）
            ActorCoreMemory b = new ActorCoreMemory();
            b.setProjectId(projectId);
            b.setCharacterRef(characterRef);
            b.setBlockKey(key);
            b.setBlockName(u.blockName() != null && !u.blockName().isBlank() ? u.blockName().trim()
                    : (existing.getBlockName() == null || existing.getBlockName().isBlank() ? key : existing.getBlockName()));
            b.setContent(content);
            b.setImportance(clampImportance(u.importance() == null ? existing.getImportance() : u.importance()));
            b.setEditableByAgent(existing.getEditableByAgent() == null ? 1 : existing.getEditableByAgent());
            b.setVersion((existing.getVersion() == null ? 1 : existing.getVersion()) + 1);
            b.setSupersedesId(existing.getId());
            coreRepository.save(b);
            existing.setDeleted(DELETED);
            existing.setUpdatedAt(java.time.LocalDateTime.now());
            coreRepository.save(existing);
            // 归档：自编辑升级旧块原文写归档（reason=superseded）
            if (archiveService != null) {
                try {
                    archiveService.archiveCoreBlock(existing, MemoryArchiveService.REASON_SUPERSEDED);
                } catch (Exception e) {
                    log.warn("[L3 核心自编辑] 升级旧块归档失败（不阻断版本链）: key={} : {}", key, e.getMessage());
                }
            }
            return true;
        }
        return false;
    }

    /** 当前核心块 JSON（自编辑输入，含只读标识/版本） */
    private String currentBlocksJson(Long projectId, String characterRef) {
        List<ActorCoreMemory> blocks = coreRepository
                .findByProjectIdAndCharacterRefAndDeletedOrderByImportanceDesc(projectId, characterRef.trim(), NOT_DELETED);
        if (blocks.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (ActorCoreMemory b : blocks) {
            list.add(Map.of(
                    "block_key", b.getBlockKey(),
                    "block_name", blockDisplayName(b),
                    "content", b.getContent(),
                    "importance", b.getImportance() == null ? 3 : b.getImportance(),
                    "editable_by_agent", Integer.valueOf(0).equals(b.getEditableByAgent()) ? 0 : 1,
                    "version", b.getVersion() == null ? 1 : b.getVersion()));
        }
        try {
            return MAPPER.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    // ==================== 私有辅助 ====================

    /**
     * 块键归一化：标准键原样返回；custom:* 校验前缀；空/非法返回 null。
     */
    private static String normalizeBlockKey(String blockKey) {
        if (blockKey == null || blockKey.isBlank()) {
            return null;
        }
        String key = blockKey.trim();
        if (STANDARD_BLOCK_NAMES.containsKey(key)) {
            return key;
        }
        if (key.startsWith(KEY_CUSTOM_PREFIX) && key.length() > KEY_CUSTOM_PREFIX.length()) {
            return key;
        }
        return null;
    }

    /** 块显示名解析（block_name 优先，标准名兜底，再兜底块键） */
    private static String resolveBlockName(String blockKey, String blockName) {
        if (blockName != null && !blockName.isBlank()) {
            return blockName.trim();
        }
        return STANDARD_BLOCK_NAMES.getOrDefault(blockKey, blockKey);
    }

    /** 内容规范化：去首尾空白 + 有界截断（≤max-chars-per-block 字） */
    private String normalizeContent(String content) {
        if (content == null) {
            return "";
        }
        String t = content.trim();
        return t.length() > Math.max(1, maxCharsPerBlock) ? t.substring(0, maxCharsPerBlock) : t;
    }

    /** 一行（含换行符） */
    private static void appendLine(StringBuilder sb, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("\n");
        }
        sb.append(line);
    }

    /** 字符串有界截断 */
    private static String cap(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    /** 重要度钳制 1~5（null 回退 3） */
    private static int clampImportance(Integer importance) {
        if (importance == null) {
            return 3;
        }
        return Math.max(1, Math.min(5, importance));
    }

    /** 校验项目归属当前用户（越权抛 404） */
    private void requireProject(Long projectId) {
        if (currentUserProvider == null) {
            return; // 纯数据测试上下文跳过归属校验
        }
        Long userId = currentUserProvider.currentUserId();
        projectRepository.findByIdAndUserIdAndDeleted(projectId, userId, 0)
                .orElseThrow(() -> new BizException(404, "项目不存在或无权访问"));
    }

    /** 校验角色引用属于该项目（越权抛 404） */
    private void requireRefInProject(Long projectId, String characterRef) {
        String ref = characterRef == null ? "" : characterRef.trim();
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（普通型/特殊型走各自归属校验，查 actor_profile.type）
        if (isCommonNpc(projectId, ref)) {
            // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
            Long npcId = NpcRefs.idOf(ref);
            if (npcId != null) {
                npcRepository.findByIdAndProjectId(npcId, projectId)
                        .orElseThrow(() -> new BizException(404, "普通 NPC 不存在或无权访问"));
            }
            return;
        }
        // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
        Long specialId = NpcRefs.idOf(ref);
        if (specialId != null) {
            characterRepository.findById(specialId)
                    .filter(c -> projectId.equals(c.getProjectId()))
                    .filter(c -> Integer.valueOf(0).equals(c.getDeleted()))
                    .orElseThrow(() -> new BizException(404, "角色不存在或无权访问"));
        }
    }

    /**
     * npc:{id} 不再编码类型——「是否普通型」决定基线走轻量/四源与是否允许自编辑，
     * 此处按意图 B 处理，改查 {@code actor_profile.type}。
     * <p>解析器未装配（纯单测直构无 Spring）时按特殊型处理——与 {@link NpcTypeResolver} 解析失败
     * 回退 {@code special} 同口径。</p>
     */
    private boolean isCommonNpc(Long projectId, String characterRef) {
        return npcTypeResolver != null && npcTypeResolver.isCommon(projectId, characterRef);
    }

    /**
     * 是否特殊型 NPC（意图 B；解析器未装配时按特殊型处理，见 {@link #isCommonNpc}）。
     */
    private boolean isSpecialNpc(Long projectId, String characterRef) {
        return npcTypeResolver == null || npcTypeResolver.isSpecial(projectId, characterRef);
    }
}
