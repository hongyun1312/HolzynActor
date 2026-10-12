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

/**
 * 世界事件查询条件（B1 世界事件总线，domain/agent/event）。
 * <p>供感知 / 时间线 / 前端 / 引擎共用 {@link WorldEventBus#query(Long, EventQuery)}：
 * 按项目 + 类别 + 来源 + 地点 + 角色 ref（可见性过滤） + 游戏时间窗过滤；limit 截断。</p>
 * <p>可见性过滤语义：<ul>
 * <li>不传 characterRef：返回全部（跳过可见性过滤）；</li>
 * <li>传 characterRef：global 全可见；private 仅 character_ids 含该 ref 的当事人可见；
 * location 对当事人或（带 locationId 时）同地点在场者可见。</li></ul></p>
 */
public record EventQuery(String kind, String source, Long locationId, String characterRef,
                         Long fromGameHour, Long toGameHour, int limit) {

    /**
     * 最近事件查询（不过滤，limit 截断）。
     *
     * @param limit 条数上限（>0）
     *
     * @return 查询条件
     */
    public static EventQuery recent(int limit) {
        return new EventQuery(null, null, null, null, null, null, limit);
    }

    /**
     * 指定角色视角的近期可见事件（感知组件 使用，B2 落地）。
     *
     * @param locationId 当前所在地点 ID（可空，用于 location 可见性在场匹配）
     *
     * @param sinceGameHour 起始游戏小时（可空）
     *
     * @param limit 条数上限
     *
     * @return 查询条件
     */
    public static EventQuery forCharacter(String characterRef, Long locationId, Long sinceGameHour, int limit) {
        return new EventQuery(null, null, locationId, characterRef, sinceGameHour, null, limit);
    }
}
