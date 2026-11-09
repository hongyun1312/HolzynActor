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

package com.holzyn.actor.domain.world.fact.model;

/**
 * 事实写入命令（§4.5）。
 *
 * <p>{@code idempotencyKey} 为空=不做幂等；非空时同项目内重复命令直接返回已有事实，
 * 不重复写日志。{@code sourceEventId} 是因果链的挂点，要求日志里非空。</p>
 *
 * @param projectId           项目/世界 ID
 * @param factType            事实类型（闭集，见 {@link FactTypes}）
 * @param factKey             稳定键
 * @param name                展示名
 * @param stateText           一句话当前状态
 * @param attributesJson      结构化属性 JSON
 * @param locationId          地点 ID
 * @param locationName        地点名
 * @param visibility          public / location / private（空=按类型缺省）
 * @param authority           brain / engine（空=brain）
 * @param validFromGameSecond 生效起点
 * @param validToGameSecond   失效终点
 * @param gameSecond          本次变更的游戏秒
 * @param actorRef            触发变更的角色 ref
 * @param sourceEventId       来源事件 ID
 * @param importance          重要度（1~5；供事件/记忆沉淀，事实表不存）
 * @param idempotencyKey      幂等键
 */
public record FactCommand(
        Long projectId, String factType, String factKey, String name, String stateText,
        String attributesJson, Long locationId, String locationName,
        String visibility, FactAuthority authority,
        Long validFromGameSecond, Long validToGameSecond,
        Long gameSecond, String actorRef, Long sourceEventId,
        Integer importance, String idempotencyKey) {
}
