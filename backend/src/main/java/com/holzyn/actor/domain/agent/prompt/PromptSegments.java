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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 段装配去重（同一段头 + 同一内容只允许出现一次）。
 *
 * <p><b>缺陷证据</b>：澄江镇二日实跑 18/1215 条 prompt 出现两段 {@code 【当前环境】}、
 * 两行 {@code [世界状态版本 …]}（1.5%）。成因是同一件事被两条装配链各注入一次：</p>
 * <ul>
 * <li>{@code 【当前环境】}：{@code PersonalityInjectionService}（人设段）与
 * {@code PerceptionService}（感知段）都调 {@code EnvironmentContextService.injectionSegment}；</li>
 * <li>{@code [世界状态版本]}：{@code WorldFactQuery#digestForPrompt} 自带版本行，而
 * {@code DecisionService#ensureDecisionSituation} 只按 {@code 【我所知道的事】} 判重——
 * 零认知角色（digest 为空、只剩版本行）判不出重复，于是同一行出现两次。</li>
 * </ul>
 *
 * <p><b>为什么必须「按内容」而不是「按段头」去重</b>：实跑取证发现同名段头承载不同内容的情况
 * 真实存在——{@code proactive_retrieval} 的 prompt 里 {@code 【当前状态】有点闷}（一句体感）
 * 与 {@code 【当前状态】} + 需求/心情/情绪清单（状态模块的详细段）同名不同义。只砍第二个
 * 会直接删掉角色的真实状态。所以规则是：</p>
 * <ul>
 * <li>同段头 + 内容相同 → 只留一份（先出现的；若后出现的更长则留更长的那份）；</li>
 * <li>同段头 + 互为前缀（截断长度不同）→ 留更完整（更长）的那份；</li>
 * <li>同段头 + 内容无关 → <b>两份都留</b>（这是两个不同的东西，不是重复）。</li>
 * </ul>
 *
 * <p><b>只认行首段头</b>：{@code 【X】}/{@code [世界状态版本 …]} 必须出现在行首才算段头；
 * 句中出现的（如【决策要求】正文里顺带提到的「【时间感】留意…」）不动——避免把正常行文切断。</p>
 *
 * <p>纯静态、无依赖、可单测。</p>
 */
public final class PromptSegments {

    private PromptSegments() {
    }

    /** 行首段头：{@code 【…】}（方括号内 ≤19 字，允许同行带正文）或 {@code [世界状态版本 …]}。 */
    private static final Pattern HEADER = Pattern.compile("^(【[^】\\n]{1,19}】|\\[世界状态版本[^\\]\\n]{0,20}\\])");

    /**
     * 段落去重（同段头 + 同内容只留一份）。
     *
     * @param prompt 已装配好的 prompt（可空）
     *
     * @return 去重后的 prompt；{@code null}/空白原样返回
     */
    public static String dedupe(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return prompt;
        }
        List<Block> blocks = split(prompt.split("\n", -1));
        if (blocks.size() <= 1) {
            return prompt;
        }
        boolean[] dropped = new boolean[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) {
            Block cur = blocks.get(i);
            if (cur.key == null || dropped[i]) {
                continue;
            }
            for (int j = 0; j < blocks.size(); j++) {
                if (j == i || dropped[j]) {
                    continue;
                }
                Block other = blocks.get(j);
                if (other.key == null || !cur.key.equals(other.key)) {
                    continue;
                }
                if (!sameOrPrefix(cur.normalized(), other.normalized())) {
                    continue;
                }
                // 同段头 + 同内容/互为前缀：留更完整的那份（等长时留先出现的）
                if (cur.normalized().length() > other.normalized().length()) {
                    dropped[j] = true;
                } else {
                    dropped[i] = true;
                    break;
                }
            }
        }
        StringBuilder out = new StringBuilder(prompt.length());
        for (int i = 0; i < blocks.size(); i++) {
            if (!dropped[i]) {
                out.append(blocks.get(i).text);
            }
        }
        if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    /**
     * 行首段头键（无段头返回 null）。
     *
     * <p>键口径：{@code 【X】} 用原文（方括号内文字必须完整一致）；
     * {@code [世界状态版本 …]} 归一为 {@code 世界状态版本}（版本号不同也算同一段——
     * 同一 prompt 里出现两个版本号本身就是自相矛盾，内容相同/前缀才会被合并）。</p>
     *
     * @param line 单行文本（可空）
     *
     * @return 段头键（null=不是段头行）
     */
    static String headerKey(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        Matcher m = HEADER.matcher(line.trim());
        if (!m.find()) {
            return null;
        }
        String token = m.group(1);
        return token.startsWith("[") ? "世界状态版本" : token;
    }

    /**
     * 切块：一个块 = 一行段头 + 其后的续行（直到下一个段头或空行）；空行单独成块（保留分隔）。
     *
     * @param lines 行数组（含空行）
     *
     * @return 块序列
     */
    private static List<Block> split(String[] lines) {
        List<Block> blocks = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String key = headerKey(line);
            if (key == null) {
                if (line.isBlank()) {
                    flushPlain(blocks, plain);
                    blocks.add(new Block(null, "\n", ""));
                } else {
                    plain.append(line).append('\n');
                }
                i++;
                continue;
            }
            flushPlain(blocks, plain);
            StringBuilder sb = new StringBuilder(line).append('\n');
            i++;
            while (i < lines.length && headerKey(lines[i]) == null && !lines[i].isBlank()) {
                sb.append(lines[i]).append('\n');
                i++;
            }
            String text = sb.toString();
            blocks.add(new Block(key, text, normalize(text)));
        }
        flushPlain(blocks, plain);
        return blocks;
    }

    /**
     * 把累积的普通文本落成一个块。
     *
     * @param blocks 块序列
     *
     * @param plain  普通文本缓冲（落块后清空）
     */
    private static void flushPlain(List<Block> blocks, StringBuilder plain) {
        if (plain.length() > 0) {
            blocks.add(new Block(null, plain.toString(), ""));
            plain.setLength(0);
        }
    }

    /**
     * 同内容或互为前缀（截断长度不同：人设段 140 字 vs 感知段 120 字）。
     *
     * @param a 归一文本 A（非空）
     *
     * @param b 归一文本 B（非空）
     *
     * @return true=可视为同一段
     */
    private static boolean sameOrPrefix(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return a.equals(b);
        }
        return a.equals(b) || a.startsWith(b) || b.startsWith(a);
    }

    /**
     * 归一文本（去空白与尾部省略号，用于内容比对）。
     *
     * @param text 块原文（非空）
     *
     * @return 归一文本
     */
    private static String normalize(String text) {
        String s = text.replaceAll("\\s+", "");
        while (s.endsWith("…") || s.endsWith("...")) {
            s = s.substring(0, s.endsWith("…") ? s.length() - 1 : s.length() - 3);
        }
        return s;
    }

    /**
     * 一个装配块（{@code key} 为空=普通文本块）。
     *
     * @param key        段头键（可空）
     *
     * @param text       块原文（含行尾换行）
     *
     * @param normalized 归一文本（普通块为空串，不参与比对）
     */
    private record Block(String key, String text, String normalized) {
    }
}
