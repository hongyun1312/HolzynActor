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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界 → 地图契约（S0b）：把「世界观 / 地点 / 住所」翻译成地图生成真正需要的那几件事。
 *
 * <p><b>为什么要有这一层</b>：旧管线里 S0 只产出「地形参数」，数量（多少栋房）、设施（灯塔/渡口/
 * 药铺到底有哪些）、区域方位（北丘陵、南滩涂、西海湾）全是算法自己猜的——于是 12 户人家的岛
 * 被铺成 485 栋房，聚落位置和世界观写的「北端海岬的灯塔」毫无关系。这一层把「世界数据」变成
 * <b>可校验的约束</b>，落位器只认它，不再自己猜。</p>
 *
 * <p><b>铁律：不含任何世界观内容</b>。本类只有通用字段（区域/设施/户数/方位），
 * 任何具体地名、角色名、设施名都来自世界数据（AI 抽取或数据库），代码里不出现。</p>
 *
 * <p><b>为什么 AI 只给「语义与方位」</b>：LLM 不擅长给绝对坐标（给出的一定是错的），
 * 但非常擅长「这个地点在北边海岬上、是石塔、该有一层灯室」这类语义判断。坐标一律由程序
 * 在地形上求（见 {@code MapSites} / {@code MapPlacer}）。</p>
 *
 * @param terrainFamily     地形族（ISLAND / MOUNTAIN_VALLEY / PLAIN ...；当前只实现 ISLAND）
 * @param regions           自然/功能区域（方位 + 半径带 + 高程/植被/湿度偏置）
 * @param settlement        聚落（中心方位、半径、最大坡度）
 * @param demography        人口学（户数 / 户均人口），由住所表推导，不交给 AI
 * @param facilities        公共设施（灯塔/渡口/市集/议事/药铺…，每个都挂到一个世界地点上）
 * @param dwellings         住宅（每个住所一栋，含住户引用）
 * @param forbidden         禁忌清单（三层合成后的只读结果）
 * @param allowedCategories 允许的建筑大类
 * @param rawJson           AI 原文（排障用；兜底为 null）
 * @param aiGenerated       是否 AI 产出
 */
public record WorldMapBrief(String terrainFamily, List<Region> regions, Settlement settlement,
                            Demography demography, List<Facility> facilities, List<Dwelling> dwellings,
                            List<String> forbidden, List<String> allowedCategories,
                            String rawJson, boolean aiGenerated) {

    /** 户均人口（用户决策：一户约 3 人）。 */
    public static final int DEFAULT_HOUSEHOLD_SIZE = 3;

    /** 每户附属房数量（柴房/谷仓/猪圈/渔具棚），真实聚落里这是撑体量又不虚增人口的关键。 */
    public static final double OUTBUILDING_PER_HOUSEHOLD = 1.2;

    /** 空置旧屋数量上限（真实村落总有几间没人住的旧屋）。 */
    public static final int VACANT_HOUSES = 2;

    /**
     * 一个区域。
     *
     * @param code           英文编码
     * @param name           世界里的叫法
     * @param kind           HILLS/FOREST/MUDFLAT/BAY/CAPE/SETTLEMENT/CREEK/RIDGE/FIELD...
     * @param bearingDeg     方位角（0=正北，顺时针）
     * @param radialFrom     内半径（相对地图半边长，0~1）
     * @param radialTo       外半径（相对地图半边长，0~1）
     * @param elevationBias  高程偏置（-1~1，正=更高）
     * @param treeDensity    植被密度（0~1）
     * @param moistureBias   湿度偏置（-1~1，正=更湿）
     */
    public record Region(String code, String name, String kind, double bearingDeg,
                         double radialFrom, double radialTo, double elevationBias,
                         double treeDensity, double moistureBias) {
    }

    /**
     * 聚落约束。
     *
     * @param centerBearingDeg 聚落中心相对地图中心的方位（0=正北）
     * @param radiusM          期望聚落半径（米；实际值还会被人口规模夹取）
     * @param slopeMax         可建最大坡度（tan 值）
     */
    public record Settlement(double centerBearingDeg, double radiusM, double slopeMax) {
    }

    /**
     * 人口学（全部由住所/角色数据推导，不交给 AI）。
     *
     * @param households   户数
     * @param householdSize 户均人口
     */
    public record Demography(int households, int householdSize) {

        /** 常住人口。 */
        public int residents() {
            return Math.max(0, households) * Math.max(1, householdSize);
        }
    }

    /**
     * 公共设施。
     *
     * @param code          英文编码
     * @param name          世界里的叫法
     * @param kind          lighthouse/pier/plaza/hall/shop/school/workshop/well/shrine/mill...
     * @param anchorLocation 必须挂到的世界地点名（空=不锚定）
     * @param role          大类（公共/商铺/工坊/生产/水工）
     * @param frontageM     面宽（米）
     * @param depthM        进深（米）
     * @param floors        层数
     */
    public record Facility(String code, String name, String kind, String anchorLocation,
                           String role, double frontageM, double depthM, int floors) {
    }

    /**
     * 住宅（一个住所一栋）。
     *
     * @param homeRef        住所引用（home id 或名称）
     * @param homeName       住所名
     * @param anchorLocation 所属地点名
     * @param members        住户引用（角色 ref）
     */
    public record Dwelling(String homeRef, String homeName, String anchorLocation, List<String> members) {
    }

    // ==================== 派生数量（落位器的唯一数量来源） ====================

    /** 住宅目标数 = 户数。 */
    public int dwellingTarget() {
        return Math.max(1, demography == null ? 1 : demography.households());
    }

    /** 附属房目标数（柴房/谷仓/猪圈/渔具棚）。 */
    public int outbuildingTarget() {
        return (int) Math.round(dwellingTarget() * OUTBUILDING_PER_HOUSEHOLD);
    }

    /** 公共设施目标数。 */
    public int facilityTarget() {
        return facilities == null ? 0 : facilities.size();
    }

    /** 商铺目标数（设施里的商铺 + 每 6 户一间杂货铺，下限 2）。 */
    public int shopTarget() {
        int fromFacilities = 0;
        if (facilities != null) {
            for (Facility f : facilities) {
                if ("商铺".equals(f.role())) {
                    fromFacilities++;
                }
            }
        }
        return Math.max(2, Math.max(fromFacilities, dwellingTarget() / 6));
    }

    /** 工坊目标数（铁匠/染坊/磨坊这类，按户数给，下限 2）。 */
    public int workshopTarget() {
        return Math.max(2, dwellingTarget() / 4);
    }

    /** 水工目标数（码头/井台/水渠），下限 1。 */
    public int waterTarget() {
        // 契约里点名的水工设施（渡口/井台/水渠）至少要放得下，不能被「每 8 户 1 个」压掉
        int fromFacilities = 0;
        if (facilities != null) {
            for (Facility f : facilities) {
                if ("水工".equals(f.role())) {
                    fromFacilities++;
                }
            }
        }
        return Math.max(1, Math.max(fromFacilities, dwellingTarget() / 8));
    }

    /** 建筑总数上限（住宅 + 附属房 + 公共 + 商铺 + 工坊 + 空置屋）。 */
    public int buildingBudget() {
        return dwellingTarget() + outbuildingTarget() + facilityTarget()
                + shopTarget() + workshopTarget() + VACANT_HOUSES;
    }

    /**
     * 建议聚落半径（米）：按结构数反推，而不是让算法铺满整个平原。
     *
     * <p>每个结构按「占地 + 院子 + 巷道」约 420 ㎡ 估，面积 = πr²。
     * 12 户的小村落在 80 米半径内就摆得下——这正是「真实聚落该有的尺度」。</p>
     *
     * @param fallbackM 世界没给半径时的兜底（通常取地形聚落半径）
     * @return 半径（米，45~320 夹取）
     */
    public double suggestedTownRadiusM(double fallbackM) {
        double byCount = Math.sqrt(Math.max(1, buildingBudget()) * 420.0 / Math.PI);
        double wanted = settlement == null || settlement.radiusM() <= 0 ? fallbackM : settlement.radiusM();
        if (wanted > 0) {
            // 世界给的半径是上限（它描述的是「聚落影响范围」），人口规模是硬下限
            byCount = Math.min(Math.max(byCount, fallbackM * 0.45), Math.max(byCount, wanted));
        }
        return Math.max(45, Math.min(320, byCount));
    }

    /**
     * 通用兜底契约（AI 失败/关闭，或世界没有任何地点数据时使用）。
     *
     * <p><b>不含任何世界观内容</b>：只有户数与空列表。落位器会退化成「一个尺度由人口决定的
     * 普通聚落」，仍然比铺满整张图真实。</p>
     *
     * @param households 户数
     * @return 兜底契约（aiGenerated=false）
     */
    public static WorldMapBrief genericFallback(int households) {
        return new WorldMapBrief("ISLAND", List.of(), null,
                new Demography(Math.max(1, households), DEFAULT_HOUSEHOLD_SIZE),
                List.of(), List.of(), List.of(), List.of(), null, false);
    }

    /** 是否为空契约（没有区域/设施信息，只能靠人口兜底）。 */
    public boolean emptyWorldData() {
        return (regions == null || regions.isEmpty()) && (facilities == null || facilities.isEmpty());
    }

    /** 人读摘要（进 S2/S3 提示词，避免重复塞整段设定）。 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("地形族：").append(terrainFamily == null ? "ISLAND" : terrainFamily).append('\n');
        if (demography != null) {
            sb.append(String.format("人口：%d 户 / 户均 %d 人 / 常住 %d 人%n",
                    demography.households(), demography.householdSize(), demography.residents()));
        }
        if (regions != null && !regions.isEmpty()) {
            sb.append("区域：\n");
            for (Region r : regions) {
                sb.append(String.format("- %s（%s，方位 %.0f°，半径 %.2f~%.2f）%n",
                        r.name(), r.kind(), r.bearingDeg(), r.radialFrom(), r.radialTo()));
            }
        }
        if (facilities != null && !facilities.isEmpty()) {
            sb.append("公共设施：");
            for (Facility f : facilities) {
                sb.append(f.name()).append('（').append(f.role()).append("）、");
            }
            sb.setLength(sb.length() - 1);
            sb.append('\n');
        }
        sb.append("建筑预算：住宅 ").append(dwellingTarget())
                .append(" / 附属房 ").append(outbuildingTarget())
                .append(" / 公共 ").append(facilityTarget())
                .append(" / 商铺 ").append(shopTarget())
                .append(" / 工坊 ").append(workshopTarget())
                .append(" / 空置 ").append(VACANT_HOUSES)
                .append(" = ").append(buildingBudget()).append('\n');
        return sb.toString();
    }

    /**
     * 落库形态（存进 {@code actor_world_map.params_json} 的 {@code brief} 节点，供复现/调试）。
     *
     * @return 可被 Jackson 序列化的 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("terrainFamily", terrainFamily);
        m.put("aiGenerated", aiGenerated);
        m.put("demography", demography == null ? null : Map.of(
                "households", demography.households(), "householdSize", demography.householdSize(),
                "residents", demography.residents()));
        m.put("settlement", settlement == null ? null : Map.of(
                "centerBearingDeg", settlement.centerBearingDeg(),
                "radiusM", settlement.radiusM(), "slopeMax", settlement.slopeMax()));
        List<Map<String, Object>> rs = new ArrayList<>();
        if (regions != null) {
            for (Region r : regions) {
                rs.add(Map.of("code", r.code(), "name", r.name(), "kind", r.kind(),
                        "bearingDeg", r.bearingDeg(), "radialFrom", r.radialFrom(),
                        "radialTo", r.radialTo(), "elevationBias", r.elevationBias(),
                        "treeDensity", r.treeDensity(), "moistureBias", r.moistureBias()));
            }
        }
        m.put("regions", rs);
        List<Map<String, Object>> fs = new ArrayList<>();
        if (facilities != null) {
            for (Facility f : facilities) {
                fs.add(Map.of("code", f.code(), "name", f.name(), "kind", f.kind(),
                        "anchorLocation", f.anchorLocation() == null ? "" : f.anchorLocation(),
                        "role", f.role(), "frontageM", f.frontageM(), "depthM", f.depthM(),
                        "floors", f.floors()));
            }
        }
        m.put("facilities", fs);
        m.put("dwellings", dwellings == null ? List.of() : dwellings);
        m.put("budget", Map.of("dwellings", dwellingTarget(), "outbuildings", outbuildingTarget(),
                "facilities", facilityTarget(), "shops", shopTarget(),
                "workshops", workshopTarget(), "total", buildingBudget()));
        return m;
    }
}
