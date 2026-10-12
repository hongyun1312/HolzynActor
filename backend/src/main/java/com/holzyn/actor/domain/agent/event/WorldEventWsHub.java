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

/**
 * 引擎 WebSocket 事件推送接口占位。
 * <p>目标形态：引擎 {@code GET /ws/world/{projectId}} 订阅世界事件总线事件，
 * 与前端 SSE 同源（去重由订阅方按 eventId 幂等）。本期仅定义接口 + 空实现
 * （{@link NoopWorldEventWsHub}），由 {@link WorldEventBusImpl} 在发布后回调，保证
 * 「广播三通道（SSE/WS/订阅者）」在 落地前骨架完整。</p>
 */
public interface WorldEventWsHub {

    /**
     * 推送一条世界事件到引擎 WS 订阅者。
     *
     * @param event 已落库的事件实体
     */
    void publishWorldEvent(ActorEvent event);
}
