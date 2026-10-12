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

package com.holzyn.actor.domain.agent.event;

import com.holzyn.actor.domain.world.entity.ActorEvent;

import java.util.List;

/**
 * 世界事件总线（，domain/agent/event）——自治环通信主干。
 * <p>目标：一切「世界发生了什么」（行动/居民活动/世界事件/
 * 演化归档/系统节点）统一经本总线落 {@code actor_event} + 广播；写一次、多方读。</p>
 * <p>广播通道：① 前端 SSE（{@link WorldEventSseHub}，/api/projects/{id}/world-events/stream）；
 * ② 引擎 WS 预留；③ 订阅者（{@link WorldEventSubscriber}，
 * 调试前端事件流 DebugEventHub 注册为订阅者——调试事件流与业务事件流同源，消 -5 分叉）。</p>
 * <p>依赖方向约定（R1）：agent 域单向依赖既有域（读世界时钟/事件仓库）；既有域的行动/事件产出经
 * 「事件发布」这一个入口回调本总线（Spring 注入解耦防环，仅限 publish）。</p>
 */
public interface WorldEventBus {

    /**
     * 发布事件：落 actor_event（project_id+source_key 幂等去重）→ 滚动保护（每项目最近 N 条）→
     * 广播（前端 SSE + 引擎 WS 预留 + 订阅者）。
     * <p>开关 {@code holzyn.actor.agent.events-enabled}（默认 true）：false 时返回 null 不落库不广播，
     * 调用方（行动/人群/事件注入）回到 B1 前行为；需要保底的演化归档/手动事件由调用方直写兜底。</p>
     *
     * @param userId 归属用户 ID（AI/用量等凭据归属，本总线仅透传）
     *
     * @param projectId 项目 ID
     *
     * @param spec 发布规格（kind/source/角色/地点/可见性/载荷/游戏时刻/幂等键）
     *
     * @return 已发布（或幂等命中）的事件实体；开关关闭/入参非法返回 null
     */
    ActorEvent publish(Long userId, Long projectId, EventSpec spec);

    /**
     * 查询事件：按项目 + 类别/来源/地点/角色 ref（可见性过滤）/游戏时间窗。
     * <p>感知 / 时间线 / 前端 / 引擎共用同一查询口径。</p>
     *
     * @param projectId 项目 ID
     *
     * @param query 查询条件（可空=最近 100 条）
     *
     * @return 事件列表（按 id 倒序，limit 截断）
     */
    List<ActorEvent> query(Long projectId, EventQuery query);

    /**
     * 注册事件订阅者（旁路消费，如调试前端事件流 DebugEventHub；发布后回调，异常不阻断广播）。
     *
     * @param subscriber 订阅者
     */
    void subscribe(WorldEventSubscriber subscriber);
}
