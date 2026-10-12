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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 世界地图生成 · 规则校验器（仿 WorldFrameValidator「AI 主通道 + 机制兜底」范式）。
 * <p>职责：校验布局的<b>空间合法性</b>与<b>引用完整性</b>——坐标界内、区域不重叠、
 * 住所不落河/路、住所两两不重叠、每角色恰在一间住所的 members 中、locationId/characterRef
 * 引用全部存在。任一硬性规则失败 → 整批回退机制兜底（世界不中断）。</p>
 * <p>几何工具（{@link #distToSegment} / {@link #pointOnPolyline} / {@link #regionsOverlap}）
 * 同时被机制兜底生成器复用，保证兜底输出天然通过本校验器。</p>
 */
@Slf4j
@Component
public class WorldMapValidator {

    /** 校验结果。 */
    public record Result(boolean valid, List<String> issues) {
        static Result ok() {
            return new Result(true, List.of());
        }

        static Result fail(List<String> issues) {
            return new Result(false, issues);
        }
    }

    /** 河流宽度缺省（格=米；既有布局未给 width 时用） */
    private static final double DEFAULT_RIVER_WIDTH = 8.0;

    /** 住所/摆放离道路中线的安全间距（格=米）：路宽/2 + 本值 */
    private static final double ROAD_CLEARANCE = 3.0;

    /** 住所/摆放离河流中线的安全间距（格=米） */
    private static final double RIVER_CLEARANCE = 3.0;

    /**
     * 校验布局（空间合法性 + 引用完整性）。
     *
     * @param layout 布局（AI 输出或机制兜底）
     *
     * @param locations 地点表（locationId → 实体）
     *
     * @param characters 角色表（characterRef → 角色）
     *
     * @param riverPolylines 河流折线（既有布局保留，可为空；每条为 [[x,z],…]）
     *
     * @param props 配置（半径/间距/留白约束）
     *
     * @param sceneSize 场景边长
     *
     * @return 校验结果（valid=false 时 issues 给出全部原因）
     */
    public Result validate(WorldMapLayout layout, Map<Long, ?> locations, Map<String, MapCharacter> characters,
                           List<List<double[]>> riverPolylines, WorldMapProperties props, int sceneSize) {
        if (layout == null) {
            return Result.fail(List.of("布局为空"));
        }
        List<String> issues = new ArrayList<>();
        double margin = props.getMargin();
        double minR = props.getMinRegionRadius();
        double maxR = props.getMaxRegionRadius();
        double gap = props.getRegionGap();
        double homeMin = props.getHomeMinDistance();

        // ==================== 区域 ====================
        if (layout.getRegions().isEmpty()) {
            issues.add("regions 为空");
        }
        Set<Long> seenLoc = new HashSet<>();
        for (WorldMapRegion r : layout.getRegions()) {
            if (r.getLocationId() == null) {
                issues.add("区域缺少 locationId：" + r.getName());
                continue;
            }
            if (!locations.containsKey(r.getLocationId())) {
                issues.add("区域 locationId 不存在：" + r.getLocationId() + "（" + r.getName() + "）");
                continue;
            }
            if (!seenLoc.add(r.getLocationId())) {
                issues.add("区域 locationId 重复：" + r.getLocationId());
            }
            if (r.getCenterX() < 0 || r.getCenterX() > sceneSize || r.getCenterZ() < 0 || r.getCenterZ() > sceneSize) {
                issues.add("区域坐标越界：" + r.getName() + " (" + r.getCenterX() + "," + r.getCenterZ() + ")");
            }
            if (r.getRadius() < minR || r.getRadius() > maxR) {
                issues.add("区域半径非法：" + r.getName() + " radius=" + r.getRadius());
            }
        }
        List<WorldMapRegion> regions = layout.getRegions();
        for (int i = 0; i < regions.size(); i++) {
            for (int j = i + 1; j < regions.size(); j++) {
                WorldMapRegion a = regions.get(i);
                WorldMapRegion b = regions.get(j);
                if (regionsOverlap(a, b, gap)) {
                    issues.add("区域重叠：" + a.getName() + " ↔ " + b.getName());
                }
            }
        }

        // ==================== 道路 ====================
        List<WorldMapRoad> roads = new ArrayList<>();
        for (WorldMapRoad road : layout.getRoads()) {
            boolean bad = false;
            for (double[] pt : road.getPath()) {
                if (pt[0] < 0 || pt[0] > sceneSize || pt[1] < 0 || pt[1] > sceneSize) {
                    bad = true;
                    break;
                }
            }
            if (bad || road.getWidth() <= 0) {
                issues.add("道路非法（越界/宽度≤0）：" + road.getName());
                continue;
            }
            roads.add(road);
        }

        // ==================== 摆放 ====================
        List<WorldMapPlacement> placements = new ArrayList<>();
        for (WorldMapPlacement p : layout.getPlacements()) {
            if (p.getPosX() < 0 || p.getPosX() > sceneSize || p.getPosZ() < 0 || p.getPosZ() > sceneSize) {
                continue; // 软性：越界摆放直接丢弃
            }
            if (p.getLocationId() != null && !locations.containsKey(p.getLocationId())) {
                continue; // 软性：locationId 无效丢弃
            }
            placements.add(p);
        }
        layout.setPlacements(placements);

        // ==================== 住所（同居共宅） ====================
        Set<String> covered = new HashSet<>();
        List<WorldMapHome> homes = new ArrayList<>();
        for (WorldMapHome h : layout.getHomes()) {
            if (h.getLocationId() != null && !locations.containsKey(h.getLocationId())) {
                issues.add("住所 locationId 不存在：" + h.getName());
                continue;
            }
            boolean badCoord = h.getPosX() < 0 || h.getPosX() > sceneSize
                    || h.getPosZ() < 0 || h.getPosZ() > sceneSize;
            if (badCoord) {
                issues.add("住所坐标越界：" + h.getName());
                continue;
            }
            // 住所不落河
            if (riverPolylines != null) {
                for (List<double[]> river : riverPolylines) {
                    if (pointOnPolyline(h.getPosX(), h.getPosZ(), river, DEFAULT_RIVER_WIDTH / 2 + RIVER_CLEARANCE)) {
                        issues.add("住所落在河上：" + h.getName());
                    }
                }
            }
            // 住所不落路
            for (WorldMapRoad road : roads) {
                if (pointOnPolyline(h.getPosX(), h.getPosZ(), road.getPath(), road.getWidth() / 2 + ROAD_CLEARANCE)) {
                    issues.add("住所落在路上：" + h.getName());
                }
            }
            // 住户引用
            if (h.getMembers().isEmpty()) {
                issues.add("住所无住户：" + h.getName());
            }
            for (WorldMapHomeMember m : h.getMembers()) {
                if (!characters.containsKey(m.getCharacterRef())) {
                    issues.add("住户 characterRef 不存在：" + m.getCharacterRef());
                }
                if (!covered.add(m.getCharacterRef())) {
                    issues.add("角色重复入住：" + m.getCharacterRef());
                }
            }
            homes.add(h);
        }
        // 每角色恰一间住所
        for (String ref : characters.keySet()) {
            if (!covered.contains(ref)) {
                issues.add("角色无住所：" + ref);
            }
        }
        layout.setHomes(homes);

        // ==================== 住所间距 ====================
        for (int i = 0; i < homes.size(); i++) {
            for (int j = i + 1; j < homes.size(); j++) {
                WorldMapHome a = homes.get(i);
                WorldMapHome b = homes.get(j);
                double dx = a.getPosX() - b.getPosX();
                double dz = a.getPosZ() - b.getPosZ();
                if (Math.sqrt(dx * dx + dz * dz) < homeMin) {
                    issues.add("住所距离过近：" + a.getName() + " ↔ " + b.getName());
                }
            }
        }

        if (issues.isEmpty()) {
            return Result.ok();
        }
        return Result.fail(issues);
    }

    // ==================== 几何工具（机制兜底生成器复用） ====================

    /**
     * AI 布局空间修复（AI 主通道专用，机制兜底不调用）：
     * 修正几何冲突——区域重叠（外推/半径收缩）、住所避路、住所间距过近——
     * 保留 AI 创意分布，仅做规则级修正。修复后仍需通过 {@link #validate}
     * （修复不完备时调用方仍回退机制兜底，世界不中断）。
     *
     * @param layout 布局（就地修改）
     *
     * @param props 配置（留白/半径/间距约束）
     *
     * @param sceneSize 场景边长
     *
     * @return 修复动作数（0=无冲突）
     */
    public int repairSpatial(WorldMapLayout layout, WorldMapProperties props, int sceneSize) {
        if (layout == null) {
            return 0;
        }
        int fixed = 0;
        double margin = props.getMargin();
        double gap = props.getRegionGap();
        double minR = props.getMinRegionRadius();
        double homeMin = props.getHomeMinDistance();
        double cx = sceneSize / 2.0;
        double cz = sceneSize / 2.0;

        // ① 区域重叠：远离村心者外推；到边界推不动 → 收缩重叠对中半径较大者
        //（大半径生产区/河畔优先让位核心区，符合现实聚落）；多轮直到稳定
        List<WorldMapRegion> regions = layout.getRegions();
        for (int round = 0; round < 24; round++) {
            boolean changed = false;
            for (int i = 0; i < regions.size(); i++) {
                for (int j = i + 1; j < regions.size(); j++) {
                    WorldMapRegion a = regions.get(i);
                    WorldMapRegion b = regions.get(j);
                    if (!regionsOverlap(a, b, gap)) {
                        continue;
                    }
                    double da = Math.hypot(a.getCenterX() - cx, a.getCenterZ() - cz);
                    double db = Math.hypot(b.getCenterX() - cx, b.getCenterZ() - cz);
                    WorldMapRegion mover = da >= db ? a : b;
                    double dx = mover.getCenterX() - cx;
                    double dz = mover.getCenterZ() - cz;
                    double len = Math.hypot(dx, dz);
                    if (len < 1e-6) {
                        dx = 1;
                        dz = 0;
                        len = 1;
                    }
                    double cur = Math.hypot(a.getCenterX() - b.getCenterX(), a.getCenterZ() - b.getCenterZ());
                    double push = (a.getRadius() + b.getRadius() + gap) - cur + 2;
                    double nx = mover.getCenterX() + dx / len * push;
                    double nz = mover.getCenterZ() + dz / len * push;
                    if (nx >= margin && nx <= sceneSize - margin && nz >= margin && nz <= sceneSize - margin) {
                        mover.setCenterX(nx);
                        mover.setCenterZ(nz);
                        fixed++;
                        changed = true;
                    } else {
                        // 到边界推不动 → 收缩重叠对中半径较大者（≥ 最小半径）
                        WorldMapRegion shrink = a.getRadius() >= b.getRadius() ? a : b;
                        WorldMapRegion otherR = shrink == a ? b : a;
                        double d = Math.hypot(a.getCenterX() - b.getCenterX(), a.getCenterZ() - b.getCenterZ());
                        double maxR = d - gap - otherR.getRadius();
                        double newR = Math.max(minR, Math.min(shrink.getRadius(), maxR));
                        if (newR < shrink.getRadius()) {
                            shrink.setRadius(newR);
                            fixed++;
                            changed = true;
                        }
                    }
                }
            }
            if (!changed) {
                break;
            }
        }

        // ② 住所避路 + 间距（多轮迭代交替收敛：单遍推开后可能二次冲突）
        List<WorldMapHome> homes = layout.getHomes();
        for (int round = 0; round < 8; round++) {
            boolean changed = false;
            for (WorldMapHome h : homes) {
                for (WorldMapRoad road : layout.getRoads()) {
                    double safe = road.getWidth() / 2 + ROAD_CLEARANCE + 1;
                    double[] proj = nearestPointOnPolyline(h.getPosX(), h.getPosZ(), road.getPath());
                    double dx = h.getPosX() - proj[0];
                    double dz = h.getPosZ() - proj[1];
                    double dist = Math.hypot(dx, dz);
                    if (dist < safe) {
                        double ux = dist < 1e-6 ? 1 : dx / dist;
                        double uz = dist < 1e-6 ? 0 : dz / dist;
                        h.setPosX(clamp(h.getPosX() + ux * (safe - dist), margin, sceneSize - margin));
                        h.setPosZ(clamp(h.getPosZ() + uz * (safe - dist), margin, sceneSize - margin));
                        fixed++;
                        changed = true;
                    }
                }
            }
            for (int i = 0; i < homes.size(); i++) {
                for (int j = i + 1; j < homes.size(); j++) {
                    WorldMapHome a = homes.get(i);
                    WorldMapHome b = homes.get(j);
                    double dx = b.getPosX() - a.getPosX();
                    double dz = b.getPosZ() - a.getPosZ();
                    double d = Math.hypot(dx, dz);
                    if (d >= homeMin) {
                        continue;
                    }
                    double ux;
                    double uz;
                    double push;
                    if (d < 1e-6) {
                        // 完全重合（如两住所避路被推到同一点）：确定性错开
                        ux = 1;
                        uz = 0;
                        push = homeMin;
                    } else {
                        ux = dx / d;
                        uz = dz / d;
                        push = homeMin - d;
                    }
                    b.setPosX(clamp(b.getPosX() + ux * push, margin, sceneSize - margin));
                    b.setPosZ(clamp(b.getPosZ() + uz * push, margin, sceneSize - margin));
                    fixed++;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        return fixed;
    }

    /** 点到折线的最近点。 */
    public static double[] nearestPointOnPolyline(double px, double pz, List<double[]> path) {
        if (path == null || path.isEmpty()) {
            return new double[]{px, pz};
        }
        if (path.size() == 1) {
            return new double[]{path.get(0)[0], path.get(0)[1]};
        }
        double bestD = Double.MAX_VALUE;
        double bx = px;
        double bz = pz;
        for (int i = 0; i < path.size() - 1; i++) {
            double[] a = path.get(i);
            double[] b = path.get(i + 1);
            double ax = a[0];
            double az = a[1];
            double ex = b[0];
            double ez = b[1];
            double dx = ex - ax;
            double dz = ez - az;
            double lenSq = dx * dx + dz * dz;
            double t = lenSq == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (pz - az) * dz) / lenSq));
            double px0 = ax + t * dx;
            double pz0 = az + t * dz;
            double d = Math.hypot(px - px0, pz - pz0);
            if (d < bestD) {
                bestD = d;
                bx = px0;
                bz = pz0;
            }
        }
        return new double[]{bx, bz};
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 点到线段的最短距离。 */
    public static double distToSegment(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double lenSq = dx * dx + dz * dz;
        if (lenSq == 0) {
            return Math.hypot(px - ax, pz - az);
        }
        double t = Math.max(0, Math.min(1, ((px - ax) * dx + (pz - az) * dz) / lenSq));
        return Math.hypot(px - (ax + t * dx), pz - (az + t * dz));
    }

    /** 点是否落在折线的「膨胀带」内（距离任意线段 < clearance）。 */
    public static boolean pointOnPolyline(double px, double pz, List<double[]> path, double clearance) {
        if (path == null || path.size() < 2) {
            return false;
        }
        for (int i = 0; i < path.size() - 1; i++) {
            double[] a = path.get(i);
            double[] b = path.get(i + 1);
            if (distToSegment(px, pz, a[0], a[1], b[0], b[1]) < clearance) {
                return true;
            }
        }
        return false;
    }

    /** 两区域是否重叠（圆心距 < r1+r2+gap）。 */
    public static boolean regionsOverlap(WorldMapRegion a, WorldMapRegion b, double gap) {
        double dx = a.getCenterX() - b.getCenterX();
        double dz = a.getCenterZ() - b.getCenterZ();
        return Math.sqrt(dx * dx + dz * dz) < (a.getRadius() + b.getRadius() + gap);
    }
}
