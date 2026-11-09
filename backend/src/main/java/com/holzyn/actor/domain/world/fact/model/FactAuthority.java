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
 * 事实权威归属（§4.2/§4.4.2）。
 *
 * <p>{@link #BRAIN}：「看不见的」语义权威，永远由后端写；桌面端全部事实都是 BRAIN。</p>
 * <p>{@link #ENGINE}：「看得见的」表现层权威，只有接引擎且模式为 ENGINE 时才允许
 * 由镜像服务写（E1）；后端服务在 ENGINE 模式下不得越权写这类事实。</p>
 */
public enum FactAuthority {
    BRAIN,
    ENGINE
}
