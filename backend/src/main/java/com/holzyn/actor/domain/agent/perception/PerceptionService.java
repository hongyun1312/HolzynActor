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

    /**
     * 事实查询（感知回流）：决策/对话 prompt 的“我所知道的事”段与 stateVersion；
     * 字段注入保持既有构造器不变，null=未装配跳过该段。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.world.fact.service.WorldFactQuery worldFactQuery;

    /**
     * 物品端口（对接：【随身物品】段的唯一数据源）。
     *
     * <p>字段注入保持既有构造器不变（AGENTS §8.1.2）；{@code item.enabled=false} 时
     * 端口不装配 → 该段整段不注入，感知文本与之前逐字节一致。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.item.port.InventoryPort inventoryPort;

    /** 装备服务（对接：【随身物品】段的「手上/身上」两行；null=未装配跳过） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.equip.service.EquipmentService equipmentService;

    /** 物品域配置（关着不注入随身段，保证关开关=回基线） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.item.config.ItemProperties itemProperties;

    /** 地点仓储（对接：【此处的货】段要把「集市」这个名字翻成 loc:{id} 才能查柜台） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.world.repository.WorldLocationRepository locationRepository;

    /**
     * 制作服务（对接：【能做的活】段的唯一数据源）。
     *
     * <p>字段注入保持既有构造器不变（AGENTS §8.1.2）；{@code craft.enabled=false} 时
     * 服务不装配 → 该段整段不注入，感知文本与之前逐字节一致。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.craft.service.CraftService craftService;

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
     * 空间读取统一口径（三方对账 · 第一/三腿）：【当前位置与方向】段的坐标
     * 由此供数——<b>权威优先（{@code actor_npc_position}）、为空回退沙盘</b>，与地图页、
     * 对账探针同一份；字段注入保持构造器不变，null=未装配时退回旧的沙盘直读。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.mapnav.service.NpcSpatialView npcSpatialView;

    /**
     * 权威侧在途路径（{@code actor_npc_path}，米制坐标，日程/寻路系统写）。
     *
     * <p><b>为什么必须和坐标同源</b>：实跑里出现过「正走在去河神庙的路上（从铁匠铺出发）」
     * 这种自相矛盾的感知句——坐标系是权威行（{@code actor_npc_position}），在途描述却读的是
     * {@link #movementService} 的沙盘移动行（0..500 的另一套坐标）。两套坐标混着说话，模型就会
     * 说着一个方向、站在另一个地方。所以：坐标走权威行时，「正走去哪」只能读权威侧路径行。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.mapnav.repository.ActorNpcPathRepository npcPathRepository;

    /**
     * 角色当前地点读口（权威坐标 → 要素 → 归属住户）。
     *
     * <p>「环境被看见」不等于「环境被使用」——只给一句叙述，模型会当背景板。约束段要回答的是
     * 「在这个地方，哪些动作是不被允许的」，必须读到要素分类（民居/商铺/公共/野外）与归属住户。</p>
     *
     * <p><b>收敛</b>：这段判定原本在本类里各查一遍（要素表 + 住户表 + 地图表），
     * 与「可发生空间按地点重算」需要的判定重复。现在统一走 {@link NpcPlaceView}——
     * 同一能力只留一个读口，避免「约束段说别人家、空间按自家出候选」这种两套真相。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.mapnav.service.NpcPlaceView placeView;

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
        // 对接：【随身物品】段（紧接【当前状态】——「我现在怎么样」之后自然是
        // 「我身上有什么」；再往后才是世界动态。物品域关着时整段为空）
        String carried = inventorySegment(projectId, characterRef, gameSecond);
        // 对接：【此处的货】段（站在店铺/集市才有）——柜台有什么、能买也能卖。
        // 没有这一段，市场在真实世界里等于不存在：裁决器认得「买」字，但决策提示里
        // 从来没有「这里有货可以买」这句话，模型也就永远想不起去买东西（整世界长跑实测 0 笔成交）。
        String market = marketSegment(projectId, vo == null ? null : vo.locationName(), gameSecond);
        // 对接：【能做的活】段（站在有炉子/工台的地方才有）——此处能做什么、要什么料、
        // 身上够不够。没有这一段，制作在真实世界里同样等于不存在（长跑 0 笔开工）。
        String craftable = craftSegment(projectId, characterRef,
                vo == null ? null : vo.locationName(), gameSecond);
        // 【世界动态】段：状态后、当前所见前（主方案 §4.4 注入顺序：核心 → 状态 → 世界动态 →
        // 当前所见 → L1）——该 NPC 当前可感知的信息包（三层过滤 + 失真文本，≤3 条 ≤60 字）
        String dynamics = worldDynamicsSegment(projectId, characterRef, gameSecond);
        // 感知回流：【我所知道的事】段（紧接【世界动态】后；格式由 WorldFactQuery 固定，
        // 无任何 knowledge 时也保留 stateVersion 行，满足可复现要求）
        String facts = worldFactsSegment(projectId, characterRef, gameSecond);
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
        // 环境从「描述」升级为「规则」：紧跟在空间段之后给一条可执行约束（此处是自家/别人家、
        // 进门要不要敲门、夜里该走哪条路）。感知是唯一漏斗——决策/对话/引擎动作全走这里，
        // 所以约束放这一段，才能覆盖「动作决策」这条真正决定行为的链路（判定输入另有一份精简版）。
        String spaceRule = spatialConstraint(projectId, characterRef, gameSecond);
        if (spaceRule != null && !spaceRule.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(spaceRule);
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
        if (carried != null && !carried.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(carried);
        }
        if (market != null && !market.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(market);
        }
        if (craftable != null && !craftable.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(craftable);
        }
        if (dynamics != null && !dynamics.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(dynamics);
        }
        if (facts != null && !facts.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(facts);
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
     * 对接【随身物品】段：手上握着什么、身上穿着什么、包里还带着什么。
     *
     * <p><b>为什么这段是「M 与 I 是否连上」的试金石</b>：物品域把「东西会旧、会烂、会被拿走」
     * 做完了，但如果决策提示里读不到「我身上有半块干粮」，模型就只能凭想象决定去吃饭还是
     * 去打架——东西再多也影响不了行为。感知是决策/对话/引擎动作的唯一漏斗，所以这段必须
     * 挂在感知里，而不是只挂在裁决输入里。</p>
     *
     * <p><b>格式为什么分三段写</b>：手上/身上/包里在语义上不是一回事——手上的能立刻用，
     * 身上的决定别人怎么看你，包里的要翻。合成一句「你有柴刀、粗布衣、面包」会把这层差别
     * 抹掉，模型也就不会写「他握紧腰间的刀」。成色只在旧了以后才写数字（
     * {@link com.holzyn.actor.domain.item.model.ItemCarryText} 同口径）。</p>
     *
     * <p><b>降级</b>：物品域关闭端口未装配任何异常 → 返回空串，感知文本与之前
     * 逐字节一致。</p>
     *
     * @param projectId    项目 ID
     *
     * @param characterRef 角色引用
     *
     * @param gameSecond   游戏秒（仅用于日志，物品读取不看时刻）
     *
     * @return 【随身物品】段（空串=不注入）
     */
    private String inventorySegment(Long projectId, String characterRef, Long gameSecond) {
        if (inventoryPort == null || projectId == null || characterRef == null || characterRef.isBlank()) {
            return "";
        }
        if (itemProperties != null && !itemProperties.isEnabled()) {
            return "";
        }
        try {
            List<com.holzyn.actor.domain.item.model.ItemView> items =
                    inventoryPort.list(projectId, characterRef);
            String hand = null;
            String body = null;
            java.util.Map<String, Integer> worn = new java.util.HashMap<>();
            if (equipmentService != null && equipmentService.enabled()) {
                for (com.holzyn.actor.domain.equip.model.EquipmentView eq
                        : equipmentService.equipmentOf(projectId, characterRef)) {
                    if (eq == null || eq.slot() == null) {
                        continue;
                    }
                    String label = eq.name() + (eq.condition() < 100
                            ? "（" + eq.conditionLabel() + " " + eq.condition() + "）" : "");
                    if ("hand".equalsIgnoreCase(eq.slot())) {
                        hand = label;
                    } else if ("body".equalsIgnoreCase(eq.slot())) {
                        body = label;
                    }
                    if (eq.itemKey() != null) {
                        worn.merge(eq.itemKey(), 1, Integer::sum);
                    }
                }
            }
            // 已经穿在身上/握在手上的那几件从「带着」里扣掉：否则同一件粗布衣会同时出现在
            // 「身上：」和「带着：」两处，读起来像他带了两件衣服。
            List<com.holzyn.actor.domain.item.model.ItemView> carriedItems =
                    stripWorn(items, worn);
            String carried = com.holzyn.actor.domain.item.model.ItemCarryText
                    .renderCarried(carriedItems, com.holzyn.actor.domain.item.model.ItemCarryText.DEFAULT_MAX_ITEMS);
            if (hand == null && body == null && (carried == null || carried.isEmpty())) {
                return "【随身物品】身上空空的，什么也没带。";
            }
            StringBuilder sb = new StringBuilder("【随身物品】");
            if (hand != null) {
                sb.append("手上：").append(hand).append("；");
            }
            if (body != null) {
                sb.append("身上：").append(body).append("；");
            }
            if (carried != null && !carried.isEmpty()) {
                sb.append("带着：").append(carried);
            }
            String text = sb.toString();
            while (text.endsWith("；")) {
                text = text.substring(0, text.length() - 1);
            }
            return text;
        } catch (Exception e) {
            log.warn("[感知] 【随身物品】段注入失败（跳过）: ref={} : {}", characterRef, e.getMessage());
            return "";
        }
    }

    /**
     * 把已经穿在身上/握在手上的件数从「带着」清单里扣掉。
     *
     * <p>只扣件数、不动别的物品：穿一件粗布衣、包里还有一件，就只从「带着」里减一。
     * 不扣的话同一件衣服会同时出现在「身上：」与「带着：」两处。</p>
     *
     * @param items 全部持有栈
     *
     * @param worn  已装备的物品键 → 件数
     *
     * @return 扣掉已装备件数后的清单
     */
    private static List<com.holzyn.actor.domain.item.model.ItemView> stripWorn(
            List<com.holzyn.actor.domain.item.model.ItemView> items, java.util.Map<String, Integer> worn) {
        if (items == null || items.isEmpty()) {
            return items == null ? List.of() : items;
        }
        if (worn == null || worn.isEmpty()) {
            return items;
        }
        java.util.Map<String, Integer> left = new java.util.HashMap<>(worn);
        List<com.holzyn.actor.domain.item.model.ItemView> out = new java.util.ArrayList<>(items.size());
        for (com.holzyn.actor.domain.item.model.ItemView it : items) {
            if (it == null) {
                continue;
            }
            int take = left.getOrDefault(it.itemKey(), 0);
            if (take <= 0) {
                out.add(it);
                continue;
            }
            left.put(it.itemKey(), 0);
            int rest = it.quantity() - take;
            if (rest > 0) {
                out.add(new com.holzyn.actor.domain.item.model.ItemView(it.itemKey(), it.name(),
                        it.category(), rest, it.quality(), it.durability(), it.equipSlot()));
            }
        }
        return out;
    }

    /**
     * 对接【此处的货】段：站在店铺/集市时，柜台上有哪些东西、各多少件。
     *
     * <p><b>为什么非有不可</b>：市场做完了「定价、成交、扣款、写 stock 事实」，
     * 但决策提示里从来没有一句话告诉角色「这个柜台上有货、你可以买」——模型于是永远
     * 不会说出「买」字，裁决器里的 TRADE 规则一次也跑不到。整世界长跑实测：3 游戏小时
     * 零成交，而所有角色的饥饿值都在往下掉。</p>
     *
     * <p><b>为什么只给店铺/集市</b>：只有这些地点在开局被铺了货（{@code ProvisionKit} 同口径），
     * 在田埂上写「此处有货」等于凭空造出一个柜台。</p>
     *
     * <p><b>为什么不带价格</b>：报价接口是<b>写</b>路径（懒建报价行 + 版本号自增），感知是只读漏斗，
     * 每读一次就写一次会把报价表刷成噪声。价格由裁决器在成交那一刻算，钱不够会得到
     * 「钱不够」的可见结果——这本身也是真实反馈。</p>
     *
     * @param projectId    项目 ID
     *
     * @param locationName 角色当前所在地点名（可空）
     *
     * @param gameSecond   游戏秒（仅日志）
     *
     * @return 【此处的货】段（空串=不注入）
     */
    private String marketSegment(Long projectId, String locationName, Long gameSecond) {
        if (projectId == null || locationName == null || locationName.isBlank()) {
            return "";
        }
        if (inventoryPort == null || locationRepository == null) {
            return "";
        }
        if (itemProperties != null && !itemProperties.isEnabled()) {
            return "";
        }
        if (!com.holzyn.actor.domain.item.provision.ProvisionKit.isMarketLike(locationName)) {
            return "";
        }
        try {
            Long locId = findLocationId(projectId, locationName, null);
            if (locId == null) {
                return "";
            }
            List<com.holzyn.actor.domain.item.model.ItemView> stock =
                    inventoryPort.list(projectId, "loc:" + locId);
            StringBuilder sb = new StringBuilder("【此处的货】");
            int shown = 0;
            int total = 0;
            for (com.holzyn.actor.domain.item.model.ItemView it : stock) {
                if (it == null || it.quantity() <= 0) {
                    continue;
                }
                // 柜台里的零钱不是「货」：写进去会让人以为可以买铜钱
                if ("currency".equalsIgnoreCase(it.category())) {
                    continue;
                }
                total++;
                if (shown >= 6) {
                    continue;
                }
                if (shown > 0) {
                    sb.append("、");
                }
                sb.append(it.name()).append("×").append(it.quantity());
                shown++;
            }
            if (total == 0) {
                // 聚落（村/镇）本来就没有柜台，别硬说「柜台空空」；只有真·店铺才提示空柜台
                return com.holzyn.actor.domain.item.provision.ProvisionKit.isShopLike(locationName)
                        ? "【此处的货】柜台空空如也，什么也没得卖。" : "";
            }
            if (total > shown) {
                sb.append(" 等 ").append(total).append(" 样");
            }
            sb.append("。可以买，也可以把身上多余的东西卖给他。");
            return sb.toString();
        } catch (Exception e) {
            log.warn("[感知] 【此处的货】段注入失败（跳过）: loc={} : {}", locationName, e.getMessage());
            return "";
        }
    }

    /**
     * 对接【能做的活】段：站在有炉子/工台的地方时，这里能做什么、要什么料、身上够不够。
     *
     * <p><b>为什么非有不可</b>：把「配方设施占炉计时出货」都做完了，但决策提示里
     * 从来没有一句话告诉角色「这里有炉子，你能打一把柴刀，料在身上」——模型于是永远想不起
     * 动手做活。整世界长跑实测：5 天 20 小时、432 条决策，{@code actor_craft_job} 0 行。</p>
     *
     * <p><b>为什么带上「够不够料」</b>：只说「能打柴刀」而身上缺铁锭，模型会去做一件注定
     * {@code NO_MATERIAL} 的活；把缺口直接写出来，它要么去集市买料（TRADE），要么改做别的。</p>
     *
     * <p><b>为什么按地点名判设施</b>：感知是只读漏斗，这里不查设施事实（那是裁决器的活），
     * 只用地点名做确定性别名匹配（与 {@code CraftFacilities} 同一张别名表），
     * 避免「在田埂上凭空打铁」。</p>
     *
     * @param projectId    项目 ID
     *
     * @param characterRef 角色引用（npc:{id}）
     *
     * @param locationName 角色当前所在地点名（可空）
     *
     * @param gameSecond   游戏秒（仅日志）
     *
     * @return 【能做的活】段（空串=不注入）
     */
    private String craftSegment(Long projectId, String characterRef, String locationName, Long gameSecond) {
        if (projectId == null || characterRef == null || characterRef.isBlank()
                || locationName == null || locationName.isBlank() || craftService == null) {
            return "";
        }
        if (itemProperties != null && !itemProperties.isEnabled()) {
            return "";
        }
        try {
            if (!craftService.enabled()) {
                return "";
            }
            java.util.List<com.holzyn.actor.domain.craft.model.CraftRecipeView> catalog =
                    craftService.catalogOf(projectId);
            if (catalog == null || catalog.isEmpty()) {
                return "";
            }
            java.util.List<com.holzyn.actor.domain.item.model.ItemView> inventory =
                    inventoryPort == null ? java.util.List.of() : inventoryPort.list(projectId, characterRef);
            StringBuilder sb = new StringBuilder("【能做的活】");
            int shown = 0;
            for (com.holzyn.actor.domain.craft.model.CraftRecipeView recipe : catalog) {
                if (recipe == null) {
                    continue;
                }
                if (recipe.needsFacility()
                        && !com.holzyn.actor.domain.craft.facility.CraftFacilities
                        .matches(recipe.facilityType(), null, locationName)) {
                    continue;
                }
                if (shown > 0) {
                    sb.append("；");
                }
                sb.append(recipe.recipeKey());
                if (recipe.inputs() != null && !recipe.inputs().isEmpty()) {
                    sb.append("（要");
                    for (int i = 0; i < recipe.inputs().size(); i++) {
                        if (i > 0) {
                            sb.append("、");
                        }
                        sb.append(recipe.inputs().get(i).itemKey())
                                .append("×").append(recipe.inputs().get(i).qty());
                    }
                    java.util.List<com.holzyn.actor.domain.craft.model.MaterialGap> gaps =
                            com.holzyn.actor.domain.craft.service.CraftService.gapsOf(recipe, inventory, 1);
                    sb.append(gaps == null || gaps.isEmpty()
                            ? "，身上都够" : "，少" + gaps.get(0).describe());
                    sb.append("）");
                }
                shown++;
                if (shown >= 3) {
                    break;
                }
            }
            if (shown == 0) {
                return "";
            }
            sb.append("。说一句「生火做一件…」就能动手。");
            return sb.toString();
        } catch (Exception e) {
            log.debug("[感知] 【能做的活】段生成失败（跳过）: loc={} : {}", locationName, e.getMessage());
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
     * 【我所知道的事】段：复用 {@link com.holzyn.actor.domain.world.fact.service.WorldFactQuery}
     * 的固定格式（含来源注脚与 stateVersion）。没有任何认知时仍输出版本行，
     * 让“每条决策输入都能复现当时世界版本”对零认知角色也成立。
     *
     * <p><b>为什么公开</b>：决策链可能由调用方传入自定义情境（结局摘要/玩家干预指令等），
     * 这些情境不含感知段；{@code DecisionService} 需要用同一格式化入口补齐事实段，
     * 而不是另写一份拼串（事实层唯一读模型 = WorldFactQuery）。</p>
     *
     * @param projectId   项目 ID
     * @param characterRef 角色 ref
     * @param gameSecond  游戏秒（可空=当前引擎位置）
     *
     * @return 段文本（未装配/异常返回空串）
     */
    public String worldFactsSegment(Long projectId, String characterRef, Long gameSecond) {
        if (worldFactQuery == null || projectId == null
                || characterRef == null || characterRef.isBlank()) {
            return "";
        }
        long gs = gameSecond == null ? 0L : gameSecond;
        if (gs <= 0 && worldClockService != null) {
            // 兼容旧调用方传 null：用引擎当前位置，避免新写入事实因 validFrom>0 被
            // gs=0 误判为“未生效”，同时让 stateVersion 不再是 gs0/vN。
            Long current = worldClockService.currentEngineGameSecond(projectId);
            gs = current == null ? 0L : current;
        }
        try {
            String digest = worldFactQuery.digestForPrompt(projectId, characterRef, gs, 8);
            if (digest != null && !digest.isBlank()) {
                return digest;
            }
            String version = worldFactQuery.stateVersion(projectId, gs);
            return version == null || version.isBlank() ? "" : "[世界状态版本 " + version + "]";
        } catch (Exception e) {
            log.warn("[感知] 【我所知道的事】段注入失败（跳过）: {}", e.getMessage());
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
        if (worldMapQueryService == null || movementService == null
                || projectId == null || characterRef == null || characterRef.isBlank()) {
            return "";
        }
        try {
            // 坐标一律走统一读口径（权威优先、沙盘回退、回退留 source）——
            // 这一处就是「三方」的第三腿：注入给 AI 的坐标必须与地图渲染/决策用的是同一个数。
            com.holzyn.actor.domain.mapnav.vo.SpatialFix fix = npcSpatialView == null ? null
                    : npcSpatialView.resolve(projectId, characterRef, gameSecond).orElse(null);
            double x;
            double z;
            if (fix != null) {
                x = fix.getX();
                z = fix.getZ();
            } else {
                com.holzyn.actor.domain.sandbox.entity.ActorSandboxState st = sandboxStateRepository == null
                        ? null
                        : sandboxStateRepository.findByProjectIdAndNpcRef(projectId, characterRef).orElse(null);
                if (st == null || st.getPosX() == null || st.getPosZ() == null) {
                    return "";
                }
                x = st.getPosX();
                z = st.getPosZ();
            }
            // 权威行与沙盘行不是同一个坐标系（实测：权威/渲染 0..1024 米制，沙盘场景 0..500），
            // 把权威坐标喂给沙盘反查（regionAt/nearbyRefs）会算到错的地方——所以按来源分岔。
            boolean authority = fix != null
                    && com.holzyn.actor.domain.mapnav.vo.SpatialFix.SOURCE_AUTHORITY.equals(fix.getSource());
            String where = authority && npcSpatialView != null
                    ? npcSpatialView.featureName(projectId, fix.getFeatureCode()) : null;
            // 权威口径的在途描述：只读权威侧路径行（actor_npc_path，米制坐标），
            // 绝不混用沙盘移动行——两套坐标混用会产出「正走在去河神庙的路上（从铁匠铺出发）」
            // 这种「方向是 A、出发点是 B」的自相矛盾句（实测，模型跟着这句走）。
            if (authority) {
                com.holzyn.actor.domain.mapnav.entity.ActorNpcPath ap =
                        authorityPath(projectId, characterRef, gameSecond, x, z);
                if (ap != null) {
                    String target = readablePlaceRef(projectId, ap.getToRef());
                    StringBuilder s = new StringBuilder("【当前位置与方向】正走在去" + target + "的路上（从"
                            + (where == null || where.isBlank() ? "此处" : where.trim()) + "出发");
                    long now = gameSecond == null ? 0L : gameSecond;
                    if (ap.getArriveGameSecond() != null && ap.getArriveGameSecond() > now) {
                        long remain = ap.getArriveGameSecond() - now;
                        if (remain >= 300) {
                            s.append("，约 ").append(remain / 60).append(" 分钟后到");
                        }
                    }
                    s.append("）");
                    String encounter = nearbyTextByAuthority(projectId, characterRef, x, z, "迎面遇到");
                    if (!encounter.isBlank()) {
                        s.append("；").append(encounter);
                    }
                    return s.append(spatialTail(projectId, x, z, fix, gameSecond)).toString();
                }
            }
            // 在途（移动中）：正走在去{目标}的路上（{起点方位}→{终点方位}，约{路程}）
            // 只在「坐标来自沙盘」时使用这条链路：沙盘移动行与沙盘坐标同源。
            com.holzyn.actor.domain.worldmap.entity.ActorMovement m = authority ? null
                    : movementService.inTransitMove(projectId, characterRef);
            if (m != null) {
                String target = m.getTargetLocationName() == null || m.getTargetLocationName().isBlank()
                        ? "目的地" : m.getTargetLocationName().trim();
                StringBuilder s;
                if (authority) {
                    s = new StringBuilder("【当前位置与方向】正走在去" + target + "的路上（从"
                            + (where == null || where.isBlank() ? "此处" : where.trim()) + "出发）");
                } else {
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
                    String route = (fromRegion == null ? "村中" : fromRegion) + "→"
                            + (toRegion == null ? "村中" : toRegion);
                    s = new StringBuilder("【当前位置与方向】正走在去" + target + "的路上（" + route
                            + "，" + distText + "，往" + bearing + "）");
                }
                // 途中遭遇（§4.6 途中可见）：附近角色互为可见 →「迎面遇到李掌柜，点头致意」
                String encounter = authority
                        ? nearbyTextByAuthority(projectId, characterRef, x, z, "迎面遇到")
                        : encounterText(projectId, characterRef, x, z);
                if (!encounter.isBlank()) {
                    s.append("；").append(encounter);
                }
                return s.append(spatialTail(projectId, x, z, fix, gameSecond)).toString();
            }
            // 驻留：在{地点}（{区域口语描述}）——同时可见附近经过的角色（§4.6 互为可见：
            // 驻留者看到移动者「正从身旁经过」；移动者看到驻留者「迎面遇到」）
            // 关键：以前 region 查不到就 `return ""`，整段（含机器可校验尾巴）一起没了——
            // 「查不到地方名」不该等于「不告诉模型它在哪」，所以这里只降级标签，绝不吞段。
            String region = authority
                    ? (where == null || where.isBlank() ? null : where.trim())
                    : worldMapQueryService.regionAt(projectId, x, z);
            String seeing = authority
                    ? nearbyTextByAuthority(projectId, characterRef, x, z, "看到")
                    : seeingText(projectId, characterRef, x, z);
            String label = region == null || region.isBlank() ? "附近" : region;
            return "【当前位置与方向】在" + label
                    + (seeing.isBlank() ? "" : "；" + seeing)
                    + spatialTail(projectId, x, z, fix, gameSecond);
        } catch (Exception e) {
            log.warn("[感知] 【当前位置与方向】段注入失败（跳过）: ref={} : {}", characterRef, e.getMessage());
            return "";
        }
    }

    /**
     * 权威侧在途行（{@code actor_npc_path}，status=walking）。
     *
     * <p>为什么单独取这一行：权威坐标在米制地图空间里推进，日程/寻路系统把「正在走的那条路」
     * 记在 {@code actor_npc_path}；而 {@code actor_movement} 写的是沙盘（0..500）那套坐标。
     * 感知要么全用权威、要么全用沙盘，混着用就会说出「方向是河神庙、出发点却是铁匠铺」。</p>
     */
    private com.holzyn.actor.domain.mapnav.entity.ActorNpcPath authorityPath(Long projectId, String characterRef,
                                                                            Long gameSecond,
                                                                            double x, double z) {
        if (npcPathRepository == null) {
            return null;
        }
        try {
            java.util.List<com.holzyn.actor.domain.mapnav.entity.ActorNpcPath> rows =
                    npcPathRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(
                            projectId, characterRef, "walking");
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            com.holzyn.actor.domain.mapnav.entity.ActorNpcPath row = rows.get(0);
            // 时间窗校验：不在 [出发, 到达] 内的 walking 行是陈旧行（进程被停过、时钟被回拨过、
            // 或者角色被直接摆到别处）。陈旧行一旦被念出来，模型就会说「我正走在去某地」，
            // 而它其实已经站在另一个地方——这正是事故里的那类自相矛盾。
            long now = gameSecond == null ? 0L : gameSecond;
            long depart = row.getDepartGameSecond() == null ? 0L : row.getDepartGameSecond();
            long arrive = row.getArriveGameSecond() == null ? Long.MAX_VALUE : row.getArriveGameSecond();
            if (now < depart || now > arrive) {
                log.debug("[感知] 权威在途行已过期（不注入在途描述）: ref={} now={} window=[{},{}]",
                        characterRef, now, depart, arrive);
                return null;
            }
            // 几何校验：路径还说得通吗？人被摆到二十米开外、路径却仍挂着 walking 时，
            // 任何「正走在去某地」的话都是假话。
            if (pathMissesPosition(row.getPointsJson(), x, z)) {
                log.debug("[感知] 权威在途行与坐标脱节（不注入在途描述）: ref={} pos=({},{})",
                        characterRef, x, z);
                return null;
            }
            return row;
        } catch (Exception e) {
            log.debug("[感知] 读权威在途路径失败（跳过）: ref={} : {}", characterRef, e.getMessage());
            return null;
        }
    }

    /**
     * 把 {@code loc:河神庙} / {@code f_xxx} 这类引用说成人话。
     *
     * <p>：路径行的 {@code toRef} 现在是「解析后的要素 code」（这样到达时坐标行能挂上
     * 真正的要素，约束/选项段才查得到地点），所以这里必须能把 code 翻回名字，否则感知会说
     * 「正走在去 f_rice_shop_40 的路上」。</p>
     *
     * @param projectId 项目（用于 code → 名字）
     *
     * @param ref       目标引用
     *
     * @return 可读地名
     */
    private String readablePlaceRef(Long projectId, String ref) {
        if (ref == null || ref.isBlank()) {
            return "目的地";
        }
        String s = ref.trim();
        int i = s.indexOf(':');
        if (i >= 0 && i < s.length() - 1) {
            s = s.substring(i + 1).trim();
        }
        if (s.startsWith("f_") && npcSpatialView != null) {
            try {
                String name = npcSpatialView.featureName(projectId, s);
                if (name != null && !name.isBlank()) {
                    return name.trim();
                }
            } catch (Exception e) {
                log.debug("[感知] 要素 code 转名失败（原样输出）: {} : {}", s, e.getMessage());
            }
        }
        return s.isBlank() ? "目的地" : s;
    }

    /** 判定「在途路径」与当前坐标是否还说得通的距离阈值（米）：超出即视为陈旧路径。 */
    private static final double AUTHORITY_PATH_TOLERANCE_M = 20.0;

    /**
     * 路径与坐标是否已经脱节（角色被摆到别处、或被别的系统搬走了，但路径行还挂着 walking）。
     *
     * <p>为什么光校验时间窗不够：路径行的 [出发, 到达] 完全可以是「还没到点」的合法窗口，
     * 而人已经被挪到二十米开外——只看时间，感知就会一直念「正走在去河神庙的路上」，
     * 一站就是好几分钟（实跑里模型就是跟着这句走的）。所以必须几何校验：
     * 当前坐标到这条折线的最短距离超过 {@link #AUTHORITY_PATH_TOLERANCE_M} 米，就判定路径陈旧。</p>
     */
    private static boolean pathMissesPosition(String pointsJson, double x, double z) {
        if (pointsJson == null || pointsJson.isBlank()) {
            return false;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(pointsJson);
            if (!root.isArray() || root.size() < 2) {
                return false;
            }
            double best = Double.MAX_VALUE;
            double px = root.get(0).get(0).asDouble();
            double pz = root.get(0).get(1).asDouble();
            for (int i = 1; i < root.size(); i++) {
                double qx = root.get(i).get(0).asDouble();
                double qz = root.get(i).get(1).asDouble();
                best = Math.min(best, distanceToSegment(x, z, px, pz, qx, qz));
                px = qx;
                pz = qz;
            }
            return best > AUTHORITY_PATH_TOLERANCE_M;
        } catch (Exception e) {
            return false;
        }
    }

    /** 点到线段的最短距离（米）。 */
    private static double distanceToSegment(double x, double z, double ax, double az,
                                            double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double len2 = dx * dx + dz * dz;
        if (len2 <= 1e-9) {
            return Math.hypot(x - ax, z - az);
        }
        double t = ((x - ax) * dx + (z - az) * dz) / len2;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(x - (ax + t * dx), z - (az + t * dz));
    }

    /**
     * 【空间约束】段：把「环境」从背景描述变成可执行规则。
     *
     * <p><b>为什么需要它（反事实实验的直接结论）</b>：同一角色、同一目标，只换
     * 【所在】的环境叙述，动作分布只移动了 2.4%~9.6%（卡方 p=0.76）——环境「被看见」了
     * （120/120 次都在 prompt 里），但没有「被使用」。原因之一是叙述只说明「你在哪」，
     * 没有说明「在这个地方，哪些动作是不合法的」。这一段补的就是后者：地点归属（自家/别人家）+
     * 由此推出的行为约束（进门要敲门、取用要打招呼、夜里走大路）。</p>
     *
     * <p>数据源：权威坐标 → 要素（分类/名称/归属住所）→ 住户表判定是不是自己家。
     * 任何一步查不到就降级为「你在某处（室内/室外）」，绝不返回半截话。</p>
     *
     * @param projectId    项目 ID
     *
     * @param characterRef 角色引用（npc:{id}）
     *
     * @param gameSecond   游戏秒（可空）
     *
     * @return 约束段文本（空串=不注入）
     */
    public String spatialConstraint(Long projectId, String characterRef, Long gameSecond) {
        if (projectId == null || characterRef == null || characterRef.isBlank() || npcSpatialView == null) {
            return "";
        }
        try {
            com.holzyn.actor.domain.mapnav.vo.SpatialFix fix =
                    npcSpatialView.resolve(projectId, characterRef, gameSecond).orElse(null);
            if (fix == null) {
                return "";
            }
            // 地点判定统一走 NpcPlaceView（与「可发生空间按地点重算」同一口径，杜绝两套真相）
            com.holzyn.actor.domain.mapnav.service.NpcPlaceView.Place p =
                    placeView == null ? null : placeView.at(projectId, characterRef);
            String name = p == null || !p.known()
                    ? npcSpatialView.featureName(projectId, fix.getFeatureCode()) : p.name();
            String category = p == null || p.category() == null ? "" : p.category().trim();
            String place = name == null || name.isBlank() ? "这里" : name.trim();
            String inout = fix.isIndoor() ? "室内" : "室外";
            StringBuilder sb = new StringBuilder("【空间约束】你在").append(place)
                    .append("（").append(category.isBlank() ? inout : category + "，" + inout).append("）。");
            boolean hasHome = p != null && p.hasHome();
            boolean mine = p != null && p.mine();
            if (hasHome) {
                if (mine) {
                    sb.append("这是你自己的住处，可以自由进出。");
                } else {
                    sb.append("这是别人的住处，不是你家；要进门先敲门、说明来意，")
                            .append("没人应声就在门外等着或改天再来，不要自己推门。");
                }
            } else if ("商铺".equals(category) || "工坊".equals(category)) {
                sb.append("这是营生的地方；动手取用东西前先跟主人或掌柜打招呼。");
            } else if ("生产".equals(category) || "水工".equals(category)) {
                sb.append("这是干活的地方；用别人的工具、占别人的场地前先说一声。");
            } else if ("公共".equals(category)) {
                sb.append("这是公共场所，做事要顾着旁人的眼色。");
            } else if ("自然".equals(category)) {
                sb.append("这是野外生产场地，夜里看不清路，走大路、别踩庄稼。");
            } else {
                sb.append("先认清这是什么地方、这里是谁的地界，再动手。");
            }
            // 约束之外再给一句「此处能做/不能做」。
            // 只给约束时，模型知道「该敲门」，但选项里「直接进去」的概率照样 23%~25%（实测 120 次）——
            // 规则句是叙述，选项句才是可对照的判据；打分模型对「不可做」的显式否定更敏感。
            sb.append(affordances(category, hasHome, mine, commerceOf(projectId, place)));
            return sb.toString();
        } catch (Exception e) {
            log.debug("[感知] 空间约束段生成失败（跳过）: ref={} : {}", characterRef, e.getMessage());
            return "";
        }
    }

    /**
     * 【空间选项】段：把「这里是什么地方」翻译成「此处能做 / 不能做」。
     *
     * <p><b>为什么约束还不够（实测）</b>：加了【空间约束】之后，四组环境的动作分布
     * 位移均值从 5.6% 只升到 6.3%，「推门进去拿东西」在别人家门口仍有 23.1% 的概率。
     * 约束是「应该怎样」的叙述，选项是「能/不能」的判据——后者能被直接对照到候选动作上。</p>
     *
     * <p>口径与约束同源（同一要素分类 + 同一住户判定），所以不会出现「约束说别人家、
     * 选项说可以随便进」这种自相矛盾。</p>
     *
     * <p><b>整世界长跑：可做清单必须跟着「这里的营生」走</b>。原来这张清单是纯静态的
     * 按分类写死，于是站在集市上的角色读到的是「停留、说话、观望、歇脚」，站在自家读到的是
     * 「自由进出、生火做饭」——<b>买卖和做活从来不在任何一张清单里</b>。结果是 5 天 20 小时、
     * 432 条决策里只有 1 条含「买」、0 笔成交、0 笔制作。选项句是模型能直接对照的判据，
     * 缺了它，市场和制作在真实世界里等于不存在。</p>
     *
     * @param category 要素分类（可空）
     *
     * @param hasHome  该要素是否登记了归属住户
     *
     * @param mine     归属住户是不是这个角色
     *
     * @param commerce 此处营生属性（柜台/炉子/世界里能买卖的地方）
     *
     * @return 选项段（始终非空；数据不明时给保守口径）
     */
    private static String affordances(String category, boolean hasHome, boolean mine, Commerce commerce) {
        String cat = category == null ? "" : category.trim();
        Commerce c = commerce == null ? Commerce.NONE : commerce;
        // 世界里有柜台时，任何地点都补一句「买卖去哪」——角色在家/在地头也能想起「去集市买」。
        String marketHint = c.market() == null ? ""
                : "；缺吃食、缺料就去「" + c.market() + "」买，身上多余的也能拿去卖";
        if (hasHome) {
            if (mine) {
                return "【空间选项】可做：自由进出、生火做饭、取用自己的工具和东西、歇脚"
                        + marketHint + "；不可做：把别人的东西带走。";
            }
            return "【空间选项】可做：敲门喊人、在门外等、说明来意；不可做：自己推门进去、翻找或拿走东西"
                    + "（要拿的东西在屋里也一样，得先问过主人）。";
        }
        if (c.stocked() || c.shop()) {
            return "【空间选项】可做：问价、买东西、把身上多余的东西卖给他、跟掌柜搭话"
                    + (c.stocked() ? "（柜台上有货）" : "（柜台空着，问价也行）")
                    + "；不可做：自己进后堂取货、拿了东西不给钱。";
        }
        if (c.workshop() || "工坊".equals(cat)) {
            return "【空间选项】可做：生炉子/上工台动手做活、借用工具、跟工匠谈活儿、把料做成东西"
                    + "；不可做：擅自动用别人的工具和料。";
        }
        if ("生产".equals(cat) || "水工".equals(cat)) {
            return "【空间选项】可做：走田埂、找干活的人说话、搭把手" + marketHint
                    + "；不可做：踩庄稼、私自开闸放水、拿走农具。";
        }
        if ("公共".equals(cat)) {
            return "【空间选项】可做：停留、说话、观望、歇脚" + marketHint
                    + "；不可做：占着道挡人、大声吵嚷、动公家的东西。";
        }
        if ("自然".equals(cat)) {
            return "【空间选项】可做：赶路、问路、找地方歇脚" + marketHint
                    + "；不可做：夜里独自往没灯的地方钻、踩踏青苗。";
        }
        return "【空间选项】可做：先看清地界、找人问一声再动" + marketHint
                + "；不可做：把不认识的院子当成自己家。";
    }

    /**
     * 此处营生属性（【空间选项】用）：像不像柜台、柜台上有没有货、有没有能动手的炉子/工台，
     * 以及这个世界里第一个能买卖的地点（给「去哪买」用）。
     *
     * @param shop     像店铺/集市
     *
     * @param stocked  柜台上有货
     *
     * @param workshop 有炉子/工台这类能做活的设施
     *
     * @param market   世界内第一个可买卖地点名（可空）
     */
    private record Commerce(boolean shop, boolean stocked, boolean workshop, String market) {

        /** 数据不明时的空口径。 */
        static final Commerce NONE = new Commerce(false, false, false, null);
    }

    /**
     * 读此处营生属性（只读；任何一步失败按空口径降级，绝不阻断感知）。
     *
     * @param projectId 项目 ID
     *
     * @param name      地点名（可空=只知道「这里」，仍返回世界内的可买卖地点）
     *
     * @return 营生属性（始终非空）
     */
    private Commerce commerceOf(Long projectId, String name) {
        if (projectId == null || locationRepository == null) {
            return Commerce.NONE;
        }
        try {
            java.util.List<com.holzyn.actor.domain.world.entity.ActorWorldLocation> locations =
                    locationRepository.findByProjectId(projectId);
            String market = null;
            for (com.holzyn.actor.domain.world.entity.ActorWorldLocation loc : locations) {
                if (loc != null && loc.getName() != null
                        && com.holzyn.actor.domain.item.provision.ProvisionKit.isShopLike(loc.getName())) {
                    market = loc.getName().trim();
                    break;
                }
            }
            // 没有店铺的世界（实测常见：只有「青溪村 / 临溪镇」两个地点）→ 退回第一个聚落：
            // 聚落本身就是交易发生的地方（与 ProvisionKit.forLocation 的铺货口径一致）。
            if (market == null) {
                for (com.holzyn.actor.domain.world.entity.ActorWorldLocation loc : locations) {
                    if (loc != null && loc.getName() != null
                            && com.holzyn.actor.domain.item.provision.ProvisionKit.isSettlement(loc.getName())) {
                        market = loc.getName().trim();
                        break;
                    }
                }
            }
            if (name == null || name.isBlank()) {
                return new Commerce(false, false, false, market);
            }
            String here = name.trim();
            boolean shop = com.holzyn.actor.domain.item.provision.ProvisionKit.isShopLike(here);
            boolean workshop = workshopLike(here);
            boolean stocked = false;
            // 柜台不一定叫「铺」：聚落里的集市同样有货（isMarketLike 与铺货同口径）
            if (com.holzyn.actor.domain.item.provision.ProvisionKit.isMarketLike(here)
                    && inventoryPort != null && itemProperties != null && itemProperties.isEnabled()) {
                Long locId = findLocationId(projectId, here, locations);
                if (locId != null) {
                    for (com.holzyn.actor.domain.item.model.ItemView it : inventoryPort.list(projectId, "loc:" + locId)) {
                        if (it != null && it.quantity() > 0 && !"currency".equalsIgnoreCase(it.category())) {
                            stocked = true;
                            break;
                        }
                    }
                }
            }
            return new Commerce(shop, stocked, workshop, market);
        } catch (Exception e) {
            log.debug("[感知] 营生属性读取失败（按空口径）: loc={} : {}", name, e.getMessage());
            return Commerce.NONE;
        }
    }

    /**
     * 是否「有炉子/工台、能动手做活」的地方。
     *
     * <p>刻意只收明确带设施的场所名：把「村子」判成作坊会让所有人在村口凭空打铁。</p>
     *
     * @param name 地点名（可空）
     *
     * @return true=能做活
     */
    private static boolean workshopLike(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String n = name.trim();
        return com.holzyn.actor.domain.item.provision.ProvisionKit.isShopLike(n)
                && (n.contains("铁") || n.contains("匠") || n.contains("锻") || n.contains("炉")
                || n.contains("窑") || n.contains("作坊") || n.contains("工坊") || n.contains("木工")
                || n.contains("磨") || n.contains("灶") || n.contains("织") || n.contains("染"));
    }

    /**
     * 按名字找地点表 ID（精确优先、其次包含；找不到返回 null）。
     *
     * @param projectId 项目 ID
     *
     * @param name      地点名
     *
     * @param locations 已取出的地点表（可空=现查）
     *
     * @return 地点 ID 或 null
     */
    private Long findLocationId(Long projectId, String name,
                                java.util.List<com.holzyn.actor.domain.world.entity.ActorWorldLocation> locations) {
        if (name == null || name.isBlank()) {
            return null;
        }
        java.util.List<com.holzyn.actor.domain.world.entity.ActorWorldLocation> rows = locations;
        if (rows == null) {
            if (locationRepository == null) {
                return null;
            }
            rows = locationRepository.findByProjectId(projectId);
        }
        String wanted = name.trim();
        for (com.holzyn.actor.domain.world.entity.ActorWorldLocation loc : rows) {
            if (loc != null && wanted.equals(loc.getName())) {
                return loc.getId();
            }
        }
        for (com.holzyn.actor.domain.world.entity.ActorWorldLocation loc : rows) {
            if (loc != null && loc.getName() != null && !loc.getName().isBlank()
                    && (loc.getName().contains(wanted) || wanted.contains(loc.getName()))) {
                return loc.getId();
            }
        }
        return null;
    }

    /**
     * 空间段的「机器可校验尾巴」（三方对账 · 第三腿）。
     *
     * <p><b>为什么要把坐标写进 prompt</b>：三方对账的第三腿必须是「模型真的看到了什么」，
     * 而不是「我们以为它看到了什么」。所以坐标以固定格式 {@code (x.xx,z.zz)@gs=N} 直接落进
     * 注入文本——验收脚本从这个真实 prompt 里抽出来的值，才配与权威行/渲染层逐位比对。</p>
     *
     * <p><b>为什么带「附近可达」清单</b>：只给一个点，重访时就只能比坐标；给了「周边能去哪、
     * 多远、什么方位」，才能验证「隔几天回来，他对这片空间的认知没变」——这正是本版验收的
     * 重访判据。清单本身是确定性的（同一位置必然同一清单），因此可逐位比对。</p>
     *
     * @param projectId 项目
     *
     * @param x         坐标 X（米）
     *
     * @param z         坐标 Z（米）
     *
     * @param fix       空间定值（可空=未装配统一读口径）
     *
     * @param gameSecond 游戏秒（可空）
     *
     * @return 尾巴文本（始终以「；」开头；为空返回空串）
     */
    private String spatialTail(Long projectId, double x, double z,
                               com.holzyn.actor.domain.mapnav.vo.SpatialFix fix, Long gameSecond) {
        if (npcSpatialView == null) {
            return "";
        }
        try {
            long gs = fix != null && fix.getGameSecond() > 0
                    ? fix.getGameSecond()
                    : (gameSecond == null ? 0L : gameSecond);
            StringBuilder sb = new StringBuilder("；坐标")
                    .append(com.holzyn.actor.domain.mapnav.service.NpcSpatialView.token(x, z, gs));
            if (fix != null && fix.getFeatureCode() != null && !fix.getFeatureCode().isBlank()) {
                sb.append("；所在要素 ").append(fix.getFeatureCode());
            }
            java.util.List<com.holzyn.actor.domain.mapnav.service.NpcSpatialView.Candidate> cands =
                    npcSpatialView.candidates(projectId, x, z,
                            com.holzyn.actor.domain.mapnav.service.NpcSpatialView.DEFAULT_CANDIDATE_LIMIT);
            if (!cands.isEmpty()) {
                sb.append("；附近可达");
                for (int i = 0; i < cands.size(); i++) {
                    com.holzyn.actor.domain.mapnav.service.NpcSpatialView.Candidate c = cands.get(i);
                    sb.append(i == 0 ? " " : "、")
                            .append(c.getRef()).append('(')
                            .append(Math.round(c.getDistanceM())).append("米,")
                            .append(c.getBearing()).append(')');
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("[感知] 空间校验尾巴生成失败（跳过）: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 附近角色文本（<b>权威口径</b>）：x/z 是米制地图坐标，附近角色同样取权威行。
     *
     * <p><b>为什么必须同源</b>：旧实现走沙盘行，而沙盘与权威是两个坐标系（0..500 vs
     * 0..1024 米）——拿权威坐标去比沙盘坐标，得到的「谁在我旁边」是随机结果，
     * 会让模型说出「看到某某经过」而对方根本没在这条街上。</p>
     *
     * @param projectId 项目
     *
     * @param characterRef 角色引用（排除自身）
     *
     * @param x 参考点 X（米）
     *
     * @param z 参考点 Z（米）
     *
     * @param verb {@code "看到"}（驻留）或 {@code "迎面遇到"}（在途）
     *
     * @return 文本（无附近角色返回空串）
     */
    private String nearbyTextByAuthority(Long projectId, String characterRef,
                                         double x, double z, String verb) {
        if (npcSpatialView == null) {
            return "";
        }
        try {
            java.util.List<com.holzyn.actor.domain.mapnav.vo.SpatialFix> near =
                    npcSpatialView.nearbyAuthority(projectId, x, z, characterRef,
                            com.holzyn.actor.domain.mapnav.service.NpcSpatialView.DEFAULT_NEARBY_RADIUS_M, 2);
            if (near.isEmpty()) {
                return "";
            }
            java.util.List<String> names = new ArrayList<>();
            for (com.holzyn.actor.domain.mapnav.vo.SpatialFix f : near) {
                String name = f.getNpcName();
                if (name == null || name.isBlank()) {
                    name = npcName(projectId, f.getNpcRef());
                }
                if (name != null && !name.isBlank()) {
                    names.add(name.trim());
                }
            }
            if (names.isEmpty()) {
                return "";
            }
            String joined = String.join("与", names);
            return "看到".equals(verb) ? "看到" + joined + "正从身旁经过" : verb + joined;
        } catch (Exception e) {
            log.debug("[感知] 附近角色（权威）组装失败（跳过）: ref={} : {}", characterRef, e.getMessage());
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
