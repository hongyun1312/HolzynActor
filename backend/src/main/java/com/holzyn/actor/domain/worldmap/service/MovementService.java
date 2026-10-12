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

import com.holzyn.actor.domain.sandbox.entity.ActorSandboxScene;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxState;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxSceneRepository;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxStateRepository;
import com.holzyn.actor.domain.sandbox.service.WorldSandboxStateService;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.worldmap.config.WorldMapProperties;
import com.holzyn.actor.domain.worldmap.entity.ActorMovement;
import com.holzyn.actor.domain.worldmap.repository.ActorMovementRepository;
import com.holzyn.actor.domain.worldmap.vo.MovementVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 连续移动状态机服务（位置移动与地图生成系统 · M2）——真实移动统一入口。
 * <p>职责（开发路径 §4.4）：① 移动发起 {@link #startMove}——目标地点带区域中心则用区域中心、
 * 否则哈希派生兜底（fail-open）；写 actor_movement（walking）+ SSE npc:walking + 沙盘 moveState=walking；
 * ② 引擎 tick 挂接 {@link #tick}——对在途移动按当前游戏秒线性插值推进
 * {@code ActorSandboxState.posX/posZ/heading}（坐标权威仍是沙盘状态行），到点 → arrived → 落位 +
 * SSE npc:arrived + 到达挂钩（刺激③ {@code RealtimeDecisionService.onNpcApproach}）+ moveState 复位；
 * ③ 打断 {@link #cancelMove}——status=cancelled，坐标停在打断点；④ 读接口 {@link #listMovements}。</p>
 * <p><b>路程换算</b>（纯静态可单测）：{@code 路程游戏秒 = 直线距离(格) × 格距(m/格) ÷ 步行速度(米/游戏秒)}；
 * 格距 0.2（与 Unity CoordinateTransformer 默认 meterPerCell 一致），速度读配置 walk-speed（默认 0.05）。</p>
 * <p><b>坐标域</b>：移动表/插值/Sse 用<b>沙盘中心化坐标</b>（与 ActorSandboxState.posX/posZ 一致，
 * 前端沙盘 SSE 一直消费中心化坐标）；目标区域中心（M1 地图坐标 [0, scene_size)）读入后转中心化
 * （−size/2），哈希派生兜底 {@code derivePosition} 本身已中心化。</p>
 * <p>⚠️ 本服务<b>零 AI</b>（纯机制插值），可带短事务；SSE/到达挂钩失败隔离不影响落库。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MovementService {

    /** 场景格距（米/格；与 Unity CoordinateTransformer 默认 meterPerCell=0.2 一致） */
    static final double METERS_PER_GRID = 0.2;

    /** 场景边长缺省 */
    static final int DEFAULT_SCENE_SIZE = 500;

    /** 已到目标判定阈值（格；距离 ≤ 该值视为已到，不发起移动直接到达） */
    static final double ARRIVE_EPSILON = 0.5;

    private final ActorMovementRepository movementRepository;
    private final ActorSandboxStateRepository sandboxStateRepository;
    private final ActorSandboxSceneRepository sceneRepository;
    private final WorldLocationRepository locationRepository;
    private final WorldMapProperties properties;

    /** 住户仓库（就寝回家：角色 → 住所映射） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.worldmap.repository.ActorHomeMemberRepository homeMemberRepository;

    /** 住所仓库（就寝回家：住所坐标/名称） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.worldmap.repository.ActorNpcHomeRepository homeRepository;

    /** 沙盘 SSE Hub（npc:walking/arrived 广播；@Lazy 破环——Hub→沙盘服务→本服务，null=未装配跳过） */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.sandbox.service.WorldSandboxSseHub sandboxHub;

    /** 实时行为决策服务（到达挂钩刺激③ onNpcApproach；@Lazy 破环，null=未装配跳过） */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.RealTimeDecisionService realtimeDecisionService;

    /** 世界时钟（listMovements 进度计算用当前游戏秒；null=未装配进度给 0） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.world.service.WorldClockService worldClockService;

    /**
     * 世界事件总线（M3 移动事件/编年史落库：移动开始/到达/途经 kind=move_to/walk/pass_by；
     * @Lazy 破环——事件总线旁路订阅链可能经认知注意力回依赖本服务，null=未装配跳过事件落库）
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.event.WorldEventBus worldEventBus;

    /** 项目仓库（事件归属 userId 解析；null=未装配回退 1L） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.project.repository.ActorProjectRepository projectRepository;

    /** 当前用户提供器（事件归属 userId 兜底；null=未装配回退 1L） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.account.CurrentUserProvider currentUserProvider;

    /**
     * 途中可遇可交互服务（M3 §4.6：移动者途中迎面遇到他角色 → AI 判定是否停下搭话；
     * @Lazy 破环——本服务→MoveEncounterService→沙盘 Hub→本服务，null=未装配跳过途中交互）
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MoveEncounterService moveEncounterService;

    /** 世界地图只读门面（M3 途中遭遇检测附近角色用；null=未装配跳过） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorldMapQueryService worldMapQueryService;

    // ==================== 静态纯逻辑（可单测） ====================

    /**
     * 路程换算（纯静态）：直线距离（格）× 格距（0.2 m/格）÷ 步行速度（米/游戏秒）。
     *
     * @param distGrids 直线距离（格，>=0）
     *
     * @param speedMps 步行速度（米/游戏秒；<=0 回退 0.05）
     *
     * @return 路程游戏秒（>=1；距离 0 → 1 秒占位）
     */
    public static long travelGameSeconds(double distGrids, double speedMps) {
        double speed = speedMps > 0 ? speedMps : 0.05;
        double secs = distGrids * METERS_PER_GRID / speed;
        return Math.max(1L, Math.round(secs));
    }

    /**
     * 线性插值（纯静态）：progress ∈ [0,1]，钳制越界。
     *
     * @param from [x, z]
     *
     * @param to [x, z]
     *
     * @param progress 进度（0=起点，1=终点）
     *
     * @return [x, z] 插值点
     */
    public static double[] interpolate(double[] from, double[] to, double progress) {
        double t = Math.max(0d, Math.min(1d, progress));
        double x = from[0] + (to[0] - from[0]) * t;
        double z = from[1] + (to[1] - from[1]) * t;
        return new double[]{x, z};
    }

    /**
     * 朝向（弧度，纯静态）：从 (fromX,fromZ) 指向 (toX,toZ)；同点回退 0。
     */
    public static double headingTo(double fromX, double fromZ, double toX, double toZ) {
        return Math.atan2(toZ - fromZ, toX - fromX);
    }

    /**
     * 地图坐标（[0, scene_size)）→ 沙盘中心化坐标（−size/2 偏移）。
     */
    static double mapToSandbox(double mapCoord, int sceneSize) {
        return mapCoord - (sceneSize > 0 ? sceneSize : DEFAULT_SCENE_SIZE) / 2.0;
    }

    /**
     * 沙盘中心化坐标 → 地图坐标（[0, scene_size)）。
     */
    static double sandboxToMap(double sandboxCoord, int sceneSize) {
        return sandboxCoord + (sceneSize > 0 ? sceneSize : DEFAULT_SCENE_SIZE) / 2.0;
    }

    // ==================== 移动发起（统一入口） ====================

    /**
     * 发起真实移动（四触发源统一入口：行动决策 / 日常计划 / 生理驱动 / 就寝回家 + 沙盘干预）。
     * <p>目标坐标解析：目标地点有区域中心（{@code ActorWorldLocation.centerX/centerZ}）→ 用区域中心
     * （地图坐标转中心化）；否则回退 {@link WorldSandboxStateService#derivePosition}（哈希派生，
     * 已中心化）。起点 = 角色当前沙盘坐标（无行则哈希派生）。</p>
     * <p>判定：直线距离 ≤ {@link #ARRIVE_EPSILON}（已在目标）→ 不写移动，直接落位 + SSE arrived +
     * 到达挂钩（等价到达）；否则写 actor_movement（walking）+ SSE npc:walking + 沙盘 moveState=walking。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用（统一 {@code npc:{id}}）
     *
     * @param npcType NPC 类型（special/common；沙盘行定型用，可空=读既有行）
     *
     * @param reason 触发源（plan/decision/physio/home/sandbox）
     *
     * @param targetLocationId 目标地点 ID（可空=坐标直行/哈希派生）
     *
     * @param targetLocationName 目标地点名（可空）
     *
     * @param gameSecond 出发游戏秒（可空=0）
     *
     * @return 移动行（直接到达时 status=arrived；失败返回 null）
     */
    public ActorMovement startMove(Long projectId, String npcRef, String npcType, String reason,
                                   Long targetLocationId, String targetLocationName, Long gameSecond) {
        if (projectId == null || npcRef == null || npcRef.isBlank() || reason == null || reason.isBlank()) {
            return null;
        }
        int sceneSize = sceneSizeOf(projectId);
        ActorSandboxState st = ensureSandboxState(projectId, npcType, npcRef);
        double[] from = new double[]{
                st.getPosX() == null ? 0d : st.getPosX(),
                st.getPosZ() == null ? 0d : st.getPosZ()};
        double[] to = resolveTargetPosition(projectId, targetLocationId, targetLocationName, sceneSize);
        return startMoveTo(projectId, npcRef, from, to, reason,
                targetLocationId, targetLocationName, gameSecond == null ? 0L : gameSecond);
    }

    /**
     * 显式起终点的移动发起（内部统一落点；含直接到达快捷路径）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用
     *
     * @param from [x, z] 起点（沙盘中心化坐标）
     *
     * @param to [x, z] 终点（沙盘中心化坐标）
     *
     * @param reason 触发源
     *
     * @param targetLocationId 目标地点 ID（可空）
     *
     * @param targetLocationName 目标地点名（可空）
     *
     * @param gameSecond 出发游戏秒
     *
     * @return 移动行
     */
    public ActorMovement startMoveTo(Long projectId, String npcRef, double[] from, double[] to,
                                     String reason, Long targetLocationId, String targetLocationName,
                                     Long gameSecond) {
        if (projectId == null || npcRef == null || npcRef.isBlank() || from == null || to == null) {
            return null;
        }
        long gs = gameSecond == null ? 0L : gameSecond;
        double dist = Math.hypot(to[0] - from[0], to[1] - from[1]);
        // 已在目标（距离 ≤ 阈值）→ 直接到达（等价「原地不动」；同时取消该角色残余在途行走）
        if (dist <= ARRIVE_EPSILON) {
            cancelMove(projectId, npcRef, gs);
            arriveAndSettle(projectId, npcRef, to, reason, targetLocationId, targetLocationName, gs);
            return null;
        }
        long travelSec = travelGameSeconds(dist, properties.getWalkSpeedGameMetersPerSecond());
        long arriveSec = gs + travelSec;
        // 打断与恢复（开发路径 §4.4）：同一角色至多一条在途移动——已有 walking 行则复用更新
        // （新行动重定向目标，从打断点续走；防并行 walking 刷屏/坐标争写）；无则新建。
        ActorMovement m = movementRepository.findFirstByProjectIdAndCharacterRefAndStatusOrderByIdDesc(
                projectId, npcRef, ActorMovement.STATUS_WALKING).orElse(null);
        boolean reused = m != null;
        if (!reused) {
            m = new ActorMovement();
            m.setProjectId(projectId);
            m.setCharacterRef(npcRef);
        }
        m.setFromX(from[0]);
        m.setFromZ(from[1]);
        m.setToX(to[0]);
        m.setToZ(to[1]);
        m.setFromGameSecond(gs);
        m.setArriveGameSecond(arriveSec);
        m.setSpeed(properties.getWalkSpeedGameMetersPerSecond());
        m.setStatus(ActorMovement.STATUS_WALKING);
        m.setReason(reason);
        m.setTargetLocationId(targetLocationId);
        m.setTargetLocationName(truncate(targetLocationName, 100));
        ActorMovement saved = movementRepository.save(m);
        // 沙盘状态：moveState=walking（权威坐标由 tick 推进）
        setSandboxWalking(projectId, npcRef, from, to, gs, reason);
        publishWalking(projectId, npcRef, from, to, 0d, arriveSec, reason);
        // 位置移动与地图生成系统 · M3：移动开始 → 事件/编年史落库（kind=move_to；失败隔离）
        publishMoveEvent(projectId, npcRef, m.getTargetLocationName(), m.getTargetLocationId(),
                "move_to", "开始",
                "前往" + (m.getTargetLocationName() == null || m.getTargetLocationName().isBlank()
                        ? "目的地" : m.getTargetLocationName()),
                "move:" + saved.getId() + ":start", gs);
        log.debug("[移动] 发起{}：project={} ref={} reason={} {}→{} dist={}格 路程={}s 到点={}",
                reused ? "（重定向既有）" : "", projectId, npcRef, reason, fmt(from), fmt(to),
                Math.round(dist), travelSec, arriveSec);
        return saved;
    }

    // ==================== 引擎 tick 挂接 ====================

    /**
     * 引擎每 tick（5 游戏分钟）调用：对在途移动按当前游戏秒线性插值推进沙盘坐标。
     * <p>到点（当前秒 ≥ 预计到达秒）→ arrived → 落位 + SSE npc:arrived + 到达挂钩（刺激③）
     * + moveState 复位；未到点 → 更新 pos/heading（SSE npc:walking 带进度，限频由调用粒度承载）。
     * 失败隔离：单行异常不影响其余行；开关关闭（tick-update-enabled=false）时不推进。</p>
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 当前游戏秒（引擎推进位置）
     */
    public void tick(Long projectId, Long gameSecond) {
        if (projectId == null || !properties.isTickUpdateEnabled() || gameSecond == null) {
            return;
        }
        List<ActorMovement> walking = movementRepository.findByProjectIdAndStatusOrderByIdAsc(
                projectId, ActorMovement.STATUS_WALKING);
        if (walking.isEmpty()) {
            return;
        }
        for (ActorMovement m : walking) {
            try {
                if (gameSecond >= m.getArriveGameSecond()) {
                    arrive(m, gameSecond);
                } else {
                    double progress = progressOf(m, gameSecond);
                    double[] pos = interpolate(
                            new double[]{m.getFromX(), m.getFromZ()},
                            new double[]{m.getToX(), m.getToZ()}, progress);
                    updateSandboxPos(projectId, m.getCharacterRef(), pos,
                            headingTo(m.getFromX(), m.getFromZ(), m.getToX(), m.getToZ()), gameSecond);
                    publishWalking(projectId, m.getCharacterRef(),
                            new double[]{m.getFromX(), m.getFromZ()},
                            new double[]{m.getToX(), m.getToZ()}, progress,
                            m.getArriveGameSecond(), m.getReason());
                    // 位置移动与地图生成系统 · M3：途经事件（kind=walk）按进度里程碑 25/50/75%
                    // 限频落库（每段移动至多 3 条，防事件刷屏；失败隔离）
                    publishWalkMilestone(projectId, m, progress, gameSecond);
                    // 位置移动与地图生成系统 · M3：途中可遇可交互（§4.6）——移动者当前位置
                    // 附近有其他角色 → MoveEncounterService 异步 AI 判定是否停下搭话
                    // （冷却/预算内；失败 fail-open 继续走；零阻塞引擎热路径）
                    if (moveEncounterService != null && worldMapQueryService != null) {
                        try {
                            moveEncounterService.maybeEncounter(projectId, m.getCharacterRef(),
                                    pos[0], pos[1], gameSecond);
                        } catch (Exception e) {
                            log.warn("[移动] 途中交互触发失败（隔离继续）: ref={} : {}",
                                    m.getCharacterRef(), e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[移动] tick 推进失败（隔离继续）: id={} ref={} : {}", m.getId(), m.getCharacterRef(), e.getMessage());
            }
        }
    }

    /** 途经里程碑 walk 事件（M3：进度跨过 25/50/75% 各落一条，sourceKey 幂等防重复）。 */
    private void publishWalkMilestone(Long projectId, ActorMovement m, double progress, Long gameSecond) {
        int mile = (int) Math.floor(progress * 4); // 0..3（25% 一档）
        if (mile <= 0 || mile >= 4) {
            return;
        }
        String target = m.getTargetLocationName() == null || m.getTargetLocationName().isBlank()
                ? "目的地" : m.getTargetLocationName();
        publishMoveEvent(projectId, m.getCharacterRef(), m.getTargetLocationName(),
                m.getTargetLocationId(), "walk", "途中",
                "正在前往" + target + "的路上（约走完 " + (mile * 25) + "%）",
                "move:" + m.getId() + ":walk:" + mile, gameSecond);
    }

    /** 到点处理：落位 + arrived + SSE + 到达挂钩 + moveState 复位。 */
    private void arrive(ActorMovement m, Long gameSecond) {
        m.setStatus(ActorMovement.STATUS_ARRIVED);
        movementRepository.save(m);
        double[] to = new double[]{m.getToX(), m.getToZ()};
        // 位置移动与地图生成系统 · M3：移动到达 → 事件/编年史落库（kind=move_to；失败隔离）
        publishMoveEvent(m.getProjectId(), m.getCharacterRef(), m.getTargetLocationName(),
                m.getTargetLocationId(), "move_to", "到达",
                "到达" + (m.getTargetLocationName() == null || m.getTargetLocationName().isBlank()
                        ? "目的地" : m.getTargetLocationName()),
                "move:" + m.getId() + ":arrive", gameSecond);
        arriveAndSettle(m.getProjectId(), m.getCharacterRef(), to, m.getReason(),
                m.getTargetLocationId(), m.getTargetLocationName(), gameSecond);
    }

    /** 落位 + SSE arrived + 到达挂钩 + moveState 复位（直接到达与 tick 到点共用）。 */
    private void arriveAndSettle(Long projectId, String npcRef, double[] to, String reason,
                                 Long targetLocationId, String targetLocationName, Long gameSecond) {
        ActorSandboxState st = sandboxStateRepository.findByProjectIdAndNpcRef(projectId, npcRef).orElse(null);
        if (st == null) {
            return;
        }
        st.setPosX(to[0]);
        st.setPosZ(to[1]);
        st.setHeading(headingTo(st.getPosX() == null ? 0d : st.getPosX(),
                st.getPosZ() == null ? 0d : st.getPosZ(), to[0], to[1]));
        st.setMoveState("idle");
        if (gameSecond != null) {
            st.setGameSecond(gameSecond);
        }
        sandboxStateRepository.save(st);
        try {
            if (sandboxHub != null) {
                sandboxHub.publishNpcArrived(projectId, npcRef, new double[]{to[0], 0d, to[1]}, st.getHeading());
                sandboxHub.publishNpcState(projectId, npcRef, st.getMoveState(),
                        st.getActionText(), st.getStatusText(), st.getGameSecond());
            }
        } catch (Exception e) {
            log.warn("[移动] 到达广播失败（不影响落库）: ref={} : {}", npcRef, e.getMessage());
        }
        // 到达挂钩：刺激③「他角色靠近/到达」（移动者自身不触发，人群不触发；失败隔离）
        try {
            if (realtimeDecisionService != null && targetLocationName != null && !targetLocationName.isBlank()) {
                realtimeDecisionService.onNpcApproach(projectId, npcRef, targetLocationName, gameSecond);
            }
        } catch (Exception e) {
            log.warn("[移动] 到达挂钩失败（不影响落库）: ref={} : {}", npcRef, e.getMessage());
        }
        log.debug("[移动] 到达：project={} ref={} reason={} pos=({},{})", projectId, npcRef, reason,
                Math.round(to[0]), Math.round(to[1]));
    }

    // ==================== 打断 / 取消 ====================

    /**
     * 打断移动（即时决策 / 新行动抢占）：status=cancelled，坐标停在打断点（当前插值位置），
     * moveState 复位 idle。无在途移动则空操作。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用
     *
     * @param gameSecond 打断时刻游戏秒（可空=不更新坐标）
     */
    public void cancelMove(Long projectId, String npcRef, Long gameSecond) {
        if (projectId == null || npcRef == null || npcRef.isBlank()) {
            return;
        }
        movementRepository.findFirstByProjectIdAndCharacterRefAndStatusOrderByIdDesc(
                projectId, npcRef, ActorMovement.STATUS_WALKING).ifPresent(m -> {
            double[] stop;
            if (gameSecond != null && m.getFromGameSecond() != null && m.getArriveGameSecond() != null
                    && m.getArriveGameSecond() > m.getFromGameSecond()) {
                stop = interpolate(new double[]{m.getFromX(), m.getFromZ()},
                        new double[]{m.getToX(), m.getToZ()}, progressOf(m, gameSecond));
            } else {
                stop = new double[]{m.getFromX(), m.getFromZ()};
            }
            m.setStatus(ActorMovement.STATUS_CANCELLED);
            movementRepository.save(m);
            ActorSandboxState st = sandboxStateRepository.findByProjectIdAndNpcRef(projectId, npcRef).orElse(null);
            if (st != null) {
                st.setPosX(stop[0]);
                st.setPosZ(stop[1]);
                st.setMoveState("idle");
                sandboxStateRepository.save(st);
            }
            log.debug("[移动] 打断：project={} ref={} 停在 ({},{})", projectId, npcRef, Math.round(stop[0]), Math.round(stop[1]));
        });
    }

    // ==================== 就寝回家（触发源④） ====================

    /**
     * 就寝回家（开发路径 §4.3：就寝时若当前所在地 ≠ 住所 → 程序化触发「回家」移动；
     * 到家后 activity_state=rest 由既有睡眠挂钩/作息状态机处理，本方法只负责移动发起）。
     * <p>目标 = 角色住所坐标（actor_home_member → actor_npc_home；住所 posX/posZ 为地图坐标
     * [0, scene_size)，读入转中心化）；已在住所（距离 ≤ 阈值）→ 不发起（已在家的角色直接歇息）。
     * 无住所/仓库未装配 → 空操作（fail-open，不打断既有「在驻地歇息」行为）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用
     *
     * @param npcType NPC 类型（可空）
     *
     * @param gameSecond 当前游戏秒
     */
    public void goHome(Long projectId, String npcRef, String npcType, Long gameSecond) {
        if (projectId == null || npcRef == null || npcRef.isBlank()
                || homeMemberRepository == null || homeRepository == null) {
            return;
        }
        try {
            com.holzyn.actor.domain.worldmap.entity.ActorHomeMember member =
                    homeMemberRepository.findByProjectIdAndCharacterRef(projectId, npcRef).orElse(null);
            if (member == null) {
                return;
            }
            com.holzyn.actor.domain.worldmap.entity.ActorNpcHome home =
                    homeRepository.findById(member.getHomeId()).orElse(null);
            if (home == null || home.getPosX() == null || home.getPosZ() == null
                    || com.holzyn.actor.domain.worldmap.entity.ActorNpcHome.STATUS_ABANDONED.equals(home.getStatus())) {
                return;
            }
            int sceneSize = sceneSizeOf(projectId);
            double[] to = new double[]{mapToSandbox(home.getPosX(), sceneSize), mapToSandbox(home.getPosZ(), sceneSize)};
            ActorSandboxState st = sandboxStateRepository.findByProjectIdAndNpcRef(projectId, npcRef).orElse(null);
            double[] from = st == null || st.getPosX() == null
                    ? new double[]{0d, 0d} : new double[]{st.getPosX(), st.getPosZ()};
            // 已在住所 → 不发起（避免深夜反复发起回家移动刷屏）
            if (Math.hypot(to[0] - from[0], to[1] - from[1]) <= ARRIVE_EPSILON) {
                return;
            }
            startMoveTo(projectId, npcRef, from, to, ActorMovement.REASON_HOME,
                    home.getLocationId(), home.getName(), gameSecond == null ? 0L : gameSecond);
        } catch (Exception e) {
            log.warn("[移动] 就寝回家发起失败（隔离继续）: ref={} : {}", npcRef, e.getMessage());
        }
    }

    // ==================== 读接口 ====================

    /**
     * 在途判定（沙盘状态同步防覆盖用）：该角色当前是否有 walking 移动行。
     * <p>世界沙盘 ensureState/syncCrowdStates 按 location 名派生坐标会<b>覆盖</b>插值中的
     * 移动坐标——在途角色必须跳过派生（坐标权威在 MovementService.tick），本方法供其守卫。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用
     *
     * @return true=在途（walking 行存在）
     */
    public boolean isInTransit(Long projectId, String npcRef) {
        if (projectId == null || npcRef == null || npcRef.isBlank()) {
            return false;
        }
        try {
            return movementRepository.existsByProjectIdAndCharacterRefAndStatus(
                    projectId, npcRef, ActorMovement.STATUS_WALKING);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 单角色在途移动行（M3 感知段【当前位置与方向】用）：该角色当前 walking 移动行（可空）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用
     *
     * @return 在途移动行；无/异常返回 null
     */
    public ActorMovement inTransitMove(Long projectId, String npcRef) {
        if (projectId == null || npcRef == null || npcRef.isBlank()) {
            return null;
        }
        try {
            return movementRepository.findFirstByProjectIdAndCharacterRefAndStatusOrderByIdDesc(
                    projectId, npcRef, ActorMovement.STATUS_WALKING).orElse(null);
        } catch (Exception e) {
            log.warn("[移动] 在途行查询失败（返回空）: ref={} : {}", npcRef, e.getMessage());
            return null;
        }
    }

    /**
     * 当前在途移动列表（GET /movements；供沙盘/新首页渲染移动中角色）。
     *
     * @param projectId 项目 ID
     *
     * @return 在途移动 VO 列表（含进度；无在途返回空列表）
     */
    public List<MovementVO> listMovements(Long projectId) {
        List<ActorMovement> walking = movementRepository.findByProjectIdAndStatusOrderByIdAsc(
                projectId, ActorMovement.STATUS_WALKING);
        Long currentSec = currentGameSecond(projectId);
        List<MovementVO> out = new ArrayList<>(walking.size());
        for (ActorMovement m : walking) {
            MovementVO vo = new MovementVO();
            vo.setProjectId(projectId);
            vo.setNpcRef(m.getCharacterRef());
            vo.setFromX(m.getFromX());
            vo.setFromZ(m.getFromZ());
            vo.setToX(m.getToX());
            vo.setToZ(m.getToZ());
            vo.setFromGameSecond(m.getFromGameSecond());
            vo.setArriveGameSecond(m.getArriveGameSecond());
            vo.setProgress(currentSec == null ? 0d : progressOf(m, currentSec));
            vo.setSpeed(m.getSpeed());
            vo.setReason(m.getReason());
            vo.setTargetLocationId(m.getTargetLocationId());
            vo.setTargetLocationName(m.getTargetLocationName());
            out.add(vo);
        }
        return out;
    }

    // ==================== 内部工具 ====================

    /** 当前游戏秒（世界时钟引擎推进位置；失败/未装配返回 null）。 */
    private Long currentGameSecond(Long projectId) {
        try {
            return worldClockService == null ? null : worldClockService.currentEngineGameSecond(projectId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 移动进度（纯静态，可单测）：(当前秒−出发秒)/(到达秒−出发秒)，钳制 [0,1]。 */
    public static double progressOf(ActorMovement m, long gameSecond) {
        if (m == null || m.getFromGameSecond() == null || m.getArriveGameSecond() == null
                || m.getArriveGameSecond() <= m.getFromGameSecond()) {
            return 0d;
        }
        long span = m.getArriveGameSecond() - m.getFromGameSecond();
        return Math.max(0d, Math.min(1d, (double) (gameSecond - m.getFromGameSecond()) / span));
    }

    /** 目标坐标解析：区域中心（地图坐标转中心化）→ 哈希派生兜底。 */
    private double[] resolveTargetPosition(Long projectId, Long targetLocationId,
                                           String targetLocationName, int sceneSize) {
        if (targetLocationId != null && locationRepository != null) {
            try {
                ActorWorldLocation loc = locationRepository.findById(targetLocationId).orElse(null);
                if (loc != null && loc.getCenterX() != null && loc.getCenterZ() != null) {
                    return new double[]{mapToSandbox(loc.getCenterX(), sceneSize),
                            mapToSandbox(loc.getCenterZ(), sceneSize)};
                }
            } catch (Exception e) {
                log.warn("[移动] 目标区域坐标解析失败（哈希派生兜底）: loc={} : {}", targetLocationId, e.getMessage());
            }
        }
        double[] pos = WorldSandboxStateService.derivePosition(targetLocationName, sceneSize);
        return new double[]{pos[0], pos[2]};
    }

    /** 惰性建/取沙盘状态行（移动起点权威；无行则按目标地点哈希派生兜底）。 */
    private ActorSandboxState ensureSandboxState(Long projectId, String npcType, String npcRef) {
        ActorSandboxState st = sandboxStateRepository.findByProjectIdAndNpcRef(projectId, npcRef).orElse(null);
        if (st != null) {
            return st;
        }
        ActorSandboxState created = new ActorSandboxState();
        created.setProjectId(projectId);
        created.setSceneId(sceneIdOf(projectId));
        created.setNpcType(npcType == null || npcType.isBlank()
                ? com.holzyn.actor.common.NpcRefs.TYPE_SPECIAL : npcType);
        created.setNpcRef(npcRef);
        return sandboxStateRepository.save(created);
    }

    /** 沙盘状态置 walking（坐标留起点，由 tick 插值推进）。 */
    private void setSandboxWalking(Long projectId, String npcRef, double[] from, double[] to,
                                   Long gameSecond, String reason) {
        ActorSandboxState st = sandboxStateRepository.findByProjectIdAndNpcRef(projectId, npcRef).orElse(null);
        if (st == null) {
            return;
        }
        st.setPosX(from[0]);
        st.setPosZ(from[1]);
        st.setHeading(headingTo(from[0], from[1], to[0], to[1]));
        st.setMoveState("walking");
        if (gameSecond != null) {
            st.setGameSecond(gameSecond);
        }
        sandboxStateRepository.save(st);
    }

    /** 更新沙盘坐标（tick 插值写回）。 */
    private void updateSandboxPos(Long projectId, String npcRef, double[] pos, double heading, Long gameSecond) {
        ActorSandboxState st = sandboxStateRepository.findByProjectIdAndNpcRef(projectId, npcRef).orElse(null);
        if (st == null) {
            return;
        }
        st.setPosX(pos[0]);
        st.setPosZ(pos[1]);
        st.setHeading(heading);
        if (gameSecond != null) {
            st.setGameSecond(gameSecond);
        }
        sandboxStateRepository.save(st);
    }

    /** SSE npc:walking 广播（失败隔离）。 */
    private void publishWalking(Long projectId, String npcRef, double[] from, double[] to,
                                double progress, Long arriveGameSecond, String reason) {
        try {
            if (sandboxHub != null) {
                sandboxHub.publishNpcWalking(projectId, npcRef, new double[]{from[0], 0d, from[1]},
                        new double[]{to[0], 0d, to[1]}, progress, arriveGameSecond, reason);
            }
        } catch (Exception e) {
            log.warn("[移动] npc:walking 广播失败（不影响落库）: ref={} : {}", npcRef, e.getMessage());
        }
    }

    /**
     * 移动事件/编年史落库（M3 §4.7）：移动开始/到达/途经 → {@code actor_event}
     * （kind=move_to/walk/pass_by，source=movement，visibility=location——同地点在场者可见）。
     * <p>事件总线未装配/项目非法/异常 → 静默跳过（零 AI 纯机制，失败隔离不影响落库）。
     * sourceKey 幂等：move:{id}:start / move:{id}:arrive / move:{id}:walk:{gs} / move:{id}:pass:{ref}。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 移动者引用
     *
     * @param targetLocationName 目标地点名（可空）
     *
     * @param targetLocationId 目标地点 ID（可空）
     *
     * @param kind 事件类别（move_to/walk/pass_by）
     *
     * @param phase 阶段描述（开始/到达/途中/经过）
     *
     * @param content 事件内容
     *
     * @param sourceKey 幂等键（可空=不幂等）
     *
     * @param gameSecond 游戏时刻（秒，可空）
     */
    private void publishMoveEvent(Long projectId, String npcRef, String targetLocationName,
                                  Long targetLocationId, String kind, String phase, String content,
                                  String sourceKey, Long gameSecond) {
        if (npcRef == null) {
            return;
        }
        publishMoveEvent(projectId, java.util.List.of(npcRef), targetLocationName, targetLocationId,
                kind, phase, content, sourceKey, gameSecond);
    }

    /**
     * 移动事件/编年史落库（多当事人版本）：{@code refs} 为全部在场当事人引用
     * （单移动者事件传 1 个；途中迎面相遇 pass_by 传双方，修 相关度只评估在场 NPC）。
     * 其余口径同单当事人版本。
     */
    private void publishMoveEvent(Long projectId, java.util.List<String> refs, String targetLocationName,
                                  Long targetLocationId, String kind, String phase, String content,
                                  String sourceKey, Long gameSecond) {
        if (worldEventBus == null || projectId == null || refs == null || refs.isEmpty()) {
            return;
        }
        try {
            Long gameHour = gameSecond == null ? null : Math.floorDiv(gameSecond, 3600L);
            String target = targetLocationName == null || targetLocationName.isBlank()
                    ? null : targetLocationName;
            worldEventBus.publish(eventUserId(projectId), projectId,
                    com.holzyn.actor.domain.agent.event.EventSpec.builder()
                            .kind(kind)
                            .source("movement")
                            .characterRefs(refs)
                            .locationId(targetLocationId)
                            .locationName(target)
                            .visibility("location")
                            .title(refs.get(0) + "：" + phase + "移动"
                                    + (target == null ? "" : "（" + target + "）"))
                            .content(content)
                            .sourceKey(sourceKey)
                            .gameHour(gameHour)
                            .build());
        } catch (Exception e) {
            log.warn("[移动] 移动事件落库失败（隔离继续）: ref={} kind={} : {}", refs.get(0), kind, e.getMessage());
        }
    }

    /**
     * 途中经过事件（M3 §4.7：kind=pass_by——移动者途中经过他角色/与他角色相遇时落库）。
     * <p>由 {@code MoveEncounterService} 途中遭遇检测命中时调用；事件总线未装配/异常 →
     * 静默跳过（失败隔离）。sourceKey=move:{movementId}:pass:{otherRef}（同对角色幂等）。</p>
     * <p><b>迎面相遇双方都是当事人（在场，修：相关度评估全 NPC）</b>：{@code characterRefs}
     * 记录双方（移动者 + 被经过角色），供事件×NPC 相关度只评估在场 NPC。</p>
     *
     * @param projectId 项目 ID
     *
     * @param moverRef 移动者引用
     *
     * @param movementId 在途移动行 ID（可空=未知）
     *
     * @param otherRef 被经过/相遇的角色引用
     *
     * @param otherName 被经过角色名（可空）
     *
     * @param gameSecond 游戏时刻（秒，可空）
     */
    public void publishPassBy(Long projectId, String moverRef, Long movementId, String otherRef,
                              String otherName, Long gameSecond) {
        if (worldEventBus == null || projectId == null || moverRef == null || otherRef == null) {
            return;
        }
        String other = otherName == null || otherName.isBlank() ? otherRef : otherName.trim();
        String target = "途经";
        // 双方当事人在场：characterRefs 记录移动者 + 被经过角色
        java.util.List<String> refs = new java.util.ArrayList<>();
        refs.add(moverRef);
        refs.add(otherRef);
        publishMoveEvent(projectId, refs, target, null, "pass_by", "经过",
                "途中经过/迎面遇到" + other,
                "move:" + (movementId == null ? "?" : movementId) + ":pass:" + otherRef, gameSecond);
    }

    /**
     * 途中交互事件（M3 §4.7：kind=move_encounter——移动者途中停下搭话/招呼落库）。
     * <p>由 {@code MoveEncounterService} AI 判定结果调用；事件总线未装配/异常 → 静默跳过
     * （失败隔离）。sourceKey=encounter:{projectId}:{mover}:{other}:{hour}（同刻幂等）。</p>
     * <p><b>偶遇双方都是当事人（在场，修：相关度评估全 NPC）</b>：{@code characterRefs}
     * 记录双方（移动者 + 对方），供事件×NPC 相关度只评估在场 NPC——2 人偶遇只有
     * 双方当事人在场，其余 NPC 零评估（对齐 M3 §4.7「同地点在场者可见」口径）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param moverRef 移动者引用
     *
     * @param otherRef 对方角色引用
     *
     * @param otherName 对方角色名（可空）
     *
     * @param greet 招呼文本（可空）
     *
     * @param stop 是否停下搭话
     *
     * @param gameSecond 游戏时刻（秒，可空）
     */
    public void publishEncounterEvent(Long projectId, String moverRef, String otherRef, String otherName,
                                      String greet, boolean stop, Long gameSecond) {
        if (worldEventBus == null || projectId == null || moverRef == null) {
            return;
        }
        try {
            String other = otherName == null || otherName.isBlank()
                    ? (otherRef == null ? "路人" : otherRef) : otherName.trim();
            String content = (stop ? "停下与" + other + "搭话：" : "途中与" + other + "招呼：")
                    + (greet == null || greet.isBlank() ? "（点头致意）" : greet.trim());
            Long gameHour = gameSecond == null ? null : Math.floorDiv(gameSecond, 3600L);
            // 双方当事人在场：characterRefs 记录移动者 + 对方（去重；对方缺失时仅移动者）
            java.util.List<String> refs = new java.util.ArrayList<>();
            refs.add(moverRef);
            if (otherRef != null && !otherRef.isBlank()) {
                refs.add(otherRef);
            }
            worldEventBus.publish(eventUserId(projectId), projectId,
                    com.holzyn.actor.domain.agent.event.EventSpec.builder()
                            .kind("move_encounter")
                            .source("movement")
                            .characterRefs(refs)
                            .visibility("location")
                            .title(moverRef + "：途中交互（" + other + "）")
                            .content(content)
                            .sourceKey("encounter:" + projectId + ":" + moverRef + ":"
                                    + (otherRef == null ? "?" : otherRef) + ":" + gameHour)
                            .gameHour(gameHour)
                            .build());
        } catch (Exception e) {
            log.warn("[移动] 途中交互事件落库失败（隔离继续）: ref={} : {}", moverRef, e.getMessage());
        }
    }

    /** 事件归属 userId（项目 owner 优先，CurrentUserProvider 兜底，未装配回退 1L）。 */
    private Long eventUserId(Long projectId) {
        try {
            if (projectRepository != null && projectId != null) {
                Long owner = projectRepository.findById(projectId)
                        .map(com.holzyn.actor.domain.project.entity.ActorProject::getUserId).orElse(null);
                if (owner != null) {
                    return owner;
                }
            }
            if (currentUserProvider != null) {
                return currentUserProvider.currentUserId();
            }
        } catch (Exception e) {
            log.debug("[移动] 事件归属 userId 解析失败（回退 1L）: {}", e.getMessage());
        }
        return 1L;
    }

    /** 主场景 ID（无场景返回 null）。 */
    private Long sceneIdOf(Long projectId) {
        ActorSandboxScene scene = sceneRepository.findFirstByProjectIdOrderByIdAsc(projectId).orElse(null);
        return scene == null ? null : scene.getId();
    }

    /** 场景边长（无场景/非法用默认 500）。 */
    private int sceneSizeOf(Long projectId) {
        ActorSandboxScene scene = sceneRepository.findFirstByProjectIdOrderByIdAsc(projectId).orElse(null);
        return scene == null || scene.getSceneSize() == null || scene.getSceneSize() <= 0
                ? DEFAULT_SCENE_SIZE : scene.getSceneSize();
    }

    /** 文本截断（字段长度保护）。 */
    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 坐标格式化（调试日志）。 */
    private String fmt(double[] p) {
        return "(" + Math.round(p[0]) + "," + Math.round(p[1]) + ")";
    }
}
