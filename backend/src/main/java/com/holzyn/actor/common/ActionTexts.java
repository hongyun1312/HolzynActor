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

package com.holzyn.actor.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 行动文本收口（唯一解包/人话化入口）。
 *
 * <p><b>为什么需要</b>：澄江镇二日实跑里 32/1464 条行动的 {@code summary} 是原始 JSON
 * （如 {@code { "action": "傍晚，老支书从桥头慢慢走回家…" }}），并且因为按字面去解析目的地而
 * 产出 {@code TARGET_NOT_FOUND}。根因是「AI 输出的 action 字段可能本身就是一段 JSON」——
 * 模型偶尔把内层契约又套了一层字符串，或者把 {action:…} 整体塞进 {@code action_detail}。
 * 这段文本一旦落库，就行走三处：行动日志（时间线）、行动世界事件标题、经历记忆，
 * 全部变成人类读不懂的 JSON 字面量。</p>
 *
 * <p><b>口径</b>：只在<b>需要「人话」的地方</b>调用（落库/事件标题/记忆/目标解析），
 * 不做语义改写、不猜意图：能抽出字符串就抽，抽不到就返回空串——宁可为空，
 * 也不把原始 JSON 当人类语言存进去。原先写死在 {@code DecisionLedgerService#cleanActionText}
 * 的规则收口到本类，避免第二份实现（同一能力两个入口就是架构混乱的开始）。</p>
 *
 * <p>纯静态、无依赖、可单测。</p>
 */
public final class ActionTexts {

    private ActionTexts() {
    }

    /**
     * 解包时按顺序尝试的键（先业务语义，再兜底语义）。
     * <p>覆盖：内层动作（action/detail）、描述（description/summary）、动作名（type/activity）、
     * 契约别名（action_detail/text/content）。</p>
     */
    private static final String[] UNWRAP_KEYS = {
            "action", "action_detail", "detail", "description", "summary", "activity", "type", "text", "content"
    };

    /** 单次抽取的字符串上限（超长说明不是一句动作，交给空串兜底）。 */
    private static final int UNWRAP_MAX_CHARS = 200;

    /**
     * 系统模板句特征词：命中即视为「不是角色想出来的话」，不进入长期记忆。
     * <ul>
     * <li>{@code 觅食充饥}：生理兜底动作（{@code PhysioNeedService}/{@code NpcStateService}）；</li>
     * <li>{@code 稍候片刻}：{@code ActionCoordinatorService} 的占位改写
     * （「X在Y附近稍候片刻，稍后再去」/「X见Y正有人使用，先在附近稍候片刻，稍后再去」）。</li>
     * </ul>
     * <p>实跑证据：21 条记忆含「觅食充饥」、4 条含「我见河神庙正有人使用」——模板句被反复
     * 沉淀成长期记忆后，角色每轮都从记忆里重新读到它，形成自我强化的单调循环。</p>
     */
    private static final String[] ROUTINE_TEMPLATE_FRAGMENTS = {
            "觅食充饥", "稍候片刻"
    };

    /**
     * 解包为「人话」文本。
     *
     * <p>非 JSON：只把连续空白压成一个空格（保持原有台账口径）。<br>
     * 是 JSON：按 {@link #UNWRAP_KEYS} 顺序抽第一个非空、且<b>本身不再是 JSON</b> 的字符串；
     * 内层还是 JSON 时再解一层（两层足够——实跑里最多反序列化两层）。<br>
     * 抽不到：返回空串（绝不把原始 JSON 当动作文本返回）。</p>
     *
     * @param raw 原始动作文本（可空）
     *
     * @return 可直接落库/展示的动作文本；{@code null} 原样返回 {@code null}，其余异常形态返回空串
     */
    public static String unwrap(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return text;
        }
        if (!looksLikeJson(text)) {
            return text.replaceAll("\\s+", " ");
        }
        String current = text;
        for (int depth = 0; depth < 2; depth++) {
            String extracted = extractFirstString(current);
            if (extracted.isBlank()) {
                return "";
            }
            String trimmed = extracted.trim();
            if (!looksLikeJson(trimmed)) {
                return trimmed.replaceAll("\\s+", " ");
            }
            current = trimmed;
        }
        return "";
    }

    /**
     * 是否像结构化文本（{@code {…}} / {@code […] }，允许前置引号或标签残留）。
     *
     * @param text 文本（可空）
     *
     * @return true=像 JSON
     */
    public static boolean looksLikeJson(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return false;
        }
        char c = t.charAt(0);
        return c == '{' || c == '[';
    }

    /**
     * 是否为系统模板句（不应被当作角色「想过的话」沉淀）。
     *
     * @param text 动作/记忆文本（可空）
     *
     * @return true=模板句
     */
    public static boolean isRoutineTemplate(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String fragment : ROUTINE_TEMPLATE_FRAGMENTS) {
            if (text.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 JSON 文本中按顺序抽出第一个可用的字符串值（不再递归解包）。
     *
     * @param json JSON 文本
     *
     * @return 抽到的原始字符串（未 trim）；抽不到返回空串
     */
    static String extractFirstString(String json) {
        for (String key : UNWRAP_KEYS) {
            Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){1,"
                    + UNWRAP_MAX_CHARS + "})\"");
            Matcher m = p.matcher(json);
            if (m.find()) {
                String v = m.group(1).replace("\\\"", "\"").replace("\\\\", "\\").trim();
                if (!v.isBlank()) {
                    return v;
                }
            }
        }
        return "";
    }
}
