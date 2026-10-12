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

package com.holzyn.actor.ai;

import java.util.List;

/**
 * 本地 token 估算工具。
 * <p>职责：当供应商未返回真实 usage（不支持 / 被中断 / 未开启 include_usage）时，
 * 按「CJK 字符 ≈ 0.55 token/字符 + ASCII 字母数字 ≈ 0.28 token/字符 + 连续单词开销」的
 * 启发式估算，保证用量展示/日志非 0 且量级合理——口径与旧 {@link OpenAiCompatibleProvider}
 * 内嵌估算<b>完全一致</b>。</p>
 * <p>起新层 {@code AiSpringChatGateway} / {@code HolzynTraceAdvisor} 均复用本工具做
 * 「供应商未返回 usage」时的兜底估算； 旧 Provider 物理删除后本工具继续存在（口径不变）。</p>
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    /**
     * 估算单段文本的 token 数。
     * <p>启发式：CJK 字符约 1 token ≈ 1.8 字符（按 0.55/字符），ASCII 字母数字约 1 token ≈ 3.5 字符
     * （按 0.28/字符）另加每个连续单词 1 token 开销；与常见中文 tokenizer 同量级，供用量展示近似参考。</p>
     *
     * @param text 文本（可空）
     *
     * @return 估算 token 数（非空文本至少 1）
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int cjk = 0;
        int ascii = 0;
        int words = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isCjk(ch)) {
                cjk++;
                if (inWord) {
                    words++;
                    inWord = false;
                }
            } else if (Character.isLetterOrDigit(ch)) {
                ascii++;
                inWord = true;
            } else if (inWord) {
                words++;
                inWord = false;
            }
        }
        if (inWord) {
            words++;
        }
        int tokens = (int) Math.ceil(cjk * 0.55) + (int) Math.ceil(ascii * 0.28) + words;
        return Math.max(1, tokens);
    }

    /**
     * 估算整组消息的 prompt token（含每条消息的系统开销，对齐 OpenAI 约 4 token/条惯例）。
     *
     * @param messages 消息序列（可空）
     *
     * @return 估算 prompt token 数
     */
    public static int estimatePromptTokens(List<AiChatRequest.ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 1;
        }
        int total = 0;
        for (AiChatRequest.ChatMessage m : messages) {
            total += 4 + estimateTokens(m.content());
        }
        return Math.max(1, total);
    }

    /**
     * 是否 CJK 字符（汉字/日文假名/韩文/中文标点等，覆盖中文全角标点）。
     *
     * @param ch 字符
     */
    private static boolean isCjk(char ch) {
        Character.UnicodeScript script = Character.UnicodeScript.of(ch);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL
                || script == Character.UnicodeScript.BOPOMOFO;
    }
}
