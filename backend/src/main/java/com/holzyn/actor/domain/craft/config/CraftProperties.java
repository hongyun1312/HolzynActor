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

package com.holzyn.actor.domain.craft.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 制作与建造配置（前缀 {@code holzyn.actor.craft}）。
 *
 * <p><b>从属于物品域</b>：本开关只在 {@code holzyn.actor.item.enabled=true} 时有意义——
 * 物品域关闭时 {@code InventoryPort} 不装配，没有可扣的材料、也没有可出的产物，
 * {@link com.holzyn.actor.domain.craft.service.CraftService} 直接判定为「未启用」，
 * 效果层记 SKIPPED、规则层保持 {@code RECIPE_UNKNOWN}，行为回之前。</p>
 *
 * <p>env 覆盖：yml 已写显式占位符，按占位符名精确匹配（点→下划线、连字符删除）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "holzyn.actor.craft")
public class CraftProperties {

    /**
     * 制作/建造总开关。
     *
     * <p>默认 false：之前的世界里没有任何配方语义，开着的世界一升级就会出现
     * 「NPC 突然会打铁」的行为漂移；与/「默认关、单独开」口径一致。</p>
     */
    private boolean enabled = false;

    /** 一次开工最多做几份（防 AI 幻觉一次把全世界的料吃光） */
    private int maxBatchesPerJob = 5;

    /** 单次结算扫描最多处理多少件活（防一轮卡死；剩下的下一轮继续） */
    private int maxJobsPerSweep = 50;

    /** 配方没写耗时时的默认耗时（游戏分钟） */
    private int defaultDurationMinutes = 30;

    /** 单件活耗时上限（游戏分钟）：240 分钟=4 小时，够建一座炉子，又不至于「建到天荒地老」 */
    private int maxDurationMinutes = 1440;

    /** 建造产物是否写成世界事实（{@code facility:*} / {@code prop:*}）；关掉则只在作业表留痕 */
    private boolean buildFactSync = true;

    /** 建造产物的可见性（public=全镇都知道这儿多了间铺子；location=只有到场的人知道） */
    private String buildVisibility = "location";
}
