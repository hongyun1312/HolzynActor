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

package com.holzyn.actor.ai.nanojev.decision;

/**
 * NanoJev 判定层 · 场景码常量。
 * <p>场景码与 {@code PromptSpecRegistry} 的 {@code nanojev_*} 登记一一对应（骨架登记 +
 * 三场景升版 v2），是 {@code holzyn.actor.ai.nanojev.decision.scenes.*} 逐场景开关的键。</p>
 * <p><b>落地状态</b>：</p>
 * <ul>
 * <li>{@link #SOCIAL_WILLINGNESS}：<b>默认开</b>（一致率 92.3% ≥80% 纳入落地清单；
 * boolean 门控 + 升级策略 top1Prob≥0.5 采纳否则升 Qwen）；</li>
 * <li>{@link #CROWD_DECISION}：<b>默认开</b>（追加：熵最高 1.96 的平坦分布恰是
 * 「真人做琐碎决定的随意感」，judge 自然度 4.38 最高；程序化候选集 + sample 直接采纳）；</li>
 * <li>{@link #SPACE_DECISION}：<b>默认开</b>（空间候选选择：空间 possible_actions +
 * 通用三项 → precondition 程序化优先 + 语义条件 boolean 批量 + choice sample 直接采纳，
 * 无升级阈值——沿 crowd 先例， 逐题确认）；</li>
 * {@link #PERSONA_SHIFT} / {@link #RELATION_DELTA}：<b>默认开</b>（认知 LOD + 高频
 * 微决策， 逐题确认 注意力双通道 / 即时意向 / 事件驱动重算触发 /
 * 人格偏移 / 关系变化量；sample 无升级阈值，全部 fail-closed 有程序化兜底）；</li>
 * <li>{@link #MASK_BREACH}：<b>默认开</b>；</li>
 * <li>{@link #ACCIDENT}：<b>默认关</b>（意外事件 boolean——仅调试开关开启时生效，
 * 涉及死亡/事故高风险，默认关）；</li>
 * <li>其余：<b>实现类就绪、默认关</b>
 * （未过 A/B 评估，0.6B 语义上限风险未验证；后续逐场景评估通过后开开关即可）。</li>
 * </ul>
 * <p>； 增补）</p>
 */
public final class NanoJevDecisionScenes {

    private NanoJevDecisionScenes() {
    }

    /** 社交意愿门控 ——默认开 */
    public static final String SOCIAL_WILLINGNESS = "social_willingness";

    /** 普通 NPC 行动选择（choice；程序化候选集 + sample 直接采纳）——默认开 */
    public static final String CROWD_DECISION = "crowd_decision";

    /**
     * 此刻可发生空间候选选择：空间 possible_actions + 通用三项 →
     * precondition 程序化优先 + 语义条件 boolean 批量（1 state 多题一次前向）+
     * choice sample 直接采纳。
     * 特殊 NPC（DecisionService.generateDecision 前置）与普通 NPC（crowd 统一空间候选）共用。
     * ——默认开
     */
    public static final String SPACE_DECISION = "space_decision";

    /** 心动判定（boolean）——默认关 */
    public static final String ROMANCE_ATTRACT = "romance_attract";

    /** 求婚判定（choice：propose/wait/give_up）——默认关 */
    public static final String PROPOSAL = "proposal";

    /** 生育意愿判定（score 3 档）——默认关 */
    public static final String FERTILITY = "fertility";

    /** 面具情境解析（choice：8 情境码）——默认关 */
    public static final String MASK_CONTEXT = "mask_context";

    /** 面具破绽检测（boolean）——默认关 */
    public static final String MASK_BREACH = "mask_breach";

    /** 打断仲裁/冲突协商（choice：proceed/wait/yield/redirect）——默认关 */
    public static final String NEGOTIATE = "negotiate";

    /** 护栏语义复核（boolean：输出是否越界）——默认关 */
    public static final String GUARD_SEMANTIC = "guard_semantic";

    // ==================== 认知 LOD + 高频微决策（增补） ====================

    /**
     * 认知注意力（score：每 tick 全 NPC 注意力状态批量 + 事件×NPC 相关度批量）——
     * 规则硬基线 + NanoJev 双语义通道驱动认知 LOD（注意力→LOD 档→决策深度闭环）。
     * ——默认开（sample 无升级阈值，fail-closed 有程序化兜底）
     */
    public static final String ATTENTION = "attention";

    /**
     * 每 tick 高频微决策（choice：即时意向——继续当下事/放空片刻/张望四顾/小动作/与他人搭话/
     * 起身活动）——：与注意力 score 同批评估（每 2 tick 默认），产出=低频记忆轨迹 +
     * 极低概率触发打断；大部分「继续当下事」保持行为连续性。——默认开
     */
    public static final String MICRO_DECISION = "micro_decision";

    /**
     * 事件驱动重算触发判定（boolean：情绪漂移/关系跨档/面具情境切换三触发，qid 区分）——
     * 三检测点全挂，NanoJev boolean + 程序化阈值兜底，触发空间重算
     * （GrowthSpaceTrigger.EMOTION_SHIFT / RELATION_JUMP / MASK_CONTEXT_CHANGE，带同码去重窗口）。
     * ——默认开
     */
    public static final String SPACE_TRIGGER = "space_trigger";

    /**
     * 人格偏移评估（score 证据强度 + choice 偏移方向）——：分布尖锐才采纳，
     * 否则升 Qwen 同题重问（沿设计 §2.4）。——默认开
     */
    public static final String PERSONA_SHIFT = "persona_shift";

    /**
     * 关系变化量（score：影响幅度 + 方向）——：重大关系变化后评估影响幅度/方向，
     * 好感/信任平滑更新（替代固定增量）。——默认开
     */
    public static final String RELATION_DELTA = "relation_delta";

    /**
     * 意外事件（boolean：低概率采样）——：涉及死亡/事故高风险，<b>默认关</b>，
     * 仅调试开关开启时生效（逐场景开关即调试闸门）。
     */
    public static final String ACCIDENT = "accident";

    /** 全部落地场景码（调试页/配置遍历序）。 */
    public static final java.util.List<String> ALL = java.util.List.of(
            SOCIAL_WILLINGNESS, CROWD_DECISION, SPACE_DECISION, ROMANCE_ATTRACT, PROPOSAL, FERTILITY,
            MASK_CONTEXT, MASK_BREACH, NEGOTIATE, GUARD_SEMANTIC,
            ATTENTION, MICRO_DECISION, SPACE_TRIGGER, PERSONA_SHIFT, RELATION_DELTA, ACCIDENT);
}
