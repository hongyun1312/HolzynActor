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

package com.holzyn.actor.domain.agent.context;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.growth.persona.PersonaService;
import com.holzyn.actor.domain.memory.service.CoreMemoryService;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationMemberRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorMessageRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.service.SeasonService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 统一 AI 上下文注入器（角色视角 AI 调用 · 上下文富化）。
 * <p><b>定位</b>：所有「涉及具体角色视角」的 AI 调用（对话/行动/决策/记忆/感知/关系/情绪/
 * 空间触发/演化/反思/NanoJev 角色判定）在发送前，统一注入两类上下文：</p>
 * <ul>
 * <li><b>世界观简报</b>（恒定注入，默认 300-500 字）：复用 {@code actor_world_setting}
 * 拼装——名称/题材/时代/地理/势力/规则/文化/历史/自由文本，供 AI 准确理解世界，杜绝 OOC；</li>
 * <li><b>角色核心卡</b>（涉及该角色必注入，默认 200-400 字）：姓名/身份/性格/说话风格/
 * 背景/目标/当前情绪状态——复用 {@code actor_profile}（结构列 + persona_json 11 维）+
 * {@code actor_memory_core}（L3 核心块）+ {@code actor_character_relation}（关键关系）+ 
 * {@code actor_state}（当前情绪），让 AI 以「这个人」而非「一个名字」为基底判断与输出。</li>
 * </ul>
 * <p><b>负担控制（分层注入）</b>：注入器按「角色视角调用涉及的角色」组装核心卡（必选，
 * 涉及即注入），世界观简报恒定；不注入记忆切片（L1/L2 检索切片由各调用方按需注入）。
 * 核心卡/简报均有字符预算上限，防上下文超限。轻量判定（NanoJev boolean/score/choice）
 * 与重量生成（对话/决策）共用本注入器，只是调用方按场景决定是否追加动态切片。</p>
 * <p><b>设计依据</b>：Smallville 生成式智能体「记忆流 + 检索切片注入」、上下文工程
 * 「稳定前缀 + 动态切片」（角色卡/世界观 = 稳定前缀，可命中 prompt caching）；
 * 角色扮演模型 y=f(P,T,x)——persona 描述符 P 必须含背景/信念/目标/情感状态/知识边界
 * （EmergentMind Role-Playing in LLMs）。</p>
 * <p><b>架构不变量</b>：本服务纯文本组装、零 AI 调用、无 {@code @Transactional}；
 * 依赖全部 {@code @Autowired(required=false)} 字段注入（缺失自动跳过该段，零阻断）；
 * 不新增门面（内部组装服务）。</p>
 */
@Slf4j
@Service
public class AiContextInjector {

    /** 世界观简报字符预算（默认 400；配置 {@code holzyn.actor.context.world-brief-chars}） */
    private final int worldBriefChars;

    /** 角色核心卡字符预算（默认 300；配置 {@code holzyn.actor.context.character-card-chars}） */
    private final int characterCardChars;

    /** 关键关系条数（默认 3；配置 {@code holzyn.actor.context.relation-lines}） */
    private final int relationLines;

    /** 角色主表仓库（读结构列 + persona_json；null=跳过角色卡段） */
    @Autowired(required = false)
    private ActorCharacterRepository characterRepository;

    /** 世界观设定仓库（读最新版本；null=跳过世界观简报段） */
    @Autowired(required = false)
    private ActorWorldSettingRepository worldSettingRepository;

    /** 档案服务（11 维档案分片渲染；null=跳过档案段）
     *  ⚠️ @Lazy：AiContextInjector 被 12 类角色视角调用方注入，且自身又依赖
     *  PersonaService/NpcStateService（二者经 NpcStateService→AiSpringChatGateway→
     *  NanoJevGuardSemanticCheck→NanoJevDecisionGate 回到本注入器）——不加 @Lazy
     *  会在启动期形成 Bean 循环依赖（第 35 次轮纯单测未启动真实世界未暴露，
     *  长跑清库重建首启实测抓到）；本类纯文本组装、服务依赖仅运行时按需调用。 */
    @Lazy
    @Autowired(required = false)
    private PersonaService personaService;

    /** L3 核心记忆服务（核心块渲染；null=跳过核心段） */
    @Lazy
    @Autowired(required = false)
    private CoreMemoryService coreMemoryService;

    /** 关系仓库（关键关系摘要；null=跳过关系段） */
    @Autowired(required = false)
    private ActorCharacterRelationRepository relationRepository;

    /** NPC 状态服务（当前情绪/需求；null=跳过状态段）
     *  ⚠️ @Lazy：NpcStateService 自身也注入本注入器（互相引用），必须懒注入断环。 */
    @Lazy
    @Autowired(required = false)
    private NpcStateService npcStateService;

    /** 世界时钟（当前游戏时刻；null=跳过时刻段） */
    @Lazy
    @Autowired(required = false)
    private WorldClockService worldClockService;

    /** 环境上下文服务（段 2D 天气来源；null=跳过天气位）
     *  ⚠️ @Lazy：EnvironmentContextService → AiSpringChatGateway → NanoJevGuardSemanticCheck →
     *  NanoJevDecisionGate → 本注入器 环形依赖，懒注入断环（同 personaService 先例）。 */
    @Lazy
    @Autowired(required = false)
    private com.holzyn.actor.domain.world.service.EnvironmentContextService environmentContextService;

    /** 会话成员仓库（few-shot 抽取：该角色参与的会话；null=跳过 few-shot 段） */
    @Autowired(required = false)
    private ActorConversationMemberRepository conversationMemberRepository;

    /** 会话主表仓库（few-shot 抽取：会话归属项目判定；null=跳过 few-shot 段） */
    @Autowired(required = false)
    private ActorConversationRepository conversationRepository;

    /** 会话消息仓库（few-shot 抽取：角色真实发言/动作/情绪；null=跳过 few-shot 段） */
    @Autowired(required = false)
    private ActorMessageRepository messageRepository;

    // ==================== V2.0 判定输入固定段文本（段 1B / 段 3I / 段 3J） ====================

    /**
     * 段 1B · 系统指令（NanoJev 判定引擎专用，调用类型专属；跨调用稳定=缓存友好）。
     * <p>V2.0 规范化结构 §3.2：1B 为「系统指令 / 任务指令」，开头含角色锚定句。
     * NanoJev 是 0.6B 并行决策模型（System One 判定层）——指令刻意收敛为
     * 「结构化情境 + 明确判据」：只依据情境事实与问题判据作答，不臆测。</p>
     */
    public static final String SEG_1B_NANOJEV_INSTRUCTION =
            "你是一个快速判断引擎。请阅读下方情境，只依据情境中给出的事实与各问题的判据作答，"
                    + "不要臆测情境之外的信息。";

    /**
     * 段 3I · 行为护栏（Post-History 高权重指令位，放在判定输入串末尾）。
     * <p>V2.0 规范化结构 §3.4：历史后的指令约束力显著强于历史前（SillyTavern jailbreak 同构）。</p>
     */
    public static final String SEG_3I_BEHAVIOR_GUARD =
            "只依据情境中明确给出的事实判断，不臆测、不脑补情境之外的关系、事件与身份；"
                    + "不要替他人作答。";

    /**
     * 段 3J · 输出契约（Post-History 高权重指令位）。
     * <p>NanoJev 输出概率分布（零解码）——契约即「按问题判据作答」；
     * 各问题的 instructions/criteria 才是真正的输出 Schema（模板自带）。</p>
     */
    public static final String SEG_3J_OUTPUT_CONTRACT =
            "严格按每个问题的判据作答：boolean 给是否概率、choice 给候选概率、score 给档位概率；"
                    + "不输出任何其他内容。";

    public AiContextInjector(
            @Value("${holzyn.actor.context.world-brief-chars:400}") int worldBriefChars,
            @Value("${holzyn.actor.context.character-card-chars:300}") int characterCardChars,
            @Value("${holzyn.actor.context.relation-lines:3}") int relationLines) {
        this.worldBriefChars = Math.max(100, worldBriefChars);
        this.characterCardChars = Math.max(100, characterCardChars);
        this.relationLines = Math.max(1, relationLines);
    }

    // ==================== 世界观简报（恒定注入） ====================

    /**
     * 世界观简报（恒定注入段，V2.0 段 1A 稳定世界观）：优先返回
     * {@code actor_world_setting.common_knowledge}——由
     * {@code WorldCommonKnowledgeService} 依最新世界观 AI 生成的 <b>300-500 字精炼
     * 「世界常识」</b>（该世界角色都知道的常识，完整返回、不截断）；常识缺失/未生成时
     * 回退结构化字段拼装（名称/题材/时代/地理/势力/规则/文化/历史/自由文本，截断到
     * {@code worldBriefChars} 预算）。<p>⚠️ 2026-10-08 外网冒烟发现旧实现直接硬截断
     * 原文（停在「农业工具改」句中）导致世界观残缺——常识摘要为根治方案。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 简报文本（无世界观/读取失败返回空串）
     */
    public String worldBrief(Long projectId) {
        if (projectId == null || worldSettingRepository == null) {
            return "";
        }
        try {
            Optional<ActorWorldSetting> opt = worldSettingRepository
                    .findTopByProjectIdOrderByVersionDesc(projectId);
            if (opt.isEmpty()) {
                return "";
            }
            ActorWorldSetting w = opt.get();
            // 首选：AI 生成的 300-500 字精炼世界常识（完整返回，不做句子级截断）
            if (w.getCommonKnowledge() != null && !w.getCommonKnowledge().isBlank()) {
                return w.getCommonKnowledge().trim();
            }
            // 回退：结构化字段拼装（旧数据兼容，仍整体截断到预算）
            return assembleFromSections(w);
        } catch (Exception e) {
            log.debug("[上下文注入] 世界观简报组装失败（跳过）: project={} : {}", projectId, e.getMessage());
            return "";
        }
    }

    /** 结构化字段拼装（无 common_knowledge 时的回退路径）。 */
    private String assembleFromSections(ActorWorldSetting w) {
        StringBuilder sb = new StringBuilder();
        List<String> head = new ArrayList<>();
        if (notBlank(w.getName())) {
            head.add(w.getName());
        }
        if (notBlank(w.getGenre())) {
            head.add(w.getGenre());
        }
        if (notBlank(w.getEra())) {
            head.add("时代：" + w.getEra().trim());
        }
        if (!head.isEmpty()) {
            sb.append("世界：").append(String.join("，", head));
        }
        appendSection(sb, "地理", w.getGeography());
        appendSection(sb, "势力", w.getFactions());
        appendSection(sb, "规则", w.getMagicSystem());
        appendSection(sb, "文化", w.getCulture());
        appendSection(sb, "历史", w.getHistory());
        if (sb.length() == 0) {
            if (notBlank(w.getFreeText())) {
                sb.append(w.getFreeText().trim());
            } else {
                return "";
            }
        } else if (notBlank(w.getFreeText())) {
            // 自由文本兜底补充：结构化字段之外再补自由文本（2026-10-10 决策：不设字数硬边界，全量注入）
            sb.append("\n补充：").append(w.getFreeText().trim());
        }
        return sb.toString().trim();
    }

    // ==================== 角色核心卡（涉及该角色必注入） ====================

    /**
     * 角色核心卡（涉及该角色必注入段，200-400 字预算）：
     * ① 身份基线段（姓名/头衔/性别/种族/职业/归属/所在地/年龄/当前行动）；
     * ② 档案分片段（persona_json 11 维按决策场景渲染：人格与价值观/言语风格/习惯等）；
     * ③ L3 核心记忆段（核心信念/关键经历，core memory 恒定块）；
     * ④ 关键关系摘要（≤ {@code relationLines} 条：关系类型/好感/信任/状态）；
     * ⑤ 当前状态段（主导情绪 + 生理需求缺口）。
     * 逐段降级：服务未装配/数据缺失自动跳过该段；全空返回空串。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用（{@code npc:{id}}）
     *
     * @return 角色核心卡文本（无数据返回空串）
     */
    public String characterCard(Long projectId, String characterRef) {
        if (projectId == null || characterRef == null || characterRef.isBlank()
                || characterRepository == null) {
            return "";
        }
        Long characterId = NpcRefs.idOf(characterRef);
        if (characterId == null) {
            return "";
        }
        try {
            ActorCharacter ch = characterRepository.findById(characterId).orElse(null);
            if (ch == null || ch.getProjectId() == null) {
                return "";
            }
            List<String> parts = new ArrayList<>();
            String identity = identityBits(ch);
            if (!identity.isBlank()) {
                parts.add(identity);
            }
            String shards = personaService == null ? ""
                    : personaService.renderShardsForScene(characterId, "action_gen");
            if (!shards.isBlank()) {
                parts.add(shards);
            }
            String core = coreMemoryService == null ? ""
                    : coreMemoryService.renderCoreSegment(projectId, characterRef);
            if (!core.isBlank()) {
                // 2026-10-10 身份去重：renderCoreSegment 自带【核心记忆】段头，不再重复拼接
                // （原「【核心记忆】」+ core 会产出「【核心记忆】【核心记忆】」双段头）
                parts.add(core);
            }
            String relations = relationSegment(projectId, characterRef, characterId);
            if (!relations.isBlank()) {
                parts.add(relations);
            }
            String state = stateBits(projectId, characterRef);
            if (!state.isBlank()) {
                parts.add(state);
            }
            if (parts.isEmpty()) {
                return "";
            }
            // 全量装配：不设字符预算（2026-10-10 决策：移除字数硬边界，确保角色信息完整进 AI 请求）
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                if (part == null || part.isBlank()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(part);
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.debug("[上下文注入] 角色核心卡组装失败（跳过）: ref={} : {}", characterRef, e.getMessage());
            return "";
        }
    }

    // ==================== 组合注入（NanoJev 判定 / ChatModel 调用共用） ====================

    /**
     * 组装【注入上下文】段：世界观简报（恒定）+ 角色核心卡（涉及该角色必注入）。
     * <p>轻量判定（NanoJev）与重量生成（对话/决策）共用：调用方把本段拼到自己的
     * state/prompt 前即可；记忆切片（L1/L2）由各调用方按需追加（不在此段内）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 涉及的角色引用（{@code npc:{id}}；可空=仅注入世界观）
     *
     * @return 注入上下文段（世界观 + 角色卡；两段都空返回空串）
     */
    public String compose(Long projectId, String characterRef) {
        String brief = worldBrief(projectId);
        String card = characterRef == null || characterRef.isBlank()
                ? "" : characterCard(projectId, characterRef);
        if (brief.isBlank() && card.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (!brief.isBlank()) {
            sb.append("【世界观】\n").append(brief);
        }
        if (!card.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("【我的身份与处境】\n").append(card);
        }
        return sb.toString().trim();
    }

    /**
     * 便捷：把注入上下文段拼到既有 state 文本前（NanoJev 判定用）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 涉及的角色引用（可空=仅世界观）
     *
     * @param stateText 原 state 文本（可空）
     *
     * @return 注入段 + 原文本；注入段为空返回原文本
     */
    public String enrichState(Long projectId, String characterRef, String stateText) {
        String ctx = compose(projectId, characterRef);
        if (ctx.isBlank()) {
            return stateText;
        }
        String base = stateText == null || stateText.isBlank() ? "" : stateText.trim();
        return base.isBlank() ? ctx : ctx + "\n\n" + base;
    }

    // ==================== V2.0 判定输入装配（段 1+2+3 单串） ====================

    /**
     * 段 2D · 当前世界动态（精简行，NanoJev 轻量判定用）。
     * <p>V2.0 规范化结构 §3.3：2D = 游戏时间/季节/天气……「NanoJev 轻量判定可只给精简行」。
     * 组装「{世界历时刻} · {季节} · {天气}」单行（≤80 字符）；无时钟/季节/环境服务
     * 逐位降级，全空返回空串（零阻断）。纯文本确定性组装，零 AI。</p>
     *
     * @param projectId 项目 ID（可空）
     *
     * @return 当前世界动态精简行（空串=无数据/读取失败）
     */
    public String worldDynamicsLine(Long projectId) {
        if (projectId == null || worldClockService == null) {
            return "";
        }
        try {
            Long gameSecond = worldClockService.currentEngineGameSecond(projectId);
            if (gameSecond == null) {
                return "";
            }
            String time = worldClockService.gameTimeTextOf(projectId, Math.floorDiv(gameSecond, 3600L));
            String season = SeasonService.seasonDisplayName(SeasonService.seasonOf(gameSecond));
            StringBuilder sb = new StringBuilder();
            if (time != null && !time.isBlank()) {
                sb.append(time);
            }
            if (season != null && !season.isBlank() && !"未知".equals(season)) {
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append(season).append("季");
            }
            if (environmentContextService != null) {
                try {
                    String weather = environmentContextService.computeContext(projectId, gameSecond).getWeather();
                    if (weather != null && !weather.isBlank()) {
                        sb.append(" · 天气").append(weather);
                    }
                } catch (Exception e) {
                    log.debug("[上下文注入] 世界动态-天气位失败（跳过）: project={} : {}", projectId, e.getMessage());
                }
            }
            return sb.length() == 0 ? "" : sb.toString().trim();
        } catch (Exception e) {
            log.debug("[上下文注入] 世界动态行失败（跳过）: project={} : {}", projectId, e.getMessage());
            return "";
        }
    }

    /**
     * 段 2D · 当前世界动态前缀（段 2D 单段；供 {@link #worldContextPrefix} 内部组装）：
     * 只注入世界历时刻/季节/天气（{@link #worldDynamicsLine} 精简行），不注入角色核心卡。
     * <p>世界视角模板统一注入请用 {@link #worldContextPrefix}（段 1A 世界观 + 段 1C 角色身份
     * [有行动者时] + 段 2D 世界动态，2026-10-10 用户指示「该类 prompt 至少也得注入【世界观】
     * 和角色身份信息」）；本方法为段 2D 单段，未装配/无数据 → 空串（零阻断，调用方跳过）。</p>
     *
     * @param projectId 项目 ID（可空）
     *
     * @return 段 2D 前缀文本（空串=无数据/读取失败）
     */
    public String worldDynamicsPrefix(Long projectId) {
        String dynamics = worldDynamicsLine(projectId);
        if (dynamics == null || dynamics.isBlank()) {
            return "";
        }
        return "【当前世界动态】\n" + dynamics;
    }

    /**
     * 段 1A【世界观】+ 段 1C【角色身份】(有角色引用时) + 段 2D【当前世界动态】前缀
     * （世界视角模板统一注入段，2026-10-10 用户指示「该类 prompt 至少也得注入【世界观】和
     * 角色身份信息，要不然 AI 怎么会知道具体的内容」——宏观判定/世界走向等世界视角调用，
     * AI 若不知道世界背景与行动者是谁，无法判断影响/走势）。
     * <p>有 {@code characterRef}（宏观影响判定=行动者、世界走向=决策者）→ 注入 段1C
     * {@link #characterCard}；无角色引用（环境/世界帧/编年史等纯世界描述）→ 仅 段1A+段2D。
     * 未装配/无数据 → 空串（零阻断，调用方跳过）。</p>
     *
     * @param projectId 项目 ID（可空）
     *
     * @param characterRef 涉及角色引用（{@code npc:{id}}；可空=仅世界观+世界动态）
     *
     * @return 段 1A+1C+2D 前缀文本（空串=无数据/读取失败）
     */
    public String worldContextPrefix(Long projectId, String characterRef) {
        List<String> segs = new ArrayList<>();
        String composed = compose(projectId, characterRef);
        if (!composed.isBlank()) {
            segs.add(composed);
        }
        String dynamics = worldDynamicsPrefix(projectId);
        if (!dynamics.isBlank()) {
            segs.add(dynamics);
        }
        return segs.isEmpty() ? "" : String.join("\n\n", segs);
    }

    // ==================== V2.0 决策与感知装配（批 3：段 2E 当前状态 + 段 2D/2E 前缀） ====================

    /**
     * 段 2E · 当前状态（决策/途中交互注入用）：委托 {@link NpcStateService#stateSegment}
     * 渲染全量【当前状态】段（惰性建行 + 按游戏时刻衰减补偿 + 需求/心情/情绪/紧急/健康）。
     * <p>V2.0 规范化结构 §3.3：2E = 地点/生理/情绪及强度/当前行动/周围实体（V1.0 CURRENT_STATE 平移）。
     * 本方法与 {@link #characterCard} 内的精简 stateBits 分工：核心卡预算内给状态位元；
     * 决策/途中交互等「此刻决策」场景调用本方法取全量状态段（AI 带着当下生理/情绪做决定）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用（{@code npc:{id}}）
     *
     * @param gameSecond 游戏时刻（秒，可空=服务回退当前引擎时刻）
     *
     * @return 【当前状态】段文本（无内容/服务未装配返回空串）
     */
    public String currentStateSegment(Long projectId, String characterRef, Long gameSecond) {
        if (npcStateService == null || projectId == null || characterRef == null || characterRef.isBlank()) {
            return "";
        }
        try {
            return npcStateService.stateSegment(projectId, characterRef, gameSecond);
        } catch (Exception e) {
            log.debug("[上下文注入] 段2E 当前状态获取失败（跳过）: ref={} : {}", characterRef, e.getMessage());
            return "";
        }
    }

    /**
     * V2.0 决策感知前缀（批 3）：段 2D【当前世界动态】+ 段 2E【当前状态】拼接，
     * 供<b>实时决策（action_gen）</b>与<b>途中交互（move_encounter）</b>在本轮输入/刺激
     * 之前注入——AI 做决定/决定是否搭话时也知道「现在是什么时节天气、我此刻状态如何」
     * （真人感：快饿死的人与精神饱满的人对同一刺激的反应不同）。
     * <p>逐段降级：段 2D/2E 任一为空则跳过该段；两段全空返回空串（零阻断）。
     * 段 2H【本轮输入/刺激】由调用方自己的情境承担，本方法只补 2D/2E（不重复包裹）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用（{@code npc:{id}}）
     *
     * @param gameSecond 游戏时刻（秒，可空）
     *
     * @return 段 2D+2E 前缀文本（空串=无数据/读取失败）
     */
    public String decisionStatePrefix(Long projectId, String characterRef, Long gameSecond) {
        List<String> segs = new ArrayList<>();
        String dynamics = worldDynamicsLine(projectId);
        if (!dynamics.isBlank()) {
            segs.add("【当前世界动态】\n" + dynamics);
        }
        String state = currentStateSegment(projectId, characterRef, gameSecond);
        if (!state.isBlank()) {
            segs.add(state);
        }
        return segs.isEmpty() ? "" : String.join("\n\n", segs);
    }

    /**
     * V2.0 判定输入装配（NanoJev 判定统一门用，段 1+段 2+段 3 全量拼接为单串）。
     * <p>V2.0 规范化结构 §3.4：「对 NanoJev 判定这类<b>单串判定输入</b>：段 1+段 2+段 3
     * 全量拼接为一个判定输入串」。顺序：</p>
     * <ol>
     * <li>段 1A【世界观】= {@link #worldBrief}（稳定 300-500 字，缓存友好）；</li>
     * <li>段 1B【系统指令】= {@link #SEG_1B_NANOJEV_INSTRUCTION}（判定引擎固定句）；</li>
     * <li>段 1C【角色身份】= {@link #characterCard}（涉及该角色必注入）；</li>
     * <li>段 2D【当前世界动态】= {@link #worldDynamicsLine}（精简行，可空）；</li>
     * <li>段 2H【本次情境/本轮输入】= 原 state 文本（调用方判定输入）；</li>
     * <li>段 3I【行为护栏】= {@link #SEG_3I_BEHAVIOR_GUARD}（Post-History 高权重位）；</li>
     * <li>段 3J【输出契约】= {@link #SEG_3J_OUTPUT_CONTRACT}（Post-History 高权重位）。</li>
     * </ol>
     * <p>逐段降级：世界观/角色卡/世界动态任一空则跳过该段；全前缀空 → 仍保留
     * 1B+3I+3J 固定段 + 原输入（判定纪律不随数据缺失丢失）。未装配注入器/异常 → 原样
     * 返回（零阻断，保既有行为）。</p>
     *
     * @param projectId 项目 ID（可空）
     *
     * @param characterRef 涉及的角色引用（{@code npc:{id}}；可空=仅世界观+纪律，无角色卡）
     *
     * @param stateText 原判定输入文本（可空）
     *
     * @return 段 1+2+3 单串判定输入（未装配/全空返回原文本）
     */
    public String decisionInput(Long projectId, String characterRef, String stateText) {
        List<String> segs = new ArrayList<>();
        String brief = worldBrief(projectId);
        if (!brief.isBlank()) {
            segs.add("【世界观】\n" + brief);
        }
        segs.add("【系统指令】\n" + SEG_1B_NANOJEV_INSTRUCTION);
        String card = characterRef == null || characterRef.isBlank()
                ? "" : characterCard(projectId, characterRef);
        if (!card.isBlank()) {
            segs.add("【角色身份】\n" + card);
        }
        String dynamics = worldDynamicsLine(projectId);
        if (!dynamics.isBlank()) {
            segs.add("【当前世界动态】\n" + dynamics);
        }
        String base = stateText == null || stateText.isBlank() ? "" : stateText.trim();
        if (!base.isBlank()) {
            segs.add("【本次情境】\n" + base);
        }
        segs.add("【行为护栏】\n" + SEG_3I_BEHAVIOR_GUARD);
        segs.add("【输出契约】\n" + SEG_3J_OUTPUT_CONTRACT);
        return String.join("\n\n", segs).trim();
    }

    /**
     * V2.0 记忆与反思链装配（批 4）：段 1A【世界观】+ 段 1C【角色核心卡】+ 段 2D【当前世界动态】
     * + 段 2E【当前状态】拼接前缀，供<b>记忆链（抽取/摘要/复核/情绪评估/主动检索）与反思链
     * （reflection）</b>在模板输入之前注入——AI 判断「什么值得记 / 如何归纳 / 复核是否保留 /
     * 此刻情绪 / 是否需要想起 / 反思什么」时，也知道「世界是什么、我是谁、此刻什么时节天气、
     * 我状态如何」。
     * <p>本质 = {@link #compose}（段 1A+1C）+ {@link #decisionStatePrefix}（段 2D+2E）合并；
     * 逐段降级：任一段空则跳过该段，全空返回空串（零阻断）。段 2F【记忆与知识】由各模板自身的
     * 近期记忆/对话文本承担（记忆链是 2F 的产出方），本方法不重复包裹。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用（{@code npc:{id}}）
     *
     * @param gameSecond 游戏时刻（秒，可空=服务回退当前引擎时刻）
     *
     * @return 记忆与反思链前缀文本（空串=无数据/读取失败）
     */
    public String memoryChainContext(Long projectId, String characterRef, Long gameSecond) {
        List<String> segs = new ArrayList<>();
        String composed = compose(projectId, characterRef);
        if (!composed.isBlank()) {
            segs.add(composed);
        }
        String prefix = decisionStatePrefix(projectId, characterRef, gameSecond);
        if (!prefix.isBlank()) {
            segs.add(prefix);
        }
        return segs.isEmpty() ? "" : String.join("\n\n", segs);
    }

    // ==================== V2.0 对话链装配（批 2：few-shot 真实历史抽取 + 段 3 Post-History 护栏） ====================

    /** few-shot 示例组数上限（V2.0 §4.4 B4：该角色近期真实对话动态抽取 1-3 组） */
    private static final int FEW_SHOT_GROUPS = 3;

    /** few-shot 示例段字符预算（示例计入段 2 预算，超限整体截断） */
    private static final int FEW_SHOT_CHARS = 800;

    /** few-shot 抽取扫描的最近会话数上限（防长期运行会话过多拖慢组装） */
    private static final int FEW_SHOT_CONVERSATION_LIMIT = 5;

    /**
     * 段 3I · 行为护栏（Post-History 高权重指令位，对话模板用）。
     * <p>V2.0 规范化结构 §3.4：历史后的指令约束力显著强于历史前（SillyTavern jailbreak 同构）。
     * 单聊/群聊共用同一套护栏（第一人称 / 不替他人发言 / 只回复一轮 / 不跳出角色），
     * 落在触发消息末尾（会话历史之后的高权重位）。</p>
     *
     * @param name 角色名（可空=用「你」）
     *
     * @return 段 3I 文本
     */
    public static String dialogueGuardRail(String name) {
        String who = name == null || name.isBlank() ? "你" : name;
        return "以【" + who + "】的第一人称说话，只说你自己的话：\n"
                + "1. 不替其他角色（或玩家）发言、不代答、不转述或标注其他角色的台词；\n"
                + "2. 只回复这一轮，不要替对方做决定，不要跳出角色。";
    }

    /**
     * 段 2F 内 few-shot 示例对话（V2.0 §4.4 B4 机制）：该角色近期高质量<b>真实对话</b>
     * 动态抽取 1-{@value #FEW_SHOT_GROUPS} 组（对话 + 动作 + 情绪），随世界演化自动更新；
     * 无真实历史时用<b>模板预置示例</b>兜底（Q7）。抽取失败 fail-open 返回空串（不注入示例、不阻断）。
     * <p>数据源=actor_conversation + actor_message（该角色作为成员的最近会话中已完成发言；
     * 每组=对方最近前文 + 本人发言 + 动作/情绪[自 rawStream 群聊契约解析]）。
     * 预算={@value #FEW_SHOT_CHARS} 字符（计入段 2 预算）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 涉及的角色引用（{@code npc:{id}}）
     *
     * @return few-shot 段文本（无数据/失败返回空串；无真实历史时返回模板预置示例）
     */
    public String dialogueFewShot(Long projectId, String characterRef) {
        if (projectId == null || characterRef == null || characterRef.isBlank()
                || conversationMemberRepository == null || conversationRepository == null
                || messageRepository == null) {
            return "";
        }
        Long characterId = NpcRefs.idOf(characterRef);
        if (characterId == null) {
            return "";
        }
        try {
            String selfName = characterRepository == null ? null
                    : characterRepository.findById(characterId).map(ActorCharacter::getName).orElse(null);
            // ① 该角色的会话成员关系 → 归属本项目的最新会话（按 updatedAt 倒序，最多取 N 个）
            List<ActorConversation> convs = recentConversationsOf(projectId, characterId);
            // ② 从最近会话倒序扫该角色已完成发言，抽最近 1-3 组（每组=对方前文 + 本人发言 + 动作/情绪）
            List<String> groups = new ArrayList<>();
            for (ActorConversation c : convs) {
                if (groups.size() >= FEW_SHOT_GROUPS) {
                    break;
                }
                List<ActorMessage> history;
                try {
                    history = messageRepository.findByConversationIdOrderByIdAsc(c.getId());
                } catch (Exception e) {
                    continue;
                }
                if (history == null || history.isEmpty()) {
                    continue;
                }
                for (int i = history.size() - 1; i >= 0 && groups.size() < FEW_SHOT_GROUPS; i--) {
                    ActorMessage m = history.get(i);
                    if (m == null || m.getCharacterId() == null || !m.getCharacterId().equals(characterId)
                            || !"assistant".equals(m.getRole()) || !"done".equals(m.getStatus())) {
                        continue;
                    }
                    String content = m.getContent();
                    if (content == null || content.isBlank()) {
                        continue;
                    }
                    String preceding = precedingLine(history, i, characterId);
                    if (preceding == null) {
                        continue; // 开场第一句无对方前文，跳过（few-shot 只取有来有往的真实对话）
                    }
                    String group = formatFewShotGroup(preceding, content,
                            rawField(m.getRawStream(), "action"), rawField(m.getRawStream(), "emotion"), selfName);
                    if (group != null) {
                        groups.add(group);
                    }
                }
            }
            if (groups.isEmpty()) {
                return templateFewShotFallback(selfName);
            }
            return "【示例对话】\n" + String.join("\n", groups);
        } catch (Exception e) {
            log.debug("[上下文注入] few-shot 示例抽取失败（跳过，零阻断）: ref={} : {}", characterRef, e.getMessage());
            return "";
        }
    }

    // ==================== 私有辅助 ====================

    /** 该角色作为成员的最近会话（本项目，按 updatedAt 倒序，最多取 {@value #FEW_SHOT_CONVERSATION_LIMIT} 个）。 */
    private List<ActorConversation> recentConversationsOf(Long projectId, Long characterId) {
        List<ActorConversation> out = new ArrayList<>();
        try {
            List<ActorConversationMember> memberships = conversationMemberRepository.findByCharacterId(characterId);
            if (memberships == null || memberships.isEmpty()) {
                return out;
            }
            for (ActorConversationMember m : memberships) {
                if (m == null || m.getConversationId() == null) {
                    continue;
                }
                try {
                    ActorConversation c = conversationRepository.findById(m.getConversationId()).orElse(null);
                    if (c != null && projectId.equals(c.getProjectId())) {
                        out.add(c);
                    }
                } catch (Exception ignored) {
                    // 单条会话解析失败跳过（零阻断）
                }
            }
            out.sort(Comparator.comparing(ActorConversation::getUpdatedAt,
                    Comparator.nullsFirst(Comparator.reverseOrder())));
            if (out.size() > FEW_SHOT_CONVERSATION_LIMIT) {
                return new ArrayList<>(out.subList(0, FEW_SHOT_CONVERSATION_LIMIT));
            }
        } catch (Exception e) {
            log.debug("[上下文注入] 会话归属解析失败（跳过）: ref={} : {}", characterId, e.getMessage());
        }
        return out;
    }

    /** 该消息的前一条实质「对方」消息（非本人、非 system 叙述；取最近一条，无则 null）。 */
    private String precedingLine(List<ActorMessage> history, int selfIndex, Long characterId) {
        for (int i = selfIndex - 1; i >= 0; i--) {
            ActorMessage m = history.get(i);
            if (m == null || m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            if ("system".equals(m.getRole())) {
                continue; // 退场/收尾叙述等系统行不作为「对方」
            }
            if ("user".equals(m.getRole())) {
                return "玩家：" + m.getContent().trim();
            }
            if ("event".equals(m.getType())) {
                return "（事件）" + m.getContent().trim();
            }
            if ("assistant".equals(m.getRole()) && "done".equals(m.getStatus())) {
                if (m.getCharacterId() != null && m.getCharacterId().equals(characterId)) {
                    continue; // 本人上一条发言不算「对方」
                }
                String otherName = characterRepository == null ? "对方"
                        : characterRepository.findById(m.getCharacterId()).map(ActorCharacter::getName).orElse("对方");
                return (otherName == null || otherName.isBlank() ? "对方" : otherName)
                        + "：" + m.getContent().trim();
            }
        }
        return null;
    }

    /** 组装单组 few-shot 示例：对方前文 + 本人发言（动作/情绪可选拼接）。 */
    private String formatFewShotGroup(String preceding, String content, String action, String emotion,
                                      String selfName) {
        StringBuilder sb = new StringBuilder();
        if (preceding != null && !preceding.isBlank()) {
            sb.append(preceding.trim()).append("\n");
        }
        String who = selfName == null || selfName.isBlank() ? "你" : selfName;
        sb.append("你（").append(who).append("）：").append(content.trim());
        StringBuilder aux = new StringBuilder();
        if (action != null && !action.isBlank()) {
            aux.append("动作：").append(action.trim());
        }
        if (emotion != null && !emotion.isBlank()) {
            if (aux.length() > 0) {
                aux.append("，");
            }
            aux.append("情绪：").append(emotion.trim());
        }
        if (aux.length() > 0) {
            sb.append("（").append(aux).append("）");
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /** 模板预置示例兜底（Q7：无真实历史时用内容中立的模板示例，不虚构世界事实）。 */
    private String templateFewShotFallback(String selfName) {
        String who = selfName == null || selfName.isBlank() ? "你" : selfName;
        return "【示例对话】（说话方式范例：以角色第一人称自然回应，不替对方说话）\n"
                + "对方：（有人与你攀谈，说了一句家常话）\n"
                + "你（" + who + "）：（用你真实的身份、性格与说话风格，自然地回应一句话）";
    }

    /** 从群聊 rawStream 契约 JSON 提取 action/emotion 字段（轻量字符串解析，失败返回 null）。 */
    private static String rawField(String raw, String key) {
        if (raw == null || raw.isBlank() || key == null) {
            return null;
        }
        String needle = "\"" + key + "\"";
        int idx = raw.indexOf(needle);
        if (idx < 0) {
            return null;
        }
        int colon = raw.indexOf(':', idx + needle.length());
        if (colon < 0) {
            return null;
        }
        int quote = raw.indexOf('"', colon + 1);
        if (quote < 0) {
            return null;
        }
        int end = raw.indexOf('"', quote + 1);
        if (end < 0) {
            return null;
        }
        String v = raw.substring(quote + 1, end);
        return v.isBlank() ? null : v;
    }

    /** 身份基线段：姓名（头衔）/性别/种族/职业/归属/所在地/年龄/当前行动。 */
    private String identityBits(ActorCharacter ch) {
        List<String> bits = new ArrayList<>();
        bits.add(ch.getName() == null || ch.getName().isBlank() ? "无名" : ch.getName().trim());
        if (notBlank(ch.getTitle())) {
            bits.add(ch.getTitle().trim());
        }
        if (notBlank(ch.getGender())) {
            bits.add(ch.getGender().trim());
        }
        if (notBlank(ch.getRace())) {
            bits.add(ch.getRace().trim());
        }
        if (notBlank(ch.getOccupation())) {
            bits.add(ch.getOccupation().trim());
        }
        if (notBlank(ch.getAffiliation())) {
            bits.add("归属" + ch.getAffiliation().trim());
        }
        if (ch.getAge() != null) {
            bits.add(ch.getAge() + "岁");
        }
        StringBuilder sb = new StringBuilder("【身份】").append(String.join("，", bits));
        if (notBlank(ch.getCurrentActivity())) {
            sb.append("；此刻正在：").append(ch.getCurrentActivity().trim());
        }
        return sb.toString();
    }

    /** 关键关系摘要（≤ relationLines 条：关系类型/好感/信任/状态；无关系返回空串）。 */
    private String relationSegment(Long projectId, String characterRef, Long characterId) {
        if (relationRepository == null) {
            return "";
        }
        try {
            List<ActorCharacterRelation> all = relationRepository.findByProjectId(projectId);
            if (all.isEmpty()) {
                return "";
            }
            List<ActorCharacterRelation> involved = new ArrayList<>();
            for (ActorCharacterRelation r : all) {
                if (matches(r.getFromRef(), r.getFromCharacterId(), r.getFromName(), characterRef, characterId)
                        || matches(r.getToRef(), r.getToCharacterId(), r.getToName(), characterRef, characterId)) {
                    involved.add(r);
                }
            }
            if (involved.isEmpty()) {
                return "";
            }
            // 亲密状态优先 + 好感降序，取前 N 条
            involved.sort(Comparator.comparingInt((ActorCharacterRelation r) ->
                                    "intimate".equals(r.getRelationState()) ? 2
                                            : "friend".equals(r.getRelationState()) ? 1 : 0)
                            .reversed()
                            .thenComparing(r -> r.getAffinity() == null ? 0 : r.getAffinity(),
                                    Comparator.reverseOrder()));
            List<String> lines = new ArrayList<>();
            for (ActorCharacterRelation r : involved) {
                if (lines.size() >= relationLines) {
                    break;
                }
                String other = otherName(r, characterRef, characterId);
                if (other == null || other.isBlank()) {
                    continue;
                }
                String state = r.getRelationState() == null ? "未知" : r.getRelationState();
                int affinity = r.getAffinity() == null ? 0 : r.getAffinity();
                int trust = r.getTrust() == null ? 0 : r.getTrust();
                String line = other + "（" + state + "，好感" + affinity + "，信任" + trust + "）";
                lines.add(line);
            }
            return lines.isEmpty() ? "" : "【关键关系】" + String.join("；", lines);
        } catch (Exception e) {
            log.debug("[上下文注入] 关键关系摘要失败（跳过）: ref={} : {}", characterRef, e.getMessage());
            return "";
        }
    }

    /** 当前状态段：主导情绪 + 生理需求缺口（饿了/口渴/疲惫/有点闷）。 */
    private String stateBits(Long projectId, String characterRef) {
        if (npcStateService == null) {
            return "";
        }
        try {
            ActorNpcState st = npcStateService.stateFor(projectId, characterRef);
            if (st == null) {
                return "";
            }
            List<String> bits = new ArrayList<>();
            if (st.getDominantEmotion() != null && !st.getDominantEmotion().isBlank()
                    && !"calm".equalsIgnoreCase(st.getDominantEmotion())) {
                bits.add("情绪：" + st.getDominantEmotion().trim());
            }
            if (st.getHunger() != null && st.getHunger() < 40) {
                bits.add("饿了");
            }
            if (st.getThirst() != null && st.getThirst() < 40) {
                bits.add("口渴");
            }
            if (st.getEnergy() != null && st.getEnergy() < 40) {
                bits.add("疲惫");
            }
            if (st.getSocial() != null && st.getSocial() < 40) {
                bits.add("有点闷");
            }
            return bits.isEmpty() ? "" : "【当前状态】" + String.join("，", bits);
        } catch (Exception e) {
            log.debug("[上下文注入] 当前状态段失败（跳过）: ref={} : {}", characterRef, e.getMessage());
            return "";
        }
    }

    /** 关系行是否命中该角色（ref 精确 / id 匹配 / 名称兜底）。 */
    private static boolean matches(String ref, Long id, String name, String characterRef, Long characterId) {
        if (ref != null && ref.equals(characterRef)) {
            return true;
        }
        if (id != null && characterId != null && id.equals(characterId)) {
            return true;
        }
        return name != null && characterRef != null && name.equals(characterRef);
    }

    /** 关系行另一方名称（ref/id 解析；无法解析返回 null）。 */
    private String otherName(ActorCharacterRelation r, String characterRef, Long characterId) {
        if (r.getFromRef() != null && r.getFromRef().equals(characterRef)) {
            return r.getToName();
        }
        if (r.getToRef() != null && r.getToRef().equals(characterRef)) {
            return r.getFromName();
        }
        if (r.getFromCharacterId() != null && characterId != null
                && r.getFromCharacterId().equals(characterId)) {
            return r.getToName();
        }
        if (r.getToCharacterId() != null && characterId != null
                && r.getToCharacterId().equals(characterId)) {
            return r.getFromName();
        }
        return null;
    }

    // ==================== 私有辅助 ====================

    /** 追加命名小节（内容非空才加）。 */
    private static void appendSection(StringBuilder sb, String label, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        String c = content.trim();
        if (sb.length() > 0) {
            sb.append("\n");
        }
        sb.append("【").append(label).append("】").append(c);
    }

    /** 非空判断（null/空白）。 */
    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
