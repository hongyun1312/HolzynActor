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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宏观社会演化 · 宏观状态模型。
 * <p>职责：承载一个项目当前的全部宏观指标（人口/经济/势力/文化/科技五段），随 MacroScheduler
 * 各周期 tick 演进，并以 JSON 序列化持久化到 actor_world_macro_snapshot.indicators_json
 * （MacroStateService 负责加载/保存；快照行=tick 时点的全量状态，回看曲线用）。</p>
 * <p>五段（设计方案 §四）：
 * ① population 人口（总人口/当日出生/死亡/净迁徙 + 职业分布）；
 * ② economy 经济（粮库存/产量/粮价/市集供需/税收/钱庄存款/货币流通 六指标）；
 * ③ faction 势力（每股影响力/成员数/关系分，关系分 → 状态机由 MacroFactionEngine 派生）；
 * ④ culture 文化（议题列表[话题/倾向 -1..1/强度 0..1] + 信仰 + 禁忌 + 流行—— 仅初始快照，
 * 演化规则留）；
 * ⑤ tech 科技。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MacroState {

    /** 项目 ID */
    private Long projectId;

    /** 已推进到哪个游戏日（最后处理的 game_day；跨日自动初始化/快照） */
    private Long gameDay = 0L;

    /**
     * 世界观模板 id（双维度模板抽象：文明形态×力量体系；默认 xianxia=仙侠修仙；
     * 随快照 JSON 持久化=权威；手动覆盖经 MacroTemplateService+initialize 落此字段）
     */
    private String templateId = "xianxia";

    /** 人口段 */
    private Population population = new Population();

    /** 经济段（六指标） */
    private Economy economy = new Economy();

    /** 势力段：势力名 → 势力状态（含影响力/成员数/关系分） */
    private Map<String, Faction> factions = new LinkedHashMap<>();

    /** 文化段：舆论议题列表 */
    private List<Issue> issues = new ArrayList<>();

    /** 文化段：信仰分布（名称 → 强度 0..1） */
    private Map<String, Double> beliefs = new LinkedHashMap<>();

    /** 文化段：禁忌（村规等） */
    private List<String> taboos = new ArrayList<>();

    /** 文化段：流行（节庆/习俗） */
    private List<String> fashions = new ArrayList<>();

    /**
     * 民怨（0-100，每日由粮价/生计压力驱动——高粮价/歉收↑、丰年/赈济↓；每旬势力 tick
     * 消费：高民怨→当权势力影响力↓/边缘势力↑/关系紧张 + 赈济负反馈/民变风险事件）
     */
    private Double grievance = 0.0;

    /** 习俗起始游戏日（习俗名 → 起始日；超龄消退判断） */
    private Map<String, Long> fashionSinceDay = new LinkedHashMap<>();

    /** 科技段：维度 → 累积值 0..100（农业/工艺/医术/法阵） */
    private Map<String, Double> techLevels = new LinkedHashMap<>();

    /** 科技维度 → 连续无投入月数（时间自然增长不算投入；≥3 月 → 衰退，&lt;10 → 失传） */
    private Map<String, Integer> techNoInputMonths = new LinkedHashMap<>();

    /** 科技维度 → 上月值（突破判定：跨里程碑 50/70/90 触发技艺突破） */
    private Map<String, Double> techPrevLevels = new LinkedHashMap<>();

    /** 本月已处理的入账/信息聚合标志（月内幂等用；随快照持久化可跨重启） */
    private Long lastCultureMonthProcessed = 0L;

    // ==================== 五段内嵌模型 ====================

    /** 人口段 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Population {
        /** 总人口（宏观人口池；具名普通 NPC 为池中代表个体） */
        private long total = 300L;
        /** 当日出生（本期 tick 累计） */
        private long births = 0L;
        /** 当日死亡（本期 tick 累计） */
        private long deaths = 0L;
        /** 当日净迁徙（+入/-出） */
        private long migrationNet = 0L;
        /** 职业分布（职业名 → 人数，农耕世界观职业推导） */
        private Map<String, Long> occupations = new LinkedHashMap<>();
    }

    /** 经济段（六指标，设计方案 §4.2） */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Economy {
        /** 粮食库存（石） */
        private double grainStock = 800.0;
        /** 当日粮食产量（石） */
        private double grainProduction = 2.7;
        /** 粮价（铜钱/石，基准 100） */
        private double grainPrice = 100.0;
        /** 市集供需活跃度（0-100，50=中性） */
        private double marketActivity = 50.0;
        /** 税收（铜钱/日） */
        private double taxRevenue = 180.0;
        /** 钱庄存款（铜钱） */
        private double bankDeposit = 5000.0;
        /** 货币流通（铜钱） */
        private double currencySupply = 20000.0;
        /** 本日收成系数（季节基准×天气修正×连续累积；随快照持久化供曲线/叙事因果链） */
        private Double harvestFactor;
        /** 本日季节（spring/summer/autumn/winter；收成因果链记录） */
        private String season;
        /** 本日天气条件（收成因果链记录；取自最近帧首区域） */
        private String weatherCondition;
        /** 连续无有效降水日（旱情累积；收成引擎维护） */
        private Integer dryStreak = 0;
        /** 连续强降水日（涝情累积） */
        private Integer floodStreak = 0;
        /** 连续降雪日（暴雪累积） */
        private Integer snowStreak = 0;
        /** 本季收成结果（1=丰年 / 0=平 / -1=歉收；每旬势力 tick 正向/负向环输入） */
        private Integer harvestOutcome = 0;
        /** 粮价对基准的比值（生计反馈/民怨驱动输入；快照记录） */
        private Double priceRatio;
        /** 科技突破加成——农业突破后产量基准加成（0.05=+5%，叠加于 grainProductionBase） */
        private Double techHarvestBonus = 0.0;
        /** 科技突破加成——医术突破后瘟疫概率缩减系数（0.7=×0.7） */
        private Double techPlagueReduction = 1.0;
        /** 科技突破加成——工艺突破后税收加成（0.05=+5%） */
        private Double techTaxBonus = 0.0;
        /** 科技突破加成——秘术突破后压制弱势势力影响力（本旬生效一次） */
        private Double techArrayBonus = 0.0;
    }

    /** 势力段（每旬推进） */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Faction {
        /** 势力名 */
        private String name;
        /** 领袖引用（special:{id}，启动时按角色名解析，解析失败 null=背景势力） */
        private String leaderRef;
        /** 影响力（0-100） */
        private double influence = 30.0;
        /** 成员规模 */
        private long memberCount = 10L;
        /** 与其他势力的关系分（势力名 → -100..100；状态机由引擎派生） */
        private Map<String, Integer> relations = new LinkedHashMap<>();
    }

    /** 文化段：舆论议题 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Issue {
        /** 议题话题（如「时局」） */
        private String topic;
        /** 倾向（-1..1：-1 反对/1 支持，0=中性） */
        private double orientation = 0.0;
        /** 强度（0..1：全村关注度） */
        private double intensity = 0.3;
    }
}
