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

package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.domain.agent.brain.service.BrainProfileService;
import com.holzyn.actor.domain.growth.persona.PersonaDims;
import com.holzyn.actor.domain.growth.persona.PersonaJson;
import com.holzyn.actor.domain.growth.persona.PersonaProfile;
import com.holzyn.actor.domain.memory.service.CoreMemoryService;
import com.holzyn.actor.domain.npc.profile.entity.ActorAgentProfile;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxState;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxStateRepository;
import com.holzyn.actor.domain.world.config.EnvironmentProperties;
import com.holzyn.actor.domain.world.service.EnvironmentContextService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.domain.world.vo.EnvironmentContextVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 个性注入服务。
 * <p>职责：为<b>即时决策（STIMULUS）</b>与<b>打断恢复决策（interrupt_resume）</b>组装
 * 【角色个性】段 ——
 * 零新表复用既有素材源：角色卡 personaJson（actor_character_card）+ L3 核心记忆
 * （actor_core_memory renderCoreSegment）+ 脑力/行为倾向（actor_agent_profile brainProfile）+
 * 关系摘要（actor_character_relation，<b>相关人物优先 + 全量兜底</b>=社交差异化）+ 时间感
 * （当前游戏时刻 + 当前行动 + 已持续游戏时长）。</p>
 * <p>设计依据：LLM 的人格不是稳定本质而是
 * 提示词条件化的「被召唤的配置」（Anthropic PSM 2026-02：post-training 是选择并稳定一个
 * pre-training 学到的角色；「persona is summoned and maintained against entropy」）——
 * 因此<b>每次即时决策/恢复决策都必须全量注入个性段</b>才能维持性格一致性（千人千面）；
 * OCEAN 五维数值直接进提示词（对齐 ETASR OCEAN 驱动 NPC 决策研究）；关系注入支撑社交差异化
 * （MoltNet arXiv 2602.13458：社会互动中意图/态度随关系分化）；RPLA（arXiv 2601.10122）
 * 验收口径=同一刺激不同性格角色给出不同但各自合理的反应（LIFECHOICE）。</p>
 * <p>成本：纯文本组装零 AI 调用；各段长度预算 + 全段总长上限（personality-max-length 默认 600）
 * 防止撑爆决策 Prompt。失败逐段降级（服务未装配/数据缺失自动跳过该段），全段为空返回空串
 * 。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonalityInjectionService {

    /** 角色卡性格标签/价值观/小癖好提取上限（每类 ≤3 条，防撑爆） */
    private static final int MAX_PERSONA_BITS = 3;

    /** 关系摘要单条长度上限（截断防撑爆） */
    private static final int RELATION_LINE_MAX = 80;

    /** 关系摘要总数上限（相关人物优先 ≤3 + 全量兜底 ≤5） */
    private static final int MAX_RELATION_RELATED = 3;
    private static final int MAX_RELATION_TOTAL = 5;

    /** 当前行动已持续时长展示上限（游戏分钟；超出显示「已持续很久」） */
    private static final int DURATION_DISPLAY_MAX = 720;

    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRelationRepository relationRepository;
    private final RealtimeDecisionProperties props;
    private final ObjectMapper objectMapper;

    /**
     * 档案服务。
     * <p>取代历史 {@code ActorCharacterCardRepository}：角色卡表已废弃，11 维档案并入
     * {@code actor_profile.persona_json}，并由本服务按场景渲染分片（信息密度取最优）。</p>
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.growth.persona.PersonaService personaService;

    /** L3 核心记忆服务（renderCoreSegment；null=未装配跳过 L3 段，不阻断个性注入） */
    @Autowired(required = false)
    private CoreMemoryService coreMemoryService;

    /** 脑力配置服务（resolveByRef → brainProfile；null=未装配跳过脑力段） */
    @Autowired(required = false)
    private BrainProfileService brainProfileService;

    /** 世界时钟（当前游戏时刻/世界历文本；null=未装配跳过时间感时刻部分） */
    @Autowired(required = false)
    private WorldClockService worldClockService;

    /** 沙盘状态仓库（当前行动开始时刻=行 game_second，供「已持续时长」；null=跳过时长部分） */
    @Autowired(required = false)
    private ActorSandboxStateRepository sandboxStateRepository;

    /**
     * 统一环境服务（时间季节系统 /：当前环境上下文——时间+环境段/极值约束/性格加权；
     * 字段注入保持构造器不变，null=跳过环境相关段）
     */
    @Autowired(required = false)
    private EnvironmentContextService environmentContextService;

    /** NPC 状态服务 */
    @Autowired(required = false)
    private com.holzyn.actor.domain.npc.state.service.NpcStateService npcStateService;

    /** 环境注入配置（极值高权重阈值；null=未装配跳过环境约束段） */
    @Autowired(required = false)
    private EnvironmentProperties environmentProperties;

    // ==================== 个性段组装（即时决策/恢复决策共用） ====================

    /**
     * 组装【角色个性】段（全量注入，主方案 §4.4）。
     * <p>段落：档案分片（身份/人格/体质/习惯/面具，经渲染器按决策场景组合）→
     * L3 核心块（renderCoreSegment）→ 脑力/行为倾向（brainProfile）→ 关系摘要
     * （relatedNames 相关人物优先 + 全量兜底，社交差异化）→ 时间感（当前游戏时刻 + 当前行动 +
     * 已持续游戏时长）→ 环境（当前环境 / 环境偏好 / 环境约束）。逐段降级：
     * 服务未装配/数据缺失自动跳过该段；全段为空返回空串。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 角色引用（{@code npc:{id}}）
     *
     * @param gameSecond 当前游戏秒（可空=世界时钟当前值）
     *
     * @param relatedNames 刺激相关人物名（当事人/靠近者/行动者；可空=无特定关联人物）
     *
     * @return 【角色个性】段文本（无内容返回空串）
     */
    public String buildPersonalitySegment(Long projectId, String characterRef, Long gameSecond,
                                          List<String> relatedNames) {
        if (!props.isPersonalityInjectEnabled() || projectId == null
                || characterRef == null || characterRef.isBlank()) {
            return "";
        }
        ActorCharacter ch = characterRepository.findById(specialIdOf(characterRef)).orElse(null);
        if (ch == null || ch.getProjectId() == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        // ====================：档案分片注入取代"角色卡整段注入" ====================
        // 变更要点：
        // ① 身份基线 + 性格(persona) + 言语风格 三段过去各自注入一遍角色卡 JSON 的字段，
        // 现在统一由分片渲染器按"决策场景默认组合 S1 S2 S4 S6 S8"产出（身份锚定/人格与价值观/
        // 身体与生理/习惯与日常/社会面具），信息密度取最优：实测决策场景 1016 字符 ≈630 token，
        // 相对旧角色卡整段（3263 token）降约 80%；
        // ② 分片内容来自 11 维档案 + 各模块投影（身份列/状态模块生理/计划模块日程…），
        // 因此"档案"不再是与模块平行的第二份数据；
        // ③ 未装配 PersonaService（单测场景）时回退到身份基线段，保证零阻断。
        String shards = personaService == null ? ""
                : personaService.renderShardsForScene(ch.getId(), "action_gen");
        if (!shards.isBlank()) {
            parts.add(shards);
        } else {
            String identity = identityBits(ch);
            if (!identity.isBlank()) {
                parts.add(identity);
            }
        }
        String core = coreSegment(ch, characterRef);
        if (!core.isBlank()) {
            parts.add(core);
        }
        String brain = brainBits(ch, characterRef);
        if (!brain.isBlank()) {
            parts.add(brain);
        }
        String selfEfficacy = selfEfficacySegment(ch);
        if (!selfEfficacy.isBlank()) {
            parts.add(selfEfficacy);
        }
        String relations = relationSegment(ch, relatedNames);
        if (!relations.isBlank()) {
            parts.add(relations);
        }
        String timeSense = timeSenseSegment(ch, gameSecond);
        if (!timeSense.isBlank()) {
            parts.add(timeSense);
        }
        // 时间季节系统：设计 §5.3 决策情境注入 + §5.4 影响强度——
        // ①【当前环境】段（时间感段升级为「时间+环境」：环境状态描述+体感）；
        // ② 性格参数加权段（v2 档案 ④人格/⑧习惯/②体质 词条 + ③生理基线派生词条 六类映射：
        // 怕冷/喜雨/夜猫子/体弱/怕热/恋家/好动）；
        // ③ 极值高权重段（暴雪暴雨出行降权 / 深夜+疲劳回家倾向 / 极端低温户外降权——系统级约束）。
        String environment = environmentSegment(ch, gameSecond);
        if (!environment.isBlank()) {
            parts.add(environment);
        }
        String personaEnv = personaEnvironmentSegment(ch, gameSecond);
        if (!personaEnv.isBlank()) {
            parts.add(personaEnv);
        }
        String constraint = environmentConstraintSegment(ch, gameSecond);
        if (!constraint.isBlank()) {
            parts.add(constraint);
        }
        if (parts.isEmpty()) {
            return "";
        }
        // ==================== 收口（世界运行测试抓修） ====================
        // 原实现：String.join 后一刀 substring(0, max)（max 默认 600）。
        // 问题： 之后本段**首段就是 11 维档案分片块**（决策场景 S1 S2 S4 S6 S8，实测
        // 1500-2500 字符，远超 600），于是整段被切在分片中间——排在后面的段落
        // （【核心记忆】/脑力倾向/【关系摘要】/【时间感】/【当前环境】/【环境偏好】/【环境约束】）
        // 全部被**静默丢弃**，且没有任何日志。实测证据：interrupt_probe 的"你的人物信息"段
        // 在 S4「身体与生理」中途断句（"能凭气味" 后即止）， 时间季节系统的
        // 性格加权（夜猫子/怕冷…）与极值约束（暴雪降权/深夜疲劳回家）从未进入过 Prompt。
        // 现改为：**按段边界**装配，放不下的整段省略并 WARN 列出段名（不再是无声截断）。
        int max = Math.max(100, props.getPersonalityMaxLength());
        StringBuilder sb = new StringBuilder();
        List<String> dropped = new ArrayList<>();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (sb.length() == 0 || sb.length() + 1 + part.length() <= max) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(part);
            } else {
                dropped.add(sectionLabel(part));
            }
        }
        if (!dropped.isEmpty()) {
            log.warn("[个性注入] 注入预算 {} 字符不足，已整段省略 {} 段: {}（如需全量可调大 "
                            + "holzyn.actor.realtime.personality-max-length；当前档案分片块本身可能已接近预算）",
                    max, dropped.size(), dropped);
        }
        return sb.toString();
    }

    /**
     * 段落标签（取首行前 12 字符，用于预算不足时的告警列举）。
     *
     * @param part 段落文本
     *
     * @return 标签（如 {@code 【环境偏好】}）
     */
    private static String sectionLabel(String part) {
        String firstLine = part.strip().split("\n", 2)[0];
        return firstLine.length() > 12 ? firstLine.substring(0, 12) + "…" : firstLine;
    }

    /**
     * 拼装「个性化刺激情境」（进 STIMULUS 即时决策的 situation → action_gen 模板 {{situation}}）。
     * <p>组成：【角色个性】段 + 【本次刺激】原文 + 【决策要求】——显式要求 AI 按性格/经历/
     * 价值观决定「是否反应/怎么反应/事后是否回去继续」，并提示考虑与相关人物的关系
     * （社交差异化），允许「不理会继续手头事」作为合理反应（真人感）。个性段为空时
     * 回退纯刺激文本。</p>
     *
     * @param personalitySegment 【角色个性】段（可空）
     *
     * @param stimulusText 本次刺激文本（可空）
     *
     * @return 拼装后的情境文本（恒非空）
     */
    public static String composeStimulusSituation(String personalitySegment, String stimulusText) {
        StringBuilder sb = new StringBuilder();
        if (personalitySegment != null && !personalitySegment.isBlank()) {
            sb.append("【角色个性】\n").append(personalitySegment.trim());
        }
        String stimulus = stimulusText == null || stimulusText.isBlank() ? "发生了一起事件" : stimulusText.trim();
        if (sb.length() > 0) {
            sb.append("\n\n");
        }
        sb.append("【本次刺激】\n").append(stimulus);
        sb.append("\n\n【决策要求】面对此刺激，请按你的性格、经历与价值观，做出最像你自己的决定："
                + "是否立即反应、以何种方式反应；若刺激与你认识的人有关，注意你们的关系"
                + "（亲近/信任/疏远/猜忌）会影响你的态度与分寸。你也可以认为这不值得理会，"
                + "选择继续手头的事。事后是否回去把被打断的事做完，由你自己判断。"
                + "【时间感】留意现在的时刻与你的处境：若你在等待某人/某件事/某个时辰且时机未到，"
                + "可以在行动决策中输出 wait_minutes（预计还需等待的游戏分钟数）并在 action 里描述"
                + "「先等一等，稍后去……」——到点后你还会被问到该做什么；能直接行动就不要无谓等待。"
                + "禁止违背你的人设（OOC）。");
        return sb.toString();
    }

    // ==================== 各段组装（私有 + 静态纯逻辑可单测） ====================

    /**
     * 身份基线段：姓名（头衔）+ 身份角色 + 背景。
     */
    private static String identityBits(ActorCharacter ch) {
        StringBuilder sb = new StringBuilder();
        sb.append("我是").append(ch.getName() == null || ch.getName().isBlank() ? "？" : ch.getName().trim());
        if (ch.getTitle() != null && !ch.getTitle().isBlank()) {
            sb.append("（").append(ch.getTitle().trim()).append("）");
        }
        if (ch.getCurrentActivity() != null && !ch.getCurrentActivity().isBlank()) {
            sb.append("；此刻正在：").append(ch.getCurrentActivity().trim());
        }
        return sb.toString();
    }

    private String coreSegment(ActorCharacter ch, String characterRef) {
        if (coreMemoryService == null) {
            return "";
        }
        try {
            String core = coreMemoryService.renderCoreSegment(ch.getProjectId(), characterRef);
            if (core == null || core.isBlank()) {
                return "";
            }
            // 2026-10-10 决策：不设字数硬边界，核心记忆全量注入
            return "核心信念/经历：" + core;
        } catch (Exception e) {
            log.debug("[个性注入] L3 核心段注入失败（跳过）: ref={} : {}", characterRef, e.getMessage());
            return "";
        }
    }

    /**
     * 脑力/行为倾向段（brainProfile）：脑力档 + 决策频率 + 思考/规划开关 → 角色「想事的深度」倾向。
     */
    private String brainBits(ActorCharacter ch, String characterRef) {
        if (brainProfileService == null) {
            return "";
        }
        try {
            ActorAgentProfile profile = brainProfileService.resolveByRef(characterRef);
            if (profile == null) {
                return "";
            }
            List<String> bits = new ArrayList<>();
            if (profile.getBrainLevel() != null && !profile.getBrainLevel().isBlank()) {
                bits.add("脑力档=" + profile.getBrainLevel().trim());
            }
            if (profile.getDecisionFrequency() != null && !profile.getDecisionFrequency().isBlank()) {
                bits.add("决策频率=" + profile.getDecisionFrequency().trim());
            }
            if (Integer.valueOf(1).equals(profile.getThinkingEnabled())) {
                bits.add("多思");
            }
            if (Integer.valueOf(1).equals(profile.getPlanningEnabled())) {
                bits.add("好做计划");
            }
            return bits.isEmpty() ? "" : "行事倾向：" + String.join("、", bits);
        } catch (Exception e) {
            log.debug("[个性注入] 脑力段注入失败（跳过）: ref={} : {}", characterRef, e.getMessage());
            return "";
        }
    }

    /**
     * 能力自评段（P1 ①-2，能力自评卡）：从 persona ⑥ ability.self_efficacy/self_comment
     * 渲染【能力自评】小节——角色「自己怎么看自己各领域本事」（1-5 主观评估，允许与客观
     * skills 有偏差=自信/谦逊），进决策让 AI 的决策/言行带上「自我认知」这一人类决策核心成分。
     * 数据缺失（无自评/档案未装配/解析失败）→ 空串跳过；不参与档案分片渲染（决策场景默认
     * 组合不含 S5），故单独注入。
     */
    private String selfEfficacySegment(ActorCharacter ch) {
        if (personaService == null) {
            return "";
        }
        try {
            String json = personaService.latestPersonaJson(ch.getId()).orElse(null);
            if (json == null || json.isBlank()) {
                return "";
            }
            PersonaProfile profile = PersonaJson.readQuietly(json);
            if (profile == null || profile.getDims() == null || profile.getDims().getAbility() == null) {
                return "";
            }
            PersonaDims.Ability ab = profile.getDims().getAbility();
            Map<String, Integer> se = ab.getSelfEfficacy();
            String comment = ab.getSelfComment();
            if ((se == null || se.isEmpty()) && (comment == null || comment.isBlank())) {
                return "";
            }
            StringBuilder sb = new StringBuilder("【能力自评】");
            if (se != null && !se.isEmpty()) {
                List<String> bits = new ArrayList<>();
                se.forEach((k, v) -> bits.add(k + "=" + v + "/5"));
                sb.append(String.join("、", bits));
            }
            if (comment != null && !comment.isBlank()) {
                sb.append("；整体：").append(comment.trim());
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("[个性注入] 能力自评段注入失败（跳过）: ref={} : {}", ch.getId(), e.getMessage());
            return "";
        }
    }

    /**
     * 关系摘要段：
     * 先注入与刺激相关人物（relatedNames）的关系（≤3 条），再注入其余关系（≤5 条兜底）；
     * 每条含关系类型/好感/信任/关系状态。
     */
    private String relationSegment(ActorCharacter ch, List<String> relatedNames) {
        List<ActorCharacterRelation> all = loadRelations(ch);
        if (all.isEmpty()) {
            return "";
        }
        Long id = ch.getId();
        String ownName = ch.getName();
        Set<String> seen = new LinkedHashSet<>();
        List<String> lines = new ArrayList<>();
        // ① 相关人物优先（relatedNames 归一化去重后按序匹配）
        List<String> related = normalizeNames(relatedNames);
        if (!related.isEmpty()) {
            for (String name : related) {
                for (ActorCharacterRelation r : all) {
                    String other = otherName(r, id, ownName);
                    if (other != null && other.equals(name) && seen.add(other)) {
                        lines.add(relationLine(r, other));
                        if (lines.size() >= MAX_RELATION_RELATED) {
                            return joinRelations(lines);
                        }
                    }
                }
            }
        }
        // ② 全量兜底（其余关系 ≤MAX_RELATION_TOTAL）
        for (ActorCharacterRelation r : all) {
            String other = otherName(r, id, ownName);
            if (other == null || !seen.add(other)) {
                continue;
            }
            lines.add(relationLine(r, other));
            if (lines.size() >= MAX_RELATION_TOTAL) {
                break;
            }
        }
        return joinRelations(lines);
    }

    /**
     * 关系列表加载（失败返回空列表，降级跳过关系段）。
     */
    private List<ActorCharacterRelation> loadRelations(ActorCharacter ch) {
        try {
            List<ActorCharacterRelation> all = relationRepository.findByProjectId(ch.getProjectId());
            List<ActorCharacterRelation> mine = new ArrayList<>();
            Long id = ch.getId();
            for (ActorCharacterRelation r : all) {
                if (r == null) {
                    continue;
                }
                boolean involved = (r.getFromCharacterId() != null && id.equals(r.getFromCharacterId()))
                        || (r.getToCharacterId() != null && id.equals(r.getToCharacterId()));
                if (involved) {
                    mine.add(r);
                }
            }
            return mine;
        } catch (Exception e) {
            log.debug("[个性注入] 关系加载失败（跳过关系段）: ref={} : {}", ch.getId(), e.getMessage());
            return List.of();
        }
    }

    /**
     * 关系行文本（另一方 + 关系类型 + 好感/信任/状态 + 描述）。
     */
    static String relationLine(ActorCharacterRelation r, String other) {
        StringBuilder sb = new StringBuilder("与").append(other == null || other.isBlank() ? "对方" : other.trim())
                .append("：");
        sb.append(r.getRelationType() == null || r.getRelationType().isBlank() ? "相识" : r.getRelationType().trim());
        if (r.getAffinity() != null || r.getTrust() != null || r.getRelationState() != null) {
            sb.append("【好感").append(r.getAffinity() == null ? 0 : r.getAffinity())
                    .append("/信任").append(r.getTrust() == null ? 0 : r.getTrust())
                    .append("/").append(stateLabel(r.getRelationState())).append("】");
        }
        if (r.getDescription() != null && !r.getDescription().isBlank()) {
            sb.append("（").append(abbreviate(r.getDescription().trim(), 40)).append("）");
        }
        return abbreviate(sb.toString(), RELATION_LINE_MAX);
    }

    /**
     * 关系状态中文标签（null/未知回退「未知」）。
     */
    static String stateLabel(String state) {
        if (state == null || state.isBlank()) {
            return "未知";
        }
        return switch (state.trim().toLowerCase()) {
            case "trust" -> "信任";
            case "distrust" -> "猜忌";
            case "neutral" -> "中立";
            case "friendly" -> "友好";
            case "hostile" -> "敌对";
            case "estranged" -> "疏远";
            default -> state.trim();
        };
    }

    /**
     * 关系段拼接（前缀【人际关系】）。
     */
    private static String joinRelations(List<String> lines) {
        return lines.isEmpty() ? "" : "【人际关系】" + String.join("；", lines);
    }

    /**
     * 时间感段（主方案 §4.4）：当前游戏时刻 + 当前行动 + 已持续游戏时长。
     */
    private String timeSenseSegment(ActorCharacter ch, Long gameSecond) {
        List<String> bits = new ArrayList<>();
        // 当前游戏时刻（世界历文本；失败跳过）
        if (worldClockService != null) {
            try {
                Long gh = gameSecond != null ? gameSecond / 3600L : worldClockService.currentGameHour(ch.getProjectId());
                if (gh != null) {
                    String t = worldClockService.gameTimeTextOf(ch.getProjectId(), gh);
                    if (t != null && !t.isBlank()) {
                        bits.add("现在是" + t.trim());
                    }
                }
            } catch (Exception e) {
                log.debug("[个性注入] 时间感-时刻注入失败（跳过）: ref={} : {}", ch.getId(), e.getMessage());
            }
        }
        // 已持续时长（沙盘行 game_second 作为当前行动开始时刻；取不到跳过）
        if (gameSecond != null && sandboxStateRepository != null) {
            try {
                ActorSandboxState st = sandboxStateRepository
                        .findByProjectIdAndNpcRef(ch.getProjectId(), com.holzyn.actor.common.NpcRefs.of(ch.getId())).orElse(null);
                if (st != null && st.getGameSecond() != null && st.getGameSecond() > 0) {
                    long minutes = Math.max(0, (gameSecond - st.getGameSecond()) / 60L);
                    if (minutes > DURATION_DISPLAY_MAX) {
                        bits.add("当前这件事已持续很久");
                    } else {
                        bits.add("当前这件事已持续约 " + minutes + " 游戏分钟");
                    }
                }
            } catch (Exception e) {
                log.debug("[个性注入] 时间感-时长注入失败（跳过）: ref={} : {}", ch.getId(), e.getMessage());
            }
        }
        return bits.isEmpty() ? "" : "【时间感】" + String.join("；", bits);
    }

    // ==================== 时间季节系统：环境段/性格加权/极值约束 ====================

    /**
     * 【当前环境】段（设计 §5.3 决策情境注入）：季节日时段 + 环境状态描述 + 体感气温
     * （统一环境服务确定性计算 + 润色缓存读取，零同步 AI）；开关 inject-decision 关/
     * 服务未装配/异常返回空串（静默降级）。
     */
    private String environmentSegment(ActorCharacter ch, Long gameSecond) {
        if (environmentContextService == null || environmentProperties != null && !environmentProperties.isInjectDecision()) {
            return "";
        }
        try {
            return environmentContextService.injectionSegment(ch.getProjectId(), gameSecond, 140);
        } catch (Exception e) {
            log.debug("[个性注入] 环境段注入失败（跳过）: ref={} : {}", ch.getId(), e.getMessage());
            return "";
        }
    }

    /**
     * 性格参数加权段（设计 §5.4，六类映射——用户 决策：怕冷/喜雨/夜猫子/体弱 +
     * 怕热/恋家/好动）：匹配 v2 档案的 ④人格/⑧习惯/②体质 词条 + ③ 生理基线派生词条 →
     * 按当前季节/天气/时段生成个性化环境提示段（软倾向显式化、量化，未命中则纯 AI 自然决策）。
     * 纯静态逻辑可单测。
     */
    private String personaEnvironmentSegment(ActorCharacter ch, Long gameSecond) {
        if (environmentContextService == null || environmentProperties != null && !environmentProperties.isInjectDecision()) {
            return "";
        }
        try {
            EnvironmentContextVO ctx = environmentContextService.computeContext(ch.getProjectId(), gameSecond);
            JsonNode persona = personaJson(ch, objectMapper);
            // ③ 生理基线（怕冷/怕热的量化基线、夜猫子作息相位）权威在状态模块，不在档案里——
            // 经 NpcStateService 取生效系数后转成同一批词条，与档案词条一起参与六类加权。
            List<String> hints = personaEnvironmentHints(persona, ctx, physioEnvTags(ch));
            return hints.isEmpty() ? "" : "【环境偏好】" + String.join("；", hints);
        } catch (Exception e) {
            log.debug("[个性注入] 性格加权段注入失败（跳过）: ref={} : {}", ch.getId(), e.getMessage());
            return "";
        }
    }

    /**
     * 读取该角色生效的 ③ 生理系数并派生加权词条（零阻断：任何异常都退化为"无生理词条"）。
     * <p>数据来源是<b>状态模块</b>而非档案：③ 生理基线的权威落在 {@code actor_state.physio_json}
     * 与生效系数 {@code physio_coef_json}（用户 决策 13——使用、消费其他模块的数据，
     * 而不是在档案里另立一份）。</p>
     *
     * @param ch 角色主表实体
     *
     * @return 生理派生词条（未装配状态服务/取数失败返回空列表）
     */
    private List<String> physioEnvTags(ActorCharacter ch) {
        if (npcStateService == null || ch == null || ch.getId() == null || ch.getProjectId() == null) {
            return List.of();
        }
        try {
            return physioEnvTags(npcStateService.physioProfileOf(
                    ch.getProjectId(), com.holzyn.actor.common.NpcRefs.of(ch.getId())));
        } catch (Exception e) {
            log.debug("[个性注入] 生理基线读取失败（跳过生理词条）: ref={} : {}", ch.getId(), e.getMessage());
            return List.of();
        }
    }

    /**
     * 极值高权重约束段（设计 §5.4：软倾向为主 + 极值高权重——系统级约束，非 AI 可选项）：
     * 暴雪/暴雨出行降权（体感 ≤ storm-travel-feelslike 且雨/雪）、深夜+疲劳强制回家倾向
     * （hour ≥ deep-night-hour 且 energy < deep-night-fatigue-energy）、极端低温户外降权
     * （体感 ≤ extreme-cold-c）。纯静态逻辑可单测（energy 由状态服务读取，失败跳过疲劳判定）。
     */
    private String environmentConstraintSegment(ActorCharacter ch, Long gameSecond) {
        if (environmentContextService == null || environmentProperties == null) {
            return "";
        }
        try {
            EnvironmentContextVO ctx = environmentContextService.computeContext(ch.getProjectId(), gameSecond);
            Integer energy = null;
            if (npcStateService != null) {
                try {
                    var st = npcStateService.stateFor(ch.getProjectId(), com.holzyn.actor.common.NpcRefs.of(ch.getId()));
                    if (st != null && st.getEnergy() != null) {
                        energy = st.getEnergy();
                    }
                } catch (Exception e) {
                    log.debug("[个性注入] 疲劳判定读取失败（跳过疲劳约束）: ref={} : {}", ch.getId(), e.getMessage());
                }
            }
            List<String> hints = environmentConstraints(ctx, energy, environmentProperties);
            return hints.isEmpty() ? "" : "【环境约束】" + String.join("；", hints);
        } catch (Exception e) {
            log.debug("[个性注入] 极值约束段注入失败（跳过）: ref={} : {}", ch.getId(), e.getMessage());
            return "";
        }
    }

    // ==================== 静态纯逻辑（可单测） ====================

    /**
     * 性格→环境影响关键词表（六类，用户 决策；匹配 v2 档案的 ④人格/⑧习惯/②体质 词条
     * + ③生理基线派生词条——见 {@link #personaTags} / {@link #physioEnvTags}）
     */
    static final Map<String, List<String>> ENV_PERSONA_KEYWORDS = Map.of(
            "cold_fearing", List.of("怕冷", "畏寒", "惧寒", "不耐寒", "喜暖", "怕冻", "畏冷"),
            "rain_loving", List.of("喜雨", "爱雨", "喜欢下雨", "盼雨", "赏雨"),
            "night_owl", List.of("夜猫子", "熬夜", "晚睡", "夜游", "越夜越精神"),
            "frail", List.of("体弱", "体虚", "身子弱", "多病", "弱不禁风"),
            "heat_fearing", List.of("怕热", "畏暑", "不耐热", "怕暑", "惧暑"),
            "home_loving", List.of("恋家", "不爱出门", "守家", "喜静", "恋窝"),
            "active", List.of("好动", "闲不住", "爱逛", "爱走动", "坐不住", "喜动"));

    /**
     * 性格加权提示组装（纯静态，两参重载：只用档案词条）。
     *
     * @param persona v2 档案 JSON（可空）
     *
     * @param ctx 环境上下文（可空）
     *
     * @return 提示列表（无命中/无环境返回空列表）
     */
    static List<String> personaEnvironmentHints(JsonNode persona, EnvironmentContextVO ctx) {
        return personaEnvironmentHints(persona, ctx, List.of());
    }

    /**
     * 性格加权提示组装（纯静态）：匹配 档案词条 + 生理派生词条 → 按环境生成个性化提示。
     *
     * @param persona v2 档案 JSON（可空）
     *
     * @param ctx 环境上下文（可空）
     *
     * @param physioTags ③ 生理基线派生词条（可空；由状态模块取数，见 {@link #physioEnvTags}）
     *
     * @return 提示列表（无命中/无环境返回空列表）
     */
    static List<String> personaEnvironmentHints(JsonNode persona, EnvironmentContextVO ctx,
                                                List<String> physioTags) {
        List<String> out = new ArrayList<>();
        if (ctx == null) {
            return out;
        }
        List<String> tags = personaTags(persona);
        if (physioTags != null) {
            tags.addAll(physioTags);
        }
        if (tags.isEmpty()) {
            return out;
        }
        Set<String> matched = new LinkedHashSet<>();
        ENV_PERSONA_KEYWORDS.forEach((key, keywords) -> {
            for (String t : tags) {
                for (String k : keywords) {
                    if (t.contains(k)) {
                        matched.add(key);
                        break;
                    }
                }
                if (matched.contains(key)) {
                    break;
                }
            }
        });
        String season = ctx.getSeason();
        String condition = ctx.getWeather() == null ? "" : ctx.getWeather();
        String phase = ctx.getPhase();
        Double feels = ctx.getFeelsLikeC();
        boolean winter = "winter".equals(season);
        boolean summer = "summer".equals(season);
        boolean raining = condition.contains("雨");
        boolean snowing = condition.contains("雪");
        boolean night = "night".equals(phase);
        boolean day = "day".equals(phase);
        boolean sunny = "晴".equals(condition) || condition.isBlank();
        if (matched.contains("cold_fearing") && (winter || (feels != null && feels < 5))) {
            out.add("你素来怕冷，这样的天气你更愿意待在温暖之处，减少外出");
        }
        if (matched.contains("heat_fearing") && (summer || (feels != null && feels >= 28))) {
            out.add("你畏暑，天热时你更愿意躲在阴凉处，避开日头");
        }
        if (matched.contains("rain_loving") && raining) {
            out.add("你一向喜欢下雨天，这样的雨让你心情舒畅，甚至会想出门走走");
        }
        if (matched.contains("night_owl") && night) {
            out.add("你是夜猫子，夜深了反而精神，不急着歇息");
        }
        if (matched.contains("frail") && ((feels != null && (feels < -10 || feels > 32)) || snowing || "雷雨".equals(condition))) {
            out.add("你身子弱，这样的天气对你影响加倍，务必保重");
        }
        if (matched.contains("home_loving") && (snowing || raining || night)) {
            out.add("你恋家，天气不好时你更想待在家里");
        }
        if (matched.contains("active") && day && sunny) {
            out.add("你闲不住，这样的好天气正想出门走动走动");
        }
        return out;
    }

    /**
     * v2 档案的性格/体质/习惯词条集合（去空白；无内容返回空列表）。
     * <p>v2 档案把"怕冷/喜雨/夜猫子"这类词条分散在三个维度，取数路径必须对齐——</p>
     * <ul>
     * <li>④人格 特质摘要 → {@code dims.personality.traits_summary}（自由文本，如「稳重、隐忍、务实、多疑」）；</li>
     * <li>⑧习惯 癖好 → {@code dims.habits.quirks}（数组）；</li>
     * <li>②外貌体质 体质弱点 → {@code dims.appearance.constitution_weaknesses}（数组，
     * 档案契约里明确写着"怕冷/忌久站/易醉…"——正是本段要匹配的形态）。</li>
     * </ul>
     * <p>旧的 {@code personality.traits/quirks} 是 v1 角色卡口径，在 v2 档案下恒为空，
     * 会让性格加权环境段**静默失效**；现统一经
     * {@link com.holzyn.actor.domain.growth.persona.PersonaBits} 按 v2 路径取
     * （自动兼容文本/数组两种形态）。</p>
     * <p>注意：③ 生理基线（怕冷/怕热的量化基线、夜猫子作息相位）<b>不在档案里</b>——
     * 该维权威在状态模块（{@code actor_state.physio_json}），由
     * {@link #physioEnvTags(com.holzyn.actor.domain.npc.state.service.PhysioProfile)} 单独取数后合并。</p>
     *
     * @param persona v2 档案 JSON 根（可为 null）
     *
     * @return 词条列表
     */
    static List<String> personaTags(JsonNode persona) {
        List<String> tags = new ArrayList<>();
        if (persona == null) {
            return tags;
        }
        tags.addAll(com.holzyn.actor.domain.growth.persona.PersonaBits.texts(
                persona, "dims", "personality", "traits_summary"));
        tags.addAll(com.holzyn.actor.domain.growth.persona.PersonaBits.texts(
                persona, "dims", "habits", "quirks"));
        tags.addAll(com.holzyn.actor.domain.growth.persona.PersonaBits.texts(
                persona, "dims", "appearance", "constitution_weaknesses"));
        return tags;
    }

    /**
     * ③ 生理基线派生的加权词条（纯静态，可单测）。
     * <p>为什么这里要"造词条"而不是直接产出提示文案：六类加权的<b>触发条件</b>是环境相关的
     * （怕冷只在冬天/低温、夜猫子只在夜里生效……），让生理信号以同一批词条进入同一张关键词表，
     * 就不会出现"人格说怕冷、生理也怕冷"两套判定逻辑各写一遍、日后各自漂移的问题。</p>
     * <p>阈值口径（对齐 {@link com.holzyn.actor.domain.npc.state.service.PhysioProfile} 的换算）：
     * {@code coldSens/heatSens = 1 − (耐寒/耐热 − 5) / 10}，故 {@code ≥1.2} 等价于
     * 「0-10 刻度上耐寒（耐热）≤ 3」——即明显偏向怕冷（怕热）。作息相位直接取
     *
     * @param p 生效生理系数（可空=无词条）
     *
     * @return 词条列表（无命中返回空列表）
     */
    static List<String> physioEnvTags(com.holzyn.actor.domain.npc.state.service.PhysioProfile p) {
        List<String> tags = new ArrayList<>();
        if (p == null) {
            return tags;
        }
        if ("night_owl".equalsIgnoreCase(p.chronotype())) {
            tags.add("夜猫子");
        }
        if (p.coldSens() >= 1.2) {
            tags.add("怕冷");
        }
        if (p.heatSens() >= 1.2) {
            tags.add("怕热");
        }
        return tags;
    }

    /**
     * 极值高权重约束文本组装（纯静态）：暴雪/暴雨出行降权、深夜+疲劳回家倾向、极端低温户外降权。
     *
     * @param ctx 环境上下文（可空）
     *
     * @param energy 当前能量值（null=跳过疲劳判定）
     *
     * @param props 环境配置（极值阈值）
     *
     * @return 约束文本列表（无极值返回空列表）
     */
    static List<String> environmentConstraints(EnvironmentContextVO ctx, Integer energy, EnvironmentProperties props) {
        List<String> out = new ArrayList<>();
        if (ctx == null || props == null) {
            return out;
        }
        String condition = ctx.getWeather() == null ? "" : ctx.getWeather();
        Double feels = ctx.getFeelsLikeC();
        boolean storm = (condition.contains("雪") || condition.contains("雨"))
                && feels != null && feels <= props.getStormTravelFeelslike();
        if (storm) {
            out.add("今日天气恶劣（体感 " + Math.round(feels) + "℃），出行不便——若非必要不宜远行，远行计划应降权或改期");
        }
        Integer hour = ctx.getHourOfDay();
        if (hour != null && hour >= props.getDeepNightHour() && energy != null && energy < props.getDeepNightFatigueEnergy()) {
            out.add("夜已深（" + hour + " 点后）且你已疲惫——你更倾向收工回家休息，而不是继续在外逗留");
        }
        if (feels != null && feels <= props.getExtremeColdC()) {
            out.add("天气寒冷刺骨（体感 " + Math.round(feels) + "℃）——户外活动应大幅降权，你更倾向待在室内");
        }
        return out;
    }

    // ==================== 静态纯逻辑辅助（可单测） ====================

    /**
     * 读取该角色的 11 维档案 JSON（：来源由 {@code actor_profile_card.persona_json} 改为
     * {@code actor_profile.persona_json}——角色卡表已废弃；解析失败返回 null）。
     * <p>用途：性格加权环境段（{@link #personaEnvironmentSegment}）按关键词表匹配词条
     * （怕冷/喜雨/夜猫子/体弱/怕热/恋家/好动）。词条取自 v2 档案的三个维度
     * （④人格 {@code traits_summary}、⑧习惯 {@code quirks}、②体质 {@code constitution_weaknesses}）——
     * 不论 NPC 把"怕冷"写进体质弱点还是人格特质都能命中；③ 生理基线不在此处，
     * 由状态模块按生效系数单独供词条（见 {@link #physioEnvTags}）。</p>
     *
     * @param ch 角色主表实体（其 personaJson 为 v2 11 维档案）
     *
     * @return 档案 JSON 树；无档案或不可解析返回 null
     */
    private JsonNode personaJson(ActorCharacter ch, ObjectMapper mapper) {
        if (ch == null || ch.getId() == null || mapper == null) {
            return null;
        }
        try {
            String persona = ch.getPersonaJson();
            if (persona == null || persona.isBlank()) {
                return null;
            }
            return mapper.readTree(persona);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 数组字段到片段列表（≤max 条，空白跳过）。
     */
    static void appendBits(List<String> bits, JsonNode node, int max) {
        if (node != null && node.isArray()) {
            node.forEach(n -> {
                String v = n == null ? "" : n.asText("");
                if (!v.isBlank() && bits.size() < max) {
                    bits.add(abbreviate(v.trim(), 80));
                }
            });
        }
    }

    /**
     * 关系的「另一方」名称（按 id 匹配，回退名称匹配）。
     */
    static String otherName(ActorCharacterRelation r, Long id, String ownName) {
        if (r == null) {
            return null;
        }
        if (id != null) {
            if (id.equals(r.getFromCharacterId())) {
                return r.getToName() == null || r.getToName().isBlank() ? null : r.getToName().trim();
            }
            if (id.equals(r.getToCharacterId())) {
                return r.getFromName() == null || r.getFromName().isBlank() ? null : r.getFromName().trim();
            }
            return null;
        }
        if (ownName != null && ownName.equals(r.getFromName())) {
            return r.getToName() == null || r.getToName().isBlank() ? null : r.getToName().trim();
        }
        if (ownName != null && ownName.equals(r.getToName())) {
            return r.getFromName() == null || r.getFromName().isBlank() ? null : r.getFromName().trim();
        }
        return null;
    }

    /**
     * 相关人物名归一化（去空白/去重，保留顺序）。
     */
    static List<String> normalizeNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String n : names) {
            if (n == null || n.isBlank()) {
                continue;
            }
            String t = n.trim();
            if (seen.add(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 从统一角色引用解析角色主表 ID（：统一 {@code npc:{id}} 单前缀；
     * 兼容解析历史 {@code special:} 前缀，见 {@link com.holzyn.actor.common.NpcRefs#parseLenient}）。
     *
     * @param characterRef 角色引用（如 {@code npc:12}）
     *
     * @return actor_profile.id；无法解析返回 null
     */
    static Long specialIdOf(String characterRef) {
        return com.holzyn.actor.common.NpcRefs.parseLenient(characterRef);
    }

    /**
     * 文本摘要（超长截断；null 回退空串）。
     */
    static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max) + "…";
    }
}
