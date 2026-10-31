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

package com.holzyn.actor.domain.mapnav.algo;

import java.util.List;

/**
 * 一条寻路结果（米制折线）。
 *
 * <p>为什么用它而不是直接返回 {@code List<double[]>}：长度和「是否直连」是下游必需的两件事——
 * 行程时长 = 长度 / 速度，直连段不需要做分段标注。把它们和折线绑在一个值里，
 * 调用方就不可能忘了传长度（那个 bug 的表现是「所有人走路都只要 1 分钟」）。</p>
 *
 * @param points  折线（米，{@code [[x,z],…]}，首点是起点、末点是终点）
 * @param lengthM 折线总长（米）
 * @param cells   展开的格数（诊断用：太小说明抄了近路，太大说明在绕圈）
 * @param direct  是否「直线可走、未经过 A*」
 */
public record NavPath(List<double[]> points, double lengthM, int cells, boolean direct) {

    /** 空路径（不可达）。 */
    public static NavPath none() {
        return new NavPath(List.of(), 0, 0, false);
    }

    /** 是否可达。 */
    public boolean reachable() {
        return points != null && points.size() >= 2;
    }
}
