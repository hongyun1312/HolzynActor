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

import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;

/**
 * 一次事实变更的结果（§4.5）。
 *
 * @param fact        变更后的事实行
 * @param fromVersion 变更前版本（新建=0）
 * @param toVersion   变更后版本
 * @param op          create / update / expire / void / duplicate（幂等命中）
 */
public record FactChange(ActorWorldFact fact, int fromVersion, int toVersion, String op) {
}
