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
import com.holzyn.actor.domain.mapgen.entity.ActorMapFeatureGroup;
import com.holzyn.actor.domain.mapgen.entity.ActorMapRoad;
import com.holzyn.actor.domain.mapgen.entity.ActorWorldMap;
import com.holzyn.actor.domain.mapgen.repository.ActorMapFeatureGroupRepository;
import com.holzyn.actor.domain.mapgen.repository.ActorMapFeatureRepository;
import com.holzyn.actor.domain.mapgen.repository.ActorMapRoadRepository;
import com.holzyn.actor.domain.mapgen.repository.ActorWorldMapRepository;
import com.holzyn.actor.domain.mapgen.service.MapPersistService;
import com.holzyn.actor.domain.mapnav.algo.LayoutEditValidator;
import com.holzyn.actor.domain.mapnav.algo.NavGrid;
import com.holzyn.actor.domain.mapnav.config.MapNavProperties;
import com.holzyn.actor.domain.mapnav.vo.LayoutEditResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 布局编辑：校验 → 落库（覆盖层）→ 派生数据跟随 → 缓存失效。
 *
 * <p><b>为什么必须是「整批原子」</b>：用户一次保存常常是整院挪动（主屋 + 厢房 + 院墙 + 门楼）。
 * 如果按条提交，前三条成功、第四条压到路，图上就会出现「半个院子」——比整批拒绝难收拾得多。
 * 所以先全量校验，任一条不过就整批 409 并逐条给原因。</p>
 *
 * <p><b>落库顺序是有讲究的</b>：先写覆盖层（用户意图，最重要），再改要素几何（视图），
 * 再回写日程落点/住所坐标（派生），最后失效导航缓存。任何一步失败都不吞异常——
 * 用户必须知道「这次没存上」，而不是刷新后发现房子还在原地。</p>
 *
 * <p>本类<b>不调用任何 AI</b>（提交是纯几何），因此可以用事务；AI 决策的接线在 P2 走另一条路。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LayoutEditService {

    private final ActorWorldMapRepository mapRepository;
    private final ActorMapFeatureRepository featureRepository;
    private final ActorMapRoadRepository roadRepository;
    private final ActorMapFeatureGroupRepository groupRepository;
    private final MapPersistService persist;
    private final LayoutOverrideService overrideService;
    private final NpcNavigationService navigationService;
    private final MapNavProperties props;

    /**
     * 提交一批布局编辑。
     *
     * @param projectId    项目
     * @param baseRevision 前端拿到的修订号（乐观锁；null=不校验）
     * @param edits        编辑项
     * @return 结果（成功或逐条拒绝原因）
     */
    public LayoutEditResultVO apply(Long projectId, Integer baseRevision, List<LayoutEditResultVO.Edit> edits) {
        LayoutEditResultVO result = new LayoutEditResultVO();
        result.setRevision(overrideService.revision(projectId));
        result.setApplied(new ArrayList<>());
        result.setRejected(new ArrayList<>());
        result.setWarnings(new ArrayList<>());
        if (edits == null || edits.isEmpty()) {
            result.setError("没有要提交的编辑");
            return result;
        }
        if (baseRevision != null && baseRevision != result.getRevision()) {
            result.setStale(true);
            result.setError("地图已被其他操作修改（当前修订 " + result.getRevision() + "），请刷新后重试");
            return result;
        }
        ActorWorldMap map = mapRepository.findFirstByProjectIdOrderByIdDesc(projectId).orElse(null);
        if (map == null) {
            result.setError("这张地图还没生成");
            return result;
        }
        List<ActorMapFeature> features = featureRepository.findByMapIdOrderBySortOrderAsc(map.getId());
        Map<String, ActorMapFeature> byCode = new HashMap<>();
        for (ActorMapFeature f : features) {
            byCode.put(f.getCode(), f);
        }
        List<ActorMapRoad> roads = roadRepository.findByMapIdOrderBySortOrderAsc(map.getId());
        NpcNavigationService.Snapshot snap = navigationService.snapshot(projectId);
        NavGrid grid = snap == null ? null : snap.grid();

        // ① 全量校验（任何一条不合法 → 整批拒绝，绝不半途落库）
        Map<String, Move> moves = new LinkedHashMap<>();
        for (LayoutEditResultVO.Edit e : edits) {
            ActorMapFeature f = byCode.get(e.getCode());
            if (f == null) {
                addRejected(result, e.getCode(), "找不到要素");
                continue;
            }
            if (!ActorMapFeature.KIND_BUILDING.equals(f.getKind())) {
                addRejected(result, e.getCode(), "只允许编辑建筑（围墙/院落请随主屋整组移动）");
                continue;
            }
            double w = f.getSizeW() == null ? 6 : f.getSizeW();
            double d = f.getSizeD() == null ? 6 : f.getSizeD();
            double rot = e.getRotation() == null
                    ? (f.getRotation() == null ? 0 : f.getRotation()) : e.getRotation();
            double x = e.getX() == null ? f.getPosX() : e.getX();
            double z = e.getZ() == null ? f.getPosZ() : e.getZ();
            LayoutEditValidator.Candidate cand = new LayoutEditValidator.Candidate(
                    f.getCode(), x, z, w, d, rot);
            LayoutEditValidator.Limits limits = new LayoutEditValidator.Limits(
                    map.getSize() == null ? 1024 : map.getSize(),
                    props.getMapMarginMeters(), props.getRoadClearanceMeters(),
                    props.getBuildingGapMeters(), props.getMaxBuildSlope(),
                    props.getMaxDoorToRoadMeters());
            List<LayoutEditValidator.Violation> violations =
                    LayoutEditValidator.validate(cand, grid, roads, features, limits);
            for (LayoutEditValidator.Violation v : violations) {
                addRejected(result, e.getCode(), v.message());
            }
            if (violations.isEmpty()) {
                moves.put(f.getCode(), new Move(f, x, z, rot, w, d));
            }
        }
        if (!result.getRejected().isEmpty()) {
            result.setOk(false);
            return result;
        }

        // ② 落库：覆盖层先行，再改要素几何，再回写派生数据
        int routines = 0;
        int homes = 0;
        Map<Long, double[]> groupShift = new HashMap<>();
        for (Move m : moves.values()) {
            ActorMapFeature f = m.feature;
            double oldX = f.getPosX() == null ? m.x : f.getPosX();
            double oldZ = f.getPosZ() == null ? m.z : f.getPosZ();
            double[] doors = doorOf(f, m);
            overrideService.upsert(projectId, map.getId(), f.getCode(), m.x, m.z, m.rot,
                    doors[0], doors[1], doors[2], null);
            persist.applyLayoutGeometry(f.getId(), m.x, m.z, m.rot, doors[0], doors[1], doors[2]);
            routines += persist.updateRoutineAnchor(map.getId(), f.getCode(), doors[0], doors[1]);
            if (f.getOwnerHomeRef() != null && !f.getOwnerHomeRef().isBlank()) {
                persist.updateHomeAnchor(f.getOwnerHomeRef(), m.x, m.z, doors[0], doors[1]);
                homes++;
            }
            result.getApplied().add(f.getCode());
            if (f.getGroupId() != null) {
                groupShift.put(f.getGroupId(), new double[]{m.x - oldX, m.z - oldZ});
            }
        }
        // ③ 整院联动：把同组其他建筑按同一位移搬走（否则院墙/厢房会留在原地）
        java.util.Set<String> moved = new java.util.HashSet<>(moves.keySet());
        for (Map.Entry<Long, double[]> e : groupShift.entrySet()) {
            double dx = e.getValue()[0];
            double dz = e.getValue()[1];
            if (Math.abs(dx) < 1e-6 && Math.abs(dz) < 1e-6) {
                continue;
            }
            result.setAffectedSiblings(result.getAffectedSiblings()
                    + shiftGroup(map, projectId, e.getKey(), dx, dz, moved));
        }

        // ④ 失效导航缓存：下一次寻路用新几何
        navigationService.invalidate(projectId);
        result.setOk(true);
        result.setRevision(overrideService.revision(projectId));
        result.setAffectedRoutines(routines);
        result.setAffectedHomes(homes);
        log.info("[mapnav] 布局提交：project={} 要素 {} / 日程 {} / 住所 {}",
                projectId, result.getApplied().size(), routines, homes);
        return result;
    }

    /** 搬同组其他建筑（主屋动了，厢房/围墙/门楼跟着动）。 */
    private int shiftGroup(ActorWorldMap map, Long projectId, Long groupId, double dx, double dz,
                           java.util.Set<String> alreadyMoved) {
        int n = 0;
        // 本节只做「同组同位移」：院落内部的相对关系（谁在左谁在右）保持不变
        ActorMapFeatureGroup group = groupRepository.findById(groupId).orElse(null);
        if (group != null && group.getCenterX() != null && group.getCenterZ() != null) {
            group.setCenterX(group.getCenterX() + dx);
            group.setCenterZ(group.getCenterZ() + dz);
            groupRepository.save(group);
        }
        for (ActorMapFeature f : featureRepository.findByMapIdOrderBySortOrderAsc(map.getId())) {
            if (!groupId.equals(f.getGroupId()) || f.getPosX() == null || f.getPosZ() == null) {
                continue;
            }
            if (alreadyMoved.contains(f.getCode())) {
                continue;
            }
            double nx = f.getPosX() + dx;
            double nz = f.getPosZ() + dz;
            double rot = f.getRotation() == null ? 0 : f.getRotation();
            double[] doors = doorOf(f, new Move(f, nx, nz, rot,
                    f.getSizeW() == null ? 6 : f.getSizeW(), f.getSizeD() == null ? 6 : f.getSizeD()));
            overrideService.upsert(projectId, map.getId(), f.getCode(), nx, nz, rot,
                    doors[0], doors[1], doors[2], "随院落整组移动");
            persist.applyLayoutGeometry(f.getId(), nx, nz, rot, doors[0], doors[1], doors[2]);
            alreadyMoved.add(f.getCode());
            n++;
        }
        return n;
    }

    /**
     * 门位置随几何走：保持「门在建筑局部坐标系里的位置」，旋转/平移后重新算世界坐标。
     *
     * @return {doorX, doorZ, doorDir}
     */
    private double[] doorOf(ActorMapFeature f, Move m) {
        double oldRot = Math.toRadians(f.getRotation() == null ? 0 : f.getRotation());
        double newRot = Math.toRadians(m.rot);
        double oldCx = f.getPosX() == null ? m.x : f.getPosX();
        double oldCz = f.getPosZ() == null ? m.z : f.getPosZ();
        double relX;
        double relZ;
        if (f.getDoorX() != null && f.getDoorZ() != null) {
            double dx = f.getDoorX() - oldCx;
            double dz = f.getDoorZ() - oldCz;
            // 反旋转到局部坐标（与 rectCorners 同约定）
            relX = dx * Math.cos(oldRot) + dz * Math.sin(oldRot);
            relZ = -dx * Math.sin(oldRot) + dz * Math.cos(oldRot);
        } else {
            // 没有门位：默认开在正面（进深方向 +d/2）
            relX = 0;
            relZ = m.d / 2;
        }
        double wx = m.x + relX * Math.cos(newRot) + relZ * Math.sin(newRot);
        double wz = m.z - relX * Math.sin(newRot) + relZ * Math.cos(newRot);
        double dir = m.rot + (m.rot - (f.getRotation() == null ? 0 : f.getRotation()));
        return new double[]{wx, wz, (dir % 360 + 360) % 360};
    }

    private void addRejected(LayoutEditResultVO result, String code, String reason) {
        LayoutEditResultVO.Rejected r = new LayoutEditResultVO.Rejected();
        r.setCode(code);
        r.setReason(reason);
        result.getRejected().add(r);
    }

    /** 一次移动的内部表示。 */
    private record Move(ActorMapFeature feature, double x, double z, double rot, double w, double d) {
    }
}
