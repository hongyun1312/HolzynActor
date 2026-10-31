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

package com.holzyn.actor.ai.decision.gate;

import com.holzyn.actor.ai.decision.DecisionQuestion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 决策模型 判定层 · 运行时场景模板。
 * <p><b>模板管理</b>：决策模型 的 instructions/criteria 视为 Prompt——
 * 本类为全部落地场景的实际模板内容，对应 PromptSpecRegistry 中 {@code decision_*} 场景登记
 * 。
 * 档位/候选描述<b>必须自包含</b>（改写档位描述会改变其被报告的索引，官方声明）。</p>
 * <p>设计原则（0.6B 语义上限，N 轨道设计 §4.6）：判据必须清晰、可枚举、情境自包含——
 * 复杂中文社会推理（宗族旧怨的情绪、话里有话的社交意图）超出 0.6B 能力，模板刻意收敛为
 * 「结构化情境 + 明确判据」。社交意愿与候选选择两场景复用 A/B 评估已验证的 v2 内容
 * （DecisionAbTemplates 同款，保证运行时判定与评估口径一致）； 空间候选两题
 * （{@link #spacePreconditionQuestion}/{@link #spaceActionQuestion}）同口径新编
 * 。</p>
 */
public final class DecisionTemplates {

    private DecisionTemplates() {
    }

    // ==================== ① 社交意愿门控（boolean，decision_social_willingness v2） ====================

    /**
     * 社交意愿题。
     * <p>判据：依据身体疲惫度/情绪/正在忙的事/对方是谁与关系/场合，判定<b>此刻</b>是否
     * 愿意停下回应或接话——身体疲惫、情绪低落、正忙、对方是陌生人或关系冷淡 → 拒绝；
     * 有精力、有兴致、关系不错、场合合适 → 愿意。 实测一致率 92.3%，升级阈值 top1Prob=0.5。</p>
     */
    public static DecisionQuestion socialWillingnessQuestion() {
        return DecisionQuestion.bool(
                "判断该角色此刻是否愿意停下当前的事，回应对方或接话："
                        + "身体疲惫/情绪低落/正忙/对方陌生或关系冷淡/场合不合适 → 不愿意；"
                        + "有精力/有兴致/关系不错/场合合适 → 愿意。",
                "不愿意（疲惫/低落/正忙/对方陌生或关系冷淡/场合不合适）",
                "愿意（有精力/有兴致/关系不错/场合合适）");
    }

    // ==================== ② 普通 NPC 行动选择（choice，decision_crowd_decision v2） ====================

    /**
     * 行为候选集通用项（程序化候选集之外固定追加的兜底项；与 评估口径一致：
     * 保证候选集 ≥2 项且含「继续当前行动」合法低权重项——真实的人大多数时候在继续手头的事）。
     */
    public static Map<String, String> commonCrowdCandidates() {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("continue_current", "继续当前正在做的事");
        c.put("rest", "停下歇一歇，放空一会儿");
        c.put("chat", "找个人说说话");
        return c;
    }

    /**
     * 行为候选选择题（choice，候选集=程序化候选 + 通用项）。
     * <p>判据：依据情境中的当前行动、身体/情绪状态、在场者与地点，选该角色<b>此刻最可能</b>
     * 采取的行动；「继续当前行动」是常见倾向，「歇一歇/找人说话」是低频但合法的选择。
     * 本场景 sample 直接采纳（不设升级阈值）——熵最高（1.96）的平坦分布恰是
     * 「真人做琐碎日常决定时的随意」，judge 自然度（4.38）全场景最高。</p>
     *
     * @param candidates 程序化候选集（需求驱动/地点/计划/环境提炼的动作摘要；已含通用项）
     */
    public static DecisionQuestion crowdQuestion(Map<String, String> candidates) {
        return DecisionQuestion.choice(
                "根据情境判断该角色此刻最可能采取的行动（选一个）："
                        + "结合其当前正在做的事、身体与情绪状态、周围在场者与地点；"
                        + "「继续当前行动」最常见，偶尔也可能歇一歇或找人说话。",
                candidates);
    }

    // ==================== ②.5 空间候选选择（space_decision，decision_space_* v2，） ====================

    /**
     * 空间候选前置条件题（boolean，带 false/true 判据）。
     * <p>判定某空间候选的前置条件<b>此刻</b>是否满足——判据=情境文本中的时刻、身体/需求状态、
     * 在场者、天气与地点（这些事实已程序化优先评估，本题只兜底语义性条件：如「若无人提及旧契」）。</p>
     *
     * @param precondition 候选前置条件原文（非空）
     */
    public static DecisionQuestion spacePreconditionQuestion(String precondition) {
        return DecisionQuestion.bool(
                "判断该角色此刻是否满足这个行动前置条件："
                        + precondition.trim()
                        + "——结合情境中的时刻、身体状态、在场者、天气与地点判断，此刻条件成立则满足。",
                "不满足（此刻条件不符）",
                "满足（此刻条件成立）");
    }

    /**
     * 空间行动选择题（choice，候选=空间 possible_actions[带倾向] + 通用三项）。
     * <p>判据：依据情境中的当前行动、身体/情绪状态、地点、内心念头与在场情况，选该角色
     * <b>此刻最可能</b>采取的行动；「继续当前行动」最常见，偶尔也可能歇一歇或找人说话。
     * 候选描述自带「（倾向 x%）」——倾向强度仅供语义参考，最终分布由模型判定（：
     * 权重作情境信息、分布归 决策模型；sample 直接采纳无升级阈值，沿 crowd 先例）。</p>
     *
     * @param candidates 空间候选 + 通用三项（键 → 自包含描述）
     */
    public static DecisionQuestion spaceActionQuestion(Map<String, String> candidates) {
        return DecisionQuestion.choice(
                "根据情境判断该角色此刻最可能采取的行动（选一个）："
                        + "结合其当前正在做的事、身体与情绪状态、地点、内心念头与周围情况；"
                        + "「继续当前行动」最常见，偶尔也可能歇一歇或找人说话；只选此刻真正可能做的。",
                candidates);
    }

    // ==================== ③ 心动判定（boolean，decision_romance_attract v2） ====================
    /**
     * 心动判定题（boolean，带 false/true 判据）。
     * <p>判据：依据双方互动证据与好感度，判定该角色是否对候选对象产生浪漫兴趣——
     * 互动多/相处愉快/好感高/气味相投 → 动心；互动少/平淡/好感一般 → 不动心。
     * 默认关（未过 A/B 评估）；开启时 boolean 门控容错高 + 失败回退 Qwen。</p>
     */
    public static DecisionQuestion romanceAttractQuestion() {
        return DecisionQuestion.bool(
                "判断该角色此刻是否对候选对象动心（产生浪漫兴趣）："
                        + "近期互动多/相处愉快/好感高/性情相投 → 动心；"
                        + "互动少/平淡/好感一般/性情不合 → 不动心。",
                "不动心（互动少/平淡/好感一般/性情不合）",
                "动心（互动多/相处愉快/好感高/性情相投）");
    }

    // ==================== ④ 求婚判定（choice，decision_proposal v2） ====================

    /**
     * 求婚判定题（choice：propose/wait/give_up）。
     * <p>判据：依据恋爱时长与候选对象概况，选该角色此刻的取向——
     * 感情成熟/彼此合适/时机已到 → 求婚；还想再处处/时机未到 → 再等等；"
     * 感情淡了/看不到结果 → 结束这段感情。</p>
     */
    public static DecisionQuestion proposalQuestion() {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("propose", "感情成熟、时机已到，向对方求婚");
        c.put("wait", "还想再处处、时机未到，再等等看");
        c.put("give_up", "感情淡了、看不到结果，结束这段感情");
        return DecisionQuestion.choice(
                "判断该角色此刻对这段恋爱关系的取向（选一个）："
                        + "结合恋爱时长、双方感情与合适程度——感情成熟时机已到 → 求婚；"
                        + "还拿不准/时机未到 → 再等等；感情淡了/看不到结果 → 结束。",
                c);
    }

    // ==================== ⑤ 生育意愿（score，decision_fertility v2） ====================

    /**
     * 生育意愿题（score 3 档，档位描述自包含）。
     * <p>判据：依据婚姻时长/已有子女/年龄/聚落人口，选该角色此刻想要孩子的意愿档位——
     * 新婚燕尔/膝下犹虚/正当壮年 → 想要；顺其自然/已有孩子 → 一般；年岁渐长/负担重 → 不想要。</p>
     */
    public static DecisionQuestion fertilityQuestion() {
        return DecisionQuestion.score(
                "根据情境判断该角色此刻想要孩子的意愿最接近哪个档位："
                        + "新婚燕尔/尚无子女/正当壮年/聚落人丁不旺 → 档位2（意愿强）；"
                        + "已有孩子/顺其自然/安于现状 → 档位1（意愿一般）；"
                        + "年岁渐长/负担沉重/人口已多 → 档位0（意愿弱）。",
                List.of(
                        "意愿弱（年岁渐长/负担沉重/人口已多，不想要）",
                        "意愿一般（已有孩子/顺其自然/安于现状，可有可无）",
                        "意愿强（新婚燕尔/尚无子女/正当壮年，想要孩子）"));
    }

    // ==================== ⑥ 面具情境解析（choice，decision_mask_context v2） ====================

    /**
     * 面具情境解析题（choice：8 个情境码，候选自包含描述）。
     * <p>判据：依据所处场合（地点公私）+ 在场者身份/关系，选该角色此刻最贴切的情境码；
     * 多人在场时消费方按需重复判定（每次一个对象）。默认关（未过 A/B 评估）。</p>
     */
    public static DecisionQuestion maskContextQuestion() {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("before_authority", "面对权威（村长/族长/师父/上官等长辈或官面上的人）");
        c.put("before_partner", "面对伴侣（配偶/恋人，最放松）");
        c.put("before_stranger", "面对陌生人/外来者（不熟、需提防）");
        c.put("before_peer", "面对同伴（同村同辈、相熟邻里）");
        c.put("before_child", "面对子女/晚辈（需要端着长辈的样子）");
        c.put("before_rival", "面对仇怨对象（有过节、心里有疙瘩）");
        c.put("public_place", "公共场合（祠堂/集市/街巷/田间等，人来人往）");
        c.put("private_place", "私人场所（家宅/居室/卧房等，无人旁观）");
        return DecisionQuestion.choice(
                "根据情境判断该角色此刻所处场合与面对的对象（选一个最贴切的情境码）："
                        + "地点是家宅居室等私密处 → 私人场所；在外头/有人来往 → 公共场合；"
                        + "对象身份与关系按字面描述判断。",
                c);
    }

    // ==================== ⑦ 面具破绽检测（boolean，decision_mask_breach v2） ====================

    /**
     * 面具破绽检测题（boolean，带 false/true 判据）。
     * <p>判据：依据情境文本中是否出现该角色的触发物（创伤相关字眼/忌讳话题/酒意状态），
     * 判定此刻是否破绽（面具被撑破、情绪外露）——触发物命中 → 破绽（抑制临时下降）；
     * 未命中 → 无事。默认关（未过 A/B 评估）；开启时 boolean 门控容错高 + 失败回退程序化。</p>
     */
    public static DecisionQuestion maskBreachQuestion() {
        return DecisionQuestion.bool(
                "判断该角色此刻是否因触发物命中而失态破绽（面具被撑破、情绪外露）："
                        + "情境中出现其忌讳/创伤相关的字眼话题，或本人正在饮酒 → 破绽；"
                        + "没有触发物、一切如常 → 无事。",
                "无事（无触发物，面具如常）",
                "破绽（忌讳/创伤字眼或酒意触发，情绪外露）");
    }

    // ==================== ⑧ 打断仲裁/冲突协商（choice，decision_negotiate v2） ====================

    /**
     * 冲突协商判定题（choice：proceed/wait/yield/redirect 四态）。
     * <p>判据：依据双方意图/优先级/目标资源，选该角色此刻最合理的处置——
     * 自己更重要或不愿让 → 仍去（proceed）；可以等 → 稍候再去（wait）；"
     * 对方更需要/自己不急 → 让给对方（yield）；改去别处更划算 → 改道（redirect）。
     * 默认关（未过 A/B 评估）。</p>
     */
    public static DecisionQuestion negotiateQuestion() {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("proceed", "仍去目标地点（自己更需要/不愿相让）");
        c.put("wait", "稍候再去（可以等，等对方用完）");
        c.put("yield", "让给对方（对方更需要/自己不急）");
        c.put("redirect", "改去别处（换个地方更划算）");
        return DecisionQuestion.choice(
                "判断该角色此刻面对目标地点被他人占用时的处置（选一个）："
                        + "结合自己与对方的紧要程度、等待成本与替代选择——"
                        + "自己更紧要 → 仍去；能等 → 稍候再去；对方更紧要 → 让给对方；"
                        + "有更好的别处 → 改道。",
                c);
    }

    // ==================== ⑨ 护栏语义复核（boolean，decision_guard_semantic v2） ====================

    /**
     * 护栏语义复核题（boolean，带 false/true 判据）。
     * <p>判据：依据输出文本与任务要求，判定该输出是否越界（空泛/复读/答非所问/违反要求）——
     * 内容切题、有实质信息 → 合规；空话/复读/跑题 → 越界。默认关（未过 A/B 评估）。</p>
     */
    public static DecisionQuestion guardSemanticQuestion() {
        return DecisionQuestion.bool(
                "判断该角色此次输出是否越界（空泛/复读/答非所问/违背任务要求）："
                        + "内容切题、有实质信息、符合要求 → 合规；"
                        + "空话套话/重复上句/答非所问/违背要求 → 越界。",
                "合规（内容切题、有实质信息、符合要求）",
                "越界（空话/复读/答非所问/违背要求）");
    }

    // ==================== ⑩ 认知注意力（attention，decision_lod_attention v2，） ====================

    /**
     * 注意力状态题（score 4 档，档位描述自包含）。
     * <p>判据：依据情境中的当前行动/身体情绪状态/在场者/最近见闻，判定该角色<b>此刻</b>的
     * 注意力状态——正专注投入 → 高；平常心、边做边想 → 中；疲惫走神/百无聊赖 → 低；
     * 昏沉欲睡/彻底放空 → 无。驱动认知 LOD 语义升降档（：规则硬基线 + 决策模型
     * 双语义通道；sample 无升级阈值，fail-closed 程序化兜底）。</p>
     */
    public static DecisionQuestion attentionStateQuestion() {
        return DecisionQuestion.score(
                "根据情境判断该角色此刻的注意力状态最接近哪个档位："
                        + "正专注投入当前的事、心无旁骛 → 档位3（高）；"
                        + "平常心、边做边想、时有时无 → 档位2（中）；"
                        + "疲惫走神、百无聊赖、心不在焉 → 档位1（低）；"
                        + "昏沉欲睡、彻底放空、什么都不想 → 档位0（无）。",
                List.of(
                        "无（昏沉欲睡/彻底放空/什么都不想）",
                        "低（疲惫走神/百无聊赖/心不在焉）",
                        "中（平常心/边做边想/时有时无）",
                        "高（专注投入/心无旁骛）"));
    }

    /**
     * 事件相关度题（score 4 档，档位描述自包含）。
     * <p>判据：依据该事件的内容与该角色身份/关系/处境/兴趣，判定这事<b>对TA有多相关、
     * 多值得注意</b>——直接牵涉自身或至亲挚友 → 高；邻里相熟/与生计相关 → 中；听说过、
     * 有一搭没一搭 → 低；八竿子打不着 → 无。驱动事件×NPC 相关度语义通道（高相关 → 升档 +
     * 深度处理）。</p>
     *
     * @param eventText 事件文本（自包含描述，非空）
     */
    public static DecisionQuestion eventRelevanceQuestion(String eventText) {
        return DecisionQuestion.score(
                "判断下面这件事对该角色来说有多相关、多值得注意："
                        + eventText.trim()
                        + "——直接牵涉自身或至亲挚友/关乎生计安危 → 档位3（高）；"
                        + "邻里相熟/间接相关 → 档位2（中）；只是听说过/有一搭没一搭 → 档位1（低）；"
                        + "八竿子打不着/完全无关 → 档位0（无）。",
                List.of(
                        "无（八竿子打不着/完全无关）",
                        "低（只是听说过/有一搭没一搭）",
                        "中（邻里相熟/间接相关）",
                        "高（直接牵涉自身或至亲挚友/关乎生计安危）"));
    }

    // ==================== ⑪ 每 tick 高频微决策（micro_decision，decision_micro_decision v2，） ====================

    /**
     * 即时意向题（choice：6 个固定候选，描述自包含）。
     * <p>判据：依据情境中的当前行动/身体情绪/在场者，选该角色<b>此刻</b>最可能的即时意向——
     * 真人大多数时候在继续手头的事（「继续当下事」是绝对多数项），偶尔放空、张望、小动作、
     * 想找人说话或起身活动；低权重项也是合法选项（真人会做反常的事）。产出=低频记忆轨迹 +
     * 极低概率触发打断。</p>
     */
    public static DecisionQuestion microIntentQuestion() {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("continue_current", "继续当下正在做的事，心里没什么波澜");
        c.put("drift", "放空片刻，任由思绪飘一会儿");
        c.put("look_around", "张望四顾，看看周围的人和事");
        c.put("fidget", "做点无意识的小动作（理衣角/敲桌面/踱步）");
        c.put("chat", "起了想找人说话的心思");
        c.put("move_about", "想起身活动活动，换个地方待着");
        return DecisionQuestion.choice(
                "根据情境判断该角色此刻最可能的即时意向（选一个）："
                        + "结合其当前正在做的事、身体与情绪状态、周围在场者；"
                        + "「继续当下事」最常见，其余是偶尔冒出的念头，也都是合法选项。",
                c);
    }

    // ==================== ⑫ 事件驱动重算触发（space_trigger，decision_space_trigger v2，） ====================

    /**
     * 空间重算触发判定题（boolean，带 false/true 判据；qid 区分触发类型）。
     * <p>判据：依据情境中的变化描述，判定这次变化<b>是否值得重新审视</b>该角色的
     * 「此刻可发生空间」——显著的情绪漂移/关系跨档/面具情境切换会改变能做的事与倾向 →
     * 值得；轻微波动/日常变化 → 不值得（保持现状）。：三检测点全挂（情绪漂移/
     * 关系跨档/面具情境切换），决策模型 boolean + 程序化阈值兜底，触发
     * {@code GrowthSpaceService.triggerRegenerate}（带既有同码去重窗口，失败零阻断）。</p>
     *
     * @param changeText 变化描述（自包含，非空）
     */
    public static DecisionQuestion spaceTriggerQuestion(String changeText) {
        return DecisionQuestion.bool(
                "判断该角色这次的变化是否值得重新审视其「此刻可发生空间」（能做的事清单）："
                        + changeText.trim()
                        + "——变化显著、可能改变其能做的事与倾向 → 值得；"
                        + "轻微波动、日常变化 → 不值得。",
                "不值得（轻微波动/日常变化，维持现状）",
                "值得（变化显著，可能改变能做的事与倾向）");
    }

    // ==================== ⑬ 人格偏移评估（persona_shift，decision_shift_eval v2，） ====================

    /**
     * 人格偏移证据强度题（score 4 档，档位描述自包含）。
     * <p>判据：依据情境中的证据集（近期经历/反复出现的行为/关键事件），判定证据对
     * 「该角色正在发生人格偏移」的支持强度——大量一致证据、反复强化 → 强；有些苗头、
     * 方向一致但不突出 → 中；孤例/模糊 → 弱；没有支持 → 无。：分布尖锐才采纳
     * （top1Prob 阈值），否则升 Qwen 同题重问（沿设计 §2.4）。</p>
     */
    public static DecisionQuestion personaShiftEvidenceQuestion() {
        return DecisionQuestion.score(
                "根据情境中的证据集，判断这些证据对「该角色正在发生人格偏移」的支持强度："
                        + "大量一致证据、反复强化、方向明确 → 档位3（强）；"
                        + "有些苗头、方向一致但不突出 → 档位2（中）；"
                        + "孤例、模糊、可作他解 → 档位1（弱）；"
                        + "没有支持证据 → 档位0（无）。",
                List.of(
                        "无（没有支持证据）",
                        "弱（孤例/模糊/可作他解）",
                        "中（有些苗头/方向一致但不突出）",
                        "强（大量一致证据/反复强化/方向明确）"));
    }

    /**
     * 人格偏移方向题（choice：3 个固定候选，描述自包含）。
     * <p>判据：依据情境中的证据集，判定偏移方向——证据多指向「更外向/更开放/更宜人」类
     * 正向特质 → 正向；多指向「更内向/更封闭/更对抗」类 → 负向；矛盾或微弱 → 方向不明。
     * 与证据强度题同 state 一次前向（mixedDecisions）；消费方按既有阶段上限/幅度截断约束
     * 应用偏移（程序化硬约束不变）。</p>
     */
    public static DecisionQuestion personaShiftDirectionQuestion() {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("positive", "正向偏移（更外向/更开放/更宜人/更积极）");
        c.put("negative", "负向偏移（更内向/更封闭/更对抗/更消极）");
        c.put("unclear", "方向不明（证据矛盾或微弱，难以判断）");
        return DecisionQuestion.choice(
                "根据情境中的证据集，判断该角色人格偏移的大致方向（选一个）："
                        + "证据多指向更外向开放宜人积极 → 正向；"
                        + "证据多指向更内向封闭对抗消极 → 负向；"
                        + "证据矛盾或微弱 → 方向不明。",
                c);
    }

    // ==================== ⑭ 关系变化量（relation_delta，decision_relation_delta v2，） ====================

    /**
     * 关系影响幅度题（score 4 档，档位描述自包含）。
     * <p>判据：依据情境中的变化事件与双方关系概况，判定这事对双方关系的影响幅度——
     * 重恩深怨/大起大落 → 大；有实质往来/明显改善或恶化 → 中；日常往来/轻微 → 小；
     * 无关紧要 → 无。与方向题同 state 一次前向（mixedDecisions）；消费方按幅度×方向
     * 对好感/信任做平滑更新。</p>
     */
    public static DecisionQuestion relationDeltaMagnitudeQuestion() {
        return DecisionQuestion.score(
                "根据情境中的变化事件与双方关系概况，判断这事对双方关系的影响幅度："
                        + "重恩深怨、大起大落 → 档位3（大）；"
                        + "有实质往来、明显改善或恶化 → 档位2（中）；"
                        + "日常往来、轻微影响 → 档位1（小）；"
                        + "无关紧要、几乎无影响 → 档位0（无）。",
                List.of(
                        "无（无关紧要/几乎无影响）",
                        "小（日常往来/轻微影响）",
                        "中（有实质往来/明显改善或恶化）",
                        "大（重恩深怨/大起大落）"));
    }

    /**
     * 关系影响方向题（choice：3 个固定候选，描述自包含）。
     * <p>判据：依据情境中的变化事件，判定这事使双方关系<b>变好还是变坏</b>——
     * 互助/示好/成全 → 增进；冲突/失信/伤害 → 恶化；无关或两讫 → 无变化。
     * 与幅度题同 state 一次前向；消费方按「幅度档位 × 方向」平滑更新好感/信任。</p>
     */
    public static DecisionQuestion relationDeltaDirectionQuestion() {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("improve", "增进（互助/示好/成全，关系变好）");
        c.put("worsen", "恶化（冲突/失信/伤害，关系变坏）");
        c.put("unchanged", "无变化（无关紧要或两讫）");
        return DecisionQuestion.choice(
                "根据情境中的变化事件，判断这事使双方关系变好还是变坏（选一个）："
                        + "互助、示好、成全 → 增进；冲突、失信、伤害 → 恶化；"
                        + "无关紧要或两不相欠 → 无变化。",
                c);
    }

    // ==================== ⑮ 意外事件（accident，decision_accident v2，） ====================

    /**
     * 意外事件判定题（boolean，带 false/true 判据）。
     * <p>判据：依据情境中的活动/环境/身体状态，判定该角色此刻是否遭遇小意外（扭伤/磕碰/
     * 受凉/失手等）——在危险处劳作/恶劣天气/身体虚弱/疲劳过度 → 概率略高，但总体仍是
     * 低概率事件；平常活动 → 无事。：<b>默认关</b>，仅调试开关开启时生效
     * （boolean 低概率采样；涉及健康影响与世界事件，不致死）。</p>
     */
    public static DecisionQuestion accidentQuestion() {
        return DecisionQuestion.bool(
                "判断该角色此刻是否遭遇一个小意外（扭伤/磕碰/受凉/失手打翻等）："
                        + "在危险处劳作/恶劣天气/身体虚弱/疲劳过度 → 有些可能，但仍属低概率；"
                        + "平常活动、一切如常 → 无事。",
                "无事（平常活动，一切如常）",
                "小意外（危险劳作/恶劣天气/虚弱疲劳等诱因下偶发）");
    }
}
