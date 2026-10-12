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

package com.holzyn.actor.domain.growth.space;

import com.holzyn.actor.ai.nanojev.NanoJevQuestion;
import com.holzyn.actor.ai.nanojev.NanoJevSampler;
import com.holzyn.actor.ai.nanojev.NanoJevState;
import com.holzyn.actor.ai.nanojev.decision.NanoJevDecisionGate;
import com.holzyn.actor.ai.nanojev.decision.NanoJevDecisionScenes;
import com.holzyn.actor.ai.nanojev.decision.NanoJevDecisionTemplates;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 空间候选选择 · 判定编排工具。
 * <p><b>职责</b>：把候选集 + precondition 上下文 + 情境文本编排为一次 NanoJev 混合判定
 * （1 state = N 个语义条件 boolean + 1 个行动 choice，<b>一次前向</b>）并解析选中候选：</p>
 * <ol>
 * <li><b>precondition 程序化过滤</b>（{@link SpacePreconditionEvaluator}）：可程序化判定的
 * 条件直接过滤（不满足出局）；语义性条件归 UNKNOWN → 进 boolean 批量；</li>
 * <li><b>问题组装</b>：每个 UNKNOWN 条件一个 boolean 题（{@link NanoJevDecisionTemplates#spacePreconditionQuestion}）
 * + 一个 choice 题（{@link NanoJevDecisionTemplates#spaceActionQuestion}，候选=过滤后剩余集）——
 * 同一 state 一次请求（候选 ≤8 → 题数 ≤9，远低于 96 上限）；</li>
 * <li><b>选中复核</b>：choice 选中项的语义条件 boolean=false → 从可接受集
 * （程序化满足 + 语义条件判定为真）按 choice 分布<b>归一化重采样</b>——模型判据与
 * 程序化事实冲突时以程序化为准（候选描述本身已要求「只选此刻真正可能做的」）；</li>
 * <li><b>失败回退</b>：判定不可用/未启用/可接受集为空 → null（调用方回退 Qwen 现状/程序化，
 * 零阻断）。</li>
 * </ol>
 * <p>纯静态可单测（gate 用 mock）。</p>
 */
public final class SpaceDecisionServiceUtils {

    private SpaceDecisionServiceUtils() {
    }

    /** 行动 choice 问题键。 */
    public static final String ACTION_QID = "action";

    /** 语义条件 boolean 问题键前缀（pc0/pc1/…）。 */
    public static final String PRECOND_QID_PREFIX = "pc";

    /**
     * 空间候选判定编排（一次前向）：precondition 程序化过滤 → boolean 批量 + choice →
     * 选中复核/重采样 → 返回选中候选。
     *
     * @param gate NanoJev 判定门（null → null）
     *
     * @param projectId 项目 ID
     *
     * @param characterId 角色 ID（state id 用）
     *
     * @param candidates 候选集（≥2 项；来自 {@link SpaceDecisionCandidates#candidatesFrom}）
     *
     * @param pctx precondition 程序化判定上下文
     *
     * @param stateText 角色此刻情境文本（含时刻/状态/在场者/天气/地点/内心/事件，判据自包含）
     *
     * @return 选中候选（null=判定不可用/未启用/失败/可接受集为空 → 调用方回退）
     */
    public static SpaceDecisionCandidates.SpaceCandidate decide(
            NanoJevDecisionGate gate, Long projectId, Long characterId,
            List<SpaceDecisionCandidates.SpaceCandidate> candidates,
            SpacePreconditionEvaluator.Context pctx, String stateText) {
        if (gate == null || candidates == null || candidates.size() < 2) {
            return null;
        }
        if (!gate.isEnabled(NanoJevDecisionScenes.SPACE_DECISION)) {
            return null;
        }
        try {
            // ① precondition 程序化过滤（NOT_SATISFIED 出局；UNKNOWN 进 boolean 批量）
            List<SpaceDecisionCandidates.SpaceCandidate> kept = new ArrayList<>();
            Map<String, SpaceDecisionCandidates.SpaceCandidate> unknownPc = new LinkedHashMap<>();
            for (SpaceDecisionCandidates.SpaceCandidate c : candidates) {
                if (c.precondition() == null || c.precondition().isBlank()) {
                    kept.add(c);
                    continue;
                }
                SpacePreconditionEvaluator.Verdict v =
                        SpacePreconditionEvaluator.evaluate(c.precondition(), pctx);
                if (v == SpacePreconditionEvaluator.Verdict.SATISFIED) {
                    kept.add(c);
                } else if (v == SpacePreconditionEvaluator.Verdict.UNKNOWN) {
                    unknownPc.put(c.key(), c);
                }
                // NOT_SATISFIED → 候选出局
            }
            kept.addAll(unknownPc.values());
            if (kept.size() < 2) {
                return null;
            }
            // ② 问题组装（语义条件 boolean 批量 + 行动 choice，同一 state 一次前向）
            Map<String, NanoJevQuestion> questions = NanoJevState.newQuestions();
            Map<String, String> qidKinds = new LinkedHashMap<>();
            Map<String, String> qidToKey = new LinkedHashMap<>();
            int i = 0;
            for (SpaceDecisionCandidates.SpaceCandidate c : unknownPc.values()) {
                String qid = PRECOND_QID_PREFIX + (i++);
                questions.put(qid, NanoJevDecisionTemplates.spacePreconditionQuestion(c.precondition()));
                qidKinds.put(qid, NanoJevQuestion.TYPE_BOOLEAN);
                qidToKey.put(qid, c.key());
            }
            Map<String, String> criteria = new LinkedHashMap<>();
            Map<String, SpaceDecisionCandidates.SpaceCandidate> byKey = new LinkedHashMap<>();
            for (SpaceDecisionCandidates.SpaceCandidate c : kept) {
                criteria.put(c.key(), c.questionText());
                byKey.put(c.key(), c);
            }
            questions.put(ACTION_QID, NanoJevDecisionTemplates.spaceActionQuestion(criteria));
            qidKinds.put(ACTION_QID, NanoJevQuestion.TYPE_CHOICE);
            NanoJevState state = NanoJevState.text("space" + characterId, stateText, questions);
            Map<String, Object> results = gate.mixedDecisions(projectId, characterId,
                    NanoJevDecisionScenes.SPACE_DECISION, state, qidKinds);
            if (results == null || results.isEmpty()) {
                return null;
            }
            // ③ 可接受集（程序化满足项 + 语义条件判定为真项）
            List<String> acceptedKeys = new ArrayList<>();
            for (SpaceDecisionCandidates.SpaceCandidate c : kept) {
                if (!unknownPc.containsKey(c.key())) {
                    acceptedKeys.add(c.key());
                    continue;
                }
                String qid = findQid(qidToKey, c.key());
                Object out = qid == null ? null : results.get(qid);
                if (out instanceof NanoJevDecisionGate.BooleanOutcome bo && bo.adopted() && bo.value()) {
                    acceptedKeys.add(c.key());
                }
            }
            if (acceptedKeys.isEmpty()) {
                return null;
            }
            NanoJevDecisionGate.ChoiceOutcome choice =
                    (NanoJevDecisionGate.ChoiceOutcome) results.get(ACTION_QID);
            if (choice == null || !choice.adopted() || choice.chosen() == null) {
                return null;
            }
            SpaceDecisionCandidates.SpaceCandidate chosen = byKey.get(choice.chosen());
            if (chosen == null) {
                return null;
            }
            if (!acceptedKeys.contains(chosen.key())) {
                // ④ 选中项语义条件不满足 → 从可接受集按 choice 分布归一化重采样
                SpaceDecisionCandidates.SpaceCandidate re = reSampleAccepted(
                        gate, choice, acceptedKeys, byKey);
                if (re == null) {
                    return null;
                }
                return re;
            }
            return chosen;
        } catch (Exception e) {
            return null;
        }
    }

    /** 按 choice 分布 ∩ 可接受集归一化重采样（分布缺失 → 均匀随机）。 */
    private static SpaceDecisionCandidates.SpaceCandidate reSampleAccepted(
            NanoJevDecisionGate gate, NanoJevDecisionGate.ChoiceOutcome choice,
            List<String> acceptedKeys,
            Map<String, SpaceDecisionCandidates.SpaceCandidate> byKey) {
        Map<String, Double> dist = new LinkedHashMap<>();
        double sum = 0;
        Map<String, Double> raw = choice.distribution();
        if (raw != null) {
            for (String k : acceptedKeys) {
                Double p = raw.get(k);
                if (p != null && p > 0) {
                    dist.put(k, p);
                    sum += p;
                }
            }
        }
        if (dist.isEmpty()) {
            return byKey.get(acceptedKeys.get(new Random().nextInt(acceptedKeys.size())));
        }
        final double s = sum;
        Map<String, Double> norm = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : dist.entrySet()) {
            norm.put(e.getKey(), e.getValue() / s);
        }
        String re = NanoJevSampler.choose(norm, gate.samplingMode(NanoJevDecisionScenes.SPACE_DECISION), null);
        return re == null ? null : byKey.get(re);
    }

    /** qid → 候选键反查。 */
    private static String findQid(Map<String, String> qidToKey, String key) {
        for (Map.Entry<String, String> e : qidToKey.entrySet()) {
            if (key.equals(e.getValue())) {
                return e.getKey();
            }
        }
        return null;
    }
}
