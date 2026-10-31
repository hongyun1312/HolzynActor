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
import com.holzyn.actor.domain.mapgen.entity.ActorNpcRoutine;
import com.holzyn.actor.domain.mapgen.entity.ActorWorldMap;
import com.holzyn.actor.domain.mapgen.repository.ActorMapFeatureRepository;
import com.holzyn.actor.domain.mapgen.repository.ActorNpcRoutineRepository;
import com.holzyn.actor.domain.mapgen.repository.ActorWorldMapRepository;
import com.holzyn.actor.domain.mapnav.algo.GridAStar;
import com.holzyn.actor.domain.mapnav.algo.NavPath;
import com.holzyn.actor.domain.mapnav.config.MapNavProperties;
import com.holzyn.actor.domain.mapnav.entity.ActorNpcPath;
import com.holzyn.actor.domain.mapnav.entity.ActorNpcPosition;
import com.holzyn.actor.domain.mapnav.repository.ActorNpcPathRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 移动引擎：把「日程段」变成「沿路的轨迹」，并按世界时钟推进坐标。
 *
 * <p><b>它替掉的是什么</b>：旧行为是前端拿两个日程落点做线性插值（{@code mapStage.placeCharacters}），
 * 于是 300 米和 30 米同速、路线是一条穿墙斜线、后端不知道人在哪。这里的做法是：</p>
 * <ol>
 *   <li><b>算路</b>：从当前位置到本段落点（建筑用门口坐标）走 {@link NpcNavigationService} 的 A*；</li>
 *   <li><b>定时刻</b>：行程 = 路径长度 / 步速（米每游戏分钟），clamp 到 [1, 45] 游戏分钟；</li>
 *   <li><b>推进</b>：每个 tick 按游戏秒在折线上按弧长求点，写回 {@code actor_npc_position}；</li>
 *   <li><b>到点</b>：状态转 static、室内标记取自本段，下一次段切换才会再动。</li>
 * </ol>
 *
 * <p><b>为什么「段切换」用距离判定而不是记一个 lastSegment</b>：记状态会在重启后失忆——
 * 世界一重启，全场 NPC 就会因为「不知道自己在哪一段」集体重算一次路径。改成
 * 「现在的位置离本段落点超过容差就要走」更朴素也更稳：不需要额外的持久化字段，
 * 重启后第一帧自然收敛。</p>
 *
 * <p>本类<b>零 AI</b>、失败隔离：任何异常只记日志，绝不让世界推进停摆。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NpcMovementService {

    /** JSON 读写（轨迹折线）。 */
    private static final tools.jackson.databind.ObjectMapper MAPPER =
            new tools.jackson.databind.ObjectMapper();

    /** 轨迹状态：在途 */
    public static final String WALKING = "walking";

    /** 轨迹状态：已到达 */
    public static final String ARRIVED = "arrived";

    /** 轨迹状态：被打断 */
    public static final String ABORTED = "aborted";

    /** 起因：日程 */
    public static final String REASON_ROUTINE = "routine";

    private final ActorNpcPathRepository pathRepository;
    private final ActorNpcRoutineRepository routineRepository;
    private final ActorMapFeatureRepository featureRepository;
    private final ActorWorldMapRepository mapRepository;
    private final NpcNavigationService navigationService;
    private final NpcPositionService positionService;
    private final MapNavProperties props;

    /**
     * 推进一个世界 tick（挂在 {@code WorldEngine} 的感知周期里）。
     *
     * @param projectId  项目
     * @param gameSecond 当前世界游戏秒
     */
    public void tick(Long projectId, long gameSecond) {
        if (!props.isEnabled()) {
            return;
        }
        ActorWorldMap map;
        try {
            map = mapRepository.findFirstByProjectIdOrderByIdDesc(projectId).orElse(null);
        } catch (Exception e) {
            log.debug("[mapnav] 读地图失败：{}", e.getMessage());
            return;
        }
        if (map == null) {
            return;
        }
        List<ActorNpcRoutine> routines;
        try {
            routines = routineRepository.findByProjectIdAndMapIdOrderByNpcRefAscSeqAsc(
                    projectId, map.getId());
        } catch (Exception e) {
            log.debug("[mapnav] 读日程失败：{}", e.getMessage());
            return;
        }
        if (routines.isEmpty()) {
            return;
        }
        Map<String, List<ActorNpcRoutine>> byRef = new LinkedHashMap<>();
        for (ActorNpcRoutine r : routines) {
            if (r.getNpcRef() != null) {
                byRef.computeIfAbsent(r.getNpcRef(), k -> new ArrayList<>()).add(r);
            }
        }
        Map<String, ActorMapFeature> features = featuresOf(map.getId());
        Map<String, ActorNpcPosition> positions = new HashMap<>();
        for (ActorNpcPosition p : positionService.list(projectId)) {
            positions.put(p.getNpcRef(), p);
        }
        Map<String, ActorNpcPath> walking = new HashMap<>();
        for (ActorNpcPath p : pathRepository.findByProjectIdAndStatusOrderByIdAsc(projectId, WALKING)) {
            walking.put(p.getNpcRef(), p);
        }
        int minute = (int) Math.floorMod(gameSecond / 60, 1440);
        List<ActorNpcPosition> dirty = new ArrayList<>();
        for (Map.Entry<String, List<ActorNpcRoutine>> e : byRef.entrySet()) {
            String ref = e.getKey();
            List<ActorNpcRoutine> segs = e.getValue();
            ActorNpcRoutine seg = segmentAt(segs, minute);
            if (seg == null) {
                continue;
            }
            ActorNpcPosition pos = positions.get(ref);
            double[] target = spotOf(seg, features);
            if (pos == null) {
                // 首次进世界：直接落位到本段落点，不让所有人从 (0,0) 走过来
                pos = positionService.upsert(projectId, map.getId(), ref, seg.getNpcName(),
                        target[0], target[1], 0, seg.getIndoor() != null && seg.getIndoor() == 1,
                        seg.getActivity(), seg.getFeatureCode(), "static", gameSecond);
                positions.put(ref, pos);
                continue;
            }
            ActorNpcPath path = walking.get(ref);
            if (path != null) {
                advance(map, pos, path, gameSecond, seg, target, dirty);
                continue;
            }
            double dist = Math.hypot(target[0] - pos.getX(), target[1] - pos.getZ());
            if (dist > props.getArriveToleranceMeters()) {
                startRoute(projectId, map.getId(), pos, seg, target, gameSecond, REASON_ROUTINE);
                positions.put(ref, pos);
                dirty.add(pos);
            } else if (!segStateMatches(pos, seg)) {
                // 原地不动，但「活动/室内」变了（比如进屋睡觉）：只改状态，不生成轨迹
                pos.setActivity(seg.getActivity());
                pos.setIndoor(seg.getIndoor() != null && seg.getIndoor() == 1 ? 1 : 0);
                pos.setFeatureCode(seg.getFeatureCode());
                pos.setStatus("static");
                pos.setGameSecond(gameSecond);
                dirty.add(pos);
            }
        }
        if (!dirty.isEmpty()) {
            positionService.saveAll(dirty);
        }
    }

    /**
     * 主动发起一次移动（P2 的 AI 决策走这里，和日程用同一条管线）。
     *
     * @param projectId 项目
     * @param npcRef    角色
     * @param targetX   目标 X（米）
     * @param targetZ   目标 Z（米）
     * @param targetRef 目标引用（要素 code / 地点 code，落日志用）
     * @param reason    起因（decision / return_home）
     * @param gameSecond 当前游戏秒
     * @return 轨迹；角色未落位或开关关闭返回 null
     */
    public ActorNpcPath startMove(Long projectId, String npcRef, double targetX, double targetZ,
                                  String targetRef, String reason, long gameSecond) {
        if (!props.isEnabled() || npcRef == null) {
            return null;
        }
        ActorWorldMap map = mapRepository.findFirstByProjectIdOrderByIdDesc(projectId).orElse(null);
        ActorNpcPosition pos = positionService.find(projectId, npcRef).orElse(null);
        if (map == null || pos == null) {
            return null;
        }
        // 同一角色至多一条在途轨迹：旧的置 aborted，新的从当前位置起步
        for (ActorNpcPath old : pathRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(
                projectId, npcRef, WALKING)) {
            old.setStatus(ABORTED);
            pathRepository.save(old);
        }
        ActorNpcRoutine seg = new ActorNpcRoutine();
        seg.setNpcRef(npcRef);
        seg.setNpcName(pos.getNpcName());
        seg.setActivity(pos.getActivity());
        seg.setIndoor(pos.getIndoor());
        seg.setFeatureCode(targetRef);
        return startRoute(projectId, map.getId(), pos, seg,
                new double[]{targetX, targetZ}, gameSecond, reason == null ? "decision" : reason);
    }

    /**
     * 某角色当前在途轨迹（查询/渲染用）。
     *
     * @param projectId 项目
     * @param npcRef    角色
     * @return 轨迹；不在途返回 empty
     */
    public Optional<ActorNpcPath> currentPath(Long projectId, String npcRef) {
        List<ActorNpcPath> rows = pathRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(
                projectId, npcRef, WALKING);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    // ==================== 内部 ====================

    /** 从当前位置到目标起一条轨迹。 */
    private ActorNpcPath startRoute(Long projectId, Long mapId, ActorNpcPosition pos,
                                    ActorNpcRoutine seg, double[] target, long gameSecond,
                                    String reason) {
        double fromX = pos.getX();
        double fromZ = pos.getZ();
        NavPath nav = navigationService.route(projectId, fromX, fromZ, target[0], target[1]);
        List<double[]> points;
        double length;
        if (nav.reachable()) {
            points = nav.points();
            length = nav.lengthM();
        } else {
            // 无导航数据 / 被围死：直线兜底，但要留痕——这类移动在验收里是「异常项」
            points = List.of(new double[]{fromX, fromZ}, new double[]{target[0], target[1]});
            length = Math.hypot(target[0] - fromX, target[1] - fromZ);
            log.warn("[mapnav] 无可用路径，退化为直线：project={} ref={} → ({},{})",
                    projectId, pos.getNpcRef(), Math.round(target[0]), Math.round(target[1]));
        }
        if (length < 0.6) {
            // 已在门口：不生成轨迹，直接落位
            pos.setX(target[0]);
            pos.setZ(target[1]);
            pos.setStatus("static");
            pos.setGameSecond(gameSecond);
            pos.setActivity(seg.getActivity());
            pos.setFeatureCode(seg.getFeatureCode());
            return null;
        }
        double speed = Math.max(1.0, props.getWalkSpeed());
        double minutes = clamp(length / speed, props.getMinTravelMinutes(), props.getMaxTravelMinutes());
        long depart = gameSecond;
        long arrive = gameSecond + Math.max(1, Math.round(minutes * 60));
        ActorNpcPath path = new ActorNpcPath();
        path.setProjectId(projectId);
        path.setMapId(mapId);
        path.setNpcRef(pos.getNpcRef());
        path.setPointsJson(toJson(points));
        path.setLengthM(length);
        path.setDepartGameSecond(depart);
        path.setArriveGameSecond(arrive);
        path.setFromRef(pos.getFeatureCode());
        path.setToRef(seg.getFeatureCode());
        path.setReason(reason);
        path.setStatus(WALKING);
        path.setVersion(1);
        try {
            path = pathRepository.save(path);
        } catch (Exception e) {
            log.warn("[mapnav] 写轨迹失败：{}", e.getMessage());
            return null;
        }
        pos.setStatus(WALKING);
        pos.setGameSecond(gameSecond);
        pos.setActivity(seg.getActivity());
        pos.setFeatureCode(seg.getFeatureCode());
        return path;
    }

    /** 按游戏秒推进一条在途轨迹。 */
    private void advance(ActorWorldMap map, ActorNpcPosition pos, ActorNpcPath path, long gameSecond,
                         ActorNpcRoutine seg, double[] target, List<ActorNpcPosition> dirty) {
        List<double[]> pts = parse(path.getPointsJson());
        if (pts.size() < 2) {
            path.setStatus(ABORTED);
            pathRepository.save(path);
            pos.setStatus("static");
            dirty.add(pos);
            return;
        }
        long depart = path.getDepartGameSecond() == null ? gameSecond : path.getDepartGameSecond();
        long arrive = path.getArriveGameSecond() == null ? gameSecond : path.getArriveGameSecond();
        double span = Math.max(1, arrive - depart);
        double t = (gameSecond - depart) / span;
        t = Math.max(0, Math.min(1, t));
        double[] at = GridAStar.pointAt(pts, t * path.getLengthM());
        double[] ahead = GridAStar.pointAt(pts, Math.min(path.getLengthM(), t * path.getLengthM() + 1.5));
        pos.setX(at[0]);
        pos.setZ(at[1]);
        pos.setHeading(bearing(at, ahead));
        pos.setStatus(WALKING);
        pos.setGameSecond(gameSecond);
        pos.setActivity(seg.getActivity());
        pos.setFeatureCode(seg.getFeatureCode());
        dirty.add(pos);
        if (t >= 1) {
            double[] last = pts.get(pts.size() - 1);
            pos.setX(last[0]);
            pos.setZ(last[1]);
            pos.setStatus("static");
            pos.setIndoor(seg.getIndoor() != null && seg.getIndoor() == 1 ? 1 : 0);
            // 落点与日程不一致时以日程为准（覆盖层改了门，轨迹可能停在新门口外侧）
            if (Math.hypot(last[0] - target[0], last[1] - target[1]) > 0.5) {
                pos.setX(last[0]);
                pos.setZ(last[1]);
            }
            path.setStatus(ARRIVED);
            pathRepository.save(path);
        }
    }

    /** 本段落点：建筑优先用门口坐标（人能真的走到门里，而不是走到房子中心）。 */
    private double[] spotOf(ActorNpcRoutine seg, Map<String, ActorMapFeature> features) {
        if (seg.getFeatureCode() != null) {
            ActorMapFeature f = features.get(seg.getFeatureCode());
            if (f != null && f.getDoorX() != null && f.getDoorZ() != null) {
                return new double[]{f.getDoorX(), f.getDoorZ()};
            }
            if (f != null && f.getPosX() != null && f.getPosZ() != null) {
                return new double[]{f.getPosX(), f.getPosZ()};
            }
        }
        double x = seg.getPosX() == null ? 0 : seg.getPosX();
        double z = seg.getPosZ() == null ? 0 : seg.getPosZ();
        return new double[]{x, z};
    }

    /** 当前位置是否已经「处于本段该有的状态」（活动 + 室内一致）。 */
    private boolean segStateMatches(ActorNpcPosition pos, ActorNpcRoutine seg) {
        boolean indoor = seg.getIndoor() != null && seg.getIndoor() == 1;
        boolean posIndoor = pos.getIndoor() != null && pos.getIndoor() == 1;
        return indoor == posIndoor
                && String.valueOf(pos.getActivity()).equals(String.valueOf(seg.getActivity()));
    }

    /** 取该图的要素索引。 */
    private Map<String, ActorMapFeature> featuresOf(Long mapId) {
        Map<String, ActorMapFeature> map = new HashMap<>();
        try {
            for (ActorMapFeature f : featureRepository.findByMapIdOrderBySortOrderAsc(mapId)) {
                map.put(f.getCode(), f);
            }
        } catch (Exception e) {
            log.debug("[mapnav] 读要素失败：{}", e.getMessage());
        }
        return map;
    }

    /** 当天某一分钟属于哪一段。 */
    private static ActorNpcRoutine segmentAt(List<ActorNpcRoutine> segs, int minute) {
        for (ActorNpcRoutine s : segs) {
            int start = s.getStartMinute() == null ? 0 : s.getStartMinute();
            int end = s.getEndMinute() == null ? 1440 : s.getEndMinute();
            if (minute >= start && minute < end) {
                return s;
            }
        }
        return segs.isEmpty() ? null : segs.get(segs.size() - 1);
    }

    /** 折线 → JSON（保留 2 位小数，文件小且够用）。 */
    private static String toJson(List<double[]> pts) {
        StringBuilder sb = new StringBuilder(pts.size() * 18 + 4);
        sb.append('[');
        for (int i = 0; i < pts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[').append(round2(pts.get(i)[0])).append(',').append(round2(pts.get(i)[1])).append(']');
        }
        sb.append(']');
        return sb.toString();
    }

    /** JSON → 折线。 */
    private static List<double[]> parse(String json) {
        List<double[]> pts = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return pts;
        }
        try {
            tools.jackson.databind.JsonNode arr = MAPPER.readTree(json);
            for (tools.jackson.databind.JsonNode p : arr) {
                pts.add(new double[]{p.path(0).asDouble(), p.path(1).asDouble()});
            }
        } catch (Exception e) {
            log.debug("[mapnav] 轨迹解析失败：{}", e.getMessage());
        }
        return pts;
    }

    /** 朝向（度，0=北，顺时针）。 */
    private static double bearing(double[] from, double[] to) {
        double dx = to[0] - from[0];
        double dz = to[1] - from[1];
        if (Math.abs(dx) < 1e-6 && Math.abs(dz) < 1e-6) {
            return 0;
        }
        double deg = Math.toDegrees(Math.atan2(dx, -dz));
        return (deg + 360) % 360;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
