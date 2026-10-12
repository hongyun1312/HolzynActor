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

package com.holzyn.actor.domain.agent.runtime;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 实时行为决策系统配置。
 * <p>职责：承载即时决策通道的可调参数——总开关 / 事件重要度阈值 / 每角色冷却（成本护栏前两道，
 * 对齐主开发方案《HolzynActor_实时行为决策系统_开发路径》§4.6 / §6.2）。
 * 代码默认值与 application.yml 同步（同字段缺省值，环境变量可覆盖）。</p>
 * <p>仅启用本节参数； / 的 chain-reaction-enabled / personality-inject-enabled /
 * time-sense-enabled / idle-behavior-enabled / micro-motion-enabled 按里程碑落地时并入本节
 * （HarmonyOS 参考文档 §5 已按完整配置表列示）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "holzyn.actor.realtime-decision")
public class RealtimeDecisionProperties {

    /** 即时决策总开关 */
    private boolean enabled = true;

    /**
     * 事件重要度阈值（默认 3：事件重要度 ≥ 此值才触发在场/相关角色即时决策；
     * 低重要度事件只记录不触发——成本护栏③，对齐 §4.6）
     */
    private int eventImportanceThreshold = 3;

    /**
     * 每角色即时决策冷却（游戏分钟，默认 10：同角色 10 游戏分钟内最多 1 次即时决策；
     * 紧急状态（urgency≥2）可突破——成本护栏②，对齐 §4.6；游戏时间基准=引擎推进位置
     * currentEngineGameSecond，世界暂停时冷却自然冻结）
     */
    private int cooldownGameMinutes = 10;

    /**
     * 打断与恢复总开关（默认 true；false=InterruptRecoveryService.interrupt/maybeResume 全部跳过，
     * 行为回到 （即时决策但不记录打断/不恢复），）
     */
    private boolean interruptEnabled = true;

    /**
     * 打断记录恢复过期窗口（游戏分钟，默认 60：被打断任务超过该游戏时长未恢复即放弃——
     * - 共性框架兜底判 resume/abandon 的依据；配置 <=0 视为不设过期）
     */
    private int interruptExpireGameMinutes = 60;

    /**
     * 即时决策防重入重试次数（默认 20：多角色并发刺激被 AgentRuntime 全局防重入拦截时，
     * 即时通道自动重试保证每个刺激最终完成「决策→行动→恢复」闭环；0=不重试[被拦截即跳过]）
     */
    private int stimulusRetryMaxAttempts = 20;

    /** 即时决策防重入重试间隔（毫秒，默认 3000：每次重试前等待，让在途决策循环释放全局锁） */
    private long stimulusRetryWaitMs = 3000;

    /**
     * 个性注入 + 社交差异化（，domain/agent/runtime PersonalityInjectionService）：
     * 【角色个性】段注入总开关（默认 true；false=即时决策回退 纯刺激情境、
     * 恢复决策回退 轻量性格组装、打断前探针的人物信息回退轻量组装）
     */
    private boolean personalityInjectEnabled = true;

    /** 【角色个性】段总长上限（字符，默认 600：超长截断防撑爆决策 Prompt；下限 100） */
    private int personalityMaxLength = 600;

    /** 打断前 AI 探针总开关 */
    private boolean interruptProbeEnabled = true;

    /**
     * 强制打断事件重要度阈值（默认 4：事件重要度 ≥ 此值= 重大/安全威胁 → 跳过探针强制打断；
     * / 常规刺激才由 AI 探针按性格判断；对齐主开发方案 §4.3 - 共性框架）
     */
    private int probeForceImportanceThreshold = 4;

    // ==================== 链式反应 + 独处 + 时间感 + 小动作 ====================

    /**
     * 链式反应总开关（默认 true：特殊 NPC 行动执行后，同地点其他特殊 NPC + 关系密切[好感/信任≥阈值]
     * 的异地相关人物经新刺激类型 CHAIN_REACTION 走完整即时决策管线；false=回到 kind=action 事件
     * 路径[同地点触发]——对齐主开发方案 §4.5 真人感五维度之链式反应 / §4.6 成本护栏⑤扩散限制）
     */
    private boolean chainReactionEnabled = true;

    /** 关系网扩散阈值（好感或信任 ≥ 此值视为「关系密切」，默认 60：异地相关人物才被链式触发） */
    private int chainRelatedThreshold = 60;

    /** 每行动异地相关人物触发上限（默认 3：按好感/信任降序截断，成本护栏防风暴） */
    private int chainRelatedMax = 3;

    /**
     * 人群围观行动重要度阈值（默认 4：决策 urgency ≥ 此值的重大行动 → 同地点人群程序化围观/议论/躲开，
     * 零 AI；低重要度行动人群不围观）
     */
    private int chainCrowdImportance = 4;

    /** 围观人群 NPC 数量上限（默认 3） */
    private int chainCrowdMax = 3;

    /**
     * 独处上下文注入开关（默认 true：周期决策/即时决策组装情境时，若同地点无其他特殊 NPC 且无人群 NPC，
     * 注入【独处】段让 AI 决定独处行为；false=不注入，行为回到）
     */
    private boolean solitudeEnabled = true;

    /**
     * 时间感行动开关（默认 true：AI 决策输出支持 wait_minutes 等待字段 → 等待类计划
     * [actor_plan.planned_game_second 游戏时刻到点 → 重新 AI 决策]；false=忽略 wait_minutes 立即执行）
     */
    private boolean timeSenseActionEnabled = true;

    /** 等待时长上限（游戏分钟，默认 720=12 游戏小时：AI 输出的 wait_minutes 超过此值钳制，防异常等待） */
    private int waitMaxGameMinutes = 720;

    /** 连续等待上限 */
    private int waitMaxConsecutive = 2;

    /**
     * 生理小动作总开关（默认 true：状态写回时按状态程序化生成小动作文本[零 AI]，进 thought_text + SSE，
     * 特殊 NPC 与人群都适用；false=不生成）
     */
    private boolean microMotionEnabled = true;

    /** 小动作生成冷却（游戏分钟，默认 30：每 NPC 每 N 游戏分钟最多生成一次小动作，防刷屏） */
    private int microMotionCooldownGameMinutes = 30;

    /** 小动作随机点缀概率（0~1，默认 0.10：无状态触发时小概率随机一个小动作，让世界更活） */
    private double microMotionRandomProbability = 0.10;

    // ==================== 环境刺激 + 护栏全量 + 空闲降频 ====================

    /**
     * 环境刺激总开关（默认 true：消费世界帧产出[帧 diff 天气/环境变化] + 信息包扩散到达 →
     * 特殊 NPC 即时决策[StimulusType.ENVIRONMENT] + 人群轻量 AI[预算门控]；false=全部跳过，
     * 行为回到）
     */
    private boolean environmentEnabled = true;

    /**
     * 环境刺激独立冷却（游戏分钟，默认 30：同角色 30 游戏分钟内最多 1 次环境刺激即时决策——
     * 护栏①：环境变化（天气骤变/扩散到达）高频但每角色冷却防刷屏；与普通刺激冷却键独立
     * [StimulusType.ENVIRONMENT 专属键]，不挤占普通冷却）
     */
    private int environmentCooldownGameMinutes = 30;

    /**
     * 单帧 diff 刺激条数上限（默认 5：一次帧变化最多产生 5 条环境刺激，防风暴—— 护栏②；
     * 超过按优先级[天气>环境变化]截断）
     */
    private int environmentMaxDiffPerFrame = 5;

    /**
     * 每日环境刺激 AI 预算（默认 30：每项目每游戏日环境刺激触发的 AI 决策[特殊 NPC 即时决策 +
     * 人群轻量 AI]合计上限，超限跳过不触发—— 护栏⑤，防环境刺激日级风暴；0=不设限）
     */
    private int environmentDailyMax = 30;

    /**
     * 单次环境刺激人群轻量 AI 上限（默认 5：受影响区域人群逐人轻量 AI 决策[预算门控]的上限，
     * 防大场景人群同时调 AI）
     */
    private int environmentCrowdMax = 5;

    /**
     * 空闲降频总开关（默认 true：特殊 NPC 有进行中行动[最近决策过]且无新刺激时，该游戏小时的
     * 周期决策跳过[延续行动零 AI]，行动完成[窗口过后]或有刺激[即时通道]立即恢复——真人不会
     * 每分钟都想一次「我该干嘛」，省 AI 成本且更真实；false=回到 每小时全量决策）
     */
    private boolean idleReductionEnabled = true;

    /**
     * 空闲降频窗口（游戏分钟，默认 120：角色最近一次决策距当前不足该时长 → 本次周期决策跳过；
     * 超过该时长视为「行动完成/需要新决策」恢复周期通道）
     */
    private int idleDecisionWindowGameMinutes = 120;

    // ==================== 决策与协调（，现状优化轨道 §7.2） ====================

    /**
     * 轻量前瞻推演配置（前缀 holzyn.actor.realtime-decision.foresight.*；
     * 触发判定=事件重要度≥阈值 ∪ urgency≥阈值 ∪ 刺激文本命中场景关键词）。
     */
    private Foresight foresight = new Foresight();

    /** 决策 state delta 配置（前缀 holzyn.actor.realtime-decision.state-delta.*）。 */
    private StateDelta stateDelta = new StateDelta();

    /** 冲突检测与 ToM 配置（前缀 holzyn.actor.realtime-decision.coordination.*）。 */
    private Coordination coordination = new Coordination();

    /**
     * 前瞻推演配置（嵌套类，Spring Boot 配置绑定 realtime-decision.foresight.*）。
     * <p>对齐现状优化轨道 §6.2：importance-threshold=4 / urgency-threshold=2 /
     */
    @lombok.Data
    public static class Foresight {
        /** 前瞻总开关 */
        private boolean enabled = true;

        /** 事件重要度触发阈值（默认 4：刺激事件重要度 ≥ 此值 → 触发前瞻推演） */
        private int importanceThreshold = 4;

        /** 紧急度触发阈值（默认 2：角色 urgency ≥ 此值 → 触发前瞻推演） */
        private int urgencyThreshold = 2;

        /** 候选行动数上限（默认 3：提示 AI 输出 ≤3 个候选；AI 超量解析时截断） */
        private int maxCandidates = 3;

        /**
         * 宏观输入注入开关（默认 true：触发时向前瞻段注入 actor_world_macro_snapshot 摘要
         * [粮价/库存/民怨/议题/势力]——宏观 ~ 已交付， 注入）
         */
        private boolean macroInjectEnabled = true;

        /** 资源获取场景关键词（刺激文本命中任一 → 触发前瞻； scenarios=resource） */
        private java.util.List<String> resourceKeywords = java.util.List.of(
                "摊位", "集市", "铺", "药材", "粮食", "货物", "货", "争", "抢", "收购", "贩卖", "租", "买", "卖", "订", "采买", "进货");

        /** 人际关系变动场景关键词（scenarios=relation） */
        private java.util.List<String> relationKeywords = java.util.List.of(
                "信任", "关系", "承诺", "结盟", "结亲", "求婚", "决裂", "和好", "求助", "求亲", "谈婚", "拜师", "收徒", "翻脸", "恩怨");

        /** 位置变更场景关键词（scenarios=location） */
        private java.util.List<String> locationKeywords = java.util.List.of(
                "前往", "离开", "搬迁", "搬家", "迁居", "移居", "远行", "出远门", "定居", "动身", "赶路");

        /** 前瞻段最大长度（字符，默认 400：超长截断防撑爆决策 Prompt） */
        private int sectionMaxLength = 400;
    }

    /**
     * state delta 配置（嵌套类，配置绑定 realtime-decision.state-delta.*）。
     * <p>对齐现状优化轨道 §6.2：state-delta.enabled=true。</p>
     */
    @lombok.Data
    public static class StateDelta {
        /** delta 落库总开关（默认 true；false=不落 delta，恢复校验/调试回看降级为无 delta 信息） */
        private boolean enabled = true;

        /**
         * delta 有效窗口（游戏分钟，默认 120：决策产生后超过该游戏时长视为「计划已过时效」——
         * 打断恢复校验/共性框架兜底据此判定原计划失效；<=0 视为不设时效）
         */
        private int expireGameMinutes = 120;
    }

    /**
     * 冲突检测与 ToM 配置（嵌套类，配置绑定 realtime-decision.coordination.*）。
     * <p>对齐现状优化轨道 §6.2：conflict-detect-enabled=true / negotiate-ai-enabled=true /
     */
    @lombok.Data
    public static class Coordination {
        /** 冲突检测总开关 */
        private boolean conflictDetectEnabled = true;

        /**
         * 冲突二次协商 AI 开关（默认 true：冲突且本方可让时预算内 LLM 二次协商[scene=negotiate]；
         * false/预算不足/失败 → 确定性等待/改道兜底）
         */
        private boolean negotiateAiEnabled = true;

        /** 协商最大输出 token（默认 200） */
        private int negotiateMaxTokens = 200;

        /** 协商重试次数（默认 1） */
        private int negotiateMaxRetry = 1;

        /**
         * 资源占用登记开关（默认 true：目标命中地点性资源时登记 actor_resource_occupancy；
         * 混合=既有判定+资源登记）
         */
        private boolean occupancyEnabled = true;

        /** 资源占用时长上限（游戏分钟，默认 120：占用到期=开始+行动耗时，封顶该值防长期占位） */
        private int occupancyMaxGameMinutes = 120;

        /** 确定性兜底等待时长（游戏分钟，默认 20：协商不可用时 loser 在原地等待后重试） */
        private int fallbackWaitGameMinutes = 20;

        /**
         * ToM 深度参数注入总开关（默认 true：按宜人性×神经质推断深度档注入各场景 Prompt；
         * false=不注入，行为回到 前）
         */
        private boolean tomInjectEnabled = true;
    }
}
