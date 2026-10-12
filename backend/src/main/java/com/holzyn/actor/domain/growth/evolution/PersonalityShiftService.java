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

package com.holzyn.actor.domain.growth.evolution;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.nanojev.NanoJevQuestion;
import com.holzyn.actor.ai.nanojev.NanoJevState;
import com.holzyn.actor.ai.nanojev.decision.NanoJevDecisionGate;
import com.holzyn.actor.ai.nanojev.decision.NanoJevDecisionScenes;
import com.holzyn.actor.ai.nanojev.decision.NanoJevDecisionTemplates;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.mask.MaskService;
import com.holzyn.actor.domain.growth.metrics.GrowthMetricsService;
import com.holzyn.actor.domain.growth.persona.PersonaDims;
import com.holzyn.actor.domain.growth.persona.PersonaJson;
import com.holzyn.actor.domain.growth.persona.PersonaProfile;
import com.holzyn.actor.domain.growth.persona.PersonaRevisionService;
import com.holzyn.actor.domain.growth.stage.GrowthStage;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.sandbox.service.WorldSandboxSseHub;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 人格演化引擎。
 * <p><b>流程</b>：</p>
 * <ol>
 * <li><b>触发</b>：30 游戏日周期（{@link #cycleCheck}，挂日级）／阶段跨越强制／重大事件
 * （importance≥4 涉及本人，WorldEventBus 监听）／测试接口 manual；</li>
 * <li><b>证据收集</b>：{@link ShiftEvidenceCollector}——L3 核心块 + L2 记忆（不用 L1），
 * 证据强度公式打分；低于 {@code minEvidenceScore} → 本轮跳过（零 AI，设计 §5.5「阈值 T1 以下为 0」）；</li>
 * 角色骨架 + 当前 OCEAN/价值权重 + 证据摘要，输出各维偏移量与理由；</li>
 * <li><b>程序化截断</b>：{@link #truncate}——单次偏移按阶段上限（INFANT 0 → 青少年 0.20 →
 * 成年 0.10）、valueWeights 每次 ±0.05、生命周期累计 |ΣΔ| ≤ 0.30（按阶段递减）硬截断；</li>
 * <li><b>写回</b>：④ OCEAN / ⑤ valueWeights 写回 {@code persona_json}（版本 +1 + 修订快照留痕），
 * 全部落 {@code actor_growth_shift_log}（证据链 + AI 理由，验收②）；</li>
 * <li><b>下行</b>：单条 |Δ| &gt; {@code minorShiftAlert}（0.15）→ SSE/Unity 下行
 * <li><b>面具固化</b>：周期评估顺带执行 {@link MaskService#solidifyMasks}（无破绽高频社交 +0.05）。</li>
 * </ol>
 * <p><b>AI 失败兜底</b>：程序化兜底=本轮零偏移（记录 source=programmatic 的 shift_log 留痕 +
 * 指标），绝不因评估失败改变人格（真实的人不会因为系统故障而变）——零阻断。</p>
 * <p><b>架构不变量</b>：本类公开方法<b>不含</b>{@code @Transactional}（AI 调用在虚拟线程异步执行，
 * 写库走仓库短事务，对齐「任何含 AI 调用的方法都不得带 @Transactional」）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonalityShiftService {

    /** 人格偏移评估场景码（PromptSpecRegistry 登记 + PromptTemplateService 内置模板） */
    public static final String SCENE_SHIFT_EVAL = "growth_persona_shift_eval";

    /** 评估异步执行器（虚拟线程：单角色评估含一次 AI 调用，绝不占引擎线程） */
    private final ExecutorService assessExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final ActorCharacterRepository characterRepository;
    private final GrowthShiftLogRepository shiftLogRepository;
    private final ShiftEvidenceCollector evidenceCollector;
    private final GrowthProperties growthProperties;
    private final GrowthMetricsService growthMetrics;
    private final MaskService maskService;
    private final PromptTemplateService promptTemplateService;
    private final AiSpringChatGateway aiChatGateway;
    private final CurrentUserProvider currentUserProvider;
    private final UsageLogService usageLogService;
    private final WorldClockService worldClockService;
    private final PersonaRevisionService personaRevisionService;
    private final WorldSandboxSseHub sandboxSseHub;

    /**
     * 人格偏移判定门（persona_shift 场景——NanoJev 初判[证据强度 score +
     * 偏移方向 choice，分布尖锐才采纳否则升 Qwen]；字段注入保持构造器不变，null=未装配走 Qwen 现状）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NanoJevDecisionGate nanoJevDecisionGate;

    /** qid：证据强度（persona_shift 场景，score） */
    public static final String QID_SHIFT_STRENGTH = "shift_strength";
    /** qid：偏移方向（persona_shift 场景，choice） */
    public static final String QID_SHIFT_DIRECTION = "shift_direction";

    /**
     * 统一上下文注入器（V2.0 段位装配；字段注入保持构造器不变）。
     * <p>批 6 收口（growth_persona_shift_eval 属批4 记忆链·角色视角）：Qwen 深度偏移判定请求
     * 前置 段1A【世界观】+段1C【角色核心卡】+段2D【当前世界动态】+段2E【当前状态】
     * （{@code memoryChainContext}）；null=未装配 → 原样渲染（fail-open 零阻断）。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    /** persona_shift 采纳后偏移幅度：证据强度「中」=0.03 /「强」=0.05（低于单次上限 0.10，高于噪声 0.005） */
    private static final double SHIFT_MAG_LEVEL2 = 0.03;
    private static final double SHIFT_MAG_LEVEL3 = 0.05;

    //
    // 触发入口
    //

    /**
     * 周期检查（挂日级维护）：游戏日达到 {@code shift.assess-cycle-game-days}（默认 30）的整数倍
     * 时，对全项目证据充分的 NPC 异步执行周期评估（含面具固化）。
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 当前世界时刻
     */
    public void cycleCheck(Long projectId, Long gameSecond) {
        if (!growthProperties.getShift().isEnabled() || projectId == null || gameSecond == null) {
            return;
        }
        int cycleDays = Math.max(1, growthProperties.getShift().getAssessCycleGameDays());
        long day = gameSecond / 86400L;
        if (day % cycleDays != 0) {
            return;
        }
        log.info("[人格演化] 周期评估触发：project={} 第 {} 游戏日（每 {} 日）", projectId, day, cycleDays);
        for (ActorCharacter ch : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
            if (GrowthStage.DECEASED.name().equalsIgnoreCase(ch.getStage())) {
                continue;
            }
            assessAsync(projectId, ch.npcRef(), GrowthShiftLog.TRIGGER_CYCLE, gameSecond);
        }
    }

    /**
     * 单角色立即评估（阶段跨越强制 / 重大事件 / 测试接口共用；异步执行）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 本人统一引用
     *
     * @param trigger 触发码（stage_transition / major_event / manual）
     *
     * @param gameSecond 当前世界时刻
     */
    public void assessAsync(Long projectId, String npcRef, String trigger, Long gameSecond) {
        if (!growthProperties.getShift().isEnabled()) {
            return;
        }
        assessExecutor.submit(() -> {
            try {
                assess(projectId, npcRef, trigger, gameSecond);
            } catch (Exception e) {
                log.warn("[人格演化] 异步评估异常（隔离）: ref={} 原因={}", npcRef, e.getMessage());
            }
        });
    }

    //
    // 评估主流程（混合引擎）
    //

    /**
     * 评估主流程（可同步调用供测试；生产入口走 {@link #assessAsync}）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 本人统一引用
     *
     * @param trigger 触发码
     *
     * @param gameSecond 当前世界时刻
     *
     * @return 评估结果摘要（测试/调试用；失败返回 null）
     */
    public Map<String, Object> assess(Long projectId, String npcRef, String trigger, Long gameSecond) {
        if (!growthProperties.getShift().isEnabled() || projectId == null || npcRef == null) {
            return null;
        }
        ActorCharacter ch = characterRepository.findById(NpcRefs.idOf(npcRef)).orElse(null);
        if (ch == null || GrowthStage.DECEASED.name().equalsIgnoreCase(ch.getStage())) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("npcRef", npcRef);
        out.put("trigger", trigger);
        out.put("gameSecond", gameSecond);
        try {
            // ① 证据收集（窗口 = 上次评估时刻；首次评估全量）
            Long windowStart = shiftLogRepository.lastAssessedGameSecond(projectId, npcRef);
            var collect = evidenceCollector.collect(projectId, npcRef, windowStart, gameSecond,
                    growthProperties.getShift().getMinEvidenceScore(),
                    growthProperties.getShift().getEvidenceTopN());
            out.put("evidenceScore", collect.totalScore());
            out.put("evidenceCount", collect.evidence().size());
            if (!collect.sufficient()) {
                log.info("[人格演化] 证据不足，本轮跳过（零 AI）: ref={} 得分={}", npcRef, collect.totalScore());
                out.put("skipped", "evidence_insufficient");
                return out;
            }
            // ② AI 判定偏移（：persona_shift 场景 NanoJev 初判——证据强度 score + 方向 choice
            // 一次前向；分布尖锐才采纳（含采样），否则升 Qwen 深度判定；未启用/失败同样升 Qwen）
            NanoJevShiftResult nanoJev = nanoJevShiftAssess(projectId, ch, collect, gameSecond);
            List<ShiftParse.ShiftItem> items;
            if (nanoJev == null) {
                items = aiAssess(projectId, ch, collect, gameSecond);
            } else {
                items = nanoJev.items();
            }
            if (items.isEmpty()) {
                if (nanoJev != null) {
                    // NanoJev 明确判定「证据不足/方向不明」→ 正常零偏移留痕（评估成功，不记失败指标）
                    writeProgrammaticNoop(projectId, ch, gameSecond, trigger, collect,
                            "NanoJev 判定证据不足以支持偏移或方向不明，本轮不改变人格（零偏移留痕）");
                } else {
                    // AI 失败/无有效偏移 → 程序化兜底：本轮零偏移（留痕 + 指标）
                    growthMetrics.recordShiftAssessFail();
                    writeProgrammaticNoop(projectId, ch, gameSecond, trigger, collect,
                            "AI 评估失败或无有效偏移，本轮不改变人格（程序化兜底）");
                }
                out.put("shifts", List.of());
                out.put("fallback", nanoJev != null ? "nanojev_no_shift" : "ai_empty");
                return out;
            }
            // ③ 程序化截断 + 写回
            List<Map<String, Object>> applied = applyShifts(projectId, ch, items, trigger, gameSecond, collect);
            out.put("shifts", applied);
            // ④ 周期评估顺带面具固化
            if (GrowthShiftLog.TRIGGER_CYCLE.equals(trigger)) {
                maskService.solidifyMasks(projectId, ch, gameSecond);
            }
            growthMetrics.recordShiftAssessOk();
            return out;
        } catch (Exception e) {
            log.warn("[人格演化] 评估异常（零阻断）: ref={} 原因={}", npcRef, e.getMessage());
            return null;
        }
    }

    /**
     * AI 判定偏移（scene=growth_persona_shift_eval；含 AI 调用，禁止 @Transactional 栈内执行）。
     *
     * @return 解析后的偏移条目（空=无有效偏移）
     */
    private List<ShiftParse.ShiftItem> aiAssess(Long projectId, ActorCharacter ch,
                                                ShiftEvidenceCollector.CollectResult collect, Long gameSecond) {
        Long uid = currentUserProvider.currentUserId();
        Map<String, Object> ph = new LinkedHashMap<>();
        ph.put("name", ch.getName());
        ph.put("age", ch.getAge() == null ? "?" : ch.getAge());
        ph.put("stage", stageLabel(ch.getStage()));
        ph.put("personality_current", renderOcean(ch));
        ph.put("values_current", renderValues(ch));
        ph.put("evidence", renderEvidence(collect.evidence()));
        String prompt = promptTemplateService.render(uid, projectId, SCENE_SHIFT_EVAL, ph);
        // 批6 收口（growth_persona_shift_eval 属批4 记忆链·角色视角）：前置 段1A+1C+2D+2E——
        // 「人格该不该偏移」先知道世界/身份/此刻处境；未装配 → 原样（fail-open）
        if (aiContextInjector != null) {
            String cctx = aiContextInjector.memoryChainContext(projectId, ch.npcRef(), gameSecond);
            if (cctx != null && !cctx.isBlank()) {
                prompt = cctx + "\n\n" + prompt;
            }
        }
        String system = promptTemplateService.systemMessage(uid, projectId, SCENE_SHIFT_EVAL, null);
        if (system.isBlank()) {
            system = "你是严谨的人格演化评估员：只输出严格 JSON；依据证据判定偏移，宁缺毋滥。";
        }
        AiChatRequest req = new AiChatRequest(null, List.of(
                new AiChatRequest.ChatMessage("system", system),
                new AiChatRequest.ChatMessage("user", prompt)),
                PromptSpecRegistry.temperatureOf(SCENE_SHIFT_EVAL),
                PromptSpecRegistry.maxOutputTokensOf(SCENE_SHIFT_EVAL),
                true);
        AiChatResult result = AiCallContext.run(SCENE_SHIFT_EVAL, ch.getId(), 1,
                () -> aiChatGateway.chatCompletion(uid, projectId, null, req));
        try {
            usageLogService.record(uid, projectId, ch.getId(), result.providerId(), result.model(),
                    SCENE_SHIFT_EVAL, result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(), 0);
        } catch (Exception e) {
            log.warn("[人格演化] 用量入账失败（已忽略）: {}", e.getMessage());
        }
        return ShiftParse.parse(result.content());
    }

    /** persona_shift 初判结果：null=未采纳（升 Qwen）；非 null=采纳（items 可空=明确判定无偏移）。 */
    private record NanoJevShiftResult(List<ShiftParse.ShiftItem> items) {
    }

    /**
     * persona_shift 初判（NanoJev 判定层，含 AI 调用，禁止 @Transactional 栈内执行）。
     * <p>同 state 一次前向：证据强度（score 4 档）+ 偏移方向（choice 正/负/不明）。
     * 门控（{@code scenes.persona-shift}，默认开、escalation top1-prob=0.6）：分布不尖锐 →
     * 升级 Qwen 同题重问；采纳后按强度×方向程序化产出偏移条目（OCEAN 外向/宜人/神经质三维，
     * 幅度 0.03/0.05，仍走既有阶段/累计截断硬约束）。：尖锐采纳否则升 Qwen。</p>
     *
     * @return null=未启用/升级/失败（调用方升 Qwen）；空列表=采纳且明确判定无偏移；非空=采纳偏移条目
     */
    private NanoJevShiftResult nanoJevShiftAssess(Long projectId, ActorCharacter ch,
                                                  ShiftEvidenceCollector.CollectResult collect,
                                                  Long gameSecond) {
        if (nanoJevDecisionGate == null
                || !nanoJevDecisionGate.isEnabled(NanoJevDecisionScenes.PERSONA_SHIFT)) {
            return null;
        }
        Map<String, NanoJevQuestion> questions = NanoJevState.newQuestions();
        questions.put(QID_SHIFT_STRENGTH, NanoJevDecisionTemplates.personaShiftEvidenceQuestion());
        questions.put(QID_SHIFT_DIRECTION, NanoJevDecisionTemplates.personaShiftDirectionQuestion());
        NanoJevState state = NanoJevState.text("persona_shift",
                buildShiftStateText(ch, collect), questions);
        Map<String, Object> out = nanoJevDecisionGate.mixedDecisions(projectId, ch.getId(),
                NanoJevDecisionScenes.PERSONA_SHIFT, state,
                Map.of(QID_SHIFT_STRENGTH, NanoJevQuestion.TYPE_SCORE,
                        QID_SHIFT_DIRECTION, NanoJevQuestion.TYPE_CHOICE));
        Object strengthRaw = out.get(QID_SHIFT_STRENGTH);
        Object dirRaw = out.get(QID_SHIFT_DIRECTION);
        if (!(strengthRaw instanceof NanoJevDecisionGate.ScoreOutcome so) || !so.adopted()) {
            return null; // 升级/失败/未启用 → 升 Qwen
        }
        if (!(dirRaw instanceof NanoJevDecisionGate.ChoiceOutcome co) || !co.adopted()) {
            return null;
        }
        // 采纳（分布尖锐）：证据强度 < 中 或 方向不明 → 明确判定本轮无偏移（零 AI 完成评估）
        if (so.level() < 2 || co.chosen() == null || "unclear".equals(co.chosen())) {
            log.info("[人格演化] NanoJev 判定无偏移：ref={} 强度档={} 方向={}", ch.npcRef(), so.level(), co.chosen());
            return new NanoJevShiftResult(List.of());
        }
        double mag = so.level() >= 3 ? SHIFT_MAG_LEVEL3 : SHIFT_MAG_LEVEL2;
        boolean positive = "positive".equals(co.chosen());
        String why = "NanoJev 判定证据" + (so.level() >= 3 ? "强" : "中")
                + "、方向" + (positive ? "正向" : "负向") + "（persona_shift 场景）";
        // 三维签名：正向=外向/宜人 ↑、神经质 ↓；负向=镜像（与现实人格漂移的维度相关一致）
        List<ShiftParse.ShiftItem> items = List.of(
                new ShiftParse.ShiftItem("ocean", "extraversion", positive ? mag : -mag, why),
                new ShiftParse.ShiftItem("ocean", "agreeableness", positive ? mag : -mag, why),
                new ShiftParse.ShiftItem("ocean", "neuroticism", positive ? -mag : mag, why));
        log.info("[人格演化] NanoJev 采纳偏移：ref={} 强度档={} 方向={} 幅度={}", ch.npcRef(),
                so.level(), co.chosen(), mag);
        return new NanoJevShiftResult(items);
    }

    /** persona_shift 初判情境文本（自包含：身份/阶段/当前人格/价值权重/证据集）。 */
    private String buildShiftStateText(ActorCharacter ch, ShiftEvidenceCollector.CollectResult collect) {
        StringBuilder sb = new StringBuilder();
        sb.append("角色：").append(ch.getName() == null || ch.getName().isBlank() ? "该角色" : ch.getName())
                .append("（").append(stageLabel(ch.getStage())).append("）\n");
        sb.append("当前人格 OCEAN：").append(renderOcean(ch)).append('\n');
        sb.append("当前价值权重：").append(renderValues(ch)).append('\n');
        sb.append("本轮评估证据：\n").append(renderEvidence(collect.evidence()));
        return sb.toString();
    }

    /**
     * 程序化截断 + 写回（④ OCEAN / ⑤ valueWeights；逐条落 shift_log；|Δ|>0.15 下行）。
     *
     * @return 实际落账的偏移条目（含截断后 Δ 与理由）
     */
    private List<Map<String, Object>> applyShifts(Long projectId, ActorCharacter ch,
                                                  List<ShiftParse.ShiftItem> items, String trigger,
                                                  Long gameSecond, ShiftEvidenceCollector.CollectResult collect) {
        List<Map<String, Object>> applied = new ArrayList<>();
        // 读当前档案（读改写一次完成；persona_json 是唯一 PERSONA 权威）
        PersonaProfile persona = PersonaJson.readQuietly(ch.getPersonaJson());
        if (persona == null || persona.getDims() == null) {
            log.warn("[人格演化] 档案缺失，偏移不落账: ref={}", ch.npcRef());
            return applied;
        }
        boolean changed = false;
        for (ShiftParse.ShiftItem item : items) {
            try {
                double cappedDelta = truncate(projectId, ch, item.dim(), item.field(), item.delta());
                if (Math.abs(cappedDelta) < 0.005) {
                    continue; // 截断后归零（已达上限/阶段不允许）
                }
                Object oldVal = readField(persona, item.dim(), item.field());
                if (!(oldVal instanceof Number n)) {
                    continue;
                }
                double old = n.doubleValue();
                double next = clamp01(old + cappedDelta);
                if (Math.abs(next - old) < 0.005) {
                    continue;
                }
                writeField(persona, item.dim(), item.field(), next);
                changed = true;
                writeShiftLog(projectId, ch, gameSecond, trigger, item.dim(), item.field(),
                        old, next, round3(next - old), item.why(),
                        GrowthShiftLog.SOURCE_AI, evidenceJson(collect));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("dim", item.dim());
                m.put("field", item.field());
                m.put("old", round3(old));
                m.put("new", round3(next));
                m.put("delta", round3(next - old));
                m.put("why", item.why());
                applied.add(m);
                // 验收⑥：|Δ|>0.15 走 SSE/Unity 下行
                if (Math.abs(next - old) > growthProperties.getShift().getMinorShiftAlert()) {
                    publishShiftEvent(projectId, ch, item, old, next);
                }
            } catch (Exception e) {
                log.warn("[人格演化] 单条偏移落账失败（跳过）: ref={} item={} 原因={}",
                        ch.npcRef(), item, e.getMessage());
            }
        }
        if (changed) {
            // 落库 + 版本号推进（Prompt 缓存失效）+ 修订快照留痕（审计链）
            ch.setPersonaJson(PersonaJson.write(persona));
            int ver = (ch.getPersonaVersion() == null ? 0 : ch.getPersonaVersion()) + 1;
            ch.setPersonaVersion(ver);
            characterRepository.save(ch);
            personaRevisionService.snapshot(projectId, ch.getId(), ver, ch.getPersonaJson(),
                    "evolution", null, SCENE_SHIFT_EVAL, "人格演化自动偏移");
            log.info("[人格演化] 偏移落账：ref={} 条目={} 版本→{}", ch.npcRef(), applied.size(), ver);
        }
        return applied;
    }

    //
    // 截断策略（程序化硬边界，防 OOC）
    //

    /**
     * 单条偏移截断（纯函数，可单测；验收①）：
     * <ol>
     * <li>阶段单次上限：INFANT 0 / TODDLER 0.05 / CHILD 0.10 / ADOLESCENT 0.20 / 成年 0.10（配置
     * <li>生命周期累计上限：|ΣΔ| ≤ 阶段累计上限（INFANT 0 / TODDLER 0.05 / CHILD 0.10 /
     * ADOLESCENT 0.20 / 成年 0.30）——按 shift_log 同维同字段历史 Δ 求和；</li>
     * </ol>
     */
    public double truncate(Long projectId, ActorCharacter ch, String dim, String field, double delta) {
        double cap = singleCap(ch, dim);
        double clipped = Math.max(-cap, Math.min(cap, delta));
        double cumCap = cumulativeCap(ch);
        double cum = cumulativeDelta(projectId, ch.npcRef(), dim, field);
        double remaining = cumCap - Math.abs(cum);
        if (remaining <= 0) {
            return 0;
        }
        double allowed = Math.max(-remaining, Math.min(remaining, clipped));
        return Math.abs(allowed) < 0.005 ? 0 : round3(allowed);
    }

    /** 阶段单次上限（values 维另受配置小步长约束）。 */
    private double singleCap(ActorCharacter ch, String dim) {
        double stageCap = stageSingleCap(ch == null ? null : ch.getStage());
        double cfgCap = "values".equals(dim)
                ? growthProperties.getShift().getMaxValueShiftPerEval()
                : growthProperties.getShift().getMaxShiftPerEval();
        return Math.min(stageCap, cfgCap);
    }

    /** 阶段单次偏移上限（设计 §5.5：INFANT 0 / TODDLER 0.05 / CHILD 0.10 / ADOLESCENT 0.20 / 成年 0.10）。 */
    static double stageSingleCap(String stage) {
        if (stage == null) {
            return 0.10;
        }
        return switch (stage) {
            case "INFANT" -> 0.0;
            case "TODDLER" -> 0.05;
            case "CHILD" -> 0.10;
            case "ADOLESCENT" -> 0.20;
            default -> 0.10;
        };
    }

    /** 阶段生命周期累计上限（设计 §5.5：成年 0.30，未成年按比例；INFANT 0）。 */
    static double stageCumulativeCap(String stage) {
        if (stage == null) {
            return 0.30;
        }
        return switch (stage) {
            case "INFANT" -> 0.0;
            case "TODDLER" -> 0.05;
            case "CHILD" -> 0.10;
            case "ADOLESCENT" -> 0.20;
            default -> 0.30;
        };
    }

    /** 当前阶段累计上限。 */
    private double cumulativeCap(ActorCharacter ch) {
        double stageCap = stageCumulativeCap(ch == null ? null : ch.getStage());
        return Math.min(stageCap, growthProperties.getShift().getMaxCumulativeShift());
    }

    /** 同维同字段历史累计 Δ（ΣΔ = 当前值 − 预设基线；shift_log 只增不改，直接求和）。 */
    private double cumulativeDelta(Long projectId, String npcRef, String dim, String field) {
        double sum = 0;
        try {
            List<GrowthShiftLog> logs = shiftLogRepository.findTop200ByProjectIdAndNpcRefOrderByIdDesc(projectId, npcRef);
            for (GrowthShiftLog l : logs) {
                if (l == null || l.getDelta() == null) {
                    continue;
                }
                if (dim.equals(l.getDim()) && field.equals(l.getField())) {
                    sum += l.getDelta();
                }
            }
        } catch (Exception e) {
            log.warn("[人格演化] 累计偏移读取失败（按 0，零阻断）: {}", e.getMessage());
        }
        return round3(sum);
    }

    //
    // 档案读写
    //

    /** 读 OCEAN 维 / valueWeights 键当前值（无值返回 null）。 */
    private Object readField(PersonaProfile persona, String dim, String field) {
        if ("ocean".equals(dim) && persona.getDims().getPersonality() != null
                && persona.getDims().getPersonality().getOcean() != null) {
            return oceanValue(persona.getDims().getPersonality().getOcean(), field);
        }
        if ("values".equals(dim) && persona.getDims().getValues() != null
                && persona.getDims().getValues().getValueWeights() != null) {
            return persona.getDims().getValues().getValueWeights().get(field);
        }
        return null;
    }

    /** 写 OCEAN 维 / valueWeights 键新值。 */
    private void writeField(PersonaProfile persona, String dim, String field, double value) {
        if ("ocean".equals(dim) && persona.getDims().getPersonality() != null
                && persona.getDims().getPersonality().getOcean() != null) {
            setOceanValue(persona.getDims().getPersonality().getOcean(), field, value);
        } else if ("values".equals(dim) && persona.getDims().getValues() != null) {
            persona.getDims().getValues().getValueWeights().put(field, value);
        }
    }

    private static Double oceanValue(PersonaDims.Ocean ocean, String field) {
        return switch (field) {
            case "openness" -> ocean.getOpenness();
            case "conscientiousness" -> ocean.getConscientiousness();
            case "extraversion" -> ocean.getExtraversion();
            case "agreeableness" -> ocean.getAgreeableness();
            case "neuroticism" -> ocean.getNeuroticism();
            default -> null;
        };
    }

    private static void setOceanValue(PersonaDims.Ocean ocean, String field, double value) {
        switch (field) {
            case "openness" -> ocean.setOpenness(value);
            case "conscientiousness" -> ocean.setConscientiousness(value);
            case "extraversion" -> ocean.setExtraversion(value);
            case "agreeableness" -> ocean.setAgreeableness(value);
            case "neuroticism" -> ocean.setNeuroticism(value);
            default -> { /* 未知字段忽略 */ }
        }
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    //
    // Prompt 输入渲染
    //

    private String renderOcean(ActorCharacter ch) {
        PersonaProfile p = PersonaJson.readQuietly(ch.getPersonaJson());
        if (p == null || p.getDims() == null || p.getDims().getPersonality() == null
                || p.getDims().getPersonality().getOcean() == null) {
            return "（无 OCEAN 数据）";
        }
        PersonaDims.Ocean o = p.getDims().getPersonality().getOcean();
        return String.format("开放 %.2f / 尽责 %.2f / 外向 %.2f / 宜人 %.2f / 神经质 %.2f",
                nvl(o.getOpenness()), nvl(o.getConscientiousness()), nvl(o.getExtraversion()),
                nvl(o.getAgreeableness()), nvl(o.getNeuroticism()));
    }

    private String renderValues(ActorCharacter ch) {
        PersonaProfile p = PersonaJson.readQuietly(ch.getPersonaJson());
        if (p == null || p.getDims() == null || p.getDims().getValues() == null
                || p.getDims().getValues().getValueWeights() == null
                || p.getDims().getValues().getValueWeights().isEmpty()) {
            return "（无价值权重数据）";
        }
        StringBuilder sb = new StringBuilder();
        p.getDims().getValues().getValueWeights().forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(k).append(" ").append(String.format("%.2f", v == null ? 0 : v));
        });
        return sb.toString();
    }

    private String renderEvidence(List<ShiftEvidenceCollector.Evidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (ShiftEvidenceCollector.Evidence e : evidence) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append("- [").append(e.source()).append(" 重要度").append(e.importance())
                    .append(" 强度").append(String.format("%.1f", e.score())).append("] ").append(e.content());
        }
        return sb.toString();
    }

    //
    // 日志与下行
    //

    private void writeShiftLog(Long projectId, ActorCharacter ch, Long gameSecond, String trigger,
                               String dim, String field, double oldVal, double newVal, double delta,
                               String reason, String source, String evidenceJson) {
        try {
            GrowthShiftLog l = new GrowthShiftLog();
            l.setProjectId(projectId);
            l.setNpcRef(ch.npcRef());
            l.setGameSecond(gameSecond == null ? 0 : gameSecond);
            l.setTriggerCode(trigger);
            l.setDim(dim);
            l.setField(field);
            l.setOldValue(String.format("%.4f", oldVal));
            l.setNewValue(String.format("%.4f", newVal));
            l.setDelta(delta);
            l.setStageCap(stageSingleCap(ch.getStage()) + "");
            l.setAiReason(reason);
            l.setSource(source);
            l.setEvidenceJson(evidenceJson);
            shiftLogRepository.save(l);
            growthMetrics.recordShiftEntry();
        } catch (Exception e) {
            log.warn("[人格演化] 偏移日志写入失败（跳过）: {}", e.getMessage());
        }
    }

    /** AI 失败程序化兜底留痕（零偏移）。 */
    private void writeProgrammaticNoop(Long projectId, ActorCharacter ch, Long gameSecond, String trigger,
                                       ShiftEvidenceCollector.CollectResult collect, String reason) {
        try {
            GrowthShiftLog l = new GrowthShiftLog();
            l.setProjectId(projectId);
            l.setNpcRef(ch.npcRef());
            l.setGameSecond(gameSecond == null ? 0 : gameSecond);
            l.setTriggerCode(trigger);
            l.setDim("ocean");
            l.setField("_noop");
            l.setOldValue("-");
            l.setNewValue("-");
            l.setDelta(0d);
            l.setStageCap(stageSingleCap(ch.getStage()) + "");
            l.setAiReason(reason);
            l.setSource(GrowthShiftLog.SOURCE_PROGRAMMATIC);
            l.setEvidenceJson(evidenceJson(collect));
            shiftLogRepository.save(l);
            growthMetrics.recordShiftEntry();
        } catch (Exception e) {
            log.warn("[人格演化] 兜底留痕失败（跳过）: {}", e.getMessage());
        }
    }

    private String evidenceJson(ShiftEvidenceCollector.CollectResult collect) {
        if (collect == null || collect.evidence() == null || collect.evidence().isEmpty()) {
            return "{}";
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalScore", collect.totalScore());
        List<Map<String, Object>> list = new ArrayList<>();
        for (ShiftEvidenceCollector.Evidence e : collect.evidence()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", e.refId());
            x.put("source", e.source());
            x.put("importance", e.importance());
            x.put("score", e.score());
            x.put("content", e.content());
            list.add(x);
        }
        m.put("items", list);
        try {
            return PersonaJson.mapper().writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 人格偏移下行（SSE npc:personality_shift；UnityWsHub 映射 npc_personality_shift，验收⑥）。 */
    private void publishShiftEvent(Long projectId, ActorCharacter ch, ShiftParse.ShiftItem item,
                                   double oldVal, double next) {
        try {
            if (sandboxSseHub != null) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("npcRef", ch.npcRef());
                data.put("npcName", ch.getName());
                data.put("dim", item.dim());
                data.put("field", item.field());
                data.put("old", round3(oldVal));
                data.put("new", round3(next));
                data.put("delta", round3(next - oldVal));
                data.put("why", item.why());
                sandboxSseHub.publish(projectId, WorldSandboxSseHub.EVENT_NPC_PERSONALITY_SHIFT, data);
            }
        } catch (Exception e) {
            log.debug("[人格演化] 下行失败（跳过）: {}", e.getMessage());
        }
    }

    //
    // 工具
    //

    private static String stageLabel(String stage) {
        GrowthStage s = GrowthStage.of(stage);
        return s == null ? (stage == null ? "未知" : stage) : s.label();
    }

    private static double nvl(Double v) {
        return v == null ? 0.5 : v;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
