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

import com.holzyn.actor.domain.growth.stage.GrowthStage;
import com.holzyn.actor.domain.growth.stage.HardGateResult;
import com.holzyn.actor.domain.mapnav.service.NpcPlaceView;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 程序化兜底空间工厂。
 * <p>对齐设计文档 §10 风险对策「程序化兜底空间（零 AI 也能跑）」：由<b>阶段 + 生理 + 当前处境</b>
 * 推导候选集——每个阶段有固定行动池，叠加状态驱动项（饿→觅食 / 累→歇息 / 孤单→找人说话 /
 * 病→休养），禁止项直接取硬门控 forbidden。输出与 AI 空间同构（{@link GrowthSpaceDoc}），
 * 消费侧无感。</p>
 */
public final class ProgrammaticSpaceFactory {

    private ProgrammaticSpaceFactory() {
    }

    /**
     * 生成程序化空间（确定性、零 AI）。
     *
     * @param chName 角色名（内心念头用）
     *
     * @param stage 当前阶段（null=宽松：用通用池）
     *
     * @param gate 硬门控结果（禁止项进空间 forbidden）
     *
     * @param state 状态行（可空；状态驱动项用）
     *
     * @param activity 当前行动描述（可空）
     *
     * @return 程序化空间文档
     */
    public static GrowthSpaceParse.GrowthSpaceDoc build(String chName, GrowthStage stage, HardGateResult gate,
                                                        ActorNpcState state, String activity) {
        return buildAt(null, chName, stage, gate, state, activity);
    }

    /**
     * 生成「带地点」的程序化空间（位置变化时按新地点重算，零 AI）。
     *
     * <p><b>为什么要有这一版</b>：可发生空间一次生成、TTL 6~12 游戏小时，而角色会走动。
     * 实测：角色走到阿珍家门口，空间里仍是铁匠铺的「补墙、取工具」，于是候选动作跟身体脱节——
     * 环境进了 prompt 却没进选项集。这一版把「地点」直接变成候选池与禁止项：
     * 在别人家门口就只会想到敲门、门外等、说明来意，不会想到「进屋拿东西」。</p>
     *
     * <p><b>口径</b>：地点可辨认时用它替换阶段池（阶段池是「一般情况能做什么」，地点池是
     * 「此处能做什么」）；地点未知时退回阶段池，不做无根据的猜测。生理驱动项（饿/累/病/孤单）
     * 仍叠加——那是身体事实，与地点无关。</p>
     *
     * @param place    地点快照（可空=沿用阶段池）
     *
     * @param chName   角色名（内心念头用）
     *
     * @param stage    当前阶段（null=宽松：用通用池）
     *
     * @param gate     硬门控结果（禁止项进空间 forbidden）
     *
     * @param state    状态行（可空；状态驱动项用）
     *
     * @param activity 当前行动描述（可空）
     *
     * @return 程序化空间文档（含地点候选池与地点禁止项）
     */
    public static GrowthSpaceParse.GrowthSpaceDoc buildAt(NpcPlaceView.Place place, String chName,
                                                          GrowthStage stage, HardGateResult gate,
                                                          ActorNpcState state, String activity) {
        // 内心：主导念头 = 当前行动/状态摘要；身体感受 = urgency 文本
        String thought = activity == null || activity.isBlank()
                ? (chName == null ? "正想着接下来做点什么" : chName + "正盘算着手头的事")
                : "正" + activity;
        String bodyFeel = "";
        if (state != null) {
            if (state.getUrgencyText() != null && !state.getUrgencyText().isBlank()) {
                bodyFeel = state.getUrgencyText();
            } else if (state.getHealth() != null && state.getHealth() < 60) {
                bodyFeel = "身上有些不大爽利";
            }
        }
        List<GrowthSpaceParse.ActionItem> actions = place == null || !place.known()
                ? actionsOf(stage, state) : actionsAt(place, state);
        List<GrowthSpaceParse.ForbiddenItem> forbidden = forbiddenOf(gate);
        forbidden.addAll(forbiddenAt(place));
        return new GrowthSpaceParse.GrowthSpaceDoc(
                new GrowthSpaceParse.Inner(null, thought, bodyFeel.isBlank() ? null : bodyFeel),
                statesOf(state), actions, forbidden);
    }

    /**
     * 地点候选池（此处能做什么），叠加生理驱动项。
     *
     * <p>权重固定 0.4（与阶段池同口径）：确定性、可复现，便于把「地点变了 → 候选集变了」
     * 当作可断言的事实，而不是又一层随机性。</p>
     */
    private static List<GrowthSpaceParse.ActionItem> actionsAt(NpcPlaceView.Place place, ActorNpcState state) {
        List<GrowthSpaceParse.ActionItem> out = new ArrayList<>();
        for (String action : poolAt(place)) {
            out.add(new GrowthSpaceParse.ActionItem(action, 0.4, null, null));
        }
        // 生理驱动项与地点无关（身体事实）：复用既有叠加口径，避免「饿了却只想着敲门」
        for (GrowthSpaceParse.ActionItem a : actionsOf(null, state)) {
            boolean dup = out.stream().anyMatch(x -> x.action().equals(a.action()));
            if (!dup) {
                out.add(a);
            }
        }
        return out;
    }

    /** 地点行动池（按「谁的什么地方」分层；未知分类返回空=沿用阶段池）。 */
    public static List<String> poolAt(NpcPlaceView.Place place) {
        if (place == null || !place.known()) {
            return List.of();
        }
        if (place.atOwnHome()) {
            return List.of("生火做饭", "烧水沏茶", "收拾屋子", "取用自己顺手的工具", "在院里歇口气");
        }
        if (place.atOthersHome()) {
            return List.of("敲门问一声", "在门外等一等", "说明来意", "改日再来");
        }
        String cat = place.category() == null ? "" : place.category().trim();
        return switch (cat) {
            case "商铺" -> List.of("进门买卖", "问个价", "跟掌柜搭话", "帮着照看门前货");
            case "工坊" -> List.of("动手做活", "跟工匠说上话", "等他用完工具", "打磨手头的活计");
            case "生产", "水工" -> List.of("下地看看庄稼", "打水", "清理水渠", "跟干活的人搭把手");
            case "公共" -> List.of("在廊下歇脚", "跟人搭话", "看会儿热闹", "等个人");
            case "自然" -> List.of("沿路走走", "看看天色", "捡拾柴草", "找地方歇脚");
            case "道路", "街巷" -> List.of("赶路", "给来人让路", "看看天色", "加快脚步");
            default -> List.of();
        };
    }

    /** 地点禁止项（越界行为显式否定：写进空间 forbidden，消费侧同款渲染）。 */
    public static List<GrowthSpaceParse.ForbiddenItem> forbiddenAt(NpcPlaceView.Place place) {
        List<GrowthSpaceParse.ForbiddenItem> out = new ArrayList<>();
        if (place == null || !place.known()) {
            return out;
        }
        if (place.atOthersHome()) {
            out.add(new GrowthSpaceParse.ForbiddenItem("自己推门进屋", "这是别人的住处，不是你家"));
            out.add(new GrowthSpaceParse.ForbiddenItem("翻找或拿走屋里的东西", "主人不在场，东西动不得"));
            return out;
        }
        String cat = place.category() == null ? "" : place.category().trim();
        if ("商铺".equals(cat)) {
            out.add(new GrowthSpaceParse.ForbiddenItem("自己进后堂取货", "后堂不是客人去的地方"));
        } else if ("工坊".equals(cat)) {
            out.add(new GrowthSpaceParse.ForbiddenItem("擅自动用别人的工具和料", "工具是匠人的饭碗"));
        } else if ("生产".equals(cat) || "水工".equals(cat)) {
            out.add(new GrowthSpaceParse.ForbiddenItem("踩庄稼", "青苗踩了就长不回来"));
            out.add(new GrowthSpaceParse.ForbiddenItem("私自开闸放水", "水是全村分着用的"));
        } else if ("自然".equals(cat)) {
            out.add(new GrowthSpaceParse.ForbiddenItem("夜里往没灯的地方钻", "野外夜路不安全"));
        }
        return out;
    }

    /** 可发生状态（状态驱动 + 阶段基调）。 */
    private static List<GrowthSpaceParse.StateItem> statesOf(ActorNpcState state) {
        List<GrowthSpaceParse.StateItem> out = new ArrayList<>();
        out.add(new GrowthSpaceParse.StateItem("按部就班做眼前的事", 0.5, null));
        if (state != null) {
            if (state.getHunger() != null && state.getHunger() < 45) {
                out.add(new GrowthSpaceParse.StateItem("被腹中饥饿分心", 0.35, "腹饥难忍"));
            }
            if (state.getEnergy() != null && state.getEnergy() < 35) {
                out.add(new GrowthSpaceParse.StateItem("犯困想歇一歇", 0.35, "精力不济"));
            }
            if (state.getSocial() != null && state.getSocial() < 45) {
                out.add(new GrowthSpaceParse.StateItem("想找人说话解闷", 0.3, "独自一人太久"));
            }
        }
        return out;
    }

    /** 可发生行为（阶段行动池 + 状态驱动项，去重）。 */
    private static List<GrowthSpaceParse.ActionItem> actionsOf(GrowthStage stage, ActorNpcState state) {
        List<GrowthSpaceParse.ActionItem> out = new ArrayList<>();
        for (String action : poolOf(stage)) {
            out.add(new GrowthSpaceParse.ActionItem(action, 0.4, null, null));
        }
        if (state != null) {
            if (state.getHunger() != null && state.getHunger() < 45) {
                out.add(new GrowthSpaceParse.ActionItem("张罗吃食填肚子", 0.6, "腹饥", null));
            }
            if (state.getEnergy() != null && state.getEnergy() < 35) {
                out.add(new GrowthSpaceParse.ActionItem("歇息一阵养精神", 0.6, "精力不济", null));
            }
            if (state.getSocial() != null && state.getSocial() < 45) {
                out.add(new GrowthSpaceParse.ActionItem("去找相熟的人说说话", 0.5, "独处已久", null));
            }
            if (state.getHealth() != null && state.getHealth() < 50) {
                out.add(new GrowthSpaceParse.ActionItem("躺着将养身子", 0.7, "身子不爽", null));
            }
        }
        return out;
    }

    /** 阶段行动池（每个阶段 4-6 项；程序化空间的「设计兜底」形态）。 */
    private static List<String> poolOf(GrowthStage stage) {
        if (stage == null) {
            return List.of("做手头的事", "在附近走走", "歇一歇");
        }
        return switch (stage) {
            case INFANT -> List.of("在襁褓里安睡", "被照护者抱着", "咿呀发声", "哭闹要奶");
            case TODDLER -> List.of("在院子里玩耍", "跟在大人身后", "缠着要吃的", "睡午觉");
            case CHILD -> List.of("和小伙伴在村里跑闹", "帮家里跑腿", "去河边看鱼", "习字玩耍");
            case ADOLESCENT -> List.of("帮家里干活", "与同龄伙伴相处", "练武或读书", "四处走走想心事");
            case YOUTH -> List.of("下地或做工", "去集市采买", "找人闲谈", "练功读书");
            case PRIME -> List.of("处理正事", "去村公所或铺面", "巡查照料家业", "与家人相处");
            case MIDDLE -> List.of("操持家业", "与人议事", "查看庄稼或货栈", "歇息喝茶");
            case ELDER -> List.of("在村里闲走", "坐在门口晒太阳", "去祠堂坐坐", "与老友叙旧");
            case ANCIENT -> List.of("在屋里歇息", "由人搀着在院里走走", "晒太阳打盹");
            case DECEASED -> List.of();
        };
    }

    /** 禁止项（直接取硬门控 forbidden；AI 空间同款消费）。 */
    private static List<GrowthSpaceParse.ForbiddenItem> forbiddenOf(HardGateResult gate) {
        List<GrowthSpaceParse.ForbiddenItem> out = new ArrayList<>();
        if (gate != null && gate.forbidden() != null) {
            for (HardGateResult.ForbiddenItem f : gate.forbidden()) {
                out.add(new GrowthSpaceParse.ForbiddenItem(f.what(), f.reason()));
            }
        }
        return out;
    }

    /** 占位 Map（未使用；保留签名兼容占位——空间 JSON 序列化走 {@code GrowthSpaceDoc#toJson}）。 */
    public static Map<String, Object> emptyInner() {
        return new LinkedHashMap<>();
    }
}
