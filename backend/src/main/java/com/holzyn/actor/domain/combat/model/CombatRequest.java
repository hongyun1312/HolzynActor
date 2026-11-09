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

package com.holzyn.actor.domain.combat.model;

/**
 * 一次攻击的请求。
 *
 * @param projectId      项目 ID
 * @param attackerRef    攻击者 ref
 * @param targetRef      目标 ref
 * @param weaponItemKey  指定武器（null=用手上/身上最好的那把）
 * @param gameSecond     游戏秒
 * @param eventId        来源事件
 * @param idempotencyKey 幂等键（重放不重复扣血、不重复磨损）
 */
public record CombatRequest(Long projectId, String attackerRef, String targetRef, String weaponItemKey,
                            long gameSecond, Long eventId, String idempotencyKey) {
}
