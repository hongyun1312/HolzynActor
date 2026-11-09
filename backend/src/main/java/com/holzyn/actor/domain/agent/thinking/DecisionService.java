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

package com.holzyn.actor.domain.agent.thinking;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.domain.npc.profile.entity.ActorAgentProfile;
import com.holzyn.actor.domain.agent.brain.service.BrainProfileService;
import com.holzyn.actor.domain.agent.perception.PerceptionService;
import com.holzyn.actor.domain.agent.runtime.DecisionSource;
import com.holzyn.actor.domain.agent.runtime.DecisionSources;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.character.service.PromptService;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 思考组件（阶段 C，domain/agent/thinking）——决策收编 {@code ActionEngine.generateDecision}。
 * <p>定位：把 Perception+Memory 的输入转化为
 * <b>有方向、可连贯的行动/对话</b>—— 收编单步决策（复用 ActionEngine 决策 Schema/重试/程序化兜底，
 * 行为不变），输入侧由调用方提供情境；brainProfile 作为<b>权威配置源</b>门控认知深度
 * （{@code brain_level=programmatic} 直接程序化决策 0 AI 成本；full/light 走 AI 决策链 + 程序化兜底）。</p>
 * <p>收编内容（原 {@code ActionEngine.generateDecision/buildPersonaSummary/firstBehavior/
 * AI（action_gen 模板 + action_decision Schema 校验 + 重试≤2）+ 程序化兜底；
 * 感知环节 recordStep（step_type=perception）与重试 attempt 分次 trace 一并迁入（决策链路）。</p>
 * <p>目标/计划/反思 与决策接 RAG 留阶段 D；planning/reflection 组件开关当前仅声明不消费。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionService {

    /** 行动决策 JSON Schema 允许的 type 枚举（与旧 ActionEngine 一致，原样迁入） */
    private static final List<String> ACTION_TYPES = List.of(
            "move", "interact", "speak", "trade", "fight", "flee", "help", "schedule", "rest", "custom");

    /** 行动生成最大输出 token */
    private static final int ACTION_MAX_TOKENS = 512;

    /** 生成失败最大重试次数 */
    private static final int MAX_RETRY = 2;

    /** 字段最大长度（角色状态持久化） */
    private static final int FIELD_MAX = 255;

    @org.springframework.beans.factory.annotation.Autowired(required = false)

    private com.holzyn.actor.domain.growth.persona.PersonaService personaService;
    private final AiSpringChatGateway aiChatGateway;
    private final PromptService promptService;
    private final UsageLogService usageLogService;
    private final PerceptionService perceptionService;
    private final WorldClockService worldClockService;
    private final BrainProfileService brainProfileService;

    /**
     * 世界事实查询：AI 决策前把当时 stateVersion 写入 trace，保证
     * {@code actor_ai_call_log} 能复现“这条决策看到的是哪个世界版本”。
     * 字段注入保持构造器不变，null=未装配（单测）跳过，不影响决策。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.world.fact.service.WorldFactQuery worldFactQuery;

    /** 世界事实待处理信号（阶段门控/空间快判让路 + 决策输入版本留证；null=未装配跳过）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.world.fact.service.WorldFactDecisionSignal worldFactDecisionSignal;
    private final ObjectMapper objectMapper;

    /** 沙盘状态服务（S2 npc:thought T1 决策源：AI 决策产物写入想法气泡；字段注入保持构造器不变） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.sandbox.service.WorldSandboxStateService sandboxStateService;

    /**
     * ToM 推断深度注入开关（realtime-decision.coordination.tom-inject-enabled；
     * 主决策 action_gen 的 {{tom_section}} 填充；字段注入保持构造器不变）
     */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.realtime-decision.coordination.tom-inject-enabled:true}")
    private boolean tomInjectEnabled = true;

    /** 前瞻推演服务（决策解析成功后记录 actor_foresight_record；null=未装配跳过） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.ForesightService foresightService;

    /** Prompt 工程指标（决策契约格式合规率埋点；字段注入保持构造器不变） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.prompt.PromptMetricsService promptMetrics;

    /** 此刻可发生空间（决策前空间保障 + 决策后空间内行动记录；字段注入保持构造器不变） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.space.GrowthSpaceService growthSpaceService;

    /**
     * 三条护栏（stage_compliance/space_compliance/developmental_knowledge_bound；
     * 决策解析后校验，blocked → 程序化 continue 兜底；字段注入保持构造器不变）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.guard.GrowthGuardService growthGuard;

    /** 增长指标（决策空间命中/空间外计数；字段注入保持构造器不变） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.metrics.GrowthMetricsService growthMetrics;

    /**
     * 决策模型 判定门（space_decision 场景——特殊 NPC action 决策前置空间候选
     * 选择；字段注入保持构造器不变，null=未装配[单测]跳过 路径回退 Qwen 现状）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.ai.decision.gate.DecisionGate decisionGate;

    /**
     * C 层社交聚集（社会对话层修复 C 层 独处 NPC 自发流向社交中心[茶馆/集市/村口…]，
     * 注入 空间候选；字段注入保持构造器不变，null=未装配[单测]跳过社交候选，行为回到修复前）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.space.SocialGatheringService socialGatheringService;

    /** 状态行（precondition 程序化判定用需求值；字段注入保持构造器不变，null=状态未知按 UNKNOWN 宽松） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.npc.state.service.NpcStateService npcStateService;

    /** 角色主表（precondition 在场者/全村名单程序化判定用；字段注入保持构造器不变，null=名单未知） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository characterRepository;

    /** 统一环境服务（天气 precondition 程序化判定用；字段注入保持构造器不变，null=天气未知） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.world.service.EnvironmentContextService environmentContextService;

    /**
     * 生理需求→行动决策耦合服务（生理需求耦合轮：危险区程序化强制前置检查 +
     * 低值区生理候选注入 + 轻行动抑制配比；字段注入保持构造器不变，null=未装配[单测]
     * 跳过耦合，行为回到修复前）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.npc.physio.PhysioNeedService physioNeedService;

    /**
     * BrainStrategy 可插拔决策策略路由器（P2-3：决策管线显式化为有序策略链——
     * 特殊链=危险强制→空间选择→AI 深思→程序化兜底，链式责任首中即胜、命中写 trace；
     * 字段注入保持构造器不变，null=未装配[单测]走既有内联管线，行为与 P2-3 前一致）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.brain.strategy.BrainStrategyRouter brainStrategyRouter;

    /**
     * 主动记忆检索（ProactAgent「Ask Only When Needed」，P1 ②-1）：决策全局自问环节——
     * AI 按情境判定是否需要主动想起/查证，需要时定向检索三源（角色记忆+世界大事记+知识卡）
     * 组装【主动想起】段，经 {@link AiCallContext} holder 传递给决策路径（AI 深思
     * {{proactive_memory}} 占位符 / 决策模型 快判情境文本）；programmatic 档 0 AI 除外；
     * fail-open 零阻断；字段注入保持构造器不变，null=未装配[单测]跳过自问）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.holzyn.actor.domain.agent.memory.ProactiveRetrievalService proactiveRetrievalService;

    /**
     * 潜在世界预测注入（P1 ③-2）：角色「脑内预演世界走向」——程序化趋势摘要（历史快照环比）
     * + AI 预测【未来一天世界走向】段（缓存至下一游戏日）；AI 深思经 action_gen
     * {{world_outlook}} 占位符（8 参重载）/ 决策模型 快判情境文本首部（仅程序化趋势摘要，零 AI）
     * 注入；fail-open 零阻断；字段注入保持构造器不变，null=未装配[单测]跳过预测）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.holzyn.actor.domain.world.service.WorldOutlookService worldOutlookService;

    /**
     * 世界地图只读门面（M3 §4.5 ③ 决策情境路程远近/距离加权：空间候选带目标地点时附
     * 「近/中/远 + 路程口语」，决策可据此权衡远近；字段注入保持构造器不变，null=未装配
     * [单测]不附加距离标注，行为与 M3 前一致）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.worldmap.service.WorldMapQueryService worldMapQueryService;

    /** 沙盘状态仓库（M3 决策情境路程远近用当前坐标；字段注入保持构造器不变，null=跳过） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.sandbox.repository.ActorSandboxStateRepository sandboxStateRepository;

    /**
     * 统一 AI 上下文注入器（批3 决策与感知：action_gen 情境缺【当前状态】段时，前置
     * 段2D 当前世界动态 + 段2E 当前状态——STIMULUS 个性刺激情境「只知刺激不知处境」的
     * 补齐；周期感知情境已含 段2E/2D，跳过防重复；字段注入保持构造器不变，null=未装配
     * [单测]跳过注入，行为与批3 前一致）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    /**
     * 规划服务（目标状态机：卡住的目标必须进入决策——「【卡住的目标】」段）。
     * <p>为什么放在决策侧：卡住的目标已被生命周期扫描移出 active 名单，
     * 因而不再出现在【当前目标】里；如果没有人把它重新摆到桌面上，
     * 角色就会像什么都没发生一样继续过日子，把「受挫」变成「遗忘」。</p>
     * <p>⚠️ {@code @Lazy}：PlanningService 侧依赖链较长（目标/计划/预算/记忆），
     * 且决策服务是热点入口，懒注入避免启动期环。</p>
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PlanningService planningService;

    /**
     * 生成行动决策（thinking 组件入口）：按 brainProfile 门控认知深度。
     * <p><b>脑力门控</b>：{@code brain_level=programmatic}（纯程序化档）→ 直接程序化决策
     * （0 AI 成本）；full/light → 原 AI 决策链（Schema 校验 + 重试≤2 + 程序化兜底）。
     * 决策感知环节 recordStep 与重试 attempt 分次 trace（决策链路）随收编一并迁入。</p>
     *
     * @param ch 角色实体
     *
     * @param userId 归属用户 ID
     *
     * @param situation 当前情境（可空=服务按游戏时间+角色状态组装）
     *
     * @return 决策 Map（type/action/target/params/reason/urgency/duration）
     */
    public Map<String, Object> generateDecision(ActorCharacter ch, Long userId, String situation) {
        return generateDecision(ch, userId, situation, false);
    }

    /**
     * 生成行动决策（thinking 组件入口，O6 发育演化 4 参重载）：按 brainProfile 门控认知深度。
     * <p><b>脑力门控</b>：{@code brain_level=programmatic}（纯程序化档）→ 直接程序化决策
     * （0 AI 成本）；full/light → 原 AI 决策链（Schema 校验 + 重试≤2 + 程序化兜底）。
     * 决策感知环节 recordStep 与重试 attempt 分次 trace（决策链路）随收编一并迁入。</p>
     * <p><b>hasStimulus（O6 发育演化）</b>：是否外部刺激触发的决策（STIMULUS 即时通道为 true；
     * 引擎每小时主决策即使带感知情境文本也传 false——感知不是刺激，阶段门控据此判定
     * 「空闲接管/刺激让路」）。</p>
     *
     * @param ch 角色实体
     *
     * @param userId 归属用户 ID
     *
     * @param situation 当前情境（可空=服务按游戏时间+角色状态组装）
     *
     * @param hasStimulus 是否有外部刺激（true=刺激决策，阶段门控让路）
     *
     * @return 决策 Map（type/action/target/params/reason/urgency/duration）
     */
    public Map<String, Object> generateDecision(ActorCharacter ch, Long userId, String situation,
                                                boolean hasStimulus) {
        // 脑力档位（brainProfile 权威配置源）：链路径组链输入 + 内联管线门控
        String brainLevel = resolveBrainLevel(ch);
        log.info("[Thinking] 决策生成（brainLevel={}）: char={} name={}", brainLevel, ch.getId(), ch.getName());
        try {
            // 决策情境统一补齐（闭环修复）：调用方传 null（引擎 tick）或传自定义情境
            // （玩家干预/结局摘要）时，必须把「感知 + 我所知道的世界事实」补齐——否则
            // 事实已入库、knowledge 已建立，决策输入却看不见，世界→角色这一跳断开。
            String effectiveSituation = ensureDecisionSituation(ch, situation);
            // 主动记忆检索自问环节（决策全局挂载，P1 ②-1）：AI 按情境判定是否需要主动想起/查证，
            // 需要时定向检索三源并组装【主动想起】段（经 AiCallContext holder 传递给 AI 深思
            // {{proactive_memory}} 占位符与 决策模型 快判情境文本）；programmatic 档 0 AI 除外；
            // 判定失败/冷却中/无命中 → 不注入（fail-open 零阻断，含异常隔离）
            runProactiveRetrieval(ch, userId, effectiveSituation, brainLevel);
            // BrainStrategy 可插拔决策策略链（P2-3）：router 装配时按角色类型+脑力档位组链
            // （SPECIAL 链=危险强制→阶段门控→空间选择→AI 深思→程序化兜底；programmatic 档=危险强制→兜底），
            // 链式责任首中即胜、命中写 trace（双过程显式化：快通道优先、System 2 深思兜底）；
            // 链空回退既有内联管线（理论不达——特殊链恒有程序化兜底）
            if (brainStrategyRouter != null && ch != null && ch.getProjectId() != null) {
                com.holzyn.actor.domain.agent.brain.strategy.DecisionOutcome outcome =
                        brainStrategyRouter.decide(new com.holzyn.actor.domain.agent.brain.strategy.DecisionContext(
                                ch.getProjectId(), ch, userId, com.holzyn.actor.domain.agent.brain.strategy.NpcKind.SPECIAL,
                                brainLevel, effectiveSituation, null, null, hasStimulus));
                if (outcome != null) {
                    // P0-03 决策源可追溯：链命中由策略名判定来源（programmatic_fallback/physio_danger
                    // → FALLBACK_PROGRAMMATIC；其余 AI 策略 → AI），打标进决策 Map（落库侧透传）
                    DecisionSource chainSource = DecisionSources.fromStrategy(outcome.hitStrategy());
                    if (outcome.postProcess()) {
                        return DecisionSources.tag(postProcessDecision(ch, outcome.decision()), chainSource);
                    }
                    return DecisionSources.tag(outcome.decision(), chainSource);
                }
            }
            // ===== 既有内联管线（router 未装配[单测]；行为与 P2-3 前一致） =====
            // 生理需求危险区前置检查（生理需求耦合轮）：任一生理四需低于危险阈值 →
            // 程序化强制生理行动（0 AI）——快饿死/尿急到极限的人不会因决策系统失效而继续发呆；
            // 本检查位于脑力门控之前，对 programmatic/full/light 全部档位生效；
            // 实时打断通道（PHYSIOLOGICAL_URGE）已让危险区角色「放下手头事」，此处兜底一切触发源
            if (physioNeedService != null && ch != null && ch.getProjectId() != null) {
                Map<String, Object> physioDanger = physioNeedService.dangerDecision(ch.getProjectId(), ch);
                if (physioDanger != null) {
                    AiCallContext.recordStep(ch.getProjectId(), "agent_decision", "thinking",
                            "生理需求危险区强制（0 AI）：" + truncate(
                                    String.valueOf(physioDanger.getOrDefault("action", "")), 40), 0);
                    log.info("[Thinking] 生理需求危险区强制决策: char={} 行动={}",
                            ch.getId(), physioDanger.get("action"));
                    // P0-03：生理危险区程序化强制=0 AI 产出，打标 FALLBACK_PROGRAMMATIC
                    return DecisionSources.tag(postProcessDecision(ch, physioDanger),
                            DecisionSource.FALLBACK_PROGRAMMATIC);
                }
            }
            // 脑力门控：programmatic 档直接程序化（AI 0 成本，对齐「分层脑力 + 程序化兜底」原则）
            if ("programmatic".equals(brainLevel)) {
                log.debug("[Thinking] programmatic 档决策（0 AI 成本）: char={}", ch.getId());
                AiCallContext.recordStep(ch.getProjectId(), "agent_decision", "thinking",
                        "思考组件：programmatic 档程序化决策（0 AI 成本，brainProfile 门控）", 0);
                return DecisionSources.tag(programmaticDecision(ch), DecisionSource.FALLBACK_PROGRAMMATIC);
            }
            // 空间候选选择：空间有效 + space_decision 场景开时，特殊 NPC 的 action 决策
            // 由 决策模型 在空间 possible_actions + 通用三项内亚秒选择（40s→亚秒 ≈40×）——Qwen 只负责
            // 低频生成空间（语义质量），决策模型 做「此刻挑一个」的高频快判（差异=真人随机波动）。
            // 判定失败/未启用/空间无效 → 回退 Qwen 生成式决策（零阻断）。
            // （决策前空间保障已内置于 trySpaceDecision：空间缺失/过期才 AI 生成，有效直接复用零成本）
            Map<String, Object> spaceDecision = trySpaceDecision(ch, effectiveSituation);
            if (spaceDecision != null) {
                // 与 AI 路径同后处理：护栏 + 前瞻 + 沙盘想法；P0-03：快判=AI 通道产出，打标 AI
                return DecisionSources.tag(postProcessDecision(ch, spaceDecision), DecisionSource.AI);
            }
            // AI 深思（System 2）：action_gen 生成式决策（重试≤2 + 程序化回退指标埋点）；
            // 全部重试失败 → null → 程序化兜底 continue（与 P2-3 前行为一致）
            Map<String, Object> aiDecision = deliberateDecision(ch, userId, effectiveSituation);
            if (aiDecision != null) {
                // P0-03：AI 深思成功=真实 AI 产出，打标 AI
                return DecisionSources.tag(postProcessDecision(ch, aiDecision), DecisionSource.AI);
            }
            return DecisionSources.tag(programmaticDecision(ch), DecisionSource.FALLBACK_PROGRAMMATIC);
        } finally {
            // 主动检索段随本次决策结束清理（防虚拟线程复用泄漏）
            com.holzyn.actor.domain.debug.service.AiCallContext.clearProactiveSegment();
            // MUSE 策略偏好段随本次决策结束清理（防虚拟线程复用泄漏）
            com.holzyn.actor.domain.debug.service.AiCallContext.clearStrategyPreference();
        }
    }

    /**
     * 主动记忆检索自问环节（决策全局挂载）：委托 {@link ProactiveRetrievalService#maybeRetrieve}，
     * 命中结果【主动想起】段经 {@link AiCallContext#holdProactiveSegment} 持有，供
     * {@link #trySpaceDecision}（决策模型 快判情境文本）与 {@link #deliberateDecision}
     * （{{proactive_memory}} 占位符）读取。programmatic 档（0 AI 语义）不执行；
     * 一切失败静默跳过（fail-open 零阻断）。
     */
    private void runProactiveRetrieval(ActorCharacter ch, Long userId, String situation, String brainLevel) {
        if (proactiveRetrievalService == null || ch == null || ch.getProjectId() == null
                || "programmatic".equals(brainLevel)) {
            return;
        }
        try {
            var result = proactiveRetrievalService.maybeRetrieve(ch.getProjectId(),
                    com.holzyn.actor.common.NpcRefs.of(ch.getId()), userId, situation);
            if (result != null && result.segment() != null && !result.segment().isBlank()) {
                com.holzyn.actor.domain.debug.service.AiCallContext.holdProactiveSegment(result.segment());
                log.info("[Thinking] 主动记忆检索命中并注入: char={} 命中 {} 条",
                        ch.getId(), result.hits() == null ? 0 : result.hits().size());
            }
        } catch (Exception e) {
            // 自问能力异常隔离：绝不干扰决策主流程
            log.warn("[Thinking] 主动记忆检索失败（零阻断）: char={} : {}", ch.getId(), e.getMessage());
        }
    }

    /**
     * AI 深思决策（公开，BrainStrategy DeliberateStrategy 委托；内联管线同用）：
     * action_gen 模板 + ReAct 新契约解析 + 重试≤2 + 用量/trace 记账。
     * <p>与 P2-3 前行为一致：全部重试失败 → 返回 null（调用方程序化兜底 continue），
     * 重试最后一次标记 trace degraded + 程序化回退指标埋点；感知环节 recordStep
     * 与每次重试 attempt 分次 trace（同一条决策链路）。</p>
     *
     * @param ch 角色实体
     *
     * @param userId 归属用户 ID
     *
     * @param situation 当前情境（可空）
     *
     * @return 内部规范决策 Map（未过 Schema 校验/全部重试失败 → null）
     */
    public Map<String, Object> deliberateDecision(ActorCharacter ch, Long userId, String situation) {
        // 决策输入统一补齐（闭环修复）：null/自定义情境也要含感知与【我所知道的事】，
        // 否则事实层已写、knowledge 已有，AI 深思的 prompt 仍看不见——L4 断链根因。
        situation = ensureDecisionSituation(ch, situation);
        String personaSummary = buildPersonaSummary(ch);
        // 批3 决策与感知：action_gen 情境缺【当前状态】段时前置 段2D 当前世界动态 +
        // 段2E 当前状态（STIMULUS 个性刺激情境「只知刺激不知处境」的补齐——AI 带着当下
        // 时节天气与自身生理/情绪做决定，真人感；周期感知情境已含 段2E/2D，跳过防重复）
        String decisionSituation = enrichDecisionSituation(ch, situation);
        // 把决策输入的 stateVersion + 决策情境快照落 trace（非 AI 步骤，零 AI 成本）；
        // user_prompt 存情境快照（含【我所知道的事】），让验收能直接检索事实文本。
        recordDecisionInputVersion(ch, decisionSituation, decisionSituation);
        // 主动记忆检索【主动想起】段（决策全局自问命中时）：经 action_gen 模板
        // {{proactive_memory}} 占位符显式注入（AI 深思被主动想起的记忆影响；
        // 未命中/无段 → 空占位，行为零变化）
        String proactiveMemory = com.holzyn.actor.domain.debug.service.AiCallContext.readProactiveSegment();
        // MUSE 策略切换【策略偏好】段（策略链内 FamiliarityStrategy 命中时）：经 action_gen
        // 模板 {{strategy_preference}} 占位符显式注入（AI 深思被「保守/激进」偏好引导；
        // 未命中/均衡 → 空占位，行为零变化）
        String strategyPreference = com.holzyn.actor.domain.debug.service.AiCallContext.readStrategyPreference();
        // 潜在世界预测注入（P1 ③-2）：【未来一天世界走向】段（WorldOutlookService 按游戏日缓存，
        // 同日不重复 AI；无世界时钟/AI 失败 → fail-open 回退趋势摘要，非 null 保证有段；
        // 未装配/无数据 → null 空占位，行为零变化）——AI 深思被「世界演化预期」引导
        // （「我不行动世界会怎样」——明天粮价/集市/势力/人心的可能走向）
        final String worldOutlook = resolveWorldOutlook(ch);
        // 数组引用：lambda 内可写（最后错误信息仅用于日志兜底展示）
        Exception[] lastError = {null};
        // B5：感知环节 + 每次重试 attempt 共享业务动作级 trace_id（同一条决策链路）
        long perceptionStart = System.currentTimeMillis();
        String perceptionNote = "行动决策感知组装（角色 special:" + ch.getId()
                + (situation == null || situation.isBlank() ? "" : "，情境 " + Math.min(situation.length(), 40) + " 字")
                + "）";
        AiCallContext.recordStep(ch.getProjectId(), "agent_decision", "perception",
                perceptionNote, System.currentTimeMillis() - perceptionStart);
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            final int att = attempt;
            try {
                Map<String, Object> decided = AiCallContext.run("action", ch.getId(), att, () -> {
                    try {
                // ：主决策 ToM 推断深度段（按角色卡 OCEAN 宜人性×神经质；开关关/无卡=空串）
                String tomSection = tomInjectEnabled ? tomSectionOf(ch) : "";
                // 主动记忆检索【主动想起】段：无段走旧 5 参签名（行为零变化）；有段走 6 参
                // 新重载注入 {{proactive_memory}} 占位符（AI 深思被主动想起的记忆影响）；
                // MUSE 策略切换【策略偏好】段：有段经 {{strategy_preference}} 占位符注入
                // （AI 深思被「保守/激进」偏好引导；无段保持旧签名调用，行为零变化）；
                // 潜在世界预测【未来一天世界走向】段：有段经 {{world_outlook}} 占位符注入
                // （AI 深思被「世界演化预期」引导；无段保持旧签名调用，行为零变化）
                String prompt;
                if (worldOutlook == null || worldOutlook.isBlank()) {
                    prompt = buildActionGenPromptNoOutlook(userId, ch, personaSummary, decisionSituation, tomSection,
                            proactiveMemory, strategyPreference);
                } else if (proactiveMemory == null || proactiveMemory.isBlank()) {
                    prompt = (strategyPreference == null || strategyPreference.isBlank())
                            ? promptService.buildActionGenPrompt(userId, ch.getProjectId(),
                                    personaSummary, decisionSituation, tomSection, "", "", worldOutlook)
                            : promptService.buildActionGenPrompt(userId, ch.getProjectId(),
                                    personaSummary, decisionSituation, tomSection, "", strategyPreference, worldOutlook);
                } else if (strategyPreference == null || strategyPreference.isBlank()) {
                    prompt = promptService.buildActionGenPrompt(userId, ch.getProjectId(),
                            personaSummary, decisionSituation, tomSection, proactiveMemory, "", worldOutlook);
                } else {
                    prompt = promptService.buildActionGenPrompt(userId, ch.getProjectId(),
                            personaSummary, decisionSituation, tomSection, proactiveMemory, strategyPreference, worldOutlook);
                }
                AiChatRequest req = new AiChatRequest(null, List.of(
                        new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                        new AiChatRequest.ChatMessage("user", prompt)), 0.7, ACTION_MAX_TOKENS, true);
                long startMs = System.currentTimeMillis();
                AiChatResult result = aiChatGateway.chatCompletion(userId, ch.getProjectId(), null, req);
                usageLogService.record(userId, ch.getProjectId(), ch.getId(), result.providerId(), result.model(),
                        "action", result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(),
                        (int) (System.currentTimeMillis() - startMs));
                String json = com.holzyn.actor.domain.agent.prompt.DecisionReActParse.extractDecisionJson(result.content());
                if (json == null) {
                    throw new BizException("AI 未返回有效 JSON");
                }
                JsonNode node = objectMapper.readTree(json);
                // Prompt 工程：ReAct 新契约解析
                // （action_type 枚举 continue/start_action/move_to/speak_to/wait + reasoning + action_detail
                // + priority + expected_duration_minutes → 内部规范 Map 含旧键映射，下游执行链零改动）
                Map<String, Object> decision = DecisionService.validateDecision(node, objectMapper);
                if (decision == null) {
                    throw new BizException("行动决策不符合 Schema 要求");
                }
                // ：格式合规率埋点（决策契约解析成功）
                if (promptMetrics != null) {
                    promptMetrics.recordJsonOk("action");
                }
                return decision;
            } catch (Exception e) {
                        // 最后一次失败后调用方将做程序化兜底 → 标记本条 trace 为 degraded
                        if (att == MAX_RETRY) {
                            AiCallContext.markDegraded();
                        }
                        throw e;
                    }
                });
                // 世界事实因果挂接（闭环修复）：本次深思是在「有到期事实」的让路信号下发生的，
                // 把事实来源事件写进决策 JSON（_worldFact），行动事件据此挂因果父链；
                // 无待处理事实/事实无来源事件 → 决策原样返回（零影响）。
                tagWorldFactCause(ch, decided);
                return decided;
            } catch (Exception e) {
                lastError[0] = e;
                log.warn("[Thinking] 行动决策生成第 {} 次失败: {}", attempt, e.getMessage());
            }
        }
        log.warn("[Thinking] 行动决策 AI 生成失败（{}），使用程序化兜底",
                lastError[0] == null ? "未知" : lastError[0].getMessage());
        // ：程序化回退指标（格式合规率分母）
        if (promptMetrics != null) {
            promptMetrics.recordFallback("action", "决策 AI 生成失败，程序化兜底（continue 当前行为）");
        }
        return null;
    }

    /**
     * 读取潜在世界预测【未来一天世界走向】段（P1 ③-2；未装配/异常 → null 空占位，
     * fail-open 零阻断——异常已隔离，绝不影响决策主流程）。
     */
    private String resolveWorldOutlook(ActorCharacter ch) {
        if (worldOutlookService == null || ch == null || ch.getProjectId() == null) {
            return null;
        }
        try {
            return worldOutlookService.outlookSegment(ch.getProjectId());
        } catch (Exception e) {
            log.warn("[Thinking] 世界走向预测段获取失败（空占位）: char={} : {}", ch.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 把本次 AI 决策输入的 stateVersion 写入决策 trace。
     * <p>只写非 AI 步骤，不改变决策内容；未装配事实查询/异常时静默跳过。
     * 验收可查 {@code actor_ai_call_log.scene='agent_decision'} 且
     * {@code step_type='state_version'} 的 content。</p>
     *
     * @param ch 角色实体
     *
     * @param situation 已组装的决策情境
     *
     * @param promptSnapshot 决策情境快照（实际进入 action_gen 的事实段；可空）
     */
    private void recordDecisionInputVersion(ActorCharacter ch, String situation, String promptSnapshot) {
        if (worldFactQuery == null || ch == null || ch.getProjectId() == null || ch.getId() == null) {
            return;
        }
        try {
            Long gameSecond = worldClockService == null ? null
                    : worldClockService.currentEngineGameSecond(ch.getProjectId());
            long gs = gameSecond == null ? 0L : gameSecond;
            String version = worldFactQuery.stateVersion(ch.getProjectId(), gs);
            if (version == null || version.isBlank()) {
                return;
            }
            String content = "[世界状态版本 " + version + "] 决策输入字数="
                    + (situation == null ? 0 : situation.length()) + " char=" + ch.getId();
            // user_prompt 存决策情境快照（含【我所知道的事】），不是为复制完整模板；
            // 这样 actor_ai_call_log 单行即可核对「本轮事实是否进入决策输入」。
            // 快照行必须带 character_id（复验缺陷：此前恒为 null，取证只能按 prompt 里的
            // 姓名反查，重名角色就串了）。
            AiCallContext.recordStep(ch.getProjectId(), ch.getId(), "agent_decision", "state_version",
                    content, 0, promptSnapshot);
        } catch (Exception e) {
            log.warn("[Thinking] 决策输入版本 trace 写入失败（跳过）: char={} : {}", ch.getId(), e.getMessage());
        }
    }

    /**
     * 情境里是否已含「事实段」（判重口径）。
     *
     * <p>两种合法形态：有认知时是 {@code 【我所知道的事】} 段；零认知时 {@code worldFactsSegment}
     * 只给一行 {@code [世界状态版本 …]}（要求零认知角色也能复现世界版本）。只按前者判重，
     * 后者就会被再追加一次。</p>
     *
     * @param situation 已装配的情境（可空）
     *
     * @return true=已含事实段（无需再补）
     */
    private static boolean containsFactsSegment(String situation) {
        if (situation == null || situation.isEmpty()) {
            return false;
        }
        return situation.contains("【我所知道的事】") || situation.contains("[世界状态版本");
    }

    /**
     * 决策情境统一补齐（角色—世界闭环修复）。
     *
     * <p><b>为什么需要</b>：事实层与 knowledge 只解决“世界知道什么”，决策输入才是
     * “角色看得见什么”。三条路径曾各自为政——手动触发器组装感知情境、引擎 tick 传
     * null、玩家干预/结局摘要传自定义短情境；只有第一条含【我所知道的事】。结果就是
     * 事实已入库、knowledge 已建立，L4 仍判 FAIL。</p>
     *
     * <p><b>本方法做两件事</b>：① 情境为空 → 走 {@link #buildSituation(ActorCharacter)}
     * 的全量感知组装（含状态/世界动态/事实段）；② 情境非空但不含【我所知道的事】→
     * 用 {@link PerceptionService#worldFactsSegment} 的同一格式化入口补齐事实段
     * （不复制 WorldFactQuery 的拼串）。失败/未装配 → 原样返回，零阻断。</p>
     *
     * @param ch 角色实体（可空）
     *
     * @param situation 调用方情境（可空）
     *
     * @return 含感知与事实段的决策情境
     */
    private String ensureDecisionSituation(ActorCharacter ch, String situation) {
        if (ch == null || ch.getProjectId() == null) {
            return situation;
        }
        try {
            String effective = (situation == null || situation.isBlank()) ? buildSituation(ch) : situation;
            // 到期约定置顶（闭环修复的“影响”半边）：只把事实塞进 8 条【我所知道的事】里，
            // 模型会把它当背景；即将到期的约定必须出现在情境最前，才可能驱动本次行动。
            // 已在情境内（重复调用）则不重复插段。
            String urgent = urgentFactSegment(ch);
            if (urgent != null && !urgent.isBlank()
                    && (effective == null || !effective.contains("【待处理约定】"))) {
                effective = effective == null || effective.isBlank() ? urgent : urgent + "\n\n" + effective;
            }
            // 判重不能只看【我所知道的事】——零认知角色的 worldFactsSegment 只会产出
            // 一行 [世界状态版本 …]（digest 为空），既有情境里其实已经有这一行却判不出重复，
            // 于是同一行版本标识进 prompt 两次（实跑 18/1215 命中）。
            if (perceptionService == null || (effective != null && containsFactsSegment(effective))) {
                return effective;
            }
            Long gs = worldClockService == null ? null
                    : worldClockService.currentEngineGameSecond(ch.getProjectId());
            String facts = perceptionService.worldFactsSegment(ch.getProjectId(),
                    com.holzyn.actor.common.NpcRefs.of(ch.getId()), gs);
            if (facts == null || facts.isBlank()) {
                return effective;
            }
            return effective == null || effective.isBlank() ? facts : effective + "\n\n" + facts;
        } catch (Exception e) {
            log.warn("[Thinking] 决策情境事实段补齐失败（原样情境继续）: char={} : {}",
                    ch.getId(), e.getMessage());
            return situation;
        }
    }

    /**
     * 世界事实因果挂接：把本次决策所响应的到期事实写进决策 JSON 的 {@code _worldFact}。
     *
     * <p>为什么放在决策 Map 而不是直接在处理器里查库：行动事件的父链由
     * {@code AgentActionService} 在发布时读取决策 JSON，决策侧只负责“我当时在为哪条事实
     * 做决定”。这样手动触发与引擎 tick 两条路径共用同一段因果逻辑，且不新增写入口。</p>
     *
     * <p>只挂 {@code sourceEventId} 非空的事实（要求事实变更必须有来源事件；
     * 历史脏数据没有来源时宁可不画因果边，也不伪造父事件）。异常/未装配 → 原样返回。</p>
     *
     * @param ch 角色实体
     *
     * @param decision 决策 Map（可空/不可变 Map 时跳过）
     */
    private void tagWorldFactCause(ActorCharacter ch, Map<String, Object> decision) {
        if (decision == null || worldFactDecisionSignal == null || ch == null
                || ch.getProjectId() == null || ch.getId() == null) {
            return;
        }
        try {
            com.holzyn.actor.domain.world.fact.entity.ActorWorldFact fact =
                    worldFactDecisionSignal.nearestDueFact(ch.getProjectId(),
                            com.holzyn.actor.common.NpcRefs.of(ch.getId()), null);
            if (fact == null || fact.getSourceEventId() == null) {
                return;
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("factId", fact.getId());
            meta.put("factKey", fact.getFactKey());
            meta.put("sourceEventId", fact.getSourceEventId());
            meta.put("dueGameSecond", fact.getValidToGameSecond());
            decision.put("_worldFact", meta);
            AiCallContext.recordStep(ch.getProjectId(), "agent_decision", "brain_strategy",
                    "决策因果挂接：待处理事实 " + fact.getFactKey()
                            + " → 来源事件 " + fact.getSourceEventId(), 0);
        } catch (Exception e) {
            log.warn("[Thinking] 世界事实因果挂接失败（决策原样继续）: char={} : {}",
                    ch.getId(), e.getMessage());
        }
    }

    /**
     * 到期约定置顶段（【待处理约定】）：把“即将到期的、角色知道的事实”放在情境最前。
     *
     * <p>为什么需要独立段：{@code digestForPrompt} 是固定格式的“我所知道的事”清单，
     * 排序按获知时间，契约常常埋在第 6~8 条；实测模型会把它当背景，继续做手头的事。
     * 本段只做<b>显著性</b>提升，不替模型决定行动（仍由 AI 在赴约/提醒/暂缓之间选择），
     * 且复用 {@link com.holzyn.actor.domain.world.fact.service.WorldFactDecisionSignal}
     * 的同一判定（最近到期的一条），不产生第二套“什么算待处理”的口径。</p>
     *
     * @param ch 角色实体
     *
     * @return 段文本；无待处理事实/未装配/异常返回空串
     */
    private String urgentFactSegment(ActorCharacter ch) {
        if (worldFactDecisionSignal == null || ch == null || ch.getProjectId() == null) {
            return "";
        }
        try {
            com.holzyn.actor.domain.world.fact.entity.ActorWorldFact fact =
                    worldFactDecisionSignal.nearestDueFact(ch.getProjectId(),
                            com.holzyn.actor.common.NpcRefs.of(ch.getId()), null);
            if (fact == null) {
                return "";
            }
            long gs = 0L;
            if (worldClockService != null) {
                Long now = worldClockService.currentEngineGameSecond(ch.getProjectId());
                gs = now == null ? 0L : now;
            }
            String dueNote = "";
            Long due = fact.getValidToGameSecond();
            if (due != null && gs > 0) {
                long remainingSeconds = due - gs;
                dueNote = remainingSeconds <= 0 ? "（已经到期）"
                        : "（约 " + Math.max(1, remainingSeconds / 60L) + " 游戏分钟后到期）";
            }
            String name = fact.getName() == null || fact.getName().isBlank()
                    ? fact.getFactKey() : fact.getName();
            StringBuilder sb = new StringBuilder("【待处理约定】");
            sb.append('\n').append("- ").append(name).append('：')
                    .append(fact.getStateText() == null ? "" : fact.getStateText()).append(dueNote);
            if (fact.getLocationName() != null && !fact.getLocationName().isBlank()) {
                sb.append("；地点：").append(fact.getLocationName());
            }
            sb.append("\n这件事与你直接相关且即将到期。本次决策请优先考虑它"
                    + "（去赴约、提醒对方、或先为它做安排）；只有更紧急的生存危险才可以暂缓。");
            return sb.toString();
        } catch (Exception e) {
            log.warn("[Thinking] 待处理约定段生成失败（跳过）: char={} : {}", ch.getId(), e.getMessage());
            return "";
        }
    }

    /**
     * 批3 决策与感知：action_gen 情境前置 段2D/2E（世界动态 + 当前状态）。
     * <p>判据：情境已含【当前状态】段（周期感知情境经 PerceptionService 注入 段2E/2D，
     * 不重复）→ 原样返回；情境缺【当前状态】（STIMULUS 个性刺激情境「只知刺激不知处境」）
     * → 前置 {@code AiContextInjector.decisionStatePrefix}（段2D + 段2E 全量状态段），
     * 让 AI 带着当下时节天气与自身生理/情绪做决定（真人感）。</p>
     * <p>fail-open：注入器未装配/角色缺失/读取失败 → 原样返回（零阻断，行为与批3 前一致）。</p>
     *
     * @param ch 角色实体
     *
     * @param situation 原情境文本（可空）
     *
     * @return 可能被前置 段2D/2E 后的情境文本
     */
    private String enrichDecisionSituation(ActorCharacter ch, String situation) {
        if (ch == null || ch.getProjectId() == null) {
            return situation;
        }
        // 段2E 已有时不重复注入 2D/2E（token 保护），但段2F【所处之地】必须恒在——
        // 世界状态（脚下这块地的现状/刚发生的变化/全镇大动静）要进入<b>每一次</b>行动决策，
        // 否则行动层只剩饥饿/口渴/心情的生理循环（澄江镇二日实跑根因：叙事会说话、决策不会动）。
        // 注意：2F/卡住目标段各自有独立的装配口，不能被「注入器未装配」一并短路。
        String enriched = situation;
        if (aiContextInjector != null && (situation == null || !situation.contains("【当前状态】"))) {
            try {
                Long gameSecond = worldClockService == null ? null
                        : worldClockService.currentEngineGameSecond(ch.getProjectId());
                String prefix = aiContextInjector.decisionStatePrefix(ch.getProjectId(),
                        com.holzyn.actor.common.NpcRefs.of(ch.getId()), gameSecond);
                if (prefix != null && !prefix.isBlank()) {
                    enriched = enriched == null || enriched.isBlank() ? prefix : prefix + "\n\n" + enriched;
                }
            } catch (Exception e) {
                log.warn("[Thinking] 段2D/2E 决策前缀注入失败（原样情境继续）: char={} : {}", ch.getId(), e.getMessage());
            }
        }
        return appendStalledGoals(ch, appendLocalitySegment(ch, enriched));
    }

    /**
     * 段【卡住的目标】追加：把「这件事卡住了」重新摆到决策桌面上。
     *
     * <p>去重键为段头「【卡住的目标】」；无卡住目标/未装配/异常 → 原样返回（零阻断）。</p>
     *
     * @param ch 角色实体
     *
     * @param situation 已补 2D/2E/2F 的情境（可空）
     *
     * @return 追加后的情境
     */
    private String appendStalledGoals(ActorCharacter ch, String situation) {
        if (planningService == null) {
            return situation;
        }
        if (situation != null && situation.contains("【卡住的目标】")) {
            return situation;
        }
        try {
            Long gameSecond = worldClockService == null ? null
                    : worldClockService.currentEngineGameSecond(ch.getProjectId());
            String stalled = planningService.stalledGoalsSegment(ch.getProjectId(),
                    com.holzyn.actor.common.NpcRefs.of(ch.getId()), gameSecond);
            if (stalled == null || stalled.isBlank()) {
                return situation;
            }
            return situation == null || situation.isBlank() ? stalled : situation + "\n\n" + stalled;
        } catch (Exception e) {
            log.warn("[Thinking] 卡住目标段注入失败（原样情境继续）: char={} : {}", ch.getId(), e.getMessage());
            return situation;
        }
    }

    /**
     * 段2F【所处之地】追加：情境末尾补「这里眼下刚刚的变化四下里的动静镇上的大动静」。
     *
     * <p>为什么放在决策侧而不是只放注入器：{@code decisionStatePrefix} 只在情境缺【当前状态】时
     * 前置，而引擎周期的感知情境<b>自带</b>【当前状态】（段2E），那条主路径会整段跳过前缀注入——
     * 实跑 1452 次决策里，真正带世界状态的只有少数刺激决策。放在这里等于给两条路径同一个兜底：
     * 已含【所处之地】（去重键 {@link com.holzyn.actor.domain.agent.context.AiContextInjector#SEG_2F_HEADER}）
     * 则跳过，否则追加。</p>
     *
     * <p>fail-open：注入器未装配/段为空/异常 → 原样返回（零阻断，行为与前一致）。</p>
     *
     * @param ch 角色实体
     *
     * @param situation 已补 2D/2E 的情境（可空）
     *
     * @return 追加段2F 后的情境
     */
    private String appendLocalitySegment(ActorCharacter ch, String situation) {
        if (situation != null
                && situation.contains(com.holzyn.actor.domain.agent.context.AiContextInjector.SEG_2F_HEADER)) {
            return situation;
        }
        try {
            Long gameSecond = worldClockService == null ? null
                    : worldClockService.currentEngineGameSecond(ch.getProjectId());
            String locality = aiContextInjector.localitySegment(ch.getProjectId(),
                    com.holzyn.actor.common.NpcRefs.of(ch.getId()), gameSecond);
            if (locality == null || locality.isBlank()) {
                return situation;
            }
            return situation == null || situation.isBlank() ? locality : situation + "\n\n" + locality;
        } catch (Exception e) {
            log.warn("[Thinking] 段2F 所处之地注入失败（原样情境继续）: char={} : {}", ch.getId(), e.getMessage());
            return situation;
        }
    }

    /**
     * 无世界走向段时的行动生成 Prompt（走既有 5/6/7 参签名——行为与 P1 ②-1/①-3 前完全一致；
     * 潜在世界预测注入（P1 ③-2）未装配/无数据时保持旧路径零变化）。
     *
     * @param userId 归属用户 ID
     *
     * @param ch 角色实体
     *
     * @param personaSummary 角色人设摘要
     *
     * @param situation 当前情境
     *
     * @param tomSection ToM 推断深度段
     *
     * @param proactiveMemory 主动记忆检索【主动想起】段（可空）
     *
     * @param strategyPreference MUSE 策略切换【策略偏好】段（可空）
     *
     * @return 行动生成 Prompt
     */
    private String buildActionGenPromptNoOutlook(Long userId, ActorCharacter ch, String personaSummary,
                                                 String situation, String tomSection,
                                                 String proactiveMemory, String strategyPreference) {
        if (proactiveMemory == null || proactiveMemory.isBlank()) {
            return (strategyPreference == null || strategyPreference.isBlank())
                    ? promptService.buildActionGenPrompt(userId, ch.getProjectId(),
                            personaSummary, situation, tomSection)
                    : promptService.buildActionGenPrompt(userId, ch.getProjectId(),
                            personaSummary, situation, tomSection, "", strategyPreference);
        }
        return (strategyPreference == null || strategyPreference.isBlank())
                ? promptService.buildActionGenPrompt(userId, ch.getProjectId(),
                        personaSummary, situation, tomSection, proactiveMemory)
                : promptService.buildActionGenPrompt(userId, ch.getProjectId(),
                        personaSummary, situation, tomSection, proactiveMemory, strategyPreference);
    }

    /**
     * S2 沙盘 npc:thought（T1 决策源）：把 AI 决策产物转短句写入角色想法气泡。
     * <p>短句 = 「打算{action}[目标]」+ 可选理由；去重由 WorldSandboxStateService.publishThought
     * 负责（内容未变不广播）；失败静默不阻断决策主流程。</p>
     *
     * @param ch 角色实体
     *
     * @param decision 决策 Map（type/action/target/params/reason/urgency/duration）
     */
    private void publishDecisionThought(ActorCharacter ch, Map<String, Object> decision) {
        if (sandboxStateService == null || ch == null || ch.getProjectId() == null || decision == null) {
            return;
        }
        try {
            Object action = decision.get("action");
            Object target = decision.get("target");
            Object reason = decision.get("reason");
            String actionText = action == null ? "" : String.valueOf(action).trim();
            if (actionText.isBlank()) {
                return;
            }
            StringBuilder sb = new StringBuilder("我打算").append(actionText);
            if (target != null && !String.valueOf(target).isBlank()) {
                sb.append("（目标：").append(String.valueOf(target).trim()).append("）");
            }
            if (reason != null && !String.valueOf(reason).isBlank()) {
                String r = String.valueOf(reason).trim();
                // 2026-10-10 决策：不设字数硬边界，理由全量（不再截断 40 字）
                sb.append("，因为").append(r);
            }
            String text = sb.toString();
            sandboxStateService.publishThought(ch.getProjectId(), com.holzyn.actor.common.NpcRefs.of(ch.getId()), text, "ai", null);
        } catch (Exception e) {
            log.debug("[Thinking] 沙盘想法写入失败（静默）: char={} : {}", ch.getId(), e.getMessage());
        }
    }

    /**
     * 组装当前情境（角色状态 + 游戏时间 + 可见事件 + 关系，B2 感知上下文）。
     * <p>由 {@link PerceptionService} 组装——游戏时间（统一时间口径）+ 自身状态 + 可见事件（≤5）+ 关系摘要；
     * 感知组装失败/无内容时回退「游戏时刻 + 角色状态」（仍用游戏时间，保证决策输入时间口径一致）。</p>
     *
     * @param ch 角色实体
     *
     * @return 情境文本
     */
    public String buildSituation(ActorCharacter ch) {
        try {
            // 关键：必须把当前引擎游戏秒传给感知层。传 null 会让【我所知道的事】
            // 以 gs=0 过滤（新写入事实 validFrom>0 被误判为未生效），并生成
            // gs0/vN 的伪版本——这正是「事实已入库但决策 prompt 看不见」的根因。
            Long decisionGameSecond = worldClockService == null ? null
                    : worldClockService.currentEngineGameSecond(ch.getProjectId());
            String perception = perceptionService.buildPerceptionText(ch.getProjectId(),
                    com.holzyn.actor.common.NpcRefs.of(ch.getId()), decisionGameSecond);
            if (perception != null && !perception.isBlank()) {
                return perception;
            }
        } catch (Exception e) {
            log.warn("[Thinking] 感知情境组装失败，回退基础情境: char={} : {}", ch.getId(), e.getMessage());
        }
        // 兜底：游戏时刻（统一时间口径）+ 角色状态
        Long gh = worldClockService.currentGameHour(ch.getProjectId());
        return "当前游戏时刻：" + (gh == null ? "未知"
                : worldClockService.gameTimeTextOf(ch.getProjectId(), gh))
                + "；角色当前状态：" + (ch.getCurrentActivity() == null || ch.getCurrentActivity().isBlank()
                ? "空闲" : ch.getCurrentActivity())
                + (ch.getLocation() == null || ch.getLocation().isBlank() ? "" : "；位置：" + ch.getLocation());
    }

    /**
     * 校验行动决策 Schema。
     * <p>新契约 = action_type（continue/start_action/move_to/speak_to/wait 固定枚举）+ reasoning +
     * action_detail + target + priority（1-5）+ expected_duration_minutes + 可选 wait_minutes/candidates；
     * 解析后返回<b>内部规范 Map</b>（含新键 actionType/actionDetail/reasoning/priority/
     * expectedDurationMinutes 与映射旧键 type/action/target/reason/urgency/duration/params/
     * waitMinutes/foresightCandidates——下游执行链零改动）。</p>
     *
     * @param node 决策 JSON 节点（<decision>标签内容或裸 JSON）
     *
     * @param mapper 未使用（保留签名兼容）
     *
     * @return 内部规范决策 Map；action_type 非法/action_detail 空白 → null
     */
    public static Map<String, Object> validateDecision(JsonNode node, ObjectMapper mapper) {
        return com.holzyn.actor.domain.agent.prompt.DecisionReActParse.parse(node);
    }

    /**
     * 程序化兜底核心（Prompt 工程 「continue 必须作为程序化兜底」——
     * AI 超时/输出不合规时自动回退到「继续当前行为」，不再凭空生成新行动）。
     *
     * @param name 角色名（用于描述在继续什么）
     *
     * @param importance 重要度（保留参数签名兼容；continue 语义下不改变行动）
     *
     * @param behavior 行为模式/当前活动描述（可空）
     *
     * @return 决策 Map（actionType=continue，内部规范含旧键映射）
     */
    public static Map<String, Object> programmaticFallback(String name, Integer importance, String behavior) {
        String activity = behavior != null && !behavior.isBlank() ? behavior : "手头的事";
        return com.holzyn.actor.domain.agent.prompt.DecisionReActParse.continueDecision(activity);
    }

    /**
     * 程序化兜底决策（公开， 由 AgentRuntime 在「纯 programmatic 档 / AI 预算用尽」时调用；0 AI 成本）。
     * <p>行为与 {@link #generateDecision} 的 programmatic 分支一致—— 起 =「继续当前行为」
     * ，AI 不可用或预算不足时保证功能可用（不冻结，修）。</p>
     *
     * @param ch 角色实体
     *
     * @return 决策 Map
     */
    public Map<String, Object> generateProgrammaticDecision(ActorCharacter ch) {
        // P0-03 决策源可追溯：默认打标 FALLBACK_PROGRAMMATIC（0 AI 产出必须打标；
        // AgentRuntime 预算用尽场景在返回后覆盖为 BUDGET_EXHAUSTED）
        return DecisionSources.tag(programmaticDecision(ch), DecisionSource.FALLBACK_PROGRAMMATIC);
    }

    // ==================== 私有辅助（原 ActionEngine 对应方法迁入） ====================

    /**
     * 此刻可发生空间：决策前空间保障（空间缺失/过期 → 按 LOD 生成；有效复用零成本）。
     * <p>感知情境中的空间段由 {@code PerceptionService.buildPerceptionText} 注入（调用方先组装），
     * 本方法保证「AI 决策发生时空间恒有效」——任何失败零阻断（决策照常进行）。</p>
     */
    private void ensureDecisionSpace(ActorCharacter ch) {
        if (growthSpaceService == null || ch == null || ch.getProjectId() == null) {
            return;
        }
        try {
            Long gameSecond = worldClockService.currentEngineGameSecond(ch.getProjectId());
            growthSpaceService.ensureSpace(ch.getProjectId(), com.holzyn.actor.common.NpcRefs.of(ch.getId()),
                    gameSecond);
        } catch (Exception e) {
            log.warn("[Thinking] 此刻可发生空间保障失败（决策继续，零阻断）: char={} : {}",
                    ch.getId(), e.getMessage());
        }
    }

    /**
     * 三条护栏：决策解析后校验（stage_compliance / space_compliance /
     * developmental_knowledge_bound），blocked → 程序化 continue 兜底；通过 → 空间内行动记录。
     * <p>护栏拦截计数由 {@code GrowthGuardService} 内部埋点（验收「拦截数→0」观测口径）；</p>
     *
     * @param ch 角色实体
     *
     * @param decision 已解析决策 Map（内部规范）
     *
     * @return 原决策（放行）或 continue 决策（拦截后兜底）
     */
    private Map<String, Object> applyGrowthGuards(ActorCharacter ch, Map<String, Object> decision) {
        if (growthGuard == null || ch == null || decision == null) {
            return decision;
        }
        try {
            String actionType = decision.get("actionType") == null ? ""
                    : String.valueOf(decision.get("actionType"));
            String actionDetail = decision.get("actionDetail") == null ? null
                    : String.valueOf(decision.get("actionDetail"));
            Long gameSecond = worldClockService.currentEngineGameSecond(ch.getProjectId());
            var guardResult = growthGuard.validateDecision(ch.getProjectId(), ch, actionType, actionDetail,
                    gameSecond);
            if (guardResult.blocked()) {
                log.info("[Thinking] 护栏拦截决策（改 continue 程序化兜底）: char={} 原因={}",
                        ch.getId(), guardResult.reason());
                return com.holzyn.actor.domain.agent.prompt.DecisionReActParse.continueDecision(
                        ch.getCurrentActivity());
            }
            // 放行：记录「空间内/空间外」命中（指标验收口径）
            if (growthSpaceService != null && actionDetail != null && !actionDetail.isBlank()) {
                try {
                    growthSpaceService.recordDecisionInSpace(ch.getProjectId(),
                            com.holzyn.actor.common.NpcRefs.of(ch.getId()), actionDetail);
                } catch (Exception e) {
                    log.debug("[Thinking] 空间命中记录失败（静默）: char={} : {}", ch.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            // 护栏自身失败 → 放行（零阻断：护栏是保护网，不是决策前置条件）
            log.warn("[Thinking] 护栏校验失败（放行决策）: char={} : {}", ch.getId(), e.getMessage());
        }
        return decision;
    }

    /**
     * 决策后处理：
     * 三条护栏（stage_compliance / space_compliance / developmental_knowledge_bound——
     * blocked → 程序化 continue 兜底；通过 → 记录「空间内/空间外」命中指标）+
     * 前瞻推演记录 + S2 沙盘 npc:thought 想法气泡。
     *
     * @param ch 角色实体
     *
     * @param decision 已解析决策 Map（内部规范）
     *
     * @return 原决策（放行）或 continue 决策（护栏拦截后兜底）
     */
    private Map<String, Object> postProcessDecision(ActorCharacter ch, Map<String, Object> decision) {
        // 三条护栏：决策解析后、执行前校验——
        // stage_compliance（INFANT 无自主行动）/ space_compliance（与硬门控禁止项冲突、
        // 硬约束开启时不在空间内）/ developmental_knowledge_bound（知识盲区）。
        // blocked → 程序化 continue 兜底（真实的人被规则/能力拦住时就是继续当前行为，
        // 不凭空切换行动）；通过 → 记录「空间内/空间外」命中指标。
        decision = applyGrowthGuards(ch, decision);
        // ：前瞻推演记录（本次决策为前瞻会话（STIMULUS 且触发命中）时，
        // 把 AI 输出的候选行动/最终行动落 actor_foresight_record；无会话零成本跳过）
        if (foresightService != null) {
            try {
                foresightService.recordIfOpen(ch.getProjectId(), com.holzyn.actor.common.NpcRefs.of(ch.getId()), decision);
            } catch (Exception e) {
                log.warn("[Thinking] 前瞻记录失败（不影响决策主流程）: char={} : {}", ch.getId(), e.getMessage());
            }
        }
        // S2 沙盘 npc:thought（T1 决策源）：AI 决策产物 → 想法气泡（去重；失败静默不阻断决策）
        publishDecisionThought(ch, decision);
        return decision;
    }

    /**
     * 空间候选选择（公开，BrainStrategy SpaceChoiceStrategy 委托）：空间有效 +
     * space_decision 场景开时，特殊 NPC 的 action 决策由 决策模型 在空间 possible_actions +
     * 通用三项内选择（sample 直接采纳，无升级阈值——沿 crowd 先例；差异=真人随机波动）。
     * precondition 程序化优先 + 语义条件 boolean 批量（1 state 多题一次前向）；
     * 选中候选纯程序化映射 ReAct 决策 Map（{@link SpaceChoiceMapper}）。
     * <p>决策前空间保障内置于本方法（空间缺失/过期才 AI 生成，有效直接复用零成本；
     * null gameSecond 视为有效，零阻断）。</p>
     *
     * @param ch 角色实体
     *
     * @param situation 当前情境（可空；非空时截断注入判定情境文本，事件/刺激语义进判定）
     *
     * @return 内部规范决策 Map（null=未启用/空间无效/判定失败 → 调用方回退 Qwen 现状）
     */
    public Map<String, Object> trySpaceDecision(ActorCharacter ch, String situation) {
        if (decisionGate == null || growthSpaceService == null || ch == null
                || ch.getProjectId() == null) {
            return null;
        }
        try {
            // 到期事实让路：空间快判只从当前空间候选里挑，天然看不见「一小时后还银币」
            // 这类跨地点约定；存在待处理事实时直接回退 AI 深思（action_gen）。
            if (worldFactDecisionSignal != null
                    && worldFactDecisionSignal.hasActionableFact(ch.getProjectId(),
                    com.holzyn.actor.common.NpcRefs.of(ch.getId()), null)) {
                AiCallContext.recordStep(ch.getProjectId(), "agent_decision", "brain_strategy",
                        "space_decision 让路：存在待处理世界事实（6 游戏小时内到期），交给 AI 深思", 0);
                return null;
            }
            // 决策输入统一补齐（闭环修复）：空间快判同样是一次决策——情境必须含
            // 感知与【我所知道的事】，否则第三人称事实（public/location）无法影响选择。
            situation = ensureDecisionSituation(ch, situation);
            // 世界状态进入快判：段2F 的单行版（此地刚有什么变化镇上近来的动静）。
            // 只给一行——0.6B 判定层要的是具体钩子，整段世界描述会撑爆报文（超时事故）。
            if (aiContextInjector != null) {
                try {
                    Long gs = worldClockService == null ? null
                            : worldClockService.currentEngineGameSecond(ch.getProjectId());
                    String hint = aiContextInjector.localityHint(ch.getProjectId(),
                            com.holzyn.actor.common.NpcRefs.of(ch.getId()), gs);
                    if (hint != null && !hint.isBlank()) {
                        situation = situation == null || situation.isBlank()
                                ? hint : situation + "\n" + hint;
                    }
                } catch (Exception e) {
                    log.debug("[Thinking] 段2F 单行版注入失败（跳过）: char={} : {}", ch.getId(), e.getMessage());
                }
            }
            // 主动记忆检索【主动想起】段（决策全局自问命中时）：拼入 决策模型 判定情境文本
            // 首部（buildSpaceStateText 对 situation 尾部截断 300 字——段在首部保活，快判
            // 路径也能被「主动想起的记忆」影响；未命中/无段 → 原样行为零影响）
            String proactive = com.holzyn.actor.domain.debug.service.AiCallContext.readProactiveSegment();
            if (proactive != null && !proactive.isBlank()) {
                situation = situation == null || situation.isBlank()
                        ? proactive : proactive + "\n" + situation;
            }
            // MUSE 策略切换【策略偏好】段（策略链内 FamiliarityStrategy 命中时）：同样拼入
            // 决策模型 判定情境文本首部（快判路径也被「保守/激进」偏好引导；未命中/均衡 →
            // 原样行为零影响）
            String strategyPreference = com.holzyn.actor.domain.debug.service.AiCallContext.readStrategyPreference();
            if (strategyPreference != null && !strategyPreference.isBlank()) {
                situation = situation == null || situation.isBlank()
                        ? strategyPreference : strategyPreference + "\n" + situation;
            }
            // 潜在世界预测注入（P1 ③-2）：决策模型 快判只拼程序化趋势摘要（零 AI 零缓存——
            // 历史快照环比，事实锚点；AI 预测段留给深思/前瞻通道，快判保持低延迟）。
            // 同样拼入判定情境文本首部（快判路径也能感知「世界在变」；无趋势 → 原样零影响）
            if (worldOutlookService != null) {
                try {
                    String worldTrend = worldOutlookService.trendSummary(ch.getProjectId());
                    if (worldTrend != null && !worldTrend.isBlank()) {
                        situation = situation == null || situation.isBlank()
                                ? worldTrend : worldTrend + "\n" + situation;
                    }
                } catch (Exception e) {
                    log.warn("[Thinking] 世界走向趋势摘要获取失败（跳过快判注入）: char={} : {}",
                            ch.getId(), e.getMessage());
                }
            }
            Long projectId = ch.getProjectId();
            String npcRef = com.holzyn.actor.common.NpcRefs.of(ch.getId());
            if (!decisionGate.isEnabled(
                    com.holzyn.actor.ai.decision.gate.DecisionScenes.SPACE_DECISION)) {
                return null;
            }
            // 决策前空间保障：空间缺失/过期才 AI 生成，有效直接复用（零成本）；感知情境里的
            // 空间段由 PerceptionService 注入（调用方已先 buildSituation/感知组装），此处为兜底
            // 保障：确保 AI 决策发生时空间恒有效。null gameSecond 视为有效，零阻断。
            ensureDecisionSpace(ch);
            // 空间（ensureDecisionSpace 已保障有效；此处取当前空间）
            com.holzyn.actor.domain.growth.space.GrowthSpace space =
                    growthSpaceService.currentSpace(projectId, npcRef);
            if (space == null || space.getSpaceJson() == null || space.getSpaceJson().isBlank()) {
                return null;
            }
            com.holzyn.actor.domain.growth.space.GrowthSpaceParse.GrowthSpaceDoc doc =
                    com.holzyn.actor.domain.growth.space.GrowthSpaceParse.parse(space.getSpaceJson());
            if (doc == null || doc.possibleActions() == null || doc.possibleActions().isEmpty()) {
                return null;
            }
            Long gameSecond = worldClockService == null ? null
                    : worldClockService.currentEngineGameSecond(projectId);
            com.holzyn.actor.domain.npc.state.entity.ActorNpcState st = npcStateService == null
                    ? null : npcStateService.stateFor(projectId, npcRef);
            // precondition 程序化判定上下文（C 层社交候选注入需在场者/时刻判断，故先于候选集组装）
            com.holzyn.actor.domain.growth.space.SpacePreconditionEvaluator.Context pctx =
                    buildSpacePreconditionContext(projectId, ch, gameSecond, st);
            // 候选集（空间 possible_actions + 通用三项，≤8；权重以「（倾向 x%）」进描述）
            java.util.List<com.holzyn.actor.domain.growth.space.SpaceDecisionCandidates.SpaceCandidate> candidates =
                    com.holzyn.actor.domain.growth.space.SpaceDecisionCandidates.candidatesFrom(doc, st);
            // 注入候选合并（社交聚集 + 生理低值，一次重组装；生理候选命中 → 抑制通用轻行动）
            //
            // 昼夜口径 = 引擎推进位置（口径：流动时钟按实时×速率插值，世界暂停/慢速时
            // 远超模拟位置——用流动时钟会让「深夜不串门」门控失真，与概率闲聊的引擎口径不一致）
            java.util.List<com.holzyn.actor.domain.growth.space.SpaceDecisionCandidates.SpaceCandidate> extra =
                    new java.util.ArrayList<>();
            boolean suppressLight = false;
            if (socialGatheringService != null) {
                java.util.List<com.holzyn.actor.domain.growth.space.SpaceDecisionCandidates.SpaceCandidate>
                        social = socialGatheringService.candidatesFor(projectId, ch.getLocation(),
                        pctx.presentNames() == null || pctx.presentNames().isEmpty(),
                        engineHourOfDay(projectId));
                if (!social.isEmpty()) {
                    extra.addAll(social);
                }
            }
            if (physioNeedService != null) {
                java.util.List<com.holzyn.actor.domain.growth.space.SpaceDecisionCandidates.SpaceCandidate>
                        physio = physioNeedService.injectableCandidates(projectId, ch);
                if (!physio.isEmpty()) {
                    extra.addAll(physio);
                    suppressLight = physioNeedService.suppressLight(projectId, ch);
                }
            }
            if (!extra.isEmpty()) {
                candidates = com.holzyn.actor.domain.growth.space.SpaceDecisionCandidates
                        .candidatesFrom(doc, st, extra, suppressLight);
            }
            if (candidates.size() < 2) {
                return null;
            }
            // 位置移动与地图生成系统 · M3 §4.5 ③：决策情境路程远近/距离加权——候选带目标地点
            // （target 为地点名）时，按角色当前沙盘坐标 → 目标区域中心距离附加「（距 X 近/中/远）」
            // 标注到判定候选文本（决策可据此权衡远近；坐标/服务未装配/失败跳过标注，行为零影响）
            candidates = enrichCandidatesWithDistance(projectId, ch, candidates);
            // 判定编排（一次前向）→ 选中候选
            String spaceStateText = buildSpaceStateText(ch, st, doc, situation);
            // 空间快判同样属于一次决策输入：把 stateVersion + 情境快照留证
            // （user_prompt 内含【我所知道的事】，供 L6 第三人称事实核对）。
            recordDecisionInputVersion(ch, spaceStateText, spaceStateText);
            com.holzyn.actor.domain.growth.space.SpaceDecisionCandidates.SpaceCandidate chosen =
                    com.holzyn.actor.domain.growth.space.SpaceDecisionServiceUtils.decide(
                            decisionGate, projectId, ch.getId(), candidates, pctx,
                            spaceStateText);
            if (chosen == null) {
                return null;
            }
            // 纯程序化映射 ReAct 决策 Map（新键+旧键双写，下游执行链零改动）
            java.util.List<String> considered = new java.util.ArrayList<>();
            for (com.holzyn.actor.domain.growth.space.SpaceDecisionCandidates.SpaceCandidate c : candidates) {
                if (c.action() != null && !c.action().isBlank()) {
                    considered.add(c.action());
                }
            }
            Map<String, Object> decision = com.holzyn.actor.domain.growth.space.SpaceChoiceMapper.toDecisionMap(
                    chosen, ch.getCurrentActivity(), considered);
            if (decision == null) {
                return null;
            }
            AiCallContext.recordStep(projectId, "agent_decision", "thinking",
                    "空间候选选择（决策模型 space_decision）：候选 " + candidates.size()
                            + " 项 → 选中「" + truncate(String.valueOf(chosen.action()), 40) + "」", 0);
            log.info("[Thinking] 空间候选选择（决策模型）: char={} 选中={} 候选={} 项",
                    ch.getId(), chosen.action(), candidates.size());
            return decision;
        } catch (Exception e) {
            log.warn("[Thinking] 空间候选选择失败（回退 Qwen/程序化）: char={} : {}",
                    ch.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 候选距离标注（M3 §4.5 ③）：空间候选带目标地点（target 为地点名）时，按角色当前
     * 沙盘坐标 → 目标区域中心距离附加「（距 X 近/中/远）」到判定候选 questionText——
     * 决策可据此权衡远近（近的先去、远的需赶时间）；通用项/无目标/坐标不可用/服务未装配
     * → 原样返回（行为零影响）。失败降级返回原列表（零阻断）。
     */
    private java.util.List<com.holzyn.actor.domain.growth.space.SpaceDecisionCandidates.SpaceCandidate>
            enrichCandidatesWithDistance(Long projectId, ActorCharacter ch,
                                         java.util.List<com.holzyn.actor.domain.growth.space.SpaceDecisionCandidates.SpaceCandidate> candidates) {
        if (candidates == null || candidates.isEmpty() || worldMapQueryService == null
                || sandboxStateRepository == null || ch == null || ch.getProjectId() == null) {
            return candidates;
        }
        try {
            com.holzyn.actor.domain.sandbox.entity.ActorSandboxState st = sandboxStateRepository
                    .findByProjectIdAndNpcRef(projectId, com.holzyn.actor.common.NpcRefs.of(ch.getId())).orElse(null);
            if (st == null || st.getPosX() == null || st.getPosZ() == null) {
                return candidates;
            }
            java.util.List<com.holzyn.actor.domain.growth.space.SpaceDecisionCandidates.SpaceCandidate> out =
                    new java.util.ArrayList<>(candidates.size());
            for (com.holzyn.actor.domain.growth.space.SpaceDecisionCandidates.SpaceCandidate c : candidates) {
                if (c == null || c.generic() || c.target() == null || c.target().isBlank()) {
                    out.add(c);
                    continue;
                }
                String dist = null;
                try {
                    // 候选 target=地点名 → 精确匹配地点 ID → 计算角色当前位置到区域中心距离分级
                    Long locId = worldMapQueryService.locationIdByName(projectId, c.target());
                    if (locId != null) {
                        dist = worldMapQueryService.distanceToTarget(projectId, st.getPosX(), st.getPosZ(), locId);
                    }
                } catch (Exception e) {
                    log.debug("[Thinking] 候选距离标注失败（跳过该候选）: {}", e.getMessage());
                }
                if (dist == null || dist.isBlank()) {
                    out.add(c);
                    continue;
                }
                String q = c.questionText() == null || c.questionText().isBlank()
                        ? c.action() : c.questionText();
                out.add(new com.holzyn.actor.domain.growth.space.SpaceDecisionCandidates.SpaceCandidate(
                        c.key(), c.action(), q + "（距 " + dist + "）", c.target(), c.precondition(),
                        c.weight(), c.generic()));
            }
            return out;
        } catch (Exception e) {
            log.warn("[Thinking] 候选距离标注失败（返回原列表）: char={} : {}", ch.getId(), e.getMessage());
            return candidates;
        }
    }

    /** 空间判定情境文本（判据自包含：时刻/当前行动/地点/状态/在场者/天气/内心念头/事件刺激）。 */
    private String buildSpaceStateText(ActorCharacter ch,
                                       com.holzyn.actor.domain.npc.state.entity.ActorNpcState st,
                                       com.holzyn.actor.domain.growth.space.GrowthSpaceParse.GrowthSpaceDoc doc,
                                       String situation) {
        StringBuilder sb = new StringBuilder();
        sb.append(ch.getName() == null || ch.getName().isBlank() ? "该角色" : ch.getName());
        sb.append("此刻").append(ch.getCurrentActivity() == null || ch.getCurrentActivity().isBlank()
                ? "正在忙自己的事" : ch.getCurrentActivity().trim());
        if (ch.getLocation() != null && !ch.getLocation().isBlank()) {
            sb.append("，在").append(ch.getLocation().trim());
        }
        if (worldClockService != null) {
            try {
                Long h = worldClockService.currentGameHour(ch.getProjectId());
                if (h != null) {
                    sb.append("，游戏时刻约 ").append(h % 24L).append(" 点");
                }
            } catch (Exception e) {
                log.debug("[Thinking] 时刻读取失败（跳过）: {}", e.getMessage());
            }
        }
        if (st != null) {
            java.util.List<String> flags = new java.util.ArrayList<>();
            if (st.getHunger() != null && st.getHunger() < 40) {
                flags.add("饿了");
            }
            if (st.getThirst() != null && st.getThirst() < 40) {
                flags.add("口渴");
            }
            if (st.getEnergy() != null && st.getEnergy() < 40) {
                flags.add("疲惫");
            }
            if (st.getSocial() != null && st.getSocial() < 40) {
                flags.add("有点闷");
            }
            if (!flags.isEmpty()) {
                sb.append("，").append(String.join("、", flags));
            }
        }
        if (doc != null && doc.inner() != null && doc.inner().dominantThought() != null
                && !doc.inner().dominantThought().isBlank()) {
            // 2026-10-10 决策：不设字数硬边界，内心念头全量注入
            sb.append("。内心念头：").append(doc.inner().dominantThought().trim());
        }
        if (situation != null && !situation.isBlank()) {
            // 2026-10-10 决策：不设字数硬边界，此刻情况全量注入
            sb.append("。此刻情况：").append(situation.trim());
        }
        sb.append("。");
        return sb.toString();
    }

    /** precondition 程序化判定上下文（时刻/状态/在场者/全村/天气/地点）。 */
    private com.holzyn.actor.domain.growth.space.SpacePreconditionEvaluator.Context buildSpacePreconditionContext(
            Long projectId, ActorCharacter ch, Long gameSecond,
            com.holzyn.actor.domain.npc.state.entity.ActorNpcState st) {
        Integer hourOfDay = null;
        if (worldClockService != null) {
            try {
                Long h = worldClockService.currentGameHour(projectId);
                hourOfDay = h == null ? null : (int) (h % 24L);
            } catch (Exception e) {
                log.debug("[Thinking] 游戏时刻读取失败（按未知）: {}", e.getMessage());
            }
        }
        java.util.List<String> present = new java.util.ArrayList<>();
        java.util.List<String> village = new java.util.ArrayList<>();
        if (characterRepository != null) {
            try {
                for (com.holzyn.actor.domain.npc.profile.entity.ActorCharacter other :
                        characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
                    if (com.holzyn.actor.domain.growth.stage.GrowthStage.DECEASED.name()
                            .equalsIgnoreCase(other.getStage()) || other.getId().equals(ch.getId())) {
                        continue;
                    }
                    village.add(other.getName());
                    if (ch.getLocation() != null && ch.getLocation().equals(other.getLocation())) {
                        present.add(other.getName());
                    }
                }
            } catch (Exception e) {
                log.debug("[Thinking] 在场者名单读取失败（按未知）: {}", e.getMessage());
            }
        }
        String weather = null;
        if (environmentContextService != null) {
            try {
                com.holzyn.actor.domain.world.vo.EnvironmentContextVO vo =
                        environmentContextService.computeContext(projectId, gameSecond);
                weather = vo == null ? null : vo.getWeather();
            } catch (Exception e) {
                log.debug("[Thinking] 天气读取失败（按未知）: {}", e.getMessage());
            }
        }
        return new com.holzyn.actor.domain.growth.space.SpacePreconditionEvaluator.Context(
                hourOfDay, st, present, village, weather, ch.getLocation());
    }

    /**
     * 引擎推进位置的时刻。
     * <p>用于 C 层社交聚集的昼夜门（「深夜不串门」）：流动时钟按实时×速率插值，世界暂停/慢速
     * 时会远超模拟位置，用流动时钟会让
     * 门控与模拟昼夜脱节；不可读返回 null（调用方按非深夜宽松处理，零阻断）。</p>
     */
    private Integer engineHourOfDay(Long projectId) {
        if (worldClockService == null) {
            return null;
        }
        try {
            Long h = worldClockService.currentEngineGameHour(projectId);
            return h == null ? null : (int) (h % 24L);
        } catch (Exception e) {
            log.debug("[Thinking] 引擎时刻读取失败（按未知）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 程序化兜底：基于角色当前行为继续（：continue=程序化兜底，AI 不可用/预算用尽
     * 时「继续当前行为」而非凭空生成新行动——真实的人不会因决策系统失效就切换行动）。
     */
    private Map<String, Object> programmaticDecision(ActorCharacter ch) {
        return com.holzyn.actor.domain.agent.prompt.DecisionReActParse.continueDecision(
                ch == null ? null : ch.getCurrentActivity());
    }

    /**
     * 组装角色人设摘要（行动生成 Prompt 输入）。
     */
    private String buildPersonaSummary(ActorCharacter ch) {
        StringBuilder sb = new StringBuilder();
        sb.append("姓名：").append(ch.getName());
        if (ch.getTitle() != null && !ch.getTitle().isBlank()) {
            sb.append("（").append(ch.getTitle()).append("）");
        }
        sb.append("；重要度：").append(ch.getImportance() == null ? 1 : ch.getImportance());
        if (ch.getCurrentActivity() != null && !ch.getCurrentActivity().isBlank()) {
            sb.append("；当前在做：").append(ch.getCurrentActivity());
        }
        if (ch.getLocation() != null && !ch.getLocation().isBlank()) {
            sb.append("；位置：").append(ch.getLocation());
        }
        String persona = (personaService == null ? null : personaService.latestPersonaJson(ch.getId()).orElse(null));
        if (persona != null && !persona.isBlank()) {
            try {
                JsonNode p = objectMapper.readTree(persona);
                List<String> bits = new ArrayList<>();
                com.holzyn.actor.domain.growth.persona.PersonaBits.appendAll(bits, 3, p, "dims", "personality", "traits_summary");
                com.holzyn.actor.domain.growth.persona.PersonaBits.appendAll(bits, 3, p, "dims", "values", "life_goals");
                com.holzyn.actor.domain.growth.persona.PersonaBits.appendAll(bits, 3, p, "dims", "habits", "micro_movements");
                if (!bits.isEmpty()) {
                    sb.append("；人设：").append(String.join("、", bits));
                }
            } catch (Exception ignored) {
                // 忽略角色卡解析失败
            }
        }
        return sb.toString();
    }

    /**
     * 主决策 ToM 推断深度段（按角色卡 OCEAN 宜人性×神经质；角色卡缺失/解析失败
     * 返回空串=不注入，零阻断）。
     */
    private String tomSectionOf(ActorCharacter ch) {
        try {
            String persona = (personaService == null ? null : personaService.latestPersonaJson(ch.getId()).orElse(null));
            return com.holzyn.actor.domain.agent.runtime.TomInference.segmentOf(persona);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 从角色卡 persona_json 提取第一条行为模式（程序化兜底依据）。
     */
    private String firstBehavior(ActorCharacter ch) {
        String persona = (personaService == null ? null : personaService.latestPersonaJson(ch.getId()).orElse(null));
        if (persona == null || persona.isBlank()) {
            return null;
        }
        try {
            // ：v2 档案里的"行为模式"= dims.habits.micro_movements（旧平铺键 behaviorPatterns 已废弃——
            // 继续按旧键取会命中 MissingNode，本方法恒返回 null，决策情境会静默丢掉行为倾向位元）。
            java.util.List<String> behaviors = com.holzyn.actor.domain.growth.persona.PersonaBits.texts(
                    objectMapper.readTree(persona), "dims", "habits", "micro_movements");
            if (!behaviors.isEmpty()) {
                return behaviors.get(0);
            }
        } catch (Exception ignored) {
            // 忽略解析失败
        }
        return null;
    }

    /**
     * 解析脑力档位（brainProfile 权威配置源）：special:{id} 解析 → brain_level；失败/缺失返回 null。
     */
    private String resolveBrainLevel(ActorCharacter ch) {
        if (ch == null || ch.getId() == null) {
            return null;
        }
        try {
            ActorAgentProfile profile = brainProfileService.resolveByRef(com.holzyn.actor.common.NpcRefs.of(ch.getId()));
            return profile == null ? null : profile.getBrainLevel();
        } catch (Exception e) {
            log.warn("[Thinking] 脑力配置解析失败（按缺省管线继续）: char={} : {}", ch.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 数组字段到摘要片段。
     */
    private void appendBits(List<String> bits, JsonNode node, int max) {
        if (node.isArray()) {
            node.forEach(n -> {
                String v = n.asText("");
                if (!v.isBlank() && bits.size() < max) {
                    bits.add(v);
                }
            });
        }
    }

    /**
     * 字符串截断到指定长度（角色状态字段长度约束）。
     */
    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
    }
}
