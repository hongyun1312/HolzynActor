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

package com.holzyn.actor.domain.mapnav.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目标文本剥词测试（纯函数，不加载 Spring）。
 *
 * <p>背景：世界 143 的行动计划里 {@code type=move} 的「顺路去米行讨碗水喝」{@code target=""}，
 * 执行侧见空目标跳过移动，AI 说去哪、地图上的人一步不动。这两个纯函数就是补那一步的：
 * 先把目的地从句子里剥出来（{@link TargetResolver#destinationText(String)}），
 * 再把口语壳剥掉（{@link TargetResolver#normalizeTarget(String)}）交给要素名模糊匹配。</p>
 */
class TargetResolverTextTest {

    /** 真实动作文本：目标在「去」之后、「讨」之前。 */
    @Test
    @DisplayName("destinationText 从整句动作里剥出地名")
    void destinationFromActionSentence() {
        assertThat(TargetResolver.destinationText("顺路去米行讨碗水喝")).isEqualTo("米行");
        assertThat(TargetResolver.destinationText("继续顺路去米行讨碗水喝")).isEqualTo("米行");
        assertThat(TargetResolver.destinationText("去周家米行买米")).isEqualTo("周家米行");
        assertThat(TargetResolver.destinationText("往镇东小学那边走")).isEqualTo("镇东小学");
        // 闭环实测：动词「打水」不能并进地名（否则 actor_profile.location 会写成「井边打水」）
        assertThat(TargetResolver.destinationText("顺路去井边打水")).isEqualTo("井边");
    }

    /** 没有位移语义的动作不该被当成「去哪」：剥不出就返回空串，执行侧保持原位。 */
    @Test
    @DisplayName("destinationText 对非位移动作返回空串")
    void destinationEmptyForNonMove() {
        assertThat(TargetResolver.destinationText("找人说说话")).isEmpty();
        assertThat(TargetResolver.destinationText("坐下歇一歇，放空片刻")).isEmpty();
        assertThat(TargetResolver.destinationText("在铺子里补两下漏风的墙")).isEmpty();
        assertThat(TargetResolver.destinationText("")).isEmpty();
        assertThat(TargetResolver.destinationText(null)).isEmpty();
    }

    /** 口语壳（动词前缀 / 指代后缀 / 句末语气词）要剥干净，否则永远匹配不上要素名。 */
    @Test
    @DisplayName("normalizeTarget 剥掉动词前缀与指代后缀")
    void normalizeStripsColloquialShell() {
        assertThat(TargetResolver.normalizeTarget("顺路去周家米行那边")).isEqualTo("周家米行");
        assertThat(TargetResolver.normalizeTarget("去河边")).isEqualTo("河边");
        assertThat(TargetResolver.normalizeTarget("回铁匠铺吧")).isEqualTo("铁匠铺");
        assertThat(TargetResolver.normalizeTarget("  ")).isEmpty();
    }

    /** 方位后缀单独一层：「广场上」→「广场】（但「渡口」这种两字地名不许再剥）。 */
    @Test
    @DisplayName("stripLocative 只剥方位字且保留两字地名")
    void stripLocativeKeepsTwoCharNames() {
        assertThat(TargetResolver.stripLocative("广场上")).isEqualTo("广场");
        assertThat(TargetResolver.stripLocative("铁匠铺里")).isEqualTo("铁匠铺");
        assertThat(TargetResolver.stripLocative("渡口")).isEqualTo("渡口");
        assertThat(TargetResolver.stripLocative("河")).isEqualTo("河");
    }
}
