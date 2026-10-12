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
 * 组装为 NanoJev choice 候选集（：候选=空间 possible_actions[含低权重项] +
 * 通用三项，无空间外通道）：</p>
 * <ul>
 * <li><b>通用三项恒在</b>（与空间候选去重）——「继续当前行动」是真人最常见的默认态，
 * 「歇/聊」是低权重合法项=真人式偶尔放空（crowd 同款口径）；</li>
 * <li><b>权重作情境信息</b>：空间候选描述带「（倾向 x%）」（weight 非空时）——
 * Qwen 生成空间时的倾向强度仅供 0.6B 语义参考，最终分布完全由 NanoJev 决定
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
            out.add(new SpaceCandidate(KEY_CONTINUE, "继续当前正在做的事", "继续当前正在做的事",
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

    /** 权重后缀（倾向 x%；weight null 不加）。 */
    private static String weightSuffix(String action, Double weight) {
        if (weight == null) {
            return action;
        }
        long pct = Math.round(weight * 100);
        return action + "（倾向 " + pct + "%）";
    }
}
