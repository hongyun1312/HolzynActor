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

package com.holzyn.actor.domain.agent.perception;

import java.util.ArrayList;
import java.util.List;

/**
 * 感知上下文文本渲染（B2 感知组件，domain/agent/perception）。
 * <p>把 {@link PerceptionVO} 渲染为注入 Prompt 的【当前所见】文本——纯静态逻辑可单测。
 * 格式：<pre>
 * 【当前所见】现在是{世界时间}，你在{地点}，正在{状态}。
 * 最近你知晓的世界动态：
 * - {事件}
 * ...
 * 你的人际关系：
 * - 与{某人}：{关系类型}（{描述}）
 * 【当前目标】- {目标}（注入）
 * 【今日计划】- 当前（{时}时）：{动作}（注入）
 * 【情境知识】你知晓的相关世界知识：
 * - {知识标题}：{片段}（注入）
 * </pre>
 * 各分节为空时自动跳过；无任何可注入内容时返回空串（调用方跳过注入，保持旧行为）。</p>
 */
public final class PerceptionText {

    private PerceptionText() {
    }

    /**
     * 渲染感知文本（可注入 system 消息）。
     *
     * @param vo 感知视图（可空）
     *
     * @return 文本；无任何可注入内容返回空串
     */
    public static String render(PerceptionVO vo) {
        if (vo == null) {
            return "";
        }
        List<String> bits = new ArrayList<>();
        if (notBlank(vo.gameTimeText())) {
            bits.add("现在是" + vo.gameTimeText());
        }
        boolean hasLocation = notBlank(vo.locationName());
        boolean hasState = notBlank(vo.ownState());
        if (hasLocation && hasState) {
            bits.add("你在" + vo.locationName() + "，正在" + vo.ownState());
        } else if (hasLocation) {
            bits.add("你身处" + vo.locationName());
        } else if (hasState) {
            bits.add("你正在" + vo.ownState());
        }
        List<String> events = vo.visibleEvents() == null ? List.of() : vo.visibleEvents();
        List<String> relations = vo.relationSummary() == null ? List.of() : vo.relationSummary();
        List<String> knowledge = vo.knowledgeHints() == null ? List.of() : vo.knowledgeHints();
        boolean hasGoals = notBlank(vo.goalsSummary());
        boolean hasPlan = notBlank(vo.planSummary());
        if (bits.isEmpty() && events.isEmpty() && relations.isEmpty() && knowledge.isEmpty()
                && !hasGoals && !hasPlan) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【当前所见】");
        sb.append(String.join("，", bits)).append("。");
        if (!events.isEmpty()) {
            sb.append("\n最近你知晓的世界动态：");
            for (String e : events) {
                sb.append("\n- ").append(e);
            }
        }
        if (!relations.isEmpty()) {
            sb.append("\n你的人际关系：");
            for (String r : relations) {
                sb.append("\n- ").append(r);
            }
        }
        if (hasGoals) {
            sb.append("\n").append(vo.goalsSummary());
        }
        if (hasPlan) {
            sb.append("\n").append(vo.planSummary());
        }
        // ：情境知识片段（决策不违反世界规则；无命中自动跳过）
        if (!knowledge.isEmpty()) {
            sb.append("\n你知晓的相关世界知识：");
            for (String k : knowledge) {
                sb.append("\n- ").append(k);
            }
        }
        return sb.toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
