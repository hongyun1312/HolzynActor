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

package com.holzyn.actor.domain.world.dialog.model;

/**
 * 对话抽取出的一条强声明。
 * <p><b>PROMISE</b>：承诺/约定/借贷/雇佣——落 {@code contract} 事实（private），双方 knowledge；
 * <b>STATEMENT</b>：对已知世界事实的陈述——只有命中已有 {@code factKey} 才更新事实，
 * 否则只落“某人说过”的 knowledge，绝不把猜测/谎话写成世界真相。</p>
 *
 * @param claimType     PROMISE / STATEMENT / GIFT
 * @param speakerRef    说话者角色引用（npc:{id}）
 * @param subjectRef    声明对象（可空；承诺的另一方 / 赠予的收礼人）
 * @param factKey       事实键（STATEMENT 必须是已知事实键；PROMISE 可空，服务端生成；
 *                      GIFT 是物品键，如「面包」）
 * @param name          事实名称（可空）
 * @param stateText     事实状态文本
 * @param visibility    private / location / public（可空=按类型默认）
 * @param dueGameMinutes 承诺期限（游戏分钟；可空=默认 3 天）
 * @param confidence    置信度 0~100（可空=80）
 * @param qty           数量（仅 GIFT 有意义；可空=1）
 */
public record DialogClaim(String claimType, String speakerRef, String subjectRef, String factKey,
                          String name, String stateText, String visibility,
                          Integer dueGameMinutes, Integer confidence, Integer qty) {

    /**
     * 兼容旧构造点（9 参，数量留空）。
     *
     * <p>{@code qty} 只为 GIFT 新增：对话里「这把刀给你」到底给几件，是物品域必须知道的
     * 数字；但 PROMISE/STATEMENT 的既有调用点不该为了一个与自己无关的字段改写。</p>
     */
    public DialogClaim(String claimType, String speakerRef, String subjectRef, String factKey,
                       String name, String stateText, String visibility,
                       Integer dueGameMinutes, Integer confidence) {
        this(claimType, speakerRef, subjectRef, factKey, name, stateText, visibility,
                dueGameMinutes, confidence, null);
    }

    /** 是否是「赠予」（要真的转移物品）。 */
    public boolean gift() {
        return "GIFT".equalsIgnoreCase(claimType);
    }
}
