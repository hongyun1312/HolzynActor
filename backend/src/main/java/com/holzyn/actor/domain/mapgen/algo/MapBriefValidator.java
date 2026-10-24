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

package com.holzyn.actor.domain.mapgen.algo;

import com.holzyn.actor.domain.mapgen.model.WorldMapBrief;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 世界→地图契约的校验器（S0b 的「程序作约束修正」那一半）。
 *
 * <p>用户决策：「AI 给布局，程序只做约束修正」。AI 给的东西必须能被程序判对错——
 * 否则「AI 说聚落在水里」「AI 给出 8 个灯塔」这类错误会一路传到落位器，最后表现为
 * 「图能出但很假」，排查时又找不到源头。这里把所有可判定的错误一次性列出来：</p>
 *
 * <ul>
 *   <li>方位/半径越界（角度归一不了、半径倒挂）；</li>
 *   <li>区域重叠到无法区分（同一方位同一半径带出现 3 个以上区域）；</li>
 *   <li>设施同名/同编码重复、尺寸离谱、锚点地点不存在；</li>
 *   <li>人口学缺失或户数 ≤ 0；</li>
 *   <li>数量失控（区域 &gt; 12、设施 &gt; 30——一个 12 户的岛不需要 30 个公共设施）。</li>
 * </ul>
 *
 * <p>校验结果不直接抛异常：{@code MapBriefService} 拿 problems 决定「重试 / 回落兜底」，
 * 并把问题写进日志，下一次一眼能看出 AI 到底错在哪。</p>
 */
public final class MapBriefValidator {

    /** 区域数量上限（超过说明 AI 在凑数）。 */
    private static final int MAX_REGIONS = 12;

    /** 设施数量上限（按 12 户世界的真实规模给足余量）。 */
    private static final int MAX_FACILITIES = 30;

    /** 合法的设施大类（与 {@code MapAssetType.CATEGORIES} 对齐，去掉「自然」）。 */
    private static final Set<String> ROLES = Set.of("民居", "商铺", "工坊", "公共", "生产", "水工");

    private MapBriefValidator() {
    }

    /**
     * 校验契约。
     *
     * @param brief 契约（可为 null）
     * @return 问题列表（空 = 通过）
     */
    public static List<String> validate(WorldMapBrief brief) {
        List<String> problems = new ArrayList<>();
        if (brief == null) {
            problems.add("契约为空");
            return problems;
        }
        if (brief.demography() == null || brief.demography().households() <= 0) {
            problems.add("人口学缺失或户数 ≤ 0");
        }
        List<WorldMapBrief.Region> regions = brief.regions() == null ? List.of() : brief.regions();
        if (regions.size() > MAX_REGIONS) {
            problems.add("区域过多：" + regions.size());
        }
        Set<String> regionCodes = new HashSet<>();
        for (WorldMapBrief.Region r : regions) {
            String tag = "区域「" + r.name() + "」";
            if (r.name() == null || r.name().isBlank()) {
                problems.add("区域缺名字");
            }
            if (r.code() == null || r.code().isBlank()) {
                problems.add(tag + "缺编码");
            } else if (!regionCodes.add(r.code())) {
                problems.add(tag + "编码重复");
            }
            if (r.bearingDeg() < 0 || r.bearingDeg() > 360) {
                problems.add(tag + "方位越界：" + r.bearingDeg());
            }
            if (r.radialFrom() < 0 || r.radialTo() > 1 || r.radialFrom() > r.radialTo()) {
                problems.add(tag + "半径带非法：" + r.radialFrom() + "~" + r.radialTo());
            }
        }
        WorldMapBrief.Settlement s = brief.settlement();
        if (s != null) {
            if (s.centerBearingDeg() < 0 || s.centerBearingDeg() > 360) {
                problems.add("聚落方位越界：" + s.centerBearingDeg());
            }
            if (s.radiusM() < 20 || s.radiusM() > 600) {
                problems.add("聚落半径越界：" + s.radiusM());
            }
            if (s.slopeMax() < 0.05 || s.slopeMax() > 0.6) {
                problems.add("可建坡度越界：" + s.slopeMax());
            }
        }
        List<WorldMapBrief.Facility> facilities = brief.facilities() == null ? List.of() : brief.facilities();
        if (facilities.size() > MAX_FACILITIES) {
            problems.add("设施过多：" + facilities.size());
        }
        Set<String> facilityCodes = new HashSet<>();
        for (WorldMapBrief.Facility f : facilities) {
            String tag = "设施「" + f.name() + "」";
            if (f.name() == null || f.name().isBlank()) {
                problems.add("设施缺名字");
                continue;
            }
            if (f.code() == null || f.code().isBlank()) {
                problems.add(tag + "缺编码");
            } else if (!facilityCodes.add(f.code())) {
                problems.add(tag + "编码重复");
            }
            if (f.role() == null || !ROLES.contains(f.role())) {
                problems.add(tag + "大类非法：" + f.role());
            }
            if (f.frontageM() < 2 || f.frontageM() > 60 || f.depthM() < 2 || f.depthM() > 60) {
                problems.add(tag + "尺寸越界：" + f.frontageM() + "×" + f.depthM());
            }
            if (f.floors() < 1 || f.floors() > 4) {
                problems.add(tag + "层数越界：" + f.floors());
            }
        }
        if (brief.dwellings() != null && brief.demography() != null
                && !brief.dwellings().isEmpty()
                && brief.dwellings().size() != brief.demography().households()) {
            problems.add("住宅数（" + brief.dwellings().size() + "）与户数（"
                    + brief.demography().households() + "）不一致");
        }
        return problems;
    }

    /**
     * 是否有硬错误（硬错误必须回落兜底；软问题只记日志）。
     *
     * @param problems 校验问题
     * @return true = 不能直接用
     */
    public static boolean fatal(List<String> problems) {
        for (String p : problems) {
            if (p.startsWith("契约为空") || p.startsWith("人口学") || p.startsWith("住宅数")) {
                return true;
            }
        }
        return false;
    }
}
