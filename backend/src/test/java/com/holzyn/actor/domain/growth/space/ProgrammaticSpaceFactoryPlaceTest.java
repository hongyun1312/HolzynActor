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

package com.holzyn.actor.domain.growth.space;

import com.holzyn.actor.domain.mapnav.service.NpcPlaceView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 地点化程序化空间：候选集与禁止项必须随「谁家的什么地方」变化。
 *
 * <p>这是「环境进入决策」的可断言部分——不是提示词里写了什么，而是<b>候选集本身</b>变了：
 * 站在别人家门口时，动作池里不该出现「取用自己顺手的工具」，禁止项里必须有「自己推门进屋」。</p>
 */
class ProgrammaticSpaceFactoryPlaceTest {

    @Test
    @DisplayName("别人家门口：候选只有敲门/门外等，禁止推门进屋")
    void othersHomeHasGateActionsAndForbidsEntering() {
        NpcPlaceView.Place place = new NpcPlaceView.Place("f_home_62", "阿珍家", "民居",
                false, true, false);
        GrowthSpaceParse.GrowthSpaceDoc doc = ProgrammaticSpaceFactory.buildAt(place, "老吴", null, null, null, null);
        List<String> actions = doc.possibleActions().stream().map(GrowthSpaceParse.ActionItem::action).toList();
        assertTrue(actions.contains("敲门问一声"), "别人家门口应该想到敲门：" + actions);
        assertTrue(actions.contains("在门外等一等"), "别人家门口应该想到门外等：" + actions);
        assertFalse(actions.contains("取用自己顺手的工具"), "别人家不该出现取用工具：" + actions);
        List<String> forbidden = doc.forbidden().stream().map(GrowthSpaceParse.ForbiddenItem::what).toList();
        assertTrue(forbidden.contains("自己推门进屋"), "别人家必须显式禁止推门：" + forbidden);
    }

    @Test
    @DisplayName("自己家：候选是生火做饭/取用工具，不禁止推门")
    void ownHomeHasHouseActions() {
        NpcPlaceView.Place place = new NpcPlaceView.Place("f_home_93", "老吴家", "民居",
                true, true, true);
        GrowthSpaceParse.GrowthSpaceDoc doc = ProgrammaticSpaceFactory.buildAt(place, "老吴", null, null, null, null);
        List<String> actions = doc.possibleActions().stream().map(GrowthSpaceParse.ActionItem::action).toList();
        assertTrue(actions.contains("生火做饭"), "自己家应该能生火做饭：" + actions);
        assertTrue(actions.contains("取用自己顺手的工具"), "自己家应该能取用工具：" + actions);
        List<String> forbidden = doc.forbidden().stream().map(GrowthSpaceParse.ForbiddenItem::what).toList();
        assertFalse(forbidden.contains("自己推门进屋"), "自己家不该禁止推门：" + forbidden);
    }

    @Test
    @DisplayName("商铺：候选是买卖/问价，禁止进后堂取货")
    void shopHasTradeActions() {
        NpcPlaceView.Place place = new NpcPlaceView.Place("f_rice_shop_40", "周家米行", "商铺",
                false, false, false);
        GrowthSpaceParse.GrowthSpaceDoc doc = ProgrammaticSpaceFactory.buildAt(place, "老吴", null, null, null, null);
        List<String> actions = doc.possibleActions().stream().map(GrowthSpaceParse.ActionItem::action).toList();
        assertTrue(actions.contains("进门买卖"), "商铺应该能买卖：" + actions);
        List<String> forbidden = doc.forbidden().stream().map(GrowthSpaceParse.ForbiddenItem::what).toList();
        assertTrue(forbidden.contains("自己进后堂取货"), "商铺应禁止自己进后堂：" + forbidden);
    }

    @Test
    @DisplayName("地点未知：退回阶段池，不编造地点候选")
    void unknownPlaceFallsBackToStagePool() {
        GrowthSpaceParse.GrowthSpaceDoc doc = ProgrammaticSpaceFactory.buildAt(
                NpcPlaceView.Place.unknown(), "老吴", null, null, null, null);
        List<String> actions = doc.possibleActions().stream().map(GrowthSpaceParse.ActionItem::action).toList();
        assertTrue(actions.contains("做手头的事"), "未知地点应退回通用池：" + actions);
        assertFalse(actions.contains("敲门问一声"), "未知地点不该出现别人家的动作：" + actions);
    }

    @Test
    @DisplayName("生理驱动项与地点叠加：饿了仍会想到吃食")
    void physioNeedStillAddedOnTopOfPlacePool() {
        NpcPlaceView.Place place = new NpcPlaceView.Place("f_home_62", "阿珍家", "民居",
                false, true, false);
        com.holzyn.actor.domain.npc.state.entity.ActorNpcState state =
                new com.holzyn.actor.domain.npc.state.entity.ActorNpcState();
        state.setHunger(20);
        GrowthSpaceParse.GrowthSpaceDoc doc = ProgrammaticSpaceFactory.buildAt(place, "老吴", null, null, state, null);
        List<String> actions = doc.possibleActions().stream().map(GrowthSpaceParse.ActionItem::action).toList();
        assertTrue(actions.contains("张罗吃食填肚子"), "饿了应该有吃食项：" + actions);
        assertTrue(actions.contains("敲门问一声"), "地点项不能被生理项挤掉：" + actions);
    }
}
