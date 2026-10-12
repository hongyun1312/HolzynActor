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

package com.holzyn.actor.domain.worldgen.model;

import java.util.List;
import java.util.Map;

/**
 * 世界观生成维度常量与映射。
 * <p>职责：集中维护 6 维世界观构建的维度键（core_laws/space_time/society/history_culture/
 * existence/information）、链式顺序、中文标签，以及 build 工作流 Agent 语义名
 * （agents.json 的 agentType，如 {@code novel-worldbuilder-corelaws}）到维度键的映射——
 * 真实 Agent 执行器（Schema 校验维度键）与 6 维映射落库服务（分节组装）共用，避免口径分裂。</p>
 * <p>说明：Agent 语义名末段为「corelaws/spacetime/historyculture」（无下划线），而产物维度键
 * 带下划线（core_laws/space_time/history_culture），不可用字符串切分推导，必须显式映射。</p>
 */
public final class WorldGenDimensions {

    private WorldGenDimensions() {
    }

    /** 6 维链式生成顺序 */
    public static final List<String> ORDER = List.of(
            "core_laws", "space_time", "society", "history_culture", "existence", "information");

    /** 维度键 → 中文标签（前端预览/日志展示；LinkedHashMap 保证链式生成顺序，勿用 Map.of 无序） */
    public static final Map<String, String> LABELS = new java.util.LinkedHashMap<>();
    static {
        LABELS.put("core_laws", "核心法则");
        LABELS.put("space_time", "时空地理");
        LABELS.put("society", "社会权力");
        LABELS.put("history_culture", "历史文化");
        LABELS.put("existence", "存在基础");
        LABELS.put("information", "信息传播");
    }

    /** build 工作流 Agent 语义名 → 维度键（显式映射，见类注释） */
    public static final Map<String, String> AGENT_TYPE_TO_DIM_KEY = Map.of(
            "novel-worldbuilder-corelaws", "core_laws",
            "novel-worldbuilder-spacetime", "space_time",
            "novel-worldbuilder-society", "society",
            "novel-worldbuilder-historyculture", "history_culture",
            "novel-worldbuilder-existence", "existence",
            "novel-worldbuilder-information", "information");

    /**
     * 由 Agent 语义名解析维度键（未命中返回 null）。
     *
     * @param agentType Agent 语义名（如 novel-worldbuilder-corelaws）
     *
     * @return 维度键（core_laws/...）或 null
     */
    public static String dimKeyOf(String agentType) {
        if (agentType == null) {
            return null;
        }
        return AGENT_TYPE_TO_DIM_KEY.get(agentType);
    }

    /**
     * 判断维度键是否属于 6 维构建集。
     *
     * @param dimKey 维度键
     *
     * @return true=是
     */
    public static boolean isBuildDimension(String dimKey) {
        return LABELS.containsKey(dimKey);
    }
}
