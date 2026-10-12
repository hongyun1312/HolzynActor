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

package com.holzyn.actor.domain.growth.stage;

/**
 * 全生命周期发育阶段枚举。
 * <p><b>阶段边界（年龄=游戏年，取区间左闭右开）</b>：</p>
 * <tr><th>码</th><th>中文</th><th>年龄区间</th><th>关键门控摘要</th></tr>
 * <tr><td>INFANT</td><td>婴儿期</td><td>0~2</td><td>无语言输出、无对话、无抽象推理、无长程规划</td></tr>
 * <tr><td>TODDLER</td><td>幼儿期</td><td>2~7</td><td>语言启用、自我中心、无长程规划、时间视野 ≤1 天</td></tr>
 * <tr><td>CHILD</td><td>学龄期</td><td>7~12</td><td>具体推理、同伴认同权重高、时间视野 ≤7 天</td></tr>
 * <tr><td>ADOLESCENT</td><td>青少年期</td><td>12~18</td><td>抽象推理、情绪波动、权威试探、时间视野 ≤30 天、浪漫关系</td></tr>
 * <tr><td>YOUTH</td><td>青年期</td><td>18~30</td><td>全能力、生育峰值、风险偏好高、体力峰值</td></tr>
 * <tr><td>PRIME</td><td>壮年期</td><td>30~45</td><td>能力峰值、责任最重、风险偏好中</td></tr>
 * <tr><td>MIDDLE</td><td>中年期</td><td>45~60</td><td>体能始降、认知保持、长程规划、健康风险起</td></tr>
 * <tr><td>ELDER</td><td>老年期</td><td>60~75</td><td>体能/感官显著退化、重体力门控、社会角色转变</td></tr>
 * <tr><td>ANCIENT</td><td>耄耋期</td><td>75+</td><td>高死亡风险、认知衰退、需照护、重体力/远行门控</td></tr>
 * <tr><td>DECEASED</td><td>已故</td><td>—</td><td>退出世界运行（记忆/关系/影响/遗物保留）</td></tr>
 * </table>
 */
public enum GrowthStage {

    /** 婴儿期 0~2：无语言输出、无对话、无抽象推理、无长程规划 */
    INFANT("婴儿期", 0, 2, "不会说话、不会走路、完全依赖照护者，只有哭闹与咿呀"),
    /** 幼儿期 2~7：语言启用、自我中心、无长程规划、时间视野 ≤1 天 */
    TODDLER("幼儿期", 2, 7, "语言稚嫩简短、自我中心、时间感只有「今天/现在」、注意力短"),
    /** 学龄期 7~12：具体推理、同伴认同权重高、时间视野 ≤7 天 */
    CHILD("学龄期", 7, 12, "具体推理、好动、听大人的话但开始有自己的主意、时间视野约一周"),
    /** 青少年期 12~18：抽象推理、情绪波动、权威试探、时间视野 ≤30 天、浪漫关系 */
    ADOLESCENT("青少年期", 12, 18, "抽象推理开启、情绪起伏大、试探权威、重视同伴评价、可对异性萌生好感"),
    /** 青年期 18~30：全能力、生育峰值、风险偏好高、体力峰值 */
    YOUTH("青年期", 18, 30, "体力与生育力峰值、敢闯敢冒险、体力充沛、可婚育、可远行"),
    /** 壮年期 30~45：能力峰值、责任最重、风险偏好中 */
    PRIME("壮年期", 30, 45, "能力与判断力峰值、家业责任最重、行事稳重、体力保持高位"),
    /** 中年期 45~60：体能始降、认知保持、长程规划、健康风险起 */
    MIDDLE("中年期", 45, 60, "体能开始下降、认知与经验保持、长程规划强、健康风险渐起、重体力开始吃力"),
    /** 老年期 60~75：体能/感官显著退化、重体力门控、社会角色转变 */
    ELDER("老年期", 60, 75, "体能感官显著退化、忌重体力与连夜远行、需午休、把事务交给后辈"),
    /** 耄耋期 75+：高死亡风险、认知衰退、需照护、重体力/远行门控 */
    ANCIENT("耄耋期", 75, Integer.MAX_VALUE, "行动迟缓需照护、不可独行远路、不可守夜劳作、随时可能病倒"),
    /** 已故：退出世界运行（记忆/关系/影响保留） */
    DECEASED("已故", -1, -1, "已不在人世，仅存于他人记忆与编年史");

    /** 中文名 */
    private final String label;
    /** 年龄下界（游戏年，含） */
    private final int minAge;
    /** 年龄上界（游戏年，不含；DECEASED 无意义） */
    private final int maxAge;
    /** 一句话阶段特征（Prompt 注入与门控文案的基础） */
    private final String summary;

    GrowthStage(String label, int minAge, int maxAge, String summary) {
        this.label = label;
        this.minAge = minAge;
        this.maxAge = maxAge;
        this.summary = summary;
    }

    public String label() {
        return label;
    }

    public int minAge() {
        return minAge;
    }

    public int maxAge() {
        return maxAge;
    }

    public String summary() {
        return summary;
    }

    /**
     * 由年龄（游戏年）判定阶段。
     *
     * @param age 年龄（负数/0 → INFANT；DECEASED 不由此推导）
     *
     * @return 阶段
     */
    public static GrowthStage ofAge(int age) {
        int a = Math.max(0, age);
        for (GrowthStage s : values()) {
            if (s == DECEASED) {
                continue;
            }
            if (a >= s.minAge && a < s.maxAge) {
                return s;
            }
        }
        return ANCIENT;
    }

    /**
     * 由阶段码解析（大小写不敏感；未知码返回 null——调用方按零阻断降级）。
     *
     * @param code 阶段码（INFANT…DECEASED）
     *
     * @return 阶段或 null
     */
    public static GrowthStage of(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (GrowthStage s : values()) {
            if (s.name().equalsIgnoreCase(code.trim())) {
                return s;
            }
        }
        return null;
    }

    /** 是否未成年类阶段（INFANT~ADOLESCENT；婚育/重体力等门控的前置判定）。 */
    public boolean isMinor() {
        return this == INFANT || this == TODDLER || this == CHILD || this == ADOLESCENT;
    }

    /** 是否成年类阶段（YOUTH~MIDDLE；生育前置「双方 ADULT 类阶段」）。 */
    public boolean isAdult() {
        return this == YOUTH || this == PRIME || this == MIDDLE;
    }

    /** 是否老年类阶段（ELDER/ANCIENT；衰老门控快速判定）。 */
    public boolean isElder() {
        return this == ELDER || this == ANCIENT;
    }

    /** 是否仍活在世界上（非 DECEASED）。 */
    public boolean isAlive() {
        return this != DECEASED;
    }
}
