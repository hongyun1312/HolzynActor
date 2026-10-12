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

import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 空间候选前置条件的程序化评估器。
 * <p><b>职责</b>：把空间候选的中文 precondition（如「白天且精力≥30」「未下雨」「独处」
 * 「白灵犀在村中」）按「程序化优先 + 语义条件兜底」口径评估为三态：</p>
 * <ul>
 * <li>{@link Verdict#SATISFIED}：可程序化判定且条件成立；</li>
 * <li>{@link Verdict#NOT_SATISFIED}：可程序化判定且条件不成立（候选出局）；</li>
 * <li>{@link Verdict#UNKNOWN}：语义性条件（如「若无人提及旧契」）/ 上下文缺失 / 解析失败
 * ——调用方走 NanoJev boolean 批量兜底，判定失败则宽松放行。</li>
 * </ul>
 * <p><b>支持的条件类型</b>（判据自包含，全部可单测）：</p>
 * <ol>
 * <li><b>时刻</b>：白天/白昼/清晨/早晨/上午/中午/正午/午后/下午/傍晚/黄昏/晚上/夜间/夜里/
 * 夜晚/深夜/凌晨 + 「N点前/后」「N点到N点」区间；</li>
 * <li><b>需求值</b>：{精力|体力|能量}（≥/&gt;/不低于/≤/&lt;/低于/N）数值比较；疲惫/乏了/困倦
 * （精力&lt;40）；饥饿/饿（hunger&lt;70）；饱（hunger≥70）；口渴/渴（thirst&lt;70）；
 * 心情好（mood≥60）/心情差·低落（mood&lt;40）；</li>
 * <li><b>在场者</b>：独处/独自/无人/没人在（同地点无他人）；「XX在村中/在村里/在场/在身边」
 * 与「XX不在…」（按名字匹配全村/同地点名单）；</li>
 * <li><b>天气</b>：下雨/雨天/有雨（天气含「雨」）；晴天/晴朗；下雪/雪天；阴天；大风；</li>
 * <li><b>地点</b>：在XX/回到XX（当前地点包含 XX）；不在XX（否定）。</li>
 * </ol>
 * <p><b>组合与否定</b>：条件按「且/并且/、/，」切段逐段判定（全部满足才 SATISFIED，任一段
 * NOT_SATISFIED 即出局）；段首「未/不/没/非」前缀取反（「未下雨」「不疲惫」）。</p>
 */
public final class SpacePreconditionEvaluator {

    private SpacePreconditionEvaluator() {
    }

    /** 判定结果三态。 */
    public enum Verdict { SATISFIED, NOT_SATISFIED, UNKNOWN }

    /**
     * 程序化判定所需上下文（全部可空；缺失项按 UNKNOWN 处理，宽松放行零阻断）。
     *
     * @param hourOfDay 当前游戏时刻（小时 0-23）
     *
     * @param state 状态行（需求值）
     *
     * @param presentNames 同地点在场角色名（不含本人；null=未知）
     *
     * @param villageNames 全村在世角色名（null=未知）
     *
     * @param weather 当前天气文本（null=未知）
     *
     * @param location 当前地点文本（null=未知）
     */
    public record Context(Integer hourOfDay, ActorNpcState state,
                          List<String> presentNames, List<String> villageNames,
                          String weather, String location) {
    }

    /** 条件切段分隔符（且/并且/、/，/；——任一出现即拆段，逐段判定）。 */
    private static final Pattern SPLIT = Pattern.compile("[且并且、，,;；]");

    /** 时刻词 → 命中区间（小时，含头不含尾；跨午夜区间如夜间 19~30 用 30 表示次日 6）。 */
    private static final String[][] TIME_WORDS = {
            {"白天", "6", "18"}, {"白昼", "6", "18"},
            {"清晨", "5", "8"}, {"早晨", "6", "9"}, {"早上", "6", "9"},
            {"上午", "7", "12"}, {"中午", "11", "14"}, {"正午", "11", "13"},
            {"午后", "12", "18"}, {"下午", "12", "18"}, {"傍晚", "17", "20"},
            {"黄昏", "17", "20"}, {"晚上", "18", "24"}, {"夜晚", "19", "24"},
            {"夜间", "19", "30"}, {"夜里", "19", "30"}, {"深夜", "23", "30"},
            {"凌晨", "0", "6"},
    };

    /** 需求值比较：{关键词, 比较符}（比较符见 compareNeeds）。 */
    private static final Pattern NEED_COMPARE = Pattern.compile(
            "(精力|体力|能量|心情)\\s*(>=|≥|>|<=|≤|<|不低于|高于|大于|不高于|低于|小于|不超过)\\s*(\\d{1,3})");

    /** 数值区间（N点到M点）。 */
    private static final Pattern HOUR_BOUND = Pattern.compile("(\\d{1,2})\\s*点\\s*(?:到|至|~|～)\\s*(\\d{1,2})\\s*点");
    private static final Pattern HOUR_BEFORE = Pattern.compile("(\\d{1,2})\\s*点\\s*(?:以前|之前)");
    private static final Pattern HOUR_AFTER = Pattern.compile("(\\d{1,2})\\s*点\\s*(?:以后|之后)");

    /**
     * 否定前缀（段首出现则取反；「无」不在此列——与「无人/无雨/无事」等固定短语冲突，
     * 其否定语义已内建在关键词里）。
     */
    private static final String NEG_PREFIX = "^(未|不|没|非)";

    /**
     * 评估单个前置条件。
     *
     * @param precondition 前置条件原文（null/空白 → SATISFIED，无约束）
     *
     * @param ctx 程序化判定上下文
     *
     * @return 三态判定
     */
    public static Verdict evaluate(String precondition, Context ctx) {
        if (precondition == null || precondition.isBlank()) {
            return Verdict.SATISFIED;
        }
        List<String> parts = new ArrayList<>();
        for (String seg : SPLIT.split(precondition.trim())) {
            String s = seg.trim();
            if (!s.isEmpty()) {
                parts.add(s);
            }
        }
        if (parts.isEmpty()) {
            return Verdict.SATISFIED;
        }
        boolean anyUnknown = false;
        for (String part : parts) {
            Verdict v = evaluatePart(part, ctx);
            if (v == Verdict.NOT_SATISFIED) {
                return Verdict.NOT_SATISFIED;
            }
            if (v == Verdict.UNKNOWN) {
                anyUnknown = true;
            }
        }
        return anyUnknown ? Verdict.UNKNOWN : Verdict.SATISFIED;
    }

    /** 单段判定（含否定前缀处理）。 */
    private static Verdict evaluatePart(String part, Context ctx) {
        boolean negated = part.matches(NEG_PREFIX + ".*");
        String body = negated ? part.substring(1).trim() : part;
        if (body.isBlank()) {
            return Verdict.UNKNOWN;
        }
        Verdict v = evaluateBody(body, ctx);
        if (negated) {
            return switch (v) {
                case SATISFIED -> Verdict.NOT_SATISFIED;
                case NOT_SATISFIED -> Verdict.SATISFIED;
                case UNKNOWN -> Verdict.UNKNOWN;
            };
        }
        return v;
    }

    /** 段主体判定（无否定前缀）。 */
    private static Verdict evaluateBody(String body, Context ctx) {
        // ① 时刻类（词表 + 数值区间）
        if (ctx.hourOfDay() != null) {
            for (String[] tw : TIME_WORDS) {
                if (body.contains(tw[0])) {
                    return inHourRange(ctx.hourOfDay(), Integer.parseInt(tw[1]), Integer.parseInt(tw[2]))
                            ? Verdict.SATISFIED : Verdict.NOT_SATISFIED;
                }
            }
            Matcher range = HOUR_BOUND.matcher(body);
            if (range.find()) {
                return inHourRange(ctx.hourOfDay(), Integer.parseInt(range.group(1)), Integer.parseInt(range.group(2)) + 1)
                        ? Verdict.SATISFIED : Verdict.NOT_SATISFIED;
            }
            Matcher before = HOUR_BEFORE.matcher(body);
            if (before.find()) {
                return ctx.hourOfDay() < Integer.parseInt(before.group(1))
                        ? Verdict.SATISFIED : Verdict.NOT_SATISFIED;
            }
            Matcher after = HOUR_AFTER.matcher(body);
            if (after.find()) {
                return ctx.hourOfDay() >= Integer.parseInt(after.group(1))
                        ? Verdict.SATISFIED : Verdict.NOT_SATISFIED;
            }
        }
        // ② 需求值比较（精力/体力/能量/心情 × 比较符 × 数值）
        Matcher nc = NEED_COMPARE.matcher(body);
        if (nc.find()) {
            if (ctx.state() == null) {
                return Verdict.UNKNOWN;
            }
            return compareNeeds(nc.group(1), nc.group(2), Integer.parseInt(nc.group(3)), ctx.state());
        }
        // ③ 需求词（无数值比较的弱语义）
        if (ctx.state() != null) {
            if (body.contains("疲惫") || body.contains("乏了") || body.contains("困倦")) {
                return below(ctx.state().getEnergy(), 40) ? Verdict.SATISFIED : Verdict.NOT_SATISFIED;
            }
            if (body.contains("饥饿") || body.contains("饿了") || body.contains("饥")) {
                return below(ctx.state().getHunger(), 70) ? Verdict.SATISFIED : Verdict.NOT_SATISFIED;
            }
            if (body.contains("饱")) {
                return ctx.state().getHunger() != null && ctx.state().getHunger() >= 70
                        ? Verdict.SATISFIED : Verdict.NOT_SATISFIED;
            }
            if (body.contains("口渴") || body.contains("渴")) {
                return below(ctx.state().getThirst(), 70) ? Verdict.SATISFIED : Verdict.NOT_SATISFIED;
            }
            if (body.contains("心情好") || body.contains("情绪好")) {
                return ctx.state().getMood() != null && ctx.state().getMood() >= 60
                        ? Verdict.SATISFIED : Verdict.NOT_SATISFIED;
            }
            if (body.contains("心情差") || body.contains("心情低落") || body.contains("情绪低落")) {
                return ctx.state().getMood() != null && ctx.state().getMood() < 40
                        ? Verdict.SATISFIED : Verdict.NOT_SATISFIED;
            }
        }
        // ④ 在场者类（独处 / XX在… / XX不在…）
        if (body.contains("独处") || body.contains("独自") || body.contains("独自一人")) {
            if (ctx.presentNames() == null) {
                return Verdict.UNKNOWN;
            }
            return ctx.presentNames().isEmpty() ? Verdict.SATISFIED : Verdict.NOT_SATISFIED;
        }
        if (body.contains("无人") || body.contains("没人") || body.contains("没人在") || body.contains("身边无人")) {
            if (ctx.presentNames() == null) {
                return Verdict.UNKNOWN;
            }
            return ctx.presentNames().isEmpty() ? Verdict.SATISFIED : Verdict.NOT_SATISFIED;
        }
        Verdict presence = presenceVerdict(body, ctx);
        if (presence != Verdict.UNKNOWN) {
            return presence;
        }
        // ⑤ 天气类
        Verdict weather = weatherVerdict(body, ctx);
        if (weather != Verdict.UNKNOWN) {
            return weather;
        }
        // ⑥ 地点类（在XX / 回到XX / 不在XX）
        Verdict location = locationVerdict(body, ctx);
        if (location != Verdict.UNKNOWN) {
            return location;
        }
        // ⑦ 语义性条件 / 无法判定 → UNKNOWN（调用方 NanoJev boolean 兜底，失败宽松放行）
        return Verdict.UNKNOWN;
    }

    /** 需求值比较（关键词 × 比较符 × 目标值）。 */
    private static Verdict compareNeeds(String key, String op, int target, ActorNpcState st) {
        Integer value = switch (key) {
            case "精力", "体力", "能量" -> st.getEnergy();
            case "心情" -> st.getMood();
            default -> null;
        };
        if (value == null) {
            return Verdict.UNKNOWN;
        }
        boolean ok = switch (op) {
            case ">=", "≥", "不低于", "大于", "高于" -> value >= target;
            case "<=", "≤", "不高于", "不超过" -> value <= target;
            case "<", "低于", "小于" -> value < target;
            default -> false;
        };
        return ok ? Verdict.SATISFIED : Verdict.NOT_SATISFIED;
    }

    /** 在场者类（XX在村中/在场/在身边，XX不在…；名字来自名单，非名字的「在」不在此类）。 */
    private static Verdict presenceVerdict(String body, Context ctx) {
        // ⚠️ 不含裸「在村」：地点短语（在村口/在村东）会误命中在场标记，须留给地点类判定
        boolean inVillage = body.contains("在村中") || body.contains("在村里") || body.contains("在村子")
                || body.contains("在镇上") || body.contains("在镇里");
        boolean present = body.contains("在场") || body.contains("在身边") || body.contains("在此")
                || body.contains("在旁边") || body.contains("在跟前");
        if (!inVillage && !present) {
            return Verdict.UNKNOWN;
        }
        List<String> names = inVillage ? ctx.villageNames() : ctx.presentNames();
        if (names == null) {
            return Verdict.UNKNOWN;
        }
        boolean negated = body.contains("不在") || body.contains("没在");
        for (String name : names) {
            if (name != null && !name.isBlank() && body.contains(name)) {
                // 名单含该名字：非否定 → 满足；否定（不在…）→ 不满足
                return negated ? Verdict.NOT_SATISFIED : Verdict.SATISFIED;
            }
        }
        // 名单里没有该名字：说「XX在场」而名单无此人 → 不满足；「XX不在」→ 满足
        return negated ? Verdict.SATISFIED : Verdict.NOT_SATISFIED;
    }

    /** 天气类（下雨/晴天/下雪/阴天/大风——天气文本包含关键词）。 */
    private static Verdict weatherVerdict(String body, Context ctx) {
        if (ctx.weather() == null || ctx.weather().isBlank()) {
            return Verdict.UNKNOWN;
        }
        String w = ctx.weather();
        if (body.contains("下雨") || body.contains("雨天") || body.contains("有雨") || body.contains("落雨")) {
            return w.contains("雨") ? Verdict.SATISFIED : Verdict.NOT_SATISFIED;
        }
        if (body.contains("无雨") || body.contains("没下雨") || body.contains("不下雨") || body.contains("晴")) {
            return w.contains("雨") ? Verdict.NOT_SATISFIED : Verdict.SATISFIED;
        }
        if (body.contains("下雪") || body.contains("雪天") || body.contains("有雪")) {
            return w.contains("雪") ? Verdict.SATISFIED : Verdict.NOT_SATISFIED;
        }
        if (body.contains("阴天") || body.contains("天阴")) {
            return w.contains("阴") ? Verdict.SATISFIED : Verdict.NOT_SATISFIED;
        }
        if (body.contains("大风") || body.contains("风大") || body.contains("刮风")) {
            return w.contains("风") ? Verdict.SATISFIED : Verdict.NOT_SATISFIED;
        }
        return Verdict.UNKNOWN;
    }

    /** 地点类（在XX / 回到XX / 不在XX——当前地点包含 XX）。 */
    private static Verdict locationVerdict(String body, Context ctx) {
        if (ctx.location() == null || ctx.location().isBlank()) {
            return Verdict.UNKNOWN;
        }
        String loc = ctx.location();
        if (body.contains("不在") && body.length() > 2) {
            String place = body.substring(body.indexOf("不在") + 2).trim();
            if (!place.isEmpty()) {
                return loc.contains(place) ? Verdict.NOT_SATISFIED : Verdict.SATISFIED;
            }
        }
        if (body.contains("在") && !body.contains("在场") && !body.contains("在跟前")
                && !body.contains("在身边") && !body.contains("在此")
                && !body.contains("在村里") && !body.contains("在村中") && !body.contains("在村子")
                && !body.contains("在镇上") && !body.contains("在镇里")) {
            String place = body.substring(body.indexOf("在") + 1).trim();
            if (place.length() >= 2 && place.length() <= 12) {
                return loc.contains(place) ? Verdict.SATISFIED : Verdict.NOT_SATISFIED;
            }
        }
        return Verdict.UNKNOWN;
    }

    /** 小时区间判定（跨午夜区间用 24~30 表示次日 0~6）。 */
    private static boolean inHourRange(int hour, int start, int end) {
        if (start <= end) {
            return hour >= start && hour < end;
        }
        return hour >= start || hour < end;
    }

    private static boolean below(Integer value, int threshold) {
        return value != null && value < threshold;
    }
}
