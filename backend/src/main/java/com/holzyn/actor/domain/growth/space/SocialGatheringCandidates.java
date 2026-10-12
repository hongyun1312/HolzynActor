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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 社交聚集行动 · 候选集组装器（社会对话层修复 C 层，domain/growth/space）。
 * <p><b>北极星对齐</b>：长跑第 1/2 轮实证「NPC 恒 idle、同地点合格 NPC ≥2 的地点稀缺」——
 * 社交对话层触发稀疏的主因是<b>NPC 不聚集</b>（村中 20 人散在 15 地点，多数地点仅 1 人，
 * 概率/事件回应轮都因「同地点不足 2 人」跳过）。本类让独处 NPC 自发流向<b>社交中心</b>
 * （茶馆/集市/村口/客栈…），形成「聚集 → 闲聊/群聊 → 关系/信息扩散」正反馈循环。</p>
 * <p><b>候选语义</b>：</p>
 * <ul>
 * <li>行动文本以「去…」开头 + 携带 <b>target=社交中心地点名</b>→ 经 {@link SpaceChoiceMapper}
 * 推断 actionType=move_to，执行链零改动（与空间候选带目标地点的既有路径完全同构）；</li>
 * <li>措辞按中心类型分化（赶集/喝茶听闲话/串门唠嗑），贴合真人语感；</li>
 * <li>权重作「（倾向 x%）」情境描述进 choice 候选文本（同 {@link SpaceDecisionCandidates} 口径），
 * 最终分布仍由 NanoJev 决定（不硬编码行为）；</li>
 * <li>无 precondition（恒可发生区）——「此刻去不去」交给 NanoJev 判定，不做程序化硬约束。</li>
 * </ul>
 * <p><b>触发门（真人常识约束，防全村同时涌向茶馆）</b>：</p>
 * <ol>
 * <li><b>独处</b>才提供聚集候选——身边已有人时可就地攀谈（通用「找人说说话」项恒在），
 * 不必专程迁移；此门天然把聚集量限制在「孤单的人」这一子集；</li>
 * <li><b>深夜不提供</b>（deep-night 窗口内居民入睡，不再四处串门）；</li>
 * <li><b>已在社交中心</b>时不提供——就地成为他人的聚集目标，
 * 避免「从茶馆跑去集市」的无意义迁移。</li>
 * </ol>
 * <p>纯静态可单测。</p>
 */
public final class SocialGatheringCandidates {

    private SocialGatheringCandidates() {
    }

    /** 社交聚集候选键前缀（gather0/gather1…；与 continue_current/rest/chat/spaceN 并列不冲突）。 */
    public static final String KEY_PREFIX = "gather";

    /**
     * 组装社交聚集候选。
     *
     * @param socialCenterNames 社交中心地点名（可空/空=无候选）
     *
     * @param currentLocation 角色当前地点名（可空=未知；未知时不排除任何中心）
     *
     * @param alone 是否独处（false=身边已有人 → 不提供聚集候选）
     *
     * @param hourOfDay 当前游戏时刻（0-23；null=未知，按非深夜处理）
     *
     * @param deepNightStart 深夜起点（含；默认 23）
     *
     * @param deepNightEnd 深夜终点（不含；默认 6）
     *
     * @param maxCandidates 最多提供的候选数（≤0=不限，仍受调用方候选集总上限约束）
     *
     * @param weight 倾向权重（0~1；作为「（倾向 x%）」描述，null=不加后缀）
     *
     * @return 社交聚集候选表（保持入参顺序、按名去重；不满足触发门返回空表）
     */
    public static List<SpaceDecisionCandidates.SpaceCandidate> candidates(
            List<String> socialCenterNames, String currentLocation, boolean alone,
            Integer hourOfDay, int deepNightStart, int deepNightEnd,
            int maxCandidates, Double weight) {
        List<SpaceDecisionCandidates.SpaceCandidate> out = new ArrayList<>();
        if (socialCenterNames == null || socialCenterNames.isEmpty()) {
            return out;
        }
        // 门 ①：独处才提供（身边有人可就地攀谈，无需专程迁移）
        if (!alone) {
            return out;
        }
        // 门 ②：深夜不提供（居民入睡）
        if (hourOfDay != null && isDeepNight(hourOfDay, deepNightStart, deepNightEnd)) {
            return out;
        }
        // 门 ③：已在社交中心 → 就地成为聚集目标（不再迁移到别的中心）
        if (matchesAny(currentLocation, socialCenterNames)) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        int limit = maxCandidates <= 0 ? Integer.MAX_VALUE : maxCandidates;
        int idx = 0;
        for (String raw : socialCenterNames) {
            if (out.size() >= limit) {
                break;
            }
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String name = raw.trim();
            if (!seen.add(name)) {
                continue; // 同名地点去重
            }
            if (matchesAny(name, List.of(currentLocation == null ? "" : currentLocation))) {
                continue; // 与当前地点同一处（文本包含口径）
            }
            String action = phrasingFor(name);
            String question = weight == null ? action
                    : action + "（倾向 " + Math.round(weight * 100) + "%）";
            out.add(new SpaceDecisionCandidates.SpaceCandidate(
                    KEY_PREFIX + idx++, action, question, name, null, weight, false));
        }
        return out;
    }

    /**
     * 中心类型 → 行动措辞（真人语感分化；均以「去」开头且带 target，执行链按 move_to 处置）。
     * <p>措辞同时承载<b>行动语义关键词</b>：普通 NPC 路径经 {@code crowdTypeOfText} 归类，
     * 各措辞均含「聊/攀谈/说话」等社交关键词 → 归类为 chat（聚集本意=去人多处说话），
     * 而非误归为 drink/work（只迁移不社交）。</p>
     */
    static String phrasingFor(String centerName) {
        String n = centerName == null ? "" : centerName;
        if (containsAny(n, "集市", "市集", "集场", "赶集", "场集")) {
            return "去" + n + "赶集，逛逛热闹，和人攀谈几句";
        }
        if (containsAny(n, "茶馆", "茶楼", "茶铺", "茶摊", "茶园", "茶棚")) {
            return "去" + n + "坐坐，喝茶闲聊";
        }
        if (containsAny(n, "客栈", "酒楼", "酒馆", "饭馆", "饭店", "食肆", "酒肆")) {
            return "去" + n + "坐坐，和乡亲们说说话";
        }
        if (containsAny(n, "村口", "牌坊", "广场", "戏台", "祠堂", "庙", "凉亭", "亭子", "树下", "井")) {
            return "去" + n + "转转，找人闲聊几句";
        }
        return "去" + n + "走走，和乡亲们说说话";
    }

    /**
     * 深夜判定（口径对齐 RoleplayService.isDeepNightHour：h ≥ start ∪ h &lt; end，支持跨午夜）。
     */
    static boolean isDeepNight(int hourOfDay, int deepNightStart, int deepNightEnd) {
        return hourOfDay >= deepNightStart || hourOfDay < deepNightEnd;
    }

    /**
     * 名称是否命中任一中心名（双向包含口径：地点名/地点类型快照可能带前缀后缀）。
     */
    static boolean matchesAny(String name, List<String> centers) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String n = name.trim();
        for (String c : centers) {
            if (c == null || c.isBlank()) {
                continue;
            }
            String center = c.trim();
            if (n.equals(center) || n.contains(center) || center.contains(n)) {
                return true;
            }
        }
        return false;
    }

    /** 文本是否包含任一关键词。 */
    private static boolean containsAny(String text, String... keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String kw : keywords) {
            if (text.contains(kw)) {
                return true;
            }
        }
        return false;
    }
}