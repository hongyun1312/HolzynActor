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

package com.holzyn.actor.domain.mapnav.service;

import com.holzyn.actor.domain.mapgen.entity.ActorMapFeature;
import com.holzyn.actor.domain.mapgen.entity.ActorMapRoad;
import com.holzyn.actor.domain.mapgen.entity.ActorWorldMap;
import com.holzyn.actor.domain.mapgen.repository.ActorMapFeatureRepository;
import com.holzyn.actor.domain.mapgen.repository.ActorMapRoadRepository;
import com.holzyn.actor.domain.mapgen.repository.ActorWorldMapRepository;
import com.holzyn.actor.domain.mapgen.service.MapArtifactStore;
import com.holzyn.actor.domain.mapnav.algo.CostField;
import com.holzyn.actor.domain.mapnav.algo.GridAStar;
import com.holzyn.actor.domain.mapnav.algo.NavGrid;
import com.holzyn.actor.domain.mapnav.algo.NavGridCodec;
import com.holzyn.actor.domain.mapnav.algo.NavPath;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行期寻路门面：一份地图 → 一张代价场 + 一个 A* 实例（按项目缓存，布局变更即失效）。
 *
 * <p><b>为什么缓存整个快照而不是每次重建</b>：重建要读 nav.bin（3.2 MB）、栅格化 400 多条道路
 * 与 400 多栋房子，几百毫秒级。而寻路是「每人每次行程一次」的热路径——缓存一份，
 * 40 个角色一天几百次寻路只付一次重建成本。</p>
 *
 * <p><b>为什么失效是显式的</b>：会改变走路可行性的只有两类事——用户手改布局、地图重生成。
 * 二者都走 {@link #invalidate(Long)}。不做「每次请求比一次库版本号」那种隐式失效：
 * 那会让 5 游戏分钟一次的 tick 多一次 DB 往返，而收益接近于零。</p>
 *
 * <p><b>nav.bin 缺失时不做「按 seed 重算」</b>：S1 的地形受 S0b 契约（{@code TerrainSemantics}）
 * 影响，单纯按 seed 重跑 {@code TerrainGenerator} 得到的是<b>另一张地形</b>——用它会造出
 * 「人沿着不存在的水岸走」这种最难查的错。宁可降级为直线移动并告警。</p>
 */
@Slf4j
@Service
public class NpcNavigationService {

    /** 前端缩略位图边长（水域红绿提示用；越小越省带宽，128 足够看出「这一步落水里」）。 */
    public static final int PREVIEW_SIZE = 128;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MapArtifactStore artifacts;
    private final ActorWorldMapRepository mapRepository;
    private final ActorMapRoadRepository roadRepository;
    private final ActorMapFeatureRepository featureRepository;

    /** 项目 → 导航快照。 */
    private final Map<Long, Snapshot> cache = new ConcurrentHashMap<>();

    /** 项目 → 重建锁（避免同一项目并发重建）。 */
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    /**
     * 构造。
     *
     * @param artifacts         地图产物（nav.bin 读写）
     * @param mapRepository     地图行
     * @param roadRepository    道路
     * @param featureRepository 要素
     */
    public NpcNavigationService(MapArtifactStore artifacts,
                                ActorWorldMapRepository mapRepository,
                                ActorMapRoadRepository roadRepository,
                                ActorMapFeatureRepository featureRepository) {
        this.artifacts = artifacts;
        this.mapRepository = mapRepository;
        this.roadRepository = roadRepository;
        this.featureRepository = featureRepository;
    }

    /**
     * 一张图的导航快照。
     *
     * @param projectId 项目
     * @return 快照；没有地图或 nav.bin 缺失时为 null
     */
    public Snapshot snapshot(Long projectId) {
        ActorWorldMap map;
        try {
            map = mapRepository.findFirstByProjectIdOrderByIdDesc(projectId).orElse(null);
        } catch (Exception e) {
            log.debug("[mapnav] 读地图行失败：{}", e.getMessage());
            return null;
        }
        if (map == null) {
            return null;
        }
        Snapshot cached = cache.get(projectId);
        if (cached != null && cached.mapId.equals(map.getId())) {
            return cached;
        }
        synchronized (locks.computeIfAbsent(projectId, k -> new Object())) {
            cached = cache.get(projectId);
            if (cached != null && cached.mapId.equals(map.getId())) {
                return cached;
            }
            Snapshot built = build(map);
            if (built != null) {
                cache.put(projectId, built);
            }
            return built;
        }
    }

    /**
     * 寻路（米坐标）。
     *
     * @param projectId 项目
     * @param sx        起点 X
     * @param sz        起点 Z
     * @param tx        终点 X
     * @param tz        终点 Z
     * @return 路径；无导航数据返回 {@link NavPath#none()}
     */
    public NavPath route(Long projectId, double sx, double sz, double tx, double tz) {
        Snapshot snap = snapshot(projectId);
        if (snap == null) {
            return NavPath.none();
        }
        return snap.astar.find(sx, sz, tx, tz);
    }

    /**
     * 某点是否可站（没有导航数据时按「可走」处理，交给降级逻辑）。
     *
     * @param projectId 项目
     * @param x         X
     * @param z         Z
     * @return true=可站
     */
    public boolean walkable(Long projectId, double x, double z) {
        Snapshot snap = snapshot(projectId);
        if (snap == null) {
            return true;
        }
        return snap.field.walkable(snap.grid.cellOf(x), snap.grid.cellOf(z));
    }

    /**
     * 取最近的合法站立足点（门被家具/新建筑堵住时用）。
     *
     * @param projectId 项目
     * @param x         X
     * @param z         Z
     * @param radius    搜索半径（格）
     * @return {x,z} 米；无可站格返回 null
     */
    public double[] nearestWalkable(Long projectId, double x, double z, int radius) {
        Snapshot snap = snapshot(projectId);
        if (snap == null) {
            return new double[]{x, z};
        }
        int[] cell = snap.field.nearestWalkable(x, z, radius);
        return cell == null ? null : new double[]{cell[0] + 0.5, cell[1] + 0.5};
    }

    /**
     * 前端缩略水域位图（base64）。
     *
     * @param projectId 项目
     * @return base64；无数据返回空串
     */
    public String waterPreview(Long projectId) {
        Snapshot snap = snapshot(projectId);
        if (snap == null) {
            return "";
        }
        return NavGridCodec.encodeWater(snap.grid, PREVIEW_SIZE);
    }

    /**
     * 可建位图（水/林/陡坡之外；前端拖拽时提示「这里能放」）。
     *
     * @param projectId 项目
     * @return base64；无数据返回空串
     */
    public String buildablePreview(Long projectId) {
        Snapshot snap = snapshot(projectId);
        if (snap == null) {
            return "";
        }
        NavGrid g = snap.grid;
        int n = g.size * g.size;
        boolean[] buildable = new boolean[n];
        for (int i = 0; i < n; i++) {
            buildable[i] = !g.water[i] && !g.forest[i] && (g.slope[i] & 0xFF) / 255.0 <= 0.32;
        }
        return NavGridCodec.encodeMask(buildable, g.size, PREVIEW_SIZE, true);
    }

    /**
     * 失效某个项目的导航缓存（布局改了 / 地图重生成）。
     *
     * @param projectId 项目；null = 全部失效
     */
    public void invalidate(Long projectId) {
        if (projectId == null) {
            cache.clear();
            return;
        }
        cache.remove(projectId);
    }

    /** 组装快照。 */
    private Snapshot build(ActorWorldMap map) {
        byte[] bytes = artifacts.readNavGrid(map.getId());
        NavGrid grid = bytes == null ? null : NavGridCodec.decode(bytes);
        if (grid == null) {
            log.warn("[mapnav] 地图 {} 没有可用的 nav.bin（运行期降级为直线移动，请重跑 S1）", map.getId());
            return null;
        }
        boolean[] road = new boolean[grid.size * grid.size];
        boolean[] occupied = new boolean[grid.size * grid.size];
        int roadCount = 0;
        int buildingCount = 0;
        try {
            List<ActorMapRoad> roads = roadRepository.findByMapIdOrderBySortOrderAsc(map.getId());
            for (ActorMapRoad r : roads) {
                roadCount += rasterRoad(grid, road, r);
            }
            List<ActorMapFeature> features = featureRepository.findByMapIdOrderBySortOrderAsc(map.getId());
            for (ActorMapFeature f : features) {
                if (rasterBuilding(grid, occupied, f)) {
                    buildingCount++;
                }
            }
        } catch (Exception e) {
            log.warn("[mapnav] 栅格化道路/建筑失败（{}），改用空白代价场", e.getMessage());
        }
        CostField field = CostField.build(grid, road, occupied);
        GridAStar astar = new GridAStar(field);
        log.info("[mapnav] 导航快照：map={} {}² / 路格 {} / 建筑 {} / 提示林 {}",
                map.getId(), grid.size, countTrue(road, grid.size * grid.size), buildingCount,
                countTrue(grid.forest, grid.size * grid.size));
        return new Snapshot(map.getId(), grid, field, astar);
    }

    /** 栅格化一条路，返回标记的格数。 */
    private int rasterRoad(NavGrid grid, boolean[] road, ActorMapRoad row) {
        if (row.getPathJson() == null || row.getPathJson().isBlank()) {
            return 0;
        }
        double halfW = Math.max(0.6, (row.getWidth() == null ? 3.0 : row.getWidth()) / 2.0);
        int marked = 0;
        try {
            JsonNode arr = MAPPER.readTree(row.getPathJson());
            double[] prev = null;
            for (JsonNode p : arr) {
                double[] cur = new double[]{p.path(0).asDouble(), p.path(1).asDouble()};
                if (prev != null) {
                    marked += markSegment(grid, road, prev[0], prev[1], cur[0], cur[1], halfW);
                } else {
                    marked += markSegment(grid, road, cur[0], cur[1], cur[0], cur[1], halfW);
                }
                prev = cur;
            }
        } catch (Exception e) {
            log.debug("[mapnav] 路段解析失败：{}（{}）", row.getCode(), e.getMessage());
        }
        return marked;
    }

    /** 标记「到线段距离 ≤ halfW」的格。 */
    private int markSegment(NavGrid grid, boolean[] out, double x0, double z0, double x1, double z1,
                            double halfW) {
        int minX = grid.cellOf(Math.min(x0, x1) - halfW);
        int maxX = grid.cellOf(Math.max(x0, x1) + halfW);
        int minZ = grid.cellOf(Math.min(z0, z1) - halfW);
        int maxZ = grid.cellOf(Math.max(z0, z1) + halfW);
        int marked = 0;
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                int i = z * grid.size + x;
                if (out[i]) {
                    continue;
                }
                double d = pointSegmentDistance(x + 0.5, z + 0.5, x0, z0, x1, z1);
                if (d <= halfW) {
                    out[i] = true;
                    marked++;
                }
            }
        }
        return marked;
    }

    /** 栅格化一栋建筑/围墙（旋转矩形），返回是否被计入。 */
    private boolean rasterBuilding(NavGrid grid, boolean[] occupied, ActorMapFeature f) {
        if (!ActorMapFeature.KIND_BUILDING.equals(f.getKind())
                && !ActorMapFeature.KIND_WALL.equals(f.getKind())) {
            return false;
        }
        double w = f.getSizeW() == null ? 6.0 : f.getSizeW();
        double d = f.getSizeD() == null ? 6.0 : f.getSizeD();
        double rot = Math.toRadians(f.getRotation() == null ? 0.0 : f.getRotation());
        double cos = Math.cos(rot);
        double sin = Math.sin(rot);
        double cx = f.getPosX();
        double cz = f.getPosZ();
        double r = Math.hypot(w, d) / 2 + 1;
        int minX = grid.cellOf(cx - r);
        int maxX = grid.cellOf(cx + r);
        int minZ = grid.cellOf(cz - r);
        int maxZ = grid.cellOf(cz + r);
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                double dx = x + 0.5 - cx;
                double dz = z + 0.5 - cz;
                // 反旋转到局部坐标（与前端 rectCorners 同一约定：rot 顺时针、0=北）
                double lx = dx * cos + dz * sin;
                double lz = -dx * sin + dz * cos;
                if (Math.abs(lx) <= w / 2 && Math.abs(lz) <= d / 2) {
                    occupied[z * grid.size + x] = true;
                }
            }
        }
        return true;
    }

    private static double pointSegmentDistance(double px, double pz, double x0, double z0,
                                               double x1, double z1) {
        double dx = x1 - x0;
        double dz = z1 - z0;
        double len2 = dx * dx + dz * dz;
        if (len2 <= 1e-12) {
            return Math.hypot(px - x0, pz - z0);
        }
        double t = Math.max(0, Math.min(1, ((px - x0) * dx + (pz - z0) * dz) / len2));
        return Math.hypot(px - (x0 + dx * t), pz - (z0 + dz * t));
    }

    private static int countTrue(boolean[] arr, int n) {
        int c = 0;
        for (int i = 0; i < n; i++) {
            if (arr[i]) {
                c++;
            }
        }
        return c;
    }

    /**
     * 一张图的导航快照（不可变）。
     *
     * @param mapId 地图 ID
     * @param grid  导航栅格
     * @param field 代价场
     * @param astar A* 实例（内部带可复用缓冲，{@code find} 是同步的）
     */
    public record Snapshot(Long mapId, NavGrid grid, CostField field, GridAStar astar) {
    }
}
