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
 * 世界事件订阅者（B1 世界事件总线旁路消费接口）。
 * <p>注册方（如调试前端事件流 {@code DebugEventHub}）实现本接口，经
 * {@link WorldEventBus#subscribe(WorldEventSubscriber)} 注册；每次 publish 后回调
 * {@link #onEvent(ActorEvent)}，异常由总线捕获不影响主广播。</p>
 */
@FunctionalInterface
public interface WorldEventSubscriber {

    /**
     * 收到已发布的世界事件。
     *
     * @param event 已落库的事件实体
     */
    void onEvent(ActorEvent event);
}
