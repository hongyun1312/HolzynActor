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

package com.holzyn.actor.domain.memory.common;

/**
 * 记忆四层类型常量与工具。
 * <p>四层：L1 短期（{@code actor_memory_working} 工作记忆）/ L2 长期（{@code actor_memory}，
 * type 四类）/ L3 核心（{@code actor_core_memory} 恒定上下文身份块）/ 归档（{@code actor_memory_archive} 冷存储）。
 * 本类集中：L2 type 四类常量、source 来源常量、会话摘要子类型（context_json subtype）判定、
 * 旧 kind → 新 type 迁移映射。</p>
 * <p>设计依据：《记忆系统分层重构 L1/L2/L3/归档 设计方案》§6（L2 重定义四类）/ §12（一次性迁移）。</p>
 */
public final class ActorMemoryTypes {

    // ==================== L2 type 四类（取代旧 kind 体系） ====================

    /** 情节（事件/经历，单次学习，context_json 绑定 who/when/where/why）——旧 observation */
    public static final String TYPE_EPISODIC = "episodic";

    /** 语义（事实/知识/会话摘要）——旧 fact/summary */
    public static final String TYPE_SEMANTIC = "semantic";

    /** 反思（洞察 + 证据指针 evidence_ids，可版本化）——旧 reflection */
    public static final String TYPE_REFLECTION = "reflection";

    /** 计划（目标/日计划/行动序列，context_json 结构化）——旧 plan */
    public static final String TYPE_PLAN = "plan";

    // ==================== context_json 会话摘要子类型（type=semantic 内区分事实/摘要） ====================

    /** context_json 子类型键 */
    public static final String CONTEXT_KEY_SUBTYPE = "subtype";

    /** 会话摘要子类型值 */
    public static final String SUBTYPE_SUMMARY = "summary";

    /** 会话摘要 context_json 片段（LIKE/contains 判定用，含引号防误匹配） */
    public static final String CONTEXT_SUBTYPE_SUMMARY_FRAGMENT = "\"subtype\":\"summary\"";

    /** 会话摘要完整 context_json */
    public static final String CONTEXT_SUMMARY_JSON = "{" + CONTEXT_SUBTYPE_SUMMARY_FRAGMENT + "}";

    // ==================== source 写入来源 ====================

    public static final String SOURCE_DIALOG = "dialog";
    public static final String SOURCE_ACTION = "action";
    public static final String SOURCE_EVENT = "event";
    public static final String SOURCE_PLANNING = "planning";
    public static final String SOURCE_REFLECTION = "reflection";
    public static final String SOURCE_CONSOLIDATION = "consolidation";
    public static final String SOURCE_IMPORT = "import";
    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_PERCEPTION = "perception";

    private ActorMemoryTypes() {
    }

    /**
     * 是否会话摘要：type=semantic 且 context_json 含 {@code {"subtype":"summary"}} 片段。
     *
     * @param type 记忆类型（可空）
     *
     * @param contextJson 上下文 JSON（可空）
     *
     * @return true 表示会话摘要（供「摘要查询/标签」与事实区分）
     */
    public static boolean isSummary(String type, String contextJson) {
        return TYPE_SEMANTIC.equals(type)
                && contextJson != null
                && contextJson.contains(CONTEXT_SUBTYPE_SUMMARY_FRAGMENT);
    }

    /**
     * 旧 kind → 新 type 映射。
     * <p>映射：observation→episodic；reflection→reflection；plan→plan；
     * fact/summary 及未知 → semantic（会话摘要经 context_json subtype=summary 与事实区分）。</p>
     *
     * @param kind 旧 kind 值（可空）
     *
     * @return 新 type 值（永不为 null）
     */
    public static String mapOldKindToType(String kind) {
        if (kind == null) {
            return TYPE_SEMANTIC;
        }
        return switch (kind) {
            case "observation" -> TYPE_EPISODIC;
            case "reflection" -> TYPE_REFLECTION;
            case "plan" -> TYPE_PLAN;
            default -> TYPE_SEMANTIC;
        };
    }
}
