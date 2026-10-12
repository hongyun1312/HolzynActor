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

import java.util.ArrayList;
import java.util.List;

/**
 * 宏观社会演化 · 人口数值引擎。
 * <p>职责：人口子系统每游戏日统计模型——出生/死亡/迁徙（设计 §4.1）：
 * ① 出生 = 总人口 × 出生率 ×（1 - 经济压力×0.6）——饥荒年出生率下降；
 * ② 死亡 = 总人口 × 死亡率 ×（1 + 经济压力×0.9）——饥荒死亡率上升；
 * ③ 迁徙 = 总人口 × 迁徙率 × 方向（经济压力 &gt; 1.2 → 外迁；安居 → 少量回流）× 确定性波动；
 * ④ 瘟疫：每日确定性概率判定（seed 哈希），触发「瘟疫减员」事件并追加减员；
 * ⑤ 事件：饥荒减员（经济压力 &gt; 1.5）/ 瘟疫减员 / 流民涌入（安居 + 幸运日）。
 * 全部确定性（daySeed 哈希抖动，同输入同输出，可复现可单测）。</p>
 * <p>具名挂钩 由 MacroScheduler 在事件触发时
 * 按规则确定性选取具名 NPC 标记影响（见 MacroStateService.pickNamedNpc），本引擎只产出数字。</p>
 */
public final class MacroPopulationEngine {

    private MacroPopulationEngine() {
    }

    /** 人口 tick 结果 */
    public record PopulationResult(long total, long births, long deaths, long migrationNet,
                                   double economicStress, List<String> events) {
    }

    /**
     * 人口每日推进（确定性）。
     *
     * @param total 当前总人口
     *
     * @param birthRate 出生率（人/人/日）
     *
     * @param deathRate 死亡率（人/人/日）
     *
     * @param migrationRate 迁徙率（人/人/日）
     *
     * @param economicStress 经济压力（0=无；&gt;1.5=饥荒：出生↓死亡↑外迁）
     *
     * @param plagueChance 瘟疫概率（每日 0..1；seed 哈希判定）
     *
     * @param daySeed 游戏日序号（确定性抖动种子）
     *
     * @return 推进后结果（events=规则事件 code 列表，由调度器映射为宏观事件）
     */
    public static PopulationResult step(long total, double birthRate, double deathRate, double migrationRate,
                                        double economicStress, double plagueChance, long daySeed) {
        List<String> events = new ArrayList<>();
        double stress = Math.max(0.0, Math.round(economicStress * 100.0) / 100.0);

        // ① 出生（饥荒抑制）
        long births = Math.round(total * birthRate * Math.max(0.0, 1.0 - stress * 0.6));
        // ② 死亡（饥荒加剧）
        long deaths = Math.round(total * deathRate * (1.0 + stress * 0.9));

        // ③ 迁徙（压力>1.2 外迁；安居有概率回流流民）
        int dir = stress > 1.2 ? -1 : 1;
        long migration = Math.round(total * migrationRate * dir * (0.5 + jitter(daySeed, 7)));
        long migrationNet = dir < 0 ? -Math.abs(migration) : Math.abs(migration);

        // ④ 瘟疫（确定性概率；事件码=通用语义码）
        if (jitter(daySeed, 11) < plagueChance) {
            long plagueDeaths = Math.max(1L, Math.round(total * 0.004));
            deaths += plagueDeaths;
            events.add("plague");
        }

        // ⑤ 事件判定（事件码=通用语义码）
        if (stress > 1.5) {
            events.add("famine");
        } else if (stress <= 0.15 && jitter(daySeed, 13) < 0.03) {
            // 安居 + 幸运日 → 流民涌入（外迁反向）
            long influx = Math.max(1L, Math.round(total * migrationRate * 8));
            migrationNet += influx;
            events.add("refugee_influx");
        }

        long totalNew = Math.max(1L, total + births - deaths + migrationNet);
        return new PopulationResult(totalNew, births, deaths, migrationNet, stress, events);
    }

    /**
     * 确定性抖动（LCG 哈希 → [0,1)；同 (seed, salt) 恒同输出，可复现）。
     */
    public static double jitter(long seed, int salt) {
        long h = seed * 2654435761L + salt * 40503L;
        h = (h ^ (h >>> 13)) * 1103515245L;
        h = h ^ (h >>> 16);
        return (h & 0x7FFFFFFFL) / (double) 0x7FFFFFFFL;
    }
}
