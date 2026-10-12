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

package com.holzyn.actor.domain.worldmap.service;

import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.worldmap.config.WorldMapProperties;
import com.holzyn.actor.domain.worldmap.model.MapCharacter;
import com.holzyn.actor.domain.worldmap.model.WorldMapHome;
import com.holzyn.actor.domain.worldmap.model.WorldMapHomeMember;
import com.holzyn.actor.domain.worldmap.model.WorldMapLayout;
import com.holzyn.actor.domain.worldmap.model.WorldMapPlacement;
import com.holzyn.actor.domain.worldmap.model.WorldMapRegion;
import com.holzyn.actor.domain.worldmap.model.WorldMapRoad;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

/**
 * 世界地图生成 · 机制兜底生成器（AI 失败 / 未装配 / 校验失败时使用，零 AI）。
 * <p><b>现实聚落分布规律</b>（用户需求）：团状聚落——公共核心（祠堂/集市/水井/茶馆）居中；
 * 民居环带围绕核心；商业服务沿主路两侧近核心；生产用地（农田/梯田/林）外围一侧；
 * 村口地标（牌坊/渡口/山神庙）在村边；临水布置；主街贯穿 + 支路连接民居群。</p>
 * <p><b>同居共宅</b>：按角色亲密关系（配偶/情侣/亲子/兄弟姐妹/亲属）连通分量分组——
 * 分量内角色同住一间住所，户主=分量内地位最高者；孤立角色独住。</p>
 * <p>确定性：角度/归属由名称哈希派生（同输入同输出）；抖动用固定 seed 的 Random。</p>
 */
@Slf4j
@Component
public class WorldMapMechanismGenerator {

    /** 类型分档（环带比例 0=村心，1=村边） */
    private static final double BAND_CORE = 0.05;
    private static final double BAND_COMMERCIAL = 0.18;
    private static final double BAND_RESIDENTIAL = 0.32;
    private static final double BAND_CULTURAL = 0.62;
    private static final double BAND_PRODUCTION = 0.72;
    private static final double BAND_WATER = 0.80;
    private static final double BAND_GATE = 0.86;
    private static final double BAND_DEFAULT = 0.40;

    /** 各档半径（格=米，按 size/500 缩放后钳制） */
    private static final double R_CORE = 16;
    private static final double R_COMMERCIAL = 12;
    private static final double R_RESIDENTIAL = 20;
    private static final double R_CULTURAL = 13;
    private static final double R_PRODUCTION = 24;
    private static final double R_WATER = 12;
    private static final double R_GATE = 10;
    private static final double R_DEFAULT = 14;

    /** 亲密关系关键词（同居共宅：命中 → 同住一间住所） */
    private static final Set<String> COHABIT_TYPES = Set.of(
            "夫妻", "伴侣", "配偶", "情侣", "道侣", "爱侣",
            "父子", "父女", "母子", "母女", "子女", "父母", "一家", "家人", "家属",
            "兄弟", "姐妹", "兄妹", "姐弟", "姊妹", "手足", "亲属", "亲戚",
            "祖孙", "婆媳", "翁婿", "叔侄", "舅甥", "姑侄", "姨甥");

    /** 配偶类关系（role 派生） */
    private static final Set<String> SPOUSE_TYPES = Set.of("夫妻", "伴侣", "配偶", "情侣", "道侣", "爱侣", "妻", "夫");

    /** 子女类关系 */
    private static final Set<String> CHILD_TYPES = Set.of("父子", "父女", "母子", "母女", "子女");

    /** 父母类关系 */
    private static final Set<String> PARENT_TYPES = Set.of("父母", "母子", "母女", "父子", "父女", "翁婿", "婆媳");

    /** 河流缺省宽度（避让用） */
    private static final double DEFAULT_RIVER_WIDTH = 8.0;

    /** 住所离道路/河流中线的安全间距（格=米） */
    private static final double CLEARANCE = 4.0;

    // ==================== 主入口 ====================

    /**
     * 生成布局（确定性；零 AI）。
     *
     * @param locations 地点列表（有序）
     *
     * @param characters 角色列表（特殊 + 普通）
     *
     * @param relations 角色关系（同居分组用，可为空）
     *
     * @param sceneSize 场景边长
     *
     * @param seed 随机种子（可复现）
     *
     * @param style 风格（回填 layout.style）
     *
     * @param props 配置
     *
     * @param riverPolylines 河流折线（既有布局保留，可为空；每条为 [[x,z],…]）
     *
     * @return 布局（source=mechanism；由构造保证通过 {@link WorldMapValidator} 的空间规则）
     */
    public WorldMapLayout generate(List<ActorWorldLocation> locations, List<MapCharacter> characters,
                                   List<ActorCharacterRelation> relations, int sceneSize, long seed,
                                   String style, WorldMapProperties props, List<List<double[]>> riverPolylines) {
        WorldMapLayout layout = new WorldMapLayout();
        layout.setSceneSize(sceneSize);
        layout.setStyle(style == null || style.isBlank() ? "village" : style);
        layout.setSeed(seed);
        layout.setSource("mechanism");
        layout.setMapName("世界地图（机制生成）");
        if (locations == null || locations.isEmpty()) {
            return layout;
        }

        double margin = props.getMargin();
        double gap = props.getRegionGap();
        double scale = Math.min(1.0, sceneSize / 500.0);
        double cx = sceneSize / 2.0;
        double cz = sceneSize / 2.0;
        Random rnd = new Random(seed);

        // ==================== ① 地点分档 + 定位 ====================
        Map<Long, WorldMapRegion> regionByLoc = new LinkedHashMap<>();
        List<WorldMapRegion> coreRegions = new ArrayList<>();
        List<WorldMapRegion> commercialRegions = new ArrayList<>();
        for (ActorWorldLocation loc : locations) {
            WorldMapRegion r = new WorldMapRegion();
            r.setLocationId(loc.getId());
            r.setName(loc.getName());
            Band band = classify(loc);
            r.setBand(band.band);
            r.setRadius(clampRadius(band.radius * scale, props));
            double angle = angleOf(loc.getName(), rnd);
            if (band == Band.GATE) {
                boolean south = loc.getName() == null || loc.getName().contains("牌坊") || loc.getName().contains("门");
                double z = south ? sceneSize - margin : margin;
                double x = cx + (rnd.nextDouble() - 0.5) * 30 * scale;
                r.setCenterX(clamp(x, margin, sceneSize - margin));
                r.setCenterZ(clamp(z, margin, sceneSize - margin));
            } else if (band == Band.COMMERCIAL || band == Band.CORE) {
                int axisIdx = ((int) (angle / (Math.PI / 2))) % 4;
                double axis = axisIdx * Math.PI / 2;
                double a = axis + (rnd.nextDouble() - 0.5) * 0.6;
                double dist = (sceneSize / 2.0 - margin) * band.band;
                r.setCenterX(clamp(cx + Math.cos(a) * dist, margin, sceneSize - margin));
                r.setCenterZ(clamp(cz + Math.sin(a) * dist, margin, sceneSize - margin));
            } else {
                double dist = (sceneSize / 2.0 - margin) * band.band;
                r.setCenterX(clamp(cx + Math.cos(angle) * dist, margin, sceneSize - margin));
                r.setCenterZ(clamp(cz + Math.sin(angle) * dist, margin, sceneSize - margin));
            }
            r.setRegionDesc(regionDesc(loc, band));
            layout.getRegions().add(r);
            regionByLoc.put(loc.getId(), r);
            if (band == Band.CORE) {
                coreRegions.add(r);
            } else if (band == Band.COMMERCIAL) {
                commercialRegions.add(r);
            }
        }

        // ② 重叠消解：往外推 + 半径收缩兜底（保证不重叠）
        resolveOverlaps(layout.getRegions(), margin, sceneSize, cx, cz, gap, props);

        // ==================== ③ 道路（主街贯穿 + 横街 + 支路） ====================
        List<WorldMapRoad> roads = new ArrayList<>();
        WorldMapRoad main = new WorldMapRoad();
        main.setName("村中主道");
        main.setWidth(5.0);
        main.getPath().add(new double[]{cx, margin});
        main.getPath().add(new double[]{cx, sceneSize - margin});
        roads.add(main);
        if (!commercialRegions.isEmpty() || !coreRegions.isEmpty()) {
            WorldMapRoad cross = new WorldMapRoad();
            cross.setName("村中横街");
            cross.setWidth(4.0);
            cross.getPath().add(new double[]{margin, cz});
            cross.getPath().add(new double[]{sceneSize - margin, cz});
            roads.add(cross);
        }
        List<WorldMapRegion> branchSources = new ArrayList<>(commercialRegions);
        branchSources.addAll(coreRegions);
        int branchCount = 0;
        for (WorldMapRegion r : branchSources) {
            if (branchCount >= 4) {
                break;
            }
            double dx = r.getCenterX() - cx;
            double dz = r.getCenterZ() - cz;
            if (Math.hypot(dx, dz) < 25 * scale) {
                continue;
            }
            WorldMapRoad branch = new WorldMapRoad();
            branch.setName(r.getName() + "支路");
            branch.setWidth(2.5);
            if (Math.abs(dx) <= Math.abs(dz)) {
                branch.getPath().add(new double[]{cx, r.getCenterZ()});
                branch.getPath().add(new double[]{r.getCenterX(), r.getCenterZ()});
            } else {
                branch.getPath().add(new double[]{r.getCenterX(), cz});
                branch.getPath().add(new double[]{r.getCenterX(), r.getCenterZ()});
            }
            roads.add(branch);
            branchCount++;
        }
        layout.setRoads(roads);

        // ==================== ④ 建筑摆放 ====================
        for (WorldMapRegion r : layout.getRegions()) {
            layout.getPlacements().add(makePlacement(r, "main", rnd));
            if (r.getBand() == BAND_RESIDENTIAL) {
                layout.getPlacements().add(makePlacement(r, "annex", rnd));
            }
        }

        // ==================== ⑤ 住所（同居共宅 + 避让河/路） ====================
        buildHomes(layout, characters, relations, regionByLoc, sceneSize, props, roads, riverPolylines, rnd);
        return layout;
    }

    // ==================== 住所生成（同居共宅） ====================

    private void buildHomes(WorldMapLayout layout, List<MapCharacter> characters,
                            List<ActorCharacterRelation> relations, Map<Long, WorldMapRegion> regionByLoc,
                            int sceneSize, WorldMapProperties props, List<WorldMapRoad> roads,
                            List<List<double[]>> riverPolylines, Random rnd) {
        if (characters == null || characters.isEmpty()) {
            return;
        }
        List<List<MapCharacter>> households = groupHouseholds(characters, relations);
        List<WorldMapRegion> regions = layout.getRegions();
        if (regions.isEmpty()) {
            return;
        }
        // 现实聚落规律：民居沿「核心外围的环带」分布（而非全挤进单一民居区域）。
        // 每户在环带上取锚点角度（户主所在民居区域方向优先 + 黄金角错开保证均匀），
        // 落点避让道路/河流/已用住所并满足间距。
        double margin = props.getMargin();
        double cx = sceneSize / 2.0;
        double cz = sceneSize / 2.0;
        double ringR = (sceneSize / 2.0 - margin) * BAND_RESIDENTIAL;
        double homeMin = props.getHomeMinDistance();
        double phase = rnd.nextDouble() * Math.PI * 2;
        List<double[]> used = new ArrayList<>();
        int homeSeq = 0;
        for (List<MapCharacter> household : households) {
            MapCharacter owner = pickOwner(household);
            // 环带锚点角度：户主所在区域方向优先，否则统一相位
            double anchorAngle;
            WorldMapRegion ownerRegion = null;
            if (owner.getLocationId() != null) {
                ownerRegion = regionByLoc.get(owner.getLocationId());
            }
            anchorAngle = ownerRegion != null
                    ? Math.atan2(ownerRegion.getCenterZ() - cz, ownerRegion.getCenterX() - cx)
                    : phase;
            double baseAngle = anchorAngle + 2 * Math.PI * homeSeq * 0.6180339887;
            double[] pos = findHomePos(baseAngle, owner.getRef(), used, homeMin, ringR, props, sceneSize,
                    roads, riverPolylines, rnd);
            used.add(pos);
            // 住所归属：落点最近的区域（locationId 校验只要求存在，可复用）
            WorldMapRegion belong = nearestRegion(regions, pos[0], pos[1]);

            WorldMapHome home = new WorldMapHome();
            home.setName(homeName(owner));
            home.setLocationId(belong.getLocationId());
            home.setPosX(pos[0]);
            home.setPosZ(pos[1]);
            home.setRoomLabel("正房");
            home.setRegion(belong.getRegionDesc() == null ? "村中" : belong.getRegionDesc());
            WorldMapHomeMember ownerMember = new WorldMapHomeMember();
            ownerMember.setCharacterRef(owner.getRef());
            ownerMember.setRole("户主");
            ownerMember.setRoomLabel("正房东间");
            ownerMember.setOwner(true);
            home.getMembers().add(ownerMember);
            for (MapCharacter m : household) {
                if (m.getRef().equals(owner.getRef())) {
                    continue;
                }
                WorldMapHomeMember mm = new WorldMapHomeMember();
                mm.setCharacterRef(m.getRef());
                mm.setRole(roleOf(owner, m, relations));
                mm.setRoomLabel(roomOf(owner, m, relations));
                mm.setOwner(false);
                home.getMembers().add(mm);
            }
            layout.getHomes().add(home);
            homeSeq++;
        }
    }

    /** 距离 (x,z) 最近的区域（住所归属）。 */
    private WorldMapRegion nearestRegion(List<WorldMapRegion> regions, double x, double z) {
        WorldMapRegion best = regions.get(0);
        double bestD = Double.MAX_VALUE;
        for (WorldMapRegion r : regions) {
            double d = Math.hypot(r.getCenterX() - x, r.getCenterZ() - z);
            if (d < bestD) {
                bestD = d;
                best = r;
            }
        }
        return best;
    }

    /** 亲密关系连通分量分组（同居共宅）。 */
    List<List<MapCharacter>> groupHouseholds(List<MapCharacter> characters, List<ActorCharacterRelation> relations) {
        Map<String, MapCharacter> byRef = new HashMap<>();
        for (MapCharacter c : characters) {
            byRef.put(c.getRef(), c);
        }
        Map<String, String> parent = new HashMap<>();
        for (String ref : byRef.keySet()) {
            parent.put(ref, ref);
        }
        Function<String, String> find = new Function<>() {
            @Override
            public String apply(String s) {
                String p = parent.get(s);
                if (p == null) {
                    return s;
                }
                if (!p.equals(s)) {
                    parent.put(s, apply(p));
                }
                return parent.get(s);
            }
        };
        if (relations != null) {
            for (ActorCharacterRelation rel : relations) {
                String a = rel.getFromRef();
                String b = rel.getToRef();
                if (a == null || b == null) {
                    continue;
                }
                if (!byRef.containsKey(a) || !byRef.containsKey(b) || !isCohabitation(rel.getRelationType())) {
                    continue;
                }
                String ra = find.apply(a);
                String rb = find.apply(b);
                if (!ra.equals(rb)) {
                    parent.put(ra, rb);
                }
            }
        }
        Map<String, List<MapCharacter>> groups = new LinkedHashMap<>();
        for (String ref : byRef.keySet()) {
            groups.computeIfAbsent(find.apply(ref), k -> new ArrayList<>()).add(byRef.get(ref));
        }
        return new ArrayList<>(groups.values());
    }

    /** 户主选定：分量内 ownerCandidate（特殊/长者）优先，其次重要度最高，其次 id 最小。 */
    private MapCharacter pickOwner(List<MapCharacter> household) {
        MapCharacter best = household.get(0);
        for (MapCharacter c : household) {
            if (ownerScore(c) > ownerScore(best)) {
                best = c;
            }
        }
        return best;
    }

    private int ownerScore(MapCharacter c) {
        int score = 0;
        if (c.isOwnerCandidate()) {
            score += 100;
        }
        score += c.getImportance() == null ? 1 : c.getImportance();
        return score;
    }

    /** 家中身份（以户主为参照的关系派生）。 */
    private String roleOf(MapCharacter owner, MapCharacter member, List<ActorCharacterRelation> relations) {
        String relType = relationTypeBetween(owner, member, relations);
        if (relType != null) {
            if (SPOUSE_TYPES.stream().anyMatch(relType::contains)) {
                return "配偶";
            }
            if (CHILD_TYPES.stream().anyMatch(relType::contains)) {
                return "子女";
            }
            if (PARENT_TYPES.stream().anyMatch(relType::contains)) {
                return "父母";
            }
        }
        return "亲属";
    }

    /** 个人房间。 */
    private String roomOf(MapCharacter owner, MapCharacter member, List<ActorCharacterRelation> relations) {
        return switch (roleOf(owner, member, relations)) {
            case "配偶" -> "东厢";
            case "子女" -> "西厢";
            case "父母" -> "东厢里间";
            default -> "偏房";
        };
    }

    /** 取两角色间的亲密关系类型（任一向）。 */
    private String relationTypeBetween(MapCharacter a, MapCharacter b, List<ActorCharacterRelation> relations) {
        if (relations == null) {
            return null;
        }
        for (ActorCharacterRelation rel : relations) {
            boolean fwd = a.getRef().equals(rel.getFromRef()) && b.getRef().equals(rel.getToRef());
            boolean rev = b.getRef().equals(rel.getFromRef()) && a.getRef().equals(rel.getToRef());
            if (fwd || rev) {
                return rel.getRelationType();
            }
        }
        return null;
    }

    /** 家名（户主姓氏 + 宅/家）。 */
    private String homeName(MapCharacter owner) {
        String name = owner.getName();
        if (name == null || name.isBlank()) {
            name = "无名氏";
        }
        String first = name.substring(0, 1);
        if (owner.isOwnerCandidate() && name.length() >= 2) {
            return first + "家大宅";
        }
        return name + "家";
    }

    /** 在民居环带上找住所坐标：角度微扰 + 半径微调扫描，避让已用点/道路/河流 + 间距约束。 */
    private double[] findHomePos(double baseAngle, String ref, List<double[]> used, double homeMin,
                                 double ringR, WorldMapProperties props, int sceneSize,
                                 List<WorldMapRoad> roads, List<List<double[]>> riverPolylines, Random rnd) {
        double margin = props.getMargin();
        double cx = sceneSize / 2.0;
        double cz = sceneSize / 2.0;
        // 确定性 ±0.5 rad 微扰（角色引用哈希派生）
        double jitter = ref == null ? 0 : (double) (ref.hashCode() & 0xFF) / 0xFF - 0.5;
        // 主扫描：角度微扰 + 半径微调，优先满足间距
        for (int attempt = 0; attempt < 24; attempt++) {
            double a = baseAngle + jitter * (attempt + 1);
            double r = ringR * (1.0 + ((attempt / 6) % 3) * 0.10 - 0.10);
            double x = clamp(cx + Math.cos(a) * r, margin, sceneSize - margin);
            double z = clamp(cz + Math.sin(a) * r, margin, sceneSize - margin);
            if (!placeOk(x, z, used, homeMin, roads, riverPolylines)) {
                continue;
            }
            return new double[]{x, z};
        }
        // 兜底：环带均匀扫角（只避路河，间距放宽），保证不落路
        for (int attempt = 0; attempt < 24; attempt++) {
            double a = baseAngle + attempt * (Math.PI * 2 / 24);
            double x = clamp(cx + Math.cos(a) * ringR, margin, sceneSize - margin);
            double z = clamp(cz + Math.sin(a) * ringR, margin, sceneSize - margin);
            boolean onRoad = false;
            for (WorldMapRoad road : roads) {
                if (WorldMapValidator.pointOnPolyline(x, z, road.getPath(), road.getWidth() / 2 + CLEARANCE)) {
                    onRoad = true;
                    break;
                }
            }
            if (onRoad) {
                continue;
            }
            boolean onRiver = false;
            if (riverPolylines != null) {
                for (List<double[]> river : riverPolylines) {
                    if (WorldMapValidator.pointOnPolyline(x, z, river, DEFAULT_RIVER_WIDTH / 2 + CLEARANCE)) {
                        onRiver = true;
                        break;
                    }
                }
            }
            if (onRiver) {
                continue;
            }
            return new double[]{x, z};
        }
        // 终极兜底：村心偏侧（半径减半），保证有返回值
        double x = clamp(cx + Math.cos(baseAngle) * ringR * 0.5, margin, sceneSize - margin);
        double z = clamp(cz + Math.sin(baseAngle) * ringR * 0.5, margin, sceneSize - margin);
        return new double[]{x, z};
    }

    private boolean placeOk(double x, double z, List<double[]> used, double homeMin,
                            List<WorldMapRoad> roads, List<List<double[]>> riverPolylines) {
        for (double[] u : used) {
            if (Math.hypot(u[0] - x, u[1] - z) < homeMin) {
                return false;
            }
        }
        for (WorldMapRoad road : roads) {
            if (WorldMapValidator.pointOnPolyline(x, z, road.getPath(), road.getWidth() / 2 + CLEARANCE)) {
                return false;
            }
        }
        if (riverPolylines != null) {
            for (List<double[]> river : riverPolylines) {
                if (WorldMapValidator.pointOnPolyline(x, z, river, DEFAULT_RIVER_WIDTH / 2 + CLEARANCE)) {
                    return false;
                }
            }
        }
        return true;
    }

    // ==================== 内部工具 ====================

    private WorldMapPlacement makePlacement(WorldMapRegion r, String kind, Random rnd) {
        WorldMapPlacement p = new WorldMapPlacement();
        if ("annex".equals(kind)) {
            p.setName(r.getName() + "民居");
        } else {
            p.setName(r.getName());
        }
        p.setCategory(categoryOf(r.getName()));
        p.setPosX(r.getCenterX() + (rnd.nextDouble() - 0.5) * r.getRadius() * 0.6);
        p.setPosZ(r.getCenterZ() + (rnd.nextDouble() - 0.5) * r.getRadius() * 0.6);
        p.setRotY(rnd.nextDouble() * Math.PI * 2);
        p.setLocationId(r.getLocationId());
        return p;
    }

    /** 地点分档。 */
    private Band classify(ActorWorldLocation loc) {
        String s = (loc.getName() == null ? "" : loc.getName()) + (loc.getType() == null ? "" : loc.getType());
        if (containsAny(s, "渡口", "埠", "码头", "桥", "河")) {
            return Band.WATER;
        }
        if (containsAny(s, "牌坊", "寨门", "村门", "门楼")) {
            return Band.GATE;
        }
        if (containsAny(s, "田", "地", "林", "坡", "塘", "渔", "场", "园", "磨")) {
            return Band.PRODUCTION;
        }
        if (containsAny(s, "庙", "观", "寺", "塔", "书院", "学堂", "塾", "祠")) {
            return Band.CULTURAL;
        }
        if (containsAny(s, "祠堂", "集市", "市集", "广场", "水井", "老井", "亭", "茶馆", "酒馆", "酒楼")) {
            return Band.CORE;
        }
        if (containsAny(s, "医", "药", "铁匠", "铺", "店", "摊", "粮", "布", "当", "客栈")) {
            return Band.COMMERCIAL;
        }
        if (containsAny(s, "居", "宅", "家", "屋", "院", "巷", "坊", "街")) {
            return Band.RESIDENTIAL;
        }
        return Band.DEFAULT;
    }

    /** 区域口语描述（感知/对话素材）。 */
    private String regionDesc(ActorWorldLocation loc, Band band) {
        String name = loc.getName() == null ? "" : loc.getName();
        String intro = loc.getIntro() == null ? "" : loc.getIntro().trim();
        String base = intro.isEmpty() ? name + "一带" : intro;
        if (base.length() > 60) {
            base = base.substring(0, 60);
        }
        String region = switch (band) {
            case CORE -> "村中心";
            case COMMERCIAL -> "村中沿街";
            case RESIDENTIAL -> "村内民居区";
            case CULTURAL -> "村边高地";
            case PRODUCTION -> "村外";
            case WATER -> "河畔";
            case GATE -> "村口";
            default -> "村内";
        };
        return region + "，" + base;
    }

    /** 摆放类别（渲染分类）。 */
    private String categoryOf(String name) {
        String s = name == null ? "" : name;
        if (containsAny(s, "医", "药")) {
            return "clinic";
        }
        if (containsAny(s, "铺", "店", "摊", "粮", "布", "当")) {
            return "store";
        }
        if (containsAny(s, "祠", "庙", "观", "寺", "塔")) {
            return "temple";
        }
        if (containsAny(s, "田", "地", "林", "坡", "塘", "园")) {
            return "field";
        }
        if (containsAny(s, "牌坊", "门", "渡", "桥")) {
            return "landmark";
        }
        if (containsAny(s, "居", "宅", "家", "屋", "院", "巷", "坊", "街")) {
            return "residence";
        }
        return "building";
    }

    /** 角度（名称哈希确定性派生）。 */
    private double angleOf(String name, Random rnd) {
        if (name == null || name.isBlank()) {
            return rnd.nextDouble() * Math.PI * 2;
        }
        return (double) (name.hashCode() & Integer.MAX_VALUE) / Integer.MAX_VALUE * Math.PI * 2;
    }

    /** 是否同居关系。 */
    private boolean isCohabitation(String relationType) {
        if (relationType == null || relationType.isBlank()) {
            return false;
        }
        for (String kw : COHABIT_TYPES) {
            if (relationType.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /** 重叠消解：往外推 + 半径收缩兜底（保证最终不重叠）。 */
    private void resolveOverlaps(List<WorldMapRegion> regions, double margin, int sceneSize,
                                 double cx, double cz, double gap, WorldMapProperties props) {
        boolean changed = true;
        int rounds = 0;
        while (changed && rounds++ < 24) {
            changed = false;
            for (int i = 0; i < regions.size(); i++) {
                for (int j = i + 1; j < regions.size(); j++) {
                    WorldMapRegion a = regions.get(i);
                    WorldMapRegion b = regions.get(j);
                    if (!WorldMapValidator.regionsOverlap(a, b, gap)) {
                        continue;
                    }
                    double dx = b.getCenterX() - cx;
                    double dz = b.getCenterZ() - cz;
                    double len = Math.hypot(dx, dz);
                    if (len < 1) {
                        len = 1;
                        dx = 1;
                        dz = 0;
                    }
                    double cur = Math.hypot(a.getCenterX() - b.getCenterX(), a.getCenterZ() - b.getCenterZ());
                    double push = (a.getRadius() + b.getRadius() + gap) - cur + 2;
                    double nx = b.getCenterX() + dx / len * push;
                    double nz = b.getCenterZ() + dz / len * push;
                    if (nx > margin && nx < sceneSize - margin && nz > margin && nz < sceneSize - margin) {
                        b.setCenterX(nx);
                        b.setCenterZ(nz);
                        changed = true;
                    }
                }
            }
        }
        // 半径收缩兜底：仍重叠 → 收缩 b 半径（≥ 最小半径）
        for (int i = 0; i < regions.size(); i++) {
            for (int j = i + 1; j < regions.size(); j++) {
                WorldMapRegion a = regions.get(i);
                WorldMapRegion b = regions.get(j);
                if (!WorldMapValidator.regionsOverlap(a, b, gap)) {
                    continue;
                }
                double d = Math.hypot(a.getCenterX() - b.getCenterX(), a.getCenterZ() - b.getCenterZ());
                double maxB = d - gap - a.getRadius();
                b.setRadius(Math.max(props.getMinRegionRadius(), Math.min(b.getRadius(), maxB)));
            }
        }
    }

    private boolean containsAny(String s, String... keywords) {
        for (String kw : keywords) {
            if (s.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private double clampRadius(double r, WorldMapProperties props) {
        return Math.max(props.getMinRegionRadius(), Math.min(props.getMaxRegionRadius(), r));
    }

    /** 地点分档枚举。 */
    private enum Band {
        CORE(BAND_CORE, R_CORE),
        COMMERCIAL(BAND_COMMERCIAL, R_COMMERCIAL),
        RESIDENTIAL(BAND_RESIDENTIAL, R_RESIDENTIAL),
        CULTURAL(BAND_CULTURAL, R_CULTURAL),
        PRODUCTION(BAND_PRODUCTION, R_PRODUCTION),
        WATER(BAND_WATER, R_WATER),
        GATE(BAND_GATE, R_GATE),
        DEFAULT(BAND_DEFAULT, R_DEFAULT);

        final double band;
        final double radius;

        Band(double band, double radius) {
            this.band = band;
            this.radius = radius;
        }
    }
}
