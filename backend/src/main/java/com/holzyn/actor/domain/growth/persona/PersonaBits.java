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

package com.holzyn.actor.domain.growth.persona;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 档案短位元读取器。
 * <p><b>为什么需要它</b>： 后 {@code persona_json} 是 11 维 v2 结构（{@code dims.*}），而多处服务过去
 * 直接从<b>v1 平铺键</b>取"性格/目标/行为模式"等短位元（{@code personality.traits}、
 * {@code background.goals}、{@code behaviorPatterns}）。在 v2 档案下这些键**全部不再命中**，
 * 结果是"段标题还在、内容静默变空"——最难发现的一类退化。</p>
 * <p>本类把"按 v2 路径取文本/数组并统一成字符串列表"这一件事收敛到一处，避免每个消费方各写一套
 * 键路径解析（也避免各自踩"文本 vs 数组 vs 对象"三种形态的坑）。</p>
 * <p><b>与 {@link PersonaRenderer} 的分工</b>：需要"整段可直接注入的人格叙述"时用渲染器
 * （{@code renderShardsForScene} / {@code renderFull}）；只需要"若干短词条拼进既有段落"时用本类，
 * 以免在大 Prompt 里重复注入同一份档案文本。</p>
 */
public final class PersonaBits {

    private PersonaBits() {
    }

    /**
     * 按 v2 路径取节点（逐级下钻，缺失返回 {@code MissingNode}，绝不返回 null）。
     *
     * @param profile v2 档案 JSON 根（可为 null）
     *
     * @param path 字段路径，如 {@code "dims","personality","traits_summary"}
     *
     * @return 命中的节点；档案为空或路径缺失返回 MissingNode
     */
    public static JsonNode at(JsonNode profile, String... path) {
        if (profile == null) {
            return tools.jackson.databind.node.MissingNode.getInstance();
        }
        JsonNode n = profile;
        for (String seg : path) {
            if (n == null || n.isMissingNode() || n.isNull()) {
                return tools.jackson.databind.node.MissingNode.getInstance();
            }
            n = n.path(seg);
        }
        return n == null ? tools.jackson.databind.node.MissingNode.getInstance() : n;
    }

    /**
     * 按 v2 路径取文本值。
     *
     * @param profile v2 档案 JSON 根
     *
     * @param path 字段路径
     *
     * @return 文本；缺失或非文本返回空串
     */
    public static String text(JsonNode profile, String... path) {
        JsonNode n = at(profile, path);
        if (n.isMissingNode() || n.isNull()) {
            return "";
        }
        return n.isValueNode() ? n.asString() : "";
    }

    /**
     * 按 v2 路径取文本值；缺失/空白时返回兜底文案。
     *
     * @param profile v2 档案 JSON 根
     *
     * @param fallback 兜底文案（缺失时使用）
     *
     * @param path 字段路径
     *
     * @return 文本或兜底文案
     */
    public static String textOr(JsonNode profile, String fallback, String... path) {
        String t = text(profile, path);
        return t == null || t.isBlank() ? fallback : t;
    }

    /**
     * 按 v2 路径取"词条列表"——把 v2 里三种可能形态统一成字符串列表：
     * <ul>
     * <li><b>数组</b>（如 {@code micro_movements}）→ 元素文本；元素为对象时取 {@code what}/{@code event}/{@code name} 字段；</li>
     * <li><b>对象</b>（如 {@code value_weights} 权重表）→ 取键名（权重值本身对提示词无意义）；</li>
     * <li><b>文本</b>（如 {@code traits_summary}）→ 单元素列表。</li>
     * </ul>
     *
     * @param profile v2 档案 JSON 根
     *
     * @param path 字段路径
     *
     * @return 词条列表（可为空列表，绝不为 null）
     */
    public static List<String> texts(JsonNode profile, String... path) {
        JsonNode n = at(profile, path);
        List<String> out = new ArrayList<>();
        if (n.isMissingNode() || n.isNull()) {
            return out;
        }
        if (n.isArray()) {
            for (JsonNode e : n) {
                String t = e.isObject()
                        ? firstText(e, "what", "event", "name", "title")
                        : (e.isValueNode() ? e.asString() : "");
                if (t != null && !t.isBlank()) {
                    out.add(t.trim());
                }
            }
        } else if (n.isObject()) {
            // Jackson 3：对象键迭代用 properties()（返回可迭代的键值集合；Jackson 2 的 fieldNames 已不在仓库口径内）
            n.properties().forEach(e -> out.add(e.getKey()));
        } else if (n.isValueNode()) {
            String t = n.asString();
            if (t != null && !t.isBlank()) {
                out.add(t.trim());
            }
        }
        return out;
    }

    /**
     * 把 v2 路径下的词条追加进既有位元列表（最多 {@code max} 条，避免短摘要段膨胀）。
     *
     * @param out 目标列表（原地追加）
     *
     * @param max 最多追加条数（≤0 视为 1）
     *
     * @param profile v2 档案 JSON 根
     *
     * @param path 字段路径
     *
     * @return 实际追加条数
     */
    public static int appendAll(List<String> out, int max, JsonNode profile, String... path) {
        if (out == null) {
            return 0;
        }
        int limit = Math.max(1, max);
        int added = 0;
        for (String t : texts(profile, path)) {
            if (added >= limit) {
                break;
            }
            if (!out.contains(t)) {
                out.add(t);
                added++;
            }
        }
        return added;
    }

    /** 取对象里第一个非空文本字段（键按给定顺序尝试）。 */
    private static String firstText(JsonNode obj, String... keys) {
        for (String k : keys) {
            JsonNode v = obj.path(k);
            if (v.isValueNode() && !v.asString().isBlank()) {
                return v.asString();
            }
        }
        return "";
    }
}