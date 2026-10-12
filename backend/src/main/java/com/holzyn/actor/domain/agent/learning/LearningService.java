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

package com.holzyn.actor.domain.agent.learning;

/**
 * 学习组件接口（阶段 C 骨架，domain/agent/learning）。
 * <p>职责：从经历中<b>改变</b>——</p>
 * <ul>
 * <li><b>反思巩固</b>：{@link #reflect}——综合重要度阈值触发，洞察+证据指针写记忆流（kind=reflection）；
 * 反思节奏上限（R4：每角色每游戏日 ≤2 次，防自强化叙事失控）；</li>
 * <li><b>动态关系/好感</b>：{@link #updateRelation}——互动（对话/行动/事件）增减 affinity/trust，
 * 关系状态机 + 发布变迁事件（拓扑实时反映，修 L-12/）；</li>
 * <li><b>行为偏好</b>：{@link #updatePreference}——同类行为频次→偏好/熟练度（习惯化）；</li>
 * <li><b>世界/生态级学习</b>：{@link #snapshotEcosystem}——群体分布/组织变迁/地点兴衰 →
 * actor_world_ecosystem + 项目级记忆；</li>
 * <li><b>周期巩固</b>：{@link #scheduleConsolidation}——由 WorldEngine 周期调用以上各项
 * （反思/关系/生态，低优先级异步）。</li>
 * </ul>
 * <p><b>阶段为骨架</b>：接口声明 + {@link LearningServiceSkeleton} 空实现（log + trace 记录），
 * 实现留阶段 D（动态关系 / 反思）。AgentRuntime 学习步骤与 WorldEngine
 * scheduleConsolidation 钩子经本接口接入，切到 D 阶段真实实现时调用方零改动。</p>
 */
public interface LearningService {

    /**
     * 反思：从近期记忆生成洞察（证据指针写记忆流 kind=reflection）。
     *
     * @param characterRef 统一角色引用（special:{id}/crowd:{id}）
     *
     * @param gameHour 当前游戏小时（时间口径=游戏时间，禁止真实时间进决策上下文）
     */
    void reflect(String characterRef, Long gameHour);

    /**
     * 动态关系/好感更新：互动增减 affinity/trust + 关系状态机 + 发布变迁事件。
     *
     * @param projectId 项目 ID
     *
     * @param fromRef 发起方统一角色引用
     *
     * @param toRef 目标方统一角色引用
     *
     * @param delta 好感增量（-100~100）
     *
     * @param reason 变更原因（对话/行动/事件摘要）
     */
    void updateRelation(Long projectId, String fromRef, String toRef, int delta, String reason);

    /**
     * 行为偏好更新：同类行为频次→偏好/熟练度（习惯化）。
     *
     * @param characterRef 统一角色引用
     *
     * @param behaviorKey 行为键（如 move/interact/trade…）
     *
     * @param delta 频次增量（默认 1）
     */
    void updatePreference(String characterRef, String behaviorKey, int delta);

    /**
     * 生态快照：周期聚合统计（群体分布/组织变迁/地点兴衰）→ 生态快照 + 项目级记忆。
     *
     * @param projectId 项目 ID
     *
     * @param gameHour 当前游戏小时
     */
    void snapshotEcosystem(Long projectId, Long gameHour);

    /**
     * 周期巩固入口（由 WorldEngine 每跨 K 游戏小时调用，低优先级异步）：
     * 反思/关系/偏好/生态的调度入口，实现留阶段 D。
     *
     * @param projectId 项目 ID
     *
     * @param gameHour 当前游戏小时
     */
    void scheduleConsolidation(Long projectId, Long gameHour);
}
