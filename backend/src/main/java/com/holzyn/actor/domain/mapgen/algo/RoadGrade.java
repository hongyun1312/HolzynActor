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

/**
 * 道路等级（数字越大路越好、越宽、越平整）。
 * <p><b>为什么要分级？</b>因为没有分级的图上，镇里的石板正街和一条田埂小路
 * 画出来一模一样粗细、一样颜色——玩家一眼就看不出「这是官道」还是「这是去菜地的土路」。
 * 现实里路的等级决定了一切：谁维护它、能不能走车、下雨天能不能过。</p>
 * <table border="1">
 *   <caption>等级约定</caption>
 *   <tr><th>等级</th><th>名称</th><th>典型宽度</th><th>路面</th><th>谁在用</th></tr>
 *   <tr><td>0</td><td>田埂土路</td><td>1.6~2.2 米</td><td>夯土/草径</td><td>人、牛车</td></tr>
 *   <tr><td>1</td><td>村庄道路</td><td>2.4~3.2 米</td><td>土路、碎石</td><td>独轮车、挑担</td></tr>
 *   <tr><td>2</td><td>镇内主街</td><td>4.0~5.5 米</td><td>石板、夯土</td><td>板车、集市</td></tr>
 *   <tr><td>3</td><td>干线官道</td><td>6.0~8.0 米</td><td>石板大道</td><td>马车队（后续扩展）</td></tr>
 * </table>
 */
public final class RoadGrade {

    /** 0 级：田埂土路——田与田之间、田与村之间的生产路。 */
    public static final int TRACK = 0;

    /** 1 级：村庄道路——巷子、胡同、补出来的连接路。 */
    public static final int VILLAGE = 1;

    /** 2 级：镇内主街——镇子的骨架，集市与码头都接在它上面。 */
    public static final int STREET = 2;

    /** 3 级：干线官道——预留：跨镇域的马车道。 */
    public static final int TRUNK = 3;

    /** 目前支持的最高等级。 */
    public static final int MAX = TRUNK;

    private RoadGrade() {
    }

    /** 中文名（给 UI / 日志用）。 */
    public static String label(int grade) {
        return switch (clamp(grade)) {
            case TRACK -> "田埂土路";
            case VILLAGE -> "村庄道路";
            case STREET -> "镇内主街";
            default -> "干线官道";
        };
    }

    /** 语义标签（落库到 {@code level} 列，便于按语义检索）。 */
    public static String level(int grade) {
        return switch (clamp(grade)) {
            case TRACK -> "track";
            case VILLAGE -> "village";
            case STREET -> "street";
            default -> "trunk";
        };
    }

    /** 语义标签 → 等级（读旧数据/前端传参时用）。 */
    public static int ofLevel(String level) {
        if (level == null) {
            return VILLAGE;
        }
        return switch (level) {
            case "track", "trail", "path" -> TRACK;
            case "village", "minor", "lane", "alley" -> VILLAGE;
            case "street", "major" -> STREET;
            case "trunk", "highway" -> TRUNK;
            default -> VILLAGE;
        };
    }

    /** 该等级路的默认宽度（米）。 */
    public static double defaultWidth(int grade) {
        // 旧的 1.8 / 2.8 / 5.0 / 7.0 在 1 米 1 像素的图上就是「一条比房子还宽的灰带」。
        // 真实村落：田埂 1.6 米、巷子 2.4 米、镇内主街 4.2 米、官道 6 米。
        return switch (clamp(grade)) {
            case TRACK -> 1.6;
            case VILLAGE -> 2.4;
            case STREET -> 4.2;
            default -> 6.0;
        };
    }

    /** 该等级路的默认路面材质。 */
    public static String defaultSurface(int grade) {
        return switch (clamp(grade)) {
            case TRACK -> "dirt";
            case VILLAGE -> "dirt";
            case STREET -> "stone";
            default -> "stone";
        };
    }

    /** 等级越高，路越「重要」——排序时先画高等级，高等级压在它上面。 */
    public static int clamp(int grade) {
        return Math.max(TRACK, Math.min(MAX, grade));
    }

    /**
     * 把「想要的宽度」夹到该等级的真实区间（米）。
     * <p>为什么必须夹：AI 与用户在拓扑里给的 width 只是<b>倾向</b>（它常写「生产路 4 米」），
     * 而宽度和等级在现实里是绑死的——4 米宽的田埂在图上会盖住一片田，一眼假。
     * 区间取「该等级最常见的人工/自然宽度」，超出就退回 {@link #defaultWidth}。</p>
     *
     * @param grade 等级
     * @param width 期望宽度（米；非正数取该等级默认宽度）
     * @return 夹取后的宽度（米）
     */
    public static double clampWidth(int grade, double width) {
        if (width <= 0 || Double.isNaN(width) || Double.isInfinite(width)) {
            return defaultWidth(grade);
        }
        double lo = switch (clamp(grade)) {
            case TRACK -> 1.6;
            case VILLAGE -> 2.4;
            case STREET -> 4.0;
            default -> 6.0;
        };
        double hi = switch (clamp(grade)) {
            case TRACK -> 2.4;
            case VILLAGE -> 3.2;
            case STREET -> 5.5;
            default -> 8.0;
        };
        return Math.max(lo, Math.min(hi, width));
    }
}
