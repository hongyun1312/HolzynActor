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

package com.holzyn.actor.domain.world.fact.service;

import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;

import java.util.List;
import java.util.Optional;

/**
 * 事实派生读模型（零写入，§4.5）。
 *
 * <p>{@link #knownBy} 只返回「该 ref 知道的事实」，不是全项目事实。
 * 全量视图只给台账/调试，不给决策。</p>
 */
public interface WorldFactQuery {

    /**
     * 某角色知道的事实（按获知时间倒序，过滤已失效/未来生效）。
     *
     * @param projectId 项目/世界 ID
     *
     * @param ref       知情者 ref（npc:{id}）
     *
     * @param gameSecond 当前游戏秒（用于有效期过滤）
     *
     * @param limit     最多返回条数
     *
     * @return 事实列表
     */
    List<ActorWorldFact> knownBy(Long projectId, String ref, long gameSecond, int limit);

    /**
     * 某地点当前有效的事实。
     *
     * @param projectId    项目/世界 ID
     *
     * @param locationName 地点名
     *
     * @param gameSecond   当前游戏秒
     *
     * @return 事实列表
     */
    List<ActorWorldFact> atLocation(Long projectId, String locationName, long gameSecond);

    /**
     * 按类型查事实。
     *
     * @param projectId 项目/世界 ID
     *
     * @param factType  类型
     *
     * @param status    active / expired / void（空=active）
     *
     * @param limit     最多条数
     *
     * @return 事实列表
     */
    List<ActorWorldFact> byType(Long projectId, String factType, String status, int limit);

    /**
     * 按键查事实（含已失效，供排障/幂等判断）。
     *
     * @param projectId 项目/世界 ID
     *
     * @param factKey   稳定键
     *
     * @return 事实（无则空）
     */
    Optional<ActorWorldFact> byKey(Long projectId, String factKey);

    /**
     * 某 ref 是否知道某条事实。
     *
     * @param projectId 项目/世界 ID
     *
     * @param factKey   稳定键
     *
     * @param ref       知情者 ref
     *
     * @return true=知道
     */
    boolean knows(Long projectId, String factKey, String ref);

    /**
     * 决策提示词用的稳定摘要（格式逐字节固定；同一 gameSecond 内可复现）。
     *
     * @param projectId  项目/世界 ID
     *
     * @param ref        角色 ref
     *
     * @param gameSecond 当前游戏秒
     *
     * @param maxLines   最多条数（超出只留计数）
     *
     * @return 摘要文本（无事实返回空串）
     */
    String digestForPrompt(Long projectId, String ref, long gameSecond, int maxLines);

    /**
     * 该角色是否知道一条将在窗口内到期的事实（决策让路信号）。
     *
     * <p>契约：只读、只使用 knowledge 行，不把全项目事实暴露给角色。判定口径为
     * {@code validTo != null && gameSecond <= validTo <= gameSecond + windowSeconds}。
     * 由于契约/路线等可结算事实的 {@code validTo} 就是到期时刻，本方法不解析
     * {@code attributesJson}，避免对属性格式产生第二套依赖。</p>
     *
     * @param projectId     项目/世界 ID
     *
     * @param ref           角色 ref
     *
     * @param gameSecond    当前游戏秒
     *
     * @param windowSeconds 到期窗口（秒；&lt;=0 返回 false）
     *
     * @return true=存在窗口内到期且角色知道的事实
     */
    boolean hasFactDueWithin(Long projectId, String ref, long gameSecond, long windowSeconds);

    /**
     * 取该角色知道的、将在窗口内到期的事实中<b>最近到期</b>的一条（决策因果挂接用）。
     *
     * <p>与 {@link #hasFactDueWithin} 同一口径：只读 knowledge、只看 {@code validTo}，
     * 按剩余秒数升序取第一条；无命中/参数非法返回 null。调用方用它把 NPC 的决策/行动
     * 事件挂到该事实的来源事件上，形成「对话/承诺 → NPC 决策 → 行动」的因果边。</p>
     *
     * @param projectId     项目/世界 ID
     *
     * @param ref           角色 ref
     *
     * @param gameSecond    当前游戏秒
     *
     * @param windowSeconds 到期窗口（秒；&lt;=0 返回 null）
     *
     * @return 最近到期的事实；无命中返回 null
     */
    ActorWorldFact nearestDueWithin(Long projectId, String ref, long gameSecond, long windowSeconds);

    /**
     * 事实层最大版本（stateVersion 的组成部分）。
     *
     * @param projectId 项目/世界 ID
     *
     * @return 最大版本；无事实返回 0
     */
    long maxVersion(Long projectId);

    /**
     * 世界状态版本串（固定格式，供决策输入留证）。
     *
     * @param projectId  项目/世界 ID
     *
     * @param gameSecond 当前游戏秒
     *
     * @return 形如 {@code gs123/v5}
     */
    String stateVersion(Long projectId, long gameSecond);
}
