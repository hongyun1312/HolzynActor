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

package com.holzyn.actor.domain.mapnav.vo;

import lombok.Data;

/**
 * 一个角色的「空间定值」——三方对账的统一载体。
 *
 * <p><b>为什么单独建一个 VO 而不是直接返回实体</b>：三腿（权威表 / 沙盘回退 / 注入文本）需要
 * <b>同一份字段集</b>才可能逐位比对。实体字段各不相同（{@code posX/posZ} vs {@code x/z}），
 * 直接在比对处做字段映射，映射本身就会成为新的分歧源。这里只留「对账真正要比的六个字段」。</p>
 *
 * <p><b>{@code source} 为什么必须存在</b>：沙盘回退是兼容路径，一旦隐性命中，「图上有人、权威没行」
 * 就永远查不出来。把它变成字段，才能被计数、被断言「回退数 = 0」。</p>
 */
@Data
public class SpatialFix {

    /** 来源：{@code authority}（{@code actor_npc_position}）/ {@code sandbox}（{@code actor_sandbox_state} 回退） */
    public static final String SOURCE_AUTHORITY = "authority";

    /** 来源：沙盘回退 */
    public static final String SOURCE_SANDBOX = "sandbox";

    /** 角色引用（{@code npc:{id}}） */
    private String npcRef;

    /** 角色名（可空） */
    private String npcName;

    /** 地图坐标 X（米，原点西南角，向东为正） */
    private double x;

    /** 地图坐标 Z（米，向南为正） */
    private double z;

    /** 朝向（度，0=北，顺时针） */
    private double heading;

    /** 所在要素 code（建筑/地点），可空 */
    private String featureCode;

    /** 对应游戏秒（引擎推进秒） */
    private long gameSecond;

    /**
     * 权威行自带的「最后变更游戏秒」。
     *
     * <p><b>为什么和 {@link #gameSecond} 分开</b>：静止角色的行只在「到点/换段」时被写过，
     * 之后几百个 tick 都不会再写——如果拿行的秒当「此刻」，静态角色会被判成永远滞后。
     * 位置的语义是「自最后一次变更起一直有效」，所以对外的 {@code gameSecond} 取引擎推进秒，
     * 行内秒只作为 {@code lastChangedGameSecond} 保留（它才是「这次移动发生在什么时候」）。</p>
     */
    private long lastChangedGameSecond;

    /** 状态：static / walking / working（权威口径） */
    private String status;

    /** 当前活动（自然语言） */
    private String activity;

    /** 是否室内 */
    private boolean indoor;

    /** 来源（{@link #SOURCE_AUTHORITY} / {@link #SOURCE_SANDBOX}） */
    private String source;
}
