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

package com.holzyn.actor.domain.agent.prompt;

import java.util.regex.Pattern;

/**
 * Prompt 渲染期规范化器。
 * <p>对<b>全部模板</b>的渲染结果做结构化迁移 ——
 * 不改模板语义，只统一「输出契约措辞」与「段分隔符」，让 60+ 模板在格式层面逐步收敛：</p>
 * <ul>
 * <li><b>输出规则统一</b>：把历史模板中散落的「请严格输出一个 JSON 对象，禁止输出任何其他文字」等
 * 变体，统一为规范句并追加全大写强制句（DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.），
 * 提升指令遵循度；</li>
 * <li><b>段分隔符统一</b>：把「── 段名 ──」「-- 段名 --」「【段名】」等旧分隔形态的标题
 * 统一为规范「—— 段名 ——」（内容零改动，仅标题行）；</li>
 * <li><b>零副作用</b>：未命中任何规则时原样返回；规范化器只做文本替换，不改变模板语义与结构。</li>
 * </ul>
 * <p>挂在 {@code PromptTemplateService.render/systemMessage} 尾部（渲染结果上执行），
 * 无论 DB 存量模板新旧都即时生效，无需手工迁移存量模板行。</p>
 */
public final class PromptNormalizer {

    /** 规范输出规则句（统一全部历史变体） */
    private static final String CANONICAL_OUTPUT_RULE =
            "请严格输出 JSON 对象，禁止输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.";

    /** 历史输出规则变体（按序替换为规范句） */
    private static final String[][] OUTPUT_RULE_VARIANTS = {
            // 常见变体 → 规范句
            {"请严格输出一个 JSON 对象，禁止输出任何其他文字。", CANONICAL_OUTPUT_RULE},
            {"请严格输出符合上述 Schema 的 JSON 对象，禁止输出任何其他文字。", CANONICAL_OUTPUT_RULE},
            {"请严格输出符合下方 Schema 的 JSON 对象，禁止输出任何其他文字。", CANONICAL_OUTPUT_RULE},
            {"请严格输出符合上方 Schema 的 JSON 对象，禁止输出任何其他文字。", CANONICAL_OUTPUT_RULE},
            {"请严格输出符合 Schema 的 JSON 对象，禁止输出任何其他文字。", CANONICAL_OUTPUT_RULE},
            {"输出一个 JSON 对象，禁止输出 JSON 以外的任何文字。", CANONICAL_OUTPUT_RULE},
            {"输出为一个 JSON 对象，禁止输出 JSON 以外的任何文字。", CANONICAL_OUTPUT_RULE},
            {"只输出一个 JSON 对象，禁止输出 JSON 以外的任何文字。", CANONICAL_OUTPUT_RULE},
            {"只输出一个 JSON 对象，禁止输出任何其他文字。", CANONICAL_OUTPUT_RULE},
            {"请严格输出一个 JSON 对象，禁止输出 JSON 以外的任何文字。", CANONICAL_OUTPUT_RULE},
            {"输出严格的 JSON 对象，禁止输出任何其他文字。", CANONICAL_OUTPUT_RULE},
            {"只输出严格的 JSON 对象，禁止输出任何其他文字。", CANONICAL_OUTPUT_RULE},
            {"请输出一个 JSON 对象，禁止输出任何其他文字。", CANONICAL_OUTPUT_RULE},
            {"输出一个 JSON 对象，不要输出任何其他文字。", CANONICAL_OUTPUT_RULE},
            {"只输出 JSON，不要输出任何其他文字。", CANONICAL_OUTPUT_RULE},
    };

    /** 旧式段标题（── X ── / -- X -- / 【X】独立行）→ 规范「—— X ——」 */
    private static final Pattern LEGACY_TITLE_EM = Pattern.compile("^\\s*──\\s*([^─\\n]{1,20}?)\\s*──\\s*$");
    private static final Pattern LEGACY_TITLE_DASH = Pattern.compile("^\\s*--\\s*([^\\-\\n]{1,20}?)\\s*--\\s*$");
    private static final Pattern LEGACY_TITLE_BRACKET = Pattern.compile("^\\s*【([^】\\n]{1,20})】\\s*$");

    /** 已含强制句（避免重复追加） */
    private static final String FORCE_CLAUSE = "DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.";

    private PromptNormalizer() {
    }

    /**
     * 规范化渲染结果（逐行处理段标题 + 输出规则变体替换）。
     *
     * @param text 渲染后的 Prompt 文本
     *
     * @return 规范化后的文本（null/空白原样返回）
     */
    public static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String out = normalizeTitles(text);
        for (String[] variant : OUTPUT_RULE_VARIANTS) {
            if (out.contains(variant[0])) {
                out = out.replace(variant[0], variant[1]);
            }
        }
        // 兜底：任何「禁止输出任何其他文字」形态但无强制句 → 追加强制句
        if (out.contains("禁止输出任何其他文字") || out.contains("禁止输出 JSON 以外的任何文字")
                || out.contains("不要输出任何其他文字")) {
            if (!out.contains(FORCE_CLAUSE)) {
                out = out + "\n" + FORCE_CLAUSE;
            }
        }
        return out;
    }

    /** 段标题统一为「—— X ——」（仅标题行，内容零改动）。 */
    static String normalizeTitles(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String normalized = null;
            java.util.regex.Matcher m = LEGACY_TITLE_EM.matcher(line);
            if (m.matches()) {
                normalized = "—— " + m.group(1).trim() + " ——";
            } else {
                m = LEGACY_TITLE_DASH.matcher(line);
                if (m.matches()) {
                    normalized = "—— " + m.group(1).trim() + " ——";
                } else {
                    m = LEGACY_TITLE_BRACKET.matcher(line);
                    if (m.matches() && isTitleLike(m.group(1))) {
                        normalized = "—— " + m.group(1).trim() + " ——";
                    }
                }
            }
            sb.append(normalized == null ? line : normalized);
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** 判定【X】是否为段标题形态（X 短且不含句号/逗号等正文特征）。 */
    private static boolean isTitleLike(String s) {
        if (s == null || s.isBlank() || s.length() > 20) {
            return false;
        }
        return !s.contains("。") && !s.contains("，") && !s.contains("：") && !s.contains("！") && !s.contains("？");
    }
}
