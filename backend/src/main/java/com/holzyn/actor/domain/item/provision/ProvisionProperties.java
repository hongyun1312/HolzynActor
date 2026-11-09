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

package com.holzyn.actor.domain.item.provision;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 开局供给配置（前缀 {@code holzyn.actor.provision}）。
 *
 * <p><b>为什么默认 true 却仍然安全</b>：本段从属于 {@code holzyn.actor.item.enabled}——
 * 物品域关闭时 {@code InventoryPort} 不装配、持有栈也不该有行，本服务直接 no-op。
 * 换句话说「默认开」只对已经开了物品域的世界生效，对基线世界是零行为。</p>
 *
 * <p>分项开关的意义是<b>可回滚</b>：某个世界如果觉得「一开局所有人都有东西」太满，
 * 可以只关 {@code npc-kit} 保留货架，或反过来。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "holzyn.actor.provision")
public class ProvisionProperties {

    /** 供给总开关（仍需 {@code item.enabled=true} 才真的写库） */
    private boolean enabled = true;

    /** 是否给 NPC 发随身物品（职业家伙 + 口粮 + 衣物） */
    private boolean npcKit = true;

    /** 是否给「像店铺的地点」铺货 */
    private boolean locationStock = true;

    /** 是否给玩家发开局随身物品 */
    private boolean playerKit = true;

    /** 发下去的衣物是否顺手穿在身上（false=只放进背包） */
    private boolean autoWear = true;

    /** 单次供给最多处理多少个持有者（防病态世界把开世界拖住） */
    private int maxHolders = 500;
}
