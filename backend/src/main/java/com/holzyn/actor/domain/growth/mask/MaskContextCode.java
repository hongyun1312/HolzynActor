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

package com.holzyn.actor.domain.growth.mask;

import java.util.List;

/**
 * 社会面具情境码常量。
 * <p><b>多情境叠加取最大抑制强度</b>（如「对权威 + 公共场合」同时在场 → 取两者中高者）；
 * 情境码同时是档案 ⑪ 维 {@code contextSuppression} 的键与 {@code actor_growth_mask.context_code} 的值。</p>
 * <p><b>面具能力随阶段解锁</b>（设计 §5.6：INFANT 无面具 → ANCIENT 上限降回 0.6，认知衰退导致
 * 抑制能力下降）：{@link #stageMaxSuppression} 给出各阶段的面具抑制强度上限，
 * {@code MaskService} 计算生效抑制时取「情境抑制 × 阶段上限钳制」。</p>
 */
public final class MaskContextCode {

    /** 对权威（县衙/上官/村长/族长/师父…） */
    public static final String BEFORE_AUTHORITY = "before_authority";
    /** 对伴侣（配偶/恋人） */
    public static final String BEFORE_PARTNER = "before_partner";
    /** 对陌生人/外来者 */
    public static final String BEFORE_STRANGER = "before_stranger";
    /** 对同伴/同龄（同村同辈） */
    public static final String BEFORE_PEER = "before_peer";
    /** 对子女/晚辈 */
    public static final String BEFORE_CHILD = "before_child";
    /** 对仇怨对象 */
    public static final String BEFORE_RIVAL = "before_rival";
    /** 公共场合（祠堂/村公所/集市/街巷/田间…） */
    public static final String PUBLIC_PLACE = "public_place";
    /** 私人场所（家宅/居室/卧房…） */
    public static final String PRIVATE_PLACE = "private_place";

    /** 全部情境码（顺序=渲染/遍历稳定序）。 */
    public static final List<String> ALL = List.of(
            BEFORE_AUTHORITY, BEFORE_PARTNER, BEFORE_STRANGER, BEFORE_PEER,
            BEFORE_CHILD, BEFORE_RIVAL, PUBLIC_PLACE, PRIVATE_PLACE);

    /** 情境码 → 中文名（S8 渲染与调试展示）。 */
    public static String labelOf(String code) {
        if (code == null) {
            return code;
        }
        return switch (code) {
            case BEFORE_AUTHORITY -> "对权威";
            case BEFORE_PARTNER -> "对伴侣";
            case BEFORE_STRANGER -> "对陌生人";
            case BEFORE_PEER -> "对同伴";
            case BEFORE_CHILD -> "对子女";
            case BEFORE_RIVAL -> "对仇怨对象";
            case PUBLIC_PLACE -> "公共场合";
            case PRIVATE_PLACE -> "私人场所";
            default -> code;
        };
    }

    /** 情境码合法性校验（非法码在消费方忽略，零阻断）。 */
    public static boolean isValid(String code) {
        return code != null && ALL.contains(code);
    }

    /**
     * 面具能力阶段上限（0=无面具；设计 §5.6：INFANT 无 → 认知衰退后 ANCIENT 降回 0.6）。
     * <p>幼儿/儿童面具是「学着大人的样子」（可观察，但很浅）；青少年开始形成稳定面具；
     * 成年（YOUTH~MIDDLE）全能力；ELDER/ANCIENT 因认知与精力衰退抑制能力下降。</p>
     *
     * @param stageCode 阶段码（GrowthStage.name()；未知/空按成年处理）
     *
     * @return 该阶段面具抑制强度上限 0-1
     */
    public static double stageMaxSuppression(String stageCode) {
        if (stageCode == null) {
            return 0.8;
        }
        return switch (stageCode) {
            case "INFANT" -> 0.0;
            case "TODDLER" -> 0.1;
            case "CHILD" -> 0.3;
            case "ADOLESCENT" -> 0.5;
            case "ELDER" -> 0.7;
            case "ANCIENT" -> 0.6;
            default -> 0.8; // YOUTH / PRIME / MIDDLE / 未知
        };
    }

    private MaskContextCode() {
    }
}
