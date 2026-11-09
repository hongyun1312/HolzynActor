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

package com.holzyn.actor.domain.world.adjudicate.service;

import com.holzyn.actor.domain.world.adjudicate.model.IntentKind;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 行动意图归类（§4.7）。
 *
 * <p>复用 {@code DecisionReActParse.legacyType} 的关键词思路，但升级为**闭集 + 优先级 +
 * 否定词处理**：显式 {@code move_to/speak_to/wait/continue} 直接映射；{@code start_action}
 * 的自由文本按“攻击 → 修理 → 装备 → 制作 → 交易 → 给予 → 获取 → 使用 → 工作 → 交谈 → 移动 → 休息”
 * 的优先级匹配，且“不去/没去/不想去”中的关键词不算命中。</p>
 *
 * <p>纯确定性、零 AI；未知一律 {@link IntentKind#OTHER}（R-DEFAULT fail-open）。</p>
 */
@Service
public class IntentClassifier {

    /** 否定词（关键词前 0~2 字内出现即视为被否定）。 */
    private static final String NEGATORS = "不没别未无";

    private static final List<String> ATTACK = List.of("攻击", "袭击", "揍", "杀", "拔剑", "战斗", "打架", "动手");
    /**
     * 修理类措辞（起含「磨刀/打磨」）。
     *
     * <p>刻意只收 {@code 磨刀}/{@code 打磨} 两个双字词，<b>不收单独的「磨」</b>：
     * 「磨面粉」是制作，收「磨」会把磨面判成修刀。</p>
     */
    private static final List<String> REPAIR = List.of("修理", "修复", "修补", "加固", "修缮", "磨刀", "打磨",
            "保养");
    /**
     * 装备类措辞：穿/戴/拿起/收起。
     *
     * <p>优先级放在 {@code ACQUIRE} 之前：「拿起刀」里既有「拿」也有「拿起」，
     * 装备是更具体的那一个；只写「拿一把刀」时才落到 ACQUIRE（去取一件东西）。</p>
     */
    private static final List<String> EQUIP = List.of("装备", "佩戴", "穿上", "披上", "戴上", "别上", "系上",
            "拿起", "握住", "握紧", "拔出", "抽出", "收起", "脱下", "摘下", "解下");
    /**
     * 制作与建造（起含「建造」）。
     *
     * <p>为什么建造也算 CRAFT：设计文档标题就是「制作与建造」，两者共用
     * {@code actor_item_recipe}（靠 {@code output_kind} 区分产物进背包还是落成设施），
     * 也共用 R-CRAFT-01 的「材料齐 + 设施可用」判定。另开一个 BUILD 意图会让同一件事
     * 有两套规则与两份提示词。</p>
     *
     * <p>注意优先级：{@code REPAIR} 在 {@code CRAFT} 之前，「修缮屋顶」仍判 REPAIR
     * （既有语义不变）；这里只收「建造/搭建/修建」这类明确的从无到有。</p>
     */
    private static final List<String> CRAFT = List.of("制作", "打造", "烹饪", "酿造", "编织", "炼", "做一把",
            "做点", "建造", "搭建", "修建", "营造", "砌", "盖房");
    /**
     * 交易类措辞（买卖双向）。
     *
     * <p>起「买/购买/收购/进货」也算 TRADE：R-TRADE-01 要判「买方资金够、卖方 stock 够」，
     * 若采购仍归 ACQUIRE（无规则、R-DEFAULT 放行），玩家「赊账买刀」就永远不会走到
     * R-TRADE-02，设计文档场景也复现不了。{@code ACQUIRE} 只保留「取/拿/采集/借」这类
     * 不涉及对价的获得方式。</p>
     */
    private static final List<String> TRADE = List.of("卖", "出售", "交易", "交换", "议价", "讨价还价", "摆摊",
            "买", "购买", "收购", "进货");
    private static final List<String> GIVE = List.of("给", "送给", "交付", "归还", "还钱", "递给", "交给");
    private static final List<String> ACQUIRE = List.of("取", "拿", "收集", "采集", "采", "领取", "借");
    private static final List<String> USE = List.of("使用", "操作", "开启", "打开", "关闭", "点燃", "点火", "驾驶",
            "乘坐", "坐下");
    private static final List<String> WORK = List.of("工作", "干活", "上班", "值班", "巡逻", "耕种", "种地", "砍柴",
            "采矿", "打猎", "捕鱼", "打理");
    private static final List<String> TALK = List.of("找", "见", "聊", "问", "商量", "拜访", "搭话", "交谈", "请教");
    private static final List<String> TRAVEL = List.of("前往", "去", "到", "赴", "回到", "赶往", "走向", "出发");
    private static final List<String> REST = List.of("休息", "睡", "躺", "坐", "发呆", "等待", "等", "吃", "喝",
            "用膳", "进食");

    /**
     * 归类。
     *
     * @param actionType   决策 action_type（move_to/speak_to/wait/start_action/continue）
     * @param actionDetail 自由文本（start_action 的 action_detail）
     *
     * @return 意图闭集
     */
    public IntentKind classify(String actionType, String actionDetail) {
        String type = actionType == null ? "" : actionType.trim();
        String detail = actionDetail == null ? "" : actionDetail.trim();
        switch (type) {
            case "move_to":
                return IntentKind.TRAVEL;
            case "speak_to":
                return IntentKind.TALK;
            case "wait":
                return IntentKind.REST;
            case "continue":
                return IntentKind.OTHER;
            case "start_action":
                return classifyDetail(detail);
            default:
                return IntentKind.OTHER;
        }
    }

    /** 自由文本归类（仅 start_action）。 */
    IntentKind classifyDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return IntentKind.OTHER;
        }
        if (affirmed(detail, ATTACK)) {
            return IntentKind.ATTACK;
        }
        if (affirmed(detail, REPAIR)) {
            return IntentKind.REPAIR;
        }
        if (affirmed(detail, EQUIP)) {
            return IntentKind.EQUIP;
        }
        if (affirmed(detail, CRAFT)) {
            return IntentKind.CRAFT;
        }
        if (affirmed(detail, TRADE)) {
            return IntentKind.TRADE;
        }
        if (affirmed(detail, GIVE)) {
            return IntentKind.GIVE;
        }
        if (affirmed(detail, ACQUIRE)) {
            return IntentKind.ACQUIRE;
        }
        if (affirmed(detail, USE)) {
            return IntentKind.USE;
        }
        if (affirmed(detail, WORK)) {
            return IntentKind.WORK;
        }
        if (affirmed(detail, TALK)) {
            return IntentKind.TALK;
        }
        if (affirmed(detail, TRAVEL)) {
            return IntentKind.TRAVEL;
        }
        if (affirmed(detail, REST)) {
            return IntentKind.REST;
        }
        return IntentKind.OTHER;
    }

    /**
     * 是否至少命中一个未被否定的关键词。
     *
     * <p>否定判定：关键词首次出现位置前 0~2 个字符内出现“不/没/别/未/无”即视为否定。
     * 例：“不去码头”中的“去”被“不”否定；“不想去”中的“去”在 2 字内命中“不”。</p>
     */
    static boolean affirmed(String text, List<String> keywords) {
        for (String kw : keywords) {
            int from = 0;
            while (true) {
                int idx = text.indexOf(kw, from);
                if (idx < 0) {
                    break;
                }
                if (!negated(text, idx)) {
                    return true;
                }
                from = idx + kw.length();
            }
        }
        return false;
    }

    /** 关键词前 2 字内是否有否定词。 */
    private static boolean negated(String text, int idx) {
        int start = Math.max(0, idx - 2);
        for (int i = idx - 1; i >= start; i--) {
            if (NEGATORS.indexOf(text.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }
}
