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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /**
     * 权威行允许的滞后上限（游戏秒）。超过它就说明这一行被漏更新了，{@link #healUntouchedPositions}
     * 会把它重锚回当前日程落点。取 2 个引擎 tick（300 × 2），给「上一帧刚写过、这一帧还没轮到」留一格余量。
     */
    private static final long STALE_GRACE_GAME_SECONDS = 600L;

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
     * AI 意图驻留锁：窗口内日程不抢人。字段注入保持构造器不变；
     * null=未装配[单测]时行为与旧版一致（日程照常推进）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NpcIntentLock intentLock;

    /**
     * 途中遭遇（迁移到权威坐标）：沙盘移动不再承载权威世界，遭遇检测必须在
     * 权威轨迹上做，否则「单一移动引擎」等于把途中交互一起删掉。
     * {@code @Lazy}：MoveEncounterService → 沙盘 Hub → MovementService，避免 Bean 环。
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.worldmap.service.MoveEncounterService moveEncounterService;

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
        Set<String> touched = new HashSet<>();
        for (Map.Entry<String, List<ActorNpcRoutine>> e : byRef.entrySet()) {
            String ref = e.getKey();
            List<ActorNpcRoutine> segs = e.getValue();
            try {
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
                    touched.add(ref);
                    continue;
                }
                touched.add(ref);
                ActorNpcPath path = walking.get(ref);
                if (path != null) {
                    advance(map, pos, path, gameSecond, seg, target, dirty);
                    // （单一移动引擎）：权威轨迹上的途中遭遇。沙盘那条路已不再由
                    // AI 行动发起，遭遇检测必须跟着权威走，否则「统一移动」会把途中交互一并弄丢。
                    maybeEncounterAuthority(projectId, ref, pos, positions, gameSecond);
                    continue;
                }
                // （意图 vs 日程）：AI 让人去哪、到站后留一个驻留窗口。
                // 窗口内日程不抢人——否则「他为什么来这儿」这件事在世界上根本留不下痕迹。
                if (intentLock != null) {
                    NpcIntentLock.Hold hold = intentLock.holding(projectId, ref, gameSecond);
                    if (hold != null) {
                        long remainMin = Math.max(0L, hold.remaining(gameSecond)) / 60L;
                        log.info("[mapnav] 日程让位于 AI 意图：ref={} 意图={}（{}）还可驻留 {} 游戏分钟",
                                ref, hold.action() == null ? "前往目的地" : hold.action(),
                                hold.featureCode(), remainMin);
                        pos.setGameSecond(gameSecond);
                        dirty.add(pos);
                        continue;
                    }
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
                } else {
                    // 心跳：人没动、状态也没变，权威行同样要体现「这个 tick 我看过他，他就在这」。
                    // 不写这一笔，一行静止坐标会永远停在上次写入的秒数上——实测对账里那 26 条
                    // 滞后 4500 秒的「分歧」全部来自这里（人一直坐在自家院子里，坐标没错，只是没心跳）。
                    pos.setGameSecond(gameSecond);
                    dirty.add(pos);
                }
            } catch (Exception ex) {
                // 单个角色算路/推进失败必须就地隔离：否则整个 tick 中断，之后全场角色都停在旧坐标，
                // 权威行比世界时钟落后几十分钟（实测 lag 达 6900 游戏秒），三方对账随即全红。
                log.warn("[mapnav] 角色推进失败（隔离继续）：project={} ref={} gameSecond={} : {}",
                        projectId, ref, gameSecond, ex.toString());
            }
        }
        healUntouchedPositions(gameSecond, positions.values(), touched, byRef, walking,
                minute, features, dirty);
        if (!dirty.isEmpty()) {
            positionService.saveAll(dirty);
        }
    }

    /**
     * 权威行自愈：本 tick 未被日程循环刷新、且行内游戏秒落后当前时钟超过 2 个 tick 的角色，
     * 直接重锚到「当前时段该在的落点」，并把残留的在途轨迹收尾。
     *
     * <p><b>为什么需要它</b>：世界引擎在积压时会丢弃一段时间（{@code tick 队列溢出}），
     * 或者某个角色因数据异常被 {@code continue} 跳过；这两种情况下权威行会停在旧秒数上，
     * 而权威-渲染-注入三方对账的口径是「滞后不得超过 1 个 tick」。
     * 与其去追每一种漏更新的根因，不如在这里立一条不变量：
     * <b>任何角色的权威行都不会落后当前时钟超过 2 个 tick（600 游戏秒）</b>。</p>
     *
     * <p>重锚是「按日程瞬移」而不是插值补间：世界时间已经跳过这段了，
     * 补间只会生成一段从未真实发生过的轨迹。</p>
     */    private void healUntouchedPositions(long gameSecond, Collection<ActorNpcPosition> rows,
                                        Set<String> touched,
                                        Map<String, List<ActorNpcRoutine>> byRef,
                                        Map<String, ActorNpcPath> walking, int minute,
                                        Map<String, ActorMapFeature> features,
                                        List<ActorNpcPosition> dirty) {
        int healed = 0;
        long maxLag = 0;
        for (ActorNpcPosition pos : rows) {
            if (pos == null || pos.getNpcRef() == null || touched.contains(pos.getNpcRef())) {
                continue;
            }
            long rowSecond = pos.getGameSecond();
            long lag = gameSecond - rowSecond;
            if (lag <= STALE_GRACE_GAME_SECONDS) {
                continue;
            }
            List<ActorNpcRoutine> segs = byRef.get(pos.getNpcRef());
            if (segs == null || segs.isEmpty()) {
                // 没有日程的角色（路人/临时角色）不能重锚（不知道该去哪），但可以心跳：
                // 坐标原样保留，只把秒数推到当前，否则对账永远把他算成滞后。
                pos.setGameSecond(gameSecond);
                dirty.add(pos);
                log.debug("[mapnav] 权威行滞后且无日程，仅心跳：ref={} lag={}s", pos.getNpcRef(), lag);
                continue;
            }
            ActorNpcRoutine seg = segmentAt(segs, minute);
            if (seg == null) {
                continue;
            }
            double[] target = spotOf(seg, features);
            ActorNpcPath path = walking.get(pos.getNpcRef());
            if (path != null) {
                // 旧轨迹还在途：直接作废，避免下一帧按旧折线把人拉回过去的路上
                path.setStatus(ABORTED);
                pathRepository.save(path);
            }
            pos.setX(target[0]);
            pos.setZ(target[1]);
            pos.setActivity(seg.getActivity());
            pos.setIndoor(seg.getIndoor() != null && seg.getIndoor() == 1 ? 1 : 0);
            pos.setFeatureCode(seg.getFeatureCode());
            pos.setStatus("static");
            pos.setGameSecond(gameSecond);
            dirty.add(pos);
            healed++;
            maxLag = Math.max(maxLag, lag);
            log.debug("[mapnav] 权威行滞后自愈：ref={} 行={}s 时钟={}s 滞后={}s → ({}, {})",
                    pos.getNpcRef(), rowSecond, gameSecond, lag, target[0], target[1]);
        }
        if (healed > 0) {
            log.warn("[mapnav] 权威坐标滞后自愈 {} 个角色（最大滞后 {} 游戏秒），已重锚到当前日程落点",
                    healed, maxLag);
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
        if (!nav.reachable()) {
            // 起点或终点落在建筑/水里（覆盖层改过门、或人本来就站在占位格里）：
            // 先把两端挪到最近的可站格再试一次，比直接直线穿墙可信得多。
            double[] from = navigationService.nearestWalkable(projectId, fromX, fromZ, 6);
            double[] to = navigationService.nearestWalkable(projectId, target[0], target[1], 6);
            if (from != null && to != null
                    && (Math.hypot(from[0] - fromX, from[1] - fromZ) > 1e-6
                    || Math.hypot(to[0] - target[0], to[1] - target[1]) > 1e-6)) {
                NavPath retry = navigationService.route(projectId, from[0], from[1], to[0], to[1]);
                if (retry.reachable()) {
                    nav = retry;
                }
            }
        }
        List<double[]> points;
        double length;
        if (nav.reachable()) {
            points = nav.points();
            length = nav.lengthM();
        } else {
            // 真的走不过去（被围死 / 无导航数据）：直线兜底，但要留痕——这类移动在验收里是「异常项」
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
        // 三方对账 · 同源归一化：轨迹落库时折线会被 round2 压成两位小数，
        // 而前端归一化用的是「它读到的折线」的长度。若这里沿用寻路器在**未压精度**折线上量出的
        // 长度，两侧的归一化因子就不同源——同一条轨迹上权威点与渲染点会按比例偏开，轨迹越长偏得越多。
        // 所以长度一律「按落库折线重算」，让 lengthM == 前端 polylineLength(points) 逐位相等。
        String pointsJson = toJson(points);
        length = polylineLength(parse(pointsJson));
        double speed = Math.max(1.0, props.getWalkSpeed());
        double minutes = clamp(length / speed, props.getMinTravelMinutes(), props.getMaxTravelMinutes());
        long depart = gameSecond;
        long arrive = gameSecond + Math.max(1, Math.round(minutes * 60));
        ActorNpcPath path = new ActorNpcPath();
        path.setProjectId(projectId);
        path.setMapId(mapId);
        path.setNpcRef(pos.getNpcRef());
        path.setPointsJson(pointsJson);
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
            // 到点坐标一律取轨迹终点，不取日程落点：AI 主动发起的移动终点常常不是本段落点，
            // 若在这里按 target 吸附，人会被凭空拉回日程地点（旧代码写过这个分支，等于把人瞬移）。
            path.setStatus(ARRIVED);
            pathRepository.save(path);
        }
    }

    /**
     * 权威轨迹上的途中遭遇。
     *
     * <p>沙盘移动不再由 AI 行动发起，遭遇检测必须跟着权威轨迹走；否则「统一移动引擎」的
     * 代价是把途中交互整条能力删掉。半径按米给（沙盘旧口径 30 格 ≈ 6 米）。</p>
     *
     * <p>零阻塞：本方法只做「找出附近有谁 + 交给异步判定」，AI 判定在虚拟线程里跑；
     * 任何异常就地吞掉，绝不中断 tick。</p>
     */
    private void maybeEncounterAuthority(Long projectId, String moverRef, ActorNpcPosition pos,
                                         Map<String, ActorNpcPosition> positions, long gameSecond) {
        if (moveEncounterService == null || pos == null || moverRef == null) {
            return;
        }
        try {
            double r = props.getEncounterRadiusMeters() > 0 ? props.getEncounterRadiusMeters() : 6.0;
            double x = pos.getX() == null ? 0d : pos.getX();
            double z = pos.getZ() == null ? 0d : pos.getZ();
            List<String> nearby = new ArrayList<>();
            for (ActorNpcPosition other : positions.values()) {
                if (other == null || other.getNpcRef() == null || moverRef.equals(other.getNpcRef())) {
                    continue;
                }
                if (other.getX() == null || other.getZ() == null) {
                    continue;
                }
                if (Math.hypot(other.getX() - x, other.getZ() - z) <= r) {
                    nearby.add(other.getNpcRef());
                }
            }
            if (nearby.isEmpty()) {
                return;
            }
            moveEncounterService.maybeEncounterWith(projectId, moverRef, nearby, gameSecond);
        } catch (Exception e) {
            log.warn("[mapnav] 途中交互触发失败（隔离继续）：project={} ref={} : {}",
                    projectId, moverRef, e.getMessage());        }
    }

    /**
     * AI 意图驻留窗口（游戏分钟）。{@code AgentActionService} 用它算「到站后还能待多久」；
     * 0 = 关闭驻留（日程立刻可以抢人，退回之前的旧口径）。
     */
    public long intentDwellGameMinutes() {
        return Math.max(0, props.getIntentDwellGameMinutes());
    }

    /** 本段落点：建筑优先用门口坐标（人能真的走到门里，而不是走到房子中心）。 */    private double[] spotOf(ActorNpcRoutine seg, Map<String, ActorMapFeature> features) {
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
    private static String toJson(List<double[]> pts) {        StringBuilder sb = new StringBuilder(pts.size() * 18 + 4);
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
    private static List<double[]> parse(String json) {        List<double[]> pts = new ArrayList<>();
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

    /**
     * 折线长度（米）。
     *
     * <p><b>为什么要在这里重算</b>：{@link #toJson} 会把每个顶点压到两位小数，压完之后折线总长
     * 与寻路器在原始精度上量出的长度**不再相等**。前端归一化用的是「读到的那条折线」的长度，
     * 因此权威侧也必须用同一条（已压精度的）折线来量——否则两侧的归一化因子不同源，
     * 权威点与渲染点会沿轨迹按比例偏开。三方对账把这条定为硬约束。</p>
     *
     * @param pts 折线
     *
     * @return 长度（米；不足两点返回 0）
     */
    static double polylineLength(List<double[]> pts) {
        if (pts == null || pts.size() < 2) {
            return 0;
        }
        double sum = 0;
        for (int i = 1; i < pts.size(); i++) {
            double[] a = pts.get(i - 1);
            double[] b = pts.get(i);
            sum += Math.hypot(b[0] - a[0], b[1] - a[1]);
        }
        return sum;
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
