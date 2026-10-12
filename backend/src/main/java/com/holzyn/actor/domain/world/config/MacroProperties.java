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

/**
 * 宏观社会演化系统配置。
 * <p>职责：承载宏观层可调参数——总开关/独立每日 AI 预算、初始参数（人口池/粮价基准/库存/
 * 税收/钱庄/货币）、阈值（粮价波动/势力位移/饥荒价格因子）、调度时间槽（00:05/00:10/00:15）、
 * 快照滚动上限。代码默认值与 application.yml 同步（同字段缺省值，环境变量可覆盖）。</p>
 * <p>设计对齐：
 * 多周期调度（经济/人口每日、势力/舆论每旬、文化/科技每月，与每日世界帧 01:00 错峰）；
 * 独立每日 AI 预算（对齐记忆系统重构轮「独立每日 AI 预算」设计，耗尽转规则兜底）；
 * 阈值触发 AI 叙事（粮价波动 &gt; 20%、势力影响力位移 &gt; 30% 等）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "holzyn.actor.macro")
public class MacroProperties {

    /** 宏观层总开关（默认 true；false=引擎不推进宏观 tick，世界帧/入账侧零影响） */
    private boolean enabled = true;

    /** 独立每日 AI 预算（重大行动判定 + 阈值叙事共用，默认 8；耗尽转规则兜底零阻断） */
    private int dailyAiCap = 8;

    /** AI 温度（叙事/判定共用，默认 0.7） */
    private double temperature = 0.7;

    /** 初始参数（农耕村落世界观推导：总人口 300 口、粮价基准 100 文/石等；可配置覆盖） */
    private Initial initial = new Initial();

    /** 阈值（重大变化触发 AI 叙事/规则事件） */
    private Thresholds thresholds = new Thresholds();

    /**
     * 调度时间槽（游戏日小时内分钟位；人口/经济=00:05、势力/舆论=00:10、文化/科技=00:15，
     * 与每日世界帧 00:00/编年史提炼 01:00 错峰； 「独立小时槽错峰」）
     */
    private Schedule schedule = new Schedule();

    /** 每项目快照行数上限（默认 1000；超出删最旧行——对齐 actor_state_history 滚动保留语义） */
    private int snapshotMaxPerProject = 1000;

    /**
     * 初始参数块。
     */
    @Data
    public static class Initial {
        /** 宏观人口池（农耕村落世界观「三百余口人」，默认 300；具名普通 NPC 为池中代表个体） */
        private long population = 300L;
        /** 粮价基准（铜钱/石，默认 100） */
        private double grainPriceBase = 100.0;
        /** 粮食库存（石，默认 800≈3 个月口粮） */
        private double grainStock = 800.0;
        /** 日均粮食产量基准（石/日，默认 2.7≈1.1 倍人均消费） */
        private double grainProductionBase = 2.7;
        /** 市集活跃度（0-100，默认 50=中性） */
        private double marketActivity = 50.0;
        /** 钱庄存款（铜钱，默认 5000） */
        private double bankDeposit = 5000.0;
        /** 货币流通（铜钱，默认 20000） */
        private double currencySupply = 20000.0;
        /** 人均日消费（石/人/日，默认 0.0082≈3 石/年） */
        private double perCapitaConsumption = 0.0082;
        /** 税率（铜钱/人/日，默认 0.6） */
        private double taxPerCapita = 0.6;
        /** 出生率（人/人/日，默认 0.0005≈18‰/年，游戏节奏） */
        private double birthRate = 0.0005;
        /** 死亡率（人/人/日，默认 0.0003≈11‰/年，游戏节奏） */
        private double deathRate = 0.0003;
        /** 迁徙率（人/人/日，默认 0.0002；经济压力/势力动荡方向翻转） */
        private double migrationRate = 0.0002;
    }

    /**
     * 阈值块。
     */
    @Data
    public static class Thresholds {
        /** 粮价波动阈值（|新-旧|/旧 &gt; 20% → 重大事件 AI 叙事，设计 §八） */
        private double priceChange = 0.20;
        /** 势力影响力位移阈值（&gt; 30% → 重大事件 AI 叙事，设计 §八） */
        private double factionShift = 0.30;
        /** 饥荒价格因子（粮价/基准 &gt; 1.5 → 饥荒风险：死亡率↑/出生率↓/人口外迁） */
        private double faminePriceFactor = 1.5;
        /** 库存告急天数（库存 &lt; 该天数×日消费 → 粮价暴涨触发阈值） */
        private double stockShortageDays = 30.0;
        /** 商队到来概率（每日，默认 0.015） */
        private double caravanChance = 0.015;
        /** 瘟疫概率（每日，默认 0.004；触发瘟疫减员事件） */
        private double plagueChance = 0.004;
    }

    /**
     * 调度时间槽块。
     */
    @Data
    public static class Schedule {
        /** 每日 tick 小时（默认 0=00 点） */
        private int dailyHour = 0;
        /** 每日 tick 分钟（默认 5=00:05，错峰帧生成 00:00） */
        private int dailyMinute = 5;
        /** 每旬 tick 分钟（默认 10=00:10） */
        private int factionMinute = 10;
        /** 每月 tick 分钟（默认 15=00:15） */
        private int monthlyMinute = 15;
        /** 每旬天数（默认 10 日一旬） */
        private int daysPerXun = 10;
        /** 每月天数（默认 30 日一月，与 SeasonService 月边界一致） */
        private int daysPerMonth = 30;
    }
}
