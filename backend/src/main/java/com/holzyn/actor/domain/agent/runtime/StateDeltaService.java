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

package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.domain.agent.runtime.entity.ActorStateDelta;
import com.holzyn.actor.domain.agent.runtime.repository.ActorStateDeltaRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 决策与协调 决策 state delta 服务（，domain/agent/runtime）。
 * <p>职责：决策执行完成后生成显式状态变更记录
 * {@code actor_state_delta}（幂等键=decision_id=actor_plan.id）——changes_json 存
 * 行动/位置/状态变更快照；打断恢复（{@link #validityOf}）据此校验「原计划是否过时效、
 * 目标状态是否变化」；调试/后续决策经 {@link #list} 读取。</p>
 * <p>语义：delta 仅记「决策驱动的变更」，不替代状态权威源（actor_state/actor_character
 * 仍是唯一真相）；位置变更=决策 target/params.move.to 落点；状态变更=执行前后
 * actor_state 行字段差值（hunger/thirst/energy/mood/health…，执行前快照由
 * AgentActionService 传入，前后缺失则仅记行动字段）。</p>
 * <p>消费：<ul>
 * <li>打断恢复校验：InterruptRecoveryService 恢复决策上下文注入 delta 时效性（过期=原计划失效），
 * 共性框架兜底按 delta 时效判 abandon；</li>
 * <li>调试/后续读取：GET /decision/state-deltas + 链式/后续决策可查最近 delta。</li>
 * </ul></p>
 */
@Slf4j
@Service
public class StateDeltaService {

    /** 状态字段白名单（执行前后 diff 比对用；缺失跳过） */
    private static final List<String> STATE_FIELDS = List.of(
            "hunger", "thirst", "energy", "bladder", "health", "mood", "hygiene", "social", "fun", "comfort");

    private final ActorStateDeltaRepository repository;
    private final RealtimeDecisionProperties realtimeProperties;
    private final ObjectMapper objectMapper;

    /** 兼容构造（测试：不传配置回退默认）。 */
    public StateDeltaService(ActorStateDeltaRepository repository, ObjectMapper objectMapper) {
        this(repository, null, objectMapper);
    }

    /** 全量构造（Spring @Autowired）。 */
    @Autowired
    public StateDeltaService(ActorStateDeltaRepository repository,
                             RealtimeDecisionProperties realtimeProperties,
                             ObjectMapper objectMapper) {
        this.repository = repository;
        this.realtimeProperties = realtimeProperties;
        this.objectMapper = objectMapper;
    }

    // ==================== 落库（幂等） ====================

    /**
     * 记录一次决策的 state delta（幂等键=decisionId）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 角色引用（special:{id}）
     *
     * @param decisionId 决策 ID（actor_plan.id，uk 幂等）
     *
     * @param decision 决策 JSON 节点（action/type/target/reason/urgency/duration/params）
     *
     * @param locationBefore 执行前角色位置（可空）
     *
     * @param stateBefore 执行前 actor_state 行（可空；与 stateAfter 成对才有状态差值）
     *
     * @param stateAfter 执行后 actor_state 行（可空）
     *
     * @param gameSecond 决策产生游戏秒（可空）
     *
     * @return 记录（已存在=幂等返回既有行）
     */
    public ActorStateDelta record(Long projectId, String npcRef, Long decisionId, JsonNode decision,
                                  String locationBefore, ActorNpcState stateBefore, ActorNpcState stateAfter,
                                  Long gameSecond) {
        if (projectId == null || npcRef == null || decisionId == null) {
            return null;
        }
        if (!enabled()) {
            return null;
        }
        try {
            // 幂等（uk decision_id 防重放）
            java.util.Optional<ActorStateDelta> existing = repository.findByDecisionId(decisionId);
            if (existing.isPresent()) {
                return existing.get();
            }
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("action", text(decision, "action"));
            changes.put("type", text(decision, "type"));
            changes.put("target", text(decision, "target"));
            changes.put("reason", text(decision, "reason"));
            changes.put("urgency", decision == null ? 0 : decision.path("urgency").asInt(0));
            changes.put("duration", decision == null ? 0 : decision.path("duration").asInt(0));
            String locationTo = moveTo(decision);
            if (locationTo != null && !locationTo.isBlank()) {
                changes.put("locationTo", locationTo);
            }
            if (locationBefore != null && !locationBefore.isBlank()) {
                changes.put("locationBefore", locationBefore);
            }
            Map<String, Object> stateDeltas = stateDeltas(stateBefore, stateAfter);
            if (!stateDeltas.isEmpty()) {
                changes.put("stateDeltas", stateDeltas);
            }
            ActorStateDelta delta = new ActorStateDelta();
            delta.setProjectId(projectId);
            delta.setNpcRef(npcRef);
            delta.setDecisionId(decisionId);
            delta.setChangesJson(toJson(changes));
            delta.setGameSecond(gameSecond);
            ActorStateDelta saved = repository.save(delta);
            log.debug("[StateDelta] 决策变更已记录: project={} ref={} decision={} 变化字段={}",
                    projectId, npcRef, decisionId, stateDeltas.keySet());
            return saved;
        } catch (Exception e) {
            log.warn("[StateDelta] 记录失败（不影响行动主流程）: ref={} decision={} : {}",
                    npcRef, decisionId, e.getMessage());
            return null;
        }
    }

    // ==================== 恢复校验（打断恢复消费） ====================

    /**
     * 时效性校验结果（恢复决策上下文/共性框架兜底用）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 角色引用
     *
     * @param gameSecond 当前游戏秒（null=视为不过期）
     *
     * @return 校验结果（无 delta=null；delta 未过期/无时效窗口=valid=true）
     */
    public Validity validityOf(Long projectId, String npcRef, Long gameSecond) {
        if (!enabled() || projectId == null || npcRef == null) {
            return null;
        }
        try {
            List<ActorStateDelta> list = repository.findByProjectIdAndNpcRefOrderByIdDesc(projectId, npcRef);
            if (list.isEmpty()) {
                return null;
            }
            ActorStateDelta latest = list.get(0);
            int expireMinutes = expireMinutes();
            long expireAt = latest.getGameSecond() == null ? Long.MAX_VALUE
                    : latest.getGameSecond() + Math.max(0, expireMinutes) * 60L;
            boolean expired = expireMinutes > 0 && gameSecond != null && gameSecond >= expireAt;
            return new Validity(!expired, expired, latest);
        } catch (Exception e) {
            log.debug("[StateDelta] 时效性校验失败（视为无 delta）: ref={} : {}", npcRef, e.getMessage());
            return null;
        }
    }

    /** 时效性结果（valid=true 原计划仍有效；expired=true 已过时效；delta=最近一条）。 */
    public record Validity(boolean valid, boolean expired, ActorStateDelta delta) {
    }

    /**
     * 恢复上下文文本（打断恢复决策用）：最近 delta 的「原计划 + 时效 + 目标状态」摘要；
     * 无 delta/失败返回空串（调用方不注入，行为回到无 delta 时代）。
     */
    public String resumeContext(Long projectId, String npcRef, Long gameSecond) {
        Validity v = validityOf(projectId, npcRef, gameSecond);
        if (v == null || v.delta() == null) {
            return "";
        }
        Map<String, Object> changes = parseChanges(v.delta().getChangesJson());
        String action = String.valueOf(changes.getOrDefault("action", ""));
        String target = String.valueOf(changes.getOrDefault("target", ""));
        StringBuilder sb = new StringBuilder("你此前的计划（delta 校验）：");
        sb.append(action.isBlank() ? "（未记录具体行动）" : action.trim());
        if (!target.isBlank() && !"null".equals(target)) {
            sb.append("，目标：").append(target.trim());
        }
        if (v.expired()) {
            sb.append("——该计划已过时效（超过 ").append(expireMinutes()).append(" 游戏分钟未执行完）");
        } else {
            sb.append("——该计划仍在时效内");
        }
        return sb.toString();
    }

    // ==================== 读取（调试/后续决策） ====================

    /** 最近 delta 列表（按角色过滤；角色空=全项目；limit 截断）。 */
    public List<ActorStateDelta> list(Long projectId, String npcRef, Integer limit) {
        List<ActorStateDelta> all = npcRef == null || npcRef.isBlank()
                ? repository.findByProjectIdOrderByIdDesc(projectId)
                : repository.findByProjectIdAndNpcRefOrderByIdDesc(projectId, npcRef);
        if (limit != null && limit > 0 && all.size() > limit) {
            return all.subList(0, limit);
        }
        return all;
    }

    // ==================== 私有辅助 ====================

    /** 执行前后状态差值（字段白名单，前后同值/缺失跳过）。 */
    private Map<String, Object> stateDeltas(ActorNpcState before, ActorNpcState after) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (before == null || after == null) {
            return out;
        }
        for (String f : STATE_FIELDS) {
            Integer b = readField(before, f);
            Integer a = readField(after, f);
            if (b == null || a == null || b.equals(a)) {
                continue;
            }
            Map<String, Object> pair = new LinkedHashMap<>();
            pair.put("from", b);
            pair.put("to", a);
            out.put(f, pair);
        }
        return out;
    }

    /** 反射读取 actor_state 数值字段（字段缺失返回 null）。 */
    private static Integer readField(ActorNpcState st, String field) {
        try {
            Object v = ActorNpcState.class.getMethod("get" + Character.toUpperCase(field.charAt(0))
                    + field.substring(1)).invoke(st);
            return v instanceof Number n ? n.intValue() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 决策落点（params.move.to 优先，其次 target）。 */
    private static String moveTo(JsonNode decision) {
        if (decision == null) {
            return "";
        }
        String move = decision.path("params").path("move").path("to").asText("");
        if (!move.isBlank()) {
            return move;
        }
        return decision.path("target").asText("");
    }

    /** 决策文本字段（null/缺失回退空串）。 */
    private static String text(JsonNode decision, String field) {
        if (decision == null) {
            return "";
        }
        return decision.path(field).asText("");
    }

    /** 开关（realtime-decision.state-delta.enabled）。 */
    private boolean enabled() {
        return realtimeProperties == null || realtimeProperties.getStateDelta() == null
                || realtimeProperties.getStateDelta().isEnabled();
    }

    /** 时效窗口（游戏分钟；配置缺失/<=0=不设时效）。 */
    private int expireMinutes() {
        if (realtimeProperties == null || realtimeProperties.getStateDelta() == null) {
            return 120;
        }
        return realtimeProperties.getStateDelta().getExpireGameMinutes();
    }

    /** changes_json 解析（失败回退空 Map）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseChanges(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Object v = objectMapper.readValue(json, Map.class);
            return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Map → JSON（失败回退 "{}"）。 */
    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 变更字段名列表（调试展示用）。 */
    public static List<String> changedFields(String changesJson, ObjectMapper mapper) {
        List<String> out = new ArrayList<>();
        if (changesJson == null || changesJson.isBlank()) {
            return out;
        }
        try {
            Object v = mapper.readValue(changesJson, Map.class);
            if (v instanceof Map<?, ?> m) {
                m.forEach((k, val) -> {
                    if (!"action".equals(k) && !"type".equals(k) && !"target".equals(k)
                            && !"reason".equals(k) && !"urgency".equals(k) && !"duration".equals(k)) {
                        out.add(String.valueOf(k));
                    }
                });
            }
        } catch (Exception ignored) {
            // 解析失败返回空
        }
        return out;
    }
}
