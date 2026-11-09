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

/**
 * 事实小时 sweep 的扩展钩子（预留：物品腐坏/库存变质等由物品域实现）。
 *
 * <p>当前没有物品域，因此没有实现；{@link FactSweepService} 用可选列表调用，
 * 物品域接入后只需注册一个 Bean，不必再改世界引擎。</p>
 */
public interface FactDecayHook {

    /**
     * 每游戏小时调用一次（零 AI；实现方自行保证幂等与异常隔离）。
     *
     * @param projectId  项目/世界 ID
     * @param gameSecond 当前游戏秒（整点）
     */
    void onHour(Long projectId, long gameSecond);
}
