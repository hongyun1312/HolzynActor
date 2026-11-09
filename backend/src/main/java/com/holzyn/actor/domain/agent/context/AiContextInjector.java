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
import com.holzyn.actor.domain.world.entity.ActorWorldEntity;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroEvent;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldEntityRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldMacroEventRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.world.service.SeasonService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一 AI 上下文注入器（角色视角 AI 调用 · 上下文富化）。
 * <p><b>定位</b>：所有「涉及具体角色视角」的 AI 调用（对话/行动/决策/记忆/感知/关系/情绪/
 * 空间触发/演化/反思/决策模型 角色判定）在发送前，统一注入两类上下文：</p>
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
 * 核心卡/简报均有字符预算上限，防上下文超限。轻量判定（决策模型 boolean/score/choice）
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

    /** 地点仓库（可选依赖）：常驻前缀「地点」段来源。 */
    @Autowired(required = false)
    private WorldLocationRepository worldLocationRepository;

    /**
     * L3 实体状态仓库（可选依赖）：段 2F【所处之地】的地点当前状态/近期变化来源。
     * <p>为什么是<b>实体状态</b>而不是感知段：{@code actor_world_entity} 是「世界视角公共状态」
     * （谁都知道的客观事实），角色站在哪儿、那儿眼下是什么样，属于所有角色共享的世界层，
     * 不是某个人的私有感知。段 2E【当前状态】只有饥饿/心情/健康等自身数值，
     * 决策因此只能围着生理需求打转（澄江镇二日实跑：老吴 122 条行动里「铁料/炉子」出现 0 次）。
     * null=未装配跳过本段（单测直接 new 构造时保持行为不变）。</p>
     */
    @Autowired(required = false)
    private ActorWorldEntityRepository worldEntityRepository;

    /** 宏观事件仓库（可选依赖）：段 2F「镇上的大动静」一行来源；null=跳过该行。 */
    @Autowired(required = false)
    private ActorWorldMacroEventRepository macroEventRepository;

    /**
     * 感知服务（可选依赖）：判定输入里的【空间约束】段由它按权威坐标生成。
     *
     * <p>⚠️ {@code @Lazy}：PerceptionService 自身会被多个运行时服务注入，本类又被十余个调用方
     * 注入——直接强引用会在启动期形成 Bean 环（与 PersonaService 同一类问题）。</p>
     */
    @Lazy
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.perception.PerceptionService perceptionService;

    /** 档案服务（11 维档案分片渲染；null=跳过档案段）
     *  ⚠️ @Lazy：AiContextInjector 被 12 类角色视角调用方注入，且自身又依赖
     *  PersonaService/NpcStateService（二者经 NpcStateService→AiSpringChatGateway→
     *  GuardSemanticCheck→DecisionGate 回到本注入器）——不加 @Lazy
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
     *  ⚠️ @Lazy：EnvironmentContextService → AiSpringChatGateway → GuardSemanticCheck →
     *  DecisionGate → 本注入器 环形依赖，懒注入断环（同 personaService 先例）。 */
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

    /**
     * 世界程度（：控制世界能力与设定的边界）。
     * <p>为什么注入到「世界观简报」这一层：它是所有对话/判定/叙事调用都会拼的恒定段，
     * 在唯一入口加一段「世界程度：休闲 —— 不出现势力斗争/战争/暗杀/背叛…」，所有场景就都
     * 受同一份边界约束，不需要逐个提示词模板改占位符（改了模板还要同步
     * {@code PromptTemplateServiceTest} 计数，扩散面大且易漏）。</p>
     */
    @Lazy
    @Autowired(required = false)
    private com.holzyn.actor.domain.world.service.WorldToneService worldToneService;

    /**
     * 决策模型判定上下文<b>精简装配</b>开关（实跑事故）。
     * <p>为什么：决策模型是 0.6B 判定层，原全量装配给每个 state 前置「700 字世界常识 +
     * 1500 字角色卡 + 三段纪律」≈ 2.6KB；12 states 批量请求达 20-34KB，实测单请求
     * 5s 起步、34KB 报文 120s 未返回，把世界推进拖成超时重试风暴。</p>
     * <p>精简后单 state 约 300-500 字符（世界一行 + 角色 3 行 + 情境 + 一行约束）。</p>
     */
    @Value("${holzyn.actor.context.decision-compact:true}")
    private boolean decisionCompact;

    /** 精简装配·角色行字符上限（默认 220；<=0 视为默认值） */
    @Value("${holzyn.actor.context.decision-card-chars:220}")
    private int decisionCardChars;

    /** 精简装配·世界行字符上限（默认 48；<=0 视为默认值） */
    @Value("${holzyn.actor.context.decision-world-chars:48}")
    private int decisionWorldChars;

    // ==================== V2.0 判定输入固定段文本（段 1B / 段 3I / 段 3J） ====================

    /**
     * 段 1B · 系统指令（决策模型 判定引擎专用，调用类型专属；跨调用稳定=缓存友好）。
     * <p>V2.0 规范化结构 §3.2：1B 为「系统指令 / 任务指令」，开头含角色锚定句。
     * 决策模型 是 0.6B 并行决策模型（System One 判定层）——指令刻意收敛为
     * 「结构化情境 + 明确判据」：只依据情境事实与问题判据作答，不臆测。</p>
     */
    public static final String SEG_1B_DECISION_INSTRUCTION =
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
     * <p>决策模型 输出概率分布（零解码）——契约即「按问题判据作答」；
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
                return appendToneBoundary(projectId, w.getCommonKnowledge().trim());
            }
            // 回退：结构化字段拼装（旧数据兼容，仍整体截断到预算）
            return appendToneBoundary(projectId, assembleFromSections(w));
        } catch (Exception e) {
            log.debug("[上下文注入] 世界观简报组装失败（跳过）: project={} : {}", projectId, e.getMessage());
            return "";
        }
    }

    /**
     * 在简报末尾追加「世界程度边界」段（标准档不追加，保持改造前字节一致）。
     *
     * @param projectId 项目 ID
     * @param brief     已有简报
     *
     * @return 追加后的简报（无边界/无档位时原样返回）
     */
    private String appendToneBoundary(Long projectId, String brief) {
        if (brief == null || brief.isBlank()) {
            return brief;
        }
        if (worldToneService == null) {
            return brief;
        }
        try {
            String boundary = worldToneService.boundary(projectId);
            if (boundary == null || boundary.isBlank()) {
                return brief;
            }
            return brief + "\n" + boundary;
        } catch (RuntimeException e) {
            log.debug("[上下文注入] 世界程度边界读取失败（跳过）: project={} : {}", projectId, e.toString());
            return brief;
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

    // ==================== 组合注入（决策模型 判定 / ChatModel 调用共用） ====================

    /**
     * 组装【注入上下文】段：世界观简报（恒定）+ 角色核心卡（涉及该角色必注入）。
     * <p>轻量判定（决策模型）与重量生成（对话/决策）共用：调用方把本段拼到自己的
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
     * 便捷：把注入上下文段拼到既有 state 文本前（决策模型 判定用）。
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
     * 段 2D · 当前世界动态（精简行，决策模型 轻量判定用）。
     * <p>V2.0 规范化结构 §3.3：2D = 游戏时间/季节/天气……「决策模型 轻量判定可只给精简行」。
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
        String locality = localitySegment(projectId, characterRef, gameSecond);
        if (!locality.isBlank()) {
            segs.add(locality);
        }
        return segs.isEmpty() ? "" : String.join("\n\n", segs);
    }

    // ==================== 段 2F【所处之地】（世界状态进入行动决策） ====================

    /**
     * 段 2F 标题（去重判据：情境已含本段时不重复注入，防 prompt 双份）。
     */
    public static final String SEG_2F_HEADER = "【所处之地】";

    /** 段 2F 字符预算（默认 320；&lt;=0 视为默认值） */
    @Value("${holzyn.actor.context.locality-chars:320}")
    private int localityChars;

    /** 段 2F 单行裁剪上限（地点当前状态/近期变化） */
    private static final int LOCALITY_LINE_MAX = 90;

    /** 段 2F 旁证行裁剪上限（其它实体近期变化/宏观事件） */
    private static final int LOCALITY_SIDE_MAX = 60;

    /**
     * 段 2F · 所处之地（世界状态进入行动决策的唯一装配口）。
     *
     * <p><b>为什么需要这一段</b>：澄江镇二日实跑的根因诊断是「叙事会说话、决策不会动」——
     * 剧情全在编年史与对话里，行动层只有生理循环（觅食/饮水/稍候片刻/找人说说话）。
     * 逐通道排查定位到装配层：{@code actor_world_entity}（地点当前状态/近期变化）与
     * 当轮宏观事件在 {@code action} 决策 prompt 里出现 <b>0 次</b>，而反事实探针证明
     * 一旦接上，输出分布位移可达 TV=0.833。所以不是模型不动，是喂进去的东西里
     * 根本没有「此刻此地发生了什么」。</p>
     *
     * <p><b>四行结构</b>（逐行降级，任一缺失跳过该行）：</p>
     * <ol>
     * <li>{@code 这里眼下}：脚下这块地的 {@code current_state}；</li>
     * <li>{@code 刚刚的变化}：同一行的 {@code recent_change}（最近一次状态变化）；</li>
     * <li>{@code 四下里的动静}：其它实体（地点/人物/势力）近一两日的 {@code recent_change}，
     * 最多 2 条——这是「消息传进耳朵」的最短路径；</li>
     * <li>{@code 镇上的大动静}：最近一次重要度 ≥3 的宏观事件标题（旱情/歉收/黑船这类
     * 影响全镇的事，不该只活在编年史里）。</li>
     * </ol>
     *
     * <p><b>只陈述事实，不替角色做决定</b>：末行仅提示「要不要理会、怎么理会自己掂量」，
     * 不指定行动（世界层只提供「势」，行动决策权仍在 AI，设计原则不变）。</p>
     *
     * <p><b>fail-open</b>：未装配/{@code projectId} 空/任何异常 → 返回空串（零阻断），
     * 段 2D/2E 与决策主流程不受影响。</p>
     *
     * @param projectId 项目 ID（可空）
     *
     * @param characterRef 角色引用（{@code npc:{id}}；可空=只给「四下里的动静 + 时局」）
     *
     * @param gameSecond 游戏时刻（秒；可空=只给静态行，不按日筛宏观事件）
     *
     * @return 段 2F 文本（空串=无数据/未装配/读取失败）
     */
    public String localitySegment(Long projectId, String characterRef, Long gameSecond) {
        if (projectId == null || worldEntityRepository == null) {
            return "";
        }
        try {
            long day = gameSecond == null || gameSecond <= 0 ? -1L : Math.floorDiv(gameSecond, 86400L);
            List<String> lines = new ArrayList<>();
            ActorCharacter self = characterOf(characterRef);
            String hereName = self == null ? null : self.getLocation();
            ActorWorldEntity here = locationEntity(projectId, hereName);
            if (here != null) {
                if (here.getName() != null && !here.getName().isBlank()) {
                    hereName = here.getName();
                }
                addLocalityLine(lines, "这里眼下", clip(here.getCurrentState(), LOCALITY_LINE_MAX));
                addLocalityLine(lines, "刚刚的变化", clip(here.getRecentChange(), LOCALITY_LINE_MAX));
            }
            // 四下里的动静：其它实体的近期变化（排除脚下的地点、角色本人与同源事件，最多 2 条）
            boolean sideAdded = false;
            for (ActorWorldEntity e : nearbyChanges(projectId, here, self, day)) {
                addLocalityLine(lines, sideAdded ? "" : "四下里的动静",
                        entityLabel(e) + "：" + clip(e.getRecentChange(), LOCALITY_SIDE_MAX));
                sideAdded = true;
                if (lines.size() >= 4) {
                    break;
                }
            }
            // 镇上的大动静：最近一次重要度 ≥3 的宏观事件（旱/歉收/黑船这类全镇级消息）
            String macroLine = latestMacroLine(projectId, day);
            if (!macroLine.isBlank()) {
                addLocalityLine(lines, "镇上的大动静", macroLine);
            }
            if (lines.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder(SEG_2F_HEADER);
            if (hereName != null && !hereName.isBlank()) {
                sb.append(hereName);
            }
            int budget = localityChars > 0 ? localityChars : 320;
            int used = sb.length();
            for (String line : lines) {
                if (used + line.length() + 1 > budget) {
                    break;
                }
                sb.append('\n').append(line);
                used += line.length() + 1;
            }
            if (sb.length() <= SEG_2F_HEADER.length()) {
                return "";
            }
            // 有「当场的动静」才加一句行动提示（纯背景时不给，避免每轮都催角色找事做）
            boolean hasLiveChange = sideAdded
                    || (here != null && here.getRecentChange() != null && !here.getRecentChange().isBlank());
            if (hasLiveChange) {
                sb.append("\n（这些动静也许与你有关，也许无关；要不要理会、怎么理会，你自己掂量）");
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("[上下文注入] 段2F 所处之地失败（跳过）: ref={} : {}", characterRef, e.getMessage());
            return "";
        }
    }

    /**
     * 段 2F 单行版（决策模型 快判路径用）：把「此地刚有什么变化」压成一行 ≤80 字。
     *
     * <p><b>为什么不直接喂完整段 2F</b>：空间快判走的是 0.6B 判定层，实跑事故
     * 证明单次请求报文从 20KB 涨到 34KB 就会把世界推进拖成超时重试风暴。快判要的不是
     * 完整世界描述，而是<b>一个具体的钩子</b>——脚边刚发生了什么；所以这里只给一行。</p>
     *
     * <p>取值顺序：地点 {@code recent_change} → 最近一次重要度 ≥3 的宏观事件标题；
     * 都没有返回空串（快判情境零变化，行为与前一致）。</p>
     *
     * @param projectId 项目 ID（可空）
     *
     * @param characterRef 角色引用（{@code npc:{id}}；可空）
     *
     * @param gameSecond 游戏时刻（秒；可空）
     *
     * @return 单行提示（空串=无变化/未装配/读取失败）
     */
    public String localityHint(Long projectId, String characterRef, Long gameSecond) {
        if (projectId == null || worldEntityRepository == null) {
            return "";
        }
        try {
            long day = gameSecond == null || gameSecond <= 0 ? -1L : Math.floorDiv(gameSecond, 86400L);
            ActorCharacter self = characterOf(characterRef);
            ActorWorldEntity here = locationEntity(projectId, self == null ? null : self.getLocation());
            if (here != null && here.getRecentChange() != null && !here.getRecentChange().isBlank()) {
                return "此地刚有变化：" + clip(here.getRecentChange(), LOCALITY_SIDE_MAX);
            }
            String macroLine = latestMacroLine(projectId, day);
            return macroLine.isBlank() ? "" : "镇上近来的动静：" + clip(macroLine, LOCALITY_SIDE_MAX);
        } catch (Exception e) {
            log.debug("[上下文注入] 段2F 单行版失败（跳过）: ref={} : {}", characterRef, e.getMessage());
            return "";
        }
    }

    /** 角色实体（{@code actor_profile} 行；解析失败返回 null）。 */
    private ActorCharacter characterOf(String characterRef) {
        if (characterRepository == null || characterRef == null || characterRef.isBlank()) {
            return null;
        }
        try {
            Long id = NpcRefs.idOf(characterRef);
            return id == null ? null : characterRepository.findById(id).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 地点当前有效实体行：先按规范键 {@code location:地点名} 精确取，未命中再按显示名在地点域兜底。
     *
     * @param projectId 项目 ID
     *
     * @param locationName 地点名（可空）
     *
     * @return 实体行（null=无此地点状态）
     */
    private ActorWorldEntity locationEntity(Long projectId, String locationName) {
        if (worldEntityRepository == null || locationName == null || locationName.isBlank()) {
            return null;
        }
        String key = ActorWorldEntity.TYPE_LOCATION + ":" + locationName.trim();
        Optional<ActorWorldEntity> exact = worldEntityRepository
                .findTopByProjectIdAndEntityKeyAndValidToGameDayIsNullOrderByIdDesc(projectId, key);
        if (exact.isPresent()) {
            return exact.get();
        }
        for (ActorWorldEntity e : worldEntityRepository
                .findByProjectIdAndEntityTypeAndValidToGameDayIsNullOrderByIdDesc(
                        projectId, ActorWorldEntity.TYPE_LOCATION)) {
            if (e.getName() != null && locationName.trim().equals(e.getName().trim())) {
                return e;
            }
        }
        return null;
    }

    /**
     * 其它实体的近期变化（排除指定实体 / 角色本人 / <b>与脚下地点同源的事件</b>；按更新时间倒序，最多 2 条）。
     *
     * <p><b>为什么要按事件源去重</b>：复验里出现「同一件事说两遍」——地点行（{@code recent_change}）
     * 与人物行（该事件同时刷了参与者的实体状态）都写了「河神庙夜聚，众人信任加深」，
     * 于是「刚刚的变化」和「四下里的动静」各说一遍。读起来像是两件独立的事，
     * 实际只有一件，反而稀释了真正的新消息。这里按
     * {@code (provenance, provenance_ref)} 与变化文本双重去重：同一条事件只留脚下地点那一行。</p>
     *
     * @param projectId 项目 ID
     *
     * @param here      脚下的地点实体（可空=无地点视角，不做同源去重）
     *
     * @param self      角色本人（可空）
     *
     * @param day       当前游戏日（&lt;=0 不按日筛）
     *
     * @return 其它实体（最多 2 条）
     */
    private List<ActorWorldEntity> nearbyChanges(Long projectId, ActorWorldEntity here,
                                                 ActorCharacter self, long day) {
        List<ActorWorldEntity> out = new ArrayList<>();
        if (worldEntityRepository == null) {
            return out;
        }
        String excludeKey = here == null ? null : here.getEntityKey();
        String hereSource = here == null ? null : sourceKeyOf(here);
        Set<String> seenChanges = new HashSet<>();
        if (here != null && here.getRecentChange() != null && !here.getRecentChange().isBlank()) {
            seenChanges.add(normalizeChange(here.getRecentChange()));
        }
        String selfKey = null;
        if (self != null && self.getId() != null) {
            selfKey = ActorWorldEntity.TYPE_PERSON + ":" + NpcRefs.of(self.getId());
        }
        String selfName = self == null ? null : self.getName();
        for (ActorWorldEntity e : worldEntityRepository
                .findTop20ByProjectIdAndValidToGameDayIsNullAndRecentChangeIsNotNullOrderByUpdatedAtDescIdDesc(projectId)) {
            if (e.getEntityKey() == null || e.getEntityKey().equals(excludeKey)) {
                continue;
            }
            if (selfKey != null && e.getEntityKey().equals(selfKey)) {
                continue;
            }
            // 人物实体也可能按姓名建键（person:姓名）：同名一律不当作「四下里的动静」
            if (selfName != null && !selfName.isBlank() && selfName.equals(e.getName())) {
                continue;
            }
            if (e.getRecentChange() == null || e.getRecentChange().isBlank()) {
                continue;
            }
            // 与脚下地点同源（同一条事件同时刷新了地点与人物/势力）→ 已经说过，不再重复
            String source = sourceKeyOf(e);
            if (hereSource != null && hereSource.equals(source)) {
                continue;
            }
            // 同一句变化文本（不同实体被同一事件刷成同一句话）→ 只说一次
            if (!seenChanges.add(normalizeChange(e.getRecentChange()))) {
                continue;
            }
            // 只留「近一两日」的变化：更旧的已经在编年史里，重复注入等于稀释当下
            if (day > 0 && e.getValidFromGameDay() != null && e.getValidFromGameDay() < day - 1) {
                continue;
            }
            out.add(e);
            if (out.size() >= 2) {
                break;
            }
        }
        return out;
    }

    /**
     * 实体状态行的来源标识（{@code provenance:provenanceRef}；无 ref 返回 null=不参与同源去重）。
     *
     * @param e 实体行（可空）
     *
     * @return 来源标识（null=无事件来源，无法判同源）
     */
    private static String sourceKeyOf(ActorWorldEntity e) {
        if (e == null || e.getProvenanceRef() == null) {
            return null;
        }
        return (e.getProvenance() == null ? "" : e.getProvenance()) + ":" + e.getProvenanceRef();
    }

    /**
     * 变化文本归一（去空白/去标点差异，用于「同一句话」判定）。
     *
     * @param change 变化描述（可空）
     *
     * @return 归一文本（可空）
     */
    private static String normalizeChange(String change) {
        if (change == null) {
            return "";
        }
        return change.replaceAll("[\\s，。、；：！？,. ;:!?]", "");
    }

    /** 最近一次重要度 ≥3 的宏观事件标题行（当天优先，回退前一日；无事件返回空串）。 */
    private String latestMacroLine(Long projectId, long day) {
        if (macroEventRepository == null || day <= 0) {
            return "";
        }
        try {
            List<ActorWorldMacroEvent> events = macroEventRepository
                    .findByProjectIdAndGameDayBetweenAndSeverityGreaterThanEqualOrderByGameDayAsc(
                            projectId, day - 1, day, 3);
            if (events == null || events.isEmpty()) {
                return "";
            }
            ActorWorldMacroEvent latest = events.get(events.size() - 1);
            String title = latest.getTitle() == null || latest.getTitle().isBlank()
                    ? latest.getEventType() : latest.getTitle().trim();
            StringBuilder sb = new StringBuilder(title);
            // 叙事只在「能整句装下」时才带：截半句会出现「阿珍的菜摊上，（重要度 4）」这种断头话，
            // 反而把一条有用的时局读成噪声；装不下就只给标题。
            String narrative = latest.getNarrative() == null ? "" : latest.getNarrative().trim();
            if (!narrative.isEmpty() && narrative.length() <= LOCALITY_SIDE_MAX) {
                sb.append("——").append(narrative);
            }
            if (latest.getSeverity() != null) {
                sb.append("（重要度 ").append(latest.getSeverity()).append("）");
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("[上下文注入] 段2F 宏观事件行失败（跳过）: project={} : {}", projectId, e.getMessage());
            return "";
        }
    }

    /** 实体显示名（name 优先，缺失回退 entity_key 冒号后的部分）。 */
    private static String entityLabel(ActorWorldEntity e) {
        if (e.getName() != null && !e.getName().isBlank()) {
            return e.getName().trim();
        }
        String key = e.getEntityKey() == null ? "" : e.getEntityKey();
        int idx = key.indexOf(':');
        return idx >= 0 && idx + 1 < key.length() ? key.substring(idx + 1) : key;
    }

    /** 段 2F 行追加（label 空=续行，缩进对齐；value 空=整行跳过）。 */
    private static void addLocalityLine(List<String> lines, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (label == null || label.isBlank()) {
            lines.add("  · " + value.trim());
        } else {
            lines.add("- " + label + "：" + value.trim());
        }
    }

    /**
     * V2.0 判定输入装配（决策模型 判定统一门用，段 1+段 2+段 3 全量拼接为单串）。
     * <p>V2.0 规范化结构 §3.4：「对 决策模型 判定这类<b>单串判定输入</b>：段 1+段 2+段 3
     * 全量拼接为一个判定输入串」。顺序：</p>
     * <ol>
     * <li>段 1A【世界观】= {@link #worldBrief}（稳定 300-500 字，缓存友好）；</li>
     * <li>段 1B【系统指令】= {@link #SEG_1B_DECISION_INSTRUCTION}（判定引擎固定句）；</li>
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
        segs.add("【系统指令】\n" + SEG_1B_DECISION_INSTRUCTION);
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
        String constraint = spatialConstraintLine(projectId, characterRef);
        if (!constraint.isBlank()) {
            segs.add(constraint);
        }
        segs.add("【行为护栏】\n" + SEG_3I_BEHAVIOR_GUARD);
        segs.add("【输出契约】\n" + SEG_3J_OUTPUT_CONTRACT);
        return String.join("\n\n", segs).trim();
    }

    // ==================== 常驻前缀（跨场景稳定头 · 缓存命中率） ====================

    /**
     * 常驻前缀总开关（{@code holzyn.actor.context.stable-prefix}）。
     * <p>关掉即回到「契约 + 世界观」的两段头，用于对照实验。</p>
     */
    @Value("${holzyn.actor.context.stable-prefix:true}")
    private boolean stablePrefixEnabled;

    /** 名册单人预算（字符）：姓名/年龄/性别/身份/归属/常在/性格/说话风格，默认 150。 */
    @Value("${holzyn.actor.context.stable-person-chars:150}")
    private int stablePersonChars;

    /** 地点单条预算（字符）：名称（类型）：简介，默认 90。 */
    @Value("${holzyn.actor.context.stable-location-chars:90}")
    private int stableLocationChars;

    /** 民情常识预算（字符）：来自 {@code actor_world_setting.common_knowledge}，默认 600。 */
    @Value("${holzyn.actor.context.stable-custom-chars:600}")
    private int stableCustomChars;

    /** 常驻前缀缓存：projectId → [写入毫秒, 文本]；TTL 见 {@link #STABLE_PREFIX_TTL_MS}。 */
    private final Map<Long, String[]> stablePrefixCache = new ConcurrentHashMap<>();

    /** 常驻前缀缓存有效期（毫秒）：名册/地点改动后最迟 5 分钟生效。 */
    private static final long STABLE_PREFIX_TTL_MS = 300_000L;

    /**
     * 常驻前缀：本世界「跨场景、跨角色逐字一致」的一段文字，排在每个 AI 请求的最前面。
     *
     * <p><b>为什么需要它</b>：两游戏日实测（run3，3,188 次 deepseek-v4-flash 调用）
     * 命中率仅 32.35%。根因不是排版错，而是<b>连续稳定前缀只有约 780 token</b>
     * （共享契约 827 字符 + 世界设定 1,141 字符，若 system 段 370 字符也算入约 1,940 token），
     * 而服务端缓存以 2048 token 为块向下取整——头跨不过第一块，后续易变内容再多也不计。
     * 本方法把「全镇名册 + 地点 + 民情常识」这类本来就恒定、却散落在各场景正文里的内容
     * 前移到头里，把稳定前缀从 1 块推到 2-3 块。</p>
     *
     * <p><b>边界</b>：只放「世界级恒定」内容，绝不放时间/天气/在场者/当前状态等易变值——
     * 头里出现一个会变的字符，整段缓存即失效。缓存 5 分钟，覆盖名册/地点编辑。</p>
     *
     * @param projectId 项目 ID（可空）
     *
     * @return 常驻前缀文本（无数据/关闭/异常返回空串，调用方按空串降级）
     */
    public String stablePrefix(Long projectId) {
        if (!stablePrefixEnabled || projectId == null) {
            return "";
        }
        long now = System.currentTimeMillis();
        String[] cached = stablePrefixCache.get(projectId);
        if (cached != null && now - Long.parseLong(cached[0]) < STABLE_PREFIX_TTL_MS) {
            return cached[1];
        }
        String text;
        try {
            text = buildStablePrefix(projectId);
        } catch (Exception e) {
            log.debug("[上下文注入] 常驻前缀组装失败（降级为空）: project={} : {}", projectId, e.getMessage());
            text = "";
        }
        stablePrefixCache.put(projectId, new String[] {String.valueOf(now), text});
        return text;
    }

    /**
     * 组装常驻前缀：段头 + 人物名册 + 地点 + 民情常识（任一段为空则跳过该段）。
     *
     * @param projectId 项目 ID
     *
     * @return 常驻前缀文本（三段全空返回空串）
     */
    private String buildStablePrefix(Long projectId) {
        List<ActorWorldLocation> locations = loadLocations(projectId);
        List<ActorCharacter> characters = loadCharacters(projectId);
        Map<Long, String> locById = locationIndex(locations);
        String places = locationBlock(locations);
        String roster = rosterBlock(characters, locById, new java.util.HashSet<>(locById.values()));
        String kinship = kinshipBlock(projectId, characterNames(characters));
        String customs = customsBlock(projectId);
        if (roster.isBlank() && places.isBlank() && customs.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(6144);
        sb.append("—— 常驻设定（对这个世界恒定；任何场景都当作既定事实，不得改写、不得张冠李戴） ——");
        if (!roster.isBlank()) {
            sb.append('\n').append(roster);
        }
        if (!kinship.isBlank()) {
            sb.append('\n').append(kinship);
        }
        if (!places.isBlank()) {
            sb.append('\n').append(places);
        }
        if (!customs.isBlank()) {
            sb.append('\n').append(customs);
        }
        return sb.toString();
    }

    /**
     * 亲属关系段：只列血缘/婚姻/师徒这类<b>稳定</b>关系，不列好感/信任等每秒都在变的数值。
     *
     * <p><b>为什么只列亲属</b>：常驻前缀要逐字一致才有缓存价值，而 {@code affinity/trust}
     * 每次互动都变；全量关系（本项目 66 条）本身也会把稳定头撑得远超缓存块边界，
     * 多出来的部分既不被缓存、又摊薄命中率。只取「谁是谁的谁」这一层。</p>
     *
     * @param projectId 项目 ID
     *
     * @param rosterNames 名册姓名集合（只收双方都在册的关系）
     *
     * @return 亲属关系段（无数据返回空串）
     */
    private String kinshipBlock(Long projectId, java.util.Set<String> rosterNames) {
        if (relationRepository == null || projectId == null) {
            return "";
        }
        List<ActorCharacterRelation> rows;
        try {
            rows = relationRepository.findByProjectId(projectId);
        } catch (Exception e) {
            log.debug("[上下文注入] 亲属关系读取失败（跳过）: project={} : {}", projectId, e.getMessage());
            return "";
        }
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        String[] kinWords = {"夫妻", "夫", "妻", "父", "母", "子", "女", "兄", "弟", "姐", "妹",
            "爷", "奶", "祖", "孙", "叔", "伯", "姑", "姨", "舅", "侄", "甥", "婆", "媳", "婿", "亲"};
        int max = 15;
        StringBuilder sb = new StringBuilder("【亲属关系】（既定关系，不含当下好恶）");
        int n = 0;
        for (ActorCharacterRelation r : rows) {
            if (r == null || r.getFromName() == null || r.getToName() == null || r.getRelationType() == null) {
                continue;
            }
            String from = r.getFromName().trim();
            String to = r.getToName().trim();
            String type = r.getRelationType().trim();
            if (from.isEmpty() || to.isEmpty() || type.isEmpty()) {
                continue;
            }
            if (rosterNames != null && (!rosterNames.contains(from) || !rosterNames.contains(to))) {
                continue;
            }
            boolean kin = false;
            for (String w : kinWords) {
                if (type.contains(w)) {
                    kin = true;
                    break;
                }
            }
            if (!kin) {
                continue;
            }
            sb.append('\n').append("- ").append(from).append(" — ").append(to).append("：").append(type);
            if (++n >= max) {
                break;
            }
        }
        return n == 0 ? "" : sb.toString();
    }

    /**
     * 读取本项目已建档地点（按排序号 + ID 升序；失败返回空表）。
     *
     * @param projectId 项目 ID
     *
     * @return 地点列表（不返回 null）
     */
    private List<ActorWorldLocation> loadLocations(Long projectId) {
        if (worldLocationRepository == null || projectId == null) {
            return List.of();
        }
        try {
            List<ActorWorldLocation> list =
                    worldLocationRepository.findByProjectIdOrderBySortOrderAscIdAsc(projectId);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            log.debug("[上下文注入] 地点读取失败（跳过）: project={} : {}", projectId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 地点索引：ID → 名称，以及名称集合（判角色档案里的「所在地」是否可信）。
     *
     * @param locations 地点列表
     *
     * @return ID → 名称（不返回 null）
     */
    private Map<Long, String> locationIndex(List<ActorWorldLocation> locations) {
        Map<Long, String> index = new java.util.LinkedHashMap<>();
        if (locations == null) {
            return index;
        }
        for (ActorWorldLocation loc : locations) {
            if (loc != null && loc.getId() != null && notBlank(loc.getName())) {
                index.put(loc.getId(), loc.getName().trim());
            }
        }
        return index;
    }

    /**
     * 人物名册段：本世界在册角色每人一行，按 ID 升序（顺序固定=字节固定）。
     *
     * @param list 角色列表
     *
     * @param locationNames 地点名集合（判「所在地」是否可信）
     *
     * @return 名册段（无角色返回空串）
     */
    private String rosterBlock(List<ActorCharacter> list, Map<Long, String> locById, java.util.Set<String> locationNames) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【人物名册】（本世界在册角色；「性格/背景」是你判断他们言行举止的既定依据）");
        int n = 0;
        for (ActorCharacter ch : list) {
            if (ch == null || ch.getName() == null || ch.getName().isBlank()) {
                continue;
            }
            sb.append('\n').append("- ").append(rosterLine(ch, locById, locationNames));
            n++;
        }
        return n == 0 ? "" : sb.toString();
    }

    /**
     * 读取本项目在册角色（按 ID 升序；失败返回空表）。
     *
     * @param projectId 项目 ID
     *
     * @return 角色列表（不返回 null）
     */
    private List<ActorCharacter> loadCharacters(Long projectId) {
        if (characterRepository == null || projectId == null) {
            return List.of();
        }
        try {
            List<ActorCharacter> list = characterRepository.findByProjectIdOrderByIdAsc(projectId);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            log.debug("[上下文注入] 名册读取失败（跳过）: project={} : {}", projectId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 角色姓名集合（亲属关系段只收双方都在册的关系）。
     *
     * @param characters 角色列表
     *
     * @return 姓名集合（不返回 null）
     */
    private java.util.Set<String> characterNames(List<ActorCharacter> characters) {
        java.util.Set<String> names = new java.util.HashSet<>();
        if (characters == null) {
            return names;
        }
        for (ActorCharacter ch : characters) {
            if (ch != null && notBlank(ch.getName())) {
                names.add(ch.getName().trim());
            }
        }
        return names;
    }

    /**
     * 名册单人行：{@code 姓名，年龄，性别，身份，归属X，常在Y；性格：…；背景：…}。
     *
     * <p>「常在」只在档案所在地确实是已建档地点名时才出现——实测角色档案的 location 字段
     * 常被填成别人的名字或俗称（「常在铁匠老吴」），直接拼进名册会污染全员常识。</p>
     *
     * @param ch 角色实体
     *
     * @param locationNames 地点名集合
     *
     * @return 单行文本（按 {@code stable-person-chars} 截断）
     */
    private String rosterLine(ActorCharacter ch, Map<Long, String> locById, java.util.Set<String> locationNames) {
        List<String> head = new ArrayList<>(6);
        head.add(ch.getName().trim());
        if (ch.getAge() != null) {
            head.add(ch.getAge() + "岁");
        }
        if (notBlank(ch.getGender())) {
            head.add(ch.getGender().trim());
        }
        String role = notBlank(ch.getOccupation()) ? ch.getOccupation().trim()
                : (notBlank(ch.getTitle()) ? ch.getTitle().trim() : "");
        if (!role.isBlank()) {
            head.add(role);
        }
        if (notBlank(ch.getAffiliation())) {
            head.add("归属" + ch.getAffiliation().trim());
        }
        // 「常在」优先按 locationId 反查地名（可靠）；档案 location 字段常被填成别人的名字
        // （实测「常在铁匠老吴」），只有它确实是已建档地点名时才采信。
        String place = ch.getLocationId() == null ? null : locById.get(ch.getLocationId());
        if (place == null && notBlank(ch.getLocation()) && locationNames != null
                && locationNames.contains(ch.getLocation().trim())) {
            place = ch.getLocation().trim();
        }
        if (place != null && !place.isBlank()) {
            head.add("常在" + place);
        }
        StringBuilder sb = new StringBuilder(String.join("，", head));
        String traits = traitSummary(ch.getId());
        if (!traits.isBlank()) {
            sb.append("；性格：").append(traits);
        }
        // 背景只取静态档案（detail），不含好感/信任等易变数值——否则常驻前缀每 5 分钟变一次，
        // 缓存永远命中不了。
        String detail = backgroundLine(ch);
        if (!detail.isBlank()) {
            sb.append("；背景：").append(detail);
        }
        int budget = stablePersonChars <= 0 ? 200 : stablePersonChars;
        return clip(sb.toString(), Math.max(60, budget));
    }

    /**
     * 名册用的一句话背景。
     * <p>实测角色档案有两种形态：叙事段（「阿福是老陈机帆船上的帮手…」）与元数据段
     * （{@code 【角色档案锚定】陈阿伯（渡口码头船夫，机帆船船长） 身份：… 性别：…}）。
     * 后者直接截断会拼出「身份：机帆船船长 性别：男 种族：汉族 年龄：58」这类噪声，
     * 且与名册行已有的年龄/性别/身份重复——只取括号里那句身份概括。</p>
     *
     * @param ch 角色实体
     *
     * @return 一句话背景（缺失返回空串）
     */
    private String backgroundLine(ActorCharacter ch) {
        if (ch == null || !notBlank(ch.getDetail())) {
            return "";
        }
        String detail = ch.getDetail().replaceAll("\\s+", " ").trim();
        if (detail.contains("【角色档案锚定】")) {
            int lp = detail.indexOf('（');
            int rp = lp < 0 ? -1 : detail.indexOf('）', lp + 1);
            if (lp >= 0 && rp > lp) {
                return clip(detail.substring(lp + 1, rp).trim(), 60);
            }
        }
        return clip(detail, 170);
    }

    /**
     * 地点段：本世界已建档地点每条一行，按排序号 + ID 升序。
     *
     * @param list 地点列表
     *
     * @return 地点段（无地点返回空串）
     */
    private String locationBlock(List<ActorWorldLocation> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【地点】（本世界已建档地点）");
        int n = 0;
        for (ActorWorldLocation loc : list) {
            if (loc == null || loc.getName() == null || loc.getName().isBlank()) {
                continue;
            }
            StringBuilder line = new StringBuilder(loc.getName().trim());
            if (notBlank(loc.getType())) {
                line.append("（").append(loc.getType().trim()).append("）");
            }
            if (notBlank(loc.getIntro())) {
                line.append("：").append(loc.getIntro().trim());
            }
            int budget = stableLocationChars <= 0 ? 90 : stableLocationChars;
            sb.append('\n').append("- ").append(clip(line.toString(), Math.max(30, budget)));
            n++;
        }
        return n == 0 ? "" : sb.toString();
    }

    /**
     * 民情常识段：只取 {@code common_knowledge}（世界观段已含文化/历史/设定，不重复注入）。
     *
     * @param projectId 项目 ID
     *
     * @return 民情段（无常识返回空串）
     */
    private String customsBlock(Long projectId) {
        if (worldSettingRepository == null) {
            return "";
        }
        Optional<ActorWorldSetting> opt;
        try {
            opt = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId);
        } catch (Exception e) {
            log.debug("[上下文注入] 民情常识读取失败（跳过）: project={} : {}", projectId, e.getMessage());
            return "";
        }
        if (opt.isEmpty() || !notBlank(opt.get().getCommonKnowledge())) {
            return "";
        }
        int budget = stableCustomChars <= 0 ? 600 : stableCustomChars;
        return "【民情常识】\n" + clip(opt.get().getCommonKnowledge().trim(), Math.max(120, budget));
    }

    // ==================== 决策模型判定·精简装配（实跑事故） ====================

    /**
     * 精简装配总开关（{@code holzyn.actor.context.decision-compact}）。
     * <p>供判定门做「全局开关 ∧ 当前适配器是否本地打分模型」的与运算；形态由调用方
     * （{@code DecisionGate}）按实际适配器选择 {@link #decisionInputCompact} 或
     * {@link #decisionInput}。</p>
     *
     * @return true=允许精简（仍需调用方确认适配器类型）
     */
    public boolean decisionCompactEnabled() {
        return decisionCompact;
    }

    /**
     * 精简判定输入（决策模型 0.6B 判定层专用）。
     * <p><b>为什么精简</b>：全量装配给每个 state 前置「世界常识 700 字 + 角色卡 1500 字 +
     * 三段纪律」，12 states 批量请求达 20-34KB；实测 20KB 单请求 5s、34KB 120s 无响应，
     * 世界推进被拖成超时重试风暴。判定层真正需要的是「谁、此刻什么处境、要判什么」。</p>
     * <p>结构（单 state 约 300-500 字符）：
     * {@code 【世界】名称｜时代｜时刻} → {@code 【角色】姓名，年龄，身份，归属；性格：…；此刻：…}
     * → {@code 【情境】原 state} → {@code 【约束】一行}。</p>
     *
     * @param projectId 项目 ID（可空）
     *
     * @param characterRef 角色引用（{@code npc:{id}}；可空=只有世界行与情境）
     *
     * @param stateText 原判定输入（可空）
     *
     * @return 精简判定输入（全空时仍返回约束行，保证模型拿到输出纪律）
     */
    public String decisionInputCompact(Long projectId, String characterRef, String stateText) {
        List<String> segs = new ArrayList<>(4);
        String world = compactWorldLine(projectId);
        if (!world.isBlank()) {
            segs.add(world);
        }
        String role = compactCharacterLine(projectId, characterRef);
        if (!role.isBlank()) {
            segs.add(role);
        }
        String base = stateText == null ? "" : stateText.trim();
        if (!base.isBlank()) {
            segs.add("【情境】\n" + base);
        }
        String constraint = spatialConstraintLine(projectId, characterRef);
        if (!constraint.isBlank()) {
            segs.add(constraint);
        }
        segs.add("【约束】只依据情境中给出的事实判断，不臆测；按每个问题的判据给出概率。");
        return String.join("\n", segs).trim();
    }

    /**
     * 空间约束行（判定输入用）：把「在什么地方」翻译成「在这里能做什么、不能做什么」。
     *
     * <p><b>为什么判定输入需要它</b>：反事实实验（同角色、同目标、只换环境）
     * 测出 TVD 只有 2.4%~9.6%，卡方 p=0.76——环境出现了，但没改变选择。原因之一是判定输入里
     * 只有坐标和平铺叙述，没有「此处是别人家，进门要先敲门」这类可执行约束。这里把约束补上，
     * 并保持一句话、同一位置逐字稳定（可缓存）。</p>
     *
     * @param projectId    项目 ID（可空）
     *
     * @param characterRef 角色引用（可空）
     *
     * @return 约束段（空串=不注入）
     */
    private String spatialConstraintLine(Long projectId, String characterRef) {
        if (projectId == null || characterRef == null || characterRef.isBlank()
                || perceptionService == null) {
            return "";
        }
        try {
            String text = perceptionService.spatialConstraint(projectId, characterRef, null);
            return text == null ? "" : text.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 精简世界行：{@code 【世界】名称｜题材｜年代｜地域｜当前时刻}。
     * <p>判定层不需要百科式世界观；「哪个镇、什么年代、眼下什么时节」足以支撑处境判断。</p>
     * <p><b>为什么要"适配"而不是"截断"</b>（用户要求）：整段 clip 到 48 字会把
     * 「二〇〇三年夏，江南水乡，撤乡并镇之后的头一」这种半句喂给判定模型，语义不完整；
     * 改为按句读整项取用（{@link #firstClause}）+ 超预算整项丢弃（{@link #fitParts}），
     * 保证模型读到的是完整的「年代/地域/时刻」，宁可少一项也不给残句。</p>
     *
     * @param projectId 项目 ID（可空）
     *
     * @return 世界行（无数据返回空串）
     */
    private String compactWorldLine(Long projectId) {
        List<String> bits = new ArrayList<>(5);
        try {
            if (projectId != null && worldSettingRepository != null) {
                Optional<ActorWorldSetting> opt =
                        worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId);
                if (opt.isPresent()) {
                    ActorWorldSetting w = opt.get();
                    if (notBlank(w.getName())) {
                        bits.add(w.getName().trim());
                    }
                    if (notBlank(w.getGenre())) {
                        bits.add(w.getGenre().trim());
                    }
                    String era = firstClause(w.getEra(), 24);
                    if (!era.isBlank()) {
                        bits.add(era);
                    }
                    String geo = firstClause(w.getGeography(), 14);
                    if (!geo.isBlank()) {
                        bits.add(geo);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[上下文注入] 精简世界行组装失败（跳过）: project={} : {}", projectId, e.getMessage());
        }
        // 时刻/时节放最后：它是唯一会随时间变的部分，放尾部不影响前面几项的稳定性
        String dynamics = firstClause(worldDynamicsLine(projectId), 22);
        if (!dynamics.isBlank()) {
            bits.add(dynamics);
        }
        if (bits.isEmpty()) {
            return "";
        }
        int budget = decisionWorldChars <= 0 ? 48 : decisionWorldChars;
        return "【世界】" + fitParts(bits, Math.max(24, budget), "｜");
    }

    /**
     * 精简角色行：{@code 【角色】姓名，性别，年龄，身份，归属X；性格：3 个关键词；此刻：正在…，状态位}。
     * <p>保留「身份 + 性格关键词 + 当下生理/活动」，丢弃 11 维全量档案与核心记忆长段。</p>
     * <p><b>整项装填</b>（用户要求）：按优先级「姓名/性别/年龄/身份 → 归属 → 性格 → 此刻」
     * 逐项累加，超预算就整项跳过——不再把「米行掌柜」切成「米行掌」。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 角色引用（{@code npc:{id}}）
     *
     * @return 角色行（无数据返回空串）
     */
    private String compactCharacterLine(Long projectId, String characterRef) {
        if (projectId == null || characterRef == null || characterRef.isBlank() || characterRepository == null) {
            return "";
        }
        Long characterId = NpcRefs.idOf(characterRef);
        if (characterId == null) {
            return "";
        }
        try {
            ActorCharacter ch = characterRepository.findById(characterId).orElse(null);
            if (ch == null) {
                return "";
            }
            int budget = decisionCardChars <= 0 ? 220 : decisionCardChars;
            List<String> head = new ArrayList<>(4);
            head.add(notBlank(ch.getName()) ? ch.getName().trim() : "该角色");
            if (notBlank(ch.getGender())) {
                head.add(ch.getGender().trim());
            }
            if (ch.getAge() != null) {
                head.add(ch.getAge() + "岁");
            }
            if (notBlank(ch.getOccupation())) {
                head.add(ch.getOccupation().trim());
            } else if (notBlank(ch.getTitle())) {
                head.add(ch.getTitle().trim());
            }
            StringBuilder sb = new StringBuilder("【角色】").append(String.join("，", head));
            // 归属（低优先，放不下就丢）
            if (notBlank(ch.getAffiliation())) {
                appendWithin(sb, "，归属" + ch.getAffiliation().trim(), budget);
            }
            // 性格：只取前 3 个关键词（整词，不切字）
            String traits = traitKeywords(characterId, 3);
            if (!traits.isBlank()) {
                appendWithin(sb, "；性格：" + traits, budget);
            }
            // 此刻：正在做的事 + 状态位（各自的整句/整项）
            List<String> now = new ArrayList<>(2);
            if (notBlank(ch.getCurrentActivity())) {
                String act = firstClause(ch.getCurrentActivity(), 14);
                if (!act.isBlank()) {
                    now.add("正在" + act);
                }
            }
            String flags = firstClause(stateBits(projectId, characterRef).replace("【当前状态】", "").trim(), 24);
            if (!flags.isBlank()) {
                now.add(flags);
            }
            if (!now.isEmpty()) {
                appendWithin(sb, "；此刻：" + String.join("，", now), budget);
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("[上下文注入] 精简角色行组装失败（跳过）: ref={} : {}", characterRef, e.getMessage());
            return "";
        }
    }

    /**
     * 性格关键词：取「特质摘要」整行后按顿号/逗号切词，取前 {@code max} 个整词。
     *
     * @param characterId 角色 ID
     *
     * @param max 关键词个数
     *
     * @return 关键词串（缺失返回空串）
     */
    private String traitKeywords(Long characterId, int max) {
        String line = personaLine(characterId, "特质摘要：", 160);
        if (line.isBlank()) {
            return "";
        }
        List<String> words = new ArrayList<>(4);
        for (String w : line.split("[、，,；;]")) {
            String t = w.trim();
            if (!t.isEmpty()) {
                words.add(t);
            }
            if (words.size() >= Math.max(1, max)) {
                break;
            }
        }
        return String.join("、", words);
    }

    /**
     * 按预算追加一段：放得下才追加（整段取舍，绝不切断）。
     *
     * @param sb 目标缓冲
     *
     * @param seg 待追加段（自带前导标点）
     *
     * @param budget 字符预算
     */
    private static void appendWithin(StringBuilder sb, String seg, int budget) {
        if (seg == null || seg.isEmpty() || budget <= 0) {
            return;
        }
        if (sb.length() + seg.length() <= budget) {
            sb.append(seg);
        }
    }

    /**
     * 整项装填：按顺序累加各项，直到再加一项就超预算为止（不切断任何一项）。
     *
     * @param parts 候选项（已按优先级排序）
     *
     * @param budget 字符预算
     *
     * @param sep 分隔符
     *
     * @return 拼接结果（至少返回第一项）
     */
    private static String fitParts(List<String> parts, int budget, String sep) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(parts.get(0));
        for (int i = 1; i < parts.size(); i++) {
            String p = parts.get(i);
            if (p == null || p.isBlank()) {
                continue;
            }
            if (sb.length() + sep.length() + p.length() > budget) {
                break;
            }
            sb.append(sep).append(p);
        }
        return sb.toString();
    }

    /**
     * 取整句：按句读（。；！？，、）切分，累加整句直到超过 {@code max} 字符。
     * <p>用于「适配而非截断」——返回的永远是完整的句子片段，不会是半句。</p>
     *
     * @param text 原文（可空）
     *
     * @param max 字符上限
     *
     * @return 整句片段（原文为空返回空串）
     */
    private static String firstClause(String text, int max) {
        if (text == null) {
            return "";
        }
        String s = text.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) {
            return "";
        }
        if (max <= 0 || s.length() <= max) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        for (String frag : s.split("(?<=[。；！？，、])")) {
            String f = frag.trim();
            if (f.isEmpty()) {
                continue;
            }
            if (sb.length() + f.length() > max) {
                break;
            }
            sb.append(f);
        }
        if (sb.length() == 0) {
            // 整段没有句读（如英文名/无标点活动描述）：取第一段空白词，仍然不切字
            int sp = s.indexOf(' ');
            return sp > 0 && sp <= max ? s.substring(0, sp) : s;
        }
        return sb.toString().trim();
    }

    /**
     * 性格关键词摘要：从精简分片档案（场景 {@code decision_compact}）取「特质摘要」一行。
     * <p>判定层只吃性格关键词（寡言/守时/怕水…），不需要 OCEAN 数值与十维细节。</p>
     *
     * @param characterId 角色 ID
     *
     * @return 特质摘要（缺失返回空串）
     */
    private String traitSummary(Long characterId) {
        return personaLine(characterId, "特质摘要：", 40);
    }

    /**
     * 从精简分片档案（场景 {@code decision_compact}）取某一行特征文本。
     * <p>供性格关键词与说话风格共用：名册只要一两行，不要 11 维全量。</p>
     *
     * @param characterId 角色 ID
     *
     * @param key 行首标签（如「特质摘要：」「言语风格：」）
     *
     * @param max 字符上限
     *
     * @return 该行内容（缺失返回空串）
     */
    private String personaLine(Long characterId, String key, int max) {
        if (personaService == null || characterId == null || key == null || key.isEmpty()) {
            return "";
        }
        try {
            String text = personaService.renderShardsForScene(characterId, "decision_compact");
            if (text == null || text.isBlank()) {
                return "";
            }
            int idx = text.indexOf(key);
            if (idx < 0) {
                return "";
            }
            String rest = text.substring(idx + key.length());
            int end = rest.indexOf('\n');
            if (end < 0) {
                end = rest.length();
            }
            return clip(rest.substring(0, end).trim(), max);
        } catch (Exception e) {
            log.debug("[上下文注入] 特征行读取失败（跳过）: id={} key={} : {}", characterId, key, e.getMessage());
            return "";
        }
    }

    /**
     * 按字符预算截断（句读优先：在最后一个「；，、。」处收口，避免半句进 Prompt）。
     *
     * @param text 原文
     *
     * @param max  字符上限（<=0 直接返回原文）
     *
     * @return 截断后文本
     */
    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        if (max <= 0 || text.length() <= max) {
            return text;
        }
        String head = text.substring(0, max);
        int cut = Math.max(Math.max(head.lastIndexOf('；'), head.lastIndexOf('，')),
                Math.max(head.lastIndexOf('、'), head.lastIndexOf('。')));
        return cut >= max / 2 ? head.substring(0, cut + 1) : head;
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
     * 单聊/群聊共用同一套护栏（第一人称 / 不替他人发言 / 只回复一轮 / 不跳出角色 /
     * 认知边界以世界时代为限），落在触发消息末尾（会话历史之后的高权重位）。</p>
     *
     * @param name 角色名（可空=用「你」）
     *
     * @return 段 3I 文本
     */
    public static String dialogueGuardRail(String name) {
        String who = name == null || name.isBlank() ? "你" : name;
        return "以【" + who + "】的第一人称说话，只说你自己的话：\n"
                + "1. 不替其他角色（或玩家）发言、不代答、不转述或标注其他角色的台词；\n"
                + "2. 只回复这一轮，不要替对方做决定，不要跳出角色；\n"
                + "3. 你的见识以这个世界和这个时代为限：凡此处尚不存在的东西，你都没见过也没听过，"
                + "不能声称「见过／晓得／听人说过」，更不能指认它的本地来历——如实表现出不解，"
                + "或拿你熟悉的东西作比；但不要每次都用同一句话搪塞（别反复说「没听说过」「头一回听说」），"
                + "换着法子说不认得，或用你熟悉的东西打个比方；\n"
                + "4. 对方告诉你的、关于你自己或你亲人的新消息，只要当场没有确凿反证，就要当真："
                + "先做出反应，并在之后的话里按它行事，不能装作没听见、也不能直接回到旧说法；\n"
                + "5. 同一个问题被再问一次时，不能把先前的答复原样再背一遍——要么给新的细节，"
                + "要么承认自己方才说得不清楚、把话往深里说一层；\n"
                + "6. 不要每一轮都用同一套动作或神态开场（例如每次都「拇指摩挲铜印系绳」）："
                + "手上的动作、看的地方、语气的轻重都要跟着眼前这件事变，没动作时就直说。";
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
