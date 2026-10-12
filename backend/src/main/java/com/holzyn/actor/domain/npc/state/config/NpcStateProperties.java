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

package com.holzyn.actor.domain.npc.state.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * NPC 状态模块配置。
 * <p>职责：承载状态模块全部可调参数——衰减率（每游戏小时）/睡眠恢复/人群衰减缩放/urgency 阈值/
 * 健康侵蚀恢复/行动恢复量（§11 配置表）。代码默认值与 application.yml 同步。</p>
 * <p>设计依据：《NPC状态模块_主开发方案》§6（衰减/恢复）/ §11（配置表）。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "holzyn.actor.npc-state")
public class NpcStateProperties {

    /** 总开关（默认开；程序化零成本） */
    private boolean enabled = true;

    /** 衰减参数块 */
    private Decay decay = new Decay();

    /** 人群衰减整体缩放（crowd 轻量列衰减 × 此系数） */
    private double crowdDecayScale = 1.0;

    /** urgency 阈值块 */
    private Urgency urgency = new Urgency();

    /** 健康联动块 */
    private Health health = new Health();

    /** 行动恢复量块（§6.2 行动类型 → 需求恢复映射） */
    private Recovery recovery = new Recovery();

    /** 情绪模型块 */
    private Emotion emotion = new Emotion();

    /** 注入块 */
    private Inject inject = new Inject();

    /** 分层驱动块 */
    private Drive drive = new Drive();

    /**
     * 生理需求→行动决策耦合块（2026-10-02 生理需求耦合轮：把需求衰减转化为实际行动——
     * 低值区候选注入 / 危险区程序化强制，对齐既有「行为状态衰减对策」）。
     */
    private Physio physio = new Physio();

    /**
     * 生理需求→行动决策耦合参数。
     * <p>两档梯度：需求值 &lt; <b>低值阈值</b> → 生理行动候选注入决策候选池（AI/NanoJev
     * 在候选内选择，带「（倾向 x%）」情境权重）；需求值 &lt; <b>危险阈值</b> → 程序化强制
     * （0 AI：无论决策触发源，决策环节直接产出生理行动；核心角色另经实时打断通道
     * PHYSIOLOGICAL_URGE 放下手头事）。</p>
     * <p>三餐窗口仅约束<b>进食</b>候选（真人不会半夜正经吃饭，但饿到危险区不受窗口限制）；
     * 饮水/如厕/清洗不受窗口约束（随时可发生）。</p>
     */
    @Data
    public static class Physio {
        /** 耦合总开关（默认 true；false=行为回到修复前，仅状态文本注入） */
        private boolean enabled = true;
        /** 低值注入阈值·饥饿（低于 → 候选注入「去吃饭」；默认 45） */
        private double lowHunger = 45.0;
        /** 低值注入阈值·口渴（默认 45） */
        private double lowThirst = 45.0;
        /** 低值注入阈值·膀胱（默认 35） */
        private double lowBladder = 35.0;
        /** 低值注入阈值·清洁（默认 50） */
        private double lowHygiene = 50.0;
        /** 危险强制阈值·饥饿（低于 → 程序化强制进食，可打断；默认 20） */
        private double dangerHunger = 20.0;
        /** 危险强制阈值·口渴（默认 20） */
        private double dangerThirst = 20.0;
        /** 危险强制阈值·膀胱（默认 15） */
        private double dangerBladder = 15.0;
        /** 危险强制阈值·清洁（默认 25） */
        private double dangerHygiene = 25.0;
        /** 三餐窗口（进食候选仅在窗口内注入；危险区不受限；默认早 6~9/午 11~14/晚 17~20） */
        private List<MealWindow> mealWindows = new java.util.ArrayList<>(List.of(
                new MealWindow(6, 9), new MealWindow(11, 14), new MealWindow(17, 20)));
        /**
         * 轻行动抑制开关（默认 true）：生理候选注入命中时，候选池的通用「歇一歇/找人说说话」
         * 轻行动不再注入（「继续当前」恒在）——饿了的人不会先放空，实质行动占比上升
         * （次对接文档 §6.1「轻行动 vs 实质行动配比控制」落地）
         */
        private boolean lightActionSuppressEnabled = true;
        /**
         * 危险区实时打断开关（默认 true，仅核心角色生效）：感知 tick 检测到危险区 →
         * 经 RealTimeDecisionService 触发 PHYSIOLOGICAL_URGE 刺激打断当前行动；
         * 普通 NPC 不触发即时决策（人群走分层概率化，危险区在其程序化调度内强制）
         */
        private boolean dangerInterruptEnabled = true;

        /**
         * 用餐窗口（左闭右开：[startHour, endHour)）。
         *
         * @param startHour 开始小时（0-23）
         *
         * @param endHour 结束小时（1-24；跨 0 点窗口用 startHour&gt;endHour 表达）
         */
        @Data
        public static class MealWindow {
            private int startHour;
            private int endHour;

            public MealWindow() {
            }

            public MealWindow(int startHour, int endHour) {
                this.startHour = startHour;
                this.endHour = endHour;
            }

            /**
             * 当前小时是否落入本窗口（[start, end) 左闭右开；start&gt;=end 视为跨零点窗口）。
             *
             * @param hour 当天小时（0-23）
             *
             * @return true=窗口内
             */
            public boolean contains(int hour) {
                if (startHour < endHour) {
                    return hour >= startHour && hour < endHour;
                }
                // 跨零点（如 22~2）：hour >= start || hour < end
                return hour >= startHour || hour < endHour;
            }
        }
    }

    /** 人群 AI 决策阈值（设计 §8.2：场景人数 ≤ 此值 → 人群也走 AI 自主决策/情绪 AI 评估，默认 20） */
    private int aiCrowdThreshold = 20;

    /** 历史快照块 */
    private History history = new History();

    /** 睡眠恢复参考时段（当日小时落入 [sleepStartHour, 24) ∪ [0, sleepEndHour) 视为夜间睡眠，精力恢复） */
    private int sleepStartHour = 22;

    /** 睡眠恢复参考时段结束小时（见 sleepStartHour） */
    private int sleepEndHour = 6;

    /** 衰减率（每游戏小时；主开发方案 §6.1 建议默认） */
    @Data
    public static class Decay {
        /** 饥饿衰减 /游戏小时（默认 1.2：约 12 游戏小时从饱腹到告急） */
        private double hungerPerHour = 1.2;
        /** 口渴衰减 /游戏小时（默认 2.0：渴得快） */
        private double thirstPerHour = 2.0;
        /** 精力衰减 /游戏小时（清醒活动时，默认 1.0；睡眠时段反向恢复） */
        private double energyPerHour = 1.0;
        /** 膀胱衰减 /游戏小时（默认 1.5；睡眠时段不下降） */
        private double bladderPerHour = 1.5;
        /** 清洁衰减 /游戏小时（默认 0.3：慢） */
        private double hygienePerHour = 0.3;
        /** 社交衰减 /游戏小时（默认 0.4：独处时） */
        private double socialPerHour = 0.4;
        /** 趣味衰减 /游戏小时（默认 0.5：慢） */
        private double funPerHour = 0.5;
        /** 睡眠时段精力恢复 /游戏小时（默认 +10） */
        private double sleepRestorePerHour = 10.0;
        /** 低值区（&lt;20）衰减放缓开关（默认 false；true=低于阈值按 50% 衰减，接近 0 放缓） */
        private boolean taper = false;
        /** 低值区放缓阈值（taper=true 时生效，默认 20） */
        private double taperThreshold = 20.0;
    }

    /** urgency 紧急状态阈值（§6.3：0 无 / 1 轻度&lt;30 / 2 强烈&lt;15 / 3 健康告急&lt;25） */
    @Data
    public static class Urgency {
        /** 强烈阈值（level 2，任一需求低于此值，默认 15） */
        private double thresholdStrong = 15.0;
        /** 轻度阈值（level 1，任一需求低于此值，默认 30） */
        private double thresholdMild = 30.0;
        /** 健康告急阈值（level 3，health 低于此值，默认 25） */
        private double healthAlert = 25.0;
    }

    /** 健康联动（§6.4：长期低需求侵蚀 / 恢复后回升） */
    @Data
    public static class Health {
        /** 生理需求长期低于侵蚀阈值的健康下降速率 /游戏小时（默认 0.5） */
        private double corrosionRate = 0.5;
        /** 生理需求恢复后的健康回升速率 /游戏小时（默认 0.2） */
        private double recoverRate = 0.2;
        /** 生理需求侵蚀判定阈值（任一生理四需低于此值开始侵蚀健康，默认 20） */
        private double corrosionThreshold = 20.0;
        /** 健康回升判定阈值（全部生理四需高于此值开始回升，默认 70） */
        private double recoverThreshold = 70.0;
    }

    /** 行动恢复量（§6.2 行动类型 → 需求恢复映射，可配置 ±，封顶 100 下限 0） */
    @Data
    public static class Recovery {
        /** 进食恢复饥饿（默认 +40） */
        private int eatHunger = 40;
        /** 饮水恢复口渴（默认 +35） */
        private int drinkThirst = 35;
        /** 睡眠/休息恢复精力（默认 +45） */
        private int sleepEnergy = 45;
        /** 如厕恢复膀胱（默认 +55） */
        private int toiletBladder = 55;
        /** 洗浴恢复清洁（默认 +50） */
        private int washHygiene = 50;
        /** 交谈/社交恢复社交（默认 +25） */
        private int chatSocial = 25;
        /** 娱乐/消遣恢复趣味（默认 +25） */
        private int playFun = 25;
        /** 治疗/就医恢复健康（默认 +30） */
        private int treatHealth = 30;
        /** 睡眠/休息附带健康微恢复（默认 +5） */
        private int sleepHealth = 5;
    }

    /** 情绪模型 */
    @Data
    public static class Emotion {
        /** AI 评估写回开关（默认 true；关=纯程序化，对齐设计 §11 emotion.ai-eval-enabled） */
        private boolean aiEvalEnabled = true;
        /** AI 评估冷却（每 NPC 每游戏小时最多评估 1 次；对话/行动/事件共用，默认 1） */
        private double aiEvalCooldownHours = 1.0;
        /** 世界事件触发 AI 评估的重要度阈值（事件重要度 ≥ 此值才评估，默认 3） */
        private int evalEventImportance = 3;
        /** 情绪衰减半衰期（游戏小时，指数衰减回平静锚点，默认 6，对齐 §7.1/§11） */
        private double decayHalfLifeHours = 6.0;
        /** OCC 显式三维评价增强开关（默认 false：忽略 OCC 字段只走 emotion+padDelta 主路径；true=合意性/称赞性/喜好性规则映射） */
        private boolean occEnabled = false;
        /** OCC 合意性 → P 轴权重（desirability，默认 0.5） */
        private double occDesirabilityWeight = 0.5;
        /** OCC 称赞性 → D 轴权重（praiseworthiness，默认 0.3） */
        private double occPraiseworthinessWeight = 0.3;
        /** OCC 喜好性 → P/D 双轴权重（liking，默认 0.5） */
        private double occLikingWeight = 0.5;
        /** mood 情绪积分权重（0~1：mood = 需求mood×(1-w) + 情绪分×w + env，默认 0.3，§7.3） */
        private double moodWeight = 0.3;
        /** 心情协同阈值（§7.3：mood 低于此值 → 负面情绪衰减放慢，默认 50） */
        private double moodSynergyThreshold = 50.0;
        /** 心情协同衰减系数（mood 低于阈值且 P<0 时，衰减量 × 此系数放慢，默认 0.5） */
        private double moodSynergyFactor = 0.5;
    }

    /** 注入 */
    @Data
    public static class Inject {
        /** 状态段注入 token 预算（设计 §11 inject.state-budget，默认 300；超预算丢低优先级描述） */
        private int stateBudget = 300;
        /** 决策注入开关（设计 §11 inject.inject-ai-decision，默认 true；false=决策不注入【当前状态】段） */
        private boolean injectAiDecision = true;
        /** 对话注入开关（设计 §11 inject.inject-dialog，默认 true；false=对话不注入【当前状态】段） */
        private boolean injectDialog = true;
    }

    /** 分层驱动 */
    @Data
    public static class Drive {
        /**
         * 人群概率化驱动开关（设计 §11 drive.crowd-probabilistic-enabled，默认 true；
         * 场景人数 > ai-crowd-threshold 时按 Sims 概率模型 + 需求-地点/对象评分驱动人群行为）
         */
        private boolean crowdProbabilisticEnabled = true;
        /** 概率化触发的需求阈值（需求值低于此值才纳入概率化候选，默认 60——与轻度阈值 30 不同，覆盖面更宽） */
        private double probabilisticThreshold = 60.0;
        /** 概率化动作选择后是否移动至目标地点（真实执行档；false=仅更新动作文本不迁移位置） */
        private boolean moveToTarget = true;
    }

    /**
     * 历史快照（§12， actor_state_history 曲线数据源——采样时机/保留条数；
     * 采样粒度可配 sample-minutes + 保留上限自动放大）
     */
    @Data
    public static class History {
        /** 历史快照采样开关（默认 true；false=引擎推进时不采样，曲线无新数据） */
        private boolean enabled = true;
        /**
         * 采样粒度（游戏分钟，默认 60=每小时；可切 5=细粒度演示；采样门控按此粒度边界，
         * advance/onCrowdSchedule 统一口径——世界演化与游戏时间系统 引入）
         */
        private int sampleMinutes = 60;
        /**
         * 每项目每 NPC 保留快照条数上限（默认 168 ≈ 7 游戏日@每小时；超出滚动删除最旧；
         * 采样粒度切 5 分钟时经 {@link #effectiveMaxPerNpc()} 自动放大，本值仍可手动调大）
         */
        private int maxPerNpc = 168;

        /**
         * 有效保留条数上限：
         * 采样粒度 ≤5 游戏分钟时按「7 游戏日」基线自动放大——每小时粒度 168 条≈7 游戏日
         * → 5 分钟粒度 7×24×12=2016 条≈7 游戏日；配置 max-per-npc 仍可手动调大覆盖
         * （Math.max 保证至少覆盖 7 游戏日，手动调大 >2016 时以手动值为准）。
         *
         * @return 生效的每 NPC 保留条数上限
         */
        public int effectiveMaxPerNpc() {
            if (sampleMinutes > 0 && sampleMinutes <= 5) {
                return Math.max(2016, maxPerNpc);
            }
            return maxPerNpc;
        }
    }
}
