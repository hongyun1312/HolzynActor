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

import com.holzyn.actor.domain.mapgen.algo.MapPlacer;

import java.util.ArrayList;
import java.util.List;

/**
 * S2 建筑资产类型——「这一带该有什么房子」，<b>只有类型，没有坐标</b>（用户决策 #19/#20/#27）。
 *
 * <p>大类固定为 民居/商铺/工坊/公共/生产/水工/自然 七种（跨时代可用的通用语义），
 * 名字自由（「渔家小院」「晒盐棚」）——固定大类保证 S4 能按语义选址，自由命名保证不串味。
 * 尺寸/层数/配色都是真实量纲（米、层、hex），S4 直接拿去实例化，不再二次猜。</p>
 *
 * @param code        英文小写下划线（全局唯一）
 * @param name        世界观里的叫法
 * @param category    大类（民居/商铺/工坊/公共/生产/水工/自然）
 * @param kind        几何类型（building / field / forest / pier / wall ...，对齐 {@code actor_map_feature.kind}）
 * @param baseW       开间（米）
 * @param baseD       进深（米）
 * @param floorsMin   最少层数
 * @param floorsMax   最多层数
 * @param wallColor   墙色（hex）
 * @param roofColor   屋顶色（hex）
 * @param roofShape   屋顶形制（flat/gable/hip/shed/dome/spire/thatch）
 * @param material    主材（wood/stone/mud/thatch/brick ...）
 * @param styleTags   形制标签（「坡屋顶」「木构」「晾网架」）
 * @param weight      出现权重（1~20）
 * @param description 一句话「谁在用、用来干什么」
 */
public record MapAssetType(String code, String name, String category, String kind,
                           double baseW, double baseD, int floorsMin, int floorsMax,
                           String wallColor, String roofColor, String roofShape, String material,
                           List<String> styleTags, int weight, String description) {

    /** 允许的大类（与 S2 提示词、{@code actor_map_asset_type.category} 严格一致） */
    public static final List<String> CATEGORIES =
            List.of("民居", "商铺", "工坊", "公共", "生产", "水工", "自然");

    /** 允许的屋顶形制（S4 的几何/渲染按这些形制分支） */
    public static final List<String> ROOF_SHAPES =
            List.of("flat", "gable", "hip", "shed", "dome", "spire", "thatch");

    /** 允许的几何类型 */
    public static final List<String> KINDS =
            List.of("building", "field", "forest", "pier", "wall", "plaza");

    /**
     * 转成落位器输入（S4 只关心尺寸/配色/权重，不关心描述）。
     *
     * @return 落位器资产规格
     */
    public MapPlacer.AssetSpec toPlacerSpec() {
        return new MapPlacer.AssetSpec(code, name, category, kind, baseW, baseD,
                floorsMin, floorsMax, wallColor, roofColor, roofShape,
                material == null ? "wood" : material, weight);
    }

    /**
     * 形制标签落库形态（逗号分隔，列宽 200）。
     *
     * @return 逗号分隔标签（可能为空串）
     */
    public String styleTagsText() {
        return styleTags == null ? "" : String.join(",", styleTags);
    }

    /**
     * 确定性兜底资产库（AI 关闭 / AI 失败时使用）：通用海岛小镇档。
     *
     * <p>只用「民居/商铺/工坊/公共/生产/水工」六个大类的通用类型（民居、粮仓、井台、码头……），
     * <b>不写任何具体世界的地标</b>——兜底一旦写死「长明灯塔」，换任何一个世界都会生成同一座镇子，
     * 那就是把世界观硬编码进了代码里。真实地标由世界数据（或 AI）给出，见 S2 的契约补齐。</p>
     *
     * <p>与离线自检程序 {@code MapFullPreviewMain.islandAssets()} 同一套数据——这样「离线调参看到的图」
     * 与「线上兜底出来的图」是完全一致的，不会出现「本地好看、线上难看」的错位。</p>
     *
     * @return 资产类型库（覆盖六个大类的通用类型）
     */
    public static List<MapAssetType> fallbackIsland() {
        List<MapAssetType> a = new ArrayList<>();
        // 民居（40% 左右：4/19 ≈ 21%，但权重最高 12~16，实际落位数量占多数）
        a.add(b("house_fisher", "渔家小院", "民居", 9.0, 7.5, 1, 2,
                "#d9c9a8", "#8a5a3c", "gable", "wood", 16, "渔民一家三代同住，院里晒网、檐下挂鱼干",
                "坡屋顶", "木构", "晾网架"));
        a.add(b("house_kelp", "海带棚屋", "民居", 7.5, 6.5, 1, 1,
                "#c9b79a", "#b9a06a", "thatch", "thatch", 14, "晒海带的人歇脚的小屋，茅顶厚实挡海风",
                "茅草顶", "低矮"));
        a.add(b("house_stone", "石砌民居", "民居", 10.0, 8.0, 1, 2,
                "#b8b2a6", "#5f5c58", "hip", "stone", 14, "岛上石头多，墙用乱石垒，冬暖夏凉",
                "石墙", "四坡顶"));
        a.add(b("house_timber", "木构瓦房", "民居", 9.5, 8.0, 2, 2,
                "#c8a882", "#5c5145", "gable", "wood", 12, "镇上殷实人家的两层瓦房，二层晒东西",
                "两层", "瓦顶"));
        // 商铺
        a.add(b("shop_grocery", "杂货铺", "商铺", 7.2, 6.4, 1, 2,
                "#d2b98e", "#7a4a33", "gable", "wood", 10, "油盐酱醋、针头线脑，柜台朝着街面",
                "临街", "柜台"));
        a.add(b("shop_tea", "海边茶摊", "商铺", 6.4, 5.6, 1, 1,
                "#d8c39a", "#6a4632", "shed", "wood", 8, "只有竹棚与几张矮桌，卖粗茶和咸鱼干",
                "单坡", "开敞"));
        a.add(b("shop_fish", "鱼行", "商铺", 8.4, 7.0, 1, 2,
                "#c9b088", "#5a4a3a", "gable", "wood", 8, "渔船靠岸就在这里过秤、分拣、上冰",
                "临港", "敞棚"));
        // 工坊
        a.add(b("work_net", "织网坊", "工坊", 11.0, 8.0, 1, 1,
                "#c1a97f", "#6b5a3e", "gable", "wood", 6, "屋前一片空地拉网，妇人们坐着补网",
                "空地", "敞门"));
        a.add(b("work_salt", "盐作坊", "工坊", 12.0, 9.0, 1, 1,
                "#bfb49c", "#5b5348", "flat", "stone", 5, "煮盐的灶与大锅，屋里常年有卤水味",
                "平顶", "灶间"));
        a.add(b("work_kiln", "窑场", "工坊", 13.0, 10.0, 1, 1,
                "#b0a48e", "#4e463c", "shed", "mud", 4, "烧砖瓦的土窑，烟囱比屋脊高",
                "窑体", "烟囱"));
        // 公共
        a.add(b("pub_hall", "镇议事厅", "公共", 16.0, 11.0, 1, 2,
                "#cfc3aa", "#4e5a68", "hip", "wood", 3, "镇上议事的正屋，门前有一对石墩",
                "门厅", "石墩"));
        a.add(b("pub_well", "井亭", "公共", 4.4, 4.4, 1, 1,
                "#c6b79a", "#5d6b52", "hip", "wood", 3, "四根柱子撑一个顶，井绳磨出了深槽",
                "井亭", "单间"));
        a.add(b("pub_school", "蒙学", "公共", 13.0, 9.0, 1, 1,
                "#d5c8ac", "#6a5240", "gable", "wood", 2, "一间书堂，墙上还留着上一届的字",
                "书堂", "开窗"));
        a.add(b("pub_lamp", "灯塔", "公共", 6.0, 6.0, 3, 4,
                "#e2e0d8", "#7a3f39", "spire", "stone", 1, "夜里为归船点灯，守塔人就住在塔下",
                "地标", "尖顶"));
        // 生产
        a.add(b("prod_granary", "谷仓", "生产", 10.0, 7.0, 1, 1,
                "#c8b590", "#6a5a3c", "gable", "wood", 4, "存稻谷和番薯干的高脚仓，仓底架上石板防潮",
                "高脚", "通风"));
        a.add(b("prod_dry", "晒场棚", "生产", 9.0, 7.0, 1, 1,
                "#bfae8c", "#7a6a4a", "shed", "wood", 4, "雨天抢收时堆东西的敞棚，平日晒鱼干",
                "敞棚", "晒架"));
        a.add(b("farm_plot", "田垄", "生产", 30.0, 10.0, 1, 1,
                "#b6ab7a", "#9a8f5e", "flat", "mud", 4, "一垄一垄的旱地，边上是走人的田埂",
                "田块", "田埂"));
        a.add(b("wood_lot", "林地", "自然", 40.0, 34.0, 1, 1,
                "#3f5c34", "#35502c", "flat", "wood", 3, "挡风的杂木林，也是砍柴的地方",
                "密林", "柴山"));
        // 水工
        a.add(b("water_pier", "码头", "水工", 3.2, 14.0, 1, 1,
                "#8a6b4a", "#7a5c3e", "flat", "wood", 3, "木栈桥伸进水里，渔船靠这里卸货",
                "栈桥", "系船桩"));
        a.add(b("water_boat", "船棚", "水工", 8.0, 6.0, 1, 1,
                "#b79b78", "#6a5238", "shed", "wood", 2, "修船补漏的棚子，地上常年有木屑",
                "敞棚", "船架"));
        return a;
    }

    /**
     * 按题材挑确定性兜底资产库（AI 关闭 / AI 失败时使用）。
     *
     * <p><b>为什么必须按题材分</b>：兜底库是「AI 没说话时这张图长什么样」的底线。
     * 如果它只维护一套「海岛渔村」，那换一个高原集镇的世界、AI 又恰好失败，
     * 图上就会长出「海带棚屋」「鱼行」——世界数据里的风俗全被兜底库盖掉了。
     * 这与用户点名的「不许硬编码任何世界观内容」直接冲突。</p>
     *
     * @param genre 题材档（island / river_valley_village / mountain_hamlet / plain_city …）
     * @return 资产类型库
     */
    public static List<MapAssetType> fallbackFor(String genre) {
        if (genre == null || genre.isBlank() || genre.startsWith("island")) {
            return fallbackIsland();
        }
        return fallbackGeneric();
    }

    /**
     * 题材无关的通用兜底库：只有「房子/店铺/作坊/公所/仓廪/田/林/码头」这些
     * 任何前工业聚落都会有的东西，名字一律通称，不带任何具体世界的风味。
     *
     * <p>code 与 {@code fallbackIsland()} 对齐（house_fisher / shop_grocery / farm_plot /
     * wood_lot / water_pier …），这样「必要大类补齐」的索引逻辑两边通用。</p>
     *
     * @return 资产类型库（14 条）
     */
    public static List<MapAssetType> fallbackGeneric() {
        List<MapAssetType> a = new ArrayList<>();
        a.add(b("house_fisher", "民居", "民居", 8.6, 7.2, 1, 2,
                "#cfc3a8", "#6d5947", "gable", "wood", 16, "普通人家的一进院子", "坡屋顶", "木构"));
        a.add(b("house_kelp", "偏屋", "民居", 7.0, 6.0, 1, 1,
                "#c4b596", "#8d7a5c", "thatch", "thatch", 12, "正屋旁的偏屋，草顶低矮", "茅草顶", "低矮"));
        a.add(b("house_stone", "石砌民居", "民居", 9.6, 7.8, 1, 2,
                "#b8b2a6", "#5f5c58", "hip", "stone", 12, "石墙承重的房子，冬暖夏凉", "石墙", "四坡顶"));
        a.add(b("house_timber", "瓦房", "民居", 9.4, 7.8, 2, 2,
                "#c8a882", "#5c5145", "gable", "wood", 10, "家境较好的人家，两层瓦房", "两层", "瓦顶"));
        a.add(b("shop_grocery", "杂货铺", "商铺", 7.2, 6.4, 1, 2,
                "#d2b98e", "#7a4a33", "gable", "wood", 10, "油盐酱醋、针头线脑", "临街", "柜台"));
        a.add(b("shop_tea", "茶摊", "商铺", 6.4, 5.6, 1, 1,
                "#d8c39a", "#6a4632", "shed", "wood", 8, "几张矮桌，卖粗茶", "单坡", "开敞"));
        a.add(b("work_kiln", "窑场", "工坊", 13.0, 10.0, 1, 1,
                "#b0a48e", "#4e463c", "shed", "mud", 5, "烧砖瓦的土窑", "窑体", "烟囱"));
        a.add(b("work_net", "作坊", "工坊", 11.0, 8.0, 1, 1,
                "#c1a97f", "#6b5a3e", "gable", "wood", 6, "屋前一片空地做工", "空地", "敞门"));
        a.add(b("pub_hall", "公所", "公共", 15.0, 10.0, 1, 2,
                "#cfc3aa", "#4e5a68", "hip", "wood", 3, "聚议事的正屋", "门厅", "石墩"));
        a.add(b("pub_well", "井亭", "公共", 4.4, 4.4, 1, 1,
                "#c6b79a", "#5d6b52", "hip", "wood", 3, "四根柱子撑一个顶的井亭", "井亭", "单间"));
        a.add(b("pub_school", "学塾", "公共", 13.0, 9.0, 1, 1,
                "#d5c8ac", "#6a5240", "gable", "wood", 2, "一间书堂", "书堂", "开窗"));
        a.add(b("pub_lamp", "望楼", "公共", 6.0, 6.0, 3, 4,
                "#e2e0d8", "#7a3f39", "spire", "stone", 1, "聚落里最高的一处，登高望远", "地标", "尖顶"));
        a.add(b("prod_granary", "仓廪", "生产", 10.0, 7.0, 1, 1,
                "#c8b590", "#6a5a3c", "gable", "wood", 4, "存粮的高脚仓", "高脚", "通风"));
        a.add(b("farm_plot", "田垄", "生产", 30.0, 10.0, 1, 1,
                "#b6ab7a", "#9a8f5e", "flat", "mud", 4, "一垄一垄的旱地", "田块", "田埂"));
        a.add(b("wood_lot", "林地", "自然", 40.0, 34.0, 1, 1,
                "#3f5c34", "#35502c", "flat", "wood", 3, "挡风的杂木林", "密林", "柴山"));
        a.add(b("water_pier", "码头", "水工", 3.2, 14.0, 1, 1,
                "#8a6b4a", "#7a5c3e", "flat", "wood", 3, "伸进水里的木栈桥", "栈桥", "系船桩"));
        a.add(b("water_boat", "船棚", "水工", 8.0, 6.0, 1, 1,
                "#b79b78", "#6a5238", "shed", "wood", 2, "修船补漏的棚子", "敞棚", "船架"));
        return a;
    }

    /**
     * 构造一条资产类型（内部简写）。
     */
    private static MapAssetType b(String code, String name, String category, double w, double d,
                                  int f0, int f1, String wall, String roof, String shape,
                                  String material, int weight, String desc, String... tags) {
        String kind = switch (category) {
            case "生产" -> "farm_plot".equals(code) ? "field" : "building";
            case "自然" -> "forest";
            case "水工" -> "water_pier".equals(code) ? "pier" : "building";
            default -> "building";
        };
        return new MapAssetType(code, name, category, kind, w, d, f0, f1, wall, roof, shape,
                material, List.of(tags), weight, desc);
    }
}
