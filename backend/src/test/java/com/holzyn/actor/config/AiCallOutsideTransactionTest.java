package com.holzyn.actor.config;

import com.holzyn.actor.domain.agent.thinking.PlanningService;
import com.holzyn.actor.domain.crowd.service.OrdinaryNpcService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 架构不变量回归：<b>AI 调用不得在数据库事务内执行</b>（2026-09-21 世界运行测试三轮抓修固化）。
 *
 * <p>为什么这是一条必须用测试守住的线：H2 的行锁在事务提交前一直持有，而 AI 调用是秒级网络等待
 * （实测 plan_day avg 42s/max 65s、action avg 17.6s、npc_crowd_decision avg 6.2s）。只要事务把
 * AI 包在里面，锁就会持续数十秒，其它线程写同一张表必然撞上 {@code LOCK_TIMEOUT=10000} → 重试
 * 仍失败 → <b>已付费的 AI 结果被丢弃</b>（情绪评估）或世界推进被拖慢。三轮世界运行的现场证据：</p>
 * <ul>
 *   <li>第一轮：56 次 {@code Timeout trying to lock table "actor_state"}，4 次情绪写回丢失
 *       （持有者=AgentActionService.execute 事务里嵌套的 L1 向量化 HTTP）；</li>
 *   <li>第二轮：jstack 实证世界冻结 14 分钟——{@code WorldEngine.dispatchHourlyDecisions} 无上限
 *       {@code Future.get()} 等一个悬挂的 plan_day（其事务由 PlanningService 提供）；</li>
 *   <li>第三轮：17 次锁超时 + 2 次情绪丢失，全部挤在人群调度的 40 秒窗口内
 *       （持有者=OrdinaryNpcService.scheduleProgrammaticByGameHour 的循环级事务，内含多个
 *       npc_crowd_decision AI 调用）。</li>
 * </ul>
 *
 * <p>本测试用反射直接断言这些方法<b>没有</b> {@code @Transactional}：有人日后顺手加回来时，
 * 这里会立刻红，而不是等到世界运行再花几轮去查锁等待。写阶段的事务由各自的下游短事务承担
 * （recordPlan / applyCrowdAction / setActivities / setDailyPlans / onCrowdSchedule 等）。</p>
 *
 * <p>所属模块：config（跨域架构约束的回归测试，与被约束的域解耦）</p>
 */
class AiCallOutsideTransactionTest {

    /**
     * 世界推进编排：自走路径 / 外部时钟路径都含大量 AI（决策/规划/世界事件），必须无事务。
     */
    @Test
    @DisplayName("世界引擎推进编排不得带 @Transactional（AI 调用在事务内会长期持锁）")
    void worldEngineAdvanceIsNotTransactional() throws Exception {
        Class<?> engine = Class.forName("com.holzyn.actor.domain.agent.runtime.WorldEngine");
        assertNoTransaction(engine, "advanceProject", Long.class, LocalDateTime.class);
        assertNoTransaction(engine, "advanceProject", Long.class, LocalDateTime.class, boolean.class);
        assertNoTransaction(engine, "advanceProjectExternal", Long.class, long.class);
    }

    /**
     * 日计划链路：plan_day 是全场景最慢的 AI 调用（avg 42s/max 65s），事务绝不能跨它存活。
     */
    @Test
    @DisplayName("规划服务（目标/日计划/重排）不得带 @Transactional")
    void planningServiceAiMethodsAreNotTransactional() throws Exception {
        Class<?> planning = PlanningService.class;
        assertNoTransaction(planning, "generateGoal", Long.class, String.class, Long.class);
        assertNoTransaction(planning, "ensurePlanDay", Long.class, String.class, Long.class);
        assertNoTransaction(planning, "planDay", Long.class, String.class, long.class);
        assertNoTransaction(planning, "replanOnDisruption", Long.class, String.class, Long.class, String.class);
    }

    /**
     * 人群作息调度：一个事务里会串起多个居民的 npc_crowd_decision（每人 6s 级），
     * 是第三轮 17 次锁超时的直接持有者。
     */
    @Test
    @DisplayName("人群作息调度不得带 @Transactional（循环内含多次人群 AI 决策）")
    void crowdScheduleIsNotTransactional() throws Exception {
        Class<?> crowd = OrdinaryNpcService.class;
        assertNoTransaction(crowd, "scheduleProgrammatic", Long.class);
        assertNoTransaction(crowd, "scheduleProgrammaticByGameHour", Long.class, int.class);
        assertNoTransaction(crowd, "scheduleProgrammaticByGameHour", Long.class, int.class, Long.class);
        // G1b 追加：批量入库可能触发兜底两段式建档（2 次 AI 调用/人），事务必须去掉
        assertNoTransaction(crowd, "batchSave", Long.class,
                Class.forName("java.util.List"));
    }

    /**
     * 情绪评估链路：AI 调用与落库分层（AI 在事务外，落库走 persistStateQuietly 的短事务 + 退避重试）。
     */
    @Test
    @DisplayName("情绪/人群决策核心不得带 @Transactional（AI 在事务外，落库分层）")
    void npcEmotionAndCrowdDecisionAreNotTransactional() throws Exception {
        Class<?> state = Class.forName("com.holzyn.actor.domain.npc.state.service.NpcStateService");
        assertNoTransaction(state, "evaluateEmotion", Long.class, String.class, String.class, String.class,
                String.class, Long.class);
        assertNoTransaction(state, "decideCrowdAction", Long.class,
                Class.forName("com.holzyn.actor.domain.npc.profile.entity.ActorCharacter"), Long.class);
        assertNoTransaction(state, "decideCrowdAction", Long.class,
                Class.forName("com.holzyn.actor.domain.npc.profile.entity.ActorCharacter"), Long.class,
                String.class);
    }

    /**
     * 档案生成/补全链路（G1b 追加入网，2026-09-22）：AI 两段式建档（avg 72–96s）与 AI 补全缺失维
     * 都不得被事务包住——G1a 遗留的 {@code CharacterCardService.generateCard} 等三个 @Transactional
     * 在本轮移除（其落库由 PersonaService.savePersona 短事务承担，版本历史同事务留痕）。
     */
    @Test
    @DisplayName("档案 AI 生成/补全不得带 @Transactional（建档是数十秒级 AI 调用）")
    void characterCardAiMethodsAreNotTransactional() throws Exception {
        Class<?> cards = Class.forName("com.holzyn.actor.domain.character.service.CharacterCardService");
        assertNoTransaction(cards, "generateCard", Long.class);
        assertNoTransaction(cards, "generateCard", Long.class, boolean.class);
        assertNoTransaction(cards, "generateAllCards", Long.class);
        assertNoTransaction(cards, "generateMissingCards", Long.class);
        assertNoTransaction(cards, "completeMissingDims", Long.class);
    }

    /**
     * O6 轨道 G2（2026-09-21）追加入网：此刻可发生空间的 AI 生成（growth_space_generate，秒级）
     * 由 ensureSpace/triggerRegenerate/periodicRecompute 触发，一律不得被事务包住；
     * 落库由仓储自身的短事务承担（空间行/日志行各自独立提交）。
     */
    @Test
    @DisplayName("G2 此刻可发生空间生成链路不得带 @Transactional（空间 AI 生成是秒级调用）")
    void growthSpaceAiMethodsAreNotTransactional() throws Exception {
        Class<?> space = Class.forName("com.holzyn.actor.domain.growth.space.GrowthSpaceService");
        assertNoTransaction(space, "ensureSpace", Long.class, String.class, Long.class);
        assertNoTransaction(space, "triggerRegenerate", Long.class, String.class,
                Class.forName("com.holzyn.actor.domain.growth.space.GrowthSpaceTrigger"), Long.class);
        assertNoTransaction(space, "periodicRecompute", Long.class, Long.class);
    }

    /**
     * O6 轨道 G2（2026-09-21）追加入网：阶段机日检/阶段跨越/死亡链路虽把 AI 叙事放异步虚拟线程
     * （不阻塞主流程），但方法本身仍可能串起多个 AI 触发（空间重算/叙事），一律不得带 @Transactional。
     */
    @Test
    @DisplayName("G2 阶段机日检/跨越/死亡链路不得带 @Transactional（触发空间重算与 AI 叙事）")
    void developmentalStageAiMethodsAreNotTransactional() throws Exception {
        Class<?> stage = Class.forName("com.holzyn.actor.domain.growth.stage.DevelopmentalStageService");
        assertNoTransaction(stage, "backfillBirthSeconds", Long.class, Long.class);
        assertNoTransaction(stage, "dailyMaintenance", Long.class, Long.class);
        assertNoTransaction(stage, "checkStageTransitions", Long.class, Long.class);
        assertNoTransaction(stage, "refreshPhysioCoefs", Long.class);
        assertNoTransaction(stage, "executeDeath", Long.class,
                Class.forName("com.holzyn.actor.domain.npc.profile.entity.ActorCharacter"), Long.class,
                String.class);
        assertNoTransaction(stage, "fastForwardAge", Long.class, Long.class, int.class, Long.class);
        assertNoTransaction(stage, "forceDeath", Long.class, Long.class, Long.class, String.class);
    }

    /**
     * O6 轨道 G2（2026-09-21）追加入网：决策链路（action_gen 秒级 AI）与对话/群聊回复链路
     * （流式 AI）都是事务外长流程，断言无 @Transactional。
     */
    @Test
    @DisplayName("G2 决策与对话/群聊链路不得带 @Transactional（AI 决策与流式回复在事务外）")
    void decisionAndRoleplayAiMethodsAreNotTransactional() throws Exception {
        Class<?> decision = Class.forName("com.holzyn.actor.domain.agent.thinking.DecisionService");
        assertNoTransaction(decision, "generateDecision",
                Class.forName("com.holzyn.actor.domain.npc.profile.entity.ActorCharacter"),
                Long.class, String.class);
        Class<?> roleplay = Class.forName("com.holzyn.actor.domain.agent.roleplay.RoleplayService");
        assertNoTransaction(roleplay, "generateSingleReply",
                Class.forName("org.springframework.web.servlet.mvc.method.annotation.SseEmitter"),
                Class.forName("com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation"),
                Class.forName("com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage"), Long.class);
        assertNoTransaction(roleplay, "generateGroupMember",
                Class.forName("org.springframework.web.servlet.mvc.method.annotation.SseEmitter"),
                Class.forName("com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation"),
                Class.forName("com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember"),
                Long.class, java.util.concurrent.atomic.AtomicBoolean.class, java.util.Set.class);
    }

    /**
     * O6 轨道 G3（2026-09-22）追加入网：人格偏移评估（growth_persona_shift_eval AI 判定，秒级）与
     * 社会面具表达 PAD 计算（感知热路径 + 破绽检测委托，内含写回）一律不得被事务包住；
     * 落库由各仓储短事务承担（shift_log 行 / actor_state 行独立提交）。
     */
    @Test
    @DisplayName("G3 人格偏移评估与面具表达计算不得带 @Transactional（AI 判定与感知热路径在事务外）")
    void growthG3AiMethodsAreNotTransactional() throws Exception {
        Class<?> shift = Class.forName("com.holzyn.actor.domain.growth.evolution.PersonalityShiftService");
        assertNoTransaction(shift, "assess", Long.class, String.class, String.class, Long.class);
        Class<?> mask = Class.forName("com.holzyn.actor.domain.growth.mask.MaskService");
        assertNoTransaction(mask, "computeExpressed", Long.class, String.class, Long.class,
                Class.forName("java.util.List"), String.class);
    }

    /**
     * O6 轨道 G4（2026-09-22）追加入网：婚育链路（心动/求婚/生育意愿 AI 判定 + 婚礼叙事 + 新生儿
     * 起名/外貌 AI）与新生儿七步流程、婴儿档案分阶段揭示（复用 AI 补全缺失维）一律不得被事务包住；
     * 落库由各仓储短事务承担（ROMANTIC/MARRIAGE 边、谱系边、基因行、新生儿行各自独立提交）。
     */
    @Test
    @DisplayName("G4 婚育/新生儿/婴儿揭示链路不得带 @Transactional（AI 判定与叙事在事务外）")
    void growthG4AiMethodsAreNotTransactional() throws Exception {
        Class<?> marriage = Class.forName("com.holzyn.actor.domain.growth.romance.MarriageService");
        assertNoTransaction(marriage, "dailyCheck", Long.class, Long.class);
        assertNoTransaction(marriage, "performWedding", Long.class,
                Class.forName("com.holzyn.actor.domain.npc.profile.entity.ActorCharacter"),
                Class.forName("com.holzyn.actor.domain.npc.profile.entity.ActorCharacter"), Long.class);
        Class<?> offspring = Class.forName("com.holzyn.actor.domain.growth.genetic.OffspringGenerator");
        assertNoTransaction(offspring, "createOffspring", Long.class, String.class, String.class, Long.class);
        Class<?> bootstrap = Class.forName("com.holzyn.actor.domain.growth.persona.PersonaBootstrapService");
        assertNoTransaction(bootstrap, "completeDims", Long.class, Long.class,
                Class.forName("java.util.List"));
        Class<?> reveal = Class.forName("com.holzyn.actor.domain.growth.infant.InfantRevealService");
        assertNoTransaction(reveal, "revealOnStageTransition", Long.class, Long.class, String.class);
    }

    /**
     * N 轨道 N1（2026-09-22）追加入网：NanoJev 判定调用同样是 AI 调用（HTTP 秒级/毫秒级网络等待），
     * 任何含它的公开方法不得带 @Transactional——否则判定调用在事务内同样长期持 actor_state 行锁，
     * 违反 2026-09-21 固化的架构不变量（含 AI 调用的方法一律无事务，AiCallOutsideTransactionTest 守护）。
     */
    @Test
    @DisplayName("N1 NanoJev 判定客户端不得带 @Transactional（NanoJev 调用同样是 AI 调用）")
    void nanoJevClientIsNotTransactional() throws Exception {
        Class<?> nanojev = Class.forName("com.holzyn.actor.ai.nanojev.NanoJevEvaluateClient");
        assertNoTransaction(nanojev, "evaluate",
                Long.class, Long.class, Long.class, String.class, Class.forName("java.util.List"));
        assertNoTransaction(nanojev, "evaluateOnce",
                Long.class, Long.class, Long.class, String.class, Class.forName("java.util.List"));
        assertNoTransaction(nanojev, "healthCheck", Long.class, Long.class);
    }

    /**
     * N 轨道 N2（2026-09-22）追加入网：NanoJev A/B 批跑编排（runBatch 内含 NanoJev evaluate +
     * Qwen 同题型重问 + Judge 评分三路 AI 调用）与 Qwen 重问客户端（chat 网关调用）——
     * 一律不得带 @Transactional；落库由仓库短事务各自承担（compareOne/judgeOne 内逐样本 save）。
     */
    @Test
    @DisplayName("N2 NanoJev A/B 批跑与 Qwen 重问不得带 @Transactional（三路 AI 调用在事务外）")
    void nanoJevAbBatchIsNotTransactional() throws Exception {
        Class<?> abRunner = Class.forName("com.holzyn.actor.ai.nanojev.ab.NanoJevAbBatchRunner");
        assertNoTransaction(abRunner, "runBatch", Long.class, String.class);
        Class<?> qwen = Class.forName("com.holzyn.actor.ai.nanojev.ab.QwenBaselineClient");
        assertNoTransaction(qwen, "ask",
                Long.class, Long.class, Long.class, String.class, String.class, String.class,
                Class.forName("com.holzyn.actor.ai.nanojev.NanoJevQuestion"));
    }

    /**
     * N 轨道 N3（2026-09-24）追加入网：判定类落地统一框架（ai/nanojev/decision）——
     * 判定门三题型/批量（NanoJev evaluate 调用）、社交意愿门控（NanoJev + 升级 Qwen 双 AI 调用）、
     * 拒绝连锁（情绪异步评估 AI 路径）、G4 三插拔点 + G3 面具两点 + 仲裁 + 护栏判定类
     * （全部经判定门走 AI）一律不得带 @Transactional；落库由各仓储短事务承担。
     */
    @Test
    @DisplayName("N3 判定类落地全链路不得带 @Transactional（NanoJev 判定 + 升级 Qwen + 拒绝连锁）")
    void nanoJevDecisionLayerIsNotTransactional() throws Exception {
        Class<?> gate = Class.forName("com.holzyn.actor.ai.nanojev.decision.NanoJevDecisionGate");
        assertNoTransaction(gate, "booleanDecision", Long.class, Long.class, String.class,
                Class.forName("com.holzyn.actor.ai.nanojev.NanoJevState"), String.class);
        assertNoTransaction(gate, "choiceDecision", Long.class, Long.class, String.class,
                Class.forName("com.holzyn.actor.ai.nanojev.NanoJevState"), String.class);
        assertNoTransaction(gate, "scoreDecision", Long.class, Long.class, String.class,
                Class.forName("com.holzyn.actor.ai.nanojev.NanoJevState"), String.class);
        assertNoTransaction(gate, "booleanDecisions", Long.class, String.class, Class.forName("java.util.Map"),
                String.class);
        Class<?> social = Class.forName("com.holzyn.actor.domain.agent.roleplay.SocialWillingnessGate");
        assertNoTransaction(social, "evaluate", Long.class, Long.class, String.class, String.class);
        assertNoTransaction(social, "evaluateBatch", Long.class, Class.forName("java.util.Map"));
        Class<?> denial = Class.forName("com.holzyn.actor.domain.agent.roleplay.SocialDenialService");
        assertNoTransaction(denial, "onDenial", Long.class, String.class, String.class, String.class,
                String.class, String.class, Long.class);
        Class<?> escalation = Class.forName("com.holzyn.actor.ai.nanojev.decision.NanoJevEscalationClient");
        assertNoTransaction(escalation, "ask", Long.class, Long.class, String.class, String.class,
                String.class, Class.forName("com.holzyn.actor.ai.nanojev.NanoJevQuestion"));
        // G4 三插拔点判定类
        Class<?> romance = Class.forName("com.holzyn.actor.ai.nanojev.decision.NanoJevRomanceAttractionDecision");
        assertNoTransaction(romance, "decide",
                Class.forName("com.holzyn.actor.domain.growth.romance.RomanceDecisions$AttractionContext"));
        Class<?> proposal = Class.forName("com.holzyn.actor.ai.nanojev.decision.NanoJevProposalDecision");
        assertNoTransaction(proposal, "decide",
                Class.forName("com.holzyn.actor.domain.growth.romance.RomanceDecisions$ProposalContext"));
        Class<?> fertility = Class.forName("com.holzyn.actor.ai.nanojev.decision.NanoJevFertilityDecision");
        assertNoTransaction(fertility, "decide",
                Class.forName("com.holzyn.actor.domain.growth.romance.RomanceDecisions$FertilityContext"));
        // G3 面具两点判定类
        Class<?> maskCtx = Class.forName("com.holzyn.actor.ai.nanojev.decision.NanoJevMaskContextDecision");
        assertNoTransaction(maskCtx, "decide", Long.class, String.class, String.class, String.class);
        Class<?> maskBreach = Class.forName("com.holzyn.actor.ai.nanojev.decision.NanoJevMaskBreachDecision");
        assertNoTransaction(maskBreach, "detect", Long.class, String.class, String.class, String.class,
                String.class);
        // 仲裁 + 护栏
        Class<?> negotiate = Class.forName("com.holzyn.actor.ai.nanojev.decision.NanoJevNegotiateDecision");
        assertNoTransaction(negotiate, "decide", Long.class, Long.class, String.class);
        Class<?> guard = Class.forName("com.holzyn.actor.ai.nanojev.decision.NanoJevGuardSemanticCheck");
        assertNoTransaction(guard, "check", Long.class, String.class, String.class);
        // 既有实现类委托入口（NanoJev 判定 + Qwen 判定都在内）
        Class<?> aiRomance = Class.forName("com.holzyn.actor.domain.growth.romance.AiRomanceDecisionSource");
        assertNoTransaction(aiRomance, "decide",
                Class.forName("com.holzyn.actor.domain.growth.romance.RomanceDecisions$AttractionContext"));
    }

    /**
     * N 轨道 N4（2026-09-25）追加入网：空间候选选择链路——判定编排（SpaceDecisionServiceUtils.decide
     * 内含 precondition 语义 boolean 批量 + 行动 choice 一次前向 = NanoJev 调用）与判定门混合批量
     * （mixedDecisions）一律不得带 @Transactional；NpcStateService.decideCrowdAction 与
     * DecisionService.generateDecision 既有断言已覆盖两个接入点的公开入口。
     */
    @Test
    @DisplayName("N4 空间候选选择链路不得带 @Transactional（混合批量一次前向 = NanoJev 调用）")
    void nanoJevSpaceDecisionIsNotTransactional() throws Exception {
        Class<?> gate = Class.forName("com.holzyn.actor.ai.nanojev.decision.NanoJevDecisionGate");
        assertNoTransaction(gate, "mixedDecisions", Long.class, Long.class, String.class,
                Class.forName("com.holzyn.actor.ai.nanojev.NanoJevState"), Class.forName("java.util.Map"));
        Class<?> utils = Class.forName("com.holzyn.actor.domain.growth.space.SpaceDecisionServiceUtils");
        assertNoTransaction(utils, "decide",
                Class.forName("com.holzyn.actor.ai.nanojev.decision.NanoJevDecisionGate"),
                Long.class, Long.class, Class.forName("java.util.List"),
                Class.forName("com.holzyn.actor.domain.growth.space.SpacePreconditionEvaluator$Context"),
                String.class);
    }

    /**
     * N 轨道 N5（2026-09-26）追加入网：认知注意力 + 高频微决策全链路——每 tick 全 NPC 批量
     * （tickAsync 内含 NanoJev 判定门 mixedBatchDecisions 两次前向）、事件×NPC 相关度批量
     * （eventRelevanceAsync）、事件驱动重算触发（SpaceTriggerJudge 三检测点 + 关系变化量评估，
     * 检测点可能从 @Transactional 栈内异步提交）、意外事件判定（AccidentJudge.onEvent，accident
     * 场景默认关仅调试开）、persona_shift 初判（PersonalityShiftService.assess 已有断言，补
     * assessAsync/cycleCheck 入口）与判定门混合批量（mixedBatchDecisions）——一律不得带
     * @Transactional；落库由各仓储短事务承担。
     */
    @Test
    @DisplayName("N5 认知 LOD + 高频微决策 + 事件驱动重算全链路不得带 @Transactional")
    void nanoJevN5AiMethodsAreNotTransactional() throws Exception {
        Class<?> gate = Class.forName("com.holzyn.actor.ai.nanojev.decision.NanoJevDecisionGate");
        assertNoTransaction(gate, "mixedBatchDecisions", Long.class, String.class,
                Class.forName("java.util.Map"), Class.forName("java.util.Map"));
        Class<?> attention = Class.forName("com.holzyn.actor.domain.growth.attention.CognitiveAttentionService");
        assertNoTransaction(attention, "tickAsync", Long.class, Long.class);
        assertNoTransaction(attention, "eventRelevanceAsync", Long.class,
                Class.forName("com.holzyn.actor.domain.world.entity.ActorEvent"));
        Class<?> trigger = Class.forName("com.holzyn.actor.domain.growth.space.SpaceTriggerJudge");
        assertNoTransaction(trigger, "onEmotionEval", Long.class, String.class, String.class,
                Class.forName("com.holzyn.actor.domain.npc.state.entity.ActorNpcState"), Long.class);
        assertNoTransaction(trigger, "onRelationJump", Long.class, String.class, String.class,
                String.class, String.class, Long.class);
        assertNoTransaction(trigger, "onMaskContextChange", Long.class, String.class, String.class,
                String.class, Long.class);
        assertNoTransaction(trigger, "evaluateRelationDeltaAsync", Long.class, String.class, String.class,
                String.class, String.class, int.class, Long.class);
        Class<?> accident = Class.forName("com.holzyn.actor.domain.growth.evolution.AccidentJudge");
        assertNoTransaction(accident, "onEvent", Long.class,
                Class.forName("com.holzyn.actor.domain.world.entity.ActorEvent"));
        Class<?> shift = Class.forName("com.holzyn.actor.domain.growth.evolution.PersonalityShiftService");
        assertNoTransaction(shift, "assessAsync", Long.class, String.class, String.class, Long.class);
        assertNoTransaction(shift, "cycleCheck", Long.class, Long.class);
    }

    /**
     * P0-2 记忆写并发治理（2026-09-28）追加入网：记忆域与编年史域的 AI 长链路——反思
     * （reflection 约 48s + 嵌套 L3 自编辑约 82s）、L1→L2/L2→L3 蒸馏、L1 渐进模糊/睡眠清洗/琐事合并、
     * 编年史即时/每日提炼 + 实体状态判定——一律不得带 @Transactional。旧实现把 AI 包在事务里
     * （反思事务内嵌套第二轮 AI；蒸馏/模糊/清洗在单事务内跨条目持锁），实测 79 次
     * {@code Timeout trying to lock table "actor_memory"} + 26 次反思失败（重试再付 AI 费）。
     * 写阶段改由下游短事务承担（recordReflection/recheckMemories/downgradeEvidence/save 等，
     * 正向断言见 {@code MemoryWritePhaseShortTransactionTest}）。
     */
    @Test
    @DisplayName("P0-2 记忆/编年史 AI 链路不得带 @Transactional（反思/蒸馏/模糊/清洗/提炼）")
    void memoryAiChainsAreNotTransactional() throws Exception {
        // 反思链：AI1 + AI2 都在事务外（AI2 经 AfterCommit 提交后执行）
        Class<?> reflection = Class.forName("com.holzyn.actor.domain.agent.learning.ReflectionService");
        assertNoTransaction(reflection, "reflect", Long.class, String.class, Long.class);
        // 蒸馏链：L1→L2 / L2→L3 扫描与单条（AI 在事务外，写阶段仓库 save 短事务）
        Class<?> distill = Class.forName("com.holzyn.actor.domain.memory.service.MemoryDistillService");
        assertNoTransaction(distill, "scanL1toL2", Long.class, Long.class);
        assertNoTransaction(distill, "distillL1toL2", Long.class,
                Class.forName("com.holzyn.actor.domain.npc.memory.entity.ActorMemoryFuzzState"), Long.class);
        assertNoTransaction(distill, "scanL2toL3", Long.class, Long.class);
        assertNoTransaction(distill, "consolidateL2toL3", Long.class,
                Class.forName("com.holzyn.actor.domain.npc.memory.entity.ActorMemory"), Long.class);
        // 模糊/睡眠清洗链：渐进模糊（AI scene=memory_fuzz）、睡眠清洗（蒸馏+琐事合并 AI）、琐事合并（AI）
        Class<?> fuzz = Class.forName("com.holzyn.actor.domain.memory.service.L1FuzzificationService");
        assertNoTransaction(fuzz, "scanDueFuzz", Long.class, Long.class);
        assertNoTransaction(fuzz, "fuzzOne", Long.class,
                Class.forName("com.holzyn.actor.domain.npc.memory.entity.ActorMemoryFuzzState"), Long.class);
        assertNoTransaction(fuzz, "triggerFuzz", Long.class, Long.class, Long.class);
        assertNoTransaction(fuzz, "onSleepAction", Long.class, String.class, Long.class);
        assertNoTransaction(fuzz, "sleepCleanup", Long.class, String.class, Long.class);
        assertNoTransaction(fuzz, "mergeTrivia", Long.class, String.class, Class.forName("java.util.List"),
                Long.class);
        // 编年史链：即时/每日提炼 + 实体状态判定 + 巩固补漏（AI 在事务外，落库逐条短事务）
        Class<?> worldMem = Class.forName("com.holzyn.actor.domain.world.service.WorldMemoryService");
        assertNoTransaction(worldMem, "instantDistill", Long.class, Long.class,
                Class.forName("com.holzyn.actor.domain.world.entity.ActorEvent"));
        assertNoTransaction(worldMem, "dailyDistillIfNeeded", Long.class, Long.class, Long.class);
        assertNoTransaction(worldMem, "applyEntityUpdates", Long.class, Long.class, Long.class,
                Class.forName("java.util.List"));
        assertNoTransaction(worldMem, "consolidateWorldMemory", Long.class, Long.class, Long.class);
    }

    /**
     * 位置移动与地图生成系统 M1（2026-10-06）追加入网：世界地图生成编排
     * （world_map_generate AI 调用，秒级~分钟级）不得带 @Transactional；
     * 落库由 WorldMapPersistService.persist 短事务承担。
     */
    @Test
    @DisplayName("M1 世界地图生成编排不得带 @Transactional（AI 生成在事务外，落库分层）")
    void worldMapGenerationIsNotTransactional() throws Exception {
        Class<?> gen = Class.forName("com.holzyn.actor.domain.worldmap.service.WorldMapGenerationService");
        assertNoTransaction(gen, "generate", Long.class, Long.class,
                Class.forName("com.holzyn.actor.domain.worldmap.dto.WorldMapGenerateRequest"));
        assertNoTransaction(gen, "generateForInit", Long.class, Long.class);
    }

    /**
     * 位置移动与地图生成系统 M3（2026-10-06）追加入网：途中轻量交互触发
     * （MoveEncounterService.maybeEncounter——AI 判定 scene=move_encounter 秒级调用）
     * 不得带 @Transactional（AI 在虚拟线程事务外执行，落库由仓库短事务承担；
     * MovementService.cancelMove 亦保持零事务供引擎热路径调用）。
     */
    @Test
    @DisplayName("M3 途中轻量交互不得带 @Transactional（AI 判定在事务外，落库分层）")
    void moveEncounterIsNotTransactional() throws Exception {
        Class<?> enc = Class.forName("com.holzyn.actor.domain.worldmap.service.MoveEncounterService");
        assertNoTransaction(enc, "maybeEncounter", Long.class, String.class,
                double.class, double.class, Long.class);
        Class<?> movement = Class.forName("com.holzyn.actor.domain.worldmap.service.MovementService");
        assertNoTransaction(movement, "cancelMove", Long.class, String.class, Long.class);
    }

    /**
     * M3（2026-10-06）之后新增（2026-10-08）：世界常识摘要（V2.0 段 1A，world_common_knowledge）——
     * 依最新世界观 AI 提炼 300-500 字精炼常识（chat 网关调用，秒级）由世界观保存提交后
     * （afterCommit）与定期扫描触发，一律不得带 @Transactional；落库由
     * {@code worldSettingRepository.saveAndFlush} 短事务承担（对齐世界设定知识同步先例）。
     */
    @Test
    @DisplayName("世界常识摘要生成/定期扫描不得带 @Transactional（AI 提炼在事务外，落库分层）")
    void worldCommonKnowledgeIsNotTransactional() throws Exception {
        Class<?> svc = Class.forName("com.holzyn.actor.domain.world.service.WorldCommonKnowledgeService");
        assertNoTransaction(svc, "generate", Long.class, Long.class);
        assertNoTransaction(svc, "scanAndBackfill");
    }

    /**
     * 项目级 AI 助手（2026-10-10 第 50 次 · M1+M2）追加入网：对话编排
     * （AssistantChatService.chat——工具决策 jsonMode 调用 + 最终回答流式调用均为秒级~分钟级 AI）
     * 一律不得带 @Transactional；落库由 AssistantSessionService 短事务承担。
     */
    @Test
    @DisplayName("项目级 AI 助手对话编排不得带 @Transactional（工具循环与流式回答在事务外）")
    void assistantChatIsNotTransactional() throws Exception {
        Class<?> chat = Class.forName("com.holzyn.actor.domain.assistant.service.AssistantChatService");
        assertNoTransaction(chat, "chat", Long.class, Long.class, String.class, String.class,
                Class.forName("com.holzyn.actor.domain.assistant.service.AssistantChatService$SseSink"));
    }

    /**
     * 项目级 AI 助手（2026-10-12 第 51 次 · M3+M4）追加入网：世界干预服务
     * （AssistantInterventionService.plan/confirm/revise——执行路径会触发 AI：插话回复
     * RoleplayService.sendMessage 流式生成、触发行动 AgentRuntime.triggerAction 决策；
     * M5 修改后执行 revise 同样经 execute 触发同类 AI）
     * 一律不得带 @Transactional；落库由干预计划仓库/会话服务各自短事务承担。
     */
    @Test
    @DisplayName("项目级 AI 助手世界干预不得带 @Transactional（执行路径触发插话回复与行动决策 AI）")
    void assistantInterventionIsNotTransactional() throws Exception {
        Class<?> svc = Class.forName("com.holzyn.actor.domain.assistant.service.AssistantInterventionService");
        assertNoTransaction(svc, "plan", Long.class, Long.class, Long.class, String.class,
                Class.forName("java.util.Map"));
        assertNoTransaction(svc, "confirm", Long.class, Long.class, Long.class);
        assertNoTransaction(svc, "cancel", Long.class, Long.class, Long.class);
        assertNoTransaction(svc, "revise", Long.class, Long.class, Long.class,
                Class.forName("java.util.Map"));
    }

    /**
     * 对谈模式（2026-10-12 第 54 次 · M1 对谈地基）追加入网：单聊编排
     * （DialogueChatService.chat——SSE 流式回复 scene=dialogue_reply，秒级 AI）
     * 一律不得带 @Transactional；落库由 DialogueMessageService 短事务承担。
     */
    @Test
    @DisplayName("对谈单聊编排不得带 @Transactional（SSE 流式回复 AI 在事务外）")
    void dialogueChatIsNotTransactional() throws Exception {
        Class<?> chat = Class.forName("com.holzyn.actor.domain.dialogue.service.DialogueChatService");
        assertNoTransaction(chat, "chat", Long.class, Long.class, Long.class, String.class,
                Class.forName("com.holzyn.actor.domain.dialogue.service.DialogueChatService$SseSink"));
    }

    /**
     * 对谈模式（2026-10-12 第 54 次 · M1 对谈地基）追加入网：AI 动态开场白
     * （DialogueGreetingService.generate——scene=dialogue_greeting，秒级 AI）
     * 一律不得带 @Transactional；落库由 DialogueMessageService 短事务承担。
     */
    @Test
    @DisplayName("对谈 AI 动态开场白不得带 @Transactional（开场白 AI 在事务外）")
    void dialogueGreetingIsNotTransactional() throws Exception {
        Class<?> greeting = Class.forName("com.holzyn.actor.domain.dialogue.service.DialogueGreetingService");
        assertNoTransaction(greeting, "generate", Long.class, Long.class, Long.class);
    }

    /**
     * 对谈模式（2026-10-12 第 55 次 · M2 前端聊天 UI 单聊闭环）追加入网：重写一条角色回复
     * （DialogueChatService.regenerate——复用 dialogue_reply 流式生成，秒级 AI）
     * 一律不得带 @Transactional；截断/落库由 DialogueMessageService 短事务承担。
     */
    @Test
    @DisplayName("对谈重写回复不得带 @Transactional（重写 AI 在事务外）")
    void dialogueRegenerateIsNotTransactional() throws Exception {
        Class<?> chat = Class.forName("com.holzyn.actor.domain.dialogue.service.DialogueChatService");
        assertNoTransaction(chat, "regenerate", Long.class, Long.class, Long.class, Long.class,
                Class.forName("com.holzyn.actor.domain.dialogue.service.DialogueChatService$SseSink"));
    }

    /**
     * 对谈模式（2026-10-12 第 57 次 · M4 记忆/世界书等价物+上下文管理增强）追加入网：
     * 滚动窗口自动摘要（DialogueSummaryService.maybeSummarize——scene=dialogue_summary，
     * 超窗时秒级 AI 摘要）一律不得带 @Transactional；摘要写回由
     * DialogueSessionService.updateSummary 短事务承担。
     */
    @Test
    @DisplayName("对谈滚动窗口自动摘要不得带 @Transactional（摘要 AI 在事务外，写回短事务）")
    void dialogueSummaryIsNotTransactional() throws Exception {
        Class<?> summary = Class.forName("com.holzyn.actor.domain.dialogue.service.DialogueSummaryService");
        assertNoTransaction(summary, "maybeSummarize", Long.class, Long.class, Long.class);
    }

    /**
     * 对谈模式（2026-10-13 第 58 次 · M5 群聊）追加入网：群聊回复编排
     * （DialogueGroupChatService.groupChat——scene=dialogue_group_reply，逐角色独立
     * AI 调用串行流式）一律不得带 @Transactional；玩家/角色消息落库由
     * DialogueMessageService 短事务承担。
     */
    @Test
    @DisplayName("对谈群聊回复编排不得带 @Transactional（逐角色 AI 在事务外，落库短事务）")
    void dialogueGroupChatIsNotTransactional() throws Exception {
        Class<?> group = Class.forName("com.holzyn.actor.domain.dialogue.service.DialogueGroupChatService");
        assertNoTransaction(group, "groupChat", Long.class, Long.class, Long.class, String.class,
                Class.forName("com.holzyn.actor.domain.dialogue.service.DialogueChatService$SseSink"));
    }

    /**
     * 对谈模式（2026-10-13 第 58 次 · M5 群聊）追加入网：群聊开场白
     * （DialogueGreetingService.generateGroup——按在场角色逐人生成，秒级 AI）
     * 一律不得带 @Transactional；开场白落库由 DialogueMessageService 短事务承担。
     */
    @Test
    @DisplayName("对谈群聊开场白不得带 @Transactional（逐角色开场 AI 在事务外）")
    void dialogueGroupGreetingIsNotTransactional() throws Exception {
        Class<?> greeting = Class.forName("com.holzyn.actor.domain.dialogue.service.DialogueGreetingService");
        assertNoTransaction(greeting, "generateGroup", Long.class, Long.class, Long.class);
    }

    /**
     * 对谈模式（2026-10-13 第 59 次 · M6 沉淀收尾）追加入网：沉淀确认卡片
     * （DialogueConsolidationService.preview——scene=dialogue_consolidation，jsonMode
     * 非流式抽取，秒级 AI）一律不得带 @Transactional；落库由 apply 阶段的
     * PlayerProfileService.applyConsolidatedRelation / AgentMemoryService.recordObservation /
     * DialogueSessionService.markConsolidated 各自短事务承担。
     */
    @Test
    @DisplayName("对谈沉淀确认卡片不得带 @Transactional（AI 抽取在事务外，落库分层短事务）")
    void dialogueConsolidationIsNotTransactional() throws Exception {
        Class<?> consolidation = Class.forName("com.holzyn.actor.domain.dialogue.service.DialogueConsolidationService");
        assertNoTransaction(consolidation, "preview", Long.class, Long.class, Long.class);
    }

    /**
     * 世界地图生成管线 V1.1（S0 档案 / S2 资产 / S3 拓扑各一次 AI 调用，秒级~分钟级）追加入网：
     * 编排器 {@code MapGenOrchestrator#run}（含全部 AI 与数秒的重算）与三个阶段服务必须无事务；
     * 落库由 {@code MapPersistService} 的短事务承担（每阶段一次，几十毫秒）。
     *
     * <p>这条断言的环境独特性：{@code run} 是包私有的（只允许经 {@code generate/rerunStage}
     * 提交到线程池执行），所以这里用 {@code getDeclaredMethod} 取——不能用 {@code getMethod}，
     * 否则测试会因为「找不到方法」直接红，而不是真的检查了注解。</p>
     */
    @Test
    @DisplayName("V1.1 地图管线编排与三个阶段服务不得带 @Transactional（三次 AI 在事务外）")
    void mapgenPipelineIsNotTransactional() throws Exception {
        Class<?> orch = Class.forName("com.holzyn.actor.domain.mapgen.service.MapGenOrchestrator");
        Method run = orch.getDeclaredMethod("run", Long.class, Long.class, int.class);
        assertNull(run.getAnnotation(Transactional.class),
                "MapGenOrchestrator#run 不得带 @Transactional：内部有 3 次 AI 调用 + 数秒地形侵蚀，"
                        + "事务包住它就是数分钟的 H2 行锁；落库由 MapPersistService 短事务承担");
        assertNoTransaction(orch, "generate", Long.class, Long.class);
        assertNoTransaction(orch, "rerunStage", Long.class, String.class);

        assertNoTransaction(Class.forName("com.holzyn.actor.domain.mapgen.service.WorldMapProfileService"),
                "build", Long.class, long.class);
        assertNoTransaction(Class.forName("com.holzyn.actor.domain.mapgen.service.MapAssetPolicyService"),
                "build", Long.class, Class.forName("com.holzyn.actor.domain.mapgen.model.MapProfile"));
        // 注意：MapRoadPlanService#build 在 V1.3 增加了第 8 个参数 townRadius（米）——
        // 反射签名必须跟着走，否则这里会 NoSuchMethod 而不是「检查通过」，等于把护栏关掉。
        assertNoTransaction(Class.forName("com.holzyn.actor.domain.mapgen.service.MapRoadPlanService"),
                "build", Long.class,
                Class.forName("com.holzyn.actor.domain.mapgen.algo.TerrainData"),
                Class.forName("com.holzyn.actor.domain.mapgen.model.MapProfile"),
                Class.forName("com.holzyn.actor.domain.mapgen.model.MapAssetCatalog"),
                Class.forName("java.util.Map"), long.class, Class.forName("[F"), double.class);
    }

    /** 断言指定公开方法存在且未标注 @Transactional（方法不存在则测试失败，防止签名漂移后静默放过）。 */
    private void assertNoTransaction(Class<?> type, String name, Class<?>... params) throws Exception {
        Method m = type.getMethod(name, params);
        assertNull(m.getAnnotation(Transactional.class),
                type.getSimpleName() + "#" + name + " 不得带 @Transactional："
                        + "AI 调用（秒级）一旦在事务内执行，actor_state 行锁会被持有数十秒，"
                        + "其它线程的状态写回将连锁超时并可丢数据（详见类注释的三轮现场证据）");
    }
}