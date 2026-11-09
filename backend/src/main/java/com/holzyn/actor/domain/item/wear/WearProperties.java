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

package com.holzyn.actor.domain.item.wear;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 损耗域配置（前缀 {@code holzyn.actor.wear}，§4.10）。
 *
 * <p><b>默认关</b>：{@code enabled=false} 时没有腐坏 tick、不解析装备、不磨损，
 * {@code R-ATTACK-01} 走 「身上有武器即算」旧路径——行为与逐字节一致。
 * 桌面端验证用 {@code HOLOZYN_ACTOR_WEAR_ENABLED=true} 打开（同时需要
 * {@code HOLOZYN_ACTOR_ITEM_ENABLED=true}）。</p>
 *
 * <p>env 覆盖：yml 已写显式占位符，按占位符名精确匹配。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "holzyn.actor.wear")
public class WearProperties {

    /** 损耗域总开关（耐久 + 腐坏 + 装备） */
    private boolean enabled = false;

    /** 每次挥砍扣多少耐久（唯一的磨损来源：打一次磨一次） */
    private int attackWear = 2;

    /** 成色低于该值视为卷刃（还能打，但打不狠 → PARTIAL） */
    private int bluntThreshold = 25;

    /** 一次修理恢复多少耐久 */
    private int repairPoints = 25;

    /** 满成色时一击的基准伤害 */
    private int baseDamage = 8;

    /**
     * 护具（body 槽）最多吃掉多少伤害（0–1；按成色折算）。
     *
     * <p>0.4 = 穿一件全新的粗布衣挡掉四成；穿到快烂了只挡一点点。
     * 设 0 等于「衣服只影响观感、不影响挨打」，那是上一版的状态。</p>
     */
    private double armorReduction = 0.4;

    /** 装备变化是否同步 {@code equip:*} 事实（让旁人「看见他腰间别着刀」） */
    private boolean equipFactSync = true;

    /** 装备事实的可见性（location=只有到场的人知道；public=全镇都知道） */
    private String equipVisibility = "location";
}
