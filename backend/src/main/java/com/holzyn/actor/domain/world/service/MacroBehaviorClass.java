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

package com.holzyn.actor.domain.world.service;

import java.util.Map;

/**
 * 宏观社会演化 · 二阶行为类归一。
 * <p>职责：把入账流水行（actionType + actionText + indicator）归一为「二阶行为类别」——
 * 供二阶涌现检测（MacroEmergenceService）与运行时自发成组（MesoGroupEngine.scanEmergentGroups）
 * 共用同一口径（同语义合并：如 trade/custom「囤」→hoarding）：</p>
 * <ul>
 * <li><b>hoarding</b>囤积：囤/存粮/买粮/购粮/屯粮/抢购…；</li>
 * <li><b>migration</b>迁徙：迁徙/离村/搬走/移居/外迁…或 move 类型；</li>
 * <li><b>protest</b>抗议：抗议/请愿/闹事/聚众/游行/示威…；</li>
 * <li><b>fleeing</b>逃离：逃离/逃难/出逃/避祸…；</li>
 * <li><b>trading_spree</b>交易潮：trade 类型或经商/买卖/赶集…；</li>
 * <li><b>social_wave</b>社交潮：social 类型或串门/茶馆/聚会…；</li>
 * <li><b>rest_break</b>集体休整：rest 类型或休息/歇息/打盹…；</li>
 * <li>其余 → null（不构成二阶行为类别，不参与聚集统计）。</li>
 * </ul>
 */
public final class MacroBehaviorClass {

    private MacroBehaviorClass() {
    }

    /** 涌现类型 → 显示名（叙事/前端呈现用） */
    public static final Map<String, String> DISPLAY = Map.of(
            "hoarding", "囤积", "migration", "迁徙", "protest", "抗议",
            "trading_spree", "交易潮", "social_wave", "社交潮", "fleeing", "逃离",
            "rest_break", "集体休整");

    /** 行为类显示名（缺失回退原码）。 */
    public static String displayOf(String behaviorClass) {
        return DISPLAY.getOrDefault(behaviorClass, behaviorClass);
    }

    /** 行为类基础权重名 → 权重（默认 1.5；囤积/迁徙/抗议/逃离 2.0——由 EmergenceProperties 承载） */

    /**
     * 入账行 → 二阶行为类别（确定性；无法归一返回 null）。
     *
     * @param actionType 行动类型（决策 type；可空）
     *
     * @param actionText 行动文本（决策 action；可空）
     *
     * @param indicator 宏观指标名（可空）
     *
     * @return 行为类（hoarding/migration/protest/fleeing/trading_spree/social_wave/rest_break；null=不参与）
     */
    public static String classify(String actionType, String actionText, String indicator) {
        String t = actionType == null ? "" : actionType.trim().toLowerCase();
        String text = actionText == null ? "" : actionText;
        // 高权重优先（囤积/抗议/逃离最「聚集」语义）
        if (containsAny(text, "囤", "存粮", "买粮", "购粮", "屯粮", "囤积", "抢购", "储粮", "备粮")) {
            return "hoarding";
        }
        if (containsAny(text, "抗议", "请愿", "闹事", "聚众", "反抗", "游行", "示威")) {
            return "protest";
        }
        if (containsAny(text, "逃离", "逃难", "出逃", "避祸", "逃亡")) {
            return "fleeing";
        }
        if (t.contains("move") || containsAny(text, "迁徙", "离村", "搬走", "移居", "出走", "外迁", "离乡")) {
            return "migration";
        }
        if (t.contains("trade") || containsAny(text, "买卖", "交易", "经商", "赶集", "摆摊", "售卖", "进货")) {
            return "trading_spree";
        }
        if (t.contains("social") || containsAny(text, "社交", "串门", "茶馆", "聚会", "闲谈", "唠嗑")) {
            return "social_wave";
        }
        if (t.contains("rest") || containsAny(text, "休息", "歇息", "打盹", "午休", "休整", "小憩")) {
            return "rest_break";
        }
        return null;
    }

    /** 关键词任一包含判定。 */
    static boolean containsAny(String text, String... keywords) {
        for (String k : keywords) {
            if (!k.isBlank() && text.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
