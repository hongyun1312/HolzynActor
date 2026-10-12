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

package com.holzyn.actor.domain.knowledge.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 中文文本分块工具。
 * <p>职责：从 {@link KnowledgeRetrievalService} 抽取的「中文按句子边界分块」公共逻辑——
 * 每块不超过 chunkSize（默认 500 字），相邻块带 overlap（默认 50 字）重叠保留上下文语义，
 * 供<b>新旧两条入库路径</b>复用：新路径（LangChain4j {@code KnowledgeVectorizerService}）与
 * 旧路径（{@code KnowledgeRetrievalService} 保留静态委托，避免既有调用/单测改动）。</p>
 * <p><b>为什么保留现有分块而不迁移 LangChain4j DocumentSplitters</b>（方案 §5.3 决策）：
 * 中文句子边界 + 重叠已按本项目检索质量调优，避免检索质量回退；框架分块器以英文空白/段落为主。</p>
 * <p>纯静态工具，无状态、可单测。</p>
 */
public final class KnowledgeChunker {

    /** 默认分块大小（字符数） */
    public static final int CHUNK_SIZE = 500;

    /** 相邻块重叠字符数（保留上下文语义） */
    public static final int CHUNK_OVERLAP = 50;

    private KnowledgeChunker() {
    }

    /**
     * 文本分块：按段落/句子切分，每块不超过 chunkSize，相邻块带 overlap 字符重叠。
     *
     * @param text 全文
     *
     * @param chunkSize 单块最大字符数
     *
     * @param overlap 相邻块重叠字符数
     *
     * @return 分块列表（空文本返回空列表）
     */
    public static List<String> chunk(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.replace("\r\n", "\n").trim();
        List<String> sentences = splitIntoSentences(normalized);
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String s : sentences) {
            // 超长句（罕见）：硬切分
            for (String piece : splitLong(s, chunkSize)) {
                if (current.length() > 0 && current.length() + piece.length() + 1 > chunkSize) {
                    chunks.add(current.toString().trim());
                    current.setLength(0);
                    String tail = tailOf(chunks.get(chunks.size() - 1), overlap);
                    current.append(tail);
                }
                if (current.length() > 0) current.append('\n');
                current.append(piece);
            }
        }
        String last = current.toString().trim();
        if (!last.isEmpty()) {
            chunks.add(last);
        }
        return chunks;
    }

    /**
     * 按句子切分（保留标点）：以换行/。！？!?；; 为边界。
     * <p>换行被替换为句号后可能产生孤立标点片段（纯标点），会并入上一句，避免分块失真。</p>
     *
     * @param text 文本
     *
     * @return 句子列表
     */
    public static List<String> splitIntoSentences(String text) {
        String normalized = text.replaceAll("\\r?\\n+", "。");
        String[] parts = normalized.split("(?<=[。！？!?；;])");
        List<String> result = new ArrayList<>();
        for (String p : parts) {
            String s = p.trim();
            if (s.isEmpty()) {
                continue;
            }
            // 纯标点片段（如换行替换产生的孤立句号）：并入上一句
            if (isOnlyPunctuation(s)) {
                if (!result.isEmpty()) {
                    result.set(result.size() - 1, result.get(result.size() - 1) + s);
                }
                continue;
            }
            result.add(s);
        }
        return result;
    }

    /**
     * 文本数组按批拆分（供应商单次 input 上限拆分，如 doubao-embedding 单次≤10；超限拆多批保持顺序）。
     * <p>AI 接入层重构：随旧 {@code EmbeddingService} 物理删除迁入本工具（旧静态方法在
     * {@code KnowledgeRetrievalServiceTest} 有直接断言，保留语义零改动）。</p>
     *
     * @param texts 待分批发往供应商的文本数组
     *
     * @param batchSize 单批条数上限（<=0 视为 1）
     *
     * @return 批次列表（每批 ≤ batchSize，顺序保持）
     */
    public static List<String[]> splitIntoBatches(String[] texts, int batchSize) {
        List<String[]> batches = new ArrayList<>();
        if (texts == null || texts.length == 0) {
            return batches;
        }
        int size = batchSize > 0 ? batchSize : 1;
        for (int from = 0; from < texts.length; from += size) {
            int to = Math.min(texts.length, from + size);
            String[] batch = new String[to - from];
            System.arraycopy(texts, from, batch, 0, to - from);
            batches.add(batch);
        }
        return batches;
    }

    /**
     * 判断文本是否仅由标点/空白构成（无字母、数字）。
     *
     * @param s 文本
     *
     * @return true 表示纯标点
     */
    private static boolean isOnlyPunctuation(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 超长句硬切分：按 chunkSize 均分。
     *
     * @param s 句子
     *
     * @param chunkSize 块大小
     *
     * @return 切分后的片段列表
     */
    private static List<String> splitLong(String s, int chunkSize) {
        if (s.length() <= chunkSize) {
            return List.of(s);
        }
        List<String> pieces = new ArrayList<>();
        int from = 0;
        while (from < s.length()) {
            int to = Math.min(s.length(), from + chunkSize);
            pieces.add(s.substring(from, to));
            from = to;
        }
        return pieces;
    }

    /**
     * 取文本尾部 overlap 字符（重叠用；不足则整段）。
     *
     * @param text 文本
     *
     * @param overlap 字符数
     *
     * @return 尾部片段
     */
    private static String tailOf(String text, int overlap) {
        if (text == null || text.isEmpty() || overlap <= 0) {
            return "";
        }
        int from = Math.max(0, text.length() - overlap);
        // 尽量从完整词边界开始（回退到最近的标点或空白，避免从字中间切断）
        String tail = text.substring(from);
        int cut = tail.indexOf('。');
        if (cut > 0 && cut < tail.length() / 2) {
            return tail.substring(cut + 1);
        }
        return tail;
    }
}
