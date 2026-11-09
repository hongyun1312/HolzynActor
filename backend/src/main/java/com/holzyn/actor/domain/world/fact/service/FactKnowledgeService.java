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

/**
 * 事实认知写入口（{@code actor_world_fact_knowledge} 的唯一写者）。
 *
 * <p>认知永远归后端（双形态权威的边界）：引擎可以告诉后端「门开着」，
 * 但不能决定「谁知道门开着」。</p>
 */
public interface FactKnowledgeService {

    /**
     * 记录/更新某人知道某条事实（同 fact+knower 幂等）。
     *
     * @param projectId     项目/世界 ID
     *
     * @param factId        事实行 ID
     *
     * @param knowerRef     知情者 ref
     *
     * @param sourceType    own / perceive / told / rumor / infer
     *
     * @param sourceRef     来源 ref（谁说的/哪个包）
     *
     * @param confidence    可信度 0~100
     *
     * @param distortionJson 失真 JSON
     */
    void learn(Long projectId, Long factId, String knowerRef, String sourceType,
               String sourceRef, int confidence, String distortionJson);

    /**
     * 同上，并记录获知时刻（感知回流；用于 digest 按新鲜度排序）。
     *
     * @param gameSecond 获知时的游戏秒（可空=沿用旧值/不记录）
     */
    void learnAt(Long projectId, Long factId, String knowerRef, String sourceType,
                 String sourceRef, int confidence, String distortionJson, Long gameSecond);

    /**
     * 事实变更时向同地点在场者广播（除 exceptRef）。
     *
     * <p>仍以 {@code actor_profile.location} 做同地点判定（与同源、可测）；
     * 后续接入 {@code actor_npc_position} 坐标精度时只换内部查询，签名不变。</p>
     *
     * @param projectId    项目/世界 ID
     *
     * @param fact         变化后的事实
     *
     * @param locationName 地点名（空=不广播）
     *
     * @param exceptRef    排除的 ref（通常是当事人自己）
     *
     * @param gameSecond   当前游戏秒
     *
     * @return 新增/更新认知的行数
     */
    int broadcastPerceive(Long projectId, ActorWorldFact fact, String locationName,
                          String exceptRef, long gameSecond);
}
