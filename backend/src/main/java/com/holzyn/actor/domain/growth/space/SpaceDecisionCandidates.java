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
import java.util.List;

/**
 * 空间候选选择 · 候选集组装器。
 * <p><b>职责</b>：把空间 {@code possible_actions} 与通用三项（继续当前/歇一歇/找人说说话）
 * 组装为 决策模型 choice 候选集（：候选=空间 possible_actions[含低权重项] +
 * 通用三项，无空间外通道）：</p>
 * <ul>
 * <li><b>通用三项恒在</b>（与空间候选去重）——「继续当前行动」是真人最常见的默认态，
 * 「歇/聊」是低权重合法项=真人式偶尔放空（crowd 同款口径）；</li>
 * <li><b>权重作情境信息</b>：空间候选描述带「（倾向 x%）」（weight 非空时）——
 * Qwen 生成空间时的倾向强度仅供 0.6B 语义参考，最终分布完全由 决策模型 决定
 * ；</li>
 * <li><b>上限 8</b>（choice 候选 ≤8 利于 0.6B 判断，官方上限 255 不触达）；</li>
 * <li><b>键</b>：通用三项键=continue_current/rest/chat，
 * 空间候选键=space0/space1/…（程序化映射时反查原文与 target）。</li>
 * </ul>
 * <p>纯静态可单测。</p>
 */
public final class SpaceDecisionCandidates {

    private SpaceDecisionCandidates() {
    }

    /** 通用三项键。 */
    public static final String KEY_CONTINUE = "continue_current";
    public static final String KEY_REST = "rest";
    public static final String KEY_CHAT = "chat";

    /** choice 候选总上限（含通用项；官方 2-255，收敛到 8 利于 0.6B 判断）。 */
    public static final int MAX_TOTAL = 8;

    /**
     * 单个候选（判定用 questionText 与执行用 action 分离——questionText 带权重后缀，action 为原文）。
     *
     * @param key 候选键（continue_current/rest/chat/spaceN）
     *
     * @param action 执行用行动原文（决策 Map 的 action_detail）
     *
     * @param questionText choice 候选描述（=action + 可选「（倾向 x%）」）
     *
     * @param target 行动目标地点（空间候选的 target；通用项为空）
     *
     * @param precondition 前置条件原文（空间候选；通用项为空）
     *
     * @param weight 倾向权重（空间候选；0-1 或 null）
     *
     * @param generic 是否通用三项（space 候选为 false）
     */
    public record SpaceCandidate(String key, String action, String questionText,
                                 String target, String precondition, Double weight, boolean generic) {
    }

    /**
     * 组装候选集（通用三项恒在 + 空间候选填充至上限）。
     *
     * @param doc 空间文档（possibleActions 可空=仅通用三项）
     *
     * @param state 状态行（rest 描述随疲惫动态；可空=不疲惫口径）
     *
     * @return 候选表（键序：continue/rest/chat → space0…；恒非空，含 continue）
     */
    public static List<SpaceCandidate> candidatesFrom(GrowthSpaceParse.GrowthSpaceDoc doc,
                                                      com.holzyn.actor.domain.npc.state.entity.ActorNpcState state) {
        return candidatesFrom(doc, state, List.of());
    }

    /**
     * 组装候选集（通用三项恒在 + <b>注入候选</b>+ 空间候选填充至上限）。
     * <p><b>注入候选（C 层社交聚集，）</b>：社交聚集行动（去茶馆/赶集/串门）由
     * {@link SocialGatheringCandidates} 生成后经本重载注入——排在通用三项之后、空间候选之前，
     * 优先占用槽位（空间候选为常规行动，社交聚集是行为层新增通道）；仍受 {@link #MAX_TOTAL}
     * 约束并与既有候选按行动原文去重。</p>
     *
     * @param doc 空间文档（possibleActions 可空=仅通用三项）
     *
     * @param state 状态行（rest 描述随疲惫动态；可空=不疲惫口径）
     *
     * @param extra 注入候选（可空=无注入，等价 2 参重载）
     *
     * @return 候选表（键序：continue/rest/chat → 注入项 → space0…；恒非空，含 continue）
     */
    public static List<SpaceCandidate> candidatesFrom(GrowthSpaceParse.GrowthSpaceDoc doc,
                                                      com.holzyn.actor.domain.npc.state.entity.ActorNpcState state,
                                                      List<SpaceCandidate> extra) {
        return candidatesFrom(doc, state, extra, false);
    }

    /**
     * 组装候选集 4 参版（生理需求→行动决策耦合：<b>轻行动抑制</b>配比分层）。
     * <p><b>配比控制（次对接文档 §6.1「轻行动 vs 实质行动」）</b>：{@code suppressLightGeneric=true}
     * 时，通用三项中的「歇一歇/找人说说话」轻行动不再注入（生理候选在场时，实质行动占比上升，
     * 对齐「饿了的人不会先放空」）；「继续当前」恒在（行为连续性优先）；
     * 空间候选中的轻行动保留（AI 生成空间时的合理行动不粗暴删除）。</p>
     *
     * @param doc 空间文档（possibleActions 可空=仅通用三项）
     *
     * @param state 状态行（rest 描述随疲惫动态；可空=不疲惫口径）
     *
     * @param extra 注入候选（可空=无注入）
     *
     * @param suppressLightGeneric true=抑制通用「歇一歇/找人说说话」（continue 恒在）
     *
     * @return 候选表（键序：continue[→rest/chat] → 注入项 → space0…；恒非空，含 continue）
     */
    public static List<SpaceCandidate> candidatesFrom(GrowthSpaceParse.GrowthSpaceDoc doc,
                                                      com.holzyn.actor.domain.npc.state.entity.ActorNpcState state,
                                                      List<SpaceCandidate> extra,
                                                      boolean suppressLightGeneric) {
        return candidatesFrom(doc, state, extra, suppressLightGeneric, null, null, null);
    }

    /**
     * 带地点口径的候选装配。
     *
     * <p><b>为什么需要</b>：「继续当前正在做的事」是恒在的合法低权重项，但它默认角色
     * 还在做手头的事——人已经被 AI 派到别人家门口，这句话就变成「在这里继续打铁」，
     * 与空间事实冲突，判定层只能靠先验硬选。带上地点后，这句话会被改写成
     * 「接着打铁（此处不便，得先回能做这件事的地方）」，把「这里做不了」这件事写进选项本身。</p>
     *
     * @param activity      当前活动文本（可空；空=不改写）
     * @param placeName     当前地点名（可空）
     * @param placeCategory 当前地点类别（可空；空=不改写，未知地点不乱猜）
     */
    public static List<SpaceCandidate> candidatesFrom(GrowthSpaceParse.GrowthSpaceDoc doc,
                                                      com.holzyn.actor.domain.npc.state.entity.ActorNpcState state,
                                                      List<SpaceCandidate> extra,
                                                      boolean suppressLightGeneric,
                                                      String activity,
                                                      String placeName,
                                                      String placeCategory) {
        List<SpaceCandidate> out = new ArrayList<>();
        // 通用三项恒在（与空间候选去重：空间里已含同类项则不重复加；轻行动抑制时 rest/chat 跳过）
        List<GrowthSpaceParse.ActionItem> actions = doc == null || doc.possibleActions() == null
                ? List.of() : doc.possibleActions();
        boolean hasContinue = actions.stream().anyMatch(a -> a.action() != null && a.action().contains("继续当前"));
        boolean hasRest = actions.stream().anyMatch(a -> a.action() != null && a.action().contains("歇"));
        boolean hasChat = actions.stream().anyMatch(a -> a.action() != null
                && (a.action().contains("说话") || a.action().contains("聊天")
                || a.action().contains("唠") || a.action().contains("攀谈")));
        if (!hasContinue) {
            String continueText = continueText(activity, placeName, placeCategory);
            out.add(new SpaceCandidate(KEY_CONTINUE, continueText, continueText,
                    null, null, null, true));
        }
        boolean tired = state != null && state.getEnergy() != null && state.getEnergy() < 80;
        if (!hasRest && !suppressLightGeneric) {
            String restText = tired ? "停下歇一歇（有些疲惫）" : "停下歇一歇，放空一会儿";
            out.add(new SpaceCandidate(KEY_REST, "停下歇一歇，放空片刻", restText,
                    null, null, null, true));
        }
        if (!hasChat && !suppressLightGeneric) {
            out.add(new SpaceCandidate(KEY_CHAT, "找人说说话", "找个人说说话",
                    null, null, null, true));
        }
        // 注入候选（C 层社交聚集，）：排在通用三项之后、空间候选之前——
        // 社交聚集是行为层新增通道（让独处 NPC 流向社交中心），优先占用槽位；与既有候选按原文去重
        if (extra != null && !extra.isEmpty()) {
            for (SpaceCandidate c : extra) {
                if (out.size() >= MAX_TOTAL) {
                    break;
                }
                if (c == null || c.action() == null || c.action().isBlank()) {
                    continue;
                }
                boolean dupExtra = false;
                for (SpaceCandidate existing : out) {
                    if (existing.action().equals(c.action())) {
                        dupExtra = true;
                        break;
                    }
                }
                if (!dupExtra) {
                    out.add(c);
                }
            }
        }
        // 空间候选填充至上限（超出截断：空间候选优先保留——通用三项已恒在）
        int room = MAX_TOTAL - out.size();
        int spaceIdx = 0;
        if (room > 0) {
            for (int i = 0; i < actions.size() && room > 0; i++) {
                GrowthSpaceParse.ActionItem a = actions.get(i);
                if (a == null || a.action() == null || a.action().isBlank()) {
                    continue;
                }
                String action = a.action().trim();
                // 空间自己就带「继续当前…」时，同样要按地点改写——否则这条恒在项
                // 会绕过上面的 continueText（实测 380/381 次 crowd_decision 都从这里来）。
                if (action.contains("继续当前")) {
                    String rewritten = continueText(activity, placeName, placeCategory);
                    if (!rewritten.equals(continueBase())) {
                        action = rewritten;
                    }
                }
                // 与既有候选去重（精确文本）
                boolean dup = false;
                for (SpaceCandidate c : out) {
                    if (c.action().equals(action)) {
                        dup = true;
                        break;
                    }
                }
                if (dup) {
                    continue;
                }
                String question = weightSuffix(action, a.weight());
                out.add(new SpaceCandidate("space" + spaceIdx++, action, question,
                        a.target(), a.precondition(), a.weight(), false));
                room--;
            }
        }
        return out;
    }

    /** 「继续当前正在做的事」的标准文本（改写判定的基准，避免两处字面量漂移）。 */
    static String continueBase() {
        return "继续当前正在做的事";
    }

    /**
     * 「继续当前正在做的事」的地点改写。
     *
     * <p>活动与地点不匹配时，把「继续」改写成「得先回能做这件事的地方」——
     * 这不是措辞游戏：它把「此处不便」变成候选本身的语义，判定层选它就意味着先离开，
     * 而不是在别人家门口继续打铁。</p>
     *
     * @return 改写后的候选文本；信息不足时返回原文（未知地点不乱猜）
     */
    static String continueText(String activity, String placeName, String placeCategory) {
        String base = continueBase();
        if (activity == null || activity.isBlank() || placeCategory == null || placeCategory.isBlank()) {
            return base;
        }
        String act = activity.trim();
        if (act.length() > 12) {
            act = act.substring(0, 12);
        }
        if (activityFitsHere(act, placeCategory)) {
            return base;
        }
        String where = placeName == null || placeName.isBlank() ? "这里" : placeName;
        return "接着" + act + "（" + where + "不便，得先回能做这件事的地方）";
    }

    /**
     * 活动是否与地点类别相容（关键词表；未命中一律视为相容，宁可不改写也不乱判）。
     *
     * <p>类别口径与 {@code NpcPlaceView} 一致（要素 category：民居/商铺/工坊/农田/生产/水工/
     * 公共/道路/自然/学校/医馆…），只做包含匹配，不引入新枚举。</p>
     */
    static boolean activityFitsHere(String activity, String category) {
        if (activity == null || activity.isBlank() || category == null || category.isBlank()) {
            return true;
        }
        String c = category;
        boolean home = c.contains("民居");
        boolean shop = c.contains("商铺") || c.contains("店");
        boolean workshop = c.contains("工坊") || c.contains("作坊");
        boolean field = c.contains("农田") || c.contains("生产") || c.contains("水工") || c.contains("自然");
        boolean school = c.contains("学校") || c.contains("学塾");
        boolean clinic = c.contains("医") || c.contains("药");
        boolean publicPlace = c.contains("公共") || c.contains("道路") || c.contains("街");

        // 手艺活：必须在工坊/自家铺面做
        if (containsAny(activity, "打铁", "打铜", "打铁器", "做活", "干活", "木工", "编筐",
                "纺", "织", "裁", "磨刀", "修犁", "补锅")) {
            return workshop || shop;
        }
        // 家务：在住处做（别人家也算「有灶有院」，但不改写——判定层另有越界约束）
        if (containsAny(activity, "做饭", "烧水", "煮", "蒸", "洗衣", "收拾屋子", "缝补", "睡觉", "歇脚")) {
            return home || workshop || shop;
        }
        // 农活：在地里/水边/野外做
        if (containsAny(activity, "种", "锄", "割", "浇", "灌", "喂", "收庄稼", "挑水", "打水", "放牛", "下地")) {
            return field;
        }
        // 买卖：在铺面做
        if (containsAny(activity, "卖", "买", "看店", "掌柜", "招呼客", "称货", "进货")) {
            return shop;
        }
        // 教书 / 看病
        if (containsAny(activity, "教书", "上课", "讲课", "念书")) {
            return school || publicPlace;
        }
        if (containsAny(activity, "看病", "抓药", "诊", "换药")) {
            return clinic || shop;
        }
        return true;
    }

    private static boolean containsAny(String text, String... keys) {
        for (String k : keys) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** 权重后缀（倾向 x%；weight null 不加）。 */    private static String weightSuffix(String action, Double weight) {
        if (weight == null) {
            return action;
        }
        long pct = Math.round(weight * 100);
        return action + "（倾向 " + pct + "%）";
    }
}
