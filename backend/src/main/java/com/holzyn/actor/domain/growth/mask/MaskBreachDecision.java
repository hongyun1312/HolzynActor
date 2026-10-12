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

/**
 * 面具破绽判定点接口。
 * <p><b>设计动机</b>：破绽检测是高频布尔判定（「当前文本/处境是否命中
 * 触发物、抑制该降多少」）——程序化实现确定可测； 之后 NanoJev 接入时只替换本接口
 * 实现（boolean 题型：states=情境+触发物上下文，questions=是否破绽 / score=抑制降幅），
 * <b>不重改面具主流程</b>（与 {@link MaskContextSource} 同一预留范式）。</p>
 * <p><b>触发物来源</b>（程序化实现读取）：档案 ⑪ 维 {@code dims.mask.breaches}（破绽条目：
 * 触发物 → 抑制增量）+ 档案 ⑨ 维 {@code dims.experience.traumas[].triggers}（创伤触发物，
 * 默认降幅 −0.3）+ 酒精状态（当前行动含饮酒词，默认降幅 −0.3）——「人会在最不该失态的时候
 * 失态」的确定性来源（设计 §5.6）。</p>
 */
public interface MaskBreachDecision {

    /**
     * 检测一次破绽。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 本人统一引用（npc:{id}）
     *
     * @param contextText 检测文本（对话输入/本人行动文本/事件内容；可空=只查状态类触发物[饮酒]）
     *
     * @param gameSecond 当前世界时刻（游戏秒）
     *
     * @return 破绽结果（triggered=false=无破绽；零阻断：任何失败按无破绽）
     */
    BreachResult detect(Long projectId, String npcRef, String contextText, Long gameSecond);

    /** 破绽检测结果。 */
    record BreachResult(boolean triggered, String trigger, double suppressionDelta) {
        /** 无破绽。 */
        public static BreachResult none() {
            return new BreachResult(false, null, 0);
        }

        /** 命中破绽（抑制降幅为正数表示「抑制下降量」，如 0.3=当前情境抑制 −0.3）。 */
        public static BreachResult hit(String trigger, double drop) {
            return new BreachResult(true, trigger, Math.max(0, Math.min(1, drop)));
        }
    }
}
