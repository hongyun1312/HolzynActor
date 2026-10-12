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

package com.holzyn.actor.ai.nanojev.decision;

import com.holzyn.actor.ai.nanojev.NanoJevAnswer;
import com.holzyn.actor.ai.nanojev.NanoJevEvaluateClient;
import com.holzyn.actor.ai.nanojev.NanoJevEvaluateResponse;
import com.holzyn.actor.ai.nanojev.NanoJevSampler;
import com.holzyn.actor.ai.nanojev.NanoJevState;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * NanoJev 判定层 · 统一判定门。
 * <p><b>定位</b>：N 轨道设计 §7 「全部走『开关 + 回退 + 采样/argmax』统一框架」的统一入口——
 * 业务插拔点 只依赖本门，
 * 不直接摸 {@link NanoJevEvaluateClient}（调用门面不变，本门是<b>判定基础设施</b>，非业务门面）。</p>
 * <p><b>统一框架四要素</b>：</p>
 * <ol>
 * <li><b>开关</b>：三重门控 fail-closed——判定层总开关 {@code decision.enabled} && 场景开关
 * 任一不满足 → 返回「未启用」（调用方回退 Qwen/程序化=现状）；</li>
 * <li><b>回退</b>：任何异常/响应解析失败/静默回退检测 → 返回「失败」（调用方回退，零阻断）；
 * NanoJev 只是加速层，永不卡死决策链路；</li>
 * <li><b>采样</b>：sample（默认，按完整概率分布加权随机=真人式多样，低权重项也有机会被选中）
 * / argmax（确定性，测试与回放）；</li>
 * <li><b>升级策略</b>：场景配置 escalation.enabled=true 时，top-1 概率 &lt; top1-prob
 * （或熵 &gt; entropy，任一配置维度超限即升级）→ 判定不可信 → 返回「升级」
 * （调用方升 Qwen 深度处理）。 实测标定：social_willingness top1Prob=0.5 已接线。</li>
 * </ol>
 * <p><b>架构不变量</b>：本门公开判定方法一律<b>不带 {@code @Transactional}</b>（NanoJev 调用是
 * AI 调用；AiCallOutsideTransactionTest 反射断言守护）；统计为纯内存观测零副作用。</p>
 */
@Slf4j
@Component
public class NanoJevDecisionGate {

    /** 判定结果处置码（调试页/统计/调用方判定用）。 */
    public static final String DISP_ADOPTED = "adopted";
    public static final String DISP_ESCALATED = "escalated";
    public static final String DISP_DISABLED = "disabled";
    public static final String DISP_FAILED = "failed";

    /** boolean 判定结果。 */
    public record BooleanOutcome(
            boolean value,
            boolean adopted,
            String disposition,
            Double pTrue,
            Double top1Prob,
            Double entropy,
            long latencyMs) {
        public static BooleanOutcome disabled() {
            return new BooleanOutcome(false, false, DISP_DISABLED, null, null, null, 0);
        }

        public static BooleanOutcome failed(long latencyMs) {
            return new BooleanOutcome(false, false, DISP_FAILED, null, null, null, latencyMs);
        }
    }

    /** choice 判定结果。 */
    public record ChoiceOutcome(
            String chosen,
            boolean adopted,
            String disposition,
            Map<String, Double> distribution,
            Double top1Prob,
            Double entropy,
            long latencyMs) {
        public static ChoiceOutcome disabled() {
            return new ChoiceOutcome(null, false, DISP_DISABLED, null, null, null, 0);
        }

        public static ChoiceOutcome failed(long latencyMs) {
            return new ChoiceOutcome(null, false, DISP_FAILED, null, null, null, latencyMs);
        }
    }

    /** score 判定结果。 */
    public record ScoreOutcome(
            int level,
            double expected,
            boolean adopted,
            String disposition,
            Map<String, Double> distribution,
            Double top1Prob,
            Double entropy,
            long latencyMs) {
        public static ScoreOutcome disabled() {
            return new ScoreOutcome(-1, 0, false, DISP_DISABLED, null, null, null, 0);
        }

        public static ScoreOutcome failed(long latencyMs) {
            return new ScoreOutcome(-1, 0, false, DISP_FAILED, null, null, null, latencyMs);
        }
    }

    /** 判定层总配置（可空=未装配，视为禁用） */
    @Autowired(required = false)
    private NanoJevDecisionProperties properties;

    /** NanoJev 调用门面 */
    @Autowired(required = false)
    private NanoJevEvaluateClient nanoJevClient;

    /** 当前用户提供者（单用户 id=1；trace/用量归属） */
    @Autowired(required = false)
    private CurrentUserProvider currentUserProvider;

    /** 判定统计（调试页数据源；可空=不记） */
    @Autowired(required = false)
    private NanoJevDecisionStats stats;

    /**
     * 统一 AI 上下文注入器（角色视角判定注入世界观简报 + 角色核心卡；null=未装配跳过注入）。
     * <p>2026-10-07 上下文注入根治：此前 NanoJev 判定（space_trigger/relation_delta 等）的
     * state 只含「npc:7 与 npc:6 的关系从 stranger 变为 acquaintance（好感 14→15）」这类
     * 光秃秃的变化文本，无角色名/身份/世界观——AI 无法准确判断。现经本注入器在判定入口
     * 统一注入【世界观】+【我的身份与处境】（涉及该角色必注入），一处覆盖全部角色视角判定。</p>
     * <p>2026-10-08 V2.0 规范化重构批 1：判定输入对齐《AI调用规范化结构_V2.0》段 1+2+3
     * 骨架——{@link AiContextInjector#decisionInput} 把段 1（1A 稳定世界观/1B 系统指令/
     * 1C 角色核心卡）+ 段 2（2D 当前世界动态/2H 本轮输入）+ 段 3（3I 行为护栏/3J 输出契约）
     * 全量拼接为一个判定输入串（V2.0 §3.4）。</p>
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    // ==================== 开关 ====================

    /** 三重门控：判定层总开关 && 场景开关 && NanoJev client 可用。 */
    public boolean isEnabled(String scene) {
        if (properties == null || !properties.isEnabled()) {
            return false;
        }
        if (nanoJevClient == null || !nanoJevClient.isEnabled()) {
            return false;
        }
        return sceneConfig(scene).isEnabled();
    }

    /** 场景配置（未配置场景回退代码默认=关；null 场景回退默认）。 */
    public NanoJevDecisionProperties.SceneConfig sceneConfig(String scene) {
        if (properties == null || properties.getScenes() == null || scene == null) {
            return new NanoJevDecisionProperties.SceneConfig();
        }
        return properties.getScenes().getOrDefault(scene, new NanoJevDecisionProperties.SceneConfig());
    }

    /** 采样模式（场景配置；sample=按分布加权随机默认）。 */
    public String samplingMode(String scene) {
        String mode = sceneConfig(scene).getSampling();
        return mode == null || mode.isBlank() ? "sample" : mode;
    }

    // ==================== 判定（boolean / choice / score） ====================

    /**
     * boolean 判定（含升级策略与采样）。
     *
     * @param projectId 项目 ID（可空）
     *
     * @param characterId 角色 ID（可空）
     *
     * @param scene 场景码
     *
     * @param state 单 state（含 boolean 问题）
     *
     * @param qid 问题键
     *
     * @return 判定结果（adopted=false=升级/未启用/失败，调用方回退 Qwen/程序化）
     */
    public BooleanOutcome booleanDecision(Long projectId, Long characterId, String scene,
                                          NanoJevState state, String qid) {
        if (!isEnabled(scene)) {
            statsIfAny(scene, "boolean", null, null, null, false, DISP_DISABLED, null, 0);
            return BooleanOutcome.disabled();
        }
        long t0 = System.currentTimeMillis();
        NanoJevEvaluateResponse resp = call(projectId, characterId, scene, state);
        long latency = System.currentTimeMillis() - t0;
        if (resp == null) {
            statsIfAny(scene, "boolean", null, null, null, false, DISP_FAILED, null, latency);
            return BooleanOutcome.failed(latency);
        }
        NanoJevAnswer answer = resp.answerOf(state.id(), qid);
        if (answer == null || answer.probabilities() == null || answer.probabilities().isEmpty()) {
            statsIfAny(scene, "boolean", null, null, null, false, DISP_FAILED, null, latency);
            return BooleanOutcome.failed(latency);
        }
        Double pTrue = answer.pTrue();
        if (pTrue == null) {
            pTrue = answer.probabilities().getOrDefault("true", 0.5);
        }
        double top1 = Math.max(pTrue, 1 - pTrue);
        double entropy = NanoJevSampler.entropy(answer.probabilities());
        String escalateReason = escalationReason(scene, top1, entropy);
        if (escalateReason != null) {
            statsIfAny(scene, "boolean", answer.probabilities(), top1, entropy, false, DISP_ESCALATED, null, latency);
            log.info("[NanoJev判定] {} 升级 Qwen：top1Prob={} 熵={} 原因={}", scene, round3(top1), round3(entropy),
                    escalateReason);
            return new BooleanOutcome(false, false, DISP_ESCALATED, pTrue, top1, entropy, latency);
        }
        boolean value = NanoJevSampler.decide(pTrue, samplingMode(scene), null);
        statsIfAny(scene, "boolean", answer.probabilities(), top1, entropy, true, DISP_ADOPTED, value, latency);
        log.info("[NanoJev判定] {} 采纳：value={} pTrue={} top1Prob={} 熵={} latency={}ms", scene, value,
                round3(pTrue), round3(top1), round3(entropy), latency);
        return new BooleanOutcome(value, true, DISP_ADOPTED, pTrue, top1, entropy, latency);
    }

    /**
     * choice 判定（含升级策略与采样）。
     *
     * @return 判定结果（chosen=null 且 adopted=false=升级/未启用/失败）
     */
    public ChoiceOutcome choiceDecision(Long projectId, Long characterId, String scene,
                                        NanoJevState state, String qid) {
        if (!isEnabled(scene)) {
            statsIfAny(scene, "choice", null, null, null, false, DISP_DISABLED, null, 0);
            return ChoiceOutcome.disabled();
        }
        long t0 = System.currentTimeMillis();
        NanoJevEvaluateResponse resp = call(projectId, characterId, scene, state);
        long latency = System.currentTimeMillis() - t0;
        if (resp == null) {
            statsIfAny(scene, "choice", null, null, null, false, DISP_FAILED, null, latency);
            return ChoiceOutcome.failed(latency);
        }
        NanoJevAnswer answer = resp.answerOf(state.id(), qid);
        if (answer == null || answer.probabilities() == null || answer.probabilities().isEmpty()) {
            statsIfAny(scene, "choice", null, null, null, false, DISP_FAILED, null, latency);
            return ChoiceOutcome.failed(latency);
        }
        Map<String, Double> dist = answer.probabilities();
        double top1 = top1Of(dist);
        double entropy = NanoJevSampler.entropy(dist);
        String escalateReason = escalationReason(scene, top1, entropy);
        if (escalateReason != null) {
            statsIfAny(scene, "choice", dist, top1, entropy, false, DISP_ESCALATED, null, latency);
            log.info("[NanoJev判定] {} 升级 Qwen：top1Prob={} 熵={} 原因={}", scene, round3(top1), round3(entropy),
                    escalateReason);
            return new ChoiceOutcome(null, false, DISP_ESCALATED, dist, top1, entropy, latency);
        }
        String chosen = NanoJevSampler.choose(dist, samplingMode(scene), null);
        statsIfAny(scene, "choice", dist, top1, entropy, true, DISP_ADOPTED, chosen, latency);
        log.info("[NanoJev判定] {} 采纳：chosen={} top1Prob={} 熵={} latency={}ms", scene, chosen,
                round3(top1), round3(entropy), latency);
        return new ChoiceOutcome(chosen, true, DISP_ADOPTED, dist, top1, entropy, latency);
    }

    /**
     * score 判定（含升级策略与采样）。
     *
     * @return 判定结果（level=-1 且 adopted=false=升级/未启用/失败）
     */
    public ScoreOutcome scoreDecision(Long projectId, Long characterId, String scene,
                                      NanoJevState state, String qid) {
        if (!isEnabled(scene)) {
            statsIfAny(scene, "score", null, null, null, false, DISP_DISABLED, null, 0);
            return ScoreOutcome.disabled();
        }
        long t0 = System.currentTimeMillis();
        NanoJevEvaluateResponse resp = call(projectId, characterId, scene, state);
        long latency = System.currentTimeMillis() - t0;
        if (resp == null) {
            statsIfAny(scene, "score", null, null, null, false, DISP_FAILED, null, latency);
            return ScoreOutcome.failed(latency);
        }
        NanoJevAnswer answer = resp.answerOf(state.id(), qid);
        if (answer == null || answer.probabilities() == null || answer.probabilities().isEmpty()) {
            statsIfAny(scene, "score", null, null, null, false, DISP_FAILED, null, latency);
            return ScoreOutcome.failed(latency);
        }
        Map<String, Double> dist = answer.probabilities();
        double top1 = top1Of(dist);
        double entropy = NanoJevSampler.entropy(dist);
        String escalateReason = escalationReason(scene, top1, entropy);
        if (escalateReason != null) {
            statsIfAny(scene, "score", dist, top1, entropy, false, DISP_ESCALATED, null, latency);
            log.info("[NanoJev判定] {} 升级 Qwen：top1Prob={} 熵={} 原因={}", scene, round3(top1), round3(entropy),
                    escalateReason);
            return new ScoreOutcome(-1, 0, false, DISP_ESCALATED, dist, top1, entropy, latency);
        }
        int level = NanoJevSampler.level(dist, samplingMode(scene), null);
        double expected = expectedScore(dist);
        statsIfAny(scene, "score", dist, top1, entropy, true, DISP_ADOPTED, String.valueOf(level), latency);
        log.info("[NanoJev判定] {} 采纳：level={} expected={} top1Prob={} 熵={} latency={}ms", scene, level,
                round3(expected), round3(top1), round3(entropy), latency);
        return new ScoreOutcome(level, expected, true, DISP_ADOPTED, dist, top1, entropy, latency);
    }

    /**
     * 批量 boolean 判定（多角色一次前向；states ≤32 官方上限）。
     * <p>群聊/自主对话每轮批量判定在场成员「此刻是否愿意接话」用——一次请求出全部结果
     * 。未启用 → 全部返回 disabled
     * （调用方按愿意处理=现状）；逐 state 升级/失败语义同单测版。</p>
     *
     * @param projectId 项目 ID（可空）
     *
     * @param scene 场景码
     *
     * @param states state 表（键=角色引用/任意调用方键，值=含 boolean 问题的 state；空=空结果）
     *
     * @param qid 问题键（所有 state 共用）
     *
     * @return 键 → 判定结果（键序保持插入序；失败 state 返回 failed）
     */
    public Map<String, BooleanOutcome> booleanDecisions(Long projectId, String scene,
                                                        Map<String, NanoJevState> states, String qid) {
        Map<String, BooleanOutcome> out = new java.util.LinkedHashMap<>();
        if (states == null || states.isEmpty()) {
            return out;
        }
        if (!isEnabled(scene)) {
            for (String k : states.keySet()) {
                out.put(k, BooleanOutcome.disabled());
            }
            return out;
        }
        long t0 = System.currentTimeMillis();
        java.util.List<NanoJevEvaluateResponse> respList;
        try {
            Long uid = currentUserProvider == null ? null : currentUserProvider.currentUserId();
            respList = nanoJevClient.evaluate(uid, projectId, null, scene,
                    new java.util.ArrayList<>(enrichBatch(projectId, states).values()));
        } catch (Exception e) {
            log.warn("[NanoJev判定] {} 批量调用异常（fail-closed 回退）：{}", scene, e.getMessage());
            respList = null;
        }
        // 单次请求（states ≤32 不拆分）→ 首个响应即全部结果
        NanoJevEvaluateResponse resp = respList == null || respList.isEmpty() ? null : respList.get(0);
        long latency = System.currentTimeMillis() - t0;
        for (Map.Entry<String, NanoJevState> en : states.entrySet()) {
            String k = en.getKey();
            NanoJevState st = en.getValue();
            if (resp == null) {
                statsIfAny(scene, "boolean", null, null, null, false, DISP_FAILED, null, latency);
                out.put(k, BooleanOutcome.failed(latency));
                continue;
            }
            NanoJevAnswer answer = resp.answerOf(st.id(), qid);
            if (answer == null || answer.probabilities() == null || answer.probabilities().isEmpty()) {
                statsIfAny(scene, "boolean", null, null, null, false, DISP_FAILED, null, latency);
                out.put(k, BooleanOutcome.failed(latency));
                continue;
            }
            Double pTrue = answer.pTrue();
            if (pTrue == null) {
                pTrue = answer.probabilities().getOrDefault("true", 0.5);
            }
            double top1 = Math.max(pTrue, 1 - pTrue);
            double entropy = NanoJevSampler.entropy(answer.probabilities());
            String escalateReason = escalationReason(scene, top1, entropy);
            if (escalateReason != null) {
                statsIfAny(scene, "boolean", answer.probabilities(), top1, entropy, false, DISP_ESCALATED,
                        null, latency);
                out.put(k, new BooleanOutcome(false, false, DISP_ESCALATED, pTrue, top1, entropy, latency));
                continue;
            }
            boolean value = NanoJevSampler.decide(pTrue, samplingMode(scene), null);
            statsIfAny(scene, "boolean", answer.probabilities(), top1, entropy, true, DISP_ADOPTED, value, latency);
            out.put(k, new BooleanOutcome(value, true, DISP_ADOPTED, pTrue, top1, entropy, latency));
        }
        log.info("[NanoJev判定] {} 批量：states={} latency={}ms", scene, states.size(), latency);
        return out;
    }

    /**
     * 单 state 多题混合判定（<b>一次前向</b>）：boolean 批量 + choice/score 同请求。
     * <p>空间候选选择 用：1 个 state（角色此刻情境）= N 个 precondition
     * boolean + 1 个行动 choice，一次 evaluate 请求全部出结果（候选 ≤8 → 题数 ≤9，远低于
     * 96 上限； 延迟实测 p50 542ms，批量后单次空间选择 ≈ 一次往返）。</p>
     *
     * @param projectId 项目 ID（可空）
     *
     * @param characterId 角色 ID（可空）
     *
     * @param scene 场景码
     *
     * @param state 单 state（含全部问题）
     *
     * @param qidKinds qid → 题型（boolean/choice/score；保持插入序，与 state 内问题一致）
     *
     * @return qid → 判定结果（BooleanOutcome/ChoiceOutcome/ScoreOutcome 按题型分派；
     * 未启用 → 全部 disabled；单题失败 → 该题 failed，其余照常）
     */
    public Map<String, Object> mixedDecisions(Long projectId, Long characterId, String scene,
                                              NanoJevState state, Map<String, String> qidKinds) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (state == null || qidKinds == null || qidKinds.isEmpty()) {
            return out;
        }
        if (!isEnabled(scene)) {
            for (String qid : qidKinds.keySet()) {
                out.put(qid, disabledOutcome(qidKinds.get(qid)));
            }
            return out;
        }
        long t0 = System.currentTimeMillis();
        NanoJevEvaluateResponse resp = call(projectId, characterId, scene, state);
        long latency = System.currentTimeMillis() - t0;
        for (Map.Entry<String, String> en : qidKinds.entrySet()) {
            String qid = en.getKey();
            String kind = en.getValue();
            if (resp == null) {
                out.put(qid, failedOutcome(kind, latency));
                statsIfAny(scene, kind, null, null, null, false, DISP_FAILED, null, latency);
                continue;
            }
            NanoJevAnswer answer = resp.answerOf(state.id(), qid);
            if (answer == null || answer.probabilities() == null || answer.probabilities().isEmpty()) {
                out.put(qid, failedOutcome(kind, latency));
                statsIfAny(scene, kind, null, null, null, false, DISP_FAILED, null, latency);
                continue;
            }
            out.put(qid, resolveMixed(scene, kind, answer, latency));
        }
        log.info("[NanoJev判定] {} 混合批量：qids={} latency={}ms", scene, qidKinds.size(), latency);
        return out;
    }

    /**
     * 多 state × 共享 qid 批量混合判定（<b>一次前向</b>）：每个 state 带同一组问题
     * （qid → 题型），一次 evaluate 请求出全部结果。
     * <p>每 tick 全 NPC 高频微决策 用：全项目 NPC 各一个 state（此刻情境），
     * 每题共用 qid（如注意力状态 score + 即时意向 choice）——20 NPC × 2 题 = 40 问题 ≤ 96
     * 上限、states ≤ 32，单次往返亚秒级；：注意力与即时意向同批评估（每 2 tick
     * 默认），异步虚拟线程执行不进 tick 屏障（真实心智活动本就有延迟）。</p>
     *
     * @param projectId 项目 ID（可空）
     *
     * @param scene 场景码（attention / micro_decision 等）
     *
     * @param states state 表（键=调用方键[角色引用等]，值=含共享问题集的 state；空=空结果）
     *
     * @param qidKinds qid → 题型（boolean/choice/score；所有 state 共用，保持插入序）
     *
     * @return 键 → (qid → 判定结果)；未启用 → 全部 disabled；单题失败 → 该题 failed 其余照常
     */
    public Map<String, Map<String, Object>> mixedBatchDecisions(Long projectId, String scene,
                                                                Map<String, NanoJevState> states,
                                                                Map<String, String> qidKinds) {
        Map<String, Map<String, Object>> out = new java.util.LinkedHashMap<>();
        if (states == null || states.isEmpty() || qidKinds == null || qidKinds.isEmpty()) {
            return out;
        }
        if (!isEnabled(scene)) {
            for (String key : states.keySet()) {
                out.put(key, allDisabled(qidKinds));
            }
            return out;
        }
        long t0 = System.currentTimeMillis();
        java.util.List<NanoJevEvaluateResponse> respList;
        try {
            Long uid = currentUserProvider == null ? null : currentUserProvider.currentUserId();
            respList = nanoJevClient.evaluate(uid, projectId, null, scene,
                    new java.util.ArrayList<>(enrichBatch(projectId, states).values()));
        } catch (Exception e) {
            log.warn("[NanoJev判定] {} 批量混合调用异常（fail-closed 回退）：{}", scene, e.getMessage());
            respList = null;
        }
        // 首响应即全部结果（states ≤32 不拆分；超限由客户端拆分器处理，这里取首个响应按 state id 对齐）
        NanoJevEvaluateResponse resp = respList == null || respList.isEmpty() ? null : respList.get(0);
        long latency = System.currentTimeMillis() - t0;
        for (Map.Entry<String, NanoJevState> en : states.entrySet()) {
            String key = en.getKey();
            NanoJevState st = en.getValue();
            Map<String, Object> perQid = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, String> qen : qidKinds.entrySet()) {
                String qid = qen.getKey();
                String kind = qen.getValue();
                if (resp == null) {
                    perQid.put(qid, failedOutcome(kind, latency));
                    statsIfAny(scene, kind, null, null, null, false, DISP_FAILED, null, latency);
                    continue;
                }
                NanoJevAnswer answer = resp.answerOf(st.id(), qid);
                if (answer == null || answer.probabilities() == null || answer.probabilities().isEmpty()) {
                    perQid.put(qid, failedOutcome(kind, latency));
                    statsIfAny(scene, kind, null, null, null, false, DISP_FAILED, null, latency);
                    continue;
                }
                perQid.put(qid, resolveMixed(scene, kind, answer, latency));
            }
            out.put(key, perQid);
        }
        log.info("[NanoJev判定] {} 批量混合：states={} qids={} latency={}ms", scene, states.size(),
                qidKinds.size(), latency);
        return out;
    }

    /**
     * 批量判定上下文注入：逐 state 按「键=角色引用（{@code npc:{id}}）」注入
     * 【世界观】+【我的身份与处境】。
     * <p>批量场景的调用方约定键为角色引用（CognitiveAttentionService/AccidentJudge/
     * SocialWillingnessGate 等均以 npcRef 为键）；键无法解析为 npcRef 时原样返回
     * （防误注入无关上下文）。未装配/异常 → 原样（零阻断）。</p>
     *
     * @param projectId 项目 ID（可空；世界简报/角色卡需要）
     *
     * @param states 键 → state（键=角色引用）
     *
     * @return 富化后的 state 表（逐项，保持插入序）
     */
    private Map<String, NanoJevState> enrichBatch(Long projectId, Map<String, NanoJevState> states) {
        if (aiContextInjector == null || states == null || states.isEmpty()) {
            return states;
        }
        Map<String, NanoJevState> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, NanoJevState> en : states.entrySet()) {
            String key = en.getKey();
            NanoJevState st = en.getValue();
            Long characterId = com.holzyn.actor.common.NpcRefs.idOf(key);
            out.put(key, characterId == null ? st : enrichState(projectId, characterId, st));
        }
        return out;
    }

    /** 批量未启用：每个 qid 一个 disabled 结果。 */
    private static Map<String, Object> allDisabled(Map<String, String> qidKinds) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> en : qidKinds.entrySet()) {
            m.put(en.getKey(), disabledOutcome(en.getValue()));
        }
        return m;
    }

    /** 混合批量未启用处置。 */
    private static Object disabledOutcome(String kind) {
        return switch (kind) {
            case "boolean" -> BooleanOutcome.disabled();
            case "choice" -> ChoiceOutcome.disabled();
            case "score" -> ScoreOutcome.disabled();
            default -> BooleanOutcome.disabled();
        };
    }

    /** 混合批量单题失败处置。 */
    private static Object failedOutcome(String kind, long latency) {
        return switch (kind) {
            case "boolean" -> BooleanOutcome.failed(latency);
            case "choice" -> ChoiceOutcome.failed(latency);
            case "score" -> ScoreOutcome.failed(latency);
            default -> BooleanOutcome.failed(latency);
        };
    }

    /** 混合批量单题判定（升级/采样口径与单题版完全一致）。 */
    private Object resolveMixed(String scene, String kind, NanoJevAnswer answer, long latency) {
        Map<String, Double> dist = answer.probabilities();
        double top1 = top1Of(dist);
        double entropy = NanoJevSampler.entropy(dist);
        String escalateReason = escalationReason(scene, top1, entropy);
        if (escalateReason != null) {
            statsIfAny(scene, kind, dist, top1, entropy, false, DISP_ESCALATED, null, latency);
            log.info("[NanoJev判定] {} 升级 Qwen：top1Prob={} 熵={} 原因={}", scene, round3(top1),
                    round3(entropy), escalateReason);
            return switch (kind) {
                case "boolean" -> new BooleanOutcome(false, false, DISP_ESCALATED, answer.pTrue(), top1, entropy, latency);
                case "choice" -> new ChoiceOutcome(null, false, DISP_ESCALATED, dist, top1, entropy, latency);
                case "score" -> new ScoreOutcome(-1, 0, false, DISP_ESCALATED, dist, top1, entropy, latency);
                default -> BooleanOutcome.disabled();
            };
        }
        return switch (kind) {
            case "boolean" -> {
                Double pTrue = answer.pTrue();
                if (pTrue == null) {
                    pTrue = dist.getOrDefault("true", 0.5);
                }
                boolean value = NanoJevSampler.decide(pTrue, samplingMode(scene), null);
                statsIfAny(scene, "boolean", dist, top1, entropy, true, DISP_ADOPTED, value, latency);
                yield new BooleanOutcome(value, true, DISP_ADOPTED, pTrue, top1, entropy, latency);
            }
            case "choice" -> {
                String chosen = NanoJevSampler.choose(dist, samplingMode(scene), null);
                statsIfAny(scene, "choice", dist, top1, entropy, true, DISP_ADOPTED, chosen, latency);
                yield new ChoiceOutcome(chosen, true, DISP_ADOPTED, dist, top1, entropy, latency);
            }
            case "score" -> {
                int level = NanoJevSampler.level(dist, samplingMode(scene), null);
                statsIfAny(scene, "score", dist, top1, entropy, true, DISP_ADOPTED, String.valueOf(level), latency);
                yield new ScoreOutcome(level, expectedScore(dist), true, DISP_ADOPTED, dist, top1, entropy, latency);
            }
            default -> BooleanOutcome.disabled();
        };
    }

    // ==================== 内部 ====================

    /** 调用门面（fail-closed：任何失败返回 null）。 */
    private NanoJevEvaluateResponse call(Long projectId, Long characterId, String scene, NanoJevState state) {
        try {
            Long uid = currentUserProvider == null ? null : currentUserProvider.currentUserId();
            NanoJevState enriched = enrichState(projectId, characterId, state);
            return nanoJevClient.evaluateOnce(uid, projectId, characterId, scene, List.of(enriched));
        } catch (Exception e) {
            log.warn("[NanoJev判定] {} 调用异常（fail-closed 回退）：{}", scene, e.getMessage());
            return null;
        }
    }

    /**
     * 角色视角判定上下文注入：把 V2.0 判定输入装配（段 1+段 2+段 3 单串）拼到 state 文本。
     * <p>只处理纯文本 state（{@code NanoJevState.text} 构造）；JSON 对象 state 不改造
     * （保持契约）。characterId 可空（如无角色归属的纯规则判定）→ 仅注入世界观与纪律段。
     * 注入器未装配 / 注入异常 → 原样返回（零阻断，保既有行为）。</p>
     *
     * @param projectId 项目 ID（可空）
     *
     * @param characterId 角色 ID（可空；非空时按 npc:{id} 注入该角色核心卡）
     *
     * @param state 原始 state（非空）
     *
     * @return 富化后的 state（未装配/非文本/失败时原样返回）
     */
    private NanoJevState enrichState(Long projectId, Long characterId, NanoJevState state) {
        if (aiContextInjector == null || state == null
                || !(state.state() instanceof String text) || text.isBlank()) {
            return state;
        }
        try {
            String ref = characterId == null || characterId <= 0 ? null : "npc:" + characterId;
            String enriched = aiContextInjector.decisionInput(projectId, ref, text);
            if (enriched == null || enriched.equals(text)) {
                return state;
            }
            return new NanoJevState(state.id(), enriched, state.questions());
        } catch (Exception e) {
            log.debug("[NanoJev判定] 上下文注入失败（原样返回，零阻断）: scene={} : {}", state.id(), e.getMessage());
            return state;
        }
    }

    /** 升级判定（未启用升级/未标定阈值 → null=采纳）。返回升级原因文本。 */
    private String escalationReason(String scene, double top1Prob, double entropy) {
        NanoJevDecisionProperties.Escalation esc = sceneConfig(scene).getEscalation();
        if (esc == null || !esc.isEnabled()) {
            return null;
        }
        Double top1Limit = esc.getTop1Prob();
        Double entropyLimit = esc.getEntropy();
        if (top1Limit != null && top1Prob < top1Limit) {
            return "top1_prob_low";
        }
        if (entropyLimit != null && entropy > entropyLimit) {
            return "entropy_high";
        }
        return null;
    }

    /** 分布 top-1 概率。 */
    private static double top1Of(Map<String, Double> dist) {
        double max = 0;
        for (Double p : dist.values()) {
            max = Math.max(max, p == null ? 0 : p);
        }
        return max;
    }

    /** score 期望值（档位 × 概率）。 */
    private static double expectedScore(Map<String, Double> dist) {
        double exp = 0;
        for (Map.Entry<String, Double> e : dist.entrySet()) {
            try {
                exp += Integer.parseInt(e.getKey()) * (e.getValue() == null ? 0 : e.getValue());
            } catch (NumberFormatException ignored) {
                // 非数字键忽略
            }
        }
        return exp;
    }

    /** 统计记录（零副作用；stats 未装配跳过）。 */
    private void statsIfAny(String scene, String type, Map<String, Double> dist, Double top1, Double entropy,
                            boolean adopted, String disposition, Object chosen, long latency) {
        try {
            if (stats != null) {
                stats.record(new NanoJevDecisionStats.Record(
                        LocalDateTime.now(), scene, null, type, dist, top1, entropy, adopted,
                        disposition, chosen == null ? null : String.valueOf(chosen), latency));
            }
        } catch (Exception e) {
            log.debug("[NanoJev判定] 统计记录失败（忽略）: {}", e.getMessage());
        }
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
