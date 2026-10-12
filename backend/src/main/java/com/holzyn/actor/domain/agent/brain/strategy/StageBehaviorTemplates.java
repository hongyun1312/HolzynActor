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

package com.holzyn.actor.domain.agent.brain.strategy;

import com.holzyn.actor.domain.growth.stage.GrowthStage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 发育阶段行为候选池（O6 发育演化 · 阶段门控策略的数据源）。
 * <p><b>形态</b>：纯静态、零 AI、可单测——受限阶段（TODDLER/CHILD/ADOLESCENT/
 * ELDER/ANCIENT）角色<b>空闲决策</b>时的行为骨架按「阶段 × 时段」推导，
 * 随机权重选一（{@link #pick}）。与设计文档 §5.4 dormant LOD「纯程序化
 * （由阶段/生理/习惯模板推导候选集，零 AI）」同语义。</p>
 * <p><b>内容口径</b>：文本<b>世界观通用</b>（家/屋前屋后/村里/河边等中性词，
 * 不绑定任何项目地点名）——行为骨架是「这个年纪/这个身体此刻该做的事」，
 * 具体地点与个人化细节由 AI 润色（scene=growth_stage_behavior）自然化。</p>
 * <p><b>actionType 口径</b>：对齐普通 NPC 链既有合法键（""=杂务/其他、
 * play=玩耍、rest=休息、chat=社交；{@code NpcStateService} 候选集键集）。</p>
 */
public final class StageBehaviorTemplates {

    private StageBehaviorTemplates() {
    }

    /** 行为骨架（text=行动文本；actionType=普通链合法键；target=目标地点[空]；reason=来源说明）。 */
    public record BehaviorOption(String text, String actionType, String target, String reason) {
    }

    /** 时段码：dawn 晨 / day 昼 / dusk 昏 / night 夜。 */
    public static final String PERIOD_DAWN = "dawn";
    public static final String PERIOD_DAY = "day";
    public static final String PERIOD_DUSK = "dusk";
    public static final String PERIOD_NIGHT = "night";

    /** 时段中文名（trace/润色 Prompt 展示）。 */
    public static String periodLabel(String period) {
        return switch (period == null ? "" : period) {
            case PERIOD_DAWN -> "清晨";
            case PERIOD_DAY -> "白天";
            case PERIOD_DUSK -> "傍晚";
            case PERIOD_NIGHT -> "夜里";
            default -> "此刻";
        };
    }

    /**
     * 时段判定（对齐 {@code EnvironmentContextService.phaseOf} 语义的简化小时窗）：
     * 晨=5~9、昼=9~17、昏=17~21、夜=21~5（含跨午夜）。
     *
     * @param hourOfDay 当日小时 0-23
     *
     * @return 时段码（恒非空）
     */
    public static String periodOf(int hourOfDay) {
        int h = Math.floorMod(hourOfDay, 24);
        if (h >= 5 && h < 9) {
            return PERIOD_DAWN;
        }
        if (h >= 9 && h < 17) {
            return PERIOD_DAY;
        }
        if (h >= 17 && h < 21) {
            return PERIOD_DUSK;
        }
        return PERIOD_NIGHT;
    }

    /**
     * 是否受限阶段（阶段门控策略适用集：未成年 + 老年；INFANT 由链外婴儿状态机
     * 接管、成年阶段能力全开放走既有链）。
     *
     * @param stage 阶段（null → false）
     *
     * @return true=受限阶段
     */
    public static boolean isRestricted(GrowthStage stage) {
        return stage == GrowthStage.TODDLER || stage == GrowthStage.CHILD
                || stage == GrowthStage.ADOLESCENT || stage == GrowthStage.ELDER
                || stage == GrowthStage.ANCIENT;
    }

    /** 阶段 → 时段 → 行为骨架清单（世界观通用文本；每时段 3~4 条）。 */
    private static final Map<String, Map<String, List<BehaviorOption>>> TEMPLATES = build();

    /** 候选池构建（显式初始化：阶段×时段 → 行为骨架清单）。 */
    private static Map<String, Map<String, List<BehaviorOption>>> build() {
        Map<String, Map<String, List<BehaviorOption>>> m = new LinkedHashMap<>();
        m.put(GrowthStage.TODDLER.name(), Map.of(
                PERIOD_DAWN, List.of(
                        opt("醒来要吃的，缠着大人要抱抱", ""),
                        opt("蹲在门槛上玩泥巴，弄得满手是土", "play"),
                        opt("追着院子里的鸡鸭跑，咯咯直笑", "play")),
                PERIOD_DAY, List.of(
                        opt("在大人脚边玩耍，累了就地打盹", "play"),
                        opt("被哄着睡午觉", "rest"),
                        opt("跟在大孩子后面学步，跌倒了也不哭", "play")),
                PERIOD_DUSK, List.of(
                        opt("坐在门口等大人回家，一见到就扑过去", ""),
                        opt("玩了一天，蔫蔫地靠着门框打呵欠", "rest"),
                        opt("被大人牵着手在村里溜达", "")),
                PERIOD_NIGHT, List.of(
                        opt("早早困了，被哄着睡觉", "rest"),
                        opt("睡梦中咿呀翻身，蹬开被角", "rest"))));
        m.put(GrowthStage.CHILD.name(), Map.of(
                PERIOD_DAWN, List.of(
                        opt("帮家里打水扫院子，干得有模有样", ""),
                        opt("赶去村塾读书识字", ""),
                        opt("和小伙伴约着去玩耍", "play")),
                PERIOD_DAY, List.of(
                        opt("和小伙伴在村里疯跑玩耍", "play"),
                        opt("去河边看鱼捉虾，裤脚卷得老高", "play"),
                        opt("帮家里看摊子打下手", ""),
                        opt("趴在桌上练字读书", "")),
                PERIOD_DUSK, List.of(
                        opt("帮家里收东西搬小凳", ""),
                        opt("和小伙伴在巷子里跑闹，满头是汗", "play"),
                        opt("回家吃饭，一边吃一边说今天的事", "")),
                PERIOD_NIGHT, List.of(
                        opt("在灯下认字，一个字一个字地念", ""),
                        opt("缠着大人讲故事，眼皮打架也不肯睡", "chat"),
                        opt("早早睡了，梦里还在笑", "rest"))));
        m.put(GrowthStage.ADOLESCENT.name(), Map.of(
                PERIOD_DAWN, List.of(
                        opt("帮家里干些轻活，手脚利落", ""),
                        opt("晨起读早课，背一段书", ""),
                        opt("在院子里练拳脚，活动筋骨", "")),
                PERIOD_DAY, List.of(
                        opt("和朋友闲逛聊天，说些有的没的", "chat"),
                        opt("帮家里干活，闷头做事", ""),
                        opt("一个人在河边发呆，想心事", "rest")),
                PERIOD_DUSK, List.of(
                        opt("帮家里收工，把家伙什归置好", ""),
                        opt("和朋友在村口说话，舍不得散", "chat"),
                        opt("在灯下看书习字", "")),
                PERIOD_NIGHT, List.of(
                        opt("在灯下读书，读到很晚", ""),
                        opt("和同伴说悄悄话，压着声音笑", "chat"),
                        opt("早早睡下，想着明天的事", "rest"))));
        m.put(GrowthStage.ELDER.name(), Map.of(
                PERIOD_DAWN, List.of(
                        opt("早起在门口晒太阳，眯着眼", "rest"),
                        opt("慢慢踱步遛弯，走走停停", ""),
                        opt("坐在檐下喝茶，看着村里人来人往", "rest")),
                PERIOD_DAY, List.of(
                        opt("午休小憩一会儿", "rest"),
                        opt("在树荫下乘凉打盹", "rest"),
                        opt("和老伙计下棋聊天", "chat"),
                        opt("看顾孙辈，坐在一旁笑呵呵地看", "")),
                PERIOD_DUSK, List.of(
                        opt("在门口看晚霞，念叨着天凉了", "rest"),
                        opt("和老伴说说话，家长里短", "chat"),
                        opt("慢慢走回家，扶着墙歇一歇", "")),
                PERIOD_NIGHT, List.of(
                        opt("早早歇下，睡得浅", "rest"),
                        opt("听着虫鸣入睡", "rest"))));
        m.put(GrowthStage.ANCIENT.name(), Map.of(
                PERIOD_DAWN, List.of(
                        opt("由人伺候着慢慢洗漱", ""),
                        opt("坐在门口晒晨光，半梦半醒", "rest")),
                PERIOD_DAY, List.of(
                        opt("靠在椅背上打盹", "rest"),
                        opt("坐在屋里听人说话，偶尔应一声", "chat"),
                        opt("闭目养神，手里攥着拐杖", "rest")),
                PERIOD_DUSK, List.of(
                        opt("由人搀着回屋", ""),
                        opt("靠在椅背上歇着，眼皮发沉", "rest")),
                PERIOD_NIGHT, List.of(
                        opt("早早入睡", "rest"),
                        opt("夜里醒来又迷糊睡去", "rest"))));
        return m;
    }

    /** 行为骨架（reason 统一=阶段×时段来源说明）。 */
    private static BehaviorOption opt(String text, String actionType) {
        return new BehaviorOption(text, actionType, "", "");
    }

    /**
     * 取某阶段某时段的行为骨架清单（空阶段/未知时段 → 空清单，零阻断）。
     *
     * @param stage 阶段（null → 空清单）
     *
     * @param period 时段码（null/未知 → 空清单）
     *
     * @return 行为骨架清单（恒非 null，可空）
     */
    public static List<BehaviorOption> candidatesOf(GrowthStage stage, String period) {
        if (stage == null || period == null) {
            return List.of();
        }
        Map<String, List<BehaviorOption>> byPeriod = TEMPLATES.get(stage.name());
        if (byPeriod == null) {
            return List.of();
        }
        List<BehaviorOption> list = byPeriod.get(period);
        return list == null ? List.of() : list;
    }

    /**
     * 随机权重选一个行为骨架（均匀随机；无候选返回 null——调用方链继续）。
     *
     * @param stage 阶段（null → null）
     *
     * @param hourOfDay 当日小时 0-23（时段判定用；越界自动取模）
     *
     * @param rng 随机源（测试可注入固定 seed 验证确定性）
     *
     * @return 行为骨架（null=该阶段该时段无候选）
     */
    public static BehaviorOption pick(GrowthStage stage, int hourOfDay, Random rng) {
        List<BehaviorOption> list = candidatesOf(stage, periodOf(hourOfDay));
        if (list.isEmpty()) {
            return null;
        }
        int idx = (rng == null ? new Random() : rng).nextInt(list.size());
        BehaviorOption o = list.get(idx);
        return new BehaviorOption(o.text(), o.actionType(), o.target(),
                "发育阶段行为（" + (stage == null ? "未知" : stage.label()) + "×"
                        + periodLabel(periodOf(hourOfDay)) + "）");
    }

    /** 全部受限阶段（策略 applies 判定与调试展示用）。 */
    public static List<GrowthStage> restrictedStages() {
        List<GrowthStage> out = new ArrayList<>();
        for (GrowthStage s : GrowthStage.values()) {
            if (isRestricted(s)) {
                out.add(s);
            }
        }
        return out;
    }
}
