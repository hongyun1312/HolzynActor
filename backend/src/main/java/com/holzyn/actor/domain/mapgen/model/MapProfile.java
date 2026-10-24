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

package com.holzyn.actor.domain.mapgen.model;

import com.holzyn.actor.domain.mapgen.algo.MapSites;
import com.holzyn.actor.domain.mapgen.algo.TerrainParams;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S0 世界档案——AI 对「这片土地长什么样」的一句话式描述 + 一组可解释的地理量。
 * <p>关键约束（用户决策 #2/#27）：<b>档案里没有任何坐标</b>。AI 只说「岛多狭长、山脊朝哪、
 * 海岸多碎、有没有河」，几何由 {@link com.holzyn.actor.domain.mapgen.algo.TerrainGenerator}
 * 用种子确定性推导。这样 AI 写错一个数字，最坏只是「岛的形状不太像设定」，不会出现
 * 「房子盖在河里、路穿过山脊」这种结构性崩坏（上一版 AI 直接吐坐标的失败根因）。</p>
 *
 * <p>档案还带两样 S2/S4 真正要用的东西：</p>
 * <ul>
 *   <li>{@link #forbidden()} 禁忌清单——「这个世界不该出现的建筑」，S2 生成后逐条过滤；</li>
 *   <li>{@link #landmarks()} 地貌级地标——只给空间语义（「岬角最东端的高处」），
 *       S3 用 {@link MapSites#pick} 把它落到真实地形上当地标路的端点。</li>
 * </ul>
 *
 * @param mapName           地图名（如「青屿镇」）
 * @param genreProfile      题材档（island / river_valley_village / ...）
 * @param climate           气候一句话
 * @param narrative         整体地貌 2~3 句叙述（给策划看，不进渲染）
 * @param params            地形生成参数（由 AI 数值 + 题材档兜底合成）
 * @param landmarks         地貌级地标（空间语义，不含坐标）
 * @param forbidden         禁忌清单（建筑类型名/大类名，S2 过滤用）
 * @param allowedCategories 允许的建筑大类
 * @param rawJson           AI 原文（排障/回放用；兜底档案为 {@code null}）
 * @param aiGenerated       是否 AI 产出（false=确定性兜底，前端可据此标注「离线档案」）
 */
public record MapProfile(String mapName, String genreProfile, String climate, String narrative,
                         TerrainParams params, List<Landmark> landmarks, List<String> forbidden,
                         List<String> allowedCategories, String rawJson, boolean aiGenerated) {

    /**
     * 地貌级地标（只有空间语义，没有坐标）。
     *
     * @param name 地标名（「老灯塔」）
     * @param kind 类型（lighthouse / cape / old_tree / ferry / spring ...）
     * @param sit  空间语义（「岬角最东端的高处」）
     * @param note 一句话说明
     */
    public record Landmark(String name, String kind, String sit, String note) {
    }

    /** 兜底禁忌清单（世界观未给时使用；对齐青屿镇设定：无武器铺/监牢/赌坊/现代设施） */
    public static final List<String> DEFAULT_FORBIDDEN =
            List.of("现代设施", "武器铺", "监牢", "赌坊", "神殿", "摩天楼");

    /** 兜底允许大类（建筑资产模板要求覆盖的六类） */
    public static final List<String> DEFAULT_CATEGORIES =
            List.of("民居", "商铺", "工坊", "公共", "生产", "水工");

    /**
     * 确定性兜底档案（AI 关闭 / AI 失败时使用）：<b>只有题材级默认，不含任何具体世界</b>。
     *
     * <p>旧兜底直接写死「青屿镇 / 长明灯塔 / 灯长年不灭」——AI 正常时它不触发，
     * 但 AI 一失败，换任何一个世界都会生成同一座青屿镇。这违反了用户点名的
     * 「不许硬编码任何世界观内容」。现在兜底只给结构：题材级地图名、通用地标名
     * （「灯塔」「渡口古树」——是<b>类型名</b>而不是专名）、题材级气候，
     * 具体名字一律留空由调用方（世界数据）填。</p>
     *
     * @param seed 种子
     * @param size 边长（格）
     * @return 兜底档案（{@code aiGenerated=false}）
     */
    public static MapProfile fallback(long seed, int size) {
        TerrainParams p = TerrainParams.island(seed, size);
        String genre = p.genreProfile == null ? "island" : p.genreProfile;
        List<Landmark> marks = new ArrayList<>();
        // 地标名用「类型名」：它只是把语义点占住，真正的名字由世界数据/契约覆盖
        marks.add(new Landmark("灯塔", "lighthouse", "岬角最东端的高处", "供归船辨认岸线"));
        marks.add(new Landmark("渡口古树", "old_tree", "河口西岸的缓坡上", "等船的人在此歇脚"));
        return new MapProfile(genreDefaultName(genre), genre, genreDefaultClimate(genre), null, p,
                marks, DEFAULT_FORBIDDEN, DEFAULT_CATEGORIES, null, false);
    }

    /** 题材级默认地图名（不是专名：专名只能来自世界数据）。 */
    private static String genreDefaultName(String genre) {
        return switch (genre) {
            case "river_valley_village" -> "河谷村落";
            case "mountain_hamlet" -> "山村聚落";
            case "plain_city" -> "平原城镇";
            case "coastal_town" -> "滨海城镇";
            default -> "海岛聚落";
        };
    }

    /** 题材级默认气候（一句话，不含本世界专名）。 */
    private static String genreDefaultClimate(String genre) {
        return switch (genre) {
            case "river_valley_village" -> "温带季风气候，夏季多雨，冬季干冷";
            case "mountain_hamlet" -> "山地气候，昼夜温差大，多雾";
            case "plain_city" -> "温带大陆性气候，四季分明";
            case "coastal_town" -> "温润海洋性气候，多风多雾";
            default -> "温润海洋性气候，多雾多风";
        };
    }

    /**
     * 档案 → 语义点表（S3/S4 的端点解析用）。地标按 {@code landmark_<code>} 挂进点表，
     * 位置取「最接近它空间语义的既有语义点」——这是「AI 说高处」落到「图上那块高地」的唯一实现。
     *
     * @param sites 已由地形求出的语义点
     * @return 语义点表（含地标）
     */
    public Map<String, MapSites.Site> mergeLandmarks(Map<String, MapSites.Site> sites) {
        Map<String, MapSites.Site> out = new LinkedHashMap<>(sites);
        if (landmarks == null) {
            return out;
        }
        int i = 0;
        for (Landmark m : landmarks) {
            String prefer = switch (m.kind() == null ? "" : m.kind()) {
                case "lighthouse", "high_ground" -> "high-ground";
                case "cape", "harbor" -> "coast";
                case "ferry", "spring" -> "river";
                case "old_tree" -> "settlement-center";
                default -> "settlement-edge";
            };
            MapSites.Site s = MapSites.pick(sites, prefer, MapSites.CORE);
            String code = "landmark_" + i++;
            out.put(code, new MapSites.Site(code, m.name(), "landmark", s.x(), s.z(), 0.5));
        }
        return out;
    }

    /**
     * 档案摘要（给 S2/S3 的提示词用，避免把整段设定重复塞进第二次调用）。
     *
     * @return 人读摘要（每行一条）
     */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("地图名：").append(mapName == null ? "未命名" : mapName).append('\n');
        sb.append("题材：").append(genreProfile).append('\n');
        if (climate != null) {
            sb.append("气候：").append(climate).append('\n');
        }
        sb.append(String.format("尺度：%d 米见方（1 格 1 米）%n", (int) Math.round(params.size * params.metersPerCell)));
        sb.append(String.format("地形：最高点 %.0f 米，平原占比 %.2f，起伏 %.2f%n",
                params.elevationMeters, params.plainFraction, params.reliefStrength));
        sb.append(String.format("水系：河流 %d 条，湖泊 %d 个%n", params.riverCount, params.lakeCount));
        sb.append(String.format("海岸：半径占比 %.2f，破碎度 %.2f，海湾 %d 个%n",
                params.islandRadiusRatio, params.coastRoughness, params.bays));
        if (landmarks != null && !landmarks.isEmpty()) {
            sb.append("地标：");
            for (Landmark m : landmarks) {
                sb.append(m.name()).append('（').append(m.sit()).append("）、");
            }
            sb.setLength(sb.length() - 1);
            sb.append('\n');
        }
        if (forbidden != null && !forbidden.isEmpty()) {
            sb.append("禁忌：").append(String.join("、", forbidden)).append('\n');
        }
        return sb.toString();
    }
}
