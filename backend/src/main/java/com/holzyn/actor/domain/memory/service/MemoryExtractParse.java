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

package com.holzyn.actor.domain.memory.service;

import com.holzyn.actor.common.JsonUtil;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆 AI 输出解析器。
 * <p>职责：把 AI 调用（memory_extract / memory_summarize 模板，json_object 模式）返回的文本
 * 解析为结构化记忆数据——抽取解析为「新记忆条目数组」，摘要解析为「摘要文本」；
 * 兼容 Markdown 代码块/首尾说明文字（复用 JsonUtil），字段缺失时给默认值，非法内容安全丢弃。</p>
 * <p><b>记忆系统重构 抽取契约扩展</b>：
 * 单条输出从 {@code {kind, content, importance}} 扩展为
 * <ul>
 * <li><b>target</b>：character（角色记忆）/ world（项目记忆）/ both（双写：项目记公共事件、
 * 角色侧落个人经历）；缺省 character（兼容旧输出）；</li>
 * <li><b>operation</b>：add（新事实/新经历）/ update（与已有记忆矛盾或演进，写新条 +
 * supersedes 版本链）/ invalidate（旧事实已失效，旧条删除归档不写新条）；缺省 add；</li>
 * <li><b>kind</b>：character 侧 fact（语义事实）/ episodic（个人经历）；
 * world 侧 event（事件类→编年史）/ entity（状态类→实体状态表）；缺省 fact（兼容旧输出）。</li>
 * </ul>
 */
public final class MemoryExtractParse {

    /** 路由目标：角色记忆 */
    public static final String TARGET_CHARACTER = "character";
    /** 路由目标：项目记忆 */
    public static final String TARGET_WORLD = "world";
    /** 路由目标：双写（项目记公共事件 + 角色记个人经历） */
    public static final String TARGET_BOTH = "both";

    /** 操作判定：新增 */
    public static final String OPERATION_ADD = "add";
    /** 操作判定：更新（演进/矛盾，版本链） */
    public static final String OPERATION_UPDATE = "update";
    /** 操作判定：失效（旧事实被推翻，删除归档） */
    public static final String OPERATION_INVALIDATE = "invalidate";

    /** 抽取条目类型：语义事实（character 侧默认） */
    public static final String KIND_FACT = "fact";
    /** 抽取条目类型：个人经历（character 侧 episodic） */
    public static final String KIND_EPISODIC = "episodic";
    /** 抽取条目类型：世界事件类（world 侧 → 编年史） */
    public static final String KIND_EVENT = "event";
    /** 抽取条目类型：实体状态类（world 侧 → 实体状态表） */
    public static final String KIND_ENTITY = "entity";

    /** Jackson 解析器（线程安全，静态复用） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 工具类禁止实例化 */
    private MemoryExtractParse() {
    }

    /**
     * 解析记忆抽取 AI 输出：期望 JSON 数组 [{target, operation, kind, content, importance}]。
     * <p>兼容对象包装（{"memories":[...]} / {"facts":[...]}）与<b>单对象输出</b>
     * （模型偶尔只返回一个对象而非数组）；content 缺失/空白条目丢弃；
     * target/operation/kind 缺省回退（target=character、operation=add、kind=fact）；importance 缺省 1 并钳制 1~5。</p>
     *
     * @param aiText AI 输出文本（可为 null/非法/空数组）
     *
     * @return 解析出的记忆条目列表（非法或空返回空列表）
     */
    public static List<ExtractedMemory> parseExtract(String aiText) {
        List<ExtractedMemory> out = new ArrayList<>();
        if (aiText == null || aiText.isBlank()) {
            return out;
        }
        String json = JsonUtil.extractJson(aiText);
        if (json == null) {
            return out;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root.isArray()) {
                for (JsonNode item : root) {
                    ExtractedMemory em = parseOne(item);
                    if (em != null) {
                        out.add(em);
                    }
                }
            } else if (root.isObject()) {
                // 兼容 { "memories": [...] } / { "facts": [...] } 对象包装（deepseek 常把数组包成对象）
                JsonNode wrapped = root.path("memories");
                if (!wrapped.isArray()) {
                    wrapped = root.path("facts");
                }
                if (wrapped.isArray()) {
                    for (JsonNode item : wrapped) {
                        ExtractedMemory em = parseOne(item);
                        if (em != null) {
                            out.add(em);
                        }
                    }
                } else if (!root.path("content").asText("").isBlank()) {
                    // 兼容单对象输出（模型偶尔返回 {kind,content,importance} 而非数组）
                    ExtractedMemory em = parseOne(root);
                    if (em != null) {
                        out.add(em);
                    }
                }
            }
        } catch (Exception e) {
            // 解析失败：返回已解析部分（若部分成功），整体失败返回空
            return out;
        }
        return out;
    }

    /**
     * 解析单个记忆条目（数组元素或单对象）。
     *
     * @param item JSON 对象节点
     *
     * @return 解析出的记忆条目（非对象/无有效 content 返回 null）
     */
    private static ExtractedMemory parseOne(JsonNode item) {
        if (item == null || !item.isObject()) {
            return null;
        }
        String content = item.path("content").asText("");
        if (content.isBlank()) {
            return null;
        }
        String kind = normalizeKind(item.path("kind").asText(""));
        String target = normalizeTarget(item.path("target").asText(""));
        String operation = normalizeOperation(item.path("operation").asText(""));
        int importance = item.path("importance").asInt(1);
        // 钳制重要度到 1~5（超出视为模型异常输出，按边界处理）
        importance = Math.max(1, Math.min(5, importance));
        return new ExtractedMemory(kind, content.trim(), importance, target, operation);
    }

    /** 归一化 kind（非法值回退 fact）。 */
    private static String normalizeKind(String raw) {
        if (raw == null) {
            return KIND_FACT;
        }
        return switch (raw.trim()) {
            case KIND_EPISODIC -> KIND_EPISODIC;
            case KIND_EVENT -> KIND_EVENT;
            case KIND_ENTITY -> KIND_ENTITY;
            default -> KIND_FACT;
        };
    }

    /** 归一化 target（非法值回退 character，兼容旧输出）。 */
    private static String normalizeTarget(String raw) {
        if (raw == null) {
            return TARGET_CHARACTER;
        }
        return switch (raw.trim()) {
            case TARGET_WORLD -> TARGET_WORLD;
            case TARGET_BOTH -> TARGET_BOTH;
            default -> TARGET_CHARACTER;
        };
    }

    /** 归一化 operation（非法值回退 add，兼容旧输出）。 */
    private static String normalizeOperation(String raw) {
        if (raw == null) {
            return OPERATION_ADD;
        }
        return switch (raw.trim()) {
            case OPERATION_UPDATE -> OPERATION_UPDATE;
            case OPERATION_INVALIDATE -> OPERATION_INVALIDATE;
            default -> OPERATION_ADD;
        };
    }

    /**
     * 解析会话摘要 AI 输出：期望 JSON 对象 {content: "..."}。
     *
     * @param aiText AI 输出文本（可为 null/非法）
     *
     * @return 摘要文本（解析失败或 content 空白返回 null）
     */
    public static String parseSummarize(String aiText) {
        if (aiText == null || aiText.isBlank()) {
            return null;
        }
        String json = JsonUtil.extractJson(aiText);
        if (json == null) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isObject()) {
                return null;
            }
            String content = root.path("content").asText("");
            return content.isBlank() ? null : content.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析后的记忆条目。
     *
     * @param kind 类型（fact/episodic/event/entity）
     *
     * @param content 记忆内容
     *
     * @param importance 重要度（1~5）
     *
     * @param target 路由目标（character/world/both）
     *
     * @param operation 操作判定（add/update/invalidate）
     */
    public record ExtractedMemory(String kind, String content, Integer importance, String target, String operation) {

        /** 兼容旧契约构造（缺省 target=character、operation=add）。 */
        public ExtractedMemory(String kind, String content, Integer importance) {
            this(kind, content, importance, TARGET_CHARACTER, OPERATION_ADD);
        }

        /** 是否路由到项目记忆（world/both）。 */
        public boolean isWorldTargeted() {
            return TARGET_WORLD.equals(target) || TARGET_BOTH.equals(target);
        }

        /** 是否路由到角色记忆（character/both）。 */
        public boolean isCharacterTargeted() {
            return TARGET_CHARACTER.equals(target) || TARGET_BOTH.equals(target);
        }
    }
}
