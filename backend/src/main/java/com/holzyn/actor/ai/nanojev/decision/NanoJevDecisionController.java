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

import com.holzyn.actor.ai.nanojev.NanoJevEvaluateClient;
import com.holzyn.actor.domain.evaluation.TestRunService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * N 轨道 「判定类落地」调试接口（，ai/nanojev/decision）。
 * <p>职责：</p>
 * <ul>
 * <li><b>GET /status（只读常开）</b>：判定层总开关 + 逐场景配置（enabled/采样模式/升级阈值）
 * + NanoJev client 状态（启用/base-url）——逐场景开关的可观测面板；</li>
 * <li><b>GET /stats（只读常开）</b>：判定统计（场景聚合：次数/采纳/升级/失败/平均延迟 +
 * 最近判定记录：分布/top1Prob/熵/处置/耗时）——对齐 N 轨道设计 §9「调试页指标」；</li>
 * <li><b>POST /override（测试操作，{@code holzyn.actor.evaluation.test-enabled=true} 才可用，
 * 默认 403）</b>：运行时场景开关覆盖（热更新，重启恢复 yml）——冒烟/对照实验用。</li>
 * </ul>
 * <p>安全约定：本地单用户、无鉴权（纯本地调试工具，勿公网暴露）；写操作一律走
 * {@link TestRunService#requireTestEnabled()} 门控（与既有测试接口同口径）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/debug/nanojev/decision")
@RequiredArgsConstructor
public class NanoJevDecisionController {

    private final NanoJevDecisionProperties properties;
    private final NanoJevDecisionStats stats;
    private final NanoJevDecisionGate gate;
    private final NanoJevEvaluateClient nanoJevClient;
    private final TestRunService testRunService;

    /** 只读：判定层配置 + 逐场景开关 + NanoJev client 状态（常开）。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decisionEnabled", properties.isEnabled());
        out.put("nanojevEnabled", nanoJevClient == null ? false : nanoJevClient.isEnabled());
        out.put("nanojevBaseUrl", nanoJevClient == null ? "" : nanoJevClient.baseUrl());
        List<Map<String, Object>> scenes = new ArrayList<>();
        for (String code : NanoJevDecisionScenes.ALL) {
            NanoJevDecisionProperties.SceneConfig cfg = gate.sceneConfig(code);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scene", code);
            m.put("enabled", cfg.isEnabled());
            m.put("effective", gate.isEnabled(code));
            m.put("sampling", gate.samplingMode(code));
            NanoJevDecisionProperties.Escalation esc = cfg.getEscalation();
            if (esc == null) {
                m.put("escalation", new LinkedHashMap<>());
            } else {
                // Map.of 拒绝 null 值——阈值未配置时显式给 null（前端显示 -）
                Map<String, Object> escView = new LinkedHashMap<>();
                escView.put("enabled", esc.isEnabled());
                escView.put("top1Prob", esc.getTop1Prob());
                escView.put("entropy", esc.getEntropy());
                m.put("escalation", escView);
            }
            scenes.add(m);
        }
        out.put("scenes", scenes);
        return out;
    }

    /** 只读：判定统计（场景聚合 + 最近判定记录；常开）。 */
    @GetMapping("/stats")
    public Map<String, Object> stats(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int max) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sceneStats", stats.sceneStats());
        List<Map<String, Object>> recent = new ArrayList<>();
        for (NanoJevDecisionStats.Record r : stats.recent(Math.max(1, Math.min(500, max)))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", String.valueOf(r.at()));
            m.put("scene", r.scene());
            m.put("npcRef", r.npcRef());
            m.put("questionType", r.questionType());
            m.put("probabilities", r.probabilities());
            m.put("top1Prob", r.top1Prob());
            m.put("entropy", r.entropy());
            m.put("adopted", r.adopted());
            m.put("disposition", r.disposition());
            m.put("chosen", r.chosen());
            m.put("latencyMs", r.latencyMs());
            recent.add(m);
        }
        out.put("recent", recent);
        return out;
    }

    /** 测试操作：运行时场景开关覆盖（热更新，重启恢复 yml）。 */
    @PostMapping("/override")
    public Map<String, Object> override(@RequestBody Map<String, Object> body) {
        testRunService.requireTestEnabled();
        String scene = body == null ? null : (String) body.get("scene");
        Boolean enabled = body == null ? null : (Boolean) body.get("enabled");
        if (scene == null || !NanoJevDecisionScenes.ALL.contains(scene)) {
            throw new com.holzyn.actor.common.BizException(400, "scene 必须是已登记场景：" + NanoJevDecisionScenes.ALL);
        }
        if (enabled == null) {
            throw new com.holzyn.actor.common.BizException(400, "enabled 必须为 true/false");
        }
        NanoJevDecisionProperties.SceneConfig cfg = properties.getScenes()
                .computeIfAbsent(scene, k -> new NanoJevDecisionProperties.SceneConfig());
        cfg.setEnabled(enabled);
        log.info("[NanoJev判定] 运行时场景开关覆盖：{} → {}", scene, enabled);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scene", scene);
        out.put("enabled", enabled);
        out.put("effective", gate.isEnabled(scene));
        out.put("message", "已热更新（重启后恢复 application.yml 配置）");
        return out;
    }

    /**
     * 测试操作：判定层探测（真实 NanoJev 调用，只读不改世界）——冒烟直接断言判定链路
     * （分布归一/采纳或升级/延迟），无需先触发世界行为。
     * <p>body：{projectId, scene, characterId?, npcName?, stateText}；按场景题型构造判定
     * （boolean=社交意愿/破绽/护栏，choice=crowd/求婚/面具情境/仲裁，score=生育）。</p>
     */
    @PostMapping("/probe")
    public Map<String, Object> probe(@RequestBody Map<String, Object> body) {
        testRunService.requireTestEnabled();
        Long projectId = body == null ? null : ((Number) body.getOrDefault("projectId", 1L)).longValue();
        String scene = body == null ? null : (String) body.get("scene");
        Long characterId = body == null || body.get("characterId") == null ? null
                : ((Number) body.get("characterId")).longValue();
        String npcName = body == null ? null : (String) body.get("npcName");
        String stateText = body == null ? null : (String) body.get("stateText");
        if (scene == null || !NanoJevDecisionScenes.ALL.contains(scene)) {
            throw new com.holzyn.actor.common.BizException(400, "scene 必须是已登记场景：" + NanoJevDecisionScenes.ALL);
        }
        if (stateText == null || stateText.isBlank()) {
            stateText = npcName == null ? "该角色此刻正在忙自己的事。" : npcName + "此刻正在忙自己的事。";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scene", scene);
        out.put("enabled", gate.isEnabled(scene));
        if (!gate.isEnabled(scene)) {
            out.put("status", "disabled");
            out.put("message", "场景未启用（三重门控）——判定 fail-closed 回退，无 NanoJev 调用");
            return out;
        }
        String stateId = "probe" + (characterId == null ? 0 : characterId);
        Map<String, com.holzyn.actor.ai.nanojev.NanoJevQuestion> questions =
                com.holzyn.actor.ai.nanojev.NanoJevState.newQuestions();
        String qid = "q1";
        switch (scene) {
            case NanoJevDecisionScenes.SOCIAL_WILLINGNESS -> {
                questions.put(qid, NanoJevDecisionTemplates.socialWillingnessQuestion());
                var r = gate.booleanDecision(projectId, characterId, scene,
                        com.holzyn.actor.ai.nanojev.NanoJevState.text(stateId, stateText, questions), qid);
                out.put("status", r.adopted() ? "adopted" : r.disposition());
                out.put("willing", r.value());
                out.put("pTrue", r.pTrue());
                out.put("top1Prob", r.top1Prob());
                out.put("entropy", r.entropy());
                out.put("latencyMs", r.latencyMs());
            }
            case NanoJevDecisionScenes.CROWD_DECISION -> {
                questions.put(qid, NanoJevDecisionTemplates.crowdQuestion(
                        NanoJevDecisionTemplates.commonCrowdCandidates()));
                var r = gate.choiceDecision(projectId, characterId, scene,
                        com.holzyn.actor.ai.nanojev.NanoJevState.text(stateId, stateText, questions), qid);
                out.put("status", r.adopted() ? "adopted" : r.disposition());
                out.put("chosen", r.chosen());
                out.put("top1Prob", r.top1Prob());
                out.put("entropy", r.entropy());
                out.put("latencyMs", r.latencyMs());
            }
            case NanoJevDecisionScenes.ROMANCE_ATTRACT -> {
                questions.put(qid, NanoJevDecisionTemplates.romanceAttractQuestion());
                var r = gate.booleanDecision(projectId, characterId, scene,
                        com.holzyn.actor.ai.nanojev.NanoJevState.text(stateId, stateText, questions), qid);
                out.put("status", r.adopted() ? "adopted" : r.disposition());
                out.put("attracted", r.value());
                out.put("top1Prob", r.top1Prob());
                out.put("entropy", r.entropy());
                out.put("latencyMs", r.latencyMs());
            }
            case NanoJevDecisionScenes.PROPOSAL -> {
                questions.put(qid, NanoJevDecisionTemplates.proposalQuestion());
                var r = gate.choiceDecision(projectId, characterId, scene,
                        com.holzyn.actor.ai.nanojev.NanoJevState.text(stateId, stateText, questions), qid);
                out.put("status", r.adopted() ? "adopted" : r.disposition());
                out.put("chosen", r.chosen());
                out.put("top1Prob", r.top1Prob());
                out.put("entropy", r.entropy());
                out.put("latencyMs", r.latencyMs());
            }
            case NanoJevDecisionScenes.FERTILITY -> {
                questions.put(qid, NanoJevDecisionTemplates.fertilityQuestion());
                var r = gate.scoreDecision(projectId, characterId, scene,
                        com.holzyn.actor.ai.nanojev.NanoJevState.text(stateId, stateText, questions), qid);
                out.put("status", r.adopted() ? "adopted" : r.disposition());
                out.put("level", r.level());
                out.put("expected", r.expected());
                out.put("top1Prob", r.top1Prob());
                out.put("entropy", r.entropy());
                out.put("latencyMs", r.latencyMs());
            }
            case NanoJevDecisionScenes.MASK_CONTEXT -> {
                questions.put(qid, NanoJevDecisionTemplates.maskContextQuestion());
                var r = gate.choiceDecision(projectId, characterId, scene,
                        com.holzyn.actor.ai.nanojev.NanoJevState.text(stateId, stateText, questions), qid);
                out.put("status", r.adopted() ? "adopted" : r.disposition());
                out.put("chosen", r.chosen());
                out.put("top1Prob", r.top1Prob());
                out.put("entropy", r.entropy());
                out.put("latencyMs", r.latencyMs());
            }
            case NanoJevDecisionScenes.MASK_BREACH -> {
                questions.put(qid, NanoJevDecisionTemplates.maskBreachQuestion());
                var r = gate.booleanDecision(projectId, characterId, scene,
                        com.holzyn.actor.ai.nanojev.NanoJevState.text(stateId, stateText, questions), qid);
                out.put("status", r.adopted() ? "adopted" : r.disposition());
                out.put("breach", r.value());
                out.put("top1Prob", r.top1Prob());
                out.put("entropy", r.entropy());
                out.put("latencyMs", r.latencyMs());
            }
            case NanoJevDecisionScenes.NEGOTIATE -> {
                questions.put(qid, NanoJevDecisionTemplates.negotiateQuestion());
                var r = gate.choiceDecision(projectId, characterId, scene,
                        com.holzyn.actor.ai.nanojev.NanoJevState.text(stateId, stateText, questions), qid);
                out.put("status", r.adopted() ? "adopted" : r.disposition());
                out.put("chosen", r.chosen());
                out.put("top1Prob", r.top1Prob());
                out.put("entropy", r.entropy());
                out.put("latencyMs", r.latencyMs());
            }
            case NanoJevDecisionScenes.GUARD_SEMANTIC -> {
                questions.put(qid, NanoJevDecisionTemplates.guardSemanticQuestion());
                var r = gate.booleanDecision(projectId, characterId, scene,
                        com.holzyn.actor.ai.nanojev.NanoJevState.text(stateId, stateText, questions), qid);
                out.put("status", r.adopted() ? "adopted" : r.disposition());
                out.put("outOfBounds", r.value());
                out.put("top1Prob", r.top1Prob());
                out.put("entropy", r.entropy());
                out.put("latencyMs", r.latencyMs());
            }
            case NanoJevDecisionScenes.SPACE_DECISION -> {
                // 空间候选：precondition 语义 boolean 批量 + 行动 choice，单 state 一次前向（mixedDecisions）
                questions.put("pc1", NanoJevDecisionTemplates.spacePreconditionQuestion("此刻不饿"));
                questions.put("pc2", NanoJevDecisionTemplates.spacePreconditionQuestion("身边没有其他人"));
                questions.put(qid, NanoJevDecisionTemplates.spaceActionQuestion(
                        NanoJevDecisionTemplates.commonCrowdCandidates()));
                Map<String, String> kinds = new java.util.LinkedHashMap<>();
                kinds.put("pc1", "boolean");
                kinds.put("pc2", "boolean");
                kinds.put(qid, "choice");
                var mixed = gate.mixedDecisions(projectId, characterId, scene,
                        com.holzyn.actor.ai.nanojev.NanoJevState.text(stateId, stateText, questions), kinds);
                out.put("preconditions", new java.util.LinkedHashMap<>());
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> pcs = (java.util.Map<String, Object>) out.get("preconditions");
                for (String k : new String[]{"pc1", "pc2"}) {
                    var o = (NanoJevDecisionGate.BooleanOutcome) mixed.get(k);
                    pcs.put(k, java.util.Map.of("value", o.value(), "adopted", o.adopted(),
                            "pTrue", o.pTrue(), "top1Prob", o.top1Prob(), "latencyMs", o.latencyMs()));
                }
                var cho = (NanoJevDecisionGate.ChoiceOutcome) mixed.get(qid);
                out.put("status", cho.adopted() ? "adopted" : cho.disposition());
                out.put("chosen", cho.chosen());
                out.put("top1Prob", cho.top1Prob());
                out.put("entropy", cho.entropy());
                out.put("latencyMs", cho.latencyMs());
            }
            case NanoJevDecisionScenes.ATTENTION -> {
                // 认知注意力（score 4 档：此刻注意力状态）
                questions.put(qid, NanoJevDecisionTemplates.attentionStateQuestion());
                var r = gate.scoreDecision(projectId, characterId, scene,
                        com.holzyn.actor.ai.nanojev.NanoJevState.text(stateId, stateText, questions), qid);
                out.put("status", r.adopted() ? "adopted" : r.disposition());
                out.put("level", r.level());
                out.put("expected", r.expected());
                out.put("top1Prob", r.top1Prob());
                out.put("entropy", r.entropy());
                out.put("latencyMs", r.latencyMs());
            }
            case NanoJevDecisionScenes.MICRO_DECISION -> {
                // 高频微决策（choice 6 候选：即时意向）
                questions.put(qid, NanoJevDecisionTemplates.microIntentQuestion());
                var r = gate.choiceDecision(projectId, characterId, scene,
                        com.holzyn.actor.ai.nanojev.NanoJevState.text(stateId, stateText, questions), qid);
                out.put("status", r.adopted() ? "adopted" : r.disposition());
                out.put("chosen", r.chosen());
                out.put("top1Prob", r.top1Prob());
                out.put("entropy", r.entropy());
                out.put("latencyMs", r.latencyMs());
            }
            case NanoJevDecisionScenes.SPACE_TRIGGER -> {
                // 事件驱动重算触发（boolean：这次变化是否值得重新审视空间）
                questions.put(qid, NanoJevDecisionTemplates.spaceTriggerQuestion(stateText));
                var r = gate.booleanDecision(projectId, characterId, scene,
                        com.holzyn.actor.ai.nanojev.NanoJevState.text(stateId, stateText, questions), qid);
                out.put("status", r.adopted() ? "adopted" : r.disposition());
                out.put("worth", r.value());
                out.put("pTrue", r.pTrue());
                out.put("top1Prob", r.top1Prob());
                out.put("entropy", r.entropy());
                out.put("latencyMs", r.latencyMs());
            }
            case NanoJevDecisionScenes.PERSONA_SHIFT -> {
                // 人格偏移评估（score 证据强度 + choice 方向，同 state 一次前向）
                questions.put("strength", NanoJevDecisionTemplates.personaShiftEvidenceQuestion());
                questions.put("direction", NanoJevDecisionTemplates.personaShiftDirectionQuestion());
                Map<String, String> kinds2 = new java.util.LinkedHashMap<>();
                kinds2.put("strength", "score");
                kinds2.put("direction", "choice");
                var mixed2 = gate.mixedDecisions(projectId, characterId, scene,
                        com.holzyn.actor.ai.nanojev.NanoJevState.text(stateId, stateText, questions), kinds2);
                var so = (NanoJevDecisionGate.ScoreOutcome) mixed2.get("strength");
                var dio = (NanoJevDecisionGate.ChoiceOutcome) mixed2.get("direction");
                out.put("status", so.adopted() && dio.adopted() ? "adopted"
                        : (so.disposition().equals(dio.disposition()) ? so.disposition() : "mixed"));
                out.put("strengthLevel", so.adopted() ? so.level() : null);
                out.put("strengthExpected", so.adopted() ? so.expected() : null);
                out.put("direction", dio.adopted() ? dio.chosen() : null);
                out.put("top1Prob", Math.max(so.top1Prob() == null ? 0 : so.top1Prob(),
                        dio.top1Prob() == null ? 0 : dio.top1Prob()));
                out.put("latencyMs", Math.max(so.latencyMs(), dio.latencyMs()));
            }
            case NanoJevDecisionScenes.RELATION_DELTA -> {
                // 关系变化量（score 幅度 + choice 方向，同 state 一次前向）
                questions.put("magnitude", NanoJevDecisionTemplates.relationDeltaMagnitudeQuestion());
                questions.put("direction", NanoJevDecisionTemplates.relationDeltaDirectionQuestion());
                Map<String, String> kinds3 = new java.util.LinkedHashMap<>();
                kinds3.put("magnitude", "score");
                kinds3.put("direction", "choice");
                var mixed3 = gate.mixedDecisions(projectId, characterId, scene,
                        com.holzyn.actor.ai.nanojev.NanoJevState.text(stateId, stateText, questions), kinds3);
                var mag = (NanoJevDecisionGate.ScoreOutcome) mixed3.get("magnitude");
                var dir = (NanoJevDecisionGate.ChoiceOutcome) mixed3.get("direction");
                out.put("status", mag.adopted() && dir.adopted() ? "adopted"
                        : (mag.disposition().equals(dir.disposition()) ? mag.disposition() : "mixed"));
                out.put("magnitudeLevel", mag.adopted() ? mag.level() : null);
                out.put("direction", dir.adopted() ? dir.chosen() : null);
                out.put("top1Prob", Math.max(mag.top1Prob() == null ? 0 : mag.top1Prob(),
                        dir.top1Prob() == null ? 0 : dir.top1Prob()));
                out.put("latencyMs", Math.max(mag.latencyMs(), dir.latencyMs()));
            }
            case NanoJevDecisionScenes.ACCIDENT -> {
                // 意外事件（boolean，默认关——探测如实呈现 disabled 状态）
                questions.put(qid, NanoJevDecisionTemplates.accidentQuestion());
                var r = gate.booleanDecision(projectId, characterId, scene,
                        com.holzyn.actor.ai.nanojev.NanoJevState.text(stateId, stateText, questions), qid);
                out.put("status", r.adopted() ? "adopted" : r.disposition());
                out.put("accident", r.value());
                out.put("pTrue", r.pTrue());
                out.put("top1Prob", r.top1Prob());
                out.put("entropy", r.entropy());
                out.put("latencyMs", r.latencyMs());
            }
            default -> throw new com.holzyn.actor.common.BizException(400, "未支持探测场景：" + scene);
        }
        return out;
    }
}
