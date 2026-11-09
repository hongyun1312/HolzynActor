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

package com.holzyn.actor.domain.world.effect.model;

/**
 * 效果落地上下文（§4.5）。
 *
 * <p>{@code eventId} 是事实日志的因果挂点，事实类效果要求非空。
 * {@code planId} 用于生成稳定的默认幂等键 {@code plan:{planId}:op:{i}}；
 * 对话抽取会显式传入 {@code dialog:{msgId}:op:{i}}。</p>
 *
 * @param projectId   项目/世界 ID
 * @param eventId     触发本次效果的事件 ID（事实/信息包必须非空）
 * @param correlationId 因果链 correlation（可空）
 * @param actorRef    触发角色 ref（可空）
 * @param planId      行动计划 ID（可空）
 * @param locationName 地点名（可空）
 * @param gameSecond  当前游戏秒
 */
public record EffectContext(
        Long projectId, Long eventId, String correlationId,
        String actorRef, Long planId, String locationName, long gameSecond) {
}
