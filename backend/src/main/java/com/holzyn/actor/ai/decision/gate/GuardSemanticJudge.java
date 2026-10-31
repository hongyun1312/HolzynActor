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

package com.holzyn.actor.ai.decision.gate;

import com.holzyn.actor.ai.decision.DecisionState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 护栏语义复核的 决策模型 实现。
 * <p><b>定位</b>： 统一护栏的<b>语义层扩展</b>（N 轨道设计 §2.1：护栏语义层 boolean
 * 「输出是否越界」作语义复核）——程序化规则（空/复读/超时）之上加一层 0.6B 语义判定：
 * 输出空泛/复读/答非所问/违背任务要求 → 越界。场景开关默认关（未过 A/B 评估）。</p>
 * <p><b>接入点</b>：由 {@code AiSpringChatGateway.guardCheck}（非流式）经字段注入可选委托——
 * 默认关时零开销；开启时对非流式输出做一次 boolean 复核，越界则按护栏失败处置
 * （统计/告警/程序化兜底，与空/复读同语义）。</p>
 * <p><b>架构不变量</b>：含 AI 调用（经判定门），公开方法不带 {@code @Transactional}
 * （AiCallOutsideTransactionTest 扩展守护）。</p>
 */
@Slf4j
@Component
public class GuardSemanticJudge {

    /** 场景码（逐场景开关键；默认关） */
    public static final String SCENE = DecisionScenes.GUARD_SEMANTIC;

    /** state 内问题键 */
    public static final String QID = "out_of_bounds";

    /** 复核文本截断上限（字符；防超长输出撑爆 state） */
    public static final int MAX_INPUT_CHARS = 800;

    @Autowired(required = false)
    private DecisionGate gate;

    /**
     * 语义复核：输出是否越界（空泛/复读/答非所问/违背任务要求）。
     *
     * @param projectId 项目 ID（可空）
     *
     * @param scene 被复核的 AI 场景（trace 归属）
     *
     * @param content 输出文本（截断后注入）
     *
     * @return 越界判定（Boolean.TRUE=越界 / Boolean.FALSE=合规）；null=未启用/升级/失败（不拦截）
     */
    public Boolean check(Long projectId, String scene, String content) {
        if (gate == null || !gate.isEnabled(SCENE) || content == null || content.isBlank()) {
            return null;
        }
        try {
            String text = content.trim();
            if (text.length() > MAX_INPUT_CHARS) {
                text = text.substring(0, MAX_INPUT_CHARS);
            }
            String stateText = "任务场景：" + (scene == null ? "（未知）" : scene)
                    + "。AI 输出：\n" + text;
            Map<String, com.holzyn.actor.ai.decision.DecisionQuestion> questions = DecisionState.newQuestions();
            questions.put(QID, DecisionTemplates.guardSemanticQuestion());
            DecisionState state = DecisionState.text("guard" + (scene == null ? 0 : scene.hashCode()),
                    stateText, questions);
            DecisionGate.BooleanOutcome out = gate.booleanDecision(
                    projectId, null, SCENE, state, QID);
            if (out == null || !out.adopted()) {
                return null;
            }
            log.info("[决策模型判定] 护栏语义：scene={} 越界={}（熵={}）", scene, out.value(),
                    out.entropy() == null ? "-" : Math.round(out.entropy() * 1000) / 1000.0);
            return out.value();
        } catch (Exception e) {
            log.warn("[决策模型判定] 护栏语义复核异常（不拦截）: {}", e.getMessage());
            return null;
        }
    }
}
