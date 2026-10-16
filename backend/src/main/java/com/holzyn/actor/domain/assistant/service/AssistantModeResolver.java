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

package com.holzyn.actor.domain.assistant.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 双模式判定器（叙事 narrative / 严格事实 fact）——三层判定：
 * <ol>
 * <li><b>逐轮覆盖</b>：单轮对话内用户显式指定（如「用数据说话」「用叙事说」）或 API 传参
 * modeOverride（narrative/fact），优先级最高；</li>
 * <li><b>意图分类</b>：问题含精确数据需求（数值/字段/对比/排查）→ 自动切事实模式；</li>
 * <li><b>会话偏好</b>：无显式覆盖且意图不明显 → 用会话默认模式（默认叙事）。</li>
 * </ol>
 * <p>纯规则判定（关键词 + 显式指令识别），零 AI 成本；显式指令可从用户问题中剥离，
 * 避免「用数据说话」这类词被当成问题正文注入。</p>
 * <p><b>M5</b>：新增三参重载 {@link #resolve(String, String, String)}——API 层 modeOverride
 * 单独传入判定，<b>不再拼进问题文本</b>（旧实现把「fact」拼进问题会导致净问题被污染，
 * 且「fact」不在关键词表内无法剥离，modeOverride 实际失效）。</p>
 */
@Component
public class AssistantModeResolver {

    /** 显式切事实模式的指令（命中即 fact，且从问题中剥离） */
    private static final List<String> FACT_EXPLICIT = List.of(
            "用数据说话", "精确查询", "给我原始数据", "用事实说", "数据模式", "事实模式", "严格事实",
            "列出数据", "用数值", "用数据表达");

    /** 显式切叙事模式的指令（命中即 narrative，且从问题中剥离；长短语在前防残留） */
    private static final List<String> NARRATIVE_EXPLICIT = List.of(
            "用叙事的方式说", "用叙事的方式", "用故事的方式说", "用讲故事的方式",
            "用叙事", "用故事", "讲个故事", "文学一点", "叙事模式", "用讲故事", "绘声绘色");

    /** 意图分类：含精确数据需求的关键词（命中自动切 fact） */
    private static final List<String> FACT_INTENT = List.of(
            "多少", "数值", "值是多少", "数值是多少", "饥饿值", "好感度", "粮价", "民怨", "数据",
            "状态值", "精确", "查一下", "对比", "排查", "为什么这次", "统计", "具体数字", "名单",
            "坐标", "版本", "调用次数", "token", "耗时");

    /**
     * 解析回答模式（三层判定；兼容旧调用）。
     *
     * @param question      用户问题（可能含显式指令）
     * @param sessionDefault 会话默认模式（narrative/fact）
     *
     * @return 判定结果（模式 + 剥离显式指令后的净问题）
     */
    public ModeDecision resolve(String question, String sessionDefault) {
        if (question == null || question.isBlank()) {
            return new ModeDecision("narrative", question == null ? "" : question);
        }
        String q = question.trim();
        // ① 显式指令（逐轮覆盖）
        for (String kw : FACT_EXPLICIT) {
            if (q.contains(kw)) {
                return new ModeDecision("fact", q.replace(kw, "").trim());
            }
        }
        for (String kw : NARRATIVE_EXPLICIT) {
            if (q.contains(kw)) {
                return new ModeDecision("narrative", q.replace(kw, "").trim());
            }
        }
        // ② 意图分类：含精确数据需求 → 事实模式
        String lower = q.toLowerCase(Locale.ROOT);
        for (String kw : FACT_INTENT) {
            if (lower.contains(kw.toLowerCase(Locale.ROOT))) {
                return new ModeDecision("fact", q);
            }
        }
        // ③ 会话偏好
        return new ModeDecision("fact".equals(sessionDefault) ? "fact" : "narrative", q);
    }

    /**
     * 解析回答模式（三层判定 + API 层逐轮覆盖，M5）。
     * <p><b>覆盖层优先</b>：modeOverride 为合法模式（narrative/fact）时直接采用该模式，
     * 问题保持原样（<b>不拼入问题文本</b>，避免「fact」这类覆盖标记污染净问题）；
     * 非法/空覆盖回退 {@link #resolve(String, String)} 的完整判定链。</p>
     *
     * @param question      用户问题（可能含显式指令）
     * @param sessionDefault 会话默认模式（narrative/fact）
     * @param modeOverride  API 层逐轮模式覆盖（narrative/fact；可空）
     *
     * @return 判定结果（模式 + 净问题）
     */
    public ModeDecision resolve(String question, String sessionDefault, String modeOverride) {
        if (modeOverride != null && ("fact".equals(modeOverride) || "narrative".equals(modeOverride))) {
            String q = question == null ? "" : question.trim();
            // 覆盖已定模式：问题中任何显式指令均冗余，剥离防污染（如「用数据说话」残留进正文）
            for (String kw : FACT_EXPLICIT) {
                if (q.contains(kw)) {
                    q = q.replace(kw, "").trim();
                }
            }
            for (String kw : NARRATIVE_EXPLICIT) {
                if (q.contains(kw)) {
                    q = q.replace(kw, "").trim();
                }
            }
            return new ModeDecision(modeOverride, q);
        }
        return resolve(question, sessionDefault);
    }

    /**
     * 判定结果。
     *
     * @param mode         narrative/fact
     * @param cleanQuestion 剥离显式指令后的净问题
     */
    public record ModeDecision(String mode, String cleanQuestion) {
    }
}
