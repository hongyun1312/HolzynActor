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

package com.holzyn.actor.domain.sandbox.service;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.npc.state.service.NpcActivityStateService;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxScene;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxState;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxSceneRepository;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxStateRepository;
import com.holzyn.actor.domain.sandbox.vo.SandboxOverviewVO;
import com.holzyn.actor.domain.sandbox.vo.SandboxSceneVO;
import com.holzyn.actor.domain.sandbox.vo.SandboxStateVO;
import com.holzyn.actor.domain.sandbox.vo.ScenePlacementVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 世界沙盘状态服务（世界沙盘 S1 + S2，domain/sandbox/service）——位置权威源 + 状态广播。
 * <p>职责（主开发方案 §10.1 S1 基础版 + S2 实时通道）：① 为项目内全部 NPC（特殊 + 普通人群）维护
 * actor_sandbox_state 状态行（惰性初始化）；② 坐标模拟：S1 无场景/锚点时用<b>地点名确定性派生坐标</b>
 * （同一地点名→同一坐标，落在场景内），后续轮 anchor_json 就位后升级为真锚点映射；
 * ③ move 行动挂钩：NPC 地点变化 → 更新 pos/heading/move_state/action_text/game_second 落库，
 * 并经 {@link WorldSandboxSseHub} 广播 npc:moved/npc:arrived/npc:state；
 * ④ 想法（npc:thought）：T0 派生 + T1 挂钩（反思/感知/决策写入，内容去重）；
 * ⑤ join/leave（NPC 创建/删除挂钩）；⑥ 读接口聚合（场景 + 状态）+ init 快照。</p>
 * <p>确定性设计：坐标派生为纯静态可测（同输入同输出），head 同源派生；派生不依赖随机。</p>
 * <p>广播去重原则：状态/想法仅在内容实际变化时广播，避免广播风暴。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldSandboxStateService {

    /** 默认场景边长（S1 无场景时的占位尺寸，1 块=1 米，最小 500） */
    public static final int DEFAULT_SCENE_SIZE = 500;

    /** 移动耗时占位（游戏秒，S1 无路径模拟按 10 秒；后续轮路径模拟升级为真实耗时） */
    static final long DEFAULT_MOVE_GAME_SEC = 10L;

    /** 场景边缘留白（米）：派生坐标远离边界，避免模型贴边 */
    static final double EDGE_MARGIN = 40.0;

    /** 坐标字段上限（对齐角色状态字段约束） */
    static final int FIELD_MAX = 255;

    private final ActorSandboxStateRepository stateRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRepository ordinaryNpcRepository;
    private final ActorSandboxSceneRepository sceneRepository;

    /**
     * 沙盘 SSE Hub（S2 广播；字段注入避免改构造器破坏存量单测，null=广播关闭；
     * @Lazy 破 Bean 环：本服务↔沙盘 Hub 双向字段引用（Hub 经 WorldEventBus→WorldClockService→本服务），
     * 运行时（发布事件时）经代理取真实 Bean）
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorldSandboxSseHub sandboxHub;

    /** 资产摆放服务（S3 overview 聚合；字段注入避免改构造器破坏存量单测，null=跳过摆放聚合） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ScenePlacementService scenePlacementService;

    /** NPC 状态服务 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.npc.state.service.NpcStateService npcStateService;

    /**
     * NPC 类型解析器（：npc:{id} 不再编码类型，惰性建行定型改查 actor_profile.type；
     * 字段注入避免改构造器破坏存量单测，null=回退特殊型——与历史 refTypeOf 行为一致）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NpcTypeResolver npcTypeResolver;

    /**
     * 作息运行态访问器（：state / last_action 已从 actor_profile 收敛到 actor_state，
     * 沙盘动作取该访问器；字段注入避免改构造器破坏存量单测，null=动作文本回退状态兜底）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NpcActivityStateService npcActivityStateService;

    /**
     * 连续移动状态机（位置移动与地图生成系统 · M2：在途判定守卫——角色有 walking 移动行时，
     * ensureState/syncCrowdStates 跳过按 location 派生坐标，避免覆盖 MovementService.tick
     * 的插值坐标；@Lazy 破潜在 Bean 环[经沙盘 Hub→世界时钟链路]，null=未装配跳过守卫，
     * 坐标派生保持既有行为）
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.worldmap.service.MovementService movementService;

    /** Jackson（S3 补全：overview 场景 VO 携带解析后的 layout_json；字段注入避免改构造器破坏存量单测） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private tools.jackson.databind.ObjectMapper objectMapper;

    /**
     * 实时行为决策服务（实时行为决策系统：onNpcMoved 挂钩刺激③「他角色靠近/到达」——
     * 到达目标地点后通知同地点其他特殊 NPC 即时决策；字段注入避免改构造器破坏存量单测，
     * @Lazy 破潜在 Bean 环（经 WorldEventBus→WorldClockService 链路），null=即时决策未装配跳过）
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.RealTimeDecisionService realtimeDecisionService;

    // ==================== 静态纯逻辑（可单测） ====================

    /**
     * 地点名确定性派生坐标（无锚点时的 S1 占位方案， 确认；S3.1 起坐标中心化）。
     * <p>同一地点名 → 同一坐标；空地点名回退「驻地」；坐标落在
     * [EDGE_MARGIN-size/2, size-EDGE_MARGIN-size/2]（如 500 → [-210, 210]），
     * **场景中心 (0,0)** 对齐 3D 渲染（500×500 地面中心在原点），y 恒 0（前期无高度）。</p>
     * <p>中心化实现：保留原 [EDGE_MARGIN, size-EDGE_MARGIN] 确定性派生后统一减 size/2——
     * 与存量行迁移（旧值 -size/2）结果完全一致，零漂移。</p>
     *
     * @param locationName 地点名（可空）
     *
     * @param sceneSize 场景边长（<=0 用默认 500）
     *
     * @return [x, y, z]，y 恒 0，x/z 落在以原点为中心的区间
     */
    public static double[] derivePosition(String locationName, int sceneSize) {
        int size = sceneSize > 0 ? sceneSize : DEFAULT_SCENE_SIZE;
        String key = normalizeKey(locationName);
        int h = key.hashCode();
        double x = mix(h, 0x9E3779B9, size) - size / 2.0;
        double z = mix(h, 0x85EBCA77, size) - size / 2.0;
        return new double[]{x, 0d, z};
    }

    /**
     * 地点名确定性派生朝向（弧度，[0, 2π)）。
     *
     * @param locationName 地点名（可空）
     *
     * @return 朝向弧度
     */
    public static double deriveHeading(String locationName) {
        String key = normalizeKey(locationName);
        int h = key.hashCode();
        long v = ((long) h * 31L + 0x165667B1L) ^ 0xBF58476D1CE4E5B9L;
        double u = (double) Long.remainderUnsigned(v, 1_000_003L) / 1_000_003L;
        return u * Math.PI * 2;
    }

    /** 地点名归一（null/空白回退「驻地」，保证派生稳定） */
    static String normalizeKey(String locationName) {
        if (locationName == null || locationName.isBlank()) {
            return "驻地";
        }
        return locationName.trim();
    }

    /** 哈希 → [EDGE_MARGIN, size-EDGE_MARGIN] 区间内的确定性坐标（两轴不同盐值避免对称） */
    private static double mix(int seed, int salt, int size) {
        long v = ((long) seed * 31L + salt) ^ (salt >>> 7);
        v = v * 0x9E3779B97F4A7C15L + 0xBF58476D1CE4E5B9L;
        double u = (double) Long.remainderUnsigned(v, 1_000_003L) / 1_000_003L;
        double span = Math.max(1, size - 2 * EDGE_MARGIN);
        return EDGE_MARGIN + u * span;
    }

    // ==================== 状态维护 ====================

    /**
     * 同步项目全部 NPC 沙盘状态（惰性建行 + 位置/动作刷新）。
     * <p>读接口（sandbox overview）与引擎调度调用：特殊 NPC + 普通人群都同步。</p>
     *
     * @param projectId 项目 ID
     */
    @Transactional
    public void syncProjectStates(Long projectId) {
        syncSpecialStates(projectId);
        syncCrowdStates(projectId, null);
        // 治理批次 0 · 卡3：存量 walking 卡死行惰性收敛（当前行动非移动 → 复位 idle）
        lazyConvergeMoveStates(projectId);
    }

    /**
     * 治理批次 0 · 卡3：对「当前行动非移动」的 walking 卡死行惰性修正为 idle（读侧收敛）。
     * <p>卡3 缺陷：{@link #onNpcMoved} 只写 "walking" 永不复位。读侧收敛口径——
     * 当前行动判定：特殊/普通 NPC 统一取 {@code actor_profile.current_activity}，
     * 行动文本是否为移动语义经映射表唯一来源 {@link NpcActivityStateService#toActivityState}
     * 判定（walk → 保持 walking；其余含 null/空白 → 复位 idle，复位值来自
     *
     * @param projectId 项目 ID
     */
    private void lazyConvergeMoveStates(Long projectId) {
        if (projectId == null) {
            return;
        }
        Map<String, NpcActivityStateService.ActivityUpdate> activityByRef =
                npcActivityStateService == null ? Map.of() : npcActivityStateService.activityByRef(projectId);
        for (ActorSandboxState st : stateRepository.findByProjectIdOrderByIdAsc(projectId)) {
            if (st == null || !NpcActivityStateService.MOVE_STATE_WALKING.equals(st.getMoveState())) {
                continue;
            }
            // 位置移动与地图生成系统 · M2：在途守卫——有 walking 移动行的角色 moveState 由
            // MovementService 维护（到点才复位 idle），此处不收敛（避免打断插值中的行走显示）
            if (movementService != null && movementService.isInTransit(projectId, st.getNpcRef())) {
                continue;
            }
            String action = null;
            try {
                ActorCharacter ch = characterRepository.findById(NpcRefs.idOf(st.getNpcRef())).orElse(null);
                if (ch != null && ch.getCurrentActivity() != null && !ch.getCurrentActivity().isBlank()) {
                    action = ch.getCurrentActivity();
                }
            } catch (Exception e) {
                // 角色行读取失败 → 回退 actor_state 侧行动判定
            }
            if (action == null || action.isBlank()) {
                NpcActivityStateService.ActivityUpdate act = activityByRef.get(st.getNpcRef());
                if (act != null && act.lastAction() != null && !act.lastAction().isBlank()) {
                    action = act.lastAction();
                }
            }
            if (action == null || action.isBlank()
                    || !NpcActivityStateService.STATE_WALK.equals(NpcActivityStateService.toActivityState(action))) {
                setMoveState(projectId, st.getNpcRef(), NpcActivityStateService.DEFAULT_ACTIVITY);
            }
        }
    }

    /**
     * 复位/设置某 NPC 的沙盘移动状态（治理批次 0 · 卡3：moveState 卡死修复的写侧复位点）。
     * <p>onNpcMoved 只写 "walking" 永不复位 ——动作结算（引擎每小时 dispatch
     * 中非移动类当前行动）与 {@link #syncProjectStates} 存量惰性收敛都经本方法复位；
     * 状态值语义对齐映射表唯一来源 {@link NpcActivityStateService#toMoveState}（卡3-B/卡4-B 共用）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用（统一 {@code npc:{id}}）
     *
     * @param state 目标移动状态（walking / idle；null/空白=忽略；无行=跳过）
     */
    @Transactional
    public void setMoveState(Long projectId, String npcRef, String state) {
        if (projectId == null || npcRef == null || npcRef.isBlank()
                || state == null || state.isBlank()) {
            return;
        }
        ActorSandboxState st = stateRepository.findByProjectIdAndNpcRef(projectId, npcRef).orElse(null);
        if (st == null) {
            return;
        }
        String s = state.trim();
        if (s.equals(st.getMoveState())) {
            return;
        }
        st.setMoveState(s);
        stateRepository.save(st);
        // S2 实时广播：移动状态变化推 npc:state（失败不影响落库）
        try {
            if (sandboxHub != null) {
                sandboxHub.publishNpcState(projectId, npcRef, st.getMoveState(),
                        st.getActionText(), st.getStatusText(), st.getGameSecond(),
                        internalState(projectId, npcRef));
            }
        } catch (Exception e) {
            log.warn("[沙盘状态] moveState 广播失败（不影响落库）: ref={} : {}", npcRef, e.getMessage());
        }
        log.debug("[沙盘状态] 移动状态复位：project={} ref={} state={}", projectId, npcRef, s);
    }

    /**
     * 同步特殊 NPC 状态行（惰性创建；位置随 current location 派生）。
     * <p>actor_profile 单表合并后本查询含全部类型，行内容按各自 type 定型（不再一律 special）。</p>
     *
     * @param projectId 项目 ID
     */
    @Transactional
    public void syncSpecialStates(Long projectId) {
        Long sceneId = sceneIdOf(projectId);
        List<ActorCharacter> chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        for (ActorCharacter ch : chars) {
            String ref = NpcRefs.of(ch.getId());
            // ：npc:{id} 不再编码类型，此处按意图 B 处理（类型取档案行内 type；单表合并后本查询含普通型）
            ensureState(projectId, sceneId, NpcRefs.normalizeType(ch.getType()), ref,
                    ch.getLocation(), ch.getCurrentActivity(), null);
        }
    }

    /**
     * 同步普通人群状态行（惰性创建；位置随 location 派生，动作取 lastAction/state）。
     * <p>actor_profile 单表合并后本查询含全部类型，行内容按各自 type 定型（不再一律 crowd）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 游戏时刻（秒，可空；人群调度时传入引擎推进到的游戏时刻）
     */
    @Transactional
    public void syncCrowdStates(Long projectId, Long gameSecond) {
        Long sceneId = sceneIdOf(projectId);
        List<ActorCharacter> npcs = ordinaryNpcRepository.findByProjectIdOrderByIdAsc(projectId);
        // ：作息运行态（state/last_action）已从 actor_profile 收敛到 actor_state —— 批量一次取全（循环内零查库）
        Map<String, NpcActivityStateService.ActivityUpdate> activityByRef =
                npcActivityStateService == null ? Map.of() : npcActivityStateService.activityByRef(projectId);
        for (ActorCharacter n : npcs) {
            String ref = NpcRefs.of(n.getId());
            NpcActivityStateService.ActivityUpdate act = activityByRef.get(ref);
            String action = act != null && act.lastAction() != null && !act.lastAction().isBlank()
                    ? act.lastAction() : describeState(act == null ? null : act.state());
            // ：npc:{id} 不再编码类型，此处按意图 B 处理（类型取档案行内 type；单表合并后本查询含特殊型）
            ensureState(projectId, sceneId, NpcRefs.normalizeType(n.getType()), ref,
                    n.getLocation(), action, gameSecond);
        }
    }

    /**
     * NPC 地点变更（move 行动挂钩）：更新坐标/朝向/移动状态/动作/游戏时刻并落库。
     * <p>S1 基础版语义：move 行动执行 → 位置直接更新为目标地点派生坐标（到达即落位），
     * move_state 按动作给 walking（到达后由后续轮事件驱动切回 idle）。
     * S2 实时通道：落库后按序广播 npc:moved（起点→终点）+ npc:arrived（到达）+ npc:state（动作/状态）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用（统一 {@code npc:{id}}）
     *
     * @param npcType NPC 类型（special / common）
     *
     * @param locationName 目标地点名
     *
     * @param actionText 动作描述（可空）
     *
     * @param gameSecond 游戏时刻（秒，可空）
     */
    @Transactional
    public void onNpcMoved(Long projectId, String npcRef, String npcType, String locationName,
                           String actionText, Long gameSecond) {
        if (projectId == null || npcRef == null || npcRef.isBlank()) {
            return;
        }
        ActorSandboxState st = stateRepository.findByProjectIdAndNpcRef(projectId, npcRef)
                .orElseGet(() -> {
                    ActorSandboxState created = new ActorSandboxState();
                    created.setProjectId(projectId);
                    created.setSceneId(sceneIdOf(projectId));
                    created.setNpcType(NpcRefs.normalizeType(npcType));
                    created.setNpcRef(npcRef);
                    return created;
                });
        double[] from = new double[]{st.getPosX() == null ? 0d : st.getPosX(),
                st.getPosY() == null ? 0d : st.getPosY(),
                st.getPosZ() == null ? 0d : st.getPosZ()};
        double[] pos = derivePosition(locationName, sceneSizeOf(st.getSceneId()));
        st.setPosX(pos[0]);
        st.setPosY(0d);
        st.setPosZ(pos[2]);
        st.setHeading(deriveHeading(locationName));
        st.setMoveState("walking");
        st.setActionText(truncate(actionText));
        st.setStatusText(truncate("位于" + normalizeKey(locationName)));
        if (gameSecond != null) {
            st.setGameSecond(gameSecond);
        }
        stateRepository.save(st);
        log.debug("[沙盘状态] NPC 移动落库：project={} ref={} loc={} pos=({},{})",
                projectId, npcRef, normalizeKey(locationName), (int) pos[0], (int) pos[2]);
        // S2 实时广播：moved（起点→终点）→ arrived（到达落位）→ state（动作/状态），失败不影响落库
        try {
            if (sandboxHub != null) {
                sandboxHub.publishNpcMoved(projectId, npcRef, from, pos, DEFAULT_MOVE_GAME_SEC);
                sandboxHub.publishNpcArrived(projectId, npcRef, pos, st.getHeading());
                sandboxHub.publishNpcState(projectId, npcRef, st.getMoveState(),
                        st.getActionText(), st.getStatusText(), st.getGameSecond(),
                        internalState(projectId, npcRef));
            }
        } catch (Exception e) {
            log.warn("[沙盘状态] move 广播失败（不影响落库）: ref={} : {}", npcRef, e.getMessage());
        }
        // 实时行为决策系统：刺激③「他角色靠近/到达」挂钩——NPC 到达目标地点后，
        // 通知同地点其他特殊 NPC 即时决策（移动者自身/人群不触发；异步执行不阻塞移动落库；
        // 未装配/异常仅告警不影响位置更新）
        try {
            if (realtimeDecisionService != null) {
                realtimeDecisionService.onNpcApproach(projectId, npcRef, locationName, gameSecond);
            }
        } catch (Exception e) {
            log.warn("[沙盘状态] 他角色靠近刺激挂钩失败（不影响落库）: ref={} : {}", npcRef, e.getMessage());
        }
    }

    /**
     * 惰性建行/刷新单条状态（sync 共用）。
     *
     * @param projectId 项目 ID
     *
     * @param sceneId 场景 ID（可空）
     *
     * @param npcType NPC 类型
     *
     * @param npcRef NPC 引用
     *
     * @param location 地点名（可空，回退「驻地」）
     *
     * @param actionText 动作描述（可空）
     *
     * @param gameSecond 游戏时刻（秒，可空）
     */
    @Transactional
    public void ensureState(Long projectId, Long sceneId, String npcType, String npcRef,
                            String location, String actionText, Long gameSecond) {
        if (projectId == null || npcRef == null || npcRef.isBlank()) {
            return;
        }
        ActorSandboxState st = stateRepository.findByProjectIdAndNpcRef(projectId, npcRef).orElse(null);
        boolean created = st == null;
        if (created) {
            st = new ActorSandboxState();
            st.setProjectId(projectId);
            st.setSceneId(sceneId);
            // ：npc:{id} 不再编码类型，此处按意图 B 处理（类型值入库前归一化：历史 crowd → common）
            st.setNpcType(NpcRefs.normalizeType(npcType));
            st.setNpcRef(npcRef);
        }
        // S2 去重：捕获更新前签名，内容无变化不广播（防 sync 反复调用刷屏）
        String before = stateSignature(st);
        // 位置移动与地图生成系统 · M2：在途守卫——角色有 walking 移动行时，坐标权威在
        // MovementService.tick（插值推进），此处跳过按 location 名派生坐标（避免把插值坐标
        // 打回目标地点哈希派生值，破坏连续移动）。未装配 movementService（单测）→ 保持既有派生。
        boolean inTransit = movementService != null && movementService.isInTransit(projectId, npcRef);
        if (!inTransit) {
            double[] pos = derivePosition(location, sceneSizeOf(sceneId));
            st.setPosX(pos[0]);
            st.setPosY(0d);
            st.setPosZ(pos[2]);
            st.setHeading(deriveHeading(location));
        }
        if (st.getMoveState() == null) {
            st.setMoveState("idle");
        }
        if (actionText != null && !actionText.isBlank()) {
            st.setActionText(truncate(actionText));
        }
        if (st.getStatusText() == null || st.getStatusText().isBlank()) {
            st.setStatusText(truncate("位于" + normalizeKey(location)));
        }
        if (gameSecond != null) {
            st.setGameSecond(gameSecond);
        }
        stateRepository.save(st);
        if (created) {
            log.debug("[沙盘状态] 初始化状态行：project={} ref={} loc={}", projectId, npcRef, normalizeKey(location));
        }
        // S2 实时广播：内容实际变化才推 npc:state（去重，防广播风暴）；新建行必推
        try {
            if (sandboxHub != null && (created || !before.equals(stateSignature(st)))) {
                sandboxHub.publishNpcState(projectId, npcRef, st.getMoveState(),
                        st.getActionText(), st.getStatusText(), st.getGameSecond(),
                        internalState(projectId, npcRef));
            }
        } catch (Exception e) {
            log.warn("[沙盘状态] state 广播失败（不影响落库）: ref={} : {}", npcRef, e.getMessage());
        }
    }

    // ==================== S2 实时广播（thought/join/leave/clock） ====================

    /**
     * NPC 内心想法写入并广播（npc:thought，T1 挂钩调用：反思/感知/决策）。
     * <p>去重：与已存 thought_text 相同则跳过（同内容不重复刷气泡）；写入后落库并广播。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用（统一 {@code npc:{id}}）
     *
     * @param text 想法文本（空白忽略）
     *
     * @param source 想法来源（derived/ai/reflection/perception）
     *
     * @param gameSecond 游戏时刻（秒，可空）
     */
    @Transactional
    public void publishThought(Long projectId, String npcRef, String text, String source, Long gameSecond) {
        if (projectId == null || npcRef == null || npcRef.isBlank() || text == null || text.isBlank()) {
            return;
        }
        String normalized = truncate(text.trim());
        ActorSandboxState st = stateRepository.findByProjectIdAndNpcRef(projectId, npcRef).orElse(null);
        if (st == null) {
            // 状态行不存在则惰性建行（跟随 sync 语义），仅记想法
            ensureState(projectId, sceneIdOf(projectId), refTypeOf(projectId, npcRef), npcRef, null, null, gameSecond);
            st = stateRepository.findByProjectIdAndNpcRef(projectId, npcRef).orElse(null);
            if (st == null) {
                return;
            }
        }
        // 去重：内容未变化不重复广播
        if (Objects.equals(normalized, st.getThoughtText())) {
            return;
        }
        st.setThoughtText(normalized);
        st.setThoughtSource(source == null || source.isBlank() ? "ai" : source);
        if (gameSecond != null) {
            st.setGameSecond(gameSecond);
        }
        stateRepository.save(st);
        log.debug("[沙盘状态] 想法写入：project={} ref={} src={} text={}", projectId, npcRef, source, normalized);
        try {
            if (sandboxHub != null) {
                sandboxHub.publishNpcThought(projectId, npcRef, normalized, st.getThoughtSource(), st.getGameSecond());
            }
        } catch (Exception e) {
            log.warn("[沙盘状态] thought 广播失败（不影响落库）: ref={} : {}", npcRef, e.getMessage());
        }
    }

    /**
     * NPC 进入场景广播（npc:join，角色/人群创建挂钩调用；并惰性建状态行）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用
     *
     * @param npcType NPC 类型（special/common；入库前经 NpcRefs 归一）
     *
     * @param name NPC 名称
     */
    @Transactional
    public void publishJoin(Long projectId, String npcRef, String npcType, String name) {
        if (projectId == null || npcRef == null || npcRef.isBlank()) {
            return;
        }
        ensureState(projectId, sceneIdOf(projectId), npcType, npcRef, null, null, null);
        try {
            if (sandboxHub != null) {
                sandboxHub.publishNpcJoin(projectId, npcRef, npcType, truncate(name));
            }
        } catch (Exception e) {
            log.warn("[沙盘状态] join 广播失败: ref={} : {}", npcRef, e.getMessage());
        }
    }

    /**
     * NPC 离开场景广播（npc:leave，角色/人群删除挂钩调用；并移除状态行）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用
     *
     * @param npcType NPC 类型（special/common；入库前经 NpcRefs 归一）
     *
     * @param name NPC 名称
     */
    @Transactional
    public void publishLeave(Long projectId, String npcRef, String npcType, String name) {
        if (projectId == null || npcRef == null || npcRef.isBlank()) {
            return;
        }
        try {
            if (sandboxHub != null) {
                sandboxHub.publishNpcLeave(projectId, npcRef, npcType, truncate(name));
            }
        } catch (Exception e) {
            log.warn("[沙盘状态] leave 广播失败: ref={} : {}", npcRef, e.getMessage());
        }
        // 离开场景：移除状态行（下次读接口惰性重建，避免幽灵行）
        stateRepository.findByProjectIdAndNpcRef(projectId, npcRef).ifPresent(stateRepository::delete);
    }

    /**
     * 世界时钟广播（clock，WorldEngine 推进 / WorldClockService 更新挂钩调用）。
     *
     * @param projectId 项目 ID
     *
     * @param paused 暂停态覆盖（0/1，可空=读时钟当前值）
     */
    public void publishClock(Long projectId, Integer paused) {
        try {
            if (sandboxHub != null) {
                sandboxHub.publishClock(projectId, paused);
            }
        } catch (Exception e) {
            log.warn("[沙盘状态] clock 广播失败: project={} : {}", projectId, e.getMessage());
        }
    }

    /** 状态行内容签名（去重比较用：位置/朝向/移动态/动作/状态/想法/游戏时刻）。 */
    static String stateSignature(ActorSandboxState st) {
        if (st == null) {
            return "";
        }
        return (st.getPosX() == null ? "" : st.getPosX())
                + "|" + (st.getPosZ() == null ? "" : st.getPosZ())
                + "|" + (st.getHeading() == null ? "" : st.getHeading())
                + "|" + (st.getMoveState() == null ? "" : st.getMoveState())
                + "|" + (st.getActionText() == null ? "" : st.getActionText())
                + "|" + (st.getStatusText() == null ? "" : st.getStatusText())
                + "|" + (st.getThoughtText() == null ? "" : st.getThoughtText())
                + "|" + (st.getGameSecond() == null ? "" : st.getGameSecond());
    }

    /** npcRef → npcType 解析。 */
    private String refTypeOf(Long projectId, String npcRef) {
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（"是否特殊型"查档案行）
        return npcTypeResolver != null ? npcTypeResolver.typeOf(projectId, npcRef) : NpcRefs.TYPE_SPECIAL;
    }

    // ==================== 读接口 ====================

    /**
     * 项目全部沙盘状态行（读接口）。
     *
     * @param projectId 项目 ID
     *
     * @return 状态行列表（按 id 升序）
     */
    @Transactional(readOnly = true)
    public List<ActorSandboxState> listStates(Long projectId) {
        return stateRepository.findByProjectIdOrderByIdAsc(projectId);
    }

    /** 项目主场景（首个场景；无则 null） */
    @Transactional(readOnly = true)
    public ActorSandboxScene mainScene(Long projectId) {
        return sceneRepository.findFirstByProjectIdOrderByIdAsc(projectId).orElse(null);
    }

    /**
     * 沙盘总览聚合（读接口 + SSE init 快照共用）：读前惰性同步状态行，返回
     * 场景列表 + 主场景 + 全部状态（含 NPC 名称）。
     *
     * @param projectId 项目 ID
     */
    @Transactional
    public SandboxOverviewVO overview(Long projectId) {
        syncProjectStates(projectId);
        SandboxOverviewVO vo = new SandboxOverviewVO();
        vo.setProjectId(projectId);
        List<ActorSandboxScene> scenes = sceneRepository.findByProjectIdOrderByCreatedAtAsc(projectId);
        vo.setScenes(scenes.stream().map(s -> SandboxSceneVO.of(s, parseLayout(s.getLayoutJson()))).toList());
        vo.setMainScene(scenes.isEmpty() ? null : SandboxSceneVO.of(scenes.get(0), parseLayout(scenes.get(0).getLayoutJson())));
        vo.setSceneCount(scenes.size());
        List<ActorSandboxState> states = listStates(projectId);
        Map<String, String> names = npcNameIndex(projectId);
        vo.setStates(states.stream().map(st -> {
            SandboxStateVO sv = SandboxStateVO.of(st);
            sv.setNpcName(names.getOrDefault(st.getNpcRef(), st.getNpcRef()));
            // ：内部状态富化（需求/心情/情绪/urgency → 前端需求条/气泡）
            enrichInternalState(projectId, sv);
            return sv;
        }).toList());
        vo.setStateCount(states.size());
        // S3：资产摆放聚合（服务未注入时返回空列表，保证 overview 结构稳定）
        if (scenePlacementService != null) {
            List<ScenePlacementVO> placements = scenePlacementService.listByProject(projectId);
            vo.setPlacements(placements);
            vo.setPlacementCount(placements.size());
        } else {
            vo.setPlacements(List.of());
            vo.setPlacementCount(0);
        }
        return vo;
    }

    /** NPC 引用 → 名称索引 */
    private Map<String, String> npcNameIndex(Long projectId) {
        Map<String, String> names = new LinkedHashMap<>();
        characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)
                .forEach(ch -> names.put(NpcRefs.of(ch.getId()), ch.getName()));
        ordinaryNpcRepository.findByProjectIdOrderByIdAsc(projectId)
                .forEach(n -> names.put(NpcRefs.of(n.getId()), n.getName()));
        return names;
    }

    // ====================：内部状态富化（actor_npc_state → SandboxStateVO / npc:state 载荷） ====================

    /**
     * 取 NPC 内部状态行。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用
     *
     * @return 内部状态行（null=不可用）
     */
    private com.holzyn.actor.domain.npc.state.entity.ActorNpcState internalState(Long projectId, String npcRef) {
        if (npcStateService == null || projectId == null || npcRef == null) {
            return null;
        }
        try {
            return npcStateService.stateFor(projectId, npcRef);
        } catch (Exception e) {
            log.debug("[沙盘状态] 内部状态读取失败（跳过富化）: ref={} : {}", npcRef, e.getMessage());
            return null;
        }
    }

    /**
     * 内部状态富化：把 actor_npc_state 的需求/心情/情绪/urgency 写入 SandboxStateVO
     * （前端需求条/情绪气泡数据源；状态行缺失时保持 null）。
     *
     * @param projectId 项目 ID
     *
     * @param sv 沙盘状态 VO（原地富化）
     */
    private void enrichInternalState(Long projectId, SandboxStateVO sv) {
        com.holzyn.actor.domain.npc.state.entity.ActorNpcState st = internalState(projectId, sv.getNpcRef());
        if (st == null) {
            return;
        }
        sv.setHunger(st.getHunger());
        sv.setThirst(st.getThirst());
        sv.setEnergy(st.getEnergy());
        sv.setBladder(st.getBladder());
        sv.setHealth(st.getHealth());
        sv.setMood(st.getMood());
        sv.setDominantEmotion(st.getDominantEmotion());
        sv.setEmotionIntensity(st.getEmotionIntensity());
        sv.setEmotionPleasure(st.getEmotionPleasure());
        sv.setEmotionArousal(st.getEmotionArousal());
        sv.setEmotionDominance(st.getEmotionDominance());
        sv.setUrgencyLevel(st.getUrgencyLevel());
        sv.setUrgencyText(st.getUrgencyText());
    }

    // ==================== 内部工具 ====================

    /** 项目主场景 ID（无场景返回 null） */
    private Long sceneIdOf(Long projectId) {
        ActorSandboxScene scene = mainScene(projectId);
        return scene == null ? null : scene.getId();
    }

    /** 场景边长（无场景/非法用默认 500） */
    private int sceneSizeOf(Long sceneId) {
        if (sceneId == null) {
            return DEFAULT_SCENE_SIZE;
        }
        return sceneRepository.findById(sceneId)
                .map(ActorSandboxScene::getSceneSize)
                .filter(s -> s != null && s > 0)
                .orElse(DEFAULT_SCENE_SIZE);
    }

    /** 普通人群状态 → 行动描述（lastAction 缺失时兜底） */
    private String describeState(String state) {
        if (state == null || state.isBlank()) {
            return "空闲";
        }
        return switch (state) {
            case "walk" -> "正在行走";
            case "stop" -> "驻足停留";
            case "talk" -> "与人交谈";
            case "rest" -> "歇息中";
            default -> "忙于日常事务";
        };
    }

    /** 截断超长字段 */
    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= FIELD_MAX ? s : s.substring(0, FIELD_MAX);
    }

    /**
     * 解析 layout_json → JSON 节点（S3 补全：场景 VO 携带给前端渲染像素地形/河路；解析失败/为空返回 null）。
     *
     * @param layoutJson 场景 layout_json 原文
     *
     * @return 解析后的 JsonNode（前端直接消费；不可用时 null）
     */
    private Object parseLayout(String layoutJson) {
        if (layoutJson == null || layoutJson.isBlank() || objectMapper == null) {
            return null;
        }
        try {
            return objectMapper.readTree(layoutJson);
        } catch (Exception e) {
            log.warn("[沙盘场景] layout_json 解析失败（前端将回退无布局渲染）：{}", e.getMessage());
            return null;
        }
    }
}
