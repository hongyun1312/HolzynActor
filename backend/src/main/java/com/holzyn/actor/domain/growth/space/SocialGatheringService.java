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

import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 社交聚集行为服务（社会对话层修复 C 层，domain/growth/space）。
 * <p><b>职责</b>：把项目世界观地点里命中<b>社交中心关键词</b>（茶馆/集市/村口/客栈…）的地点
 * 解析为社交中心名单，再经 {@link SocialGatheringCandidates} 为「独处 + 非深夜 + 不在社交中心」
 * 的角色生成聚集候选（带目标地点 → move_to），供 空间候选决策注入。</p>
 * <p><b>接线点两处</b>（特殊 NPC 与普通 NPC 决策管线同源，覆盖全量 NPC）：</p>
 * <ul>
 * <li>{@code DecisionService.trySpaceDecision}（特殊 NPC：决策模型 space_decision 空间候选）；</li>
 * <li>{@code NpcStateService.decideCrowdActionDecisionFromSpace}（普通 NPC：crowd 空间候选）。</li>
 * </ul>
 * <p><b>通用世界观纪律</b>：社交中心按<b>关键词</b>（名称或类型命中）识别、关键词可配，
 * 不绑定任何具体测试世界的地点名（对齐「Prompt 与代码不得绑定单一世界观」审计要求）。</p>
 * deep-night-end / max-candidates / weight，全量环境变量）。失败零阻断（返回空表 → 行为回到修复前）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SocialGatheringService {

    private final WorldLocationRepository locationRepository;

    /** 社交聚集注入总开关（默认 true；false=行为回到修复前） */
    @Value("${holzyn.actor.social-gathering.enabled:true}")
    private boolean enabled = true;

    /** 社交中心关键词（逗号分隔；地点名或地点类型命中任一即视为社交中心） */
    @Value("${holzyn.actor.social-gathering.center-keywords:"
            + "茶馆,茶楼,茶铺,集市,市集,村口,客栈,酒楼,酒馆,饭馆,饭店,广场,戏台,祠堂,凉亭,亭子,庙}")
    private String centerKeywords =
            "茶馆,茶楼,茶铺,集市,市集,村口,客栈,酒楼,酒馆,饭馆,饭店,广场,戏台,祠堂,凉亭,亭子,庙";

    /** 深夜起点（含；默认 23，与 roleplay.deep-night-start 同口径） */
    @Value("${holzyn.actor.social-gathering.deep-night-start:23}")
    private int deepNightStart = 23;

    /** 深夜终点（不含；默认 6） */
    @Value("${holzyn.actor.social-gathering.deep-night-end:6}")
    private int deepNightEnd = 6;

    /** 每次决策注入的社交候选上限（默认 2） */
    @Value("${holzyn.actor.social-gathering.max-candidates:2}")
    private int maxCandidates = 2;

    /** 社交候选倾向权重（默认 0.3） */
    @Value("${holzyn.actor.social-gathering.weight:0.3}")
    private double weight = 0.3;

    /**
     * 为「当前地点的某角色」生成社交聚集候选（零阻断：异常/未启用/无中心 → 空表）。
     *
     * @param projectId 项目 ID
     *
     * @param currentLocation 角色当前地点名（可空=未知）
     *
     * @param alone 是否独处（身边无其他在世角色；false=不提供聚集候选）
     *
     * @param hourOfDay 当前游戏时刻（0-23；**调用方应传引擎推进位置口径**——：流动时钟
     * 在世界暂停/慢速时远超模拟位置；null=未知按非深夜宽松处理）
     *
     * @return 社交聚集候选表（可空表；键 gather0/gather1…）
     */
    public List<SpaceDecisionCandidates.SpaceCandidate> candidatesFor(Long projectId, String currentLocation,
                                                                      boolean alone, Integer hourOfDay) {
        if (!enabled || projectId == null) {
            return List.of();
        }
        try {
            List<ActorWorldLocation> locations = locationRepository.findByProjectId(projectId);
            List<String> centers = centerNames(locations, parseKeywords(centerKeywords));
            List<SpaceDecisionCandidates.SpaceCandidate> out = SocialGatheringCandidates.candidates(
                    centers, currentLocation, alone, hourOfDay,
                    deepNightStart, deepNightEnd, maxCandidates, weight);
            if (!out.isEmpty()) {
                log.info("[社交聚集] 注入聚集候选 {} 项：project={} 当前地点={} 时刻={} 候选={}",
                        out.size(), projectId, currentLocation, hourOfDay,
                        out.stream().map(SpaceDecisionCandidates.SpaceCandidate::target).toList());
            }
            return out;
        } catch (Exception e) {
            log.warn("[社交聚集] 候选生成失败（零阻断，行为回退）: project={} : {}", projectId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 社交中心名单（纯静态可单测）：地点名或类型命中任一关键词即入选；按地点表顺序保序去重。
     *
     * @param locations 项目地点表（可空）
     *
     * @param keywords 社交中心关键词（可空=无中心）
     *
     * @return 社交中心地点名（保序去重）
     */
    static List<String> centerNames(List<ActorWorldLocation> locations, List<String> keywords) {
        List<String> out = new ArrayList<>();
        if (locations == null || locations.isEmpty() || keywords == null || keywords.isEmpty()) {
            return out;
        }
        for (ActorWorldLocation loc : locations) {
            if (loc == null || loc.getName() == null || loc.getName().isBlank()) {
                continue;
            }
            String name = loc.getName().trim();
            if (out.contains(name)) {
                continue;
            }
            String type = loc.getType() == null ? "" : loc.getType().trim();
            for (String kw : keywords) {
                if (kw == null || kw.isBlank()) {
                    continue;
                }
                String k = kw.trim();
                if (name.contains(k) || type.contains(k)) {
                    out.add(name);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * 关键词解析（逗号分隔，兼容中英文逗号/顿号；去空白去重保序）。
     */
    static List<String> parseKeywords(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String raw : csv.split("[,，、;；]")) {
            String k = raw.trim();
            if (!k.isEmpty() && !out.contains(k)) {
                out.add(k);
            }
        }
        return out;
    }
}