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

package com.holzyn.actor.domain.growth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轨道 「全生命周期阶段机 + 此刻可发生空间」配置（前缀 {@code holzyn.actor.growth.*}，）。
 * <p><b>逐条对应（开工前 11 问）</b>：</p>
 * <ul>
 * <li>全 20 人空间 + 消费（普通 NPC 逐人决策，crowd 批量 AI 运行时停用）→ {@link #dispatch}；</li>
 * <li>死亡完整实现默认关 → {@link #death}；</li>
 * <li>认知 LOD=重要度 + 事件/对话/同地/关系跳数驱动、预留相机接口 → {@link #lod}；</li>
 * <li>空间全量注入所有消费场景（不严格受 1200 token 验收线约束）→ {@link #space}；</li>
 * <li>空间内选择=软引导 + 硬禁止，可配硬约束开关 → {@code space.hard-constraint}；</li>
 * <li>出生时刻反推=时钟基准 + 档案提取 + 默认兜底 → {@link #backfill}；</li>
 * <li>门控表=建表 + 代码 seed、表优先 → {@link #gateTable};</li>
 * <li>决策频率=全 20 人每游戏小时（NanoJev 后续提速）→ {@code dispatch.all-npc-hourly}；</li>
 * <li>护栏三条（stage/space/knowledge_bound）→ {@link #guardrails}；</li>
 * <li>机能曲线完整接入状态衰减 → {@link #aging}。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "holzyn.actor.growth")
public class GrowthProperties {

    /** 生命周期系统总开关 */
    private boolean enabled = true;

    /** 阶段检查周期（游戏日，默认 1：阶段检查/年龄刷新挂到日级周期，每 N 游戏日执行一次） */
    private int stageCheckCycleGameDays = 1;

    /** 门控表配置（建表 + seed + 热更新）。 */
    private GateTable gateTable = new GateTable();

    /** 此刻可发生空间配置。 */
    private Space space = new Space();

    /** 认知 LOD 配置。 */
    private Lod lod = new Lod();

    /** 衰老与出生配置。 */
    private Aging aging = new Aging();

    /** 死亡配置（调试期默认关）。 */
    private Death death = new Death();

    /** 决策调度配置。 */
    private Dispatch dispatch = new Dispatch();

    /** 认知注意力/高频微决策配置 */
    private Attention attention = new Attention();

    /** 出生时刻反推配置（存量 NPC 迁移）。 */
    private Backfill backfill = new Backfill();

    /** 护栏配置。 */
    private Guardrails guardrails = new Guardrails();

    /** 人格演化配置。 */
    private Shift shift = new Shift();

    /** 社会面具配置。 */
    private Mask mask = new Mask();

    /** 遗传婚育谱系配置。 */
    private Reproduction reproduction = new Reproduction();

    /** 婴儿行为配置。 */
    private Infant infant = new Infant();

    /** 发育阶段行为门控配置（O6 发育演化：受限阶段空闲决策的程序化阶段行为 + AI 润色）。 */
    private StageGating stageGating = new StageGating();

    /** 身后事/遗物配置。 */
    private Legacy legacy = new Legacy();

    /** 门控表：建表 + 代码 seed，表优先读、代码兜底。 */
    @Data
    public static class GateTable {
        /** 启动 seed 开关（默认 true：表为空时写入代码默认行；非空不覆盖，保留热更新结果） */
        private boolean seedOnStartup = true;
    }

    /** 此刻可发生空间配置（对齐设计文档 §5.3 / 附录 E）。 */
    @Data
    public static class Space {
        /** 空间生成总开关（默认 true；false=全部走程序化兜底空间，零 AI） */
        private boolean enabled = true;

        /**
         * focus 档定期兜底重算周期（游戏分钟，默认 360=6 游戏小时）。
         * <p><b>长跑抓修（原默认 60=1 游戏小时）</b>：定期兜底是「逐个 NPC 串行 AI
         * 生成」，一次全项目重算的耗时 ≈ NPC 数 × 单次生成耗时（实测 103 次 growth_space_generate
         * 共 3242 秒、均值 31.5 秒 → 20 个 NPC 一轮≈10.5 真实分钟）。而 rate=24 下 1 游戏小时仅 2.5
         * 真实分钟——周期 60 游戏分钟意味着兜底任务永远跑不完（引擎被拖到 21% 标称速率）；即使异步化 +
         * 单飞，仍会占满网关使角色决策整批排队超时。改为 6 游戏小时（=15 真实分钟）后兜底是真正的
         * 「兜底」：空间新鲜度由决策前的惰性保障（ensureSpace，TTL 见 ttlGameMinutes）与事件触发
         * （情绪漂移/阶段跨越/在场者变化等 triggerRegenerate）负责，兜底只补前两者漏掉的 NPC。</p>
         */
        private int periodicRecomputeGameMinutes = 360;

        /**
         * ambient 档定期兜底重算周期（游戏分钟，默认 720=12 游戏小时；同 focus 档的成本理由，
         * 低频档位周期取 focus 的两倍—— 由 360 调整为 720）
         */
        private int ambientPeriodicGameMinutes = 720;

        /** 空间 TTL（游戏分钟，默认 120：超过后决策前必须重算） */
        private int ttlGameMinutes = 120;

        /** 情绪漂移触发阈值（|ΔPAD| 默认 0.35：超过触发 emotion_shift 重算） */
        private double emotionShiftThreshold = 0.35;

        /** 全量注入预留开关（默认 false：空间生成时是否注入 11 维全量档案；调试/验证用） */
        private boolean fullInject = false;

        /**
         * 硬约束开关（默认 false=软引导：AI 可在候选清单外选择[反常行为真实]，仅 forbidden 硬禁；
         * true=决策必须落在 possible_actions 内，否则程序化从空间采样兜底——调试/评估用）
         */
        private boolean hardConstraint = false;

        /** 空间内行动率目标（指标展示用，默认 0.85，不强制） */
        private double inSpaceActionTarget = 0.85;

        /**
         * 空间 JSON 单块注入上限（字符，默认 900：超长截断防撑爆 Prompt；
         * 全量注入所有消费场景，故上限放宽到承载完整四段式空间）
         */
        private int injectMaxChars = 900;
    }

    /** 认知 LOD 配置（对齐设计文档 §5.4 / 附录 E）。 */
    @Data
    public static class Lod {
        /** LOD 总开关（默认 true；false=全部按 focus 处理，不升降档） */
        private boolean enabled = true;

        /** 降档滞回窗口（游戏分钟，默认 120：离开焦点后 N 游戏分钟才降档，防抖动） */
        private int downgradeHysteresisGameMinutes = 120;

        /** 相机近距离阈值（米，默认 25：Unity 相机距离低于此值升 focus；复用 U1 坐标 1 格=0.2 米） */
        private double cameraNearMeters = 25;

        /** 特殊型 NPC 默认档位（默认 focus：重要度≥4 的常驻焦点） */
        private String defaultTierSpecial = "focus";

        /**
         * 普通型 NPC 默认档位（默认 ambient； 全 20 人每小时决策，
         * dormant 仅配置/相机/手动降档后生效）
         */
        private String defaultTierCommon = "ambient";

        /** 升档关系跳数阈值（默认 1：与 focus 角色关系跳数 ≤ 此值升档） */
        private int focusRelationHops = 1;

        /** 事件升档窗口（游戏分钟，默认 60：被事件涉及后保持升档的时长） */
        private int eventPromoteWindowGameMinutes = 60;

        // ===== 认知注意力语义通道 =====
        /** 注意力语义通道总开关 */
        private boolean semanticEnabled = true;

        /** 语义升档保持时长（游戏分钟，默认 30：注意力高→focus 后保持的时长，防每 tick 抖动） */
        private int semanticFocusGameMinutes = 30;

        /** 语义降档保持时长（游戏分钟，默认 30：注意力低→ambient 后保持的时长，防抖动） */
        private int semanticDemoteGameMinutes = 30;
    }

    /** 衰老与出生配置（对齐设计文档 §4.6 / 附录 E）。 */
    @Data
    public static class Aging {
        /** 衰老推进开关（默认 true；false=年龄/阶段冻结） */
        private boolean enabled = true;

        /** 游戏年秒数（默认 31536000=365 天；测试环境可调快） */
        private long gameSecondsPerYear = 31_536_000L;
    }

    /** 死亡配置。 */
    @Data
    public static class Death {
        /** 死亡总开关（默认 false=调试期关；true=日级死亡风险掷点生效；测试接口可临时强制） */
        private boolean enabled = false;

        /**
         * 各阶段基线年死亡率（默认：ANCIENT 0.08 / ELDER 0.02 / MIDDLE 0.004；YOUTH/PRIME 0.001、
         * 青少年 0.0005、幼儿/儿童 0.0008；INFANT 0.004[新生儿风险]）
         */
        private Map<String, Double> baseRateByStage = new LinkedHashMap<>() {{
            put("INFANT", 0.004);
            put("TODDLER", 0.0008);
            put("CHILD", 0.0008);
            put("ADOLESCENT", 0.0005);
            put("YOUTH", 0.001);
            put("PRIME", 0.001);
            put("MIDDLE", 0.004);
            put("ELDER", 0.02);
            put("ANCIENT", 0.08);
        }};

        /** 健康对死亡风险的修正权重（默认 0.5：健康 100→×0.5，健康 0→×1.5，线性） */
        private double healthModifierWeight = 0.5;

        /** 季节修正开关（默认 true：冬季/酷暑死亡风险 ×1.3，春秋 ×1.0——季节系统已就绪） */
        private boolean seasonModifierEnabled = true;
    }

    /** 决策调度配置。 */
    @Data
    public static class Dispatch {
        /**
         * 全 NPC 每游戏小时逐人决策（默认 true：普通 NPC 也走 AgentRuntime 引擎决策 + 空间消费；
         * false=回退 行为[特殊 NPC 每小时 + 普通 NPC 程序化作息]）
         */
        private boolean allNpcHourly = true;
    }

    /**
     * 认知注意力/高频微决策配置（， 逐题确认：
     * 每 tick 微决策=即时意向 choice + 注意力 score 同批评估，异步虚拟线程不进 tick 屏障；
     * 产出=低频记忆轨迹 + 极低概率触发打断；大部分「继续当下事」保持行为连续性）。
     */
    @Data
    public static class Attention {
        /** 高频微决策总开关（默认 true；false=每 tick 批量停用，事件相关度通道仍可用） */
        private boolean enabled = true;

        /** 批量周期（tick 数，默认 2：每 2 tick≈10 游戏分钟全 NPC 批量一次；1=每 tick 5 游戏分钟） */
        private int periodTicks = 2;

        /** 打断概率（默认 0.05：意向≠继续当下事时触发即时决策打断的概率——极低概率=真人偶发起念） */
        private double interruptProbability = 0.05;

        /**
         * 记忆轨迹写入概率（默认 0.3：意向≠继续当下事时写 L1 感知记忆的概率——低频，防 20 NPC ×
         * 288 tick/天 ≈ 5760 条/天污染记忆库）
         */
        private double memoryWriteProbability = 0.3;

        /** 事件相关度升档档位（默认 3：事件×NPC 相关度 score 档位 ≥ 此值 → 语义升档 focus） */
        private int eventRelevancePromoteLevel = 3;

        /** 事件相关度记忆写入档位（默认 2：相关度 ≥ 此值 → 写 L1 感知记忆「此事与我有关」） */
        private int eventRelevanceMemoryLevel = 2;

        /** 单 state 情境文本上限（字符，默认 160：全 NPC 批量 state 保持精简，防请求体膨胀） */
        private int stateTextMaxChars = 160;

        // ===== 认知 LOD 深化（，：三项全做 + Prompt/缓存/输出优化贯穿） =====

        // ---------- ① 群聊参与度统计与双向联动 ----------
        /** 参与度统计总开关 */
        private boolean participationEnabled = true;

        /** 参与度统计窗口（游戏小时，默认 6：近 6 游戏小时内的发言/在场次数，真人社交记忆节奏） */
        private int participationWindowGameHours = 6;

        /**
         * 参与度档位步长（默认 2：加权次数 ≥1 步长=1 档、≥2 步长=2 档、≥3 步长=3 档；
         * 发言权重 1、在场权重 0.5——开口说话比在场更说明「投入」）
         */
        private int participationLevelStep = 2;

        /**
         * 参与度回灌注意力开关（默认 true：参与度 ≥2 档时每 tick 注意力档位 +1 封顶 3——
         * 「正在聊的人更投入」回灌认知 LOD）
         */
        private boolean participationBoostEnabled = true;

        /**
         * 群聊 desire 参与度加分开关（默认 true：参与度 ≥2 档或 focus 档角色 desire +1 封顶 5——
         * 高参与/高关注角色更愿接话，双向联动的对话侧）
         */
        private boolean desireBoostEnabled = true;

        /** 群聊参与度记录开关（默认 true：群聊成功发言=spoke、在场其他成员=attended 记入统计） */
        private boolean participationRecordingEnabled = true;

        // ---------- ② 记忆检索注意力加权（层间优先级修正 + 档位数量配额，全量注入路径） ----------
        /** 记忆注入注意力加权总开关 */
        private boolean memoryWeightEnabled = true;

        /**
         * memory_tier 层间优先级修正开关（默认 true：注意力档位修正注入档位——focus 时 LOW 升 MID
         * （L2 层进入注入）、dormant 时 HIGH 降 MID（L3 巩固层退出）——「高关注时回忆预算更慷慨、
         * 低关注时只记近事」的真人式优先级。⚠️ 诚实说明：单角色检索内注意力系数是同角色常量，
         * 数学上不改变该角色记忆间的相对顺序，故「排序加权」落地为层间优先级 + 条数配额，
         * 而非角色内重排）
         */
        private boolean memoryTierAdjustEnabled = true;

        /**
         * 各注意力档位 L1 清晰段条数上限（focus/ambient/dormant，默认 5/4/2：高关注注入更丰富、
         * 低关注收窄——同预算内重新分配，总 token 不增反降）
         */
        private Map<String, Integer> memoryL1CapByTier = new LinkedHashMap<>() {{
            put("focus", 5);
            put("ambient", 4);
            put("dormant", 2);
        }};

        /** 各注意力档位 L2 段条数上限（focus/ambient/dormant，默认 4/3/2，同 L1 理由） */
        private Map<String, Integer> memoryL2TopKByTier = new LinkedHashMap<>() {{
            put("focus", 4);
            put("ambient", 3);
            put("dormant", 2);
        }};

        /**
         * 空间生成记忆条数按注意力档位（focus/ambient/dormant，默认 3/2/1：最大输入场景的配额收窄，
         * 对「降输出/提缓存」收益最直接）
         */
        private Map<String, Integer> spaceMemoryTopKByTier = new LinkedHashMap<>() {{
            put("focus", 3);
            put("ambient", 2);
            put("dormant", 1);
        }};

        // ---------- ③ 相机注意力聚焦（程序化融合 + 零副作用开关） ----------
        /** 相机注意力聚焦总开关（默认 true；Web 无相机/未装配自动跳过，零副作用） */
        private boolean cameraFocusEnabled = true;

        /**
         * 相机中距离阈值（米，默认 50：距离 ≤ 此值注意力=2 档；≤ cameraNearMeters(25)=3 档；
         * ≤ far(100)=1 档——连续距离→连续注意力档位，替代原二值升档）
         */
        private double cameraMidMeters = 50;

        /** 相机远距离阈值（米，默认 100：超过此值不参与注意力（0 档），仍由规则升档兜底） */
        private double cameraFarMeters = 100;
    }

    /** 出生时刻反推配置。 */
    @Data
    public static class Backfill {
        /** 反推开关（默认 true：启动时对缺失 birth_game_second 的 NPC 反推一次） */
        private boolean enabled = true;

        /** 特殊 NPC 提取失败默认年龄（游戏年，默认 30） */
        private int defaultAgeSpecial = 30;

        /** 普通 NPC 无 age 列默认年龄（游戏年，默认 25） */
        private int defaultAgeCommon = 25;
    }

    /** 护栏配置。 */
    @Data
    public static class Guardrails {
        /** stage_compliance 总开关（默认 true：决策/对话输出与阶段能力硬冲突 → 拦截 + 程序化兜底） */
        private boolean stageComplianceEnabled = true;

        /** space_compliance 总开关（默认 true：AI 空间候选与硬门控冲突 → 剔除 + 告警；决策 forbidden 拦截） */
        private boolean spaceComplianceEnabled = true;

        /** developmental_knowledge_bound 总开关（默认 true：输出超出认知上限[技能/知识盲区] → 拦截修正） */
        private boolean knowledgeBoundEnabled = true;

        /**
         * mask_compliance 总开关（：默认 true——对话/决策输出命中当前情境「禁止表达」→ 拦截 +
         * 程序化兜底；独处/私人场所不设防，见 GrowthGuardService）
         */
        private boolean maskComplianceEnabled = true;
    }

    /** 人格演化配置。 */
    @Data
    public static class Shift {
        /** 人格演化总开关（默认 true；false=不评估不写回，档案保持静态） */
        private boolean enabled = true;

        /** 周期评估间隔（游戏日，默认 30：每 30 游戏日对证据充分的 NPC 评估一次人格偏移） */
        private int assessCycleGameDays = 30;

        /** 证据预筛后送入 AI 的记忆条数上限（默认 20） */
        private int evidenceTopN = 20;

        /**
         * 证据强度最低阈值（默认 6.0：Σ(importance×2×repeat 因子) 低于此值的 NPC 本轮跳过，零 AI——
         * 没有足够经历的人不会变；与设计 §5.5「阈值 T1 以下为 0」对应）
         */
        private double minEvidenceScore = 6.0;

        /**
         * 单次偏移下行告警阈值（默认 0.08：|Δ|>0.08 走 SSE/Unity 下行，验收⑥。
         * <b>注意</b>：必须低于单次偏移上限（maxShiftPerEval=0.10），否则下行永不触发——
         * 0.08 之下 TODDLER（0.05）/INFANT（0）不广播，CHILD（0.10）/青少年（0.20）/成年（0.10）可广播）
         */
        private double minorShiftAlert = 0.08;

        /** 单次偏移上限（默认 0.10：AI 判定 Δ 经本上限截断；创伤例外 0.20 见 ShiftTruncationPolicy） */
        private double maxShiftPerEval = 0.10;

        /** 生命周期累计偏移上限（默认 0.30：同一 OCEAN 维/价值键相对预设基线的累计 |Δ| 封顶） */
        private double maxCumulativeShift = 0.30;

        /** 价值权重单次微调上限（默认 0.05：⑤ 维 valueWeights 每次 Δ∈[-0.05,+0.05]） */
        private double maxValueShiftPerEval = 0.05;

        /**
         * AI 评估失败时是否程序化兜底（默认 true：AI 不可用/失败 → 按证据强度方向程序化小偏移，
         * 零阻断；false=本轮跳过）
         */
        private boolean programmaticFallback = true;
    }

    /** 社会面具配置。 */
    @Data
    public static class Mask {
        /** 社会面具总开关（默认 true；false=表达 PAD=真实 PAD、面具演化停止、S8 仍注入预设文本） */
        private boolean enabled = true;

        /** 破绽机制总开关（默认 true；false=触发物不再临时降抑制，仅保留情境抑制） */
        private boolean breachEnabled = true;

        /** 破绽命中后抑制强度演化步长（默认 0.05：每次破绽 −0.05 向真实靠拢，双向演化见 MaskService） */
        private double breachEvolutionStep = 0.05;

        /** 破绽冷却（游戏分钟，默认 30：同一角色破绽演化冷却，防连击刷演化） */
        private int breachCooldownGameMinutes = 30;

        /** 面具固化：评估周期内高频社交且无破绽 → 抑制 +0.05 封顶预设值（默认 true） */
        private boolean solidifyEnabled = true;

        /** 面具情境解析上限（默认 3：一次表达最多叠加的情境码数，多情境取最大抑制） */
        private int maxContexts = 3;

        /**
         * 表达 PAD 抑制上限（默认 0.80：即使抑制 0.9+，表达 PAD 也保留至少 20% 真实痕迹——
         * 完全无表情的人不真实）
         */
        private double maxSuppression = 0.80;
    }

    //
    // 遗传婚育谱系配置（，10 项 逐条对应）
    //

    /**
     * 遗传婚育谱系配置（， 混合引擎 / 孟德尔位点+遗传度框架 / 三插拔点编排 /
     * 冷却+人口上限，全部可配）。
     */
    @Data
    public static class Reproduction {
        /** 婚育总开关（默认 true；false=心动/求婚/生育全部跳过，谱系只读） */
        private boolean enabled = true;

        // ===== 遗传合成 =====

        /** 遗传度系数 h（默认 0.7 ⇒ h²≈0.49；child = 群体均值 + h×(midparent−均值) + 环境噪声） */
        private double heritability = 0.7;

        /** 表现型高斯噪声 σ（默认 0.05，对齐参考文档 §4.2） */
        private double gaussianNoiseSigma = 0.05;

        /** 从头突变率下限（默认 0.001=0.1%） */
        private double mutationRateMin = 0.001;

        /** 从头突变率上限（默认 0.005=0.5%） */
        private double mutationRateMax = 0.005;

        /** 表观修饰窗口 */
        private int epigeneticsWindowGameDays = 90;

        // ===== 婚恋流程 =====

        /** 婚恋适龄下限（游戏年，默认 18=YOUTH 起，对齐 10 阶段门控 reproduction） */
        private int marriageMinAge = 18;

        /** 婚恋适龄上限（游戏年，默认 60=MIDDLE 末，超龄者不再发展新婚姻） */
        private int marriageMaxAge = 60;

        /** 心动判定好感门槛（默认 60：低于此值连 AI 心动判定的资格都没有——程序化预筛） */
        private int romanceAffinityThreshold = 60;

        /** 心动判定冷却（游戏日，默认 7：同一对候选两次 AI 心动判定至少间隔 7 游戏日） */
        private int attractionCooldownGameDays = 7;

        /** 恋爱期最短时长（游戏日，默认 30：ROMANTIC 边建立满 30 游戏日才允许求婚判定） */
        private int romanceDurationGameDays = 30;

        /** 求婚判定周期（游戏日，默认 15：恋爱期后每 15 游戏日检查一次求婚意愿） */
        private int proposalCheckGameDays = 15;

        // ===== 生育 =====

        /** 孕期时长（游戏日，默认 280=人类真实孕期） */
        private int pregnancyGameDays = 280;

        /** 生育冷却（游戏日，默认 180：每次分娩后 180 游戏日内不再启动新孕期，参考文档 §9） */
        private int cooldownGameDays = 180;

        /** 人口上限（默认 50：基线 20 人预留 2.5 倍增长空间；超出后生育意愿程序化拒绝） */
        private int populationCap = 50;

        /** 生育意愿检查周期（游戏日，默认 30：婚后每 30 游戏日对适龄夫妇检查一次生育意愿） */
        private int fertilityCheckGameDays = 30;

        /** 谱系追溯代数（默认 3：家族树/血亲禁忌判定按三代；直系+三代内旁系禁婚） */
        private int pedigreeGenerations = 3;

        /**
         * AI 软判定失败时的程序化兜底开关（默认 true：心动=好感门槛命中/求婚=最高好感对象/
         * 生育=适龄且冷却到期；false=AI 失败本轮跳过，零推进）
         */
        private boolean programmaticFallback = true;
    }

    /** 婴儿行为配置。 */
    @Data
    public static class Infant {
        /** 婴儿行为总开关（默认 true；false=婴儿只挂存在（位置跟随），无状态机无刺激） */
        private boolean enabled = true;

        /** 饥饿啼哭阈值（默认 35：hunger 跌破即啼哭） */
        private int cryHungerThreshold = 35;

        /** 膀胱啼哭阈值（默认 35：bladder 跌破即啼哭） */
        private int cryBladderThreshold = 35;

        /** 饥饿衰减速率（每游戏小时，默认 2.5：婴儿代谢快，比成人（约 1.2）更快饿） */
        private double hungerDecayPerHour = 2.5;

        /** 膀胱衰减速率（每游戏小时，默认 2.0） */
        private double bladderDecayPerHour = 2.0;

        /** 啼哭刺激产生间隔（游戏小时，默认 1：同一婴儿每小时最多向父母产生一次啼哭刺激，防刷屏） */
        private int cryStimulusIntervalGameHours = 1;

        /** 哺乳恢复量（每次喂养 hunger 恢复值，默认 35：程序化喂养事件） */
        private int feedingRestore = 35;
    }

    /** 身后事/遗物配置。 */
    @Data
    public static class Legacy {
        /** 身后事总开关 */
        private boolean enabled = true;

        /** 继承顺序（默认：配偶 → 长子/长女 → 父母 → 族中公产；按顺序首个在世者承接） */
        private List<String> inheritOrder = new java.util.ArrayList<>(
                java.util.List.of("spouse", "eldest_child", "parent", "clan"));
    }

    /**
     * 发育阶段行为门控配置（O6 发育演化：BrainStrategy stage_gating 策略，
     * priority=90 紧接生理危险强制）。
     * <p>受限阶段（TODDLER/CHILD/ADOLESCENT/ELDER/ANCIENT）角色空闲决策时
     * 由候选池（阶段×时段）产出程序化阶段行为，命中概率触发 AI 润色
     * （scene=growth_stage_behavior 把骨架展开为个人化细节；失败/越界回退骨架）。</p>
     */
    @Data
    public static class StageGating {
        /** 发育阶段门控总开关（默认 true；false=策略不适用，受限阶段回到既有链） */
        private boolean enabled = true;

        /** 特殊 NPC AI 润色概率（默认 0.4：有 11 维档案，润色更个性化） */
        private double polishRateSpecial = 0.4;

        /** 普通 NPC AI 润色概率（默认 0.2：轻量，控制低价值决策的 AI 用量） */
        private double polishRateCommon = 0.2;
    }
}
