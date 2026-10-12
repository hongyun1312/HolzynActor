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

package com.holzyn.actor.ai.nanojev.ab;

import com.holzyn.actor.ai.nanojev.NanoJevQuestion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NanoJev A/B 评估 · 三类目标场景的判定题模板。
 * <p><b>模板管理</b>：NanoJev 的 instructions/criteria 视为
 * Prompt——本类为三类场景的实际模板内容，对应 PromptSpecRegistry 中
 * 三场景升版 version 1→2 登记留痕；档位/候选描述<b>必须自包含</b>（改写档位描述会改变
 * 其被报告的索引，官方声明）。后续模板变更按规范 §7 六步流程（改内容→升版→解析器/
 * 测试/冒烟→指标）。</p>
 * <p>设计原则（0.6B 语义上限，设计 §4.6）：判据必须清晰、可枚举、情境自包含——
 * 复杂中文社会推理（宗族旧怨的情绪、话里有话的社交意图）超出 0.6B 能力，模板刻意
 * 收敛为「结构化情境 + 明确判据」。</p>
 */
public final class NanoJevAbTemplates {

    private NanoJevAbTemplates() {
    }

    // ==================== ① 情绪评估（score，nanojev_emotion_eval v2） ====================

    /**
     * 情绪档位题（3 档自包含描述）。
     * <p>判据：依据情境中角色的身体/情绪状态与事件性质，选「此刻情绪最接近」的档位；
     * 与真实 npc_emotion_eval 的 padDelta 语义对齐（负面=档 0 / 平淡=档 1 / 正面=档 2）。</p>
     */
    public static NanoJevQuestion emotionQuestion() {
        return NanoJevQuestion.score(
                "根据情境判断该角色此刻的情绪最接近哪个档位："
                        + "负面情绪（低落/烦躁/委屈/愤怒/恐惧等）选档位0；"
                        + "情绪平淡、无明显波动选档位1；"
                        + "正面情绪（愉悦/满足/兴奋/安心等）选档位2。",
                List.of(
                        "情绪低落/烦躁/委屈等负面情绪明显",
                        "情绪平淡，不好不坏，无明显波动",
                        "心情愉悦/满足/有兴致等正面情绪明显"));
    }

    // ==================== ② 候选选择（choice，nanojev_crowd_decision v2） ====================

    /**
     * 行为候选集通用项（动态候选集之外固定追加的兜底项； ⑮：按样本动态提取
     * 历史 action 输出 + 通用项，保证候选集 5~8 项且含「继续当前行动」合法低权重项）。
     */
    public static Map<String, String> commonChoiceCandidates() {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("continue_current", "继续当前正在做的事");
        c.put("rest", "停下歇一歇，放空一会儿");
        c.put("chat", "找个人说说话");
        return c;
    }

    /**
     * 行为候选选择题（choice，候选集=按样本动态提取 + 通用项）。
     * <p>判据：依据情境中的当前行动、身体/情绪状态、在场者与地点，选该角色<b>此刻最可能</b>
     * 采取的行动；「继续当前行动」是常见倾向，「歇一歇/找人说话」是低频但合法的选择。</p>
     *
     * @param candidates 动态候选集（历史 action 提炼的动作摘要；已含通用项）
     */
    public static NanoJevQuestion crowdQuestion(Map<String, String> candidates) {
        return NanoJevQuestion.choice(
                "根据情境判断该角色此刻最可能采取的行动（选一个）："
                        + "结合其当前正在做的事、身体与情绪状态、周围在场者与地点；"
                        + "「继续当前行动」最常见，偶尔也可能歇一歇或找人说话。",
                candidates);
    }

    // ==================== ③ 社交意愿（boolean，nanojev_social_willingness v2） ====================

    /**
     * 社交意愿题（boolean，带 false/true 判据）。
     * <p>判据：依据身体疲惫度/情绪/正在忙的事/对方是谁与关系/场合，判定<b>此刻</b>是否
     * 愿意停下回应或接话——身体疲惫、情绪低落、正忙、对方是陌生人或关系冷淡 → 拒绝；
     * 有精力、有兴致、关系不错、场合合适 → 愿意。</p>
     */
    public static NanoJevQuestion willingnessQuestion() {
        return NanoJevQuestion.bool(
                "判断该角色此刻是否愿意停下当前的事，回应对方或接话："
                        + "身体疲惫/情绪低落/正忙/对方陌生或关系冷淡/场合不合适 → 不愿意；"
                        + "有精力/有兴致/关系不错/场合合适 → 愿意。",
                "不愿意（疲惫/低落/正忙/对方陌生或关系冷淡/场合不合适）",
                "愿意（有精力/有兴致/关系不错/场合合适）");
    }
}
