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

package com.holzyn.actor.domain.npc.state.service;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.repository.ActorNpcStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NPC 作息运行态访问器。
 * <p><b>存在理由（唯一家原则）</b>：{@code state} / {@code last_action} / {@code daily_plan_json}
 * 三个运行态字段在 之前挂在 {@code actor_profile_ordinary}（普通型 NPC 表）上；该表已<b>物理废弃</b>，
 * 其数据与语义迁入 {@code actor_state} 的 {@code activity_state} / {@code last_action} /
 * {@code daily_plan_json} 三列（见 {@link ActorNpcState}）。主表 {@code actor_profile} 只保留档案与
 * 路由/结构列，<b>不再承载运行态</b>——因此任何读写作息状态机的地方都必须经过本访问器，
 * 不得再从主表实体上取这三个字段。</p>
 * <p><b>四项纪律</b>：</p>
 * <ol>
 * <li><b>零阻断</b>：读失败/写失败都只记 {@code debug} 日志并返回默认值（读）或静默跳过（写）——
 * 作息状态机是调度主流程的旁路增强，任何一次状态行读写异常都不得中断人群调度/世界推进；</li>
 * <li><b>批量优先</b>：调度循环一次取全（{@link #activityStateByRef} / {@link #dailyPlanByRef} /
 * {@link #activityByRef}）、循环内只用内存 Map、循环结束一次性写回
 * （{@link #setActivities} / {@link #setDailyPlans}）——避免逐 NPC 查库（500 居民 × 每小时 tick）；</li>
 * <li><b>建行口径</b>：无行时先建行（{@code project_id + npc_ref} 唯一），新建行的
 * {@code npc_type} 固定为 {@link NpcRefs#TYPE_COMMON}（本访问器的消费方是普通型人群作息状态机）；
 * 已存在的行<b>原样沿用</b>其 {@code npc_type}（不覆盖状态模块自己建的行）；</li>
 * <li><b>类型归一</b>：批量读取只排除明确的 {@code special} 行；{@code crowd}（历史写法）/空值
 * 一律按 {@link NpcRefs#normalizeType} 归一为普通型纳入口径，避免与状态模块
 * （{@code NpcStateService}）自建行错位而漏读。</li>
 * </ol>
 * <p><b>统一身份</b>：{@code npcRef} 一律为 {@link NpcRefs#of(Long)} 产出的 {@code npc:{actor_profile.id}}，
 * 本类内不拼接任何前缀字面量。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NpcActivityStateService {

    /** 无状态行/状态列为空时的默认活动状态（空闲） */
    public static final String DEFAULT_ACTIVITY = "idle";

    /** 移动语义活动状态（activity_state 五值之一） */
    public static final String STATE_WALK = "walk";

    /** 移动语义沙盘投影（move_state 二值之一） */
    public static final String MOVE_STATE_WALKING = "walking";

    //
    // 枚举映射表（治理批次 0 · 卡4-A②：activityState ↔ moveState 唯一来源）
    //

    /**
     * 引擎活动文本 → activityState 映射（卡4-A②，映射表唯一来源）。
     * <p>引擎程序化/决策活动文本为自然语言（如「前往集市」「深夜在驻地歇息就寝」），
     * 映射到 {@code actor_state.activity_state} 五值枚举（idle/walk/stop/talk/rest）：
     * 移动语义（前往/赶去/走去/以「去」开头）→ {@code walk}；歇息/就寝/休息/睡觉/入睡 → {@code rest}；
     * 闲谈/交谈/聊天/寒暄 → {@code talk}；其余（含日常事务）→ {@code idle}。null/空白 → {@code idle}。</p>
     *
     * @param activityText 引擎活动文本（可空）
     *
     * @return activity_state 枚举值（idle/walk/stop/talk/rest）
     */
    public static String toActivityState(String activityText) {
        if (activityText == null) {
            return DEFAULT_ACTIVITY;
        }
        String t = activityText.trim();
        if (t.isEmpty()) {
            return DEFAULT_ACTIVITY;
        }
        if (t.contains("前往") || t.contains("赶去") || t.contains("走去") || t.startsWith("去")) {
            return STATE_WALK;
        }
        if (t.contains("歇息就寝") || t.contains("就寝") || t.contains("休息")
                || t.contains("睡觉") || t.contains("入睡")) {
            return "rest";
        }
        if (t.contains("闲谈") || t.contains("交谈") || t.contains("聊天") || t.contains("寒暄")) {
            return "talk";
        }
        return DEFAULT_ACTIVITY;
    }

    /**
     * activityState → moveState 投影（卡4-A②/卡3-B，映射表唯一来源）。
     * <p>{@code actor_state.activity_state} 五值 → 沙盘 {@code move_state} 二值：
     * 卡3 沙盘复位（G-MOVE-RESET）与卡4-B moveState 投影共用本表，禁止散落第二份映射。</p>
     *
     * @param activityState activity_state 枚举值（可空）
     *
     * @return 沙盘 move_state（walking / idle）
     */
    public static String toMoveState(String activityState) {
        return STATE_WALK.equals(activityState == null ? null : activityState.trim())
                ? MOVE_STATE_WALKING : DEFAULT_ACTIVITY;
    }

    /** NPC 内部状态仓库（actor_state，运行态唯一家） */
    private final ActorNpcStateRepository stateRepository;

    //
    // 读（单条）
    //

    /**
     * 读取某 NPC 的作息活动状态（{@code actor_state.activity_state}）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 统一身份引用（{@code npc:{id}}）
     *
     * @return 活动状态（idle/walk/stop/talk/rest）；<b>无行或状态列空 → {@link #DEFAULT_ACTIVITY}</b>；
     * 读取异常 → 默认值（debug 日志，零阻断）
     */
    public String activityState(Long projectId, String npcRef) {
        try {
            ActorNpcState st = rowOf(projectId, npcRef);
            return normalizeState(st == null ? null : st.getActivityState());
        } catch (Exception e) {
            log.debug("[作息运行态] 读取活动状态失败（返回默认 {}）: project={} ref={} : {}",
                    DEFAULT_ACTIVITY, projectId, npcRef, e.getMessage());
            return DEFAULT_ACTIVITY;
        }
    }

    /**
     * 读取某 NPC 的运行态快照（活动状态 + 最近行动，一次查询取两列）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 统一身份引用
     *
     * @return 快照（无行 → {@code {idle, null}}）；读取异常 → 默认快照（debug 日志，零阻断）
     */
    public ActivityUpdate activityOf(Long projectId, String npcRef) {
        try {
            ActorNpcState st = rowOf(projectId, npcRef);
            if (st == null) {
                return new ActivityUpdate(DEFAULT_ACTIVITY, null);
            }
            return new ActivityUpdate(normalizeState(st.getActivityState()), st.getLastAction());
        } catch (Exception e) {
            log.debug("[作息运行态] 读取运行态快照失败（返回默认）: project={} ref={} : {}",
                    projectId, npcRef, e.getMessage());
            return new ActivityUpdate(DEFAULT_ACTIVITY, null);
        }
    }

    /**
     * 读取某 NPC 的当日计划 JSON（{@code actor_state.daily_plan_json}）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 统一身份引用
     *
     * @return 计划 JSON；<b>无行/无计划 → null</b>（调用方据此判定"计划缺失，需重新生成"）；
     * 读取异常 → null（debug 日志，零阻断）
     */
    public String dailyPlanJson(Long projectId, String npcRef) {
        try {
            ActorNpcState st = rowOf(projectId, npcRef);
            String plan = st == null ? null : st.getDailyPlanJson();
            return plan == null || plan.isBlank() ? null : plan;
        } catch (Exception e) {
            log.debug("[作息运行态] 读取当日计划失败（返回 null）: project={} ref={} : {}",
                    projectId, npcRef, e.getMessage());
            return null;
        }
    }

    //
    // 读（批量：调度循环一次取全）
    //

    /**
     * 批量读取项目内普通型 NPC 的活动状态（{@code npcRef → activity_state}）。
     * <p>调度循环（逐小时推进/逐归属调度）应先调本方法取一次，循环内只读内存 Map，
     * 避免逐 NPC 查库。</p>
     *
     * @param projectId 项目 ID
     *
     * @return npcRef → 活动状态（无行的 NPC 不出现在 Map 中，调用方按 {@link #DEFAULT_ACTIVITY} 兜底）；
     * 查询异常 → 空 Map（debug 日志，零阻断）
     */
    public Map<String, String> activityStateByRef(Long projectId) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (ActorNpcState st : commonRows(projectId)) {
                if (st.getNpcRef() == null || st.getNpcRef().isBlank()) {
                    continue;
                }
                out.put(st.getNpcRef(), normalizeState(st.getActivityState()));
            }
            return out;
        } catch (Exception e) {
            log.debug("[作息运行态] 批量读取活动状态失败（返回空 Map）: project={} : {}", projectId, e.getMessage());
            return Map.of();
        }
    }

    /**
     * 批量读取项目内普通型 NPC 的运行态快照（{@code npcRef → {state, lastAction}}）。
     * <p>列表页/调试页一次取全（state 与 lastAction 同表两列，单次查询即可回填整页 VO）。</p>
     *
     * @param projectId 项目 ID
     *
     * @return npcRef → 运行态快照；查询异常 → 空 Map（debug 日志，零阻断）
     */
    public Map<String, ActivityUpdate> activityByRef(Long projectId) {
        Map<String, ActivityUpdate> out = new LinkedHashMap<>();
        try {
            for (ActorNpcState st : commonRows(projectId)) {
                if (st.getNpcRef() == null || st.getNpcRef().isBlank()) {
                    continue;
                }
                out.put(st.getNpcRef(), new ActivityUpdate(normalizeState(st.getActivityState()), st.getLastAction()));
            }
            return out;
        } catch (Exception e) {
            log.debug("[作息运行态] 批量读取运行态快照失败（返回空 Map）: project={} : {}", projectId, e.getMessage());
            return Map.of();
        }
    }

    /**
     * 批量读取项目内普通型 NPC 的当日计划（{@code npcRef → daily_plan_json}）。
     * <p>调度循环用它判定"当日计划是否已存在/是否属于当前游戏日"，无计划者才重新生成。</p>
     *
     * @param projectId 项目 ID
     *
     * @return npcRef → 计划 JSON（无计划的行不进入 Map）；查询异常 → 空 Map（debug 日志，零阻断）
     */
    public Map<String, String> dailyPlanByRef(Long projectId) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (ActorNpcState st : commonRows(projectId)) {
                if (st.getNpcRef() == null || st.getNpcRef().isBlank()) {
                    continue;
                }
                String plan = st.getDailyPlanJson();
                if (plan != null && !plan.isBlank()) {
                    out.put(st.getNpcRef(), plan);
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("[作息运行态] 批量读取当日计划失败（返回空 Map）: project={} : {}", projectId, e.getMessage());
            return Map.of();
        }
    }

    //
    // 写（单条 / 批量）
    //

    /**
     * 写入某 NPC 的作息活动状态与最近行动（无行 → 先建行）。
     * <p>状态为空串时<b>不改动</b>既有活动状态（只刷新最近行动）；行动为空串时清空最近行动。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 统一身份引用
     *
     * @param state 活动状态（idle/walk/stop/talk/rest；空=不改动）
     *
     * @param lastAction 最近行动描述（可空）
     */
    @Transactional
    public void setActivity(Long projectId, String npcRef, String state, String lastAction) {
        if (!writable(projectId, npcRef)) {
            return;
        }
        try {
            ActorNpcState st = upsertRow(projectId, npcRef, null);
            if (st == null) {
                return;
            }
            String s = state == null ? null : state.trim();
            if (s != null && !s.isEmpty()) {
                st.setActivityState(s);
            }
            st.setLastAction(blankToNull(lastAction));
            stateRepository.save(st);
        } catch (Exception e) {
            log.debug("[作息运行态] 写入活动状态失败（不阻断调度）: project={} ref={} : {}",
                    projectId, npcRef, e.getMessage());
        }
    }

    /**
     * 只刷新某 NPC 的最近行动描述（<b>不改变</b>作息活动状态：宏观事件标记等"语义改写"场景用）。
     * <p>无行 → 先建行（活动状态列取实体默认 {@code idle}，等价于"该 NPC 尚未被状态机推进过"）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 统一身份引用
     *
     * @param lastAction 最近行动描述（可空，空=清空）
     */
    @Transactional
    public void setLastAction(Long projectId, String npcRef, String lastAction) {
        if (!writable(projectId, npcRef)) {
            return;
        }
        try {
            ActorNpcState st = upsertRow(projectId, npcRef, null);
            if (st == null) {
                return;
            }
            st.setLastAction(blankToNull(lastAction));
            stateRepository.save(st);
        } catch (Exception e) {
            log.debug("[作息运行态] 写入最近行动失败（不阻断主流程）: project={} ref={} : {}",
                    projectId, npcRef, e.getMessage());
        }
    }

    /**
     * 批量写回作息活动状态（调度循环结束一次性写，避免逐 NPC 查库/逐条 flush）。
     * <p>实现口径：先一次性载入项目未删状态行建索引，命中的行就地更新；
     * 未命中的 ref 走单条兜底查询（覆盖软删行等边界，避免唯一键 {@code project_id + npc_ref} 冲突），
     * 仍无行才新建。</p>
     *
     * @param projectId 项目 ID
     *
     * @param updates npcRef → 运行态快照（state 为空=不改状态，仅刷新 lastAction）
     */
    @Transactional
    public void setActivities(Long projectId, Map<String, ActivityUpdate> updates) {
        if (projectId == null || updates == null || updates.isEmpty()) {
            return;
        }
        try {
            Map<String, ActorNpcState> cache = indexRows(projectId);
            List<ActorNpcState> dirty = new ArrayList<>();
            for (Map.Entry<String, ActivityUpdate> e : updates.entrySet()) {
                String ref = e.getKey();
                ActivityUpdate u = e.getValue();
                if (ref == null || ref.isBlank() || u == null) {
                    continue;
                }
                ActorNpcState st = upsertRow(projectId, ref, cache);
                if (st == null) {
                    continue;
                }
                if (u.state() != null && !u.state().isBlank()) {
                    st.setActivityState(u.state().trim());
                }
                st.setLastAction(blankToNull(u.lastAction()));
                dirty.add(st);
            }
            if (!dirty.isEmpty()) {
                stateRepository.saveAll(dirty);
            }
        } catch (Exception e) {
            log.debug("[作息运行态] 批量写入活动状态失败（不阻断调度）: project={} : {}", projectId, e.getMessage());
        }
    }

    /**
     * 写入某 NPC 的当日计划 JSON（无行 → 先建行）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 统一身份引用
     *
     * @param json 计划 JSON（{@code {date_game_day, items:[{hour,state,action}]}}；空=清空）
     */
    @Transactional
    public void setDailyPlanJson(Long projectId, String npcRef, String json) {
        if (!writable(projectId, npcRef)) {
            return;
        }
        try {
            ActorNpcState st = upsertRow(projectId, npcRef, null);
            if (st == null) {
                return;
            }
            st.setDailyPlanJson(blankToNull(json));
            stateRepository.save(st);
        } catch (Exception e) {
            log.debug("[作息运行态] 写入当日计划失败（不阻断调度）: project={} ref={} : {}",
                    projectId, npcRef, e.getMessage());
        }
    }

    /**
     * 批量写回当日计划（计划生成循环结束一次性写：一次载入 + 一次 saveAll）。
     *
     * @param projectId 项目 ID
     *
     * @param planJsonByRef npcRef → 计划 JSON（值空=清空该 NPC 计划）
     */
    @Transactional
    public void setDailyPlans(Long projectId, Map<String, String> planJsonByRef) {
        if (projectId == null || planJsonByRef == null || planJsonByRef.isEmpty()) {
            return;
        }
        try {
            Map<String, ActorNpcState> cache = indexRows(projectId);
            List<ActorNpcState> dirty = new ArrayList<>();
            for (Map.Entry<String, String> e : planJsonByRef.entrySet()) {
                String ref = e.getKey();
                if (ref == null || ref.isBlank()) {
                    continue;
                }
                ActorNpcState st = upsertRow(projectId, ref, cache);
                if (st == null) {
                    continue;
                }
                st.setDailyPlanJson(blankToNull(e.getValue()));
                dirty.add(st);
            }
            if (!dirty.isEmpty()) {
                stateRepository.saveAll(dirty);
            }
        } catch (Exception e) {
            log.debug("[作息运行态] 批量写入当日计划失败（不阻断调度）: project={} : {}", projectId, e.getMessage());
        }
    }

    //
    // 内部工具
    //

    /**
     * 运行态快照（活动状态 + 最近行动）：VO 回填与调度循环写回的最小载体。
     *
     * @param state 活动状态（idle/walk/stop/talk/rest）
     *
     * @param lastAction 最近行动描述（可空）
     */
    public record ActivityUpdate(String state, String lastAction) {
    }

    /** 单行查询（入参非法返回 null） */
    private ActorNpcState rowOf(Long projectId, String npcRef) {
        if (projectId == null || npcRef == null || npcRef.isBlank()) {
            return null;
        }
        return stateRepository.findByProjectIdAndNpcRef(projectId, npcRef.trim()).orElse(null);
    }

    /**
     * 项目内"普通型"状态行（批量读口径）：排除明确的 {@code special} 行；
     * {@code crowd}（历史写法）与空 {@code npc_type} 纳入（类型未定，漏读代价高于多读）。
     */
    private List<ActorNpcState> commonRows(Long projectId) {
        if (projectId == null) {
            return List.of();
        }
        return stateRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0).stream()
                .filter(NpcActivityStateService::isCommonRow)
                .toList();
    }

    /** 是否普通型状态行（含历史 crowd 写法与空类型；special 明确排除） */
    private static boolean isCommonRow(ActorNpcState st) {
        if (st == null) {
            return false;
        }
        String type = st.getNpcType();
        if (type == null || type.isBlank()) {
            return true;
        }
        return NpcRefs.isCommonType(type);
    }

    /** 批量写前的行索引（npcRef → 行；同 ref 多行时首行胜出） */
    private Map<String, ActorNpcState> indexRows(Long projectId) {
        Map<String, ActorNpcState> cache = new LinkedHashMap<>();
        for (ActorNpcState st : stateRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
            if (st.getNpcRef() != null && !st.getNpcRef().isBlank()) {
                cache.putIfAbsent(st.getNpcRef(), st);
            }
        }
        return cache;
    }

    /**
     * 取行或建行（写入路径唯一入口）。
     * <p>命中缓存/库内已有行 → 原样返回（沿用其 {@code npc_type}，不覆盖状态模块自建行）；
     * 未命中 → 走单条兜底查询（软删行/未入缓存）；仍无 → 新建行
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 统一身份引用
     *
     * @param cache 批量索引（可空=单条路径，直接查库）
     *
     * @return 可写状态行
     */
    private ActorNpcState upsertRow(Long projectId, String npcRef, Map<String, ActorNpcState> cache) {
        String ref = npcRef.trim();
        ActorNpcState st = cache == null ? null : cache.get(ref);
        if (st == null) {
            st = stateRepository.findByProjectIdAndNpcRef(projectId, ref).orElse(null);
        }
        if (st != null) {
            if (cache != null) {
                cache.put(ref, st);
            }
            return st;
        }
        ActorNpcState created = new ActorNpcState();
        created.setProjectId(projectId);
        created.setNpcRef(ref);
        // 本访问器服务普通型人群作息状态机：新建行类型固定 common（历史 crowd 由 NpcRefs 归一化读取）
        created.setNpcType(NpcRefs.TYPE_COMMON);
        created.setActivityState(DEFAULT_ACTIVITY);
        if (cache != null) {
            cache.put(ref, created);
        }
        return created;
    }

    /** 状态列归一（空 → {@link #DEFAULT_ACTIVITY}） */
    private static String normalizeState(String state) {
        return state == null || state.isBlank() ? DEFAULT_ACTIVITY : state.trim();
    }

    /** 空白 → null（存库为 NULL，避免空串与 NULL 双语义） */
    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 写路径入参守卫（项目/引用非法直接跳过，不记日志——调用方数据问题不算异常） */
    private static boolean writable(Long projectId, String npcRef) {
        return projectId != null && npcRef != null && !npcRef.isBlank();
    }
}