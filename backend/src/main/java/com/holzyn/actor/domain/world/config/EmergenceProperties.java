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

package com.holzyn.actor.domain.world.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 宏观社会演化 · 二阶涌现检测配置。
 * <p>职责：二阶涌现检测（MacroEmergenceService）可调参数——总开关（enabled=false 整体关闭=
 * 回滚）、最小成员数（默认 3）、时间窗（默认 1 游戏日）、冷却（默认 5 日防重复误报）、
 * 项目规模比例模式（小村庄用：按具名角色总数比例）、行为类基础权重（typeBase：
 * hoarding/migration/protest/fleeing ×2.0，普通 ×1.5）、叙事预算门控。可配置、可关闭、可回滚。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "holzyn.actor.emergence")
public class EmergenceProperties {

    /** 检测总开关（默认 true；false=不扫描不叠加=回滚语义） */
    private boolean enabled = true;

    /** 最小成员数（默认 3：同一游戏日 3 个以上不同角色同类行为才成涌现） */
    private int minMembers = 3;

    /** 时间窗（游戏日，默认 1：扫描窗口内聚集） */
    private int windowDays = 1;

    /** 冷却（游戏日，默认 5：同行为类冷却防重复误报） */
    private int cooldownDays = 5;

    /** 比例模式开关（默认 true：按具名角色总数比例；小村庄用） */
    private boolean ratioEnabled = true;

    /** 比例阈值（默认 0.2=≥20% 具名角色参与即成涌现） */
    private double ratio = 0.2;

    /** 行为类基础权重（weight = memberCount × typeBase；缺失回退 default 1.5） */
    private Map<String, Double> typeBase = new LinkedHashMap<>();

    /** 默认基础权重（未配置行为类；默认 1.5） */
    private double defaultTypeBase = 1.5;

    /** 参与成员上限（evidence_json 截断护栏；默认 50） */
    private int maxEvidenceMembers = 50;

    /** 单日最大涌现事件数（防刷屏护栏；默认 10） */
    private int maxPerDay = 10;

    public EmergenceProperties() {
        // 内置行为类基础权重（可被 application.yml 覆盖）：囤积/迁徙/抗议/逃离 权重更高
        typeBase.put("hoarding", 2.0);
        typeBase.put("migration", 2.0);
        typeBase.put("protest", 2.0);
        typeBase.put("fleeing", 2.0);
        typeBase.put("trading_spree", 1.5);
        typeBase.put("social_wave", 1.5);
        typeBase.put("rest_break", 1.5);
    }

    /** 行为类基础权重（缺失回退默认）。 */
    public double typeBaseOf(String behaviorClass) {
        Double v = typeBase.get(behaviorClass);
        return v == null ? defaultTypeBase : v;
    }
}
