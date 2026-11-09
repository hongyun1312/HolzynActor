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

package com.holzyn.actor.domain.world.adjudicate.model;

/**
 * 行动意图闭集（§4.7）。
 *
 * <p>只由确定性 {@code IntentClassifier} 从 action_type + action_detail 归类；
 * 未知一律 {@link #OTHER}，保证 AI 新词不会卡死世界（R-DEFAULT fail-open）。</p>
 */
public enum IntentKind {

    /** 移动/前往 */
    TRAVEL,

    /** 交谈/搭话 */
    TALK,

    /** 获取（买/取/采/借） */
    ACQUIRE,

    /** 给予（给/送/还） */
    GIVE,

    /** 交易（卖/交换/议价） */
    TRADE,

    /** 制作/烹饪/打造 */
    CRAFT,

    /** 使用/操作设施或物品 */
    USE,

    /** 工作/巡逻/耕种/采矿 */
    WORK,

    /** 攻击/战斗 */
    ATTACK,

    /** 修理/修复 */
    REPAIR,

    /** 穿/戴/拿起（装备槽） */
    EQUIP,

    /** 休息/进食/睡眠 */
    REST,

    /** 其他/不裁决 */
    OTHER
}
