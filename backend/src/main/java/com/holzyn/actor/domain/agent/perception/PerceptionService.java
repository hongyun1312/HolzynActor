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

package com.holzyn.actor.domain.agent.perception;

import com.holzyn.actor.ai.KnowledgeHit;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.agent.event.EventQuery;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.learning.RelationDynamicsService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.npc.state.service.NpcActivityStateService;
import com.holzyn.actor.domain.knowledge.service.KnowledgeRetrievalService;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 感知上下文服务（B2 感知组件，domain/agent/perception）。
 * <p>职责：为一切「以角色视角」的认知（决策/对话/行动）组装<b>视角化的世界状态</b>——
 * 修 L-04「决策是盲的」（只有自身状态+真实时间，看不到世界）：
 * ① 游戏时间（统一时间口径，WorldClockService，不再用真实时间）；
 * ② 自身状态（特殊=npc:{id} 的 current_activity+location / 普通=npc:{id} 的 state+last_action+location；
 * 引用不再编码类型，由 NpcTypeResolver 查 actor_profile.type 分流）；
 * ③ 可见近期事件 ≤5（经 {@link WorldEventBus#query} + characterRef 可见性过滤——B1 已落地，
 * 感知不是全知：global 全可见 / private 仅当事人 / location 对当事人或同地点在场者）；
 * ④ 关系摘要（该角色参与的社会关系 ≤5）。</p>
 * <p>不包含在场者聚合与记忆提示（在场者留 在场登记；记忆由 MemoryService.memoryContext
 * 独立注入避免重复）。接入点：ActionEngine 决策 situation（替换 LocalDateTime.now()，修 L-07）、
 * ChatService/GroupChatService 对话【当前所见】段（开关 holzyn.actor.perception.enabled 默认 true）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PerceptionService {

    /** 可见事件上限（≤5，设计 §7.2） */
    private static final int MAX_VISIBLE_EVENTS = 5;
    /** 关系摘要上限 */
    private static final int MAX_RELATIONS = 5;
    /** 情境知识片段上限（，topK=3） */
    private static final int MAX_KNOWLEDGE = 3;
    /** 情境知识单条长度上限（截断防撑爆 Prompt） */
    private static final int KNOWLEDGE_TEXT_MAX = 200;

    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRepository npcRepository;
    private final ActorCharacterRelationRepository relationRepository;
    private final WorldClockService worldClockService;
    private final WorldEventBus worldEventBus;

    /**
     * NPC 类型解析器（注入：npc:{id} 不再编码 special/common——自身状态取字段口径
     * [普通型 state/last_action vs 特殊型 current_activity] 与角色级知识并入都属「只有该类型才做」
     * 的分支，类型必须查 actor_profile.type；Spring 自动装配）
     */
    private final NpcTypeResolver npcTypeResolver;

    /** 规划组件（注入：感知【当前目标】/【今日计划】段；字段注入避免循环依赖改构造器） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.thinking.PlanningService planningService;

    /** 知识检索服务（注入：感知附带情境知识片段；字段注入保持构造器不变，存量单测零改动） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private KnowledgeRetrievalService knowledgeRetrievalService;

    /** 当前用户解析（注入：知识检索 embedding 凭据归属；字段注入保持构造器不变） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.account.CurrentUserProvider currentUserProvider;

    /**
     * L1 工作记忆服务（注入：感知快照/情境知识上下文工作项写入 + 【当前情境】热缓冲注入；
     * 字段注入保持构造器不变，存量单测零改动）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.memory.service.MemoryWorkingService memoryWorkingService;

    /**
     * L3 核心记忆服务（注入：感知/决策注入【核心记忆】恒在段首部——设计 §9.2/§9.3；
     * 字段注入保持构造器不变，存量单测零改动）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.memory.service.CoreMemoryService coreMemoryService;

    /** 沙盘状态服务（S2 npc:thought T1 感知源：感知产物→想法气泡；字段注入保持构造器不变） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.sandbox.service.WorldSandboxStateService sandboxStateService;

    /** NPC 状态服务 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.npc.state.service.NpcStateService npcStateService;

    /**
     * 世界地图只读门面（M3 感知联动：坐标 → 区域反查/八方位/路程口语化——感知段
     * 【当前位置与方向】的纯函数数据源；字段注入保持构造器不变，null=未装配跳过空间段）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.worldmap.service.WorldMapQueryService worldMapQueryService;

    /**
     * 连续移动状态机（M3 感知联动：在途移动行读取——感知段【当前位置与方向】移动中
     * 描述「正走在去医馆的路上」；字段注入保持构造器不变，null=未装配跳过在途描述）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.worldmap.service.MovementService movementService;

    /**
     * 沙盘状态仓库（M3 感知联动：当前坐标读取——感知段【当前位置与方向】驻留/移动中
     * 的坐标来源；字段注入保持构造器不变，null=未装配跳过空间段）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.sandbox.repository.ActorSandboxStateRepository sandboxStateRepository;

    /**
     * NPC 作息运行态访问器。
     * <p><b>为什么必须有它</b>：普通型角色的 {@code state} / {@code last_action} 两个运行态字段已随
     * {@code actor_profile_ordinary} 物理废弃迁入 {@code actor_state}
     * （{@code activity_state} / {@code last_action}，运行态唯一家）——主表实体 {@link ActorCharacter}
     * 上再无这两个字段，"普通型取 state/last_action"的取字段口径必须改为读运行态。</p>
     * <p>字段注入（非 final）保持既有构造器签名不变（存量单测零改动）；{@code null}=未装配 →
     * 回退默认态（{@code 空闲}），零阻断。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NpcActivityStateService npcActivityStateService;

    /**
     * 信息感知三层过滤服务（注入：决策/对话【世界动态】段——该 NPC 当前可感知的信息包，
     * 空间→社会→认知过滤 + 失真文本；字段注入保持构造器不变，存量单测零改动）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.world.service.InfoPerceptionService infoPerceptionService;

    /**
     * 注入：此刻可发生空间段（决策/对话全量注入【发育阶段约束】+【此刻可发生空间】——
     * 感知是唯一漏斗（WorldEngine 周期决策/即时决策/单聊/群聊/自主对话全走本方法）；
     * 字段注入保持构造器不变，存量单测零改动，null=跳过）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.space.GrowthSpaceService growthSpaceService;

    /**
     * 注入：社会面具双段（【内心与表达】段——真实 PAD 与表达 PAD 分离可见 + 当前情境/
     * 抑制/允许/禁止；在空间段后，所有消费场景经唯一漏斗获得；字段注入保持构造器不变，
     * 存量单测零改动，null=跳过）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.mask.MaskService maskService;

    /** 【内心与表达】段注入开关 */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.growth.mask.inject-dual-segment:true}")
    private boolean injectMaskDualSegment;

    /**
     * 统一环境服务（时间季节系统 / 注入：感知/对话【当前环境】段置顶——季节/时段/天气/
     * 体感/环境描述；字段注入保持构造器不变，存量单测零改动，null=跳过）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.world.service.EnvironmentContextService environmentContextService;

    /**
     * 宏观社会演化系统 注入：感知/对话【宏观环境】段（回灌路④ 行为参数联动 v1
     * ——粮价/局势/舆论只读摘要；字段注入保持构造器不变，存量单测零改动，null=跳过）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.world.service.MacroContextService macroContextService;

    /**
     * 注入：感知/对话【组织处境】段（该角色所属组织的近期指令——同一宏观事件不同组织
     * 成员读到不同的组织处境；字段注入保持构造器不变，存量单测零改动，null=跳过）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.world.service.MesoDirectiveRouter mesoDirectiveRouter;

    /**
     * 生计压力段注入开关（默认 true；false=不注入【生计压力】段——粮价→生计处境描述，
     * 特殊 NPC 决策/对话自然引用，行为参数联动 v2）
     */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.macro.livelihood-inject:true}")
    private boolean injectLivelihood;

    /** L1 感知快照/上下文工作项写入开关（默认 true；false=感知不写 L1，仅注入已有工作项） */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.memory.working.perception-enabled:true}")
    private boolean workingPerceptionEnabled;

    /** 感知注入目标/计划段总开关（默认 true；false=感知不注入【当前目标】/【今日计划】，行为与 一致） */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.perception.goals-enabled:true}")
    private boolean goalsEnabled;

    /** 感知附带情境知识片段开关（默认 true；false=感知不检索/不注入知识，行为与 一致） */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.perception.knowledge-enabled:true}")
    private boolean knowledgeEnabled;

    /** 决策注入开关 */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.npc-state.inject.inject-ai-decision:true}")
    private boolean injectAiDecision;

    /** 对话注入开关（默认 true；false=对话不注入【当前状态】段） */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.npc-state.inject.inject-dialog:true}")
    private boolean injectDialog;

    /** 决策/感知【当前环境】段注入开关（默认 true；false=决策感知不注入环境段） */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.environment.inject-perception:true}")
    private boolean injectEnvironmentPerception;

    /** 对话【当前环境】段注入开关（默认 true；false=对话不注入环境段） */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.environment.inject-dialogue:true}")
    private boolean injectEnvironmentDialogue;

    /**
     * 组装角色感知上下文。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用（npc:{id}）
     *
     * @param gameSecond 游戏秒（可空=当前世界时钟）
     *
     * @return 感知视图；项目/角色引用非法返回 null
     */
    public PerceptionVO buildPerception(Long projectId, String characterRef, Long gameSecond) {
        if (projectId == null || characterRef == null || characterRef.isBlank()) {
            return null;
        }
        // 注意：避免三元表达式 long/Long 混合分支（null 拆箱 NPE）——显式 if 处理
        Long gameHour;
        if (gameSecond != null) {
            gameHour = gameSecond / 3600L;
        } else {
            gameHour = worldClockService.currentGameHour(projectId);
        }
        String gameTimeText = worldClockService.gameTimeTextOf(projectId, gameHour);
        OwnState own = resolveOwnState(projectId, characterRef);
        List<String> events = visibleEvents(projectId, characterRef, own.locationId());
        List<String> relations = relationSummary(projectId, characterRef, own.name());
        // ：目标/计划摘要（开关控制；crowd 只注入计划——普通 NPC 无目标表）
        String goals = "";
        String plan = "";
        if (goalsEnabled && planningService != null) {
            goals = planningService.goalsSummary(projectId, characterRef);
            plan = planningService.planSummary(projectId, characterRef, gameHour);
        }
        // ：情境知识片段（相关世界知识 ≤3；开关控制；crowd 只检项目级知识）
        List<String> knowledge = knowledgeHints(projectId, characterRef, own);
        return new PerceptionVO(gameHour, gameTimeText, own.location(), own.state(), events, relations, goals, plan, knowledge);
    }

    /**
     * 组装感知文本（可直接注入 system 消息；无可注入内容返回空串）。
     * <p>感知注入组合 = 【核心记忆】L3 恒在段（首部）+ 【当前状态】段 +
     * 感知视图 + 【当前情境】L1 段（设计 §9.3：感知注入含 L3 核心摘要）；决策/引擎
     * （DecisionService/WorldEngine）经本方法获得 L3 与状态。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用
     *
     * @param gameSecond 游戏秒（可空=当前）
     *
     * @return 【当前所见】文本；非法/空返回空串
     */
    public String buildPerceptionText(Long projectId, String characterRef, Long gameSecond) {
        return buildPerceptionText(projectId, characterRef, gameSecond, true, injectAiDecision);
    }

    /**
     * 组装感知文本（可指定是否注入 L3 核心段）。
     * <p>includeCore=false：对话场景专用——对话的【核心记忆】已由记忆段（memoryContext/ByRef）注入，
     * 感知段不再重复（避免恒在段双份，token 预算保护）；true=决策/感知/引擎场景（无记忆段，L3 必须经感知注入）。
     * 状态段按决策开关 inject-ai-decision 注入（对话请用 {@link #buildDialogPerceptionText}）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用
     *
     * @param gameSecond 游戏秒（可空=当前）
     *
     * @param includeCore true=注入【核心记忆】L3 恒在段（默认）；false=跳过（对话去重）
     *
     * @return 【当前所见】文本；非法/空返回空串
     */
    public String buildPerceptionText(Long projectId, String characterRef, Long gameSecond, boolean includeCore) {
        return buildPerceptionText(projectId, characterRef, gameSecond, includeCore, injectAiDecision);
    }

    /**
     * 组装感知文本（可指定是否注入 L3 核心段与【当前状态】段）。
     * <p>注入顺序：<b>【核心记忆】→【当前状态】→【当前所见】→【当前情境】L1</b>
     * （设计 §8.1：状态段在核心后、当前所见前；决策/对话共用全量段）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用
     *
     * @param gameSecond 游戏秒（可空=当前）
     *
     * @param includeCore true=注入【核心记忆】L3 恒在段；false=跳过（对话去重）
     *
     * @param includeState true=注入【当前状态】段；false=跳过
     *
     * @return 【当前所见】文本；非法/空返回空串
     */
    public String buildPerceptionText(Long projectId, String characterRef, Long gameSecond,
                                      boolean includeCore, boolean includeState) {
        return buildPerceptionText(projectId, characterRef, gameSecond, includeCore, includeState, null);
    }

    /**
     * 感知文本组装主方法（带面具对象参数： ——决策走 null[无社交对象→仅场合面具]；
     * 对话/群聊传对象引用列表[玩家会话传空列表=外来者]）。
     *
     * @param partnerRefs 在场者/对话对象（null=独处/决策；空列表=有非角色对象在场[玩家]）
     */
    public String buildPerceptionText(Long projectId, String characterRef, Long gameSecond,
                                      boolean includeCore, boolean includeState, List<String> partnerRefs) {
        PerceptionVO vo = buildPerception(projectId, characterRef, gameSecond);
        // S2 沙盘 npc:thought（T1 感知源）：感知产物 → 想法气泡（有可见事件=「注意到」；
        // 无事件=「身处/状态」；去重由 publishThought 负责，失败静默不阻断感知主流程）
        publishPerceptionThought(projectId, characterRef, vo, gameSecond);
        String base = PerceptionText.render(vo);
        // L3 恒在注入：感知注入组合首部【核心记忆】段（设计 §9.3；决策/引擎经此获得 L3）
        String core = includeCore ? coreSegment(projectId, characterRef) : "";
        // 【当前状态】段：核心后、当前所见前（决策/对话共用全量段，§8.1）
        String state = includeState ? stateSegment(projectId, characterRef, gameSecond) : "";
        // 【世界动态】段：状态后、当前所见前（主方案 §4.4 注入顺序：核心 → 状态 → 世界动态 →
        // 当前所见 → L1）——该 NPC 当前可感知的信息包（三层过滤 + 失真文本，≤3 条 ≤60 字）
        String dynamics = worldDynamicsSegment(projectId, characterRef, gameSecond);
        // 宏观社会演化系统：【宏观环境】段（回灌路④ 行为参数联动 v1——粮价/
        // 局势/舆论只读摘要；紧随【世界动态】后、当前所见前；零阻断失败跳过）
        String macro = macroDynamicsSegment(projectId);
        // ：【生计压力】段（行为参数联动 v2——粮价→生计处境/民怨/收成，
        // 特殊 NPC AI 决策自然引用；紧随【宏观环境】后；零阻断失败跳过）
        String livelihood = injectLivelihood ? livelihoodSegment(projectId) : "";
        // ：【组织处境】段（该角色所属组织的近期指令摘要——同一宏观事件不同组织成员
        // 读到不同的组织处境；紧随【生计压力】后、当前所见前；零阻断失败跳过）
        String meso = mesoDirectiveRouter == null ? "" : mesoSegment(projectId, characterRef, gameSecond);
        // L1 工作记忆：① 写当前感知快照/情境知识上下文工作项（单槽替换防膨胀）；
        // ② 追加【当前情境】热缓冲段（设计 §9.3：感知注入含 L1 段）
        writeWorkingSnapshots(projectId, characterRef, vo);
        String working = workingSegment(projectId, characterRef);
        // 时间季节系统：【当前环境】段置顶（设计 §5.3 感知段——时间+环境状态
        // 文本+sensory；决策 includeCore=true 走 inject-perception 开关，对话 includeCore=false
        // 走 inject-dialogue 开关）；环境段由统一环境服务确定性计算+润色缓存读取，零同步 AI
        String environment = includeCore
                ? environmentSegment(projectId, characterRef, gameSecond, injectEnvironmentPerception)
                : environmentSegment(projectId, characterRef, gameSecond, injectEnvironmentDialogue);
        StringBuilder sb = new StringBuilder();
        if (environment != null && !environment.isBlank()) {
            sb.append(environment);
        }
        // 位置移动与地图生成系统 · M3：【当前位置与方向】段（置顶——紧跟环境段后、
        // 核心记忆段前；感知是唯一漏斗：决策/对话/引擎全量注入）。驻留 →「在村西口」；
        // 移动中 →「正走在去医馆的路上（村西→村中，约百余步）」；途中遭遇 →「迎面遇到李掌柜」。
        // 纯只读（WorldMapQueryService 纯函数 + 沙盘坐标），零 AI；失败零阻断跳过。
        String spatial = spatialSegment(projectId, characterRef, gameSecond);
        if (spatial != null && !spatial.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(spatial);
        }
        if (core != null && !core.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(core);
        }
        if (state != null && !state.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(state);
        }
        if (dynamics != null && !dynamics.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(dynamics);
        }
        if (macro != null && !macro.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(macro);
        }
        if (livelihood != null && !livelihood.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(livelihood);
        }
        if (meso != null && !meso.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(meso);
        }
        if (base != null && !base.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(base);
        }
        if (working != null && !working.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(working);
        }
        // ：【发育阶段约束】+【此刻可发生空间】段（决策/对话全量注入——
        // 「所有消费场景全量注入」；只读渲染（有效空间直接渲染，缺失/过期走程序化块），
        // 绝不因注入触发 AI 生成；失败零阻断）
        if (growthSpaceService != null && characterRef != null && characterRef.startsWith("npc:")) {
            try {
                String space = growthSpaceService.spaceSegment(projectId, characterRef, gameSecond);
                if (space != null && !space.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(space);
                }
            } catch (Exception e) {
                log.warn("[感知] 可发生空间段注入失败（跳过，零阻断）: ref={} : {}", characterRef, e.getMessage());
            }
        }
        // ：【内心与表达】双段注入（真实 PAD 与表达 PAD 分离可见 + 当前面具
        // 情境/抑制/允许/禁止；表达入口现算并写回 actor_state.expressed_*；在空间段后——
        // 所有消费场景（决策/单聊/群聊/自主对话）经唯一漏斗获得；失败零阻断）
        if (injectMaskDualSegment && maskService != null && characterRef != null && characterRef.startsWith("npc:")) {
            try {
                String dual = maskService.dualSegment(projectId, characterRef, gameSecond, partnerRefs, null);
                if (dual != null && !dual.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(dual);
                }
            } catch (Exception e) {
                log.warn("[感知] 内心与表达段注入失败（跳过，零阻断）: ref={} : {}", characterRef, e.getMessage());
            }
        }
        return sb.toString().trim();
    }

    /**
     * 组装对话专用感知文本：对话的【核心记忆】已由记忆段（memoryContext/ByRef）注入
     * （includeCore=false 防双份），状态段按对话开关 inject-dialog 注入【当前状态】全量段。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用
     *
     * @param gameSecond 游戏秒（可空=当前）
     *
     * @return 对话感知文本；非法/空返回空串
     */
    public String buildDialogPerceptionText(Long projectId, String characterRef, Long gameSecond) {
        return buildPerceptionText(projectId, characterRef, gameSecond, false, injectDialog, null);
    }

    /**
     * 带面具对象参数的对话感知文本（：群聊传在场成员、单聊传空列表[玩家=外来者]，
     * 使【内心与表达】段按真实社交情境计算；null=按独处处理）。
     *
     * @param partnerRefs 在场者/对话对象引用列表（空列表=有非角色对象在场[玩家]）
     *
     * @return 对话感知文本；非法/空返回空串
     */
    public String buildDialogPerceptionText(Long projectId, String characterRef, Long gameSecond,
                                            List<String> partnerRefs) {
        return buildPerceptionText(projectId, characterRef, gameSecond, false, injectDialog, partnerRefs);
    }

    /**
     * S2 沙盘 npc:thought（T1 感知源）：把感知视图转短句写入角色想法气泡。
     * <p>组合规则：有可见事件 → 「我注意到：{首条事件}」；无事件 → 「我{ownState}。」
     * （ownState 空白则跳过）；去重由 {@code WorldSandboxStateService.publishThought} 负责
     * （内容未变不广播，防感知热路径刷屏）；失败静默不阻断感知主流程。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用（npc:{id}）
     *
     * @param vo 感知视图
     *
     * @param gameSecond 游戏秒（可空）
     */
    private void publishPerceptionThought(Long projectId, String characterRef, PerceptionVO vo, Long gameSecond) {
        if (sandboxStateService == null || projectId == null || characterRef == null || vo == null) {
            return;
        }
        try {
            String text;
            String source;
            if (vo.visibleEvents() != null && !vo.visibleEvents().isEmpty()) {
                String first = vo.visibleEvents().get(0);
                String t = first == null ? "" : first.trim();
                text = "我注意到：" + (t.length() > 60 ? t.substring(0, 60) + "…" : t);
                source = "perception";
            } else if (vo.ownState() != null && !vo.ownState().isBlank()) {
                String s = vo.ownState().trim();
                text = "我" + s + "。";
                source = "derived";
            } else {
                return;
            }
            if (text.length() > 255) {
                text = text.substring(0, 255);
            }
            sandboxStateService.publishThought(projectId, characterRef, text, source, gameSecond);
        } catch (Exception e) {
            log.debug("[感知] 沙盘想法写入失败（静默）: ref={} : {}", characterRef, e.getMessage());
        }
    }

    /**
     * L3：取该角色【核心记忆】恒在段（静态基线 + 核心块；无内容/开关关返回空串）。
     */
    private String coreSegment(Long projectId, String characterRef) {
        if (coreMemoryService == null || projectId == null || characterRef == null || characterRef.isBlank()) {
            return "";
        }
        try {
            return coreMemoryService.renderCoreSegment(projectId, characterRef);
        } catch (Exception e) {
            log.warn("[感知] L3 核心记忆注入失败（跳过）: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 【当前状态】段（§8.1，只读注入）：委托 {@link NpcStateService#stateSegment} 渲染
     * （惰性建行 + 衰减补偿 + 需求/心情/情绪/紧急）；无内容/服务未装配返回空串。
     */
    private String stateSegment(Long projectId, String characterRef, Long gameSecond) {
        if (npcStateService == null || projectId == null || characterRef == null || characterRef.isBlank()) {
            return "";
        }
        try {
            return npcStateService.stateSegment(projectId, characterRef, gameSecond);
        } catch (Exception e) {
            log.warn("[感知] 【当前状态】段注入失败（跳过）: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 【世界动态】段（主方案 §4.4，只读注入）：委托 {@link InfoPerceptionService#worldDynamicsSegment}
     * 渲染——该 NPC 当前可感知的信息包（空间→社会→认知三层过滤 + 失真文本，≤3 条 ≤60 字，预算内）；
     * 无内容/服务未装配/异常返回空串（静默降级不阻断感知主流程）。
     */
    private String worldDynamicsSegment(Long projectId, String characterRef, Long gameSecond) {
        if (infoPerceptionService == null || projectId == null || characterRef == null || characterRef.isBlank()) {
            return "";
        }
        try {
            return infoPerceptionService.worldDynamicsSegment(projectId, characterRef, gameSecond);
        } catch (Exception e) {
            log.warn("[感知] 【世界动态】段注入失败（跳过）: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 宏观社会演化系统 【宏观环境】段（回灌路④ 行为参数联动 v1，只读注入）：委托
     * {@link MacroContextService#macroDynamicsSegment} 渲染——粮价/局势/舆论只读摘要，
     * 决策与对话可自然引用（「粮价涨了，去集市看看行情」）；服务未装配/异常返回空串
     * 。
     */
    private String macroDynamicsSegment(Long projectId) {
        if (macroContextService == null || projectId == null) {
            return "";
        }
        try {
            return macroContextService.macroDynamicsSegment(projectId);
        } catch (Exception e) {
            log.warn("[感知] 【宏观环境】段注入失败（跳过）: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 【组织处境】段（回灌个体化，只读注入）：委托 {@link MesoDirectiveRouter#composePerceptionSegment}
     * 渲染——该角色所属组织的角色 + 组织近期指令摘要（同一宏观事件不同组织成员读到不同的组织
     * 处境）；服务未装配/异常返回空串。
     */
    private String mesoSegment(Long projectId, String characterRef, Long gameSecond) {
        if (mesoDirectiveRouter == null || projectId == null || characterRef == null) {
            return "";
        }
        try {
            return mesoDirectiveRouter.composePerceptionSegment(projectId, characterRef, gameSecond);
        } catch (Exception e) {
            log.warn("[感知] 【组织处境】段注入失败（跳过）: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 宏观社会演化系统 【生计压力】段（行为参数联动 v2，只读注入）：委托
     * {@link MacroContextService#livelihoodSegment} 渲染——粮价→生计处境/民怨/收成，
     * 特殊 NPC AI 决策自然引用（「米价贵，得省着点吃」）；服务未装配/异常返回空串。
     */
    private String livelihoodSegment(Long projectId) {
        if (macroContextService == null || projectId == null) {
            return "";
        }
        try {
            return macroContextService.livelihoodSegment(projectId);
        } catch (Exception e) {
            log.warn("[感知] 【生计压力】段注入失败（跳过）: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 时间季节系统 【当前环境】段（设计 §5.3 感知段注入，置顶）：委托统一环境服务
     * {@link EnvironmentContextService#injectionSegment} 渲染——「【当前环境】冬日傍晚，天已经全黑了，
     * 北风裹着雪粒…（体感 −9℃，日落 17:30）」；服务未装配/开关关/异常返回空串（静默降级，
     * 决策/对话行为与 前一致）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 角色引用（仅日志用）
     *
     * @param gameSecond 游戏秒（可空=当前）
     *
     * @param enabled 注入开关（决策/感知 vs 对话分开关）
     *
     * @return 环境段文本（空串=不注入）
     */
    private String environmentSegment(Long projectId, String characterRef, Long gameSecond, boolean enabled) {
        if (!enabled || environmentContextService == null || projectId == null) {
            return "";
        }
        try {
            return environmentContextService.injectionSegment(projectId, gameSecond, 120);
        } catch (Exception e) {
            log.warn("[感知] 【当前环境】段注入失败（跳过）: ref={} : {}", characterRef, e.getMessage());
            return "";
        }
    }

    /**
     * 位置移动与地图生成系统 · M3：【当前位置与方向】段（置顶，只读注入）。
     * <p>职责（开发路径 §4.5 ①）：把「我在哪/正去哪」口语化注入感知——<b>永不出现精确坐标</b>
     * （对齐用户「像真人一样无需知道准确坐标」，方位计算零 AI）。</p>
     * <ul>
     * <li><b>驻留</b>：`在村口石牌坊（村西口，进村第一景）`——经 {@code WorldMapQueryService.regionAt}
     *     坐标反查区域口语描述（regionDesc 优先，无则地点名）；</li>
     * <li><b>移动中</b>：`正走在去医馆的路上（村西→村中，约百余步）`——读 MovementService 在途行
     *     （from/to/目标地点名），方位用八方位、路程用步/里口语化；</li>
     * <li><b>途中遭遇</b>：`迎面遇到李掌柜，点头致意`——移动中经 {@code WorldMapQueryService.nearbyRefs}
     *     查询附近角色（§4.6 途中可见，互为可见——对方感知同样看到移动者）。</li>
     * </ul>
     * <p>数据源：沙盘状态行坐标（posX/posZ 权威）+ WorldMapQueryService 纯函数；服务未装配/
     * 无坐标/异常返回空串（静默降级不阻断感知主流程）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用（npc:{id}）
     *
     * @param gameSecond 游戏秒（可空）
     *
     * @return 空间段文本（空串=不注入）
     */
    private String spatialSegment(Long projectId, String characterRef, Long gameSecond) {
        if (worldMapQueryService == null || movementService == null || sandboxStateRepository == null
                || projectId == null || characterRef == null || characterRef.isBlank()) {
            return "";
        }
        try {
            com.holzyn.actor.domain.sandbox.entity.ActorSandboxState st =
                    sandboxStateRepository.findByProjectIdAndNpcRef(projectId, characterRef).orElse(null);
            if (st == null || st.getPosX() == null || st.getPosZ() == null) {
                return "";
            }
            double x = st.getPosX();
            double z = st.getPosZ();
            // 在途（移动中）：正走在去{目标}的路上（{起点方位}→{终点方位}，约{路程}）
            com.holzyn.actor.domain.worldmap.entity.ActorMovement m =
                    movementService.inTransitMove(projectId, characterRef);
            if (m != null) {
                String target = m.getTargetLocationName() == null || m.getTargetLocationName().isBlank()
                        ? "目的地" : m.getTargetLocationName().trim();
                String fromRegion = worldMapQueryService.regionAt(projectId,
                        m.getFromX() == null ? x : m.getFromX(), m.getFromZ() == null ? z : m.getFromZ());
                String toRegion = worldMapQueryService.regionAt(projectId,
                        m.getToX() == null ? x : m.getToX(), m.getToZ() == null ? z : m.getToZ());
                double distGrids = Math.hypot(
                        (m.getToX() == null ? x : m.getToX()) - (m.getFromX() == null ? x : m.getFromX()),
                        (m.getToZ() == null ? z : m.getToZ()) - (m.getFromZ() == null ? z : m.getFromZ()));
                String distText = com.holzyn.actor.domain.worldmap.service.WorldMapQueryService
                        .distanceText(distGrids * 0.2); // 格距 0.2 m/格（对齐 MovementService.METERS_PER_GRID）
                String bearing = com.holzyn.actor.domain.worldmap.service.WorldMapQueryService.bearing(
                        m.getFromX() == null ? x : m.getFromX(), m.getFromZ() == null ? z : m.getFromZ(),
                        m.getToX() == null ? x : m.getToX(), m.getToZ() == null ? z : m.getToZ());
                String route = (fromRegion == null ? "村中" : fromRegion) + "→" + (toRegion == null ? "村中" : toRegion);
                StringBuilder s = new StringBuilder("【当前位置与方向】正走在去" + target + "的路上（" + route
                        + "，" + distText + "，往" + bearing + "）");
                // 途中遭遇（§4.6 途中可见）：附近角色互为可见 →「迎面遇到李掌柜，点头致意」
                String encounter = encounterText(projectId, characterRef, x, z);
                if (!encounter.isBlank()) {
                    s.append("；").append(encounter);
                }
                return s.toString();
            }
            // 驻留：在{地点}（{区域口语描述}）——同时可见附近经过的角色（§4.6 互为可见：
            // 驻留者看到移动者「正从身旁经过」；移动者看到驻留者「迎面遇到」）
            String region = worldMapQueryService.regionAt(projectId, x, z);
            if (region == null || region.isBlank()) {
                return "";
            }
            String seeing = seeingText(projectId, characterRef, x, z);
            return "【当前位置与方向】在" + region
                    + (seeing.isBlank() ? "" : "；" + seeing);
        } catch (Exception e) {
            log.warn("[感知] 【当前位置与方向】段注入失败（跳过）: ref={} : {}", characterRef, e.getMessage());
            return "";
        }
    }

    /**
     * 驻留可见文本（M3 §4.6 途中可见，互为可见的另一半）：驻留角色看到附近经过的
     * 移动者——「看到{名}正从身旁经过」。取最近 ≤2 名附近角色（含在途/驻留），
     * 避免感知刷屏（每角色感知至多 2 人）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 角色引用（排除自身）
     *
     * @param x 角色当前沙盘坐标 X
     *
     * @param z 角色当前沙盘坐标 Z
     *
     * @return 可见文本（无附近角色返回空串）
     */
    private String seeingText(Long projectId, String characterRef, double x, double z) {
        try {
            java.util.List<String> nearby = worldMapQueryService.nearbyRefs(projectId, x, z, characterRef, 30);
            if (nearby.isEmpty()) {
                return "";
            }
            java.util.List<String> names = new ArrayList<>();
            for (String ref : nearby) {
                if (names.size() >= 2) {
                    break;
                }
                String name = npcName(projectId, ref);
                if (name != null && !name.isBlank()) {
                    names.add(name.trim());
                }
            }
            if (names.isEmpty()) {
                return "";
            }
            return "看到" + String.join("与", names) + "正从身旁经过";
        } catch (Exception e) {
            log.debug("[感知] 驻留可见组装失败（跳过）: ref={} : {}", characterRef, e.getMessage());
            return "";
        }
    }

    /**
     * 途中遭遇文本（M3 §4.6 途中可见）：移动中经过沿途区域 → 该区域在场角色与移动者
     * <b>互为可见</b>——感知段互相注入「迎面/擦肩而过看到 X」。取最近 ≤2 名附近角色
     * （{@code WorldMapQueryService.nearbyRefs}，按距离近→远），各自注入「迎面遇到{名}」。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 移动者引用（排除自身）
     *
     * @param x 移动者当前沙盘坐标 X
     *
     * @param z 移动者当前沙盘坐标 Z
     *
     * @return 遭遇文本（无附近角色返回空串）
     */
    private String encounterText(Long projectId, String characterRef, double x, double z) {
        try {
            java.util.List<String> nearby = worldMapQueryService.nearbyRefs(projectId, x, z, characterRef, 30);
            if (nearby.isEmpty()) {
                return "";
            }
            java.util.List<String> names = new ArrayList<>();
            for (String ref : nearby) {
                if (names.size() >= 2) {
                    break;
                }
                String name = npcName(projectId, ref);
                if (name != null && !name.isBlank()) {
                    names.add(name.trim());
                }
            }
            if (names.isEmpty()) {
                return "";
            }
            return "迎面遇到" + String.join("与", names) + "，点头致意";
        } catch (Exception e) {
            log.debug("[感知] 途中遭遇组装失败（跳过）: ref={} : {}", characterRef, e.getMessage());
            return "";
        }
    }

    /** 角色引用 → 名称（特殊/普通统一走 actor_profile；解析失败返回 null）。 */
    private String npcName(Long projectId, String ref) {
        try {
            Long id = com.holzyn.actor.common.NpcRefs.idOf(ref);
            if (id == null) {
                return null;
            }
            ActorCharacter ch = characterRepository.findById(id).orElse(null);
            return ch == null ? null : ch.getName();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * L1：写感知快照（perception）与情境知识上下文（context）工作项（单槽替换防膨胀）。
     * <p>仅在开关开、工作记忆服务可用、感知解析成功且有实质内容（所在地/活动/可见事件/知识命中）时写入；
     * 失败静默不阻断感知主流程。</p>
     */
    private void writeWorkingSnapshots(Long projectId, String characterRef, PerceptionVO vo) {
        if (!workingPerceptionEnabled || memoryWorkingService == null
                || projectId == null || characterRef == null || characterRef.isBlank() || vo == null) {
            return;
        }
        try {
            boolean hasLocation = notBlank(vo.locationName());
            boolean hasState = notBlank(vo.ownState());
            List<String> events = vo.visibleEvents() == null ? List.of() : vo.visibleEvents();
            if (hasLocation || hasState || !events.isEmpty()) {
                StringBuilder content = new StringBuilder("当前感知：");
                if (hasLocation) {
                    content.append("在").append(vo.locationName());
                }
                if (hasState) {
                    content.append("，正在").append(vo.ownState());
                }
                if (!events.isEmpty()) {
                    content.append("；近期可见事件：").append(String.join("；", events));
                }
                memoryWorkingService.writePerception(projectId, characterRef, content.toString(), 2);
            }
            List<String> knowledge = vo.knowledgeHints() == null ? List.of() : vo.knowledgeHints();
            if (!knowledge.isEmpty()) {
                memoryWorkingService.writeContext(projectId, characterRef,
                        "情境知识：" + String.join("；", knowledge), 2);
            }
        } catch (Exception e) {
            log.warn("[感知] L1 感知快照写入失败（跳过）: project={} ref={} : {}", projectId, characterRef, e.getMessage());
        }
    }

    /**
     * L1：取该角色【当前情境】热缓冲注入段（active 工作项 ≤5；无内容返回空串）。
     */
    private String workingSegment(Long projectId, String characterRef) {
        if (memoryWorkingService == null || projectId == null || characterRef == null || characterRef.isBlank()) {
            return "";
        }
        try {
            return memoryWorkingService.injectWorkingSegment(projectId, characterRef);
        } catch (Exception e) {
            log.warn("[感知] L1 工作记忆注入失败（跳过）: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 空白判定辅助。
     */
    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    // ==================== 内部组装 ====================

    /** 自身状态解析结果（名称用于关系摘要按名匹配； 含归一化 location_id 供在场过滤） */
    private record OwnState(String name, String location, String state, Long locationId) {
    }

    /**
     * 解析角色自身状态：特殊型（npc:{id} → current_activity+location）/ 普通型（npc:{id} → state+last_action+location）。
     * <p>引用不再编码类型，分支改由 {@link NpcTypeResolver} 查 {@code actor_profile.type} 判定
     * （查询失败回退 special，与历史「非 crowd 前缀即特殊分支」方向一致）。</p>
     * <p>第二处：普通型分支的 {@code state}/{@code last_action} 已不在主表实体上（随
     * {@code actor_profile_ordinary} 物理废弃迁入 {@code actor_state}）——权限/类型分派逻辑保持不变，
     * 只把"取状态字段"的来源换成运行态访问器；读不到时回退默认态（{@code 空闲}），零阻断。</p>
     */
    private OwnState resolveOwnState(Long projectId, String characterRef) {
        // ：npc:{id} 不再编码类型，此处按意图 B 处理——「普通型取 state/last_action、特殊型取
        // current_activity」是只有该类型才走的取字段口径，类型必须查档案而非看引用前缀
        if (npcTypeResolver.isCommon(projectId, characterRef)) {
            // ：解析主键统一走 NpcRefs.idOf（保持原有 null → 空状态 的处理）
            Long id = NpcRefs.idOf(characterRef);
            ActorCharacter npc = id == null ? null : npcRepository.findById(id).orElse(null);
            if (npc == null) {
                return new OwnState(null, null, null, null);
            }
            // ：state/last_action 改读 actor_state（运行态唯一家）——主表实体已无这两列。
            // 无状态行/列为空 → 访问器回默认 "idle"（与旧实体字段默认值一致）；访问器未装配或读异常
            // → null → 下方回退中文默认「空闲」（零阻断，绝不抛异常中断感知组装）
            String rawState = null;
            String lastAction = null;
            if (npcActivityStateService != null) {
                NpcActivityStateService.ActivityUpdate runState =
                        npcActivityStateService.activityOf(projectId, characterRef);
                if (runState != null) {
                    rawState = runState.state();
                    lastAction = runState.lastAction();
                }
            }
            String state = (rawState == null || rawState.isBlank() ? "空闲" : rawState)
                    + (lastAction == null || lastAction.isBlank()
                    ? "" : "（" + lastAction + "）");
            return new OwnState(npc.getName(), npc.getLocation(), state, npc.getLocationId());
        }
        // ：解析主键统一走 NpcRefs.idOf（保持原有 null → 空状态 的处理）
        Long id = NpcRefs.idOf(characterRef);
        ActorCharacter ch = id == null ? null : characterRepository.findById(id).orElse(null);
        if (ch == null) {
            return new OwnState(null, null, null, null);
        }
        String state = ch.getCurrentActivity() == null || ch.getCurrentActivity().isBlank()
                ? "空闲" : ch.getCurrentActivity();
        return new OwnState(ch.getName(), ch.getLocation(), state, ch.getLocationId());
    }

    /**
     * 可见近期事件（≤5）：经事件总线按角色 ref 可见性过滤（感知不是全知）。
     * <p>传角色归一化 location_id ——location 可见性事件仅对同地点在场者可见
     * （修 信息传播无地理逻辑：城西行动事件，城东角色不感知）。</p>
     */
    private List<String> visibleEvents(Long projectId, String characterRef, Long locationId) {
        try {
            List<ActorEvent> events = worldEventBus.query(projectId,
                    EventQuery.forCharacter(characterRef, locationId, null, MAX_VISIBLE_EVENTS));
            List<String> out = new ArrayList<>();
            for (ActorEvent e : events) {
                out.add(eventText(e));
            }
            return out;
        } catch (Exception e) {
            log.warn("[感知] 可见事件查询失败：project={} ref={} : {}", projectId, characterRef, e.getMessage());
            return List.of();
        }
    }

    /**
     * 情境知识片段：以「所在地 + 当前活动」为 query 复用知识检索服务检索 top-K（≤3），
     * 单条长度由检索层保证有界（fallback 文本检索=句边界对齐窗口 ≤400 字；RAG=≤500 字块；
     * round-46：不再整篇注入世界观）；无知识文档/未命中/检索失败自动跳过（零成本降级）；开关关闭不检索。
     * <p>检索 query 构造：所在地 + 自身状态（含活动），如「妖灵会馆大厅 巡视妖灵会馆」；
     * characterId 仅特殊型传角色 id（角色级知识并入），普通型传 null（仅项目级知识）；
     * 引用不再编码类型，特殊/普通由 {@link NpcTypeResolver} 查 actor_profile.type 判定。
     * 每次感知都检索（简单直接；embedding 可用时消耗 scene=embedding 用量，可观测）。</p>
     */
    private List<String> knowledgeHints(Long projectId, String characterRef, OwnState own) {
        if (!knowledgeEnabled || knowledgeRetrievalService == null || projectId == null) {
            return List.of();
        }
        try {
            StringBuilder q = new StringBuilder();
            if (own.location() != null && !own.location().isBlank()) {
                q.append(own.location());
            }
            if (own.state() != null && !own.state().isBlank()) {
                if (q.length() > 0) q.append(' ');
                q.append(own.state());
            }
            if (q.length() == 0) {
                return List.of();
            }
            Long userId = currentUserProvider == null ? 1L : currentUserProvider.currentUserId();
            // ：npc:{id} 不再编码类型，此处按意图 B 处理——「角色级知识仅特殊型并入」属只有特殊型
            // 才做的行为（普通型仍传 null=仅项目级知识），类型改由 NpcTypeResolver 查档案判定
            Long characterId = npcTypeResolver.isCommon(projectId, characterRef)
                    ? null : NpcRefs.idOf(characterRef);
            List<KnowledgeHit> hits =
                    knowledgeRetrievalService.search(userId, projectId, characterId, q.toString(), MAX_KNOWLEDGE);
            if (hits == null || hits.isEmpty()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (KnowledgeHit hit : hits) {
                if (hit == null || hit.text() == null || hit.text().isBlank()) {
                    continue;
                }
                String title = hit.title() == null || hit.title().isBlank() ? "知识" : hit.title().trim();
                // round-46：单条长度由检索层保证有界（句边界窗口，不整篇注入世界观）；
                // 此处不再做字符截断（round-45：此前 truncate 200 常切在句中）
                String text = hit.text().trim().replaceAll("\\s+", " ");
                // P1 ②-2：stale 文档命中标注「可能过时」（AI 引用时自行斟酌，模拟真实记忆的不可靠感）
                out.add(title + "：" + (hit.stale() ? "〔可能过时〕" : "") + text);
                if (out.size() >= MAX_KNOWLEDGE) {
                    break;
                }
            }
            return out;
        } catch (Exception e) {
            // 知识检索失败不阻断感知（降级跳过，保持既有行为）
            log.warn("[感知] 情境知识检索失败（跳过）: project={} ref={} : {}", projectId, characterRef, e.getMessage());
            return List.of();
        }
    }

    /**
     * 事件 → 展示文本（标题 + 内容首行，避免整段过长）。
     */
    static String eventText(ActorEvent e) {
        if (e == null) {
            return "";
        }
        String title = e.getTitle() == null || e.getTitle().isBlank() ? "事件" : e.getTitle().trim();
        String content = e.getContent();
        if (content == null || content.isBlank()) {
            return title;
        }
        String firstLine = content.trim().split("\\n")[0].trim();
        if (firstLine.isBlank() || firstLine.equals(title)) {
            return title;
        }
        return title + "：" + firstLine;
    }

    /**
     * 关系摘要（≤5）：能解析出 actor_profile.id 时按 from/to id 匹配；否则（引用非法）按名称匹配。
     */
    private List<String> relationSummary(Long projectId, String characterRef, String ownName) {
        try {
            List<ActorCharacterRelation> all = relationRepository.findByProjectId(projectId);
            if (all.isEmpty()) {
                return List.of();
            }
            // ：npc:{id} 不再编码类型，此处按意图 A 处理——只要引用能解析成 actor_profile 行就按
            // from/to id 匹配关系行（special/common 都成立）；解析失败保持既有「按名称匹配」回退
            Long id = NpcRefs.idOf(characterRef);
            boolean byName = id == null;
            List<String> out = new ArrayList<>();
            for (ActorCharacterRelation r : all) {
                if (byName) {
                    if (ownName == null || ownName.isBlank()
                            || !(ownName.equals(r.getFromName()) || ownName.equals(r.getToName()))) {
                        continue;
                    }
                } else {
                    boolean involved = (r.getFromCharacterId() != null && id.equals(r.getFromCharacterId()))
                            || (r.getToCharacterId() != null && id.equals(r.getToCharacterId()));
                    if (!involved) {
                        continue;
                    }
                }
                String other = otherName(r, id, ownName);
                String line = "与" + other + "：" + (r.getRelationType() == null || r.getRelationType().isBlank()
                        ? "相识" : r.getRelationType());
                // ：动态关系可见性——好感/信任/关系状态带入感知（拓扑/对话展示动态关系）
                if (r.getAffinity() != null || r.getTrust() != null || r.getRelationState() != null) {
                    line += "【好感" + (r.getAffinity() == null ? 0 : r.getAffinity())
                            + "/信任" + (r.getTrust() == null ? 0 : r.getTrust())
                            + "/" + RelationDynamicsService.stateLabel(r.getRelationState()) + "】";
                }
                if (r.getDescription() != null && !r.getDescription().isBlank()) {
                    line += "（" + r.getDescription() + "）";
                }
                out.add(line);
                if (out.size() >= MAX_RELATIONS) {
                    break;
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("[感知] 关系摘要查询失败：project={} ref={} : {}", projectId, characterRef, e.getMessage());
            return List.of();
        }
    }

    /**
     * 关系的「另一方」名称：返回与当前角色不同的端点名。
     */
    private String otherName(ActorCharacterRelation r, Long id, String ownName) {
        if (id != null) {
            if (id.equals(r.getFromCharacterId())) {
                return r.getToName() == null || r.getToName().isBlank() ? "对方" : r.getToName();
            }
            return r.getFromName() == null || r.getFromName().isBlank() ? "对方" : r.getFromName();
        }
        // 按名称匹配：返回另一端点名
        if (ownName != null && ownName.equals(r.getFromName())) {
            return r.getToName() == null || r.getToName().isBlank() ? "对方" : r.getToName();
        }
        return r.getFromName() == null || r.getFromName().isBlank() ? "对方" : r.getFromName();
    }
}
