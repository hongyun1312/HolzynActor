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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 意图归类 40 例（含否定词与优先级），纯确定性零 Spring。
 */
class IntentClassifierTest {

    private final IntentClassifier classifier = new IntentClassifier();

    private record Case(String type, String detail, IntentKind expected) {
    }

    private static final List<Case> CASES = List.of(
            new Case("move_to", "去集市", IntentKind.TRAVEL),
            new Case("speak_to", "与铁匠攀谈", IntentKind.TALK),
            new Case("wait", "歇一会", IntentKind.REST),
            new Case("continue", "继续手头的事", IntentKind.OTHER),
            new Case("start_action", "拔剑攻击强盗", IntentKind.ATTACK),
            new Case("start_action", "和混混打架", IntentKind.ATTACK),
            new Case("start_action", "对守卫动手", IntentKind.ATTACK),
            new Case("start_action", "修理木门", IntentKind.REPAIR),
            new Case("start_action", "修补渔网", IntentKind.REPAIR),
            new Case("start_action", "打造一把锄头", IntentKind.CRAFT),
            new Case("start_action", "烹饪晚饭", IntentKind.CRAFT),
            // 建造：从无到有也算 CRAFT（与配方 output_kind=facility 同一套规则）
            new Case("start_action", "建造一座铁匠炉", IntentKind.CRAFT),
            new Case("start_action", "搭建草棚", IntentKind.CRAFT),
            new Case("start_action", "修缮屋顶", IntentKind.REPAIR),
            new Case("start_action", "把菜卖掉", IntentKind.TRADE),
            new Case("start_action", "和行商交换货物", IntentKind.TRADE),
            new Case("start_action", "把信交给邮差", IntentKind.GIVE),
            new Case("start_action", "归还借来的书", IntentKind.GIVE),
            new Case("start_action", "去米行买米", IntentKind.TRADE),
            new Case("start_action", "上山采集草药", IntentKind.ACQUIRE),
            new Case("start_action", "使用磨坊", IntentKind.USE),
            new Case("start_action", "打开仓库门", IntentKind.USE),
            new Case("start_action", "沿城墙巡逻", IntentKind.WORK),
            new Case("start_action", "在田里耕种", IntentKind.WORK),
            new Case("start_action", "找镇长聊聊", IntentKind.TALK),
            new Case("start_action", "拜访邻居", IntentKind.TALK),
            new Case("start_action", "前往码头", IntentKind.TRAVEL),
            new Case("start_action", "赶往集市", IntentKind.TRAVEL),
            new Case("start_action", "休息片刻", IntentKind.REST),
            new Case("start_action", "吃点东西", IntentKind.REST),
            new Case("start_action", "不去码头", IntentKind.OTHER),
            new Case("start_action", "不想打架", IntentKind.OTHER),
            new Case("start_action", "没买米", IntentKind.OTHER),
            new Case("start_action", "别修理门", IntentKind.OTHER),
            new Case("start_action", "未使用磨坊", IntentKind.OTHER),
            new Case("start_action", "无人巡逻", IntentKind.OTHER),
            new Case("start_action", "去码头买鱼", IntentKind.TRADE),
            new Case("start_action", "去码头等船", IntentKind.TRAVEL),
            new Case("start_action", "去码头找船夫", IntentKind.TALK),
            new Case("start_action", "做点吃的", IntentKind.CRAFT),
            new Case("start_action", "收购旧书", IntentKind.TRADE),
            new Case("start_action", "点燃炉子", IntentKind.USE),
            new Case("start_action", "整理思绪", IntentKind.OTHER),
            // 装备/修理：装备槽是显式动作；「磨刀」是修理而不是制作
            new Case("start_action", "穿上粗布衣", IntentKind.EQUIP),
            new Case("start_action", "腰间别上柴刀", IntentKind.EQUIP),
            new Case("start_action", "把刀收起来", IntentKind.EQUIP),
            new Case("start_action", "脱下外衣", IntentKind.EQUIP),
            new Case("start_action", "磨刀", IntentKind.REPAIR),
            new Case("start_action", "打磨斧刃", IntentKind.REPAIR),
            // 优先级：拿刀算装备（比「拿」的获取更具体），拿一把刀才落 ACQUIRE
            new Case("start_action", "拿起柴刀", IntentKind.EQUIP),
            new Case("start_action", "拿一把柴刀", IntentKind.ACQUIRE),
            // 否定词仍然有效
            new Case("start_action", "不穿粗布衣", IntentKind.OTHER)
    );

    @Test
    @DisplayName("52 个代表例：显式类型、自由文本优先级、否定词全部命中预期")
    void classifiesFortyRepresentativeCases() {
        // 新增 3 例建造类措辞（建造/搭建/修缮）→ 40 → 43
        // 新增 9 例（装备 4 + 修理 2 + 优先级 2 + 否定 1）→ 43 → 52
        assertEquals(52, CASES.size(), "用例数必须保持 52，增删要同步设计文档口径");
        for (Case c : CASES) {
            assertEquals(c.expected(), classifier.classify(c.type(), c.detail()),
                    "type=" + c.type() + " detail=" + c.detail());
        }
    }

    @Test
    @DisplayName("空类型/空文本归 OTHER，不抛异常")
    void handlesNullAndBlank() {
        assertEquals(IntentKind.OTHER, classifier.classify(null, null));
        assertEquals(IntentKind.OTHER, classifier.classify("start_action", "  "));
        assertEquals(IntentKind.OTHER, classifier.classify("unknown_type", "前往码头"));
    }
}
