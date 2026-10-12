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

import java.util.List;

/**
 * 感知上下文视图（B2 感知组件，domain/agent/perception）。
 * <p>为一切「以角色视角」的认知（思考/对话/行动/演化）提供当前世界状态的视角化视图——
 * 对齐系统框架 §4.1 与闭环评估 §7.2：游戏时间 / 所在地 / 自身状态 / 可见事件（≤5，经事件总线
 * visibility 过滤）/ 关系摘要。 基础版不含在场者聚合与记忆提示（在场者留 在场登记，记忆由既有
 * MemoryService.memoryContext 独立注入，避免重复）。</p>
 * <p>新增 goalsSummary（【当前目标】active 目标 top2）与 planSummary（【今日计划】当前时段），
 * 使行动决策/对话有方向性（对齐 Smallville 层级规划）；开关 holzyn.actor.perception.goals-enabled
 * （默认 true）控制注入。</p>
 * <p>新增 knowledgeHints（【情境知识】相关世界知识片段 ≤3，复用 KnowledgeRetrievalService，
 * 无 embedding 降级文本；开关 holzyn.actor.perception.knowledge-enabled 默认 true）——决策不违反世界规则
 * 。</p>
 * <p>characterRef 统一角色引用：special:{id} / crowd:{id}（双轨）。</p>
 */
public record PerceptionVO(
        /** 当前游戏时刻（小时数，自纪元起；统一时间口径） */
        Long gameHour,
        /** 世界时间文本（到分，如「罗小黑战记历 0001年01月19日 20时00分」） */
        String gameTimeText,
        /** 所在地点（角色/居民自由文本位置快照； 归一后为地点表规范名快照） */
        String locationName,
        /** 自身状态（特殊：current_activity；普通：state + last_action） */
        String ownState,
        /** 可见近期事件文本（≤5，按事件总线 query 的 visibility 过滤） */
        List<String> visibleEvents,
        /** 关系摘要（该角色参与的社会关系，≤5 条） */
        List<String> relationSummary,
        /** 当前目标摘要（：【当前目标】active 目标 top2；crowd 恒空串） */
        String goalsSummary,
        /** 今日计划摘要（：【今日计划】当前时段条目；无计划空串） */
        String planSummary,
        /** 情境知识片段（：【情境知识】相关世界知识 ≤3，无命中空列表） */
        List<String> knowledgeHints) {
}
